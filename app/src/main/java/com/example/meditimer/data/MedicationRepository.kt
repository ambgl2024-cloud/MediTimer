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
    }

    fun getIntakes(): List<IntakeEvent> = parseArray("intakes") { IntakeEvent.fromJson(it) }

    fun recordIntake(event: IntakeEvent) {
        val list = getIntakes().filterNot { it.key == event.key }.toMutableList()
        list.add(event)
        val trimmed = list.sortedByDescending { it.takenAtMillis }.take(5000)
        saveArray("intakes", trimmed.map { it.toJson() })
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
