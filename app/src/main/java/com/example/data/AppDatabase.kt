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
    DoseRecord::class
], version = 3, exportSchema = false)
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

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "dawaey_database"
                )
                .addMigrations(MIGRATION_2_3)
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
