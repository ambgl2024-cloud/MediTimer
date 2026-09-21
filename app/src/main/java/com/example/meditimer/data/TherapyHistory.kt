package com.example.meditimer.data

import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class TherapyHistoryType { BASELINE, CREATED, UPDATED, DISABLED, ENABLED, DELETED }

data class TherapyHistoryEntry(
    val id: Long,
    val medicationId: Long,
    val timestampMillis: Long,
    val type: TherapyHistoryType,
    val snapshot: Medication,
    val previousSnapshot: Medication? = null,
    val legacyBaseline: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("medicationId", medicationId)
        put("timestampMillis", timestampMillis)
        put("type", type.name)
        put("snapshot", snapshot.toJson())
        if (previousSnapshot != null) put("previousSnapshot", previousSnapshot.toJson())
        put("legacyBaseline", legacyBaseline)
    }

    companion object {
        fun fromJson(o: JSONObject): TherapyHistoryEntry = TherapyHistoryEntry(
            id = o.optLong("id", o.getLong("timestampMillis")),
            medicationId = o.getLong("medicationId"),
            timestampMillis = o.getLong("timestampMillis"),
            type = runCatching { TherapyHistoryType.valueOf(o.optString("type", "BASELINE")) }
                .getOrDefault(TherapyHistoryType.BASELINE),
            snapshot = Medication.fromJson(o.getJSONObject("snapshot")),
            previousSnapshot = o.optJSONObject("previousSnapshot")?.let(Medication::fromJson),
            legacyBaseline = o.optBoolean("legacyBaseline", false)
        )
    }
}

data class TherapyPeriod(
    val medicationId: Long,
    val medicationName: String,
    val startMillis: Long,
    val endMillisExclusive: Long?,
    val snapshot: Medication,
    val sourceType: TherapyHistoryType,
    val endType: TherapyHistoryType?,
    val legacyBaseline: Boolean
)

data class AdherenceStats(
    val expected: Int = 0,
    val taken: Int = 0
) {
    val missed: Int get() = (expected - taken).coerceAtLeast(0)
    val percentage: Int? get() = if (expected > 0) ((taken * 100.0) / expected).toInt().coerceIn(0, 100) else null

    operator fun plus(other: AdherenceStats): AdherenceStats =
        AdherenceStats(expected = expected + other.expected, taken = taken + other.taken)
}

fun therapyRelevantEquals(a: Medication, b: Medication): Boolean =
    a.name == b.name &&
        a.doseNote == b.doseNote &&
        a.timesPerActiveDay == b.timesPerActiveDay &&
        a.recurrenceType == b.recurrenceType &&
        a.weekdays == b.weekdays &&
        a.everyNDays == b.everyNDays &&
        a.anchorEpochDay == b.anchorEpochDay &&
        a.monthlyDays == b.monthlyDays &&
        a.alarmTimes == b.alarmTimes

fun inferredMedicationCreatedMillis(medication: Medication, nowMillis: Long = System.currentTimeMillis()): Long? {
    val earliestPlausible = LocalDate.of(2000, 1, 1)
        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val latestPlausible = nowMillis + 86_400_000L
    return medication.id.takeIf { it in earliestPlausible..latestPlausible }
}

fun Medication.scheduleDescription(): String {
    val frequency = when (timesPerActiveDay) {
        1 -> "1 volta al giorno"
        else -> "$timesPerActiveDay volte al giorno"
    }
    val times = alarmTimes.joinToString(" · ")
    val recurrence = recurrenceLabel().lowercase()
    return buildString {
        if (doseNote.isNotBlank()) append(doseNote.trim()).append(" · ")
        append(frequency)
        if (times.isNotBlank()) append(" (" + times + ")")
        append(" · ").append(recurrence)
    }
}

fun buildTherapyPeriods(history: List<TherapyHistoryEntry>): List<TherapyPeriod> {
    return history.groupBy { it.medicationId }.flatMap { (medicationId, entries) ->
        val sorted = entries.sortedBy { it.timestampMillis }
        buildList {
            sorted.forEachIndexed { index, entry ->
                if (entry.type == TherapyHistoryType.DELETED) return@forEachIndexed
                val next = sorted.getOrNull(index + 1)
                add(
                    TherapyPeriod(
                        medicationId = medicationId,
                        medicationName = entry.snapshot.name,
                        startMillis = entry.timestampMillis,
                        endMillisExclusive = next?.timestampMillis,
                        snapshot = entry.snapshot,
                        sourceType = entry.type,
                        endType = next?.type,
                        legacyBaseline = entry.legacyBaseline
                    )
                )
            }
        }
    }
}

fun adherenceForPeriods(
    periods: List<TherapyPeriod>,
    intakes: List<IntakeEvent>,
    rangeStartMillis: Long,
    rangeEndMillisExclusive: Long,
    nowMillis: Long = System.currentTimeMillis(),
    medicationId: Long? = null
): AdherenceStats {
    val zone = ZoneId.systemDefault()
    val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
    val takenKeys = intakes.asSequence()
        .filter { medicationId == null || it.medicationId == medicationId }
        .map { it.key }
        .toHashSet()

    var expected = 0
    var taken = 0

    periods.asSequence()
        .filter { medicationId == null || it.medicationId == medicationId }
        .forEach { period ->
            if (!period.snapshot.enabled) return@forEach
            val start = maxOf(period.startMillis, rangeStartMillis)
            val end = minOf(period.endMillisExclusive ?: rangeEndMillisExclusive, rangeEndMillisExclusive, nowMillis + 1)
            if (end <= start) return@forEach

            var date = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
            val lastDate = Instant.ofEpochMilli(end - 1).atZone(zone).toLocalDate()
            while (!date.isAfter(lastDate)) {
                if (period.snapshot.isActiveOn(date)) {
                    period.snapshot.alarmTimes.forEach { timeText ->
                        val parsed = runCatching { LocalTime.parse(timeText, timeFormat) }.getOrNull()
                        if (parsed != null) {
                            val plannedMillis = date.atTime(parsed).atZone(zone).toInstant().toEpochMilli()
                            if (plannedMillis >= start && plannedMillis < end && plannedMillis <= nowMillis) {
                                expected++
                                val key = "${period.medicationId}|${date.toEpochDay()}|${parsed.format(timeFormat)}"
                                if (key in takenKeys) taken++
                            }
                        }
                    }
                }
                date = date.plusDays(1)
            }
        }

    return AdherenceStats(expected = expected, taken = taken)
}

fun dailyAdherence(
    periods: List<TherapyPeriod>,
    intakes: List<IntakeEvent>,
    firstDate: LocalDate,
    days: Int,
    nowMillis: Long = System.currentTimeMillis()
): List<Pair<LocalDate, AdherenceStats>> {
    val zone = ZoneId.systemDefault()
    return (0 until days).map { offset ->
        val date = firstDate.plusDays(offset.toLong())
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        date to adherenceForPeriods(periods, intakes, start, end, nowMillis)
    }
}
