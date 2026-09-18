package com.example.meditimer.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.example.meditimer.data.ActiveCountdown
import com.example.meditimer.data.IntakeEvent
import com.example.meditimer.data.MedicationRepository

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val medId = intent.getLongExtra(EXTRA_MED_ID, -1)
        val epochDay = intent.getLongExtra(EXTRA_EPOCH_DAY, -1)
        val time = intent.getStringExtra(EXTRA_TIME) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, NotificationHelper.notificationId(medId, time))
        val repo = MedicationRepository(context)
        val med = repo.getMedication(medId) ?: return

        when (intent.action) {
            ACTION_SNOOZE -> {
                if (!repo.isTaken(medId, epochDay, time)) {
                    Scheduler.scheduleSnooze(context, medId, epochDay, time, med.snoozeMinutes)
                }
                NotificationManagerCompat.from(context).cancel(notificationId)
            }

            ACTION_TAKEN -> {
                Scheduler.cancelSnooze(context, medId, epochDay, time)
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
                NotificationManagerCompat.from(context).cancel(notificationId)
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
