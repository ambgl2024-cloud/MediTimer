package com.example.meditimer.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.meditimer.data.MedicationRepository
import java.time.LocalDate

class StockReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val medId = intent.getLongExtra(EXTRA_MED_ID, -1L)
        if (medId < 0L) return

        val repo = MedicationRepository(context)
        val med = repo.getMedication(medId) ?: return
        val stock = med.stockCount
        if (!med.enabled || stock == null || stock > 1) {
            Scheduler.cancelStockReminder(context, medId)
            return
        }

        val today = LocalDate.now().toEpochDay()
        if (repo.getLastStockReminderEpochDay(medId) != today) {
            NotificationHelper.showLowStockWarning(context, med, stock)
            repo.markStockReminderShown(medId, today)
        }
        Scheduler.scheduleNextStockReminder(context, med)
    }

    companion object {
        const val EXTRA_MED_ID = "stock_med_id"
    }
}
