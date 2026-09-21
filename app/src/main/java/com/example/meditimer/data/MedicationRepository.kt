package com.example.meditimer.data

import android.content.Context
import org.json.JSONArray
import java.time.LocalDate
import java.time.ZoneId

class MedicationRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("meditimer_data", Context.MODE_PRIVATE)

    fun getMedications(): List<Medication> = parseArray("medications") { Medication.fromJson(it) }.sortedBy { it.name.lowercase() }

    fun getMedication(id: Long): Medication? = getMedications().firstOrNull { it.id == id }

    fun upsertMedication(medication: Medication) {
        val previous = getMedication(medication.id)
        val list = getMedications().toMutableList()
        val idx = list.indexOfFirst { it.id == medication.id }
        if (idx >= 0) list[idx] = medication else list.add(medication)
        saveArray("medications", list.map { it.toJson() })

        when {
            previous == null -> appendTherapyHistory(
                type = TherapyHistoryType.CREATED,
                snapshot = medication,
                previous = null
            )
            previous.enabled != medication.enabled -> appendTherapyHistory(
                type = if (medication.enabled) TherapyHistoryType.ENABLED else TherapyHistoryType.DISABLED,
                snapshot = medication,
                previous = previous
            )
            !therapyRelevantEquals(previous, medication) -> appendTherapyHistory(
                type = TherapyHistoryType.UPDATED,
                snapshot = medication,
                previous = previous
            )
        }
    }

    fun deleteMedication(id: Long) {
        val med = getMedication(id)
        if (med != null) {
            appendTherapyHistory(
                type = TherapyHistoryType.DELETED,
                snapshot = med.copy(enabled = false),
                previous = med
            )
            val enriched = getIntakes().map {
                if (it.medicationId == id && it.medicationName.isBlank()) it.copy(medicationName = med.name) else it
            }
            saveArray("intakes", enriched.map { it.toJson() })
        }
        saveArray("medications", getMedications().filterNot { it.id == id }.map { it.toJson() })
        saveArray("countdowns", getCountdownsRaw().filterNot { it.medicationId == id }.map { it.toJson() })
        saveArray("snoozes", getPendingSnoozes().filterNot { it.medicationId == id }.map { it.toJson() })
        clearPackageReminderState(id)
        clearStockReminderState(id)
    }



    fun mergeHistoricalMedication(sourceMedicationId: Long, targetMedicationId: Long): Boolean {
        if (sourceMedicationId == targetMedicationId) return false

        // The destination must be a medication that still exists.
        // The source is intentionally restricted to a historical/deleted ID so that
        // this correction cannot silently overwrite another active medication.
        val target = getMedication(targetMedicationId) ?: return false
        if (getMedication(sourceMedicationId) != null) return false

        val allIntakes = getIntakes()
        val sourceIntakes = allIntakes.filter { it.medicationId == sourceMedicationId }
        val targetIntakes = allIntakes.filter { it.medicationId == targetMedicationId }

        // Keep the target event if the same planned dose exists on both IDs.
        // This avoids creating duplicate "Assunto" events after the merge.
        val mergedTargetIntakes = LinkedHashMap<String, IntakeEvent>()
        targetIntakes.sortedBy { it.takenAtMillis }.forEach { event ->
            val normalized = event.copy(
                medicationId = targetMedicationId,
                medicationName = target.name
            )
            mergedTargetIntakes[normalized.key] = normalized
        }
        sourceIntakes.sortedBy { it.takenAtMillis }.forEach { event ->
            val normalized = event.copy(
                medicationId = targetMedicationId,
                medicationName = target.name
            )
            mergedTargetIntakes.putIfAbsent(normalized.key, normalized)
        }

        val untouchedIntakes = allIntakes.filter {
            it.medicationId != sourceMedicationId && it.medicationId != targetMedicationId
        }
        val mergedIntakes = (untouchedIntakes + mergedTargetIntakes.values)
            .sortedByDescending { it.takenAtMillis }
            .take(5000)
        saveArraySync("intakes", mergedIntakes.map { it.toJson() })

        val allHistory = getTherapyHistory()
        val sourceHistory = allHistory
            .filter { it.medicationId == sourceMedicationId }
            .sortedBy { it.timestampMillis }
        val targetHistory = allHistory
            .filter { it.medicationId == targetMedicationId }
            .sortedBy { it.timestampMillis }
        val untouchedHistory = allHistory.filter {
            it.medicationId != sourceMedicationId && it.medicationId != targetMedicationId
        }

        fun normalizeMedication(medication: Medication?): Medication? =
            medication?.copy(id = targetMedicationId, name = target.name)

        fun normalizeEntry(entry: TherapyHistoryEntry): TherapyHistoryEntry =
            entry.copy(
                medicationId = targetMedicationId,
                snapshot = normalizeMedication(entry.snapshot)!!,
                previousSnapshot = normalizeMedication(entry.previousSnapshot)
            )

        // "Deleted" on the old ID and "Created/Baseline" on the recreated ID are
        // technical artifacts of the accidental delete/recreate operation.
        val sourceWithoutDelete = sourceHistory
            .filterNot { it.type == TherapyHistoryType.DELETED }
            .map(::normalizeEntry)
            .toMutableList()

        val sourceLastKnownSnapshot = sourceHistory
            .asReversed()
            .firstNotNullOfOrNull { entry ->
                when {
                    entry.type != TherapyHistoryType.DELETED -> normalizeMedication(entry.snapshot)
                    entry.previousSnapshot != null -> normalizeMedication(entry.previousSnapshot)
                    else -> null
                }
            }

        val earliestSourceIntake = sourceIntakes.minOfOrNull { it.takenAtMillis }
        val earliestSourceHistory = sourceWithoutDelete.minOfOrNull { it.timestampMillis }
        val earliestSourceEvidence = listOfNotNull(earliestSourceIntake, earliestSourceHistory).minOrNull()

        val normalizedTargetHistory = targetHistory.map(::normalizeEntry).toMutableList()
        val firstTargetEntry = normalizedTargetHistory.firstOrNull()

        // If the old ID only survives in the intake history (typical for a medication
        // deleted before the Storia feature existed), create a legacy baseline so the
        // unified story begins from the old recorded period instead of the recreation date.
        if (
            earliestSourceEvidence != null &&
            (sourceWithoutDelete.isEmpty() ||
                earliestSourceEvidence < (sourceWithoutDelete.minOfOrNull { it.timestampMillis } ?: Long.MAX_VALUE))
        ) {
            val snapshot = sourceLastKnownSnapshot
                ?: firstTargetEntry?.snapshot
                ?: target
            val existingIds = allHistory.mapTo(mutableSetOf()) { it.id }
            var syntheticId = earliestSourceEvidence * 1000L + 997L
            while (syntheticId in existingIds) syntheticId++

            sourceWithoutDelete.add(
                0,
                TherapyHistoryEntry(
                    id = syntheticId,
                    medicationId = targetMedicationId,
                    timestampMillis = earliestSourceEvidence,
                    type = TherapyHistoryType.BASELINE,
                    snapshot = normalizeMedication(snapshot)!!,
                    previousSnapshot = null,
                    legacyBaseline = true
                )
            )
        }

        val lastSourceSnapshot = sourceWithoutDelete
            .maxByOrNull { it.timestampMillis }
            ?.snapshot
            ?: sourceLastKnownSnapshot

        val targetAfterOrigin = normalizedTargetHistory.toMutableList()
        if (earliestSourceEvidence != null && firstTargetEntry != null) {
            // The recreation entry must not appear as a second medication start.
            // If the recreated configuration differs, preserve that moment as a normal
            // schema/status change; otherwise remove the artificial boundary entirely.
            targetAfterOrigin.removeAt(0)

            val previous = lastSourceSnapshot
            if (previous != null) {
                val replacementType = when {
                    previous.enabled != firstTargetEntry.snapshot.enabled ->
                        if (firstTargetEntry.snapshot.enabled) TherapyHistoryType.ENABLED
                        else TherapyHistoryType.DISABLED
                    !therapyRelevantEquals(previous, firstTargetEntry.snapshot) ->
                        TherapyHistoryType.UPDATED
                    else -> null
                }

                if (replacementType != null) {
                    targetAfterOrigin.add(
                        0,
                        firstTargetEntry.copy(
                            type = replacementType,
                            previousSnapshot = previous,
                            legacyBaseline = false
                        )
                    )
                }
            } else {
                targetAfterOrigin.add(0, firstTargetEntry)
            }
        }

        val mergedMedicationHistory = (sourceWithoutDelete + targetAfterOrigin)
            .sortedWith(compareBy<TherapyHistoryEntry> { it.timestampMillis }.thenBy { it.id })

        val mergedHistory = (untouchedHistory + mergedMedicationHistory)
            .sortedWith(compareBy<TherapyHistoryEntry> { it.timestampMillis }.thenBy { it.id })
            .takeLast(5000)

        saveArraySync("therapy_history", mergedHistory.map { it.toJson() })

        // No alarms, stock, package state, snoozes or countdown settings are copied:
        // the current target medication remains exactly as configured by the user.
        clearPackageReminderState(sourceMedicationId)
        clearStockReminderState(sourceMedicationId)

        return true
    }

    fun getTherapyHistory(): List<TherapyHistoryEntry> =
        parseArray("therapy_history") { TherapyHistoryEntry.fromJson(it) }
            .sortedBy { it.timestampMillis }

    fun getTherapyHistory(medicationId: Long): List<TherapyHistoryEntry> =
        getTherapyHistory().filter { it.medicationId == medicationId }

    fun ensureTherapyHistoryBaselines() {
        val existingIds = getTherapyHistory().mapTo(mutableSetOf()) { it.medicationId }
        val now = System.currentTimeMillis()
        getMedications().filterNot { it.id in existingIds }.forEach { medication ->
            val inferredStart = inferredMedicationCreatedMillis(medication, now)
                ?: getIntakes().filter { it.medicationId == medication.id }.minOfOrNull { it.takenAtMillis }
                ?: now
            appendTherapyHistory(
                type = TherapyHistoryType.BASELINE,
                snapshot = medication,
                previous = null,
                timestampMillis = inferredStart,
                legacyBaseline = true
            )
        }
    }

    private fun appendTherapyHistory(
        type: TherapyHistoryType,
        snapshot: Medication,
        previous: Medication?,
        timestampMillis: Long = System.currentTimeMillis(),
        legacyBaseline: Boolean = false
    ) {
        val list = getTherapyHistory().toMutableList()
        val uniqueId = timestampMillis * 1000L + (list.size % 1000)
        list.add(
            TherapyHistoryEntry(
                id = uniqueId,
                medicationId = snapshot.id,
                timestampMillis = timestampMillis,
                type = type,
                snapshot = snapshot,
                previousSnapshot = previous,
                legacyBaseline = legacyBaseline
            )
        )
        saveArraySync("therapy_history", list.sortedBy { it.timestampMillis }.takeLast(5000).map { it.toJson() })
    }

    fun getIntakes(): List<IntakeEvent> = parseArray("intakes") { IntakeEvent.fromJson(it) }

    fun recordIntake(event: IntakeEvent) {
        val list = getIntakes().filterNot { it.key == event.key }.toMutableList()
        list.add(event)
        val trimmed = list.sortedByDescending { it.takenAtMillis }.take(5000)
        saveArray("intakes", trimmed.map { it.toJson() })
    }


    fun updateIntake(event: IntakeEvent) {
        val list = getIntakes().toMutableList()
        val idx = list.indexOfFirst { it.id == event.id }
        if (idx >= 0) {
            list[idx] = event
        } else {
            list.add(event)
        }
        saveArray("intakes", list.sortedByDescending { it.takenAtMillis }.take(5000).map { it.toJson() })
    }

    fun deleteIntake(id: Long) {
        saveArray("intakes", getIntakes().filterNot { it.id == id }.map { it.toJson() })
    }

    fun removeIntake(medicationId: Long, epochDay: Long, plannedTime: String) {
        saveArray(
            "intakes",
            getIntakes().filterNot {
                it.medicationId == medicationId && it.plannedEpochDay == epochDay && it.plannedTime == plannedTime
            }.map { it.toJson() }
        )
    }

    fun isTaken(medicationId: Long, epochDay: Long, plannedTime: String): Boolean =
        getIntakes().any { it.medicationId == medicationId && it.plannedEpochDay == epochDay && it.plannedTime == plannedTime }

    fun getPackageIntakesUsed(medication: Medication): Int {
        if (medication.packageDurationMode != PackageDurationMode.INTAKES || medication.lastPackageChangeEpochDay == null) return 0
        val startMillis = medication.lastPackageChangeMillis ?: LocalDate
            .ofEpochDay(medication.lastPackageChangeEpochDay)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        return getIntakes().count { it.medicationId == medication.id && it.takenAtMillis >= startMillis }
    }

    fun getPackageIntakesRemaining(medication: Medication): Int? {
        if (medication.packageDurationMode != PackageDurationMode.INTAKES || medication.lastPackageChangeEpochDay == null) return null
        return medication.packageMaxIntakes - getPackageIntakesUsed(medication) + medication.packageIntakesAdjustment
    }

    private fun getCountdownsRaw(): List<ActiveCountdown> = parseArray("countdowns") { ActiveCountdown.fromJson(it) }

    fun getCountdowns(): List<ActiveCountdown> = getCountdownsRaw().sortedBy { it.endMillis }

    fun getActiveCountdowns(now: Long = System.currentTimeMillis()): List<ActiveCountdown> =
        getCountdownsRaw().filter { it.endMillis > now }.sortedBy { it.endMillis }

    fun getCountdown(id: Long): ActiveCountdown? = getCountdownsRaw().firstOrNull { it.id == id }

    fun getCountdownsForIntake(medicationId: Long, epochDay: Long, plannedTime: String): List<ActiveCountdown> =
        getCountdownsRaw().filter {
            it.medicationId == medicationId && it.plannedEpochDay == epochDay && it.plannedTime == plannedTime
        }

    fun addCountdown(countdown: ActiveCountdown) {
        val list = getCountdownsRaw().filterNot { it.id == countdown.id }.toMutableList()
        list.add(countdown)
        // Countdown state is critical: commit synchronously so it survives an immediate
        // page change, app backgrounding or process termination.
        saveArraySync("countdowns", list.map { it.toJson() })
    }

    fun removeCountdown(id: Long) {
        saveArraySync("countdowns", getCountdownsRaw().filterNot { it.id == id }.map { it.toJson() })
    }

    fun getPendingSnoozes(): List<PendingSnooze> =
        parseArray("snoozes") { PendingSnooze.fromJson(it) }.sortedBy { it.triggerAtMillis }

    fun getPendingSnoozesForMedication(medicationId: Long): List<PendingSnooze> =
        getPendingSnoozes().filter { it.medicationId == medicationId }

    fun upsertPendingSnooze(snooze: PendingSnooze) {
        val list = getPendingSnoozes().filterNot { it.key == snooze.key }.toMutableList()
        list.add(snooze)
        saveArraySync("snoozes", list.map { it.toJson() })
    }

    fun removePendingSnooze(medicationId: Long, epochDay: Long, plannedTime: String) {
        saveArraySync(
            "snoozes",
            getPendingSnoozes().filterNot {
                it.medicationId == medicationId &&
                    it.plannedEpochDay == epochDay &&
                    it.plannedTime == plannedTime
            }.map { it.toJson() }
        )
    }


    fun getLastPackageReminderEpochDay(medicationId: Long): Long? {
        val key = "package_reminder_last_$medicationId"
        return if (prefs.contains(key)) prefs.getLong(key, Long.MIN_VALUE) else null
    }

    fun markPackageReminderShown(medicationId: Long, epochDay: Long) {
        prefs.edit().putLong("package_reminder_last_$medicationId", epochDay).apply()
    }

    fun clearPackageReminderState(medicationId: Long) {
        prefs.edit()
            .remove("package_reminder_last_$medicationId")
            .remove("package_reminder_remaining_$medicationId")
            .apply()
    }

    fun getLastPackageReminderRemaining(medicationId: Long): Int? {
        val key = "package_reminder_remaining_$medicationId"
        return if (prefs.contains(key)) prefs.getInt(key, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE } else null
    }

    fun markPackageReminderRemaining(medicationId: Long, remaining: Int) {
        prefs.edit().putInt("package_reminder_remaining_$medicationId", remaining).apply()
    }

    fun clearPackageReminderRemaining(medicationId: Long) {
        prefs.edit().remove("package_reminder_remaining_$medicationId").apply()
    }

    fun getLastStockReminderEpochDay(medicationId: Long): Long? {
        val key = "stock_reminder_last_$medicationId"
        return if (prefs.contains(key)) prefs.getLong(key, Long.MIN_VALUE) else null
    }

    fun markStockReminderShown(medicationId: Long, epochDay: Long) {
        prefs.edit().putLong("stock_reminder_last_$medicationId", epochDay).apply()
    }

    fun clearStockReminderState(medicationId: Long) {
        prefs.edit().remove("stock_reminder_last_$medicationId").apply()
    }

    fun mergeImportedIntakes(events: List<IntakeEvent>): Int {
        val existing = getIntakes().toMutableList()
        val fingerprints = existing.mapTo(mutableSetOf()) { it.historyFingerprint() }
        var added = 0
        events.forEach { event ->
            if (fingerprints.add(event.historyFingerprint())) {
                existing.add(event)
                added++
            }
        }
        saveArray("intakes", existing.sortedByDescending { it.takenAtMillis }.take(5000).map { it.toJson() })
        return added
    }

    fun replaceImportedIntakes(events: List<IntakeEvent>) {
        val unique = LinkedHashMap<String, IntakeEvent>()
        events.sortedBy { it.takenAtMillis }.forEach { unique[it.historyFingerprint()] = it }
        saveArray("intakes", unique.values.sortedByDescending { it.takenAtMillis }.take(5000).map { it.toJson() })
    }

    private fun IntakeEvent.historyFingerprint(): String = buildString {
        append(medicationName.trim().lowercase())
        append('|').append(takenAtMillis)
        append('|').append(plannedEpochDay)
        append('|').append(plannedTime.trim())
    }

    private fun <T> parseArray(key: String, mapper: (org.json.JSONObject) -> T): List<T> {
        val raw = prefs.getString(key, "[]") ?: "[]"
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = runCatching { mapper(array.getJSONObject(i)) }.getOrNull()
                if (item != null) add(item)
            }
        }
    }

    private fun saveArray(key: String, objects: List<org.json.JSONObject>) {
        val a = JSONArray()
        objects.forEach { a.put(it) }
        prefs.edit().putString(key, a.toString()).apply()
    }

    private fun saveArraySync(key: String, objects: List<org.json.JSONObject>) {
        val a = JSONArray()
        objects.forEach { a.put(it) }
        prefs.edit().putString(key, a.toString()).commit()
    }
}
