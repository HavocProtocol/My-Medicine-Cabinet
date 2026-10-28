package com.example.schedule

import com.example.data.DoseRecord
import com.example.data.InventoryItem
import com.example.data.MedicationSchedule
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

object MedicationScheduleEngine {

    private val ISO_DATE_REGEX = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")
    private val TIME_HM_REGEX = Regex("""^([01]\d|2[0-3]):([0-5]\d)$""")
    const val EXPIRING_SOON_DAYS = 30

    /**
     * Converts epoch milliseconds to "YYYY-MM-DD" in the given [timeZone].
     */
    fun millisToLocalDateIso(
        millis: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): String {
        val cal = Calendar.getInstance(timeZone, Locale.US).apply {
            timeInMillis = millis
        }
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        return String.format(Locale.US, "%04d-%02d-%02d", y, m, d)
    }

    /**
     * Converts epoch milliseconds to "HH:mm" in the given [timeZone].
     */
    fun millisToLocalTimeHm(
        millis: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): String {
        val cal = Calendar.getInstance(timeZone, Locale.US).apply {
            timeInMillis = millis
        }
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val min = cal.get(Calendar.MINUTE)
        return String.format(Locale.US, "%02d:%02d", h, min)
    }

    /**
     * Parses a strict "YYYY-MM-DD" string into (year, month 1..12, day 1..31), or null if invalid.
     */
    fun parseIsoDateParts(dateIso: String?): Triple<Int, Int, Int>? {
        if (dateIso.isNullOrBlank()) return null
        val match = ISO_DATE_REGEX.matchEntire(dateIso.trim()) ?: return null
        val year = match.groupValues[1].toIntOrNull() ?: return null
        val month = match.groupValues[2].toIntOrNull() ?: return null
        val day = match.groupValues[3].toIntOrNull() ?: return null
        if (year !in 1990..2100 || month !in 1..12 || day !in 1..31) return null

        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US).apply {
            isLenient = false
            clear()
            set(year, month - 1, day, 0, 0, 0)
        }
        return try {
            cal.timeInMillis // triggers validation when isLenient = false
            Triple(year, month, day)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parses "HH:mm" into (hour 0..23, minute 0..59), or null if invalid.
     */
    fun parseTimeHmParts(timeHm: String?): Pair<Int, Int>? {
        if (timeHm.isNullOrBlank()) return null
        val match = TIME_HM_REGEX.matchEntire(timeHm.trim()) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        return hour to minute
    }

    /**
     * Combines a local date ("YYYY-MM-DD") and local time ("HH:mm") into epoch millis
     * in the specified [timeZone], accounting for daylight saving time transitions.
     */
    fun combineDateAndTimeToMillis(
        dateIso: String,
        timeHm: String,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Long? {
        val (year, month, day) = parseIsoDateParts(dateIso) ?: return null
        val (hour, minute) = parseTimeHmParts(timeHm) ?: return null
        val cal = Calendar.getInstance(timeZone, Locale.US).apply {
            clear()
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    /**
     * Start of local calendar day (00:00:00.000) in [timeZone].
     */
    fun localDateIsoToStartOfDayMillis(
        dateIso: String,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Long? = combineDateAndTimeToMillis(dateIso, "00:00", timeZone)

    /**
     * End of local calendar day (23:59:59.999) in [timeZone].
     */
    fun localDateIsoToEndOfDayMillis(
        dateIso: String,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Long? {
        val (year, month, day) = parseIsoDateParts(dateIso) ?: return null
        val cal = Calendar.getInstance(timeZone, Locale.US).apply {
            clear()
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }
        return cal.timeInMillis
    }

    /**
     * Pure calendar day difference (endDateIso - startDateIso) unaffected by 23h/25h DST shifts.
     */
    fun daysBetween(startDateIso: String, endDateIso: String): Int {
        val (y1, m1, d1) = parseIsoDateParts(startDateIso) ?: return 0
        val (y2, m2, d2) = parseIsoDateParts(endDateIso) ?: return 0
        val utc = TimeZone.getTimeZone("UTC")
        val c1 = Calendar.getInstance(utc, Locale.US).apply {
            clear()
            set(y1, m1 - 1, d1, 12, 0, 0)
        }
        val c2 = Calendar.getInstance(utc, Locale.US).apply {
            clear()
            set(y2, m2 - 1, d2, 12, 0, 0)
        }
        val diffMs = c2.timeInMillis - c1.timeInMillis
        return (diffMs / (24.0 * 3600.0 * 1000.0)).roundToInt()
    }

    /**
     * Adds [daysToAdd] calendar days to [dateIso] and returns the resulting "YYYY-MM-DD".
     */
    fun addDaysToIso(dateIso: String, daysToAdd: Int): String {
        val (y, m, d) = parseIsoDateParts(dateIso) ?: return dateIso
        val utc = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(utc, Locale.US).apply {
            clear()
            set(y, m - 1, d, 12, 0, 0)
            add(Calendar.DAY_OF_MONTH, daysToAdd)
        }
        return String.format(
            Locale.US,
            "%04d-%02d-%02d",
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    /**
     * Returns ISO day of week: 1 = Monday, 2 = Tuesday, ..., 7 = Sunday.
     */
    fun getIsoDayOfWeek(dateIso: String): Int {
        val (y, m, d) = parseIsoDateParts(dateIso) ?: return 1
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US).apply {
            clear()
            set(y, m - 1, d, 12, 0, 0)
        }
        return when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 1
            Calendar.TUESDAY -> 2
            Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4
            Calendar.FRIDAY -> 5
            Calendar.SATURDAY -> 6
            Calendar.SUNDAY -> 7
            else -> 1
        }
    }

    /**
     * Evaluates a medicine's expiration status using the consistent Date-Only + timestamp rule.
     *
     * Rules:
     * 1. Missing expiryDateMillis (null or <= 0L) -> MISSING_EXPIRY (cannot activate without confirming/entering date).
     * 2. Invalid date (outside 1990..2100) -> INVALID_EXPIRY (cannot activate).
     * 3. Already expired (expiryDateMillis <= referenceTimeMillis OR expiryDateIso < referenceDateIso) -> EXPIRED (strictly blocked).
     * 4. Expiring within 30 days (daysRemaining in 0..30) -> EXPIRING_SOON (requires user review acknowledgment).
     * 5. Otherwise -> VALID.
     */
    fun evaluateExpirySafety(
        medicineName: String,
        expiryDateMillis: Long?,
        referenceTimeMillis: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): ExpiryEvaluationResult {
        if (expiryDateMillis == null || expiryDateMillis <= 0L) {
            return ExpiryEvaluationResult(
                status = ExpirySafetyStatus.MISSING_EXPIRY,
                medicineName = medicineName,
                expiryDateMillis = null,
                expiryDateIso = null,
                daysRemaining = null,
                canActivateSchedule = false,
                requiresExpiringSoonAcknowledgment = false,
                requiresExpiryEntry = true
            )
        }

        val expiryIso = millisToLocalDateIso(expiryDateMillis, timeZone)
        val parsedParts = parseIsoDateParts(expiryIso)
        if (parsedParts == null) {
            return ExpiryEvaluationResult(
                status = ExpirySafetyStatus.INVALID_EXPIRY,
                medicineName = medicineName,
                expiryDateMillis = expiryDateMillis,
                expiryDateIso = expiryIso,
                daysRemaining = null,
                canActivateSchedule = false,
                requiresExpiringSoonAcknowledgment = false,
                requiresExpiryEntry = true
            )
        }

        val referenceIso = millisToLocalDateIso(referenceTimeMillis, timeZone)
        val daysRem = daysBetween(referenceIso, expiryIso)

        // Strict expiry check: if calendar date is before today OR exact timestamp has passed
        if (daysRem < 0 || expiryDateMillis <= referenceTimeMillis) {
            return ExpiryEvaluationResult(
                status = ExpirySafetyStatus.EXPIRED,
                medicineName = medicineName,
                expiryDateMillis = expiryDateMillis,
                expiryDateIso = expiryIso,
                daysRemaining = daysRem.coerceAtMost(-1),
                canActivateSchedule = false,
                requiresExpiringSoonAcknowledgment = false,
                requiresExpiryEntry = false
            )
        }

        if (daysRem <= EXPIRING_SOON_DAYS) {
            return ExpiryEvaluationResult(
                status = ExpirySafetyStatus.EXPIRING_SOON,
                medicineName = medicineName,
                expiryDateMillis = expiryDateMillis,
                expiryDateIso = expiryIso,
                daysRemaining = daysRem,
                canActivateSchedule = true,
                requiresExpiringSoonAcknowledgment = true,
                requiresExpiryEntry = false
            )
        }

        return ExpiryEvaluationResult(
            status = ExpirySafetyStatus.VALID,
            medicineName = medicineName,
            expiryDateMillis = expiryDateMillis,
            expiryDateIso = expiryIso,
            daysRemaining = daysRem,
            canActivateSchedule = true,
            requiresExpiringSoonAcknowledgment = false,
            requiresExpiryEntry = false
        )
    }

    /**
     * Generates a non-hardcoded expiry warning message from actual stored data.
     */
    fun buildExpiredWarningMessage(
        medicineName: String,
        expiryDateIso: String
    ): String {
        return "⚠️ This medicine ($medicineName) has expired. Its recorded expiration date is $expiryDateIso. You cannot add it to an active medication schedule. Please verify the packaging and consult a pharmacist or healthcare professional about a suitable replacement."
    }

    /**
     * Validates a [ScheduleDraftInput] before creating or updating a schedule.
     */
    fun validateScheduleDraft(
        draft: ScheduleDraftInput,
        referenceTimeMillis: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): ScheduleValidationResult {
        val errors = mutableListOf<ScheduleValidationError>()

        if (draft.medicineId == null || draft.medicineId <= 0 || draft.medicineName.isBlank()) {
            errors.add(ScheduleValidationError.MISSING_MEDICINE)
        }

        if (draft.defaultDosage <= 0.0 || draft.defaultDosage.isNaN() || draft.defaultDosage.isInfinite()) {
            errors.add(ScheduleValidationError.INVALID_DEFAULT_DOSAGE)
        }

        if (draft.dosageUnit.isBlank()) {
            errors.add(ScheduleValidationError.MISSING_DOSAGE_UNIT)
        }

        val startParts = parseIsoDateParts(draft.startDateIso)
        if (startParts == null) {
            errors.add(ScheduleValidationError.INVALID_START_DATE)
        }

        if (!draft.endDateIso.isNullOrBlank()) {
            val endParts = parseIsoDateParts(draft.endDateIso)
            if (endParts == null || (startParts != null && draft.endDateIso < draft.startDateIso)) {
                errors.add(ScheduleValidationError.END_DATE_BEFORE_START_DATE)
            }
        }

        if (draft.timeSlots.isEmpty()) {
            errors.add(ScheduleValidationError.EMPTY_REMINDER_TIMES)
        } else {
            for (slot in draft.timeSlots) {
                if (parseTimeHmParts(slot.timeHm) == null) {
                    errors.add(ScheduleValidationError.INVALID_REMINDER_TIME_FORMAT)
                }
                if (slot.dosage <= 0.0 || slot.dosage.isNaN()) {
                    errors.add(ScheduleValidationError.INVALID_SLOT_DOSAGE)
                }
            }
        }

        when (draft.frequency) {
            ScheduleFrequency.DAILY -> Unit
            ScheduleFrequency.SELECTED_DAYS -> {
                if (draft.selectedDaysOfWeek.isEmpty() || draft.selectedDaysOfWeek.any { it !in 1..7 }) {
                    errors.add(ScheduleValidationError.EMPTY_SELECTED_DAYS)
                }
            }
            ScheduleFrequency.INTERVAL_DAYS, ScheduleFrequency.CUSTOM_REPEAT -> {
                val interval = draft.intervalDays ?: 0
                if (interval < 1 || interval > 365) {
                    errors.add(ScheduleValidationError.INVALID_INTERVAL_DAYS)
                }
            }
        }

        val expiryEval = evaluateExpirySafety(
            medicineName = draft.medicineName,
            expiryDateMillis = draft.recordedExpiryDateMillis,
            referenceTimeMillis = referenceTimeMillis,
            timeZone = timeZone
        )

        when (expiryEval.status) {
            ExpirySafetyStatus.EXPIRED -> errors.add(ScheduleValidationError.MEDICINE_EXPIRED)
            ExpirySafetyStatus.MISSING_EXPIRY -> errors.add(ScheduleValidationError.MEDICINE_EXPIRY_MISSING)
            ExpirySafetyStatus.INVALID_EXPIRY -> errors.add(ScheduleValidationError.MEDICINE_EXPIRY_INVALID)
            ExpirySafetyStatus.EXPIRING_SOON -> {
                if (!draft.expiringSoonAcknowledged) {
                    errors.add(ScheduleValidationError.EXPIRING_SOON_NOT_ACKNOWLEDGED)
                }
            }
            ExpirySafetyStatus.VALID -> Unit
        }

        // Also check if startDateIso is already after the recorded expiry date
        if (expiryEval.expiryDateIso != null && startParts != null && draft.startDateIso > expiryEval.expiryDateIso) {
            if (ScheduleValidationError.MEDICINE_EXPIRED !in errors) {
                errors.add(ScheduleValidationError.SCHEDULE_EXTENDS_PAST_EXPIRY)
            }
        }

        return ScheduleValidationResult(
            isValid = errors.isEmpty(),
            errors = errors.distinct(),
            expiryEvaluation = expiryEval
        )
    }

    /**
     * Determines whether [schedule] has a scheduled occurrence on [dateIso] ("YYYY-MM-DD").
     */
    fun isScheduleMatchingDate(schedule: MedicationSchedule, dateIso: String): Boolean {
        if (parseIsoDateParts(dateIso) == null) return false
        if (dateIso < schedule.startDateIso) return false
        val endIso = schedule.endDateIso
        if (!endIso.isNullOrBlank() && dateIso > endIso) return false

        return when (ScheduleFrequency.fromKey(schedule.frequencyType)) {
            ScheduleFrequency.DAILY -> true
            ScheduleFrequency.SELECTED_DAYS -> {
                val allowedDays = schedule.selectedDaysMask
                    ?.split(",")
                    ?.mapNotNull { it.trim().toIntOrNull() }
                    ?.toSet()
                    .orEmpty()
                if (allowedDays.isEmpty()) false else getIsoDayOfWeek(dateIso) in allowedDays
            }
            ScheduleFrequency.INTERVAL_DAYS, ScheduleFrequency.CUSTOM_REPEAT -> {
                val step = (schedule.intervalDays ?: 1).coerceAtLeast(1)
                val diff = daysBetween(schedule.startDateIso, dateIso)
                diff >= 0 && (diff % step == 0)
            }
        }
    }

    /**
     * Builds the deterministic list of [DoseRecord] occurrences for [schedule] on [dateIso].
     */
    fun buildDoseRecordsForScheduleOnDate(
        schedule: MedicationSchedule,
        dateIso: String,
        latestExpiryMillis: Long? = schedule.recordedExpiryDateMillis,
        referenceTimeMillis: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): List<DoseRecord> {
        if (schedule.status == ScheduleStatus.DISCONTINUED.key) return emptyList()
        if (!isScheduleMatchingDate(schedule, dateIso)) return emptyList()

        val slots = ScheduledTimeSlot.decodeList(schedule.doseTimesJson)
            .sortedBy { it.timeHm }
        if (slots.isEmpty()) return emptyList()

        val expiryEval = evaluateExpirySafety(
            medicineName = schedule.medicineName,
            expiryDateMillis = latestExpiryMillis,
            referenceTimeMillis = referenceTimeMillis,
            timeZone = timeZone
        )
        val isBlockedByExpiry = schedule.status == ScheduleStatus.BLOCKED_EXPIRED.key ||
            !expiryEval.canActivateSchedule ||
            (expiryEval.expiryDateIso != null && dateIso > expiryEval.expiryDateIso)

        return slots.mapNotNull { slot ->
            val timestamp = combineDateAndTimeToMillis(dateIso, slot.timeHm, timeZone) ?: return@mapNotNull null
            val occKey = buildOccurrenceKey(schedule.userId, schedule.id, dateIso, slot.timeHm)
            val notifId = computeNotificationId(occKey)
            val initialStatus = if (isBlockedByExpiry) {
                DoseStatus.BLOCKED.key
            } else {
                DoseStatus.PENDING.key
            }

            DoseRecord(
                occurrenceKey = occKey,
                userId = schedule.userId,
                scheduleId = schedule.id,
                medicineId = schedule.medicineId,
                inventoryItemId = schedule.inventoryItemId,
                medicineName = schedule.medicineName,
                strength = schedule.strength,
                formulation = schedule.formulation,
                scheduledDateIso = dateIso,
                scheduledTimeHm = slot.timeHm,
                scheduledTimestampMillis = timestamp,
                configuredDosage = if (slot.dosage > 0.0) slot.dosage else schedule.defaultDosage,
                dosageUnit = slot.unit.ifBlank { schedule.dosageUnit },
                routeOfAdministration = schedule.routeOfAdministration,
                instructions = schedule.instructions,
                status = initialStatus,
                notificationId = notifId,
                createdAt = referenceTimeMillis,
                updatedAt = referenceTimeMillis
            )
        }
    }

    fun buildOccurrenceKey(
        userId: String,
        scheduleId: Int,
        dateIso: String,
        timeHm: String
    ): String = "${userId}_${scheduleId}_${dateIso}_${timeHm}"

    fun computeNotificationId(occurrenceKey: String): Int {
        val hash = occurrenceKey.hashCode() and 0x7FFFFFFF
        return if (hash == 0) 10001 else hash
    }

    /**
     * Checks whether a PENDING [DoseRecord] has passed its missed-dose window and should be marked MISSED.
     * Never marks a dose as TAKEN automatically.
     */
    fun shouldMarkDoseAsMissed(
        dose: DoseRecord,
        missedThresholdMinutes: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (dose.status != DoseStatus.PENDING.key) return false
        val effectiveTargetMillis = maxOf(
            dose.snoozedUntilMillis ?: dose.scheduledTimestampMillis,
            dose.createdAt
        )
        val thresholdMs = missedThresholdMinutes.coerceAtLeast(1) * 60_000L
        return nowMillis > (effectiveTargetMillis + thresholdMs)
    }

    /**
     * Detects multiple doses scheduled for the exact same date and time.
     */
    fun detectOverlappingDoses(doses: List<DoseRecord>): List<OverlappingTimeSlotGroup> {
        return doses
            .filter { it.status != DoseStatus.BLOCKED.key }
            .groupBy { it.scheduledDateIso to it.scheduledTimeHm }
            .filter { (_, list) -> list.size > 1 }
            .map { (key, list) ->
                OverlappingTimeSlotGroup(
                    dateIso = key.first,
                    timeHm = key.second,
                    doses = list.sortedBy { it.medicineName }
                )
            }
            .sortedWith(compareBy({ it.dateIso }, { it.timeHm }))
    }

    /**
     * Calculates upcoming 7-day required quantity for each medicine and checks inventory sufficiency.
     */
    fun evaluateInventorySufficiency(
        schedules: List<MedicationSchedule>,
        inventoryItems: List<InventoryItem>,
        startDateIso: String,
        daysAhead: Int = 7
    ): List<InventorySufficiencyInfo> {
        val activeSchedules = schedules.filter { it.status == ScheduleStatus.ACTIVE.key && it.medicineId != null }
        if (activeSchedules.isEmpty()) return emptyList()

        val byMedId = activeSchedules.groupBy { it.medicineId!! }
        val results = mutableListOf<InventorySufficiencyInfo>()

        for ((medId, medSchedules) in byMedId) {
            val firstSched = medSchedules.first()
            val invItem = firstSched.inventoryItemId?.let { id -> inventoryItems.find { it.id == id } }
                ?: inventoryItems.filter { it.medicineId == medId }.maxByOrNull { it.expiryDate }

            var requiredTotal = 0.0
            for (dayOffset in 0 until daysAhead) {
                val dateIso = addDaysToIso(startDateIso, dayOffset)
                for (sched in medSchedules) {
                    if (isScheduleMatchingDate(sched, dateIso)) {
                        val slots = ScheduledTimeSlot.decodeList(sched.doseTimesJson)
                        requiredTotal += slots.sumOf { if (it.dosage > 0.0) it.dosage else sched.defaultDosage }
                    }
                }
            }

            val recordedQty = invItem?.quantity
            val threshold = medSchedules.maxOfOrNull { it.lowStockThreshold } ?: 5.0
            val isLow = recordedQty != null && recordedQty <= threshold
            val isInsufficient = recordedQty != null && requiredTotal > recordedQty

            results.add(
                InventorySufficiencyInfo(
                    medicineId = medId,
                    inventoryItemId = invItem?.id,
                    medicineName = firstSched.medicineName,
                    recordedQuantity = recordedQty,
                    unit = invItem?.unit?.ifBlank { firstSched.dosageUnit } ?: firstSched.dosageUnit,
                    upcoming7DayRequired = requiredTotal,
                    lowStockThreshold = threshold,
                    isLowStock = isLow,
                    isInsufficientForUpcoming = isInsufficient
                )
            )
        }
        return results
    }
}
