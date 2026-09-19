package com.example.meditimer.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.meditimer.data.MedicationRepository
import com.example.meditimer.data.PackageDurationMode
import java.time.LocalDate

class PackageReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val medId = intent.getLongExtra(EXTRA_MED_ID, -1L)
        if (medId < 0L) return

        val repo = MedicationRepository(context)
        val med = repo.getMedication(medId) ?: return
        val lastChange = med.lastPackageChangeEpochDay?.let(LocalDate::ofEpochDay)
        if (!med.enabled || lastChange == null) {
            Scheduler.cancelPackageReminder(context, medId)
            return
        }

        if (med.packageDurationMode == PackageDurationMode.INTAKES) {
            // Any stale time-based alarm from an older configuration is converted to the
            // intake-driven reminder logic.
            Scheduler.scheduleNextPackageReminder(context, med)
            return
        }

        val today = LocalDate.now()
        val due = lastChange.plusDays(med.packageMaxDays.toLong())
        val remaining = due.toEpochDay() - today.toEpochDay()
        val lastShown = repo.getLastPackageReminderEpochDay(medId)

        if (remaining <= 7L && lastShown != today.toEpochDay()) {
            NotificationHelper.showPackageChangeReminder(context, med, remaining)
            repo.markPackageReminderShown(medId, today.toEpochDay())
        }

        Scheduler.scheduleNextPackageReminder(context, med)
    }

    companion object {
        const val EXTRA_MED_ID = "package_med_id"
    }
}
