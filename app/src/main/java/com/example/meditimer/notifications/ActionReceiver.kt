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
        if (intent.action != ACTION_TAKEN) return
        val medId = intent.getLongExtra(EXTRA_MED_ID, -1)
        val epochDay = intent.getLongExtra(EXTRA_EPOCH_DAY, -1)
        val time = intent.getStringExtra(EXTRA_TIME) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        val repo = MedicationRepository(context)
        val med = repo.getMedication(medId) ?: return
        val now = System.currentTimeMillis()
        repo.recordIntake(IntakeEvent(medId, epochDay, time, now))
        if (med.countdownEnabled && med.countdownMinutes > 0) {
            val countdown = ActiveCountdown(
                id = now + medId,
                medicationId = medId,
                medicationName = med.name,
                note = med.countdownNote,
                startMillis = now,
                endMillis = now + med.countdownMinutes * 60_000L
            )
            repo.addCountdown(countdown)
            Scheduler.scheduleCountdown(context, countdown)
        }
        NotificationManagerCompat.from(context).cancel(notificationId)
    }

    companion object {
        const val ACTION_TAKEN = "com.example.meditimer.TAKEN"
        const val EXTRA_MED_ID = "med_id"
        const val EXTRA_EPOCH_DAY = "epoch_day"
        const val EXTRA_TIME = "time"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}
