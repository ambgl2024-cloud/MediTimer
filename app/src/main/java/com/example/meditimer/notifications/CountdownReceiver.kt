package com.example.meditimer.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.meditimer.data.MedicationRepository

class CountdownReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_COUNTDOWN_ID, -1)
        val name = intent.getStringExtra(EXTRA_MED_NAME) ?: "Farmaco"
        val note = intent.getStringExtra(EXTRA_NOTE).orEmpty()
        val repo = MedicationRepository(context)
        if (repo.getCountdown(id) == null) return
        repo.removeCountdown(id)
        NotificationHelper.showCountdownFinished(context, name, note, id)
    }

    companion object {
        const val EXTRA_COUNTDOWN_ID = "countdown_id"
        const val EXTRA_MED_NAME = "med_name"
        const val EXTRA_NOTE = "note"
    }
}
