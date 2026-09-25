package com.example.meditimer.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.meditimer.data.MedicationRepository
import com.example.meditimer.data.evaluateStockReminder
import java.time.LocalDate

class StockReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val medId = intent.getLongExtra(EXTRA_MED_ID, -1L)
        if (medId < 0L) return

        val repo = MedicationRepository(context)
        val med = repo.getMedication(medId) ?: return
        val today = LocalDate.now()
        val evaluation = evaluateStockReminder(med, repo, today)

        if (!evaluation.active) {
            Scheduler.cancelStockReminder(context, medId)
            repo.clearStockReminderState(medId)
            NotificationHelper.cancelLowStockWarning(context, medId)
            Scheduler.scheduleNextStockReminder(context, med)
            return
        }

        val lastShown = repo.getLastStockReminderEpochDay(medId)
        val due = lastShown == null || today.toEpochDay() - lastShown >= 7L
        if (due) {
            NotificationHelper.showStockPurchaseReminder(
                context = context,
                medication = med,
                body = evaluation.message,
                weeklyReminder = lastShown != null
            )
            repo.markStockReminderShown(medId, today.toEpochDay())
        }
        Scheduler.scheduleNextStockReminder(context, med)
    }

    companion object {
        const val EXTRA_MED_ID = "stock_med_id"
    }
}
