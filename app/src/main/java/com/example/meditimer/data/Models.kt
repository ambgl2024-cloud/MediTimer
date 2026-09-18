package com.example.meditimer.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

enum class RecurrenceType { DAILY, WEEKDAYS, EVERY_N_DAYS, MONTHLY_DAYS }

data class Medication(
    val id: Long = System.currentTimeMillis(),
    val name: String,
    val doseNote: String = "",
    val timesPerActiveDay: Int = 1,
    val recurrenceType: RecurrenceType = RecurrenceType.DAILY,
    val weekdays: Set<Int> = emptySet(),
    val everyNDays: Int = 1,
    val anchorEpochDay: Long = LocalDate.now().toEpochDay(),
    val monthlyDays: Set<Int> = emptySet(),
    val alarmTimes: List<String> = listOf("08:00"),
    val packageMaxDays: Int = 30,
    val lastPackageChangeEpochDay: Long? = null,
    val countdownEnabled: Boolean = false,
    val countdownMinutes: Int = 0,
    val countdownNote: String = "",
    val snoozeMinutes: Int = 10,
    val enabled: Boolean = true
) {
    fun isActiveOn(date: LocalDate): Boolean {
        if (!enabled) return false
        return when (recurrenceType) {
            RecurrenceType.DAILY -> true
            RecurrenceType.WEEKDAYS -> weekdays.contains(date.dayOfWeek.value)
            RecurrenceType.EVERY_N_DAYS -> {
                val delta = ChronoUnit.DAYS.between(LocalDate.ofEpochDay(anchorEpochDay), date)
                delta >= 0 && everyNDays > 0 && delta % everyNDays == 0L
            }
            RecurrenceType.MONTHLY_DAYS -> monthlyDays.contains(date.dayOfMonth)
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("doseNote", doseNote)
        put("timesPerActiveDay", timesPerActiveDay)
        put("recurrenceType", recurrenceType.name)
        put("weekdays", JSONArray(weekdays.sorted()))
        put("everyNDays", everyNDays)
        put("anchorEpochDay", anchorEpochDay)
        put("monthlyDays", JSONArray(monthlyDays.sorted()))
        put("alarmTimes", JSONArray(alarmTimes))
        put("packageMaxDays", packageMaxDays)
        if (lastPackageChangeEpochDay != null) put("lastPackageChangeEpochDay", lastPackageChangeEpochDay)
        put("countdownEnabled", countdownEnabled)
        put("countdownMinutes", countdownMinutes)
        put("countdownNote", countdownNote)
        put("snoozeMinutes", snoozeMinutes)
        put("enabled", enabled)
    }

    companion object {
        fun fromJson(o: JSONObject): Medication = Medication(
            id = o.getLong("id"),
            name = o.getString("name"),
            doseNote = o.optString("doseNote"),
            timesPerActiveDay = o.optInt("timesPerActiveDay", 1),
            recurrenceType = runCatching { RecurrenceType.valueOf(o.optString("recurrenceType", "DAILY")) }.getOrDefault(RecurrenceType.DAILY),
            weekdays = o.optJSONArray("weekdays").toIntSet(),
            everyNDays = o.optInt("everyNDays", 1).coerceAtLeast(1),
            anchorEpochDay = o.optLong("anchorEpochDay", LocalDate.now().toEpochDay()),
            monthlyDays = o.optJSONArray("monthlyDays").toIntSet(),
            alarmTimes = o.optJSONArray("alarmTimes").toStringList().ifEmpty { listOf("08:00") },
            packageMaxDays = o.optInt("packageMaxDays", 30).coerceAtLeast(1),
            lastPackageChangeEpochDay = if (o.has("lastPackageChangeEpochDay")) o.optLong("lastPackageChangeEpochDay") else null,
            countdownEnabled = o.optBoolean("countdownEnabled", false),
            countdownMinutes = o.optInt("countdownMinutes", 0),
            countdownNote = o.optString("countdownNote"),
            snoozeMinutes = o.optInt("snoozeMinutes", 10).coerceAtLeast(1),
            enabled = o.optBoolean("enabled", true)
        )
    }
}

data class IntakeEvent(
    val id: Long = System.currentTimeMillis(),
    val medicationId: Long,
    val medicationName: String = "",
    val plannedEpochDay: Long,
    val plannedTime: String,
    val takenAtMillis: Long
) {
    val key: String get() = "$medicationId|$plannedEpochDay|$plannedTime"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("medicationId", medicationId)
        put("medicationName", medicationName)
        put("plannedEpochDay", plannedEpochDay)
        put("plannedTime", plannedTime)
        put("takenAtMillis", takenAtMillis)
    }

    companion object {
        fun fromJson(o: JSONObject): IntakeEvent {
            val takenAt = o.getLong("takenAtMillis")
            return IntakeEvent(
                id = o.optLong("id", takenAt),
                medicationId = o.getLong("medicationId"),
                medicationName = o.optString("medicationName"),
                plannedEpochDay = o.getLong("plannedEpochDay"),
                plannedTime = o.getString("plannedTime"),
                takenAtMillis = takenAt
            )
        }
    }
}

