package com.example.meditimer.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class DoseObservation(
    val medicationId: Long,
    val medicationName: String,
    val date: LocalDate,
    val plannedTime: String,
    val plannedMillis: Long,
    val takenAtMillis: Long?
) {
    val taken: Boolean get() = takenAtMillis != null
    val deviationMinutes: Double?
        get() = takenAtMillis?.let { (it - plannedMillis) / 60_000.0 }
}

data class TimingSlotStats(
    val medicationId: Long,
    val medicationName: String,
    val plannedTime: String,
    val expected: Int,
    val taken: Int,
    val averageTakenTime: String?,
    val meanDeviationMinutes: Int?,
    val dispersionMinutes: Int?,
    val meanAbsoluteDeviationMinutes: Double?
)

data class ProblemGroupStats(
    val label: String,
    val expected: Int,
    val taken: Int,
    val missedRate: Double,
    val meanAbsoluteDeviationMinutes: Double
)

data class CriticalPeriods(
    val weekday: ProblemGroupStats?,
    val week: ProblemGroupStats?,
    val month: ProblemGroupStats?
)

fun doseObservations(
    periods: List<TherapyPeriod>,
    intakes: List<IntakeEvent>,
    rangeStartMillis: Long,
    rangeEndMillisExclusive: Long,
    nowMillis: Long = System.currentTimeMillis(),
    medicationId: Long? = null
): List<DoseObservation> {
    val zone = ZoneId.systemDefault()
    val format = DateTimeFormatter.ofPattern("HH:mm")
    val intakeByKey = intakes
        .asSequence()
        .filter { medicationId == null || it.medicationId == medicationId }
        .associateBy { it.key }
    val observations = mutableListOf<DoseObservation>()

    // Statistics must describe only doses that were actually due at the instant
    // of the calculation. A selected week/month may extend into the future, but
    // future doses must never become missed doses.
    val evaluationEndExclusive = minOf(
        rangeEndMillisExclusive,
        if (nowMillis == Long.MAX_VALUE) Long.MAX_VALUE else nowMillis + 1
    )
    if (evaluationEndExclusive <= rangeStartMillis) return emptyList()

    periods.asSequence()
        .filter { medicationId == null || it.medicationId == medicationId }
        .forEach { period ->
            // Disabled therapy periods generate no expected doses.
            if (!period.snapshot.enabled) return@forEach

            // Intersect the selected range with the exact historical period in which
            // this medication configuration was active. This prevents days before
            // activation or after suspension/deletion from lowering adherence.
            val start = maxOf(period.startMillis, rangeStartMillis)
            val end = minOf(
                period.endMillisExclusive ?: evaluationEndExclusive,
                evaluationEndExclusive
            )
            if (end <= start) return@forEach

            var date = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
            val lastDate = Instant.ofEpochMilli(end - 1).atZone(zone).toLocalDate()
            while (!date.isAfter(lastDate)) {
                if (period.snapshot.isActiveOn(date)) {
                    period.snapshot.alarmTimes.forEach { timeText ->
                        val time = runCatching { LocalTime.parse(timeText, format) }.getOrNull() ?: return@forEach
                        val plannedMillis = LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli()

                        // plannedMillis < end is the only eligibility rule needed here:
                        // end is already capped at now + 1 ms and at the therapy-period end.
                        if (plannedMillis >= start && plannedMillis < end) {
                            val canonical = time.format(format)
                            val key = "${period.medicationId}|${date.toEpochDay()}|$canonical"
                            observations += DoseObservation(
                                medicationId = period.medicationId,
                                medicationName = period.medicationName,
                                date = date,
                                plannedTime = canonical,
                                plannedMillis = plannedMillis,
                                takenAtMillis = intakeByKey[key]?.takenAtMillis
                            )
                        }
                    }
                }
                date = date.plusDays(1)
            }
        }
    return observations.sortedBy { it.plannedMillis }
}

