package com.example.meditimer.data

import android.content.Context
import org.json.JSONArray

class MedicationRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("meditimer_data", Context.MODE_PRIVATE)

    fun getMedications(): List<Medication> = parseArray("medications") { Medication.fromJson(it) }.sortedBy { it.name.lowercase() }

    fun getMedication(id: Long): Medication? = getMedications().firstOrNull { it.id == id }

    fun upsertMedication(medication: Medication) {
        val list = getMedications().toMutableList()
        val idx = list.indexOfFirst { it.id == medication.id }
        if (idx >= 0) list[idx] = medication else list.add(medication)
        saveArray("medications", list.map { it.toJson() })
    }

    fun deleteMedication(id: Long) {
        val med = getMedication(id)
        if (med != null) {
            val enriched = getIntakes().map {
                if (it.medicationId == id && it.medicationName.isBlank()) it.copy(medicationName = med.name) else it
            }
            saveArray("intakes", enriched.map { it.toJson() })
        }
        saveArray("medications", getMedications().filterNot { it.id == id }.map { it.toJson() })
        saveArray("countdowns", getCountdownsRaw().filterNot { it.medicationId == id }.map { it.toJson() })
        saveArray("snoozes", getPendingSnoozes().filterNot { it.medicationId == id }.map { it.toJson() })
    }

    fun getIntakes(): List<IntakeEvent> = parseArray("intakes") { IntakeEvent.fromJson(it) }

    fun recordIntake(event: IntakeEvent) {
        val list = getIntakes().filterNot { it.key == event.key }.toMutableList()
        list.add(event)
        val trimmed = list.sortedByDescending { it.takenAtMillis }.take(5000)
        saveArray("intakes", trimmed.map { it.toJson() })
    }


    fun updateIntake(event: IntakeEvent) {
        val list = getIntakes().toMutableList()
        val idx = list.indexOfFirst { it.id == event.id }
        if (idx >= 0) {
            list[idx] = event
        } else {
            list.add(event)
        }
        saveArray("intakes", list.sortedByDescending { it.takenAtMillis }.take(5000).map { it.toJson() })
    }

    fun deleteIntake(id: Long) {
        saveArray("intakes", getIntakes().filterNot { it.id == id }.map { it.toJson() })
    }

    fun removeIntake(medicationId: Long, epochDay: Long, plannedTime: String) {
        saveArray(
            "intakes",
            getIntakes().filterNot {
                it.medicationId == medicationId && it.plannedEpochDay == epochDay && it.plannedTime == plannedTime
            }.map { it.toJson() }
        )
    }

    fun isTaken(medicationId: Long, epochDay: Long, plannedTime: String): Boolean =
        getIntakes().any { it.medicationId == medicationId && it.plannedEpochDay == epochDay && it.plannedTime == plannedTime }

    private fun getCountdownsRaw(): List<ActiveCountdown> = parseArray("countdowns") { ActiveCountdown.fromJson(it) }

    fun getCountdowns(): List<ActiveCountdown> = getCountdownsRaw().sortedBy { it.endMillis }

    fun getActiveCountdowns(now: Long = System.currentTimeMillis()): List<ActiveCountdown> =
        getCountdownsRaw().filter { it.endMillis > now }.sortedBy { it.endMillis }

    fun getCountdown(id: Long): ActiveCountdown? = getCountdownsRaw().firstOrNull { it.id == id }

    fun getCountdownsForIntake(medicationId: Long, epochDay: Long, plannedTime: String): List<ActiveCountdown> =
        getCountdownsRaw().filter {
            it.medicationId == medicationId && it.plannedEpochDay == epochDay && it.plannedTime == plannedTime
        }

    fun addCountdown(countdown: ActiveCountdown) {
        val list = getCountdownsRaw().filterNot { it.id == countdown.id }.toMutableList()
        list.add(countdown)
        saveArray("countdowns", list.map { it.toJson() })
    }

    fun removeCountdown(id: Long) {
        saveArray("countdowns", getCountdownsRaw().filterNot { it.id == id }.map { it.toJson() })
    }

    fun getPendingSnoozes(): List<PendingSnooze> =
        parseArray("snoozes") { PendingSnooze.fromJson(it) }.sortedBy { it.triggerAtMillis }

    fun getPendingSnoozesForMedication(medicationId: Long): List<PendingSnooze> =
        getPendingSnoozes().filter { it.medicationId == medicationId }

    fun upsertPendingSnooze(snooze: PendingSnooze) {
        val list = getPendingSnoozes().filterNot { it.key == snooze.key }.toMutableList()
        list.add(snooze)
        saveArray("snoozes", list.map { it.toJson() })
    }

    fun removePendingSnooze(medicationId: Long, epochDay: Long, plannedTime: String) {
        saveArray(
            "snoozes",
            getPendingSnoozes().filterNot {
                it.medicationId == medicationId &&
                    it.plannedEpochDay == epochDay &&
                    it.plannedTime == plannedTime
            }.map { it.toJson() }
        )
    }

    fun mergeImportedIntakes(events: List<IntakeEvent>): Int {
        val existing = getIntakes().toMutableList()
        val fingerprints = existing.mapTo(mutableSetOf()) { it.historyFingerprint() }
        var added = 0
        events.forEach { event ->
            if (fingerprints.add(event.historyFingerprint())) {
                existing.add(event)
                added++
            }
        }
        saveArray("intakes", existing.sortedByDescending { it.takenAtMillis }.take(5000).map { it.toJson() })
        return added
    }

    fun replaceImportedIntakes(events: List<IntakeEvent>) {
        val unique = LinkedHashMap<String, IntakeEvent>()
        events.sortedBy { it.takenAtMillis }.forEach { unique[it.historyFingerprint()] = it }
        saveArray("intakes", unique.values.sortedByDescending { it.takenAtMillis }.take(5000).map { it.toJson() })
    }

    private fun IntakeEvent.historyFingerprint(): String = buildString {
        append(medicationName.trim().lowercase())
        append('|').append(takenAtMillis)
        append('|').append(plannedEpochDay)
        append('|').append(plannedTime.trim())
    }

    private fun <T> parseArray(key: String, mapper: (org.json.JSONObject) -> T): List<T> {
        val raw = prefs.getString(key, "[]") ?: "[]"
        return runCatching {
            val a = JSONArray(raw)
            buildList { for (i in 0 until a.length()) add(mapper(a.getJSONObject(i))) }
        }.getOrDefault(emptyList())
    }

    private fun saveArray(key: String, objects: List<org.json.JSONObject>) {
        val a = JSONArray()
        objects.forEach { a.put(it) }
        prefs.edit().putString(key, a.toString()).apply()
    }
}
