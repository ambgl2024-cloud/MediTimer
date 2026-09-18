package com.example.meditimer.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.meditimer.data.ActiveCountdown
import com.example.meditimer.data.Medication
import com.example.meditimer.data.MedicationRepository
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
                    val am = context.getSystemService(AlarmManager::class.java)
                    if (canScheduleExact(context)) {
                        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
                    } else {
                        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
                    }
                    return
                }
            }
            date = date.plusDays(1)
        }
    }

    fun scheduleCountdown(context: Context, countdown: ActiveCountdown, afterMillis: Long = System.currentTimeMillis()) {
        if (countdown.endMillis <= afterMillis) {
            scheduleCountdownEvent(context, countdown, countdown.endMillis, CountdownReceiver.ACTION_FINISH)
            return
        }

        val elapsed = ((afterMillis - countdown.startMillis).coerceAtLeast(0L) / 60_000L)
        val nextMinuteMark = countdown.startMillis + (elapsed + 1L) * 60_000L
        if (nextMinuteMark < countdown.endMillis) {
            scheduleCountdownEvent(context, countdown, nextMinuteMark, CountdownReceiver.ACTION_TICK)
        } else {
            scheduleCountdownEvent(context, countdown, countdown.endMillis, CountdownReceiver.ACTION_FINISH)
        }
    }

    fun cancelCountdown(context: Context, countdown: ActiveCountdown) {
        val am = context.getSystemService(AlarmManager::class.java)
        listOf(CountdownReceiver.ACTION_TICK, CountdownReceiver.ACTION_FINISH).forEach { action ->
            countdownPendingIntent(context, countdown.id, action, PendingIntent.FLAG_NO_CREATE)?.let { pi ->
                am.cancel(pi)
                pi.cancel()
            }
        }
    }

    private fun scheduleCountdownEvent(context: Context, countdown: ActiveCountdown, trigger: Long, action: String) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = countdownPendingIntent(context, countdown.id, action, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        if (canScheduleExact(context)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
        }
    }

    private fun countdownPendingIntent(context: Context, countdownId: Long, action: String, baseFlag: Int): PendingIntent? {
        val intent = Intent(context, CountdownReceiver::class.java).apply {
            this.action = action
            putExtra(CountdownReceiver.EXTRA_COUNTDOWN_ID, countdownId)
        }
        return PendingIntent.getBroadcast(
            context,
            ("countdown:$countdownId:$action").hashCode(),
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
