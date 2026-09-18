package com.example.meditimer.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.meditimer.data.MedicationRepository

class CountdownReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_COUNTDOWN_ID, -1)
        val repo = MedicationRepository(context)
        val countdown = repo.getCountdown(id) ?: return

        when (intent.action) {
            ACTION_TICK -> {
                if (System.currentTimeMillis() < countdown.endMillis) {
                    SoundHelper.playMinuteTick()
                    Scheduler.scheduleCountdown(context, countdown, System.currentTimeMillis())
                }
            }
            ACTION_FINISH -> {
                repo.removeCountdown(id)
                SoundHelper.playCountdownFinished()
                NotificationHelper.showCountdownFinished(context, countdown.medicationName, countdown.note, id)
            }
        }
    }

    companion object {
        const val ACTION_TICK = "com.example.meditimer.COUNTDOWN_TICK"
        const val ACTION_FINISH = "com.example.meditimer.COUNTDOWN_FINISH"
        const val EXTRA_COUNTDOWN_ID = "countdown_id"
    }
}
