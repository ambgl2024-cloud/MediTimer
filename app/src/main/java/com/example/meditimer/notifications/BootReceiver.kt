package com.example.meditimer.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        NotificationHelper.ensureChannels(context)
        Scheduler.scheduleAll(context)
        Scheduler.scheduleAllPackageReminders(context)
        Scheduler.restoreCountdowns(context)
        Scheduler.restoreSnoozes(context)
    }
}
