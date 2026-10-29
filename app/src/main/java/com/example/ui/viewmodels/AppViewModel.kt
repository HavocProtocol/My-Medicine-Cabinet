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
import com.example.safety.ExpiryService
import com.example.safety.MedicationRefillEvaluator
import com.example.safety.MedicationSafetyCoordinator
import com.example.safety.MedicineExpirationStatus
import com.example.services.LocalLlmConfig
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

    private val _currentTimeMillis = MutableStateFlow(System.currentTimeMillis())
    val currentTimeMillis: StateFlow<Long> = _currentTimeMillis.asStateFlow()

    val expiryWarningDays: StateFlow<Int> = LocalLlmConfig.expiryWarningDaysFlow
    val isPremiumSubscribed: StateFlow<Boolean> = LocalLlmConfig.premiumSubscribedFlow

    val allMedicines: StateFlow<List<Medicine>> = dao.getAllMedicines()
        .onEach { medicines -> MedicationSafetyCoordinator.onCabinetMedicinesUpdated(medicines) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allCategories: StateFlow<List<Category>> = dao.getAllCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allInventoryItems: StateFlow<List<InventoryItem>> = dao.getAllInventoryItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Reactive available (non-expired: ACTIVE + EXPIRING_SOON) inventory items.
     * Automatically updates when items are added/edited, when warning period changes, or when app resumes.
     */
    val availableItems: StateFlow<List<InventoryItem>> = combine(
        allInventoryItems,
        _currentTimeMillis,
        expiryWarningDays
    ) { items, now, warnDays ->
        items.filter { item ->
            ExpiryService.evaluateExpirationStatusMillis(now, item.expiryDate, warnDays) != MedicineExpirationStatus.EXPIRED
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Reactive strictly ACTIVE inventory items (not expired and outside the near-expiration warning period).
     */
    val activeOnlyItems: StateFlow<List<InventoryItem>> = combine(
        allInventoryItems,
        _currentTimeMillis,
        expiryWarningDays
    ) { items, now, warnDays ->
        items.filter { item ->
            ExpiryService.evaluateExpirationStatusMillis(now, item.expiryDate, warnDays) == MedicineExpirationStatus.ACTIVE
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Reactive EXPIRING_SOON inventory items (expires within 1..warningDays, default 7 days).
     */
    val expiringSoonItems: StateFlow<List<InventoryItem>> = combine(
        allInventoryItems,
        _currentTimeMillis,
        expiryWarningDays
    ) { items, now, warnDays ->
        items.filter { item ->
            ExpiryService.evaluateExpirationStatusMillis(now, item.expiryDate, warnDays) == MedicineExpirationStatus.EXPIRING_SOON
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Reactive EXPIRED inventory items (expiry date is today or earlier).
     */
    val expiredItems: StateFlow<List<InventoryItem>> = combine(
        allInventoryItems,
        _currentTimeMillis,
        expiryWarningDays
    ) { items, now, warnDays ->
        items.filter { item ->
            ExpiryService.evaluateExpirationStatusMillis(now, item.expiryDate, warnDays) == MedicineExpirationStatus.EXPIRED
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Shared reactive evaluation for Refill Needed and One-Time Medications pages (Section 4).
     */
    val refillEvaluation: StateFlow<MedicationRefillEvaluator.RefillEvaluationResult> = combine(
        allMedicines,
        allInventoryItems
    ) { medicines, items ->
        MedicationRefillEvaluator.evaluate(medicines, items)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        MedicationRefillEvaluator.RefillEvaluationResult(emptyList(), emptyList())
    )

    private val _syncStatus = MutableStateFlow(SyncStatus.IDLE)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private val _syncState = MutableStateFlow("")
    val syncState: StateFlow<String> = _syncState.asStateFlow()

    val unreadAlerts: StateFlow<List<AlertWithMedicine>> = dao.getUnreadAlertsWithMedicine()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        LocalLlmConfig.getExpiryWarningDays(application)
        LocalLlmConfig.isPremiumSubscribed(application)
        observeAndSynchronizeExpirationStatuses()
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

    /**
     * Refreshes the reference clock and re-synchronizes all expiration statuses and alerts immediately.
     * Called on startup, on activity resume, after adding/editing medicines, and when warning days change.
     */
    fun refreshExpirationStatuses(nowMillis: Long = System.currentTimeMillis()) {
        val app = getApplication<Application>()
        LocalLlmConfig.getExpiryWarningDays(app)
        LocalLlmConfig.isPremiumSubscribed(app)
        _currentTimeMillis.value = nowMillis
    }

    fun setExpiryWarningDays(days: Int) {
        val app = getApplication<Application>()
        LocalLlmConfig.setExpiryWarningDays(app, days)
        _currentTimeMillis.value = System.currentTimeMillis()
    }

    fun setPremiumSubscribed(subscribed: Boolean) {
        val app = getApplication<Application>()
        LocalLlmConfig.setPremiumSubscribed(app, subscribed)
    }

    fun evaluateItemExpirationStatus(
        expiryDateMillis: Long?,
        nowMillis: Long = _currentTimeMillis.value
    ): MedicineExpirationStatus {
        return ExpiryService.evaluateExpirationStatusMillis(
            nowMillis = nowMillis,
            expiryMillis = expiryDateMillis,
            warningDays = expiryWarningDays.value
        )
    }

    private fun observeAndSynchronizeExpirationStatuses() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            combine(
                dao.getAllMedicines(),
                dao.getAllInventoryItems(),
                _currentTimeMillis,
                expiryWarningDays
            ) { medicines, items, now, warnDays ->
                Quadruple(medicines, items, now, warnDays)
            }.collect { (medicines, items, now, warnDays) ->
                val itemsByMed = items.groupBy { it.medicineId }
                for (med in medicines) {
                    val medItems = itemsByMed[med.id].orEmpty()
                    val latestItem = medItems.maxByOrNull { it.expiryDate }
                    val computedStatus = if (latestItem == null) {
                        MedicineExpirationStatus.ACTIVE
                    } else {
                        ExpiryService.evaluateExpirationStatusMillis(now, latestItem.expiryDate, warnDays)
                    }
                    if (med.expirationStatus != computedStatus.key) {
                        dao.updateMedicineExpirationStatus(med.id, computedStatus.key)
                    }
                }

                for (item in items) {
                    when (ExpiryService.evaluateExpirationStatusMillis(now, item.expiryDate, warnDays)) {
                        MedicineExpirationStatus.EXPIRED -> {
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
                        MedicineExpirationStatus.EXPIRING_SOON -> {
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
                        MedicineExpirationStatus.ACTIVE -> Unit
                    }
                }
            }
        }
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

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
        categoryIds: Set<Int>,
        refillClassification: String = MedicationRefillClassification.ONE_TIME.key,
        prescribedDosage: String? = null,
        administrationRoute: String? = null,
        onValidationError: (String) -> Unit = {},
        onSuccess: () -> Unit = {}
    ) {
        val now = System.currentTimeMillis()
        if (!ExpiryService.isValidNewMedicineExpiryMillis(now, expiryDate)) {
            onValidationError("Invalid expiry date. This medicine would already be expired on entry. Please select a future expiry date.")
            return
        }
        val normalizedClassification = MedicationRefillClassification.fromKey(refillClassification).key
        val initialStatus = ExpiryService.evaluateExpirationStatusMillis(
            nowMillis = now,
            expiryMillis = expiryDate,
            warningDays = expiryWarningDays.value
        ).key

        viewModelScope.launch {
            val medicine = Medicine(
                tradeName = tradeName.trim(),
                scientificName = scientificName?.trim()?.ifBlank { null },
                barcode = barcode?.trim()?.ifBlank { null },
                form = form,
                strength = strength?.trim()?.ifBlank { null },
                manufacturer = manufacturer?.trim()?.ifBlank { null },
                imagePath = imagePath,
                indications = indications?.trim()?.ifBlank { null },
                warnings = warnings?.trim()?.ifBlank { null },
                sourceId = null,
                createdAt = now,
                refillClassification = normalizedClassification,
                prescribedDosage = prescribedDosage?.trim()?.ifBlank { null },
                administrationRoute = administrationRoute?.trim()?.ifBlank { null },
                expirationStatus = initialStatus
            )
            val medicineId = dao.insertMedicine(medicine).toInt()
            val inventoryItem = InventoryItem(
                medicineId = medicineId,
                familyMemberId = null,
                quantity = quantity.coerceAtLeast(0.0),
                unit = unit,
                purchaseDate = purchaseDate,
                expiryDate = expiryDate,
                batchNumber = batchNumber?.trim()?.ifBlank { null },
                storageLocation = storageLocation?.trim()?.ifBlank { null },
                needsRefrigeration = needsRefrigeration,
                usageMode = usageMode ?: prescribedDosage,
                userNotes = userNotes?.trim()?.ifBlank { null }
            )
            dao.insertInventoryItem(inventoryItem)
            for (categoryId in categoryIds) {
                dao.insertMedicineCategory(MedicineCategoryCrossRef(medicineId, categoryId))
            }
            refreshExpirationStatuses(System.currentTimeMillis())
            onSuccess()
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
            refreshExpirationStatuses()
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
            scheduleRepository.onMedicineInventoryUpdated(medicineId)
            refreshExpirationStatuses()
            onComplete()
        }
    }

    // Keep backwards-compatible overload
    fun logUsedQuantity(inventoryItemId: Int, currentQuantity: Double, amountUsed: Double, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            val newQty = (currentQuantity - amountUsed).coerceAtLeast(0.0)
            dao.updateInventoryQuantity(inventoryItemId, newQty)
            refreshExpirationStatuses()
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
            val now = System.currentTimeMillis()
            val updatedExpStatus = if (inventoryItem != null) {
                ExpiryService.evaluateExpirationStatusMillis(now, inventoryItem.expiryDate, expiryWarningDays.value).key
            } else {
                medicine.expirationStatus
            }
            dao.updateMedicine(medicine.copy(expirationStatus = updatedExpStatus))
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
            refreshExpirationStatuses(now)
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
            val now = System.currentTimeMillis()
            val existing = dao.getInventoryItemsForMedicine(medicineId).firstOrNull()?.firstOrNull()
            if (existing != null) {
                dao.updateInventoryItem(
                    existing.copy(
                        quantity = quantity.coerceAtLeast(0.0),
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
                        quantity = quantity.coerceAtLeast(0.0),
                        unit = unit,
                        purchaseDate = now,
                        expiryDate = expiryDate,
                        batchNumber = null,
                        storageLocation = storageLocation,
                        needsRefrigeration = needsRefrigeration,
                        usageMode = null,
                        userNotes = null
                    )
                )
            }
            val computedStatus = ExpiryService.evaluateExpirationStatusMillis(now, expiryDate, expiryWarningDays.value).key
            dao.updateMedicineExpirationStatus(medicineId, computedStatus)
            scheduleRepository.onMedicineInventoryUpdated(medicineId)
            refreshExpirationStatuses(now)
            onComplete()
        }
    }

    fun updateMedicineRefillClassification(
        medicineId: Int,
        classification: MedicationRefillClassification,
        onComplete: () -> Unit = {}
    ) {
        viewModelScope.launch {
            dao.updateMedicineRefillClassification(medicineId, classification.key)
            scheduleRepository.onMedicineInventoryUpdated(medicineId)
            onComplete()
        }
    }

    fun getInventoryForMedicine(medicineId: Int): Flow<List<InventoryItem>> = dao.getInventoryItemsForMedicine(medicineId)

    fun getCategoriesForMedicine(medicineId: Int): Flow<List<Int>> = dao.getCategoryIdsForMedicine(medicineId)
}
