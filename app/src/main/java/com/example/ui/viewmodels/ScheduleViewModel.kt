package com.example.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.DoseRecord
import com.example.data.InventoryItem
import com.example.data.MedicationSchedule
import com.example.data.Medicine
import com.example.schedule.DayScheduleSummary
import com.example.schedule.DoseStatus
import com.example.schedule.ExpiryEvaluationResult
import com.example.schedule.InventorySufficiencyInfo
import com.example.schedule.MedicationScheduleEngine
import com.example.schedule.MedicationScheduleRepository
import com.example.schedule.OverlappingTimeSlotGroup
import com.example.schedule.ReminderNotificationScheduler
import com.example.schedule.ScheduleDraftInput
import com.example.schedule.ScheduleValidationResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = AppDatabase.getDatabase(application).appDao()
    val notificationScheduler = ReminderNotificationScheduler(application)
    val repository = MedicationScheduleRepository(dao, notificationScheduler)

    private val _currentUserId = MutableStateFlow("default_user")
    val currentUserId: StateFlow<String> = _currentUserId.asStateFlow()

    private val _todayDateIso = MutableStateFlow(
        MedicationScheduleEngine.millisToLocalDateIso(System.currentTimeMillis())
    )
    val todayDateIso: StateFlow<String> = _todayDateIso.asStateFlow()

    private val _selectedDateIso = MutableStateFlow(
        MedicationScheduleEngine.millisToLocalDateIso(System.currentTimeMillis())
    )
    val selectedDateIso: StateFlow<String> = _selectedDateIso.asStateFlow()

    val allMedicines: StateFlow<List<Medicine>> = dao.getAllMedicines()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allInventoryItems: StateFlow<List<InventoryItem>> = dao.getAllInventoryItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val schedules: StateFlow<List<MedicationSchedule>> = _currentUserId
        .flatMapLatest { userId -> repository.observeSchedulesForUser(userId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val todayDoses: StateFlow<List<DoseRecord>> = combine(_currentUserId, _todayDateIso) { u, d -> u to d }
        .flatMapLatest { (userId, dateIso) -> repository.observeDoseRecordsForDate(userId, dateIso) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedDateDoses: StateFlow<List<DoseRecord>> = combine(_currentUserId, _selectedDateIso) { u, d -> u to d }
        .flatMapLatest { (userId, dateIso) -> repository.observeDoseRecordsForDate(userId, dateIso) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allDoseRecords: StateFlow<List<DoseRecord>> = _currentUserId
        .flatMapLatest { userId -> repository.observeAllDoseRecordsForUser(userId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val overlappingSlotsForSelectedDate: StateFlow<List<OverlappingTimeSlotGroup>> = selectedDateDoses
        .combine(_selectedDateIso) { doses, _ ->
            MedicationScheduleEngine.detectOverlappingDoses(doses)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val inventorySufficiency: StateFlow<List<InventorySufficiencyInfo>> = combine(
        schedules,
        allInventoryItems,
        _todayDateIso
    ) { scheds, invItems, todayIso ->
        MedicationScheduleEngine.evaluateInventorySufficiency(
            schedules = scheds,
            inventoryItems = invItems,
            startDateIso = todayIso,
            daysAhead = 7
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val daySummariesByDate: StateFlow<Map<String, DayScheduleSummary>> = allDoseRecords
        .combine(_currentUserId) { records, _ ->
            records.groupBy { it.scheduledDateIso }.mapValues { (dateIso, list) ->
                DayScheduleSummary(
                    dateIso = dateIso,
                    totalCount = list.size,
                    takenCount = list.count { it.status == DoseStatus.TAKEN.key },
                    skippedCount = list.count { it.status == DoseStatus.SKIPPED.key },
                    missedCount = list.count { it.status == DoseStatus.MISSED.key },
                    pendingCount = list.count { it.status == DoseStatus.PENDING.key },
                    blockedCount = list.count { it.status == DoseStatus.BLOCKED.key }
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    init {
        refreshAndGenerateOccurrences()
    }

    fun refreshAndGenerateOccurrences() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val todayIso = MedicationScheduleEngine.millisToLocalDateIso(now)
            _todayDateIso.value = todayIso
            val startRangeIso = MedicationScheduleEngine.addDaysToIso(todayIso, -2)
            repository.ensureOccurrencesGeneratedForRange(
                userId = _currentUserId.value,
                fromDateIso = startRangeIso,
                daysCount = 14,
                nowMillis = now
            )
            // Also ensure selected date has occurrences generated if user navigated ahead in calendar
            val selDate = _selectedDateIso.value
            if (selDate >= todayIso) {
                repository.ensureOccurrencesGeneratedForRange(
                    userId = _currentUserId.value,
                    fromDateIso = selDate,
                    daysCount = 3,
                    nowMillis = now
                )
            }
        }
    }

    fun selectDate(dateIso: String) {
        _selectedDateIso.value = dateIso
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val todayIso = MedicationScheduleEngine.millisToLocalDateIso(now)
            if (dateIso >= todayIso) {
                repository.ensureOccurrencesGeneratedForRange(
                    userId = _currentUserId.value,
                    fromDateIso = dateIso,
                    daysCount = 1,
                    nowMillis = now
                )
            } else {
                repository.evaluateMissedDoses(_currentUserId.value, now)
            }
        }
    }

    fun setCurrentUser(userId: String) {
        if (userId.isBlank()) return
        _currentUserId.value = userId.trim()
        refreshAndGenerateOccurrences()
    }

    fun evaluateMedicineExpiry(
        medicineId: Int?,
        medicineName: String,
        overrideExpiryMillis: Long? = null
    ): ExpiryEvaluationResult {
        val invItems = allInventoryItems.value.filter { it.medicineId == medicineId }
        val latestExpiry = overrideExpiryMillis
            ?: invItems.maxByOrNull { it.expiryDate }?.expiryDate
        return MedicationScheduleEngine.evaluateExpirySafety(
            medicineName = medicineName,
            expiryDateMillis = latestExpiry
        )
    }

    fun validateDraft(draft: ScheduleDraftInput): ScheduleValidationResult {
        return MedicationScheduleEngine.validateScheduleDraft(
            draft = draft.copy(userId = _currentUserId.value)
        )
    }

    fun createSchedule(
        draft: ScheduleDraftInput,
        onSuccess: (MedicationSchedule) -> Unit = {},
        onError: (Throwable) -> Unit = {}
    ) {
        viewModelScope.launch {
            val result = repository.createSchedule(draft.copy(userId = _currentUserId.value))
            result.onSuccess {
                refreshAndGenerateOccurrences()
                onSuccess(it)
            }.onFailure {
                onError(it)
            }
        }
    }

    fun updateSchedule(
        draft: ScheduleDraftInput,
        onSuccess: (MedicationSchedule) -> Unit = {},
        onError: (Throwable) -> Unit = {}
    ) {
        viewModelScope.launch {
            val result = repository.updateSchedule(draft.copy(userId = _currentUserId.value))
            result.onSuccess {
                refreshAndGenerateOccurrences()
                onSuccess(it)
            }.onFailure {
                onError(it)
            }
        }
    }

    fun pauseSchedule(
        scheduleId: Int,
        onResult: (Result<MedicationSchedule>) -> Unit = {}
    ) {
        viewModelScope.launch {
            val res = repository.pauseSchedule(scheduleId, _currentUserId.value)
            refreshAndGenerateOccurrences()
            onResult(res)
        }
    }

    fun resumeSchedule(
        scheduleId: Int,
        onResult: (Result<MedicationSchedule>) -> Unit = {}
    ) {
        viewModelScope.launch {
            val res = repository.resumeSchedule(scheduleId, _currentUserId.value)
            refreshAndGenerateOccurrences()
            onResult(res)
        }
    }

    fun removeSchedule(
        scheduleId: Int,
        onComplete: (Boolean) -> Unit = {}
    ) {
        viewModelScope.launch {
            val removed = repository.removeSchedule(scheduleId, _currentUserId.value)
            refreshAndGenerateOccurrences()
            onComplete(removed)
        }
    }

    fun replaceSchedule(
        oldScheduleId: Int,
        replacementDraft: ScheduleDraftInput,
        onSuccess: (MedicationSchedule) -> Unit = {},
        onError: (Throwable) -> Unit = {}
    ) {
        viewModelScope.launch {
            val res = repository.replaceScheduleWithMedicine(
                oldScheduleId = oldScheduleId,
                replacementDraft = replacementDraft.copy(userId = _currentUserId.value)
            )
            res.onSuccess {
                refreshAndGenerateOccurrences()
                onSuccess(it)
            }.onFailure {
                onError(it)
            }
        }
    }

    fun markDoseTaken(
        doseRecordId: Int,
        notes: String? = null,
        onResult: (Result<DoseRecord>) -> Unit = {}
    ) {
        viewModelScope.launch {
            val res = repository.markDoseTaken(
                doseRecordId = doseRecordId,
                userId = _currentUserId.value,
                notes = notes
            )
            onResult(res)
        }
    }

    fun markDoseSkipped(
        doseRecordId: Int,
        notes: String? = null,
        onResult: (Result<DoseRecord>) -> Unit = {}
    ) {
        viewModelScope.launch {
            val res = repository.markDoseSkipped(
                doseRecordId = doseRecordId,
                userId = _currentUserId.value,
                notes = notes
            )
            onResult(res)
        }
    }

    fun snoozeDose(
        doseRecordId: Int,
        snoozeMinutes: Int = 15,
        onResult: (Result<DoseRecord>) -> Unit = {}
    ) {
        viewModelScope.launch {
            val res = repository.snoozeDose(
                doseRecordId = doseRecordId,
                userId = _currentUserId.value,
                snoozeMinutes = snoozeMinutes
            )
            onResult(res)
        }
    }

    fun updateMedicineExpiryInCabinet(
        medicineId: Int,
        newExpiryDateMillis: Long,
        onComplete: () -> Unit = {}
    ) {
        viewModelScope.launch {
            val existing = dao.getInventoryItemsForMedicine(medicineId).firstOrNull()?.firstOrNull()
            if (existing != null) {
                dao.updateInventoryItem(existing.copy(expiryDate = newExpiryDateMillis))
            } else {
                dao.insertInventoryItem(
                    InventoryItem(
                        medicineId = medicineId,
                        familyMemberId = null,
                        quantity = 1.0,
                        unit = "Pack",
                        purchaseDate = System.currentTimeMillis(),
                        expiryDate = newExpiryDateMillis,
                        batchNumber = null,
                        storageLocation = null,
                        needsRefrigeration = false,
                        usageMode = null,
                        userNotes = null
                    )
                )
            }
            repository.onMedicineInventoryUpdated(medicineId)
            refreshAndGenerateOccurrences()
            onComplete()
        }
    }
}
