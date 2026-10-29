package com.example.safety

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

enum class ExpiryStatus {
    EXPIRED, WITHIN_30, WITHIN_60, WITHIN_90, OK
}

/**
 * Authoritative 3-tier expiration classification required across the application:
 * - ACTIVE: Not expired and outside the configured near-expiration warning period (daysUntil > warningDays).
 * - EXPIRING_SOON: Expires within the configured warning period (1 <= daysUntil <= warningDays).
 * - EXPIRED: Expiry date is today or earlier (daysUntil <= 0).
 */
enum class MedicineExpirationStatus(val key: String) {
    ACTIVE("ACTIVE"),
    EXPIRING_SOON("EXPIRING_SOON"),
    EXPIRED("EXPIRED");

    companion object {
        fun fromKey(key: String?): MedicineExpirationStatus =
            entries.find { it.key.equals(key, ignoreCase = true) } ?: ACTIVE
    }
}

object ExpiryService {
    const val DEFAULT_WARNING_DAYS = 7

    fun currentLocalDate(zoneId: ZoneId = ZoneId.systemDefault()): LocalDate {
        return LocalDate.now(zoneId)
    }

    fun daysUntil(now: LocalDate, expiry: LocalDate): Long {
        return ChronoUnit.DAYS.between(now, expiry)
    }

    /**
     * Converts epoch milliseconds to a [LocalDate] without off-by-one-day drift.
     * Note: Material 3 DatePicker returns timestamps aligned to 00:00:00 UTC (exact multiples of 86,400,000 ms).
     * When a timestamp is an exact UTC midnight value, interpreting it in UTC preserves the exact calendar date
     * picked by the user in any timezone (including negative offsets like America/Los_Angeles).
     * Otherwise, it converts using the specified local [zoneId].
     */
    fun millisToLocalDate(
        millis: Long,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): LocalDate {
        if (millis % 86_400_000L == 0L) {
            return Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
        }
        return Instant.ofEpochMilli(millis).atZone(zoneId).toLocalDate()
    }

