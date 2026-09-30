package com.example.meditimer.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.meditimer.AlarmActivity
import com.example.meditimer.data.ActiveCountdown
import com.example.meditimer.data.IntakeEvent
import com.example.meditimer.data.MedicationRepository

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val medId = intent.getLongExtra(EXTRA_MED_ID, -1)
        val epochDay = intent.getLongExtra(EXTRA_EPOCH_DAY, -1)
        val time = intent.getStringExtra(EXTRA_TIME) ?: return
        val repo = MedicationRepository(context)
        val med = repo.getMedication(medId) ?: return

        when (intent.action) {
            ACTION_SNOOZE -> {
                AlarmPlaybackService.stop(context)
                AlarmActivity.requestClose(context)
                NotificationHelper.cancelDoseNotifications(context, medId, epochDay, time)

                if (!repo.isTaken(medId, epochDay, time)) {
                    // Replace the automatic retry with a snooze counted from the user's tap.
                    Scheduler.cancelSnooze(context, medId, epochDay, time)
                    Scheduler.scheduleSnooze(context, medId, epochDay, time, med.snoozeMinutes)
                }
            }

            ACTION_TAKEN -> {
                AlarmPlaybackService.stop(context)
                AlarmActivity.requestClose(context)
                Scheduler.cancelSnooze(context, medId, epochDay, time)
                NotificationHelper.cancelDoseNotifications(context, medId, epochDay, time)

                if (!repo.isTaken(medId, epochDay, time)) {
                    val now = System.currentTimeMillis()
                    repo.recordIntake(
                        IntakeEvent(
                            medicationId = medId,
                            medicationName = med.name,
                            plannedEpochDay = epochDay,
                            plannedTime = time,
                            takenAtMillis = now
                        )
                    )
                    Scheduler.scheduleNextPackageReminder(context, med)
                    Scheduler.scheduleNextStockReminder(context, repo.getMedication(med.id) ?: med)

                    if (med.countdownEnabled && med.countdownMinutes > 0) {
                        val countdown = ActiveCountdown(
                            id = now + medId,
                            medicationId = medId,
                            medicationName = med.name,
                            note = med.countdownNote,
                            startMillis = now,
                            endMillis = now + med.countdownMinutes * 60_000L,
                            plannedEpochDay = epochDay,
                            plannedTime = time
                        )
                        repo.addCountdown(countdown)
                        Scheduler.scheduleCountdown(context, countdown)
                    }
                }
                // Intentionally no "farmaco assunto" Android notification: the action
                // only clears the dose reminders and records the intake.
            }
        }
    }

    companion object {
        const val ACTION_TAKEN = "com.example.meditimer.TAKEN"
        const val ACTION_SNOOZE = "com.example.meditimer.SNOOZE"
        const val EXTRA_MED_ID = "med_id"
        const val EXTRA_EPOCH_DAY = "epoch_day"
        const val EXTRA_TIME = "time"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}
