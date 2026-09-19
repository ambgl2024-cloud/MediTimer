package com.example.meditimer.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.meditimer.data.MedicationRepository

/** Final countdown alarm. The notification channel itself plays the alarm sound. */
class CountdownReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FINISH) return

        val id = intent.getLongExtra(EXTRA_COUNTDOWN_ID, -1L)
        if (id < 0L) return

        val repo = MedicationRepository(context)
        val stored = repo.getCountdown(id)

        // The alarm also carries a copy of the essential countdown data. This means the
        // final alert can still be emitted even if Android killed the app process and the
        // in-memory/UI state has been recreated before the alarm fires.
        val medicationName = stored?.medicationName
            ?: intent.getStringExtra(EXTRA_MEDICATION_NAME)
            ?: "Farmaco"
        val note = stored?.note
            ?: intent.getStringExtra(EXTRA_NOTE)
            ?: ""

        repo.removeCountdown(id)
        NotificationHelper.showCountdownFinished(context, medicationName, note, id)
    }

    companion object {
        const val ACTION_FINISH = "com.example.meditimer.COUNTDOWN_FINISH"
        const val EXTRA_COUNTDOWN_ID = "countdown_id"
        const val EXTRA_MEDICATION_NAME = "countdown_medication_name"
        const val EXTRA_NOTE = "countdown_note"
    }
}
