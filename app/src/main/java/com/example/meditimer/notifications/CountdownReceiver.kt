package com.example.meditimer.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.VibrationEffect
import android.os.Vibrator
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
        // Remove persisted state immediately: the countdown has reached zero.
        repo.removeCountdown(id)

        // Do not post an Android notification when the countdown ends. The user requested
        // only the audible double bip and vibration. Clear a possible legacy notification
        // with the same id in case the app was updated while a countdown was active.
        NotificationHelper.cancelCountdownFinished(appContext, id)

        // Keep the broadcast alive while the short custom bip and vibration are played.
        // This preserves the known-good final sound mechanism without publishing a
        // notification or using a minute-by-minute foreground service.
        val pendingResult = goAsync()
        Thread {
            try {
                runCatching {
                    appContext.getSystemService(Vibrator::class.java)?.vibrate(
                        VibrationEffect.createWaveform(longArrayOf(0, 500, 250, 500), -1)
                    )
                }
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