fun timingSlotStats(observations: List<DoseObservation>): List<TimingSlotStats> =
    observations.groupBy { Triple(it.medicationId, it.medicationName, it.plannedTime) }
        .map { (key, values) ->
            val deviations = values.mapNotNull { it.deviationMinutes }
            val mean = deviations.takeIf { it.isNotEmpty() }?.average()
            val dispersion = if (mean != null && deviations.isNotEmpty()) {
                sqrt(deviations.map { (it - mean) * (it - mean) }.average())
            } else null
            val plannedMinutes = key.third.substringBefore(':').toInt() * 60 + key.third.substringAfter(':').toInt()
            val averageClock = mean?.let {
                val minute = ((plannedMinutes + it.roundToInt()) % 1440 + 1440) % 1440
                String.format("%02d:%02d", minute / 60, minute % 60)
            }
            TimingSlotStats(
                medicationId = key.first,
                medicationName = key.second,
                plannedTime = key.third,
                expected = values.size,
                taken = deviations.size,
                averageTakenTime = averageClock,
                meanDeviationMinutes = mean?.roundToInt(),
                dispersionMinutes = dispersion?.roundToInt(),
                meanAbsoluteDeviationMinutes = deviations.takeIf { it.isNotEmpty() }?.map { abs(it) }?.average()
            )
        }
        .sortedWith(compareBy<TimingSlotStats> { it.medicationName.lowercase() }.thenBy { it.plannedTime })

private fun aggregateProblemGroup(label: String, values: List<DoseObservation>): ProblemGroupStats {
    val taken = values.count { it.taken }
    val deviations = values.mapNotNull { it.deviationMinutes }.map(::abs)
    return ProblemGroupStats(
        label = label,
        expected = values.size,
        taken = taken,
        missedRate = if (values.isEmpty()) 0.0 else (values.size - taken).toDouble() / values.size,
        meanAbsoluteDeviationMinutes = if (deviations.isEmpty()) 0.0 else deviations.average()
    )
}

private fun chooseWorst(groups: List<ProblemGroupStats>): ProblemGroupStats? {
    if (groups.isEmpty()) return null
    return groups.reduce { current, candidate ->
        val missedDiff = candidate.missedRate - current.missedRate
        when {
            missedDiff > 0.02 -> candidate
            missedDiff < -0.02 -> current
            candidate.meanAbsoluteDeviationMinutes > current.meanAbsoluteDeviationMinutes -> candidate
            else -> current
        }
    }
}

fun criticalPeriods(
    observations: List<DoseObservation>,
    nowMillis: Long = System.currentTimeMillis()
): CriticalPeriods {
    // Defensive cutoff: critical-period statistics only use doses whose scheduled
    // time has already arrived. This keeps partial days/weeks/months comparable
    // without treating their future remainder as missed.
    val dueObservations = observations.filter { it.plannedMillis <= nowMillis }

    val weekdayNames = mapOf(
        DayOfWeek.MONDAY to "Lunedì",
        DayOfWeek.TUESDAY to "Martedì",
        DayOfWeek.WEDNESDAY to "Mercoledì",
        DayOfWeek.THURSDAY to "Giovedì",
        DayOfWeek.FRIDAY to "Venerdì",
        DayOfWeek.SATURDAY to "Sabato",
        DayOfWeek.SUNDAY to "Domenica"
    )
    val weekdays = dueObservations.groupBy { it.date.dayOfWeek }
        .map { (key, values) -> aggregateProblemGroup(weekdayNames[key] ?: key.name, values) }

    val weeks = dueObservations.groupBy { it.date.minusDays((it.date.dayOfWeek.value - 1).toLong()) }
        .map { (start, values) ->
            aggregateProblemGroup("${start.format(DateTimeFormatter.ofPattern("dd/MM"))}–${start.plusDays(6).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))}", values)
        }

    val months = dueObservations.groupBy { YearMonth.from(it.date) }
        .map { (month, values) ->
            aggregateProblemGroup(month.format(DateTimeFormatter.ofPattern("MM/yyyy")), values)
        }

    return CriticalPeriods(
        weekday = chooseWorst(weekdays),
        week = chooseWorst(weeks),
        month = chooseWorst(months)
    )
}
