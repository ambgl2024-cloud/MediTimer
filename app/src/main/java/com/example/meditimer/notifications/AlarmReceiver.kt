package com.example.meditimer.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.meditimer.data.MedicationRepository
import java.time.Instant
import java.time.ZoneId

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val medId = intent.getLongExtra(EXTRA_MED_ID, -1)
        val time = intent.getStringExtra(EXTRA_TIME) ?: return
        val repo = MedicationRepository(context)
        val med = repo.getMedication(medId) ?: return
        val isSnooze = intent.getBooleanExtra(EXTRA_IS_SNOOZE, false) || intent.action == ACTION_SNOOZE_ALARM
        val now = System.currentTimeMillis()
        val fallbackDate = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        val plannedEpochDay = intent.getLongExtra(EXTRA_EPOCH_DAY, fallbackDate.toEpochDay())

        if (isSnooze) {
            repo.removePendingSnooze(medId, plannedEpochDay, time)
            if (med.enabled && !repo.isTaken(med.id, plannedEpochDay, time)) {
                NotificationHelper.showMedicationAlarm(context, med, plannedEpochDay, time, now)
            }
            return
        }

        if (!med.enabled || !med.alarmTimes.contains(time)) return
        if (!repo.isTaken(med.id, plannedEpochDay, time)) {
            NotificationHelper.showMedicationAlarm(context, med, plannedEpochDay, time, now)
        }
        Scheduler.scheduleNextForSlot(context, med, time, afterMillis = now + 60_000)
    }

    companion object {
        const val ACTION_SNOOZE_ALARM = "com.example.meditimer.SNOOZE_ALARM"
        const val EXTRA_MED_ID = "med_id"
        const val EXTRA_TIME = "time"
        const val EXTRA_EPOCH_DAY = "epoch_day"
        const val EXTRA_IS_SNOOZE = "is_snooze"
    }
}
