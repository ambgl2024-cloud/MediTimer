package com.example.meditimer.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.meditimer.data.ActiveCountdown
import com.example.meditimer.data.Medication
import com.example.meditimer.data.MedicationRepository
import com.example.meditimer.data.PendingSnooze
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

object Scheduler {
    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    fun scheduleAll(context: Context) {
        MedicationRepository(context).getMedications().filter { it.enabled }.forEach { med ->
            med.alarmTimes.forEach { time -> scheduleNextForSlot(context, med, time) }
        }
    }

    fun cancelMedication(context: Context, medication: Medication) {
        val am = context.getSystemService(AlarmManager::class.java)
        medication.alarmTimes.forEach { time ->
            alarmPendingIntent(context, medication.id, time, PendingIntent.FLAG_NO_CREATE)?.let { pi ->
                am.cancel(pi)
                pi.cancel()
            }
        }
        MedicationRepository(context).getPendingSnoozesForMedication(medication.id).forEach { snooze ->
            cancelSnooze(context, snooze.medicationId, snooze.plannedEpochDay, snooze.plannedTime)
        }
    }

    fun scheduleNextForSlot(context: Context, medication: Medication, time: String, afterMillis: Long = System.currentTimeMillis()) {
        if (!medication.enabled || !medication.alarmTimes.contains(time)) return
        val parsed = runCatching { LocalTime.parse(time) }.getOrNull() ?: return
        val zone = ZoneId.systemDefault()
        val after = java.time.Instant.ofEpochMilli(afterMillis).atZone(zone).toLocalDateTime()
        var date = after.toLocalDate()
        repeat(370) {
            if (medication.isActiveOn(date)) {
                val candidate = LocalDateTime.of(date, parsed)
                if (candidate.isAfter(after)) {
                    val trigger = candidate.atZone(zone).toInstant().toEpochMilli()
                    val pi = alarmPendingIntent(context, medication.id, time, PendingIntent.FLAG_UPDATE_CURRENT, date.toEpochDay()) ?: return
                    scheduleAlarm(context, trigger, pi)
                    return
                }
            }
            date = date.plusDays(1)
        }
    }

    fun scheduleSnooze(
        context: Context,
        medicationId: Long,
        plannedEpochDay: Long,
        plannedTime: String,
        snoozeMinutes: Int
    ) {
        val trigger = System.currentTimeMillis() + snoozeMinutes.coerceAtLeast(1) * 60_000L
        val snooze = PendingSnooze(medicationId, plannedEpochDay, plannedTime, trigger)
        MedicationRepository(context).upsertPendingSnooze(snooze)
        scheduleSnoozeEvent(context, snooze)
    }

    fun cancelSnooze(context: Context, medicationId: Long, plannedEpochDay: Long, plannedTime: String) {
        val am = context.getSystemService(AlarmManager::class.java)
        snoozePendingIntent(context, medicationId, plannedEpochDay, plannedTime, PendingIntent.FLAG_NO_CREATE)?.let { pi ->
            am.cancel(pi)
            pi.cancel()
        }
        MedicationRepository(context).removePendingSnooze(medicationId, plannedEpochDay, plannedTime)
    }

    fun restoreSnoozes(context: Context) {
        val repo = MedicationRepository(context)
        val now = System.currentTimeMillis()
        repo.getPendingSnoozes().forEach { snooze ->
            val med = repo.getMedication(snooze.medicationId)
            if (med == null || !med.enabled || repo.isTaken(snooze.medicationId, snooze.plannedEpochDay, snooze.plannedTime)) {
                cancelSnooze(context, snooze.medicationId, snooze.plannedEpochDay, snooze.plannedTime)
            } else {
                val restored = if (snooze.triggerAtMillis <= now) snooze.copy(triggerAtMillis = now + 1_000L) else snooze
                if (restored != snooze) repo.upsertPendingSnooze(restored)
                scheduleSnoozeEvent(context, restored)
            }
        }
    }

    /** Countdown: no intermediate sounds. Only the final alarm is scheduled. */
    fun scheduleCountdown(context: Context, countdown: ActiveCountdown) {
        scheduleCountdownFinishFallback(context, countdown)
    }

