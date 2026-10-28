package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMedicine(medicine: Medicine): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInventoryItem(item: InventoryItem): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCategory(category: Category): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMedicineCategory(crossRef: MedicineCategoryCrossRef)

    
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlert(alert: Alert)

    @Query("SELECT * FROM alerts WHERE isRead = 0 ORDER BY triggerDate DESC")
    fun getUnreadAlerts(): Flow<List<Alert>>

    @Query("SELECT a.*, m.tradeName, i.expiryDate FROM alerts a INNER JOIN inventory_items i ON a.inventoryItemId = i.id INNER JOIN medicines m ON i.medicineId = m.id WHERE a.isRead = 0 ORDER BY a.triggerDate DESC")
    fun getUnreadAlertsWithMedicine(): Flow<List<AlertWithMedicine>>

    @Query("UPDATE alerts SET isRead = 1 WHERE id = :id")
    suspend fun markAlertAsRead(id: Int)

    @Query("UPDATE alerts SET isRead = 1")
    suspend fun markAllAlertsAsRead()

    @Query("SELECT * FROM alerts WHERE inventoryItemId = :itemId AND type = :type")
    suspend fun getAlertByItemAndType(itemId: Int, type: String): Alert?

    @Query("SELECT * FROM categories")
    fun getAllCategories(): Flow<List<Category>>

    @Query("SELECT m.* FROM medicines m INNER JOIN medicine_categories mc ON m.id = mc.medicineId WHERE mc.categoryId = :categoryId")
    fun getMedicinesForCategory(categoryId: Int): Flow<List<Medicine>>

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteCategory(id: Int)

    @Query("SELECT * FROM inventory_items WHERE expiryDate > :now AND (:familyMemberId IS NULL OR familyMemberId = :familyMemberId)")
    fun getAvailableItems(now: Long, familyMemberId: Int?): Flow<List<InventoryItem>>

    @Query("SELECT * FROM inventory_items WHERE expiryDate <= :now")
    fun getExpiredItems(now: Long): Flow<List<InventoryItem>>

    @Transaction
    @Query("SELECT * FROM medicines")
    fun getAllMedicines(): Flow<List<Medicine>>

    @Query("SELECT * FROM medicines WHERE id = :id")
    suspend fun getMedicineById(id: Int): Medicine?

    @Query("SELECT * FROM inventory_items WHERE medicineId = :medicineId")
    fun getInventoryItemsForMedicine(medicineId: Int): Flow<List<InventoryItem>>

    @Query("SELECT * FROM medicines WHERE isSynced = 0")
    suspend fun getUnsyncedMedicines(): List<Medicine>

    @Query("SELECT * FROM inventory_items WHERE isSynced = 0")
    suspend fun getUnsyncedInventoryItems(): List<InventoryItem>

    @Query("UPDATE medicines SET isSynced = 1 WHERE id IN (:ids)")
    suspend fun markMedicinesAsSynced(ids: List<Int>)

    @Query("UPDATE inventory_items SET isSynced = 1 WHERE id IN (:ids)")
    suspend fun markInventoryItemsAsSynced(ids: List<Int>)

    @Update
    suspend fun updateMedicine(medicine: Medicine)

    @Update
    suspend fun updateInventoryItem(item: InventoryItem)

    @Query("UPDATE inventory_items SET quantity = :newQuantity WHERE id = :itemId")
    suspend fun updateInventoryQuantity(itemId: Int, newQuantity: Double)

    @Query("DELETE FROM medicines WHERE id = :id")
    suspend fun deleteMedicineById(id: Int)

    @Query("DELETE FROM inventory_items WHERE id = :id")
    suspend fun deleteInventoryItemById(id: Int)

    @Query("SELECT * FROM inventory_items")
    fun getAllInventoryItems(): Flow<List<InventoryItem>>

    @Query("DELETE FROM alerts WHERE inventoryItemId IN (SELECT id FROM inventory_items WHERE medicineId = :medicineId)")
    suspend fun deleteAlertsForMedicine(medicineId: Int)

    @Query("DELETE FROM inventory_items WHERE medicineId = :medicineId")
    suspend fun deleteInventoryItemsForMedicine(medicineId: Int)

    @Transaction
    suspend fun deleteMedicineCompletely(medicineId: Int) {
        deleteAlertsForMedicine(medicineId)
        deleteInventoryItemsForMedicine(medicineId)
        deleteMedicineCategories(medicineId)
        deleteMedicineById(medicineId)
    }

    @Query("SELECT categoryId FROM medicine_categories WHERE medicineId = :medicineId")
    fun getCategoryIdsForMedicine(medicineId: Int): Flow<List<Int>>

    @Query("DELETE FROM medicine_categories WHERE medicineId = :medicineId")
    suspend fun deleteMedicineCategories(medicineId: Int)

    // --- Medication Schedules & Dose Records ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSchedule(schedule: MedicationSchedule): Long

    @Update
    suspend fun updateSchedule(schedule: MedicationSchedule)

    @Query("DELETE FROM medication_schedules WHERE id = :id AND userId = :userId")
    suspend fun deleteScheduleById(id: Int, userId: String)

    @Query("SELECT * FROM medication_schedules WHERE id = :id AND userId = :userId LIMIT 1")
    suspend fun getScheduleById(id: Int, userId: String): MedicationSchedule?

    @Query("SELECT * FROM medication_schedules WHERE id = :id LIMIT 1")
    suspend fun getScheduleByIdAnyUser(id: Int): MedicationSchedule?

    @Query("SELECT * FROM medication_schedules WHERE userId = :userId AND status != 'DISCONTINUED' ORDER BY createdAt DESC")
    fun getSchedulesForUser(userId: String): Flow<List<MedicationSchedule>>

    @Query("SELECT * FROM medication_schedules WHERE userId = :userId AND status != 'DISCONTINUED' ORDER BY createdAt DESC")
    suspend fun getSchedulesForUserSnapshot(userId: String): List<MedicationSchedule>

    @Query("SELECT * FROM medication_schedules WHERE status = 'ACTIVE'")
    suspend fun getAllActiveSchedulesSnapshot(): List<MedicationSchedule>

    @Query("SELECT * FROM medication_schedules WHERE medicineId = :medicineId")
    suspend fun getSchedulesForMedicineSnapshot(medicineId: Int): List<MedicationSchedule>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDoseRecordIgnore(record: DoseRecord): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDoseRecordReplace(record: DoseRecord): Long

    @Update
    suspend fun updateDoseRecord(record: DoseRecord)

    @Query("SELECT * FROM dose_records WHERE id = :id AND userId = :userId LIMIT 1")
    suspend fun getDoseRecordById(id: Int, userId: String): DoseRecord?

    @Query("SELECT * FROM dose_records WHERE id = :id LIMIT 1")
    suspend fun getDoseRecordByIdAnyUser(id: Int): DoseRecord?

    @Query("SELECT * FROM dose_records WHERE occurrenceKey = :occurrenceKey LIMIT 1")
    suspend fun getDoseRecordByOccurrenceKey(occurrenceKey: String): DoseRecord?

    @Query("SELECT * FROM dose_records WHERE userId = :userId AND scheduledDateIso = :dateIso ORDER BY scheduledTimeHm ASC, id ASC")
    fun getDoseRecordsForDate(userId: String, dateIso: String): Flow<List<DoseRecord>>

    @Query("SELECT * FROM dose_records WHERE userId = :userId AND scheduledDateIso = :dateIso ORDER BY scheduledTimeHm ASC, id ASC")
    suspend fun getDoseRecordsForDateSnapshot(userId: String, dateIso: String): List<DoseRecord>

    @Query("SELECT * FROM dose_records WHERE userId = :userId ORDER BY scheduledDateIso DESC, scheduledTimeHm DESC, id DESC")
    fun getDoseRecordsForUser(userId: String): Flow<List<DoseRecord>>

    @Query("SELECT * FROM dose_records WHERE userId = :userId ORDER BY scheduledDateIso DESC, scheduledTimeHm DESC, id DESC")
    suspend fun getDoseRecordsForUserSnapshot(userId: String): List<DoseRecord>

    @Query("SELECT * FROM dose_records WHERE scheduleId = :scheduleId ORDER BY scheduledTimestampMillis ASC")
    suspend fun getDoseRecordsForScheduleSnapshot(scheduleId: Int): List<DoseRecord>

    @Query("DELETE FROM dose_records WHERE scheduleId = :scheduleId AND status = 'PENDING' AND scheduledTimestampMillis >= :fromTimestampMillis")
    suspend fun deleteFuturePendingDosesForSchedule(scheduleId: Int, fromTimestampMillis: Long)

    @Query("DELETE FROM dose_records WHERE scheduleId = :scheduleId AND status = 'PENDING'")
    suspend fun deleteAllPendingDosesForSchedule(scheduleId: Int)

    @Query("SELECT * FROM inventory_items WHERE medicineId = :medicineId ORDER BY expiryDate DESC")
    suspend fun getInventoryItemsForMedicineSnapshot(medicineId: Int): List<InventoryItem>

    @Query("SELECT * FROM inventory_items WHERE id = :id LIMIT 1")
    suspend fun getInventoryItemById(id: Int): InventoryItem?
}