data class ActiveCountdown(
    val id: Long,
    val medicationId: Long,
    val medicationName: String,
    val note: String,
    val startMillis: Long,
    val endMillis: Long,
    val plannedEpochDay: Long = -1,
    val plannedTime: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("medicationId", medicationId)
        put("medicationName", medicationName)
        put("note", note)
        put("startMillis", startMillis)
        put("endMillis", endMillis)
        put("plannedEpochDay", plannedEpochDay)
        put("plannedTime", plannedTime)
    }

    companion object {
        fun fromJson(o: JSONObject) = ActiveCountdown(
            id = o.getLong("id"),
            medicationId = o.getLong("medicationId"),
            medicationName = o.getString("medicationName"),
            note = o.optString("note"),
            startMillis = o.getLong("startMillis"),
            endMillis = o.getLong("endMillis"),
            plannedEpochDay = o.optLong("plannedEpochDay", -1),
            plannedTime = o.optString("plannedTime")
        )
    }
}


data class PendingSnooze(
    val medicationId: Long,
    val plannedEpochDay: Long,
    val plannedTime: String,
    val triggerAtMillis: Long
) {
    val key: String get() = "$medicationId|$plannedEpochDay|$plannedTime"

    fun toJson(): JSONObject = JSONObject().apply {
        put("medicationId", medicationId)
        put("plannedEpochDay", plannedEpochDay)
        put("plannedTime", plannedTime)
        put("triggerAtMillis", triggerAtMillis)
    }

    companion object {
        fun fromJson(o: JSONObject) = PendingSnooze(
            medicationId = o.getLong("medicationId"),
            plannedEpochDay = o.getLong("plannedEpochDay"),
            plannedTime = o.getString("plannedTime"),
            triggerAtMillis = o.getLong("triggerAtMillis")
        )
    }
}

fun Medication.recurrenceLabel(): String = when (recurrenceType) {
    RecurrenceType.DAILY -> "Ogni giorno"
    RecurrenceType.WEEKDAYS -> {
        val names = mapOf(1 to "Lun", 2 to "Mar", 3 to "Mer", 4 to "Gio", 5 to "Ven", 6 to "Sab", 7 to "Dom")
        weekdays.sorted().joinToString(" · ") { names[it] ?: it.toString() }.ifBlank { "Nessun giorno" }
    }
    RecurrenceType.EVERY_N_DAYS -> "Ogni $everyNDays giorni"
    RecurrenceType.MONTHLY_DAYS -> "Giorni ${monthlyDays.sorted().joinToString(", ")} del mese"
}

fun LocalDate.itDate(): String = format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))

private fun JSONArray?.toIntSet(): Set<Int> {
    if (this == null) return emptySet()
    return buildSet { for (i in 0 until length()) add(optInt(i)) }
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList { for (i in 0 until length()) add(optString(i)) }
}
