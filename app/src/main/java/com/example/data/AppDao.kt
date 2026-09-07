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
}
