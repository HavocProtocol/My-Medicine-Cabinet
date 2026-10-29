package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

enum class MedicationRefillClassification(val key: String, val displayLabel: String) {
    ONGOING_REFILL("ONGOING_REFILL", "Ongoing Medication — Requires Refills"),
    ONE_TIME("ONE_TIME", "One-Time Medication");

    companion object {
        fun fromKey(key: String?): MedicationRefillClassification =
            entries.find { it.key.equals(key, ignoreCase = true) } ?: ONE_TIME
    }
}

@Entity(tableName = "medicines",
    foreignKeys = [ForeignKey(entity = SourceReference::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.SET_NULL)]
)
data class Medicine(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val tradeName: String,
    val scientificName: String?,
    val barcode: String?,
    val form: String,
    val strength: String?,
    val manufacturer: String?,
    val imagePath: String?,
    val indications: String?,
    val warnings: String?,
    @ColumnInfo(index = true) val sourceId: Int?,
    val createdAt: Long,
    val isSynced: Boolean = false,
    val refillClassification: String = MedicationRefillClassification.ONE_TIME.key,
    val prescribedDosage: String? = null,
    val administrationRoute: String? = null,
    val expirationStatus: String = "ACTIVE"
)

@Entity(tableName = "inventory_items",
    foreignKeys = [
        ForeignKey(entity = Medicine::class, parentColumns = ["id"], childColumns = ["medicineId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = FamilyMember::class, parentColumns = ["id"], childColumns = ["familyMemberId"], onDelete = ForeignKey.SET_NULL)
    ]
)
data class InventoryItem(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(index = true) val medicineId: Int,
    @ColumnInfo(index = true) val familyMemberId: Int?,
    val quantity: Double,
    val unit: String?,
    val purchaseDate: Long?,
    val expiryDate: Long,
    val batchNumber: String?,
    val storageLocation: String?,
    val needsRefrigeration: Boolean = false,
    val usageMode: String?,
    val userNotes: String?,
    val isSynced: Boolean = false
)

@Entity(tableName = "categories", indices = [Index(value = ["name"], unique = true)])
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val description: String?,
    val color: String?,
    val icon: String?,
    val isDefault: Boolean = false
)

@Entity(tableName = "medicine_categories",
    primaryKeys = ["medicineId", "categoryId"],
    foreignKeys = [
        ForeignKey(entity = Medicine::class, parentColumns = ["id"], childColumns = ["medicineId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Category::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.CASCADE)
    ]
)
data class MedicineCategoryCrossRef(
    val medicineId: Int,
    @ColumnInfo(index = true) val categoryId: Int
)

@Entity(tableName = "family_members")
data class FamilyMember(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val birthDate: Long?,
    val healthNotes: String?,
    val isChild: Boolean = false
)

@Entity(tableName = "alerts",
    foreignKeys = [ForeignKey(entity = InventoryItem::class, parentColumns = ["id"], childColumns = ["inventoryItemId"], onDelete = ForeignKey.CASCADE)]
)
data class Alert(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(index = true) val inventoryItemId: Int,
    val type: String,
    val triggerDate: Long,
    val isRead: Boolean = false,
    val createdAt: Long
)

@Entity(tableName = "source_references")
data class SourceReference(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val medicineId: Int?,
    val sourceName: String,
    val url: String?,
    val accessedAt: Long?,
    val lastUpdatedAt: Long?
)

data class AlertWithMedicine(
    @Embedded val alert: Alert,
    val tradeName: String,
    val expiryDate: Long
)

@Entity(
    tableName = "medication_schedules",
    indices = [
        Index(value = ["userId"]),
        Index(value = ["medicineId"]),
        Index(value = ["status"])
    ]
)
data class MedicationSchedule(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val userId: String = "default_user",
    val familyMemberId: Int? = null,
    val medicineId: Int? = null,
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
    val frequencyType: String, // DAILY, SELECTED_DAYS, INTERVAL_DAYS, CUSTOM_REPEAT
    val selectedDaysMask: String? = null, // e.g., "1,2,3,4,5,6,7" (1=Mon..7=Sun)
    val intervalDays: Int? = null, // e.g., every N days
    val doseTimesJson: String, // JSON array of ScheduledTimeSlot
    val remindersEnabled: Boolean = true,
    val status: String = "ACTIVE", // ACTIVE, PAUSED, COMPLETED, BLOCKED_EXPIRED, DISCONTINUED
    val blockedReason: String? = null,
    val autoDeductInventory: Boolean = true,
    val lowStockThreshold: Double = 5.0,
    val snoozeMinutes: Int = 15,
    val missedDoseThresholdMinutes: Int = 120,
    val recordedExpiryDateMillis: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "dose_records",
    indices = [
        Index(value = ["occurrenceKey"], unique = true),
        Index(value = ["userId", "scheduledDateIso"]),
        Index(value = ["scheduleId"]),
        Index(value = ["medicineId"])
    ]
)
data class DoseRecord(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val occurrenceKey: String, // "${userId}_${scheduleId}_${scheduledDateIso}_${scheduledTimeHm}"
    val userId: String = "default_user",
    val scheduleId: Int,
    val medicineId: Int? = null,
    val inventoryItemId: Int? = null,
    val medicineName: String,
    val strength: String? = null,
    val formulation: String? = null,
    val scheduledDateIso: String, // YYYY-MM-DD
    val scheduledTimeHm: String, // HH:mm
    val scheduledTimestampMillis: Long,
    val configuredDosage: Double,
    val dosageUnit: String,
    val routeOfAdministration: String? = null,
    val instructions: String? = null,
    val status: String = "PENDING", // PENDING, TAKEN, SKIPPED, MISSED, BLOCKED
    val actualTakenTimestampMillis: Long? = null,
    val userNotes: String? = null,
    val inventoryDeducted: Boolean = false,
    val deductedAmount: Double = 0.0,
    val notificationId: Int = 0,
    val notificationDeliveredAt: Long? = null,
    val snoozedUntilMillis: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "support_conversations",
    indices = [
        Index(value = ["conversationUid"], unique = true),
        Index(value = ["userId"]),
        Index(value = ["status"]),
        Index(value = ["telegramTopicId"])
    ]
)
data class SupportConversation(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val conversationUid: String,
    val userId: String = "default_user",
    val userReference: String,
    val subject: String = "Medicine Cabinet Support",
    val status: String = "OPEN", // OPEN, WAITING_FOR_SUPPORT, REPLIED, CLOSED
    val telegramTopicId: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastMessagePreview: String? = null
)

@Entity(
    tableName = "support_messages",
    indices = [
        Index(value = ["conversationId"]),
        Index(value = ["conversationUid"]),
        Index(value = ["userId"]),
        Index(value = ["clientIdempotencyKey"], unique = true),
        Index(value = ["telegramMessageId"])
    ]
)
data class SupportMessage(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val conversationId: Int,
    val conversationUid: String,
    val userId: String = "default_user",
    val senderType: String, // USER, SUPPORT_AGENT, SYSTEM
    val senderName: String? = null,
    val senderTelegramUserId: Long? = null,
    val messageText: String,
    val deliveryStatus: String = "SENDING", // SENDING, SENT_TO_TELEGRAM, DELIVERED, FAILED
    val clientIdempotencyKey: String,
    val telegramChatId: Long? = null,
    val telegramMessageId: Long? = null,
    val replyToTelegramMessageId: Long? = null,
    val telegramUpdateId: Long? = null,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "processed_telegram_updates",
    indices = [
        Index(value = ["telegramMessageId"])
    ]
)
data class ProcessedTelegramUpdate(
    @PrimaryKey val updateId: Long,
    val telegramMessageId: Long,
    val conversationUid: String? = null,
    val status: String, // ROUTED, IGNORED_UNMATCHED, REJECTED_UNAUTHORIZED
    val processedAt: Long = System.currentTimeMillis()
)


