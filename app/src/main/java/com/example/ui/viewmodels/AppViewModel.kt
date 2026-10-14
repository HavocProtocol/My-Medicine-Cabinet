package com.example.ui.viewmodels

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
                        sendPushNotification("Expired Medicine", "You have an expired medicine in your inventory.")
                    }
                }
                for (item in available) {
                    if (item.expiryDate - now <= thirtyDaysMs) {
                        val existing = dao.getAlertByItemAndType(item.id, "EXPIRING_SOON")
                        if (existing == null) {
                            val alert = Alert(inventoryItemId = item.id, type = "EXPIRING_SOON", triggerDate = now, createdAt = now)
                            dao.insertAlert(alert)
                            sendPushNotification("Expiring Soon", "A medicine is expiring within 30 days.")
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
            val channel = NotificationChannel(channelId, "Medicine Alerts", NotificationManager.IMPORTANCE_DEFAULT)
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
}
