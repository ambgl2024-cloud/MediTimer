package com.example.meditimer.data

import java.time.LocalDate

data class StockReminderEvaluation(
    val active: Boolean,
    val message: String = ""
)

fun evaluateStockReminder(
    medication: Medication,
    repo: MedicationRepository,
    today: LocalDate = LocalDate.now()
): StockReminderEvaluation {
    if (!medication.enabled || medication.stockReminderMode == StockReminderMode.NONE) {
        return StockReminderEvaluation(false)
    }

    val stock = medication.stockCount ?: return StockReminderEvaluation(false)
    val threshold = medication.stockReminderThreshold.coerceAtLeast(0)

    return when (medication.stockReminderMode) {
        StockReminderMode.NONE -> StockReminderEvaluation(false)
        StockReminderMode.REORDER_POINT -> {
            if (stock <= threshold) {
                StockReminderEvaluation(
                    true,
                    if (stock == 1) {
                        "${medication.name}: riacquisto consigliato — rimane 1 confezione (soglia $threshold)."
                    } else {
                        "${medication.name}: riacquisto consigliato — rimangono $stock confezioni (soglia $threshold)."
                    }
                )
            } else StockReminderEvaluation(false)
        }
        StockReminderMode.TIME_REMAINING -> {
            if (stock != 0 || medication.lastPackageChangeEpochDay == null) {
                StockReminderEvaluation(false)
            } else if (medication.packageDurationMode == PackageDurationMode.DAYS) {
                val due = LocalDate.ofEpochDay(medication.lastPackageChangeEpochDay)
                    .plusDays(medication.packageMaxDays.toLong())
                val remaining = due.toEpochDay() - today.toEpochDay()
                if (remaining <= threshold.toLong()) {
                    val detail = when {
                        remaining > 1 -> "restano $remaining giorni alla fine dell'ultima confezione"
                        remaining == 1L -> "resta 1 giorno alla fine dell'ultima confezione"
                        remaining == 0L -> "l'ultima confezione termina oggi"
                        remaining == -1L -> "l'ultima confezione è oltre la durata prevista di 1 giorno"
                        else -> "l'ultima confezione è oltre la durata prevista di ${-remaining} giorni"
                    }
                    StockReminderEvaluation(true, "${medication.name}: riacquisto consigliato — $detail.")
                } else StockReminderEvaluation(false)
            } else {
                val remaining = repo.getPackageIntakesRemaining(medication) ?: return StockReminderEvaluation(false)
                if (remaining <= threshold) {
                    val detail = when {
                        remaining > 1 -> "restano $remaining assunzioni nell'ultima confezione"
                        remaining == 1 -> "resta 1 assunzione nell'ultima confezione"
                        remaining == 0 -> "hai raggiunto l'ultima assunzione prevista"
                        remaining == -1 -> "hai superato di 1 assunzione la durata prevista"
                        else -> "hai superato di ${-remaining} assunzioni la durata prevista"
                    }
                    StockReminderEvaluation(true, "${medication.name}: riacquisto consigliato — $detail.")
                } else StockReminderEvaluation(false)
            }
        }
    }
}
