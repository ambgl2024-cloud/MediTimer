package com.example.meditimer.notifications

import android.content.Context
import com.example.meditimer.AlarmActivity
import com.example.meditimer.data.ActiveCountdown
import com.example.meditimer.data.IntakeEvent
import com.example.meditimer.data.MedicationRepository

object DoseActionHandler {
    fun markTaken(
        context: Context,
        medicationId: Long,
        plannedEpochDay: Long,
        plannedTime: String
    ): Boolean {
        val repo = MedicationRepository(context)
        val med = repo.getMedication(medicationId) ?: return false

        AlarmPlaybackService.stop(context)
        AlarmActivity.requestClose(context)
        Scheduler.cancelSnooze(context, medicationId, plannedEpochDay, plannedTime)
        NotificationHelper.cancelDoseNotifications(context, medicationId, plannedEpochDay, plannedTime)

        if (!repo.isTaken(medicationId, plannedEpochDay, plannedTime)) {
            val now = System.currentTimeMillis()
            repo.recordIntake(
                IntakeEvent(
                    medicationId = medicationId,
                    medicationName = med.name,
                    plannedEpochDay = plannedEpochDay,
                    plannedTime = plannedTime,
                    takenAtMillis = now
                )
            )
            Scheduler.scheduleNextPackageReminder(context, med)
            Scheduler.scheduleNextStockReminder(context, repo.getMedication(med.id) ?: med)

            if (med.countdownEnabled && med.countdownMinutes > 0) {
                val countdown = ActiveCountdown(
                    id = now + medicationId,
                    medicationId = medicationId,
                    medicationName = med.name,
                    note = med.countdownNote,
                    startMillis = now,
                    endMillis = now + med.countdownMinutes * 60_000L,
                    plannedEpochDay = plannedEpochDay,
                    plannedTime = plannedTime
                )
                repo.addCountdown(countdown)
                Scheduler.scheduleCountdown(context, countdown)
            }
        }

        // Deliberately no Android "assunto" confirmation notification.
        return true
    }
}
