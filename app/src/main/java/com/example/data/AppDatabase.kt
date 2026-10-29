package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(entities = [
    Medicine::class,
    InventoryItem::class,
    Category::class,
    MedicineCategoryCrossRef::class,
    FamilyMember::class,
    Alert::class,
    SourceReference::class,
    MedicationSchedule::class,
    DoseRecord::class,
    SupportConversation::class,
    SupportMessage::class,
    ProcessedTelegramUpdate::class
], version = 6, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appDao(): AppDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `medication_schedules` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `userId` TEXT NOT NULL,
                        `familyMemberId` INTEGER,
                        `medicineId` INTEGER,
                        `inventoryItemId` INTEGER,
                        `medicineName` TEXT NOT NULL,
                        `strength` TEXT,
                        `formulation` TEXT,
                        `defaultDosage` REAL NOT NULL,
                        `dosageUnit` TEXT NOT NULL,
                        `routeOfAdministration` TEXT,
                        `instructions` TEXT,
                        `userNotes` TEXT,
                        `startDateIso` TEXT NOT NULL,
                        `endDateIso` TEXT,
                        `frequencyType` TEXT NOT NULL,
                        `selectedDaysMask` TEXT,
                        `intervalDays` INTEGER,
                        `doseTimesJson` TEXT NOT NULL,
                        `remindersEnabled` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `blockedReason` TEXT,
                        `autoDeductInventory` INTEGER NOT NULL,
                        `lowStockThreshold` REAL NOT NULL,
                        `snoozeMinutes` INTEGER NOT NULL,
                        `missedDoseThresholdMinutes` INTEGER NOT NULL,
                        `recordedExpiryDateMillis` INTEGER,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_medication_schedules_userId` ON `medication_schedules` (`userId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_medication_schedules_medicineId` ON `medication_schedules` (`medicineId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_medication_schedules_status` ON `medication_schedules` (`status`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `dose_records` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `occurrenceKey` TEXT NOT NULL,
                        `userId` TEXT NOT NULL,
                        `scheduleId` INTEGER NOT NULL,
                        `medicineId` INTEGER,
                        `inventoryItemId` INTEGER,
                        `medicineName` TEXT NOT NULL,
                        `strength` TEXT,
                        `formulation` TEXT,
                        `scheduledDateIso` TEXT NOT NULL,
                        `scheduledTimeHm` TEXT NOT NULL,
                        `scheduledTimestampMillis` INTEGER NOT NULL,
                        `configuredDosage` REAL NOT NULL,
                        `dosageUnit` TEXT NOT NULL,
                        `routeOfAdministration` TEXT,
                        `instructions` TEXT,
                        `status` TEXT NOT NULL,
                        `actualTakenTimestampMillis` INTEGER,
                        `userNotes` TEXT,
                        `inventoryDeducted` INTEGER NOT NULL,
                        `deductedAmount` REAL NOT NULL,
                        `notificationId` INTEGER NOT NULL,
                        `notificationDeliveredAt` INTEGER,
                        `snoozedUntilMillis` INTEGER,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_dose_records_occurrenceKey` ON `dose_records` (`occurrenceKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_dose_records_userId_scheduledDateIso` ON `dose_records` (`userId`, `scheduledDateIso`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_dose_records_scheduleId` ON `dose_records` (`scheduleId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_dose_records_medicineId` ON `dose_records` (`medicineId`)")
            }
        }

        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `medicines` ADD COLUMN `refillClassification` TEXT NOT NULL DEFAULT 'ONE_TIME'")
                db.execSQL("ALTER TABLE `medicines` ADD COLUMN `prescribedDosage` TEXT")
                db.execSQL("ALTER TABLE `medicines` ADD COLUMN `administrationRoute` TEXT")
                db.execSQL("ALTER TABLE `medicines` ADD COLUMN `expirationStatus` TEXT NOT NULL DEFAULT 'ACTIVE'")
            }
        }

        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `support_conversations` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `conversationUid` TEXT NOT NULL,
                        `userId` TEXT NOT NULL,
                        `userReference` TEXT NOT NULL,
                        `subject` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `telegramTopicId` INTEGER,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `lastMessagePreview` TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_support_conversations_conversationUid` ON `support_conversations` (`conversationUid`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_support_conversations_userId` ON `support_conversations` (`userId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_support_conversations_status` ON `support_conversations` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_support_conversations_telegramTopicId` ON `support_conversations` (`telegramTopicId`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `support_messages` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `conversationId` INTEGER NOT NULL,
                        `conversationUid` TEXT NOT NULL,
                        `userId` TEXT NOT NULL,
                        `senderType` TEXT NOT NULL,
                        `senderName` TEXT,
                        `senderTelegramUserId` INTEGER,
                        `messageText` TEXT NOT NULL,
                        `deliveryStatus` TEXT NOT NULL,
                        `clientIdempotencyKey` TEXT NOT NULL,
                        `telegramChatId` INTEGER,
                        `telegramMessageId` INTEGER,
                        `replyToTelegramMessageId` INTEGER,
                        `telegramUpdateId` INTEGER,
                        `errorMessage` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_support_messages_conversationId` ON `support_messages` (`conversationId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_support_messages_conversationUid` ON `support_messages` (`conversationUid`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_support_messages_userId` ON `support_messages` (`userId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_support_messages_clientIdempotencyKey` ON `support_messages` (`clientIdempotencyKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_support_messages_telegramMessageId` ON `support_messages` (`telegramMessageId`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `processed_telegram_updates` (
                        `updateId` INTEGER NOT NULL,
                        `telegramMessageId` INTEGER NOT NULL,
                        `conversationUid` TEXT,
                        `status` TEXT NOT NULL,
                        `processedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`updateId`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_processed_telegram_updates_telegramMessageId` ON `processed_telegram_updates` (`telegramMessageId`)")
            }
        }

        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `support_conversations` ADD COLUMN `telegramTopicId` INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_support_conversations_telegramTopicId` ON `support_conversations` (`telegramTopicId`)")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "dawaey_database"
                )
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .addCallback(DatabaseCallback(context.applicationContext))
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }

    private class DatabaseCallback(private val context: Context) : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            INSTANCE?.let { database ->
                CoroutineScope(Dispatchers.IO).launch {
                    val dao = database.appDao()
                    val defaults = listOf(
                        context.getString(com.example.R.string.cat_respiratory),
                        context.getString(com.example.R.string.cat_gastro),
                        context.getString(com.example.R.string.cat_ent),
                        context.getString(com.example.R.string.cat_painkillers),
                        context.getString(com.example.R.string.cat_allergy),
                        context.getString(com.example.R.string.cat_skin),
                        context.getString(com.example.R.string.cat_eye),
                        context.getString(com.example.R.string.cat_vitamins),
                        context.getString(com.example.R.string.cat_first_aid),
                        context.getString(com.example.R.string.cat_chronic),
                        context.getString(com.example.R.string.cat_custom)
                    )
                    defaults.forEach { name ->
                        dao.insertCategory(Category(name = name, isDefault = true, description = null, color = null, icon = null))
                    }
                }
            }
        }
    }
}
