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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

object Scheduler {
    private const val PACKAGE_REMINDER_HOUR = 9
    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    fun scheduleAll(context: Context) {
        MedicationRepository(context).getMedications().filter { it.enabled }.forEach { med ->
            med.alarmTimes.forEach { time -> scheduleNextForSlot(context, med, time) }
        }
    }

    fun scheduleAllPackageReminders(context: Context) {
        MedicationRepository(context).getMedications().forEach { med ->
            scheduleNextPackageReminder(context, med)
            scheduleNextStockReminder(context, med)
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
        cancelPackageReminder(context, medication.id)
        cancelStockReminder(context, medication.id)
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


    fun scheduleNextPackageReminder(context: Context, medication: Medication) {
        if (!medication.enabled || medication.lastPackageChangeEpochDay == null) {
            cancelPackageReminder(context, medication.id)
            return
        }

        val repo = MedicationRepository(context)
        val zone = ZoneId.systemDefault()
        val now = java.time.Instant.ofEpochMilli(System.currentTimeMillis()).atZone(zone).toLocalDateTime()
        val today = now.toLocalDate()
        val due = LocalDate.ofEpochDay(medication.lastPackageChangeEpochDay)
            .plusDays(medication.packageMaxDays.toLong())
        val warningStart = due.minusDays(7)
        val lastShown = repo.getLastPackageReminderEpochDay(medication.id)

        var targetDate = if (today.isBefore(warningStart)) warningStart else today
        if (lastShown == today.toEpochDay()) targetDate = today.plusDays(1)

        var candidate = LocalDateTime.of(targetDate, LocalTime.of(PACKAGE_REMINDER_HOUR, 0))
        if (targetDate == today && !candidate.isAfter(now)) {
            candidate = if (lastShown == today.toEpochDay()) {
                LocalDateTime.of(today.plusDays(1), LocalTime.of(PACKAGE_REMINDER_HOUR, 0))
            } else {
                now.plusSeconds(3)
            }
        }

        val pi = packageReminderPendingIntent(context, medication.id, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        scheduleAlarm(context, candidate.atZone(zone).toInstant().toEpochMilli(), pi)
    }

    fun cancelPackageReminder(context: Context, medicationId: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        packageReminderPendingIntent(context, medicationId, PendingIntent.FLAG_NO_CREATE)?.let { pi ->
            am.cancel(pi)
            pi.cancel()
        }
    }

    fun scheduleNextStockReminder(context: Context, medication: Medication) {
        val stock = medication.stockCount
        if (!medication.enabled || stock == null || stock > 1) {
            cancelStockReminder(context, medication.id)
            return
        }
        val repo = MedicationRepository(context)
        val zone = ZoneId.systemDefault()
        val now = java.time.Instant.ofEpochMilli(System.currentTimeMillis()).atZone(zone).toLocalDateTime()
        val today = now.toLocalDate()
        val lastShown = repo.getLastStockReminderEpochDay(medication.id)
        var targetDate = if (lastShown == today.toEpochDay()) today.plusDays(1) else today
        var candidate = LocalDateTime.of(targetDate, LocalTime.of(PACKAGE_REMINDER_HOUR, 0))
        if (targetDate == today && !candidate.isAfter(now)) candidate = now.plusSeconds(3)
        val pi = stockReminderPendingIntent(context, medication.id, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        scheduleAlarm(context, candidate.atZone(zone).toInstant().toEpochMilli(), pi)
    }

    fun cancelStockReminder(context: Context, medicationId: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        stockReminderPendingIntent(context, medicationId, PendingIntent.FLAG_NO_CREATE)?.let { pi ->
            am.cancel(pi)
            pi.cancel()
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



    private fun stockReminderPendingIntent(
        context: Context,
        medicationId: Long,
        baseFlag: Int
    ): PendingIntent? {
        val intent = Intent(context, StockReminderReceiver::class.java).apply {
            putExtra(StockReminderReceiver.EXTRA_MED_ID, medicationId)
        }
        return PendingIntent.getBroadcast(
            context,
            ("stock-reminder:$medicationId").hashCode(),
            intent,
            baseFlag or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun packageReminderPendingIntent(
        context: Context,
        medicationId: Long,
        baseFlag: Int
    ): PendingIntent? {
        val intent = Intent(context, PackageReminderReceiver::class.java).apply {
            putExtra(PackageReminderReceiver.EXTRA_MED_ID, medicationId)
        }
        return PendingIntent.getBroadcast(
            context,
            ("package-reminder:$medicationId").hashCode(),
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
