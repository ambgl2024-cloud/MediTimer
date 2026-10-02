package com.example.meditimer.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.meditimer.AlarmActivity
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
                DoseActionHandler.markTaken(context, medId, epochDay, time)

                // Legacy broadcast actions from already-posted notifications still try to
                // open Oggi. New notifications use a direct Activity PendingIntent, which
                // avoids Android's notification-trampoline restriction.
                openToday(context)
            }
        }
    }

    private fun openToday(context: Context) {
        runCatching {
            context.startActivity(
                Intent(context, com.example.meditimer.MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(com.example.meditimer.MainActivity.EXTRA_OPEN_TODAY, true)
                }
            )
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
