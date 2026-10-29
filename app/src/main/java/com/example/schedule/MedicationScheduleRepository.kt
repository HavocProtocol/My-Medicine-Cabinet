package com.example.schedule

import com.example.data.Alert
import com.example.data.AppDao
import com.example.data.DoseRecord
import com.example.data.InventoryItem
import com.example.data.MedicationSchedule
import kotlinx.coroutines.flow.Flow
import java.util.TimeZone

class MedicationScheduleRepository(
    private val dao: AppDao,
    private val notificationScheduler: ReminderNotificationScheduler? = null
) {

    fun observeSchedulesForUser(userId: String): Flow<List<MedicationSchedule>> =
        dao.getSchedulesForUser(userId)

    fun observeDoseRecordsForDate(userId: String, dateIso: String): Flow<List<DoseRecord>> =
        dao.getDoseRecordsForDate(userId, dateIso)

    fun observeAllDoseRecordsForUser(userId: String): Flow<List<DoseRecord>> =
        dao.getDoseRecordsForUser(userId)

    /**
     * Resolves the authoritative expiration timestamp for a schedule from the cabinet's InventoryItem
     * (if linked) or from the schedule's recordedExpiryDateMillis.
     */
    suspend fun resolveExpiryMillis(
        medicineId: Int?,
        inventoryItemId: Int?,
        fallbackRecordedExpiry: Long?
    ): Long? {
        if (inventoryItemId != null && inventoryItemId > 0) {
            val item = dao.getInventoryItemById(inventoryItemId)
            if (item != null && item.expiryDate > 0L) {
                return item.expiryDate
            }
        }
        if (medicineId != null && medicineId > 0) {
            val items = dao.getInventoryItemsForMedicineSnapshot(medicineId)
            val validOrLatest = items.maxByOrNull { it.expiryDate }
            if (validOrLatest != null && validOrLatest.expiryDate > 0L) {
                return validOrLatest.expiryDate
            }
        }
        return fallbackRecordedExpiry?.takeIf { it > 0L }
    }

    /**
     * Resolves the primary [InventoryItem] for a medicine.
     */
    suspend fun resolveInventoryItem(medicineId: Int?, inventoryItemId: Int?): InventoryItem? {
        if (inventoryItemId != null && inventoryItemId > 0) {
            val byId = dao.getInventoryItemById(inventoryItemId)
            if (byId != null) return byId
        }
        if (medicineId != null && medicineId > 0) {
            return dao.getInventoryItemsForMedicineSnapshot(medicineId).maxByOrNull { it.expiryDate }
        }
        return null
    }

    /**
     * Creates a new medication schedule after strict validation and expiry protection.
     */
    suspend fun createSchedule(
        draft: ScheduleDraftInput,
        nowMillis: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): Result<MedicationSchedule> {
        val registeredMedicine = draft.medicineId?.takeIf { it > 0 }?.let { dao.getMedicineById(it) }
        val authoritativeExpiry = resolveExpiryMillis(
            medicineId = registeredMedicine?.id,
            inventoryItemId = draft.inventoryItemId,
            fallbackRecordedExpiry = draft.recordedExpiryDateMillis
        )
        val enrichedDraft = draft.copy(
            medicineId = registeredMedicine?.id,
            medicineName = registeredMedicine?.tradeName ?: "",
            strength = registeredMedicine?.strength ?: draft.strength,
            formulation = registeredMedicine?.form ?: draft.formulation,
            recordedExpiryDateMillis = authoritativeExpiry
        )

        val validation = MedicationScheduleEngine.validateScheduleDraft(
            draft = enrichedDraft,
            referenceTimeMillis = nowMillis,
            timeZone = timeZone
        )
        if (!validation.isValid || registeredMedicine == null) {
            return Result.failure(ScheduleValidationException(validation))
        }

        // If the user supplied/confirmed an expiry date that wasn't on the inventory item yet, update inventory
        if (authoritativeExpiry != null && authoritativeExpiry > 0L) {
            val existingInv = resolveInventoryItem(registeredMedicine.id, draft.inventoryItemId)
            if (existingInv != null && existingInv.expiryDate <= 0L) {
                dao.updateInventoryItem(existingInv.copy(expiryDate = authoritativeExpiry))
            }
        }

        val resolvedInv = resolveInventoryItem(registeredMedicine.id, draft.inventoryItemId)
        val sortedSlots = enrichedDraft.timeSlots.sortedBy { it.timeHm }.distinctBy { it.timeHm }

        val entity = MedicationSchedule(
            userId = enrichedDraft.userId,
            familyMemberId = enrichedDraft.familyMemberId,
            medicineId = registeredMedicine.id,
            inventoryItemId = resolvedInv?.id ?: enrichedDraft.inventoryItemId,
            medicineName = registeredMedicine.tradeName.trim(),
            strength = registeredMedicine.strength?.trim()?.ifBlank { null },
            formulation = registeredMedicine.form.trim().ifBlank { null },
            defaultDosage = enrichedDraft.defaultDosage,
            dosageUnit = enrichedDraft.dosageUnit.trim(),
            routeOfAdministration = draft.routeOfAdministration?.trim()?.ifBlank { null },
            instructions = draft.instructions?.trim()?.ifBlank { null },
            userNotes = draft.userNotes?.trim()?.ifBlank { null },
            startDateIso = draft.startDateIso.trim(),
            endDateIso = draft.endDateIso?.trim()?.ifBlank { null },
            frequencyType = draft.frequency.key,
            selectedDaysMask = if (draft.frequency == ScheduleFrequency.SELECTED_DAYS) {
                draft.selectedDaysOfWeek.sorted().joinToString(",")
            } else null,
            intervalDays = if (draft.frequency == ScheduleFrequency.INTERVAL_DAYS ||
                draft.frequency == ScheduleFrequency.CUSTOM_REPEAT
            ) {
                (draft.intervalDays ?: 1).coerceAtLeast(1)
            } else null,
            doseTimesJson = ScheduledTimeSlot.encodeList(sortedSlots),
            remindersEnabled = draft.remindersEnabled,
            status = ScheduleStatus.ACTIVE.key,
            blockedReason = null,
            autoDeductInventory = draft.autoDeductInventory,
            lowStockThreshold = draft.lowStockThreshold,
            snoozeMinutes = draft.snoozeMinutes,
            missedDoseThresholdMinutes = draft.missedDoseThresholdMinutes,
            recordedExpiryDateMillis = authoritativeExpiry,
            createdAt = nowMillis,
            updatedAt = nowMillis
        )

        val newId = dao.insertSchedule(entity).toInt()
        val savedSchedule = entity.copy(id = newId)

        // Generate occurrences for today..+7 days (and startDate if within range)
        val todayIso = MedicationScheduleEngine.millisToLocalDateIso(nowMillis, timeZone)
        ensureOccurrencesGeneratedForRange(
            userId = savedSchedule.userId,
            fromDateIso = minOf(todayIso, savedSchedule.startDateIso),
            daysCount = 8,
            nowMillis = nowMillis,
            timeZone = timeZone
        )

        return Result.success(savedSchedule)
    }

    /**
     * Updates an existing schedule, preserving historical dose records and rescheduling future ones.
     */
    suspend fun updateSchedule(
        draft: ScheduleDraftInput,
        nowMillis: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): Result<MedicationSchedule> {
        val scheduleId = draft.scheduleId
            ?: return Result.failure(IllegalArgumentException("Schedule ID is required for update"))
        val existing = dao.getScheduleById(scheduleId, draft.userId)
            ?: return Result.failure(IllegalArgumentException("Schedule not found for user"))

        val targetMedicineId = draft.medicineId ?: existing.medicineId
        val registeredMedicine = targetMedicineId?.takeIf { it > 0 }?.let { dao.getMedicineById(it) }
        val authoritativeExpiry = resolveExpiryMillis(
            medicineId = registeredMedicine?.id,
            inventoryItemId = draft.inventoryItemId,
            fallbackRecordedExpiry = draft.recordedExpiryDateMillis ?: existing.recordedExpiryDateMillis
        )
        val enrichedDraft = draft.copy(
            medicineId = registeredMedicine?.id,
            medicineName = registeredMedicine?.tradeName ?: "",
            strength = registeredMedicine?.strength ?: draft.strength,
            formulation = registeredMedicine?.form ?: draft.formulation,
            recordedExpiryDateMillis = authoritativeExpiry
        )

        val validation = MedicationScheduleEngine.validateScheduleDraft(
            draft = enrichedDraft,
            referenceTimeMillis = nowMillis,
            timeZone = timeZone
        )
        if (!validation.isValid || registeredMedicine == null) {
            return Result.failure(ScheduleValidationException(validation))
        }

        // Cancel existing alarms for this schedule
        val existingDoses = dao.getDoseRecordsForScheduleSnapshot(scheduleId)
        notificationScheduler?.cancelAllAlarmsForSchedule(scheduleId, existingDoses)

        // Delete only future PENDING doses (preserving all TAKEN, SKIPPED, MISSED, BLOCKED and past records)
        dao.deleteFuturePendingDosesForSchedule(scheduleId, nowMillis)
        // Also delete today's pending doses that haven't been acted upon if their time slot was removed
        val sortedSlots = enrichedDraft.timeSlots.sortedBy { it.timeHm }.distinctBy { it.timeHm }
        val newSlotTimes = sortedSlots.map { it.timeHm }.toSet()
        existingDoses.filter {
            it.status == DoseStatus.PENDING.key && it.scheduledTimeHm !in newSlotTimes
        }.forEach { obsoletePending ->
            notificationScheduler?.cancelDoseAlarm(obsoletePending.occurrenceKey, obsoletePending.notificationId)
        }
        dao.deleteAllPendingDosesForSchedule(scheduleId)

        val resolvedInv = resolveInventoryItem(registeredMedicine.id, enrichedDraft.inventoryItemId)
        val updated = existing.copy(
            familyMemberId = enrichedDraft.familyMemberId,
            medicineId = registeredMedicine.id,
            inventoryItemId = resolvedInv?.id ?: enrichedDraft.inventoryItemId,
            medicineName = registeredMedicine.tradeName.trim(),
            strength = registeredMedicine.strength?.trim()?.ifBlank { null },
            formulation = registeredMedicine.form.trim().ifBlank { null },
            defaultDosage = enrichedDraft.defaultDosage,
            dosageUnit = enrichedDraft.dosageUnit.trim(),
            routeOfAdministration = draft.routeOfAdministration?.trim()?.ifBlank { null },
            instructions = draft.instructions?.trim()?.ifBlank { null },
            userNotes = draft.userNotes?.trim()?.ifBlank { null },
            startDateIso = draft.startDateIso.trim(),
            endDateIso = draft.endDateIso?.trim()?.ifBlank { null },
            frequencyType = draft.frequency.key,
            selectedDaysMask = if (draft.frequency == ScheduleFrequency.SELECTED_DAYS) {
                draft.selectedDaysOfWeek.sorted().joinToString(",")
            } else null,
            intervalDays = if (draft.frequency == ScheduleFrequency.INTERVAL_DAYS ||
                draft.frequency == ScheduleFrequency.CUSTOM_REPEAT
            ) {
                (draft.intervalDays ?: 1).coerceAtLeast(1)
            } else null,
            doseTimesJson = ScheduledTimeSlot.encodeList(sortedSlots),
            remindersEnabled = draft.remindersEnabled,
            status = ScheduleStatus.ACTIVE.key,
            blockedReason = null,
            autoDeductInventory = draft.autoDeductInventory,
            lowStockThreshold = draft.lowStockThreshold,
            snoozeMinutes = draft.snoozeMinutes,
            missedDoseThresholdMinutes = draft.missedDoseThresholdMinutes,
            recordedExpiryDateMillis = authoritativeExpiry,
            updatedAt = nowMillis
        )

        dao.updateSchedule(updated)

        val todayIso = MedicationScheduleEngine.millisToLocalDateIso(nowMillis, timeZone)
        ensureOccurrencesGeneratedForRange(
            userId = updated.userId,
            fromDateIso = todayIso,
            daysCount = 8,
            nowMillis = nowMillis,
            timeZone = timeZone
        )

        return Result.success(updated)
    }

    /**
     * Pauses an active schedule and cancels all outstanding alarms.
     */
    suspend fun pauseSchedule(
        scheduleId: Int,
        userId: String,
        nowMillis: Long = System.currentTimeMillis()
    ): Result<MedicationSchedule> {
        val existing = dao.getScheduleById(scheduleId, userId)
            ?: return Result.failure(IllegalArgumentException("Schedule not found"))

        val doses = dao.getDoseRecordsForScheduleSnapshot(scheduleId)
        notificationScheduler?.cancelAllAlarmsForSchedule(scheduleId, doses)

        val paused = existing.copy(
            status = ScheduleStatus.PAUSED.key,
            remindersEnabled = false,
            updatedAt = nowMillis
        )
        dao.updateSchedule(paused)
        return Result.success(paused)
    }

    /**
     * Resumes a paused schedule ONLY if the medicine passes strict expiration validation.
     * Cannot be used to bypass expired-medicine protection.
     */
    suspend fun resumeSchedule(
        scheduleId: Int,
        userId: String,
        nowMillis: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): Result<MedicationSchedule> {
        val existing = dao.getScheduleById(scheduleId, userId)
            ?: return Result.failure(IllegalArgumentException("Schedule not found"))

        val authoritativeExpiry = resolveExpiryMillis(
            medicineId = existing.medicineId,
            inventoryItemId = existing.inventoryItemId,
            fallbackRecordedExpiry = existing.recordedExpiryDateMillis
        )
        val expiryEval = MedicationScheduleEngine.evaluateExpirySafety(
            medicineName = existing.medicineName,
            expiryDateMillis = authoritativeExpiry,
            referenceTimeMillis = nowMillis,
            timeZone = timeZone
        )

        if (!expiryEval.canActivateSchedule) {
            val blockedReason = if (expiryEval.status == ExpirySafetyStatus.EXPIRED && expiryEval.expiryDateIso != null) {
                MedicationScheduleEngine.buildExpiredWarningMessage(existing.medicineName, expiryEval.expiryDateIso)
            } else {
                "Cannot activate schedule: expiration status (${expiryEval.status}) could not be verified."
            }
            val blocked = existing.copy(
                status = ScheduleStatus.BLOCKED_EXPIRED.key,
                remindersEnabled = false,
                blockedReason = blockedReason,
                recordedExpiryDateMillis = authoritativeExpiry,
                updatedAt = nowMillis
            )
            dao.updateSchedule(blocked)
            val errors = when (expiryEval.status) {
                ExpirySafetyStatus.EXPIRED -> listOf(ScheduleValidationError.MEDICINE_EXPIRED)
                ExpirySafetyStatus.MISSING_EXPIRY -> listOf(ScheduleValidationError.MEDICINE_EXPIRY_MISSING)
                ExpirySafetyStatus.INVALID_EXPIRY -> listOf(ScheduleValidationError.MEDICINE_EXPIRY_INVALID)
                else -> listOf(ScheduleValidationError.MEDICINE_EXPIRED)
            }
            return Result.failure(
                ScheduleValidationException(
                    ScheduleValidationResult(isValid = false, errors = errors, expiryEvaluation = expiryEval)
                )
            )
        }

        val resumed = existing.copy(
            status = ScheduleStatus.ACTIVE.key,
            remindersEnabled = true,
            blockedReason = null,
            recordedExpiryDateMillis = authoritativeExpiry,
            updatedAt = nowMillis
        )
        dao.updateSchedule(resumed)

        val todayIso = MedicationScheduleEngine.millisToLocalDateIso(nowMillis, timeZone)
        ensureOccurrencesGeneratedForRange(
            userId = userId,
            fromDateIso = todayIso,
            daysCount = 8,
            nowMillis = nowMillis,
            timeZone = timeZone
        )
        return Result.success(resumed)
    }

    /**
     * Removes/discontinues a schedule without deleting the medicine from the cabinet inventory,
     * while preserving historical dose records.
     */
    suspend fun removeSchedule(
        scheduleId: Int,
        userId: String,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        val existing = dao.getScheduleById(scheduleId, userId) ?: return false
        val doses = dao.getDoseRecordsForScheduleSnapshot(scheduleId)
        notificationScheduler?.cancelAllAlarmsForSchedule(scheduleId, doses)

        // Delete only PENDING occurrences; keep TAKEN/SKIPPED/MISSED/BLOCKED for history
        dao.deleteAllPendingDosesForSchedule(scheduleId)

        val remainingHistory = dao.getDoseRecordsForScheduleSnapshot(scheduleId)
        if (remainingHistory.isEmpty()) {
            dao.deleteScheduleById(scheduleId, userId)
        } else {
            dao.updateSchedule(
                existing.copy(
                    status = ScheduleStatus.DISCONTINUED.key,
                    remindersEnabled = false,
                    updatedAt = nowMillis
                )
            )
        }
        return true
    }

    /**
     * Continuously checks all schedules for [userId] against their medicine's expiration date.
     * Automatically suspends/blocks any schedule whose medicine has expired and cancels future reminders.
     */
    suspend fun evaluateAndEnforceExpiryProtection(
        userId: String,
        nowMillis: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): List<MedicationSchedule> {
        val schedules = dao.getSchedulesForUserSnapshot(userId)
        val newlyBlocked = mutableListOf<MedicationSchedule>()
        val todayIso = MedicationScheduleEngine.millisToLocalDateIso(nowMillis, timeZone)

        for (schedule in schedules) {
            if (schedule.status == ScheduleStatus.DISCONTINUED.key ||
                schedule.status == ScheduleStatus.COMPLETED.key
            ) continue

            // Check if schedule has passed its endDateIso
            if (!schedule.endDateIso.isNullOrBlank() && todayIso > schedule.endDateIso &&
                schedule.status == ScheduleStatus.ACTIVE.key
            ) {
                val doses = dao.getDoseRecordsForScheduleSnapshot(schedule.id)
                notificationScheduler?.cancelAllAlarmsForSchedule(schedule.id, doses)
                dao.updateSchedule(schedule.copy(status = ScheduleStatus.COMPLETED.key, updatedAt = nowMillis))
                continue
            }

            val latestExpiry = resolveExpiryMillis(
                medicineId = schedule.medicineId,
                inventoryItemId = schedule.inventoryItemId,
                fallbackRecordedExpiry = schedule.recordedExpiryDateMillis
            )

            val expiryEval = MedicationScheduleEngine.evaluateExpirySafety(
                medicineName = schedule.medicineName,
                expiryDateMillis = latestExpiry,
                referenceTimeMillis = nowMillis,
                timeZone = timeZone
            )

            if (!expiryEval.canActivateSchedule) {
                val expiryIso = expiryEval.expiryDateIso ?: "Unknown"
                val reason = MedicationScheduleEngine.buildExpiredWarningMessage(
                    medicineName = schedule.medicineName,
                    expiryDateIso = expiryIso
                )

                val doses = dao.getDoseRecordsForScheduleSnapshot(schedule.id)
                notificationScheduler?.cancelAllAlarmsForSchedule(schedule.id, doses)

                // Block any PENDING doses for this expired medicine
                for (dose in doses) {
                    if (dose.status == DoseStatus.PENDING.key) {
                        dao.updateDoseRecord(
                            dose.copy(
                                status = DoseStatus.BLOCKED.key,
                                updatedAt = nowMillis
                            )
                        )
                    }
                }

                if (schedule.status != ScheduleStatus.BLOCKED_EXPIRED.key) {
                    val blockedSched = schedule.copy(
                        status = ScheduleStatus.BLOCKED_EXPIRED.key,
                        remindersEnabled = false,
                        blockedReason = reason,
                        recordedExpiryDateMillis = latestExpiry,
                        updatedAt = nowMillis
                    )
                    dao.updateSchedule(blockedSched)
                    newlyBlocked.add(blockedSched)

                    // Insert cabinet alert if inventoryItemId is known
                    if (schedule.inventoryItemId != null && schedule.inventoryItemId > 0) {
                        val existingAlert = dao.getAlertByItemAndType(schedule.inventoryItemId, "SCHEDULE_EXPIRED_BLOCKED")
                        if (existingAlert == null) {
                            dao.insertAlert(
                                Alert(
                                    inventoryItemId = schedule.inventoryItemId,
                                    type = "SCHEDULE_EXPIRED_BLOCKED",
                                    triggerDate = nowMillis,
                                    isRead = false,
                                    createdAt = nowMillis
                                )
                            )
                        }
                    }

                    notificationScheduler?.showExpirySuspendedNotification(
                        medicineName = schedule.medicineName,
                        expiryDateIso = expiryIso,
                        scheduleId = schedule.id
                    )
                }
            }
        }
        return newlyBlocked
    }

    /**
     * Generates deterministic [DoseRecord] occurrences for [fromDateIso] .. [fromDateIso + daysCount - 1],
     * marks overdue PENDING doses as MISSED, and schedules future alarms.
     */
    suspend fun ensureOccurrencesGeneratedForRange(
        userId: String,
        fromDateIso: String,
        daysCount: Int = 7,
        nowMillis: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ) {
        evaluateAndEnforceExpiryProtection(userId, nowMillis, timeZone)
        val schedules = dao.getSchedulesForUserSnapshot(userId)

        for (dayOffset in 0 until daysCount.coerceAtLeast(1)) {
            val dateIso = MedicationScheduleEngine.addDaysToIso(fromDateIso, dayOffset)
            for (schedule in schedules) {
                if (schedule.status != ScheduleStatus.ACTIVE.key &&
                    schedule.status != ScheduleStatus.BLOCKED_EXPIRED.key
                ) {
                    continue
                }
                val latestExpiry = resolveExpiryMillis(
                    medicineId = schedule.medicineId,
                    inventoryItemId = schedule.inventoryItemId,
                    fallbackRecordedExpiry = schedule.recordedExpiryDateMillis
                )
                val records = MedicationScheduleEngine.buildDoseRecordsForScheduleOnDate(
                    schedule = schedule,
                    dateIso = dateIso,
                    latestExpiryMillis = latestExpiry,
                    referenceTimeMillis = nowMillis,
                    timeZone = timeZone
                )
                for (record in records) {
                    val existing = dao.getDoseRecordByOccurrenceKey(record.occurrenceKey)
                    if (existing == null) {
                        val insertedId = dao.insertDoseRecordIgnore(record).toInt()
                        if (insertedId > 0) {
                            val saved = record.copy(id = insertedId)
                            if (saved.status == DoseStatus.PENDING.key) {
                                notificationScheduler?.scheduleDoseAlarm(schedule, saved, nowMillis)
                            }
                        }
                    } else {
                        // Synchronize timestamps if timezone changed and record is still PENDING
                        var current = existing
                        if (current.status == DoseStatus.PENDING.key &&
                            current.scheduledTimestampMillis != record.scheduledTimestampMillis
                        ) {
                            current = current.copy(
                                scheduledTimestampMillis = record.scheduledTimestampMillis,
                                updatedAt = nowMillis
                            )
                            dao.updateDoseRecord(current)
                        }
                        if (record.status == DoseStatus.BLOCKED.key && current.status == DoseStatus.PENDING.key) {
                            current = current.copy(status = DoseStatus.BLOCKED.key, updatedAt = nowMillis)
                            dao.updateDoseRecord(current)
                            notificationScheduler?.cancelDoseAlarm(current.occurrenceKey, current.notificationId)
                        } else if (current.status == DoseStatus.PENDING.key) {
                            notificationScheduler?.scheduleDoseAlarm(schedule, current, nowMillis)
                        }
                    }
                }
            }
        }

        evaluateMissedDoses(userId, nowMillis)
    }

    /**
     * Marks any overdue PENDING doses for [userId] as MISSED according to each schedule's threshold.
     * Never automatically marks a dose as TAKEN.
     */
    suspend fun evaluateMissedDoses(
        userId: String,
        nowMillis: Long = System.currentTimeMillis()
    ): Int {
        val schedulesById = dao.getSchedulesForUserSnapshot(userId).associateBy { it.id }
        val allDoses = dao.getDoseRecordsForUserSnapshot(userId)
        var missedUpdatedCount = 0

        for (dose in allDoses) {
            if (dose.status != DoseStatus.PENDING.key) continue
            val schedule = schedulesById[dose.scheduleId]
            val thresholdMins = schedule?.missedDoseThresholdMinutes ?: 120
            if (MedicationScheduleEngine.shouldMarkDoseAsMissed(dose, thresholdMins, nowMillis)) {
                val updated = dose.copy(
                    status = DoseStatus.MISSED.key,
                    updatedAt = nowMillis
                )
                dao.updateDoseRecord(updated)
                notificationScheduler?.cancelDoseAlarm(dose.occurrenceKey, dose.notificationId)
                missedUpdatedCount++
            }
        }
        return missedUpdatedCount
    }

    /**
     * Marks a dose occurrence as TAKEN, optionally deducting from cabinet inventory (at most once per occurrence).
     */
    suspend fun markDoseTaken(
        doseRecordId: Int,
        userId: String,
        notes: String? = null,
        nowMillis: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): Result<DoseRecord> {
        val dose = dao.getDoseRecordById(doseRecordId, userId)
            ?: return Result.failure(IllegalArgumentException("Dose record not found for user"))

        val schedule = dao.getScheduleById(dose.scheduleId, userId)

        // Check expiration safety before allowing dose to be marked TAKEN
        val latestExpiry = resolveExpiryMillis(
            medicineId = dose.medicineId ?: schedule?.medicineId,
            inventoryItemId = dose.inventoryItemId ?: schedule?.inventoryItemId,
            fallbackRecordedExpiry = schedule?.recordedExpiryDateMillis
        )
        val expiryEval = MedicationScheduleEngine.evaluateExpirySafety(
            medicineName = dose.medicineName,
            expiryDateMillis = latestExpiry,
            referenceTimeMillis = nowMillis,
            timeZone = timeZone
        )
        if (!expiryEval.canActivateSchedule || dose.status == DoseStatus.BLOCKED.key) {
            evaluateAndEnforceExpiryProtection(userId, nowMillis, timeZone)
            return Result.failure(IllegalStateException("Cannot mark dose as taken: medicine is expired or blocked."))
        }

        // Already TAKEN: idempotent return without double-deducting inventory
        if (dose.status == DoseStatus.TAKEN.key && dose.inventoryDeducted) {
            return Result.success(dose)
        }

        var deductedFlag = dose.inventoryDeducted
        var deductedAmt = dose.deductedAmount
        val shouldAutoDeduct = schedule?.autoDeductInventory ?: true

        var autoCompletedOneTimeSchedule = false

        if (shouldAutoDeduct && !deductedFlag) {
            val targetMedId = dose.medicineId ?: schedule?.medicineId
            val invItem = resolveInventoryItem(
                medicineId = targetMedId,
                inventoryItemId = dose.inventoryItemId ?: schedule?.inventoryItemId
            )
            if (invItem != null && invItem.quantity > 0.0) {
                val deduct = dose.configuredDosage.coerceAtMost(invItem.quantity).coerceAtLeast(0.0)
                val newQty = (invItem.quantity - deduct).coerceAtLeast(0.0)
                dao.updateInventoryQuantity(invItem.id, newQty)
                deductedFlag = true
                deductedAmt = deduct

                val medicine = targetMedId?.takeIf { it > 0 }?.let { dao.getMedicineById(it) }
                val classification = com.example.data.MedicationRefillClassification.fromKey(medicine?.refillClassification)
                val totalRemainingForMed = if (targetMedId != null && targetMedId > 0) {
                    dao.getInventoryItemsForMedicineSnapshot(targetMedId).sumOf { it.quantity.coerceAtLeast(0.0) }
                } else {
                    newQty
                }

                if (classification == com.example.data.MedicationRefillClassification.ONGOING_REFILL) {
                    val lowThreshold = schedule?.lowStockThreshold ?: 5.0
                    if (totalRemainingForMed <= lowThreshold) {
                        notificationScheduler?.showLowStockNotification(
                            medicineName = dose.medicineName,
                            remainingQty = totalRemainingForMed,
                            unit = invItem.unit?.ifBlank { dose.dosageUnit } ?: dose.dosageUnit,
                            medicineId = invItem.medicineId
                        )
                    }
                } else if (classification == com.example.data.MedicationRefillClassification.ONE_TIME && totalRemainingForMed <= 0.0) {
                    autoCompletedOneTimeSchedule = true
                }
            }
        }

        val updated = dose.copy(
            status = DoseStatus.TAKEN.key,
            actualTakenTimestampMillis = nowMillis,
            userNotes = notes?.trim()?.ifBlank { dose.userNotes } ?: dose.userNotes,
            inventoryDeducted = deductedFlag,
            deductedAmount = deductedAmt,
            snoozedUntilMillis = null,
            updatedAt = nowMillis
        )
        dao.updateDoseRecord(updated)
        notificationScheduler?.cancelDoseAlarm(updated.occurrenceKey, updated.notificationId)

        // If a ONE_TIME medication has finished its supply/course, complete the routine so it does not recur indefinitely
        if (autoCompletedOneTimeSchedule && schedule != null && schedule.status == ScheduleStatus.ACTIVE.key) {
            val allScheduleDoses = dao.getDoseRecordsForScheduleSnapshot(schedule.id)
            val pendingDoses = allScheduleDoses.filter { it.status == DoseStatus.PENDING.key && it.id != updated.id }
            notificationScheduler?.cancelAllAlarmsForSchedule(schedule.id, pendingDoses)
            dao.deleteAllPendingDosesForSchedule(schedule.id)
            dao.updateSchedule(
                schedule.copy(
                    status = ScheduleStatus.COMPLETED.key,
                    updatedAt = nowMillis
                )
            )
        }

        return Result.success(updated)
    }

    /**
     * Marks a dose occurrence as SKIPPED. If inventory had previously been deducted for this dose, restores it.
     */
    suspend fun markDoseSkipped(
        doseRecordId: Int,
        userId: String,
        notes: String? = null,
        nowMillis: Long = System.currentTimeMillis()
    ): Result<DoseRecord> {
        val dose = dao.getDoseRecordById(doseRecordId, userId)
            ?: return Result.failure(IllegalArgumentException("Dose record not found for user"))

        var deductedFlag = dose.inventoryDeducted
        var deductedAmt = dose.deductedAmount

        if (deductedFlag && deductedAmt > 0.0) {
            val invItem = resolveInventoryItem(dose.medicineId, dose.inventoryItemId)
            if (invItem != null) {
                dao.updateInventoryQuantity(invItem.id, invItem.quantity + deductedAmt)
            }
            deductedFlag = false
            deductedAmt = 0.0
        }

        val updated = dose.copy(
            status = DoseStatus.SKIPPED.key,
            actualTakenTimestampMillis = null,
            userNotes = notes?.trim()?.ifBlank { dose.userNotes } ?: dose.userNotes,
            inventoryDeducted = deductedFlag,
            deductedAmount = deductedAmt,
            snoozedUntilMillis = null,
            updatedAt = nowMillis
        )
        dao.updateDoseRecord(updated)
        notificationScheduler?.cancelDoseAlarm(updated.occurrenceKey, updated.notificationId)
        return Result.success(updated)
    }

    /**
     * Snoozes a PENDING dose reminder ("Remind Me Later") for [snoozeMinutes].
     */
    suspend fun snoozeDose(
        doseRecordId: Int,
        userId: String,
        snoozeMinutes: Int = 15,
        nowMillis: Long = System.currentTimeMillis()
    ): Result<DoseRecord> {
        val dose = dao.getDoseRecordById(doseRecordId, userId)
            ?: return Result.failure(IllegalArgumentException("Dose record not found for user"))
        val schedule = dao.getScheduleById(dose.scheduleId, userId)
            ?: return Result.failure(IllegalArgumentException("Schedule not found"))

        if (dose.status != DoseStatus.PENDING.key) {
            return Result.failure(IllegalStateException("Only pending doses can be snoozed"))
        }

        val effectiveMinutes = snoozeMinutes.coerceIn(1, 240)
        val snoozeTargetMillis = nowMillis + effectiveMinutes * 60_000L

        notificationScheduler?.cancelDoseAlarm(dose.occurrenceKey, dose.notificationId)
        ReminderNotificationScheduler.deliveredNotificationKeys.remove(dose.occurrenceKey)

        val updated = dose.copy(
            snoozedUntilMillis = snoozeTargetMillis,
            notificationDeliveredAt = null,
            updatedAt = nowMillis
        )
        dao.updateDoseRecord(updated)
        notificationScheduler?.scheduleDoseAlarm(schedule, updated, nowMillis)
        return Result.success(updated)
    }

    /**
     * Replaces an expired/blocked schedule with a newly selected valid medicine and schedule configuration.
     * Never assumes clinical equivalence automatically; requires explicit replacement draft.
     */
    suspend fun replaceScheduleWithMedicine(
        oldScheduleId: Int,
        replacementDraft: ScheduleDraftInput,
        nowMillis: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): Result<MedicationSchedule> {
        val oldSchedule = dao.getScheduleById(oldScheduleId, replacementDraft.userId)
            ?: return Result.failure(IllegalArgumentException("Original schedule not found"))

        // Validate and create new schedule first so if validation fails, old state isn't lost
        val createdResult = createSchedule(
            draft = replacementDraft.copy(scheduleId = null),
            nowMillis = nowMillis,
            timeZone = timeZone
        )
        if (createdResult.isFailure) {
            return createdResult
        }

        // Discontinue old schedule while keeping its dose history intact
        val oldDoses = dao.getDoseRecordsForScheduleSnapshot(oldSchedule.id)
        notificationScheduler?.cancelAllAlarmsForSchedule(oldSchedule.id, oldDoses)
        dao.deleteAllPendingDosesForSchedule(oldSchedule.id)
        dao.updateSchedule(
            oldSchedule.copy(
                status = ScheduleStatus.DISCONTINUED.key,
                remindersEnabled = false,
                updatedAt = nowMillis
            )
        )

        return createdResult
    }

    /**
     * Synchronizes schedule state when a medicine is deleted from the cabinet.
     */
    suspend fun onMedicineDeletedFromCabinet(
        medicineId: Int,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        val linkedSchedules = dao.getSchedulesForMedicineSnapshot(medicineId)
        for (sched in linkedSchedules) {
            val doses = dao.getDoseRecordsForScheduleSnapshot(sched.id)
            notificationScheduler?.cancelAllAlarmsForSchedule(sched.id, doses)
            dao.deleteAllPendingDosesForSchedule(sched.id)
            dao.updateSchedule(
                sched.copy(
                    status = ScheduleStatus.DISCONTINUED.key,
                    remindersEnabled = false,
                    blockedReason = "Medicine removed from cabinet inventory",
                    updatedAt = nowMillis
                )
            )
        }
    }

    /**
     * Synchronizes schedule state when a medicine, its inventory quantity/expiry, or its refill
     * classification (ONGOING_REFILL vs ONE_TIME) is updated in the cabinet.
     */
    suspend fun onMedicineInventoryUpdated(
        medicineId: Int,
        nowMillis: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ) {
        val medicine = dao.getMedicineById(medicineId)
        val classification = com.example.data.MedicationRefillClassification.fromKey(medicine?.refillClassification)
        val totalRemainingStock = dao.getInventoryItemsForMedicineSnapshot(medicineId)
            .sumOf { it.quantity.coerceAtLeast(0.0) }
        val linkedSchedules = dao.getSchedulesForMedicineSnapshot(medicineId)
        val latestExpiry = resolveExpiryMillis(medicineId, null, null)
        val todayIso = MedicationScheduleEngine.millisToLocalDateIso(nowMillis, timeZone)

        for (sched in linkedSchedules) {
            if (sched.status == ScheduleStatus.DISCONTINUED.key) continue

            val isWithinEndDate = sched.endDateIso.isNullOrBlank() || todayIso <= sched.endDateIso

            if (classification == com.example.data.MedicationRefillClassification.ONE_TIME &&
                totalRemainingStock <= 0.0 &&
                sched.status == ScheduleStatus.ACTIVE.key
            ) {
                // One-time medication depleted -> mark routine COMPLETED and cancel future pending reminders
                val doses = dao.getDoseRecordsForScheduleSnapshot(sched.id)
                val pendingDoses = doses.filter { it.status == DoseStatus.PENDING.key }
                notificationScheduler?.cancelAllAlarmsForSchedule(sched.id, pendingDoses)
                dao.deleteAllPendingDosesForSchedule(sched.id)
                dao.updateSchedule(
                    sched.copy(
                        status = ScheduleStatus.COMPLETED.key,
                        recordedExpiryDateMillis = latestExpiry,
                        updatedAt = nowMillis
                    )
                )
            } else if (sched.status == ScheduleStatus.COMPLETED.key &&
                isWithinEndDate &&
                (totalRemainingStock > 0.0 || classification == com.example.data.MedicationRefillClassification.ONGOING_REFILL)
            ) {
                // Restocked or reclassified to ONGOING_REFILL -> reactivate if expiry is valid
                val expiryEval = MedicationScheduleEngine.evaluateExpirySafety(
                    medicineName = sched.medicineName,
                    expiryDateMillis = latestExpiry,
                    referenceTimeMillis = nowMillis,
                    timeZone = timeZone
                )
                if (expiryEval.canActivateSchedule) {
                    dao.updateSchedule(
                        sched.copy(
                            status = ScheduleStatus.ACTIVE.key,
                            recordedExpiryDateMillis = latestExpiry,
                            blockedReason = null,
                            updatedAt = nowMillis
                        )
                    )
                    ensureOccurrencesGeneratedForRange(
                        userId = sched.userId,
                        fromDateIso = todayIso,
                        daysCount = 8,
                        nowMillis = nowMillis,
                        timeZone = timeZone
                    )
                } else {
                    dao.updateSchedule(
                        sched.copy(
                            recordedExpiryDateMillis = latestExpiry,
                            updatedAt = nowMillis
                        )
                    )
                    evaluateAndEnforceExpiryProtection(sched.userId, nowMillis, timeZone)
                }
            } else {
                dao.updateSchedule(
                    sched.copy(
                        recordedExpiryDateMillis = latestExpiry,
                        updatedAt = nowMillis
                    )
                )
                evaluateAndEnforceExpiryProtection(sched.userId, nowMillis, timeZone)
                if (sched.status == ScheduleStatus.ACTIVE.key && totalRemainingStock > 0.0) {
                    ensureOccurrencesGeneratedForRange(
                        userId = sched.userId,
                        fromDateIso = todayIso,
                        daysCount = 8,
                        nowMillis = nowMillis,
                        timeZone = timeZone
                    )
                }
            }
        }
    }

    /**
     * Called by [ReminderBroadcastReceiver] when an alarm fires for an occurrence.
     * Verifies expiry protection before delivering the notification and records notificationDeliveredAt
     * so duplicate notifications are prevented.
     */
    suspend fun handleAlarmTrigger(
        occurrenceKey: String,
        nowMillis: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): Boolean {
        val dose = dao.getDoseRecordByOccurrenceKey(occurrenceKey) ?: return false
        val schedule = dao.getScheduleByIdAnyUser(dose.scheduleId) ?: return false

        // Enforce expiry protection before delivering any reminder
        evaluateAndEnforceExpiryProtection(schedule.userId, nowMillis, timeZone)

        val refreshedSchedule = dao.getScheduleByIdAnyUser(dose.scheduleId) ?: return false
        val refreshedDose = dao.getDoseRecordByOccurrenceKey(occurrenceKey) ?: return false

        if (refreshedSchedule.status != ScheduleStatus.ACTIVE.key || !refreshedSchedule.remindersEnabled) {
            return false
        }
        if (refreshedDose.status != DoseStatus.PENDING.key) {
            return false
        }

        // Prevent duplicate notification for the same scheduled dose
        if (refreshedDose.notificationDeliveredAt != null && refreshedDose.snoozedUntilMillis == null) {
            return false
        }

        val wasSnoozed = refreshedDose.snoozedUntilMillis != null
        val shown = notificationScheduler?.showDoseReminderNotification(
            schedule = refreshedSchedule,
            dose = refreshedDose,
            isSnoozeTrigger = wasSnoozed
        ) ?: false

        dao.updateDoseRecord(
            refreshedDose.copy(
                notificationDeliveredAt = nowMillis,
                snoozedUntilMillis = null,
                updatedAt = nowMillis
            )
        )
        return shown
    }
}

class ScheduleValidationException(
    val validationResult: ScheduleValidationResult
) : IllegalArgumentException(
    "Schedule validation failed: ${validationResult.errors.joinToString(", ")}"
)