    /**
     * Converts a [LocalDate] to epoch milliseconds at 12:00:00 (noon) in [zoneId] (or end of day 23:59:59.999)
     * so that both local-zone and UTC-based date inspections resolve to the exact same calendar day.
     */
    fun localDateToStoredExpiryMillis(
        date: LocalDate,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Long {
        return date.atTime(23, 59, 59).atZone(zoneId).toInstant().toEpochMilli()
    }

    /**
     * Converts Material 3 DatePicker selectedDateMillis (UTC midnight) to a [LocalDate].
     */
    fun datePickerMillisToLocalDate(selectedDateMillis: Long): LocalDate {
        return Instant.ofEpochMilli(selectedDateMillis).atZone(ZoneOffset.UTC).toLocalDate()
    }

    /**
     * Converts a [LocalDate] to Material 3 DatePicker UTC midnight milliseconds.
     */
    fun localDateToDatePickerMillis(date: LocalDate): Long {
        return date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }

    /**
     * Calculates the number of calendar days between [nowMillis] and [expiryMillis] in [zoneId].
     */
    fun daysUntilMillis(
        nowMillis: Long,
        expiryMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Long {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
        val expiryDate = millisToLocalDate(expiryMillis, zoneId)
        return daysUntil(today, expiryDate)
    }

    /**
     * Shared expiration status calculation used throughout the application.
     * - EXPIRED: expiry date is today or earlier (daysUntil <= 0)
     * - EXPIRING_SOON: 1 <= daysUntil <= warningDays (default 7 days)
     * - ACTIVE: daysUntil > warningDays
     */
    fun evaluateExpirationStatus(
        now: LocalDate,
        expiry: LocalDate,
        warningDays: Int = DEFAULT_WARNING_DAYS
    ): MedicineExpirationStatus {
        val effectiveWarningDays = warningDays.coerceAtLeast(1)
        val d = daysUntil(now, expiry)
        return when {
            d <= 0L -> MedicineExpirationStatus.EXPIRED
            d <= effectiveWarningDays.toLong() -> MedicineExpirationStatus.EXPIRING_SOON
            else -> MedicineExpirationStatus.ACTIVE
        }
    }

    fun evaluateExpirationStatusMillis(
        nowMillis: Long,
        expiryMillis: Long?,
        warningDays: Int = DEFAULT_WARNING_DAYS,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): MedicineExpirationStatus {
        if (expiryMillis == null || expiryMillis <= 0L) {
            return MedicineExpirationStatus.EXPIRED
        }
        val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
        val expiryDate = millisToLocalDate(expiryMillis, zoneId)
        return evaluateExpirationStatus(today, expiryDate, warningDays)
    }

    /**
     * Validation rule for Add Medicine:
     * The expiry date must be strictly later than today's local date (daysUntil >= 1).
     * A medicine expiring today or earlier is invalid on entry.
     * A medicine expiring within the next 7 days (e.g., tomorrow) is valid on entry and classified as EXPIRING_SOON.
     */
    fun isValidNewMedicineExpiryDate(
        today: LocalDate,
        expiry: LocalDate
    ): Boolean {
        return daysUntil(today, expiry) >= 1L
    }

    fun isValidNewMedicineExpiryMillis(
        nowMillis: Long,
        expiryMillis: Long?,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Boolean {
        if (expiryMillis == null || expiryMillis <= 0L) return false
        val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
        val expiryDate = millisToLocalDate(expiryMillis, zoneId)
        return isValidNewMedicineExpiryDate(today, expiryDate)
    }

    fun classify(now: LocalDate, expiry: LocalDate): ExpiryStatus {
        val d = daysUntil(now, expiry)
        return when {
            d <= 0 -> ExpiryStatus.EXPIRED
            d <= 30 -> ExpiryStatus.WITHIN_30
            d <= 60 -> ExpiryStatus.WITHIN_60
            d <= 90 -> ExpiryStatus.WITHIN_90
            else -> ExpiryStatus.OK
        }
    }

    fun alertsFor(now: LocalDate, expiry: LocalDate): List<String> {
        return when (classify(now, expiry)) {
            ExpiryStatus.EXPIRED -> listOf("expired")
            ExpiryStatus.WITHIN_30 -> listOf("expiry_30")
            ExpiryStatus.WITHIN_60 -> listOf("expiry_60")
            ExpiryStatus.WITHIN_90 -> listOf("expiry_90")
            ExpiryStatus.OK -> emptyList()
        }
    }

    fun isUsable(now: LocalDate, expiry: LocalDate): Boolean {
        return evaluateExpirationStatus(now, expiry) != MedicineExpirationStatus.EXPIRED
    }
}

/**
 * Shared quantity-change and refill classification evaluator (Section 4.1 - 4.4).
 * Ensures that:
 * - Refill Needed contains only medicines classified as ONGOING_REFILL whose total quantity is <= 0.
 * - One-Time Medications contains only medicines classified as ONE_TIME whose total quantity is <= 0.
 * - No medicine appears in both lists, and no duplicate entries exist.
 * - Increasing quantity > 0 immediately removes a medicine from the depleted lists.
 */
object MedicationRefillEvaluator {

    data class DepletedMedicineEntry(
        val medicine: com.example.data.Medicine,
        val primaryInventoryItem: com.example.data.InventoryItem?,
        val totalRemainingQuantity: Double,
        val classification: com.example.data.MedicationRefillClassification
    )

    data class RefillEvaluationResult(
        val refillNeeded: List<DepletedMedicineEntry>,
        val oneTimeCompleted: List<DepletedMedicineEntry>
    )

    fun evaluate(
        medicines: List<com.example.data.Medicine>,
        inventoryItems: List<com.example.data.InventoryItem>
    ): RefillEvaluationResult {
        val itemsByMedicineId = inventoryItems.groupBy { it.medicineId }
        val refillNeeded = mutableListOf<DepletedMedicineEntry>()
        val oneTimeCompleted = mutableListOf<DepletedMedicineEntry>()
        val seenMedicineIds = mutableSetOf<Int>()

        for (medicine in medicines) {
            if (!seenMedicineIds.add(medicine.id)) continue
            val medItems = itemsByMedicineId[medicine.id].orEmpty()
            val totalQty = if (medItems.isEmpty()) 0.0 else medItems.sumOf { it.quantity.coerceAtLeast(0.0) }
            if (totalQty <= 0.0) {
                val primaryItem = medItems.maxByOrNull { it.expiryDate }
                val classification = com.example.data.MedicationRefillClassification.fromKey(medicine.refillClassification)
                val entry = DepletedMedicineEntry(
                    medicine = medicine,
                    primaryInventoryItem = primaryItem,
                    totalRemainingQuantity = 0.0,
                    classification = classification
                )
                when (classification) {
                    com.example.data.MedicationRefillClassification.ONGOING_REFILL -> refillNeeded.add(entry)
                    com.example.data.MedicationRefillClassification.ONE_TIME -> oneTimeCompleted.add(entry)
                }
            }
        }

        return RefillEvaluationResult(
            refillNeeded = refillNeeded,
            oneTimeCompleted = oneTimeCompleted
        )
    }
}
