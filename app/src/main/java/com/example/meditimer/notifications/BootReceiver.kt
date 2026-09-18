package com.example.meditimer.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.meditimer.data.MedicationRepository

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        NotificationHelper.ensureChannels(context)
        Scheduler.scheduleAll(context)
        val now = System.currentTimeMillis()
        MedicationRepository(context).getActiveCountdowns(now).forEach { Scheduler.scheduleCountdown(context, it) }
    }
}
