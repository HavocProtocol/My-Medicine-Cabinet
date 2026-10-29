package com.example.schedule

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class ScheduleFrequency(val key: String) {
    DAILY("DAILY"),
    SELECTED_DAYS("SELECTED_DAYS"),
    INTERVAL_DAYS("INTERVAL_DAYS"),
    CUSTOM_REPEAT("CUSTOM_REPEAT");

    companion object {
        fun fromKey(key: String): ScheduleFrequency =
            entries.find { it.key.equals(key, ignoreCase = true) } ?: DAILY
    }
}

enum class ScheduleStatus(val key: String) {
    ACTIVE("ACTIVE"),
    PAUSED("PAUSED"),
    COMPLETED("COMPLETED"),
    BLOCKED_EXPIRED("BLOCKED_EXPIRED"),
    DISCONTINUED("DISCONTINUED");

    companion object {
        fun fromKey(key: String): ScheduleStatus =
            entries.find { it.key.equals(key, ignoreCase = true) } ?: ACTIVE
    }
}

enum class DoseStatus(val key: String) {
    PENDING("PENDING"),
    TAKEN("TAKEN"),
    SKIPPED("SKIPPED"),
    MISSED("MISSED"),
    BLOCKED("BLOCKED");

    companion object {
        fun fromKey(key: String): DoseStatus =
            entries.find { it.key.equals(key, ignoreCase = true) } ?: PENDING
    }
}

@Serializable
data class ScheduledTimeSlot(
    val timeHm: String, // "HH:mm" in 24h format, e.g., "08:00", "20:30"
    val dosage: Double,
    val unit: String,
    val label: String = ""
) {
    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun encodeList(slots: List<ScheduledTimeSlot>): String {
            return runCatching { json.encodeToString(slots) }.getOrDefault("[]")
        }

        fun decodeList(rawJson: String?): List<ScheduledTimeSlot> {
            if (rawJson.isNullOrBlank()) return emptyList()
            return runCatching {
                json.decodeFromString<List<ScheduledTimeSlot>>(rawJson)
            }.getOrDefault(emptyList())
        }
    }
}

enum class ExpirySafetyStatus {
    VALID,
    EXPIRING_SOON,
    EXPIRED,
    MISSING_EXPIRY,
    INVALID_EXPIRY
}

data class ExpiryEvaluationResult(
    val status: ExpirySafetyStatus,
    val medicineName: String,
    val expiryDateMillis: Long?,
    val expiryDateIso: String?,
    val daysRemaining: Int?,
    val canActivateSchedule: Boolean,
    val requiresExpiringSoonAcknowledgment: Boolean = false,
    val requiresExpiryEntry: Boolean = false
)

enum class ScheduleValidationError {
    MISSING_MEDICINE,
    INVALID_DEFAULT_DOSAGE,
    MISSING_DOSAGE_UNIT,
    INVALID_START_DATE,
    END_DATE_BEFORE_START_DATE,
    EMPTY_REMINDER_TIMES,
    INVALID_REMINDER_TIME_FORMAT,
    INVALID_SLOT_DOSAGE,
    EMPTY_SELECTED_DAYS,
    INVALID_INTERVAL_DAYS,
    MEDICINE_EXPIRED,
    MEDICINE_EXPIRY_MISSING,
    MEDICINE_EXPIRY_INVALID,
    EXPIRING_SOON_NOT_ACKNOWLEDGED,
    SCHEDULE_EXTENDS_PAST_EXPIRY
}

data class ScheduleDraftInput(
    val scheduleId: Int? = null,
    val userId: String = "default_user",
    val familyMemberId: Int? = null,
    val medicineId: Int?,
    val inventoryItemId: Int? = null,
    val medicineName: String,
    val strength: String? = null,
    val formulation: String? = null,
    val defaultDosage: Double,
    val dosageUnit: String,
    val routeOfAdministration: String? = null,
    val instructions: String? = null,
    val userNotes: String? = null,
    val startDateIso: String,
    val endDateIso: String? = null,
    val frequency: ScheduleFrequency = ScheduleFrequency.DAILY,
    val selectedDaysOfWeek: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7), // 1=Mon..7=Sun
    val intervalDays: Int? = null,
    val timeSlots: List<ScheduledTimeSlot>,
    val remindersEnabled: Boolean = true,
    val autoDeductInventory: Boolean = true,
    val lowStockThreshold: Double = 5.0,
    val snoozeMinutes: Int = 15,
    val missedDoseThresholdMinutes: Int = 120,
    val recordedExpiryDateMillis: Long? = null,
    val expiringSoonAcknowledged: Boolean = false
)

data class ScheduleValidationResult(
    val isValid: Boolean,
    val errors: List<ScheduleValidationError>,
    val expiryEvaluation: ExpiryEvaluationResult
)

data class OverlappingTimeSlotGroup(
    val dateIso: String,
    val timeHm: String,
    val doses: List<com.example.data.DoseRecord>
)

data class InventorySufficiencyInfo(
    val medicineId: Int,
    val inventoryItemId: Int?,
    val medicineName: String,
    val recordedQuantity: Double?,
    val unit: String,
    val upcoming7DayRequired: Double,
    val lowStockThreshold: Double,
    val isLowStock: Boolean,
    val isInsufficientForUpcoming: Boolean,
    val unitMismatchWarning: String? = null
)

data class DayScheduleSummary(
    val dateIso: String,
    val totalCount: Int = 0,
    val takenCount: Int = 0,
    val skippedCount: Int = 0,
    val missedCount: Int = 0,
    val pendingCount: Int = 0,
    val blockedCount: Int = 0
) {
    val completedCount: Int get() = takenCount + skippedCount
    val remainingCount: Int get() = pendingCount
}
