package com.example.meditimer.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.meditimer.data.MedicationRepository
import java.time.Instant
import java.time.ZoneId

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val medId = intent.getLongExtra(EXTRA_MED_ID, -1)
        val time = intent.getStringExtra(EXTRA_TIME) ?: return
        val repo = MedicationRepository(context)
        val med = repo.getMedication(medId) ?: return
        val now = System.currentTimeMillis()
        val fallbackDate = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        val plannedEpochDay = intent.getLongExtra(EXTRA_EPOCH_DAY, fallbackDate.toEpochDay())

        when (intent.action) {
            ACTION_PRE_REMINDER -> {
                if (
                    med.enabled &&
                    med.alarmTimes.contains(time) &&
                    !repo.isTaken(med.id, plannedEpochDay, time)
                ) {
                    NotificationHelper.showMedicationPreReminder(context, med, plannedEpochDay, time)
                }
            }

            ACTION_SNOOZE_ALARM -> {
                repo.removePendingSnooze(medId, plannedEpochDay, time)
                if (med.enabled && !repo.isTaken(med.id, plannedEpochDay, time)) {
                    // Ignoring the alarm behaves like an automatic snooze: every alarm
                    // occurrence immediately schedules the next retry.
                    Scheduler.scheduleSnooze(context, medId, plannedEpochDay, time, med.snoozeMinutes)
                    AlarmPlaybackService.start(context, medId, plannedEpochDay, time)
                } else {
                    NotificationHelper.cancelDoseNotifications(context, medId, plannedEpochDay, time)
                }
            }

            else -> {
                if (!med.enabled || !med.alarmTimes.contains(time)) return

                // The previous pending dose of the same medication becomes definitively
                // missed when the next scheduled dose is reached.
                Scheduler.cancelOlderDoseRepeats(context, medId, plannedEpochDay, time)

                if (!repo.isTaken(med.id, plannedEpochDay, time)) {
                    Scheduler.scheduleSnooze(context, medId, plannedEpochDay, time, med.snoozeMinutes)
                    AlarmPlaybackService.start(context, medId, plannedEpochDay, time)
                } else {
                    NotificationHelper.cancelDoseNotifications(context, medId, plannedEpochDay, time)
                }

                // Schedule the next regular occurrence (including its silent pre-reminder).
                Scheduler.scheduleNextForSlot(context, med, time, afterMillis = now + 60_000)
            }
        }
    }

    companion object {
        const val ACTION_MEDICATION_ALARM = "com.example.meditimer.MEDICATION_ALARM"
        const val ACTION_PRE_REMINDER = "com.example.meditimer.PRE_REMINDER"
        const val ACTION_SNOOZE_ALARM = "com.example.meditimer.SNOOZE_ALARM"
        const val EXTRA_MED_ID = "med_id"
        const val EXTRA_TIME = "time"
        const val EXTRA_EPOCH_DAY = "epoch_day"
        const val EXTRA_IS_SNOOZE = "is_snooze"
    }
}