    fun cancelCountdown(context: Context, countdown: ActiveCountdown) {
        cancelCountdownFinishFallback(context, countdown.id)
    }

    fun restoreCountdowns(context: Context) {
        val repo = MedicationRepository(context)
        // Re-arm every persisted countdown. If its end time has already passed (for example
        // after process death/reboot), scheduleAlarm receives a near-immediate trigger so
        // the completion alert is not silently lost.
        repo.getCountdowns().forEach { countdown ->
            scheduleCountdownFinishFallback(context, countdown)
        }
    }

    private fun scheduleSnoozeEvent(context: Context, snooze: PendingSnooze) {
        val pi = snoozePendingIntent(
            context,
            snooze.medicationId,
            snooze.plannedEpochDay,
            snooze.plannedTime,
            PendingIntent.FLAG_UPDATE_CURRENT
        ) ?: return
        scheduleAlarm(context, snooze.triggerAtMillis, pi)
    }

    private fun scheduleCountdownFinishFallback(context: Context, countdown: ActiveCountdown) {
        val pi = countdownFinishPendingIntent(
            context = context,
            countdownId = countdown.id,
            baseFlag = PendingIntent.FLAG_UPDATE_CURRENT,
            medicationName = countdown.medicationName,
            note = countdown.note
        ) ?: return
        val triggerAt = maxOf(countdown.endMillis, System.currentTimeMillis() + 500L)
        scheduleAlarm(context, triggerAt, pi)
    }

    private fun scheduleAlarm(context: Context, trigger: Long, pi: PendingIntent) {
        val am = context.getSystemService(AlarmManager::class.java)
        if (canScheduleExact(context)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
        }
    }

    fun cancelCountdownFinishFallback(context: Context, countdownId: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        countdownFinishPendingIntent(context, countdownId, PendingIntent.FLAG_NO_CREATE)?.let { pi ->
            am.cancel(pi)
            pi.cancel()
        }
    }

    private fun countdownFinishPendingIntent(
        context: Context,
        countdownId: Long,
        baseFlag: Int,
        medicationName: String? = null,
        note: String? = null
    ): PendingIntent? {
        val intent = Intent(context, CountdownReceiver::class.java).apply {
            action = CountdownReceiver.ACTION_FINISH
            putExtra(CountdownReceiver.EXTRA_COUNTDOWN_ID, countdownId)
            medicationName?.let { putExtra(CountdownReceiver.EXTRA_MEDICATION_NAME, it) }
            note?.let { putExtra(CountdownReceiver.EXTRA_NOTE, it) }
        }
        return PendingIntent.getBroadcast(
            context,
            ("countdown-finish:$countdownId").hashCode(),
            intent,
            baseFlag or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun snoozePendingIntent(
        context: Context,
        medicationId: Long,
        plannedEpochDay: Long,
        plannedTime: String,
        baseFlag: Int
    ): PendingIntent? {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_SNOOZE_ALARM
            putExtra(AlarmReceiver.EXTRA_MED_ID, medicationId)
            putExtra(AlarmReceiver.EXTRA_TIME, plannedTime)
            putExtra(AlarmReceiver.EXTRA_EPOCH_DAY, plannedEpochDay)
            putExtra(AlarmReceiver.EXTRA_IS_SNOOZE, true)
        }
        return PendingIntent.getBroadcast(
            context,
            ("snooze:$medicationId:$plannedEpochDay:$plannedTime").hashCode(),
            intent,
            baseFlag or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun alarmPendingIntent(
        context: Context,
        medId: Long,
        time: String,
        baseFlag: Int,
        plannedEpochDay: Long? = null
    ): PendingIntent? {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(AlarmReceiver.EXTRA_MED_ID, medId)
            putExtra(AlarmReceiver.EXTRA_TIME, time)
            plannedEpochDay?.let { putExtra(AlarmReceiver.EXTRA_EPOCH_DAY, it) }
        }
        val flags = baseFlag or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, ("alarm:$medId:$time").hashCode(), intent, flags)
    }
}
