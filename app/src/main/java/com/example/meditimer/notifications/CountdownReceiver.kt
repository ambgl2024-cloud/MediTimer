package com.example.meditimer.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.meditimer.data.MedicationRepository

/** Fires the final countdown sound and notification. */
class CountdownReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FINISH) return
        val id = intent.getLongExtra(EXTRA_COUNTDOWN_ID, -1)
        val repo = MedicationRepository(context)
        val countdown = repo.getCountdown(id) ?: return

        repo.removeCountdown(id)
        SoundHelper.playCountdownFinished(context)
        NotificationHelper.showCountdownFinished(context, countdown.medicationName, countdown.note, id)
    }

    companion object {
        const val ACTION_FINISH = "com.example.meditimer.COUNTDOWN_FINISH"
        const val EXTRA_COUNTDOWN_ID = "countdown_id"
    }
}
