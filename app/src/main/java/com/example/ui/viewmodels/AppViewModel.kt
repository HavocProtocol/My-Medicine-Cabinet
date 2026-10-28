package com.example.ui.viewmodels

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.data.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class SyncStatus {
    IDLE,
    SYNCING,
    SUCCESS,
    ERROR
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getDatabase(application).appDao()

    val allMedicines: StateFlow<List<Medicine>> = dao.getAllMedicines()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allCategories: StateFlow<List<Category>> = dao.getAllCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val nowTime = System.currentTimeMillis()

    val availableItems: StateFlow<List<InventoryItem>> = dao.getAvailableItems(nowTime, null)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val expiredItems: StateFlow<List<InventoryItem>> = dao.getExpiredItems(nowTime)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allInventoryItems: StateFlow<List<InventoryItem>> = dao.getAllInventoryItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _syncStatus = MutableStateFlow(SyncStatus.IDLE)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private val _syncState = MutableStateFlow("")
    val syncState: StateFlow<String> = _syncState.asStateFlow()

    val unreadAlerts: StateFlow<List<AlertWithMedicine>> = dao.getUnreadAlertsWithMedicine()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        checkAndGenerateAlerts()
        viewModelScope.launch {
            val cats = dao.getAllCategories().first()
            if (cats.isEmpty()) {
                dao.insertCategory(Category(name = "Painkillers & Antipyretics", isDefault = true, description = null, color = null, icon = null))
                dao.insertCategory(Category(name = "Antibiotics", isDefault = true, description = null, color = null, icon = null))
                dao.insertCategory(Category(name = "Vitamins & Supplements", isDefault = true, description = null, color = null, icon = null))
                dao.insertCategory(Category(name = "Chronic Medications", isDefault = true, description = null, color = null, icon = null))
            }
        }
    }

    private fun checkAndGenerateAlerts() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val now = System.currentTimeMillis()
            val thirtyDaysMs = 30L * 24 * 60 * 60 * 1000
            
            combine(dao.getAvailableItems(now, null), dao.getExpiredItems(now)) { available, expired ->
                available to expired
            }.collect { (available, expired) ->
                for (item in expired) {
                    val existing = dao.getAlertByItemAndType(item.id, "EXPIRED")
                    if (existing == null) {
                        val alert = Alert(inventoryItemId = item.id, type = "EXPIRED", triggerDate = now, createdAt = now)
                        dao.insertAlert(alert)
                        sendPushNotification(
                            app.getString(R.string.expired_alert_title),
                            app.getString(R.string.expired_warning)
                        )
                    }
                }
                for (item in available) {
                    if (item.expiryDate - now <= thirtyDaysMs) {
                        val existing = dao.getAlertByItemAndType(item.id, "EXPIRING_SOON")
                        if (existing == null) {
                            val alert = Alert(inventoryItemId = item.id, type = "EXPIRING_SOON", triggerDate = now, createdAt = now)
                            dao.insertAlert(alert)
                            sendPushNotification(
                                app.getString(R.string.expiring_soon_alert_title),
                                app.getString(R.string.expiring_soon)
                            )
                        }
                    }
                }
            }
        }
    }

    private fun sendPushNotification(title: String, message: String) {
        val context = getApplication<Application>()
        val channelId = "medicine_alerts"
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                context.getString(R.string.notifications_title),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            notificationManager.notify(System.currentTimeMillis().toInt(), notification)
        } catch (e: SecurityException) {
            // Permission not granted
        }
    }

    fun markAlertAsRead(id: Int) {
        viewModelScope.launch {
            dao.markAlertAsRead(id)
        }
    }

    fun markAllAlertsAsRead() {
        viewModelScope.launch {
            dao.markAllAlertsAsRead()
        }
    }

    fun syncWithCloud() {
        viewModelScope.launch {
            _syncStatus.value = SyncStatus.SYNCING
            try {
                val unsyncedMeds = dao.getUnsyncedMedicines()
                val unsyncedItems = dao.getUnsyncedInventoryItems()
                kotlinx.coroutines.delay(1500)
                if (unsyncedMeds.isNotEmpty()) {
                    dao.markMedicinesAsSynced(unsyncedMeds.map { it.id })
                }
                if (unsyncedItems.isNotEmpty()) {
                    dao.markInventoryItemsAsSynced(unsyncedItems.map { it.id })
                }
                _syncStatus.value = SyncStatus.SUCCESS
            } catch (e: Exception) {
                _syncStatus.value = SyncStatus.ERROR
            }
        }
    }

    fun insertCategory(name: String, color: String? = null, icon: String? = null) {
        viewModelScope.launch {
            dao.insertCategory(Category(name = name, description = null, color = color, icon = icon))
        }
    }

    fun deleteCategory(id: Int) {
        viewModelScope.launch {
            dao.deleteCategory(id)
        }
    }

    fun getMedicinesForCategory(categoryId: Int): Flow<List<Medicine>> {
        return dao.getMedicinesForCategory(categoryId)
    }

    fun insertMedicine(
        tradeName: String,
        scientificName: String?,
        barcode: String?,
        form: String,
        strength: String?,
        manufacturer: String?,
        imagePath: String?,
        indications: String?,
        warnings: String?,
        quantity: Double,
        unit: String?,
        purchaseDate: Long?,
        expiryDate: Long,
        batchNumber: String?,
        storageLocation: String?,
        needsRefrigeration: Boolean,
        usageMode: String?,
        userNotes: String?,
        categoryIds: Set<Int>
    ) {
        viewModelScope.launch {
            val medicine = Medicine(
                tradeName = tradeName,
                scientificName = scientificName,
                barcode = barcode,
                form = form,
                strength = strength,
                manufacturer = manufacturer,
                imagePath = imagePath,
                indications = indications,
                warnings = warnings,
                sourceId = null,
                createdAt = System.currentTimeMillis()
            )
            val medicineId = dao.insertMedicine(medicine).toInt()
            val inventoryItem = InventoryItem(
                medicineId = medicineId,
                familyMemberId = null,
                quantity = quantity,
                unit = unit,
                purchaseDate = purchaseDate,
                expiryDate = expiryDate,
                batchNumber = batchNumber,
                storageLocation = storageLocation,
                needsRefrigeration = needsRefrigeration,
                usageMode = usageMode,
                userNotes = userNotes
            )
            dao.insertInventoryItem(inventoryItem)
            for (categoryId in categoryIds) {
                dao.insertMedicineCategory(MedicineCategoryCrossRef(medicineId, categoryId))
            }
        }
    }

    private val scheduleRepository by lazy {
        com.example.schedule.MedicationScheduleRepository(
            dao = dao,
            notificationScheduler = com.example.schedule.ReminderNotificationScheduler(getApplication())
        )
    }

    fun deleteMedicine(medicineId: Int, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                scheduleRepository.onMedicineDeletedFromCabinet(medicineId)
                dao.deleteMedicineCompletely(medicineId)
            } catch (_: Exception) {
                try {
                    dao.deleteMedicineById(medicineId)
                } catch (_: Exception) {}
            }
            onComplete()
        }
    }

    fun logUsedQuantity(
        medicineId: Int,
        inventoryItemId: Int?,
        currentQuantity: Double,
        amountUsed: Double,
        onComplete: () -> Unit = {}
    ) {
        viewModelScope.launch {
            val newQty = (currentQuantity - amountUsed).coerceAtLeast(0.0)
            if (inventoryItemId != null && inventoryItemId > 0) {
                dao.updateInventoryQuantity(inventoryItemId, newQty)
            } else {
                val existing = dao.getInventoryItemsForMedicine(medicineId).firstOrNull()?.firstOrNull()
                if (existing != null) {
                    dao.updateInventoryQuantity(existing.id, (existing.quantity - amountUsed).coerceAtLeast(0.0))
                } else {
                    dao.insertInventoryItem(
                        InventoryItem(
                            medicineId = medicineId,
                            familyMemberId = null,
                            quantity = newQty,
                            unit = null,
                            purchaseDate = System.currentTimeMillis(),
                            expiryDate = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000,
                            batchNumber = null,
                            storageLocation = null,
                            needsRefrigeration = false,
                            usageMode = null,
                            userNotes = null
                        )
                    )
                }
            }
            onComplete()
        }
    }

    // Keep backwards-compatible overload
    fun logUsedQuantity(inventoryItemId: Int, currentQuantity: Double, amountUsed: Double, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            val newQty = (currentQuantity - amountUsed).coerceAtLeast(0.0)
            dao.updateInventoryQuantity(inventoryItemId, newQty)
            onComplete()
        }
    }

    fun updateMedicineWithInventory(
        medicine: Medicine,
        inventoryItem: InventoryItem?,
        categoryIds: Set<Int> = emptySet(),
        onComplete: () -> Unit = {}
    ) {
        viewModelScope.launch {
            dao.updateMedicine(medicine)
            if (inventoryItem != null) {
                if (inventoryItem.id > 0) {
                    dao.updateInventoryItem(inventoryItem)
                } else {
                    dao.insertInventoryItem(inventoryItem.copy(medicineId = medicine.id))
                }
            }
            if (categoryIds.isNotEmpty()) {
                dao.deleteMedicineCategories(medicine.id)
                categoryIds.forEach { catId ->
                    dao.insertMedicineCategory(MedicineCategoryCrossRef(medicine.id, catId))
                }
            }
            scheduleRepository.onMedicineInventoryUpdated(medicine.id)
            onComplete()
        }
    }

    fun insertOrUpdateInventoryItem(
        medicineId: Int,
        quantity: Double,
        unit: String?,
        expiryDate: Long,
        storageLocation: String?,
        needsRefrigeration: Boolean,
        onComplete: () -> Unit = {}
    ) {
        viewModelScope.launch {
            val existing = dao.getInventoryItemsForMedicine(medicineId).firstOrNull()?.firstOrNull()
            if (existing != null) {
                dao.updateInventoryItem(
                    existing.copy(
                        quantity = quantity,
                        unit = unit ?: existing.unit,
                        expiryDate = expiryDate,
                        storageLocation = storageLocation,
                        needsRefrigeration = needsRefrigeration
                    )
                )
            } else {
                dao.insertInventoryItem(
                    InventoryItem(
                        medicineId = medicineId,
                        familyMemberId = null,
                        quantity = quantity,
                        unit = unit,
                        purchaseDate = System.currentTimeMillis(),
                        expiryDate = expiryDate,
                        batchNumber = null,
                        storageLocation = storageLocation,
                        needsRefrigeration = needsRefrigeration,
                        usageMode = null,
                        userNotes = null
                    )
                )
            }
            scheduleRepository.onMedicineInventoryUpdated(medicineId)
            onComplete()
        }
    }

    fun getInventoryForMedicine(medicineId: Int): Flow<List<InventoryItem>> = dao.getInventoryItemsForMedicine(medicineId)

    fun getCategoriesForMedicine(medicineId: Int): Flow<List<Int>> = dao.getCategoryIdsForMedicine(medicineId)
}
