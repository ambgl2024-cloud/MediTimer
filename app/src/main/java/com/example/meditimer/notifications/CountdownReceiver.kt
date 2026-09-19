package com.example.meditimer.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.meditimer.data.MedicationRepository

/**
 * Final countdown alarm.
 *
 * The exact AlarmManager event wakes this receiver. The custom countdown bip is played
 * with USAGE_ALARM by SoundHelper, while the notification channel is intentionally silent
 * so Android does not add its default alarm ringtone on top of the custom sound.
 */
class CountdownReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FINISH) return

        val id = intent.getLongExtra(EXTRA_COUNTDOWN_ID, -1L)
        if (id < 0L) return

        val appContext = context.applicationContext
        val repo = MedicationRepository(appContext)
        val stored = repo.getCountdown(id)

        val medicationName = stored?.medicationName
            ?: intent.getStringExtra(EXTRA_MEDICATION_NAME)
            ?: "Farmaco"
        val note = stored?.note
            ?: intent.getStringExtra(EXTRA_NOTE)
            ?: ""

        // Remove persisted state immediately: the countdown has reached zero.
        repo.removeCountdown(id)

        // Post the visual/vibration notification immediately. Its channel has no sound.
        NotificationHelper.showCountdownFinished(appContext, medicationName, note, id)

        // Keep the broadcast alive while the short custom bip is played. This is the same
        // custom final sound mechanism that worked in the earlier versions, but without the
        // old minute-by-minute foreground service.
        val pendingResult = goAsync()
        Thread {
            try {
                SoundHelper.playCountdownFinished(appContext)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION_FINISH = "com.example.meditimer.COUNTDOWN_FINISH"
        const val EXTRA_COUNTDOWN_ID = "countdown_id"
        const val EXTRA_MEDICATION_NAME = "countdown_medication_name"
        const val EXTRA_NOTE = "countdown_note"
    }
}
