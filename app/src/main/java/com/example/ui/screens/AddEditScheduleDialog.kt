package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties
import com.example.R
import com.example.data.MedicationSchedule
import com.example.data.Medicine
import com.example.safety.ExpiryService
import com.example.safety.MedicineExpirationStatus
import com.example.schedule.ExpiryEvaluationResult
import com.example.schedule.ExpirySafetyStatus
import com.example.schedule.MedicationScheduleEngine
import com.example.schedule.ScheduleDraftInput
import com.example.schedule.ScheduleFrequency
import com.example.schedule.ScheduleValidationError
import com.example.schedule.ScheduledTimeSlot
import com.example.services.LocalLlmConfig
import com.example.ui.viewmodels.ScheduleViewModel
import java.time.LocalDate

private val STANDARD_DOSAGE_AMOUNTS = listOf(
    "0.25", "0.5", "1", "1.5", "2", "2.5", "3", "4", "5", "7.5", "10", "15", "20"
)

private val STANDARD_REMINDER_TIMES: List<String> = buildList {
    for (hour in 0..23) {
        for (minute in listOf(0, 15, 30, 45)) {
            add(String.format("%02d:%02d", hour, minute))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddEditScheduleDialog(
    viewModel: ScheduleViewModel,
    existingSchedule: MedicationSchedule? = null,
    replacingSchedule: MedicationSchedule? = null,
    preselectedMedicineId: Int? = null,
    onDismiss: () -> Unit,
    onSaved: (MedicationSchedule) -> Unit
) {
    val context = LocalContext.current
    val medicines by viewModel.allMedicines.collectAsState()
    val inventoryItems by viewModel.allInventoryItems.collectAsState()
    val todayIso by viewModel.todayDateIso.collectAsState()
    val warningDays by LocalLlmConfig.expiryWarningDaysFlow.collectAsState()

    val initialSchedule = existingSchedule ?: replacingSchedule
    val defaultMed = remember(medicines, initialSchedule, preselectedMedicineId) {
        when {
            replacingSchedule != null -> medicines.find { it.id == preselectedMedicineId }
            existingSchedule != null -> medicines.find { it.id == existingSchedule.medicineId }
            preselectedMedicineId != null -> medicines.find { it.id == preselectedMedicineId }
            else -> medicines.firstOrNull()
        }
    }

    var selectedMedicineId by remember(defaultMed) {
        mutableStateOf(
            when {
                replacingSchedule != null -> preselectedMedicineId
                existingSchedule != null -> existingSchedule.medicineId ?: defaultMed?.id
                else -> defaultMed?.id
            }
        )
    }

    val selectedMedicine = remember(medicines, selectedMedicineId) {
        medicines.find { it.id == selectedMedicineId }
    }

    val selectedInventoryItem = remember(inventoryItems, selectedMedicineId) {
        inventoryItems.filter { it.medicineId == selectedMedicineId }
            .maxByOrNull { it.expiryDate }
    }

    // Searchable dropdown state for filtering registered medicines in the cabinet
    var medicineDropdownExpanded by remember { mutableStateOf(false) }
    var medicineSearchQuery by remember(selectedMedicine) {
        mutableStateOf(
            selectedMedicine?.let { formatMedicineLabel(it) } ?: ""
        )
    }
    var isFilteringDropdown by remember { mutableStateOf(false) }

    val filteredMedicines = remember(medicines, medicineSearchQuery, isFilteringDropdown) {
        val query = medicineSearchQuery.trim()
        if (!isFilteringDropdown || query.isBlank()) {
            medicines
        } else {
            medicines.filter { med ->
                med.tradeName.contains(query, ignoreCase = true) ||
                    (med.scientificName?.contains(query, ignoreCase = true) == true) ||
                    (med.strength?.contains(query, ignoreCase = true) == true) ||
                    med.form.contains(query, ignoreCase = true)
            }
        }
    }

    // Schedule Fields
    var dosageText by remember {
        val initDose = initialSchedule?.defaultDosage
        mutableStateOf(
            if (initDose != null) {
                if (initDose % 1.0 == 0.0) initDose.toInt().toString() else initDose.toString()
            } else {
                selectedMedicine?.prescribedDosage
                    ?.trim()
                    ?.split(" ")
                    ?.firstOrNull()
                    ?.takeIf { it.toDoubleOrNull() != null }
                    ?: "1"
            }
        )
    }
    var isCustomDosageMode by remember {
        mutableStateOf(dosageText.isNotBlank() && dosageText !in STANDARD_DOSAGE_AMOUNTS)
    }
    var dosageDropdownExpanded by remember { mutableStateOf(false) }

    var dosageUnit by remember(selectedMedicine) {
        mutableStateOf(
            initialSchedule?.dosageUnit ?: when (selectedMedicine?.form?.lowercase()) {
                "syrup", "liquid" -> "mL"
                "drops" -> "drop"
                "inhaler" -> "puff"
                "capsules", "capsule" -> "capsule"
                else -> "tablet"
            }
        )
    }
    var unitDropdownExpanded by remember { mutableStateOf(false) }

    val routeOptions = listOf(
        "Oral" to stringResource(R.string.sched_route_oral),
        "Topical" to stringResource(R.string.sched_route_topical),
        "Inhalation" to stringResource(R.string.sched_route_inhalation),
        "Injection" to stringResource(R.string.sched_route_injection),
        "Drops" to stringResource(R.string.sched_route_Auto_drops),
        "Sublingual" to stringResource(R.string.route_sublingual),
        "Nasal" to stringResource(R.string.route_nasal),
        "Ophthalmic" to stringResource(R.string.route_ophthalmic),
        "Otic" to stringResource(R.string.route_otic),
        "Other" to stringResource(R.string.route_other)
    )
    val initialRoute = initialSchedule?.routeOfAdministration
        ?: selectedMedicine?.administrationRoute?.takeIf { it.isNotBlank() }
        ?: "Oral"
    val knownRouteKeys = remember {
        setOf("Oral", "Topical", "Inhalation", "Injection", "Drops", "Sublingual", "Nasal", "Ophthalmic", "Otic")
    }
    var routeOfAdministration by remember {
        mutableStateOf(if (initialRoute in knownRouteKeys) initialRoute else "Other")
    }
    var customRouteText by remember {
        mutableStateOf(if (initialRoute !in knownRouteKeys) initialRoute else "")
    }
    var routeDropdownExpanded by remember { mutableStateOf(false) }

    var instructions by remember {
        mutableStateOf(initialSchedule?.instructions ?: "")
    }
    var startDateIso by remember {
        mutableStateOf(initialSchedule?.startDateIso ?: todayIso)
    }
    var endDateIso by remember {
        mutableStateOf(initialSchedule?.endDateIso ?: "")
    }
    var showStartDatePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }

    var frequencyType by remember {
        mutableStateOf(initialSchedule?.frequencyType ?: ScheduleFrequency.DAILY.key)
    }
    var selectedDaysOfWeek by remember {
        mutableStateOf(
            initialSchedule?.selectedDaysMask
                ?.split(",")
                ?.mapNotNull { it.trim().toIntOrNull() }
                ?.toSet()
                ?: setOf(1, 2, 3, 4, 5, 6, 7)
        )
    }
    var intervalDaysText by remember {
        mutableStateOf((initialSchedule?.intervalDays ?: 2).toString())
    }

    var timeSlots by remember {
        val parsed = initialSchedule?.let {
            ScheduledTimeSlot.decodeList(it.doseTimesJson)
        }.orEmpty()
        mutableStateOf<List<ScheduledTimeSlot>>(
            if (parsed.isNotEmpty()) parsed
            else listOf(ScheduledTimeSlot(timeHm = "08:00", dosage = 1.0, unit = dosageUnit))
        )
    }

    var remindersEnabled by remember {
        mutableStateOf(initialSchedule?.remindersEnabled ?: true)
    }
    var autoDeductInventory by remember {
        mutableStateOf(initialSchedule?.autoDeductInventory ?: true)
    }
    var snoozeMinutesText by remember {
        mutableStateOf((initialSchedule?.snoozeMinutes ?: 15).toString())
    }
    var lowStockThresholdText by remember {
        val initThresh = initialSchedule?.lowStockThreshold
        mutableStateOf(
            if (initThresh != null) {
                if (initThresh % 1.0 == 0.0) initThresh.toInt().toString() else initThresh.toString()
            } else {
                "5"
            }
        )
    }
    var userNotes by remember {
        mutableStateOf(initialSchedule?.userNotes ?: "")
    }

    // Expiry Protection state
    var manualExpiryInputIso by remember(selectedMedicineId, selectedInventoryItem) {
        mutableStateOf(
            selectedInventoryItem?.expiryDate?.takeIf { it > 0L }?.let {
                MedicationScheduleEngine.millisToLocalDateIso(it)
            } ?: ""
        )
    }
    var acknowledgeExpiringSoon by remember(selectedMedicineId) { mutableStateOf(false) }

    val effectiveExpiryMillis = remember(selectedInventoryItem, manualExpiryInputIso) {
        val parsedManual = MedicationScheduleEngine.localDateIsoToStartOfDayMillis(manualExpiryInputIso)
        parsedManual ?: selectedInventoryItem?.expiryDate
    }

    val expiryEvaluation = remember(selectedMedicine, effectiveExpiryMillis, todayIso, warningDays) {
        MedicationScheduleEngine.evaluateExpirySafety(
            medicineName = selectedMedicine?.tradeName ?: "",
            expiryDateMillis = effectiveExpiryMillis,
            warningDays = warningDays
        )
    }

    // Section 5: Immediately detect if the selected medicine is expired using shared status calculation
    val isSelectedMedicineExpired = remember(selectedMedicine, expiryEvaluation, effectiveExpiryMillis) {
        selectedMedicine != null && (
            expiryEvaluation.status == ExpirySafetyStatus.EXPIRED ||
                (effectiveExpiryMillis == null && selectedMedicine.expirationStatus == MedicineExpirationStatus.EXPIRED.key)
            )
    }

    var validationErrors by remember { mutableStateOf<List<ScheduleValidationError>>(emptyList()) }
    var submitErrorMessage by remember { mutableStateOf<String?>(null) }

    // Calendar Picker for Start Date (Section 6.2)
    if (showStartDatePicker) {
        val initialStartMillis = remember(startDateIso) {
            runCatching { LocalDate.parse(startDateIso.trim()) }
                .getOrNull()
                ?.let { ExpiryService.localDateToDatePickerMillis(it) }
                ?: ExpiryService.localDateToDatePickerMillis(ExpiryService.currentLocalDate())
        }
        val startPickerState = rememberDatePickerState(initialSelectedDateMillis = initialStartMillis)
        DatePickerDialog(
            onDismissRequest = { showStartDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        startPickerState.selectedDateMillis?.let { millis ->
                            val pickedDate = ExpiryService.datePickerMillisToLocalDate(millis)
                            val newStartIso = pickedDate.toString()
                            startDateIso = newStartIso
                            if (endDateIso.isNotBlank() && endDateIso < newStartIso) {
                                endDateIso = newStartIso
                            }
                        }
                        showStartDatePicker = false
                    },
                    modifier = Modifier.testTag("confirm_start_date_picker_button")
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showStartDatePicker = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        ) {
            DatePicker(state = startPickerState)
        }
    }

    // Calendar Picker for End Date (Section 6.2) — enforces endDate >= startDate
    if (showEndDatePicker) {
        val minEndDate = remember(startDateIso) {
            runCatching { LocalDate.parse(startDateIso.trim()) }
                .getOrDefault(ExpiryService.currentLocalDate())
        }
        val minEndMillis = remember(minEndDate) {
            ExpiryService.localDateToDatePickerMillis(minEndDate)
        }
        val initialEndMillis = remember(endDateIso, minEndMillis) {
            runCatching { LocalDate.parse(endDateIso.trim()) }
                .getOrNull()
                ?.let { ExpiryService.localDateToDatePickerMillis(it) }
                ?.coerceAtLeast(minEndMillis)
                ?: minEndMillis
        }
        val endPickerState = rememberDatePickerState(
            initialSelectedDateMillis = initialEndMillis,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val candidate = ExpiryService.datePickerMillisToLocalDate(utcTimeMillis)
                    return !candidate.isBefore(minEndDate)
                }

                override fun isSelectableYear(year: Int): Boolean {
                    return year >= minEndDate.year
                }
            }
        )
        DatePickerDialog(
            onDismissRequest = { showEndDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        endPickerState.selectedDateMillis?.let { millis ->
                            val pickedDate = ExpiryService.datePickerMillisToLocalDate(millis)
                            if (!pickedDate.isBefore(minEndDate)) {
                                endDateIso = pickedDate.toString()
                            }
                        }
                        showEndDatePicker = false
                    },
                    modifier = Modifier.testTag("confirm_end_date_picker_button")
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showEndDatePicker = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        ) {
            DatePicker(state = endPickerState)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 16.dp)
                .imePadding(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header Bar
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = when {
                                    replacingSchedule != null -> Icons.Default.SwapHoriz
                                    existingSchedule != null -> Icons.Default.EditCalendar
                                    else -> Icons.Default.AddAlarm
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = when {
                                    replacingSchedule != null -> stringResource(R.string.sched_replace_title)
                                    existingSchedule != null -> stringResource(R.string.sched_edit_title)
                                    else -> stringResource(R.string.sched_add_title)
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.testTag("close_schedule_dialog_button")
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.close)
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 1. SELECT REGISTERED MEDICINE FROM CABINET DROPDOWN
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.sched_select_medicine_label),
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.primary
                            )

                            if (medicines.isEmpty()) {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("schedule_empty_cabinet_notice"),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                                    ),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Info,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = stringResource(R.string.sched_empty_cabinet_notice),
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            } else {
                                ExposedDropdownMenuBox(
                                    expanded = medicineDropdownExpanded,
                                    onExpandedChange = {
                                        medicineDropdownExpanded = !medicineDropdownExpanded
                                        if (medicineDropdownExpanded) {
                                            isFilteringDropdown = false
                                        }
                                    }
                                ) {
                                    OutlinedTextField(
                                        value = medicineSearchQuery,
                                        onValueChange = { query ->
                                            medicineSearchQuery = query
                                            isFilteringDropdown = true
                                            medicineDropdownExpanded = true
                                            val exactMatch = medicines.find {
                                                formatMedicineLabel(it).equals(query.trim(), ignoreCase = true) ||
                                                    it.tradeName.equals(query.trim(), ignoreCase = true)
                                            }
                                            selectedMedicineId = exactMatch?.id
                                        },
                                        label = { Text(stringResource(R.string.trade_name)) },
                                        placeholder = { Text(stringResource(R.string.sched_search_medicine_placeholder)) },
                                        singleLine = true,
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Medication,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        },
                                        trailingIcon = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (medicineSearchQuery.isNotEmpty()) {
                                                    IconButton(
                                                        onClick = {
                                                            medicineSearchQuery = ""
                                                            selectedMedicineId = null
                                                            isFilteringDropdown = true
                                                            medicineDropdownExpanded = true
                                                        },
                                                        modifier = Modifier.testTag("clear_medicine_search_button")
                                                    ) {
                                                        Icon(
                                                            Icons.Default.Clear,
                                                            contentDescription = stringResource(R.string.close),
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                }
                                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = medicineDropdownExpanded)
                                            }
                                        },
                                        modifier = Modifier
                                            .menuAnchor(MenuAnchorType.PrimaryEditable)
                                            .fillMaxWidth()
                                            .testTag("schedule_medicine_selector")
                                    )

                                    DropdownMenu(
                                        expanded = medicineDropdownExpanded,
                                        onDismissRequest = {
                                            medicineDropdownExpanded = false
                                            isFilteringDropdown = false
                                            if (selectedMedicine != null) {
                                                medicineSearchQuery = formatMedicineLabel(selectedMedicine)
                                            }
                                        },
                                        properties = PopupProperties(focusable = false),
                                        modifier = Modifier
                                            .exposedDropdownSize(matchTextFieldWidth = true)
                                            .heightIn(max = 280.dp)
                                            .testTag("schedule_medicine_dropdown_menu")
                                    ) {
                                        if (filteredMedicines.isEmpty()) {
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        text = stringResource(R.string.sched_med_not_found_notice),
                                                        fontSize = 12.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                },
                                                onClick = { medicineDropdownExpanded = false },
                                                enabled = false,
                                                modifier = Modifier.testTag("schedule_medicine_not_found_item")
                                            )
                                        } else {
                                            filteredMedicines.forEach { med ->
                                                val medInv = inventoryItems.filter { it.medicineId == med.id }
                                                    .maxByOrNull { it.expiryDate }
                                                val expText = medInv?.expiryDate?.takeIf { it > 0L }?.let {
                                                    MedicationScheduleEngine.millisToLocalDateIso(it)
                                                } ?: "-"
                                                val medEval = MedicationScheduleEngine.evaluateExpirySafety(
                                                    medicineName = med.tradeName,
                                                    expiryDateMillis = medInv?.expiryDate,
                                                    warningDays = warningDays
                                                )

                                                DropdownMenuItem(
                                                    text = {
                                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                            Row(
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                            ) {
                                                                Text(
                                                                    text = formatMedicineLabel(med),
                                                                    fontWeight = FontWeight.SemiBold,
                                                                    fontSize = 14.sp,
                                                                    modifier = Modifier.weight(1f, fill = false)
                                                                )
                                                                if (medEval.status == ExpirySafetyStatus.EXPIRED ||
                                                                    med.expirationStatus == MedicineExpirationStatus.EXPIRED.key
                                                                ) {
                                                                    Surface(
                                                                        color = Color(0xFFFEE2E2),
                                                                        shape = RoundedCornerShape(6.dp)
                                                                    ) {
                                                                        Text(
                                                                            text = stringResource(R.string.expired_badge),
                                                                            color = Color(0xFF991B1B),
                                                                            fontSize = 10.sp,
                                                                            fontWeight = FontWeight.Bold,
                                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                                        )
                                                                    }
                                                                }
                                                            }
                                                            val subDetails = buildString {
                                                                if (!med.scientificName.isNullOrBlank()) {
                                                                    append("${med.scientificName} • ")
                                                                }
                                                                append("${stringResource(R.string.expiry_date)}: $expText")
                                                            }
                                                            Text(
                                                                text = subDetails,
                                                                fontSize = 12.sp,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                        }
                                                    },
                                                    onClick = {
                                                        selectedMedicineId = med.id
                                                        medicineSearchQuery = formatMedicineLabel(med)
                                                        isFilteringDropdown = false
                                                        manualExpiryInputIso = medInv?.expiryDate?.takeIf { it > 0L }?.let {
                                                            MedicationScheduleEngine.millisToLocalDateIso(it)
                                                        } ?: ""
                                                        acknowledgeExpiringSoon = false
                                                        validationErrors = emptyList()
                                                        submitErrorMessage = null
                                                        medicineDropdownExpanded = false
                                                    },
                                                    modifier = Modifier.testTag("medicine_dropdown_item_${med.id}")
                                                )
                                            }
                                        }
                                    }
                                }

                                // Selected medicine summary badge showing strength & formulation from database
                                if (selectedMedicine != null) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                                            .padding(horizontal = 12.dp, vertical = 8.dp)
                                            .testTag("selected_medicine_details_badge")
                                    ) {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = stringResource(
                                                R.string.sched_selected_med_details,
                                                selectedMedicine.strength?.takeIf { it.isNotBlank() } ?: "-",
                                                selectedMedicine.form
                                            ),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                // Clear helper notice explaining that unregistered medicines must be added via Add Medicine first
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("unregistered_medicine_guidance_text")
                                ) {
                                    Icon(
                                        Icons.Default.Info,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.sched_med_not_found_notice),
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            // STRICT EXPIRED-MEDICINE PROTECTION BANNER (Section 5)
                            if (selectedMedicine != null) {
                                ExpiryProtectionCard(
                                    medicineName = selectedMedicine.tradeName,
                                    evaluation = expiryEvaluation,
                                    isExpiredOverride = isSelectedMedicineExpired,
                                    manualExpiryInputIso = manualExpiryInputIso,
                                    onManualExpiryChange = { manualExpiryInputIso = it },
                                    onSaveConfirmedExpiry = {
                                        val medId = selectedMedicineId ?: return@ExpiryProtectionCard
                                        val millis = MedicationScheduleEngine.localDateIsoToStartOfDayMillis(manualExpiryInputIso)
                                        if (millis != null) {
                                            viewModel.updateMedicineExpiryInCabinet(medId, millis)
                                        }
                                    },
                                    acknowledgeExpiringSoon = acknowledgeExpiringSoon,
                                    onAcknowledgeChange = { acknowledgeExpiringSoon = it }
                                )
                            }
                        }
                    }

                    // Section 5: Hide all subsequent scheduling steps when an expired medicine is selected!
                    if (!isSelectedMedicineExpired) {
                        // 2. PRESCRIBED DOSAGE & ADMINISTRATION (Section 6.1 Dropdowns)
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("schedule_step_dosage_card"),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.sched_dosage_section_label),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = stringResource(R.string.sched_dosage_Disclaimer),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    // Dosage Amount Dropdown
                                    ExposedDropdownMenuBox(
                                        expanded = dosageDropdownExpanded,
                                        onExpandedChange = { dosageDropdownExpanded = !dosageDropdownExpanded },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        OutlinedTextField(
                                            value = dosageText,
                                            onValueChange = { newVal ->
                                                dosageText = newVal
                                                val parsed = newVal.toDoubleOrNull()
                                                if (parsed != null && parsed > 0.0 && timeSlots.size == 1) {
                                                    timeSlots = listOf(timeSlots[0].copy(dosage = parsed, unit = dosageUnit))
                                                }
                                            },
                                            label = { Text(stringResource(R.string.sched_dosage_amount_label)) },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                            singleLine = true,
                                            trailingIcon = {
                                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = dosageDropdownExpanded)
                                            },
                                            modifier = Modifier
                                                .menuAnchor(MenuAnchorType.PrimaryEditable)
                                                .fillMaxWidth()
                                                .testTag("schedule_dosage_amount_input")
                                        )
                                        ExposedDropdownMenu(
                                            expanded = dosageDropdownExpanded,
                                            onDismissRequest = { dosageDropdownExpanded = false }
                                        ) {
                                            STANDARD_DOSAGE_AMOUNTS.forEach { amt ->
                                                DropdownMenuItem(
                                                    text = { Text("$amt $dosageUnit") },
                                                    onClick = {
                                                        dosageText = amt
                                                        isCustomDosageMode = false
                                                        val parsed = amt.toDoubleOrNull()
                                                        if (parsed != null && parsed > 0.0 && timeSlots.size == 1) {
                                                            timeSlots = listOf(timeSlots[0].copy(dosage = parsed, unit = dosageUnit))
                                                        }
                                                        dosageDropdownExpanded = false
                                                    },
                                                    modifier = Modifier.testTag("schedule_dosage_option_$amt")
                                                )
                                            }
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.custom_dosage_option)) },
                                                onClick = {
                                                    isCustomDosageMode = true
                                                    dosageDropdownExpanded = false
                                                },
                                                modifier = Modifier.testTag("schedule_dosage_option_other")
                                            )
                                        }
                                    }

                                    // Dosage Unit Dropdown
                                    val commonUnits = listOf("tablet", "capsule", "mL", "drop", "puff", "mg", "application", "sachet")
                                    ExposedDropdownMenuBox(
                                        expanded = unitDropdownExpanded,
                                        onExpandedChange = { unitDropdownExpanded = !unitDropdownExpanded },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        OutlinedTextField(
                                            value = dosageUnit,
                                            onValueChange = { newUnit ->
                                                dosageUnit = newUnit
                                                timeSlots = timeSlots.map { it.copy(unit = newUnit) }
                                            },
                                            label = { Text(stringResource(R.string.sched_dosage_unit_label)) },
                                            singleLine = true,
                                            trailingIcon = {
                                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = unitDropdownExpanded)
                                            },
                                            modifier = Modifier
                                                .menuAnchor(MenuAnchorType.PrimaryEditable)
                                                .fillMaxWidth()
                                                .testTag("schedule_dosage_unit_input")
                                        )
                                        ExposedDropdownMenu(
                                            expanded = unitDropdownExpanded,
                                            onDismissRequest = { unitDropdownExpanded = false }
                                        ) {
                                            commonUnits.forEach { u ->
                                                DropdownMenuItem(
                                                    text = { Text(u) },
                                                    onClick = {
                                                        dosageUnit = u
                                                        timeSlots = timeSlots.map { it.copy(unit = u) }
                                                        unitDropdownExpanded = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }

                                if (isCustomDosageMode) {
                                    val isCustomInvalid = dosageText.toDoubleOrNull()?.let { it <= 0.0 } ?: true
                                    OutlinedTextField(
                                        value = dosageText,
                                        onValueChange = { newVal ->
                                            if (newVal.all { it.isDigit() || it == '.' }) {
                                                dosageText = newVal
                                                val parsed = newVal.toDoubleOrNull()
                                                if (parsed != null && parsed > 0.0 && timeSlots.size == 1) {
                                                    timeSlots = listOf(timeSlots[0].copy(dosage = parsed, unit = dosageUnit))
                                                }
                                            }
                                        },
                                        label = { Text(stringResource(R.string.custom_dosage_gt_zero_label)) },
                                        isError = isCustomInvalid,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                        singleLine = true,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("schedule_dosage_custom_input")
                                    )
                                }

                                // Quick unit suggestion chips
                                val chipUnits = listOf("tablet", "capsule", "mL", "drop", "puff", "mg")
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    chipUnits.forEach { u ->
                                        FilterChip(
                                            selected = dosageUnit.equals(u, ignoreCase = true),
                                            onClick = {
                                                dosageUnit = u
                                                timeSlots = timeSlots.map { it.copy(unit = u) }
                                            },
                                            label = { Text(u, fontSize = 12.sp) }
                                        )
                                    }
                                }

                                // Route of administration Dropdown + Chips (Section 6.1)
                                Text(
                                    text = stringResource(R.string.sched_route_label),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                ExposedDropdownMenuBox(
                                    expanded = routeDropdownExpanded,
                                    onExpandedChange = { routeDropdownExpanded = !routeDropdownExpanded }
                                ) {
                                    val selectedRouteLabel = routeOptions.find { it.first == routeOfAdministration }?.second
                                        ?: routeOfAdministration
                                    OutlinedTextField(
                                        value = selectedRouteLabel,
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text(stringResource(R.string.sched_route_label)) },
                                        trailingIcon = {
                                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = routeDropdownExpanded)
                                        },
                                        modifier = Modifier
                                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                            .fillMaxWidth()
                                            .testTag("schedule_route_dropdown")
                                    )
                                    ExposedDropdownMenu(
                                        expanded = routeDropdownExpanded,
                                        onDismissRequest = { routeDropdownExpanded = false }
                                    ) {
                                        routeOptions.forEach { (key, label) ->
                                            DropdownMenuItem(
                                                text = { Text(label) },
                                                onClick = {
                                                    routeOfAdministration = key
                                                    routeDropdownExpanded = false
                                                },
                                                modifier = Modifier.testTag("schedule_route_option_$key")
                                            )
                                        }
                                    }
                                }

                                if (routeOfAdministration == "Other") {
                                    OutlinedTextField(
                                        value = customRouteText,
                                        onValueChange = { customRouteText = it },
                                        label = { Text(stringResource(R.string.specify_administration_route_label)) },
                                        isError = customRouteText.isBlank(),
                                        singleLine = true,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("schedule_custom_route_input")
                                    )
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    routeOptions.take(5).forEach { (key, label) ->
                                        FilterChip(
                                            selected = routeOfAdministration == key,
                                            onClick = { routeOfAdministration = key },
                                            label = { Text(label, fontSize = 12.sp) }
                                        )
                                    }
                                }

                                OutlinedTextField(
                                    value = instructions,
                                    onValueChange = { instructions = it },
                                    label = { Text(stringResource(R.string.sched_instructions_label)) },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("schedule_instructions_input")
                                )
                            }
                        }

                        // 3. SCHEDULE & FREQUENCY (Section 6.2 Calendar Date Pickers)
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("schedule_step_frequency_card"),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.sched_schedule_section_label),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )

                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Box(modifier = Modifier.weight(1f)) {
                                        OutlinedTextField(
                                            value = startDateIso,
                                            onValueChange = { startDateIso = it },
                                            readOnly = true,
                                            label = { Text(stringResource(R.string.sched_start_date_label)) },
                                            placeholder = { Text("YYYY-MM-DD") },
                                            singleLine = true,
                                            trailingIcon = {
                                                IconButton(
                                                    onClick = { showStartDatePicker = true },
                                                    modifier = Modifier.testTag("open_start_date_picker_button")
                                                ) {
                                                    Icon(
                                                        Icons.Default.CalendarMonth,
                                                        contentDescription = stringResource(R.string.sched_start_date_label),
                                                        tint = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { showStartDatePicker = true }
                                                .testTag("schedule_start_date_input")
                                        )
                                    }

                                    Box(modifier = Modifier.weight(1f)) {
                                        OutlinedTextField(
                                            value = endDateIso,
                                            onValueChange = { endDateIso = it },
                                            readOnly = true,
                                            label = { Text(stringResource(R.string.sched_end_date_label)) },
                                            placeholder = { Text(stringResource(R.string.optional_placeholder)) },
                                            singleLine = true,
                                            isError = endDateIso.isNotBlank() && endDateIso < startDateIso,
                                            trailingIcon = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    if (endDateIso.isNotBlank()) {
                                                        IconButton(
                                                            onClick = { endDateIso = "" },
                                                            modifier = Modifier.testTag("clear_end_date_button")
                                                        ) {
                                                            Icon(
                                                                Icons.Default.Clear,
                                                                contentDescription = stringResource(R.string.close),
                                                                modifier = Modifier.size(18.dp)
                                                            )
                                                        }
                                                    }
                                                    IconButton(
                                                        onClick = { showEndDatePicker = true },
                                                        modifier = Modifier.testTag("open_end_date_picker_button")
                                                    ) {
                                                        Icon(
                                                            Icons.Default.Event,
                                                            contentDescription = stringResource(R.string.sched_end_date_label),
                                                            tint = MaterialTheme.colorScheme.primary
                                                        )
                                                    }
                                                }
                                            },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { showEndDatePicker = true }
                                                .testTag("schedule_end_date_input")
                                        )
                                    }
                                }

                                // Frequency Options
                                val freqChoices = listOf(
                                    ScheduleFrequency.DAILY.key to stringResource(R.string.sched_freq_daily),
                                    ScheduleFrequency.SELECTED_DAYS.key to stringResource(R.string.sched_freq_selected_days),
                                    ScheduleFrequency.INTERVAL_DAYS.key to stringResource(R.string.sched_freq_interval)
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    freqChoices.forEach { (key, label) ->
                                        FilterChip(
                                            selected = frequencyType == key,
                                            onClick = { frequencyType = key },
                                            label = { Text(label, fontSize = 12.sp) },
                                            modifier = Modifier.testTag("freq_chip_$key")
                                        )
                                    }
                                }

                                if (frequencyType == ScheduleFrequency.SELECTED_DAYS.key) {
                                    val dayLabels = listOf(
                                        1 to stringResource(R.string.sched_day_mon),
                                        2 to stringResource(R.string.sched_day_tue),
                                        3 to stringResource(R.string.sched_day_wed),
                                        4 to stringResource(R.string.sched_day_thu),
                                        5 to stringResource(R.string.sched_day_fri),
                                        6 to stringResource(R.string.sched_day_sat),
                                        7 to stringResource(R.string.sched_day_sun)
                                    )
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        dayLabels.forEach { (dayNum, label) ->
                                            val isSelected = dayNum in selectedDaysOfWeek
                                            FilterChip(
                                                selected = isSelected,
                                                onClick = {
                                                    selectedDaysOfWeek = if (isSelected) {
                                                        selectedDaysOfWeek - dayNum
                                                    } else {
                                                        selectedDaysOfWeek + dayNum
                                                    }
                                                },
                                                label = { Text(label, fontSize = 12.sp) }
                                            )
                                        }
                                    }
                                } else if (frequencyType == ScheduleFrequency.INTERVAL_DAYS.key) {
                                    OutlinedTextField(
                                        value = intervalDaysText,
                                        onValueChange = { intervalDaysText = it },
                                        label = { Text(stringResource(R.string.sched_interval_days_label)) },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }

                        // 4. REMINDER TIMES & MULTI-DOSE SLOTS (Section 6.3 Dropdowns)
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("schedule_step_times_card"),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.sched_times_section_label),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(
                                        onClick = {
                                            val defaultDose = dosageText.toDoubleOrNull()?.takeIf { it > 0.0 } ?: 1.0
                                            val nextSuggestedTime = when (timeSlots.size) {
                                                1 -> "14:00"
                                                2 -> "20:00"
                                                else -> "22:00"
                                            }
                                            timeSlots = timeSlots + ScheduledTimeSlot(
                                                timeHm = nextSuggestedTime,
                                                dosage = defaultDose,
                                                unit = dosageUnit
                                            )
                                        },
                                        modifier = Modifier.testTag("add_time_slot_button")
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(stringResource(R.string.sched_add_time_slot_btn), fontSize = 12.sp)
                                    }
                                }

                                timeSlots.forEachIndexed { idx, slot ->
                                    TimeSlotDropdownRow(
                                        index = idx,
                                        slot = slot,
                                        dosageUnit = dosageUnit,
                                        canDelete = timeSlots.size > 1,
                                        onTimeChange = { newTime ->
                                            timeSlots = timeSlots.toMutableList().also {
                                                it[idx] = slot.copy(timeHm = newTime)
                                            }
                                        },
                                        onDosageChange = { newDose ->
                                            timeSlots = timeSlots.toMutableList().also {
                                                it[idx] = slot.copy(dosage = newDose)
                                            }
                                        },
                                        onDelete = {
                                            timeSlots = timeSlots.toMutableList().also { it.removeAt(idx) }
                                        }
                                    )
                                }
                            }
                        }

                        // 5. REMINDER & INVENTORY OPTIONS
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("schedule_step_options_card"),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.sched_options_section_label),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.sched_enable_reminders_label),
                                        fontSize = 14.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Switch(
                                        checked = remindersEnabled,
                                        onCheckedChange = { remindersEnabled = it },
                                        modifier = Modifier.testTag("enable_reminders_switch")
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.sched_auto_deduct_label),
                                        fontSize = 14.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Switch(
                                        checked = autoDeductInventory,
                                        onCheckedChange = { autoDeductInventory = it }
                                    )
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedTextField(
                                        value = snoozeMinutesText,
                                        onValueChange = { snoozeMinutesText = it },
                                        label = { Text(stringResource(R.string.sched_snooze_duration_label)) },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        value = lowStockThresholdText,
                                        onValueChange = { lowStockThresholdText = it },
                                        label = { Text(stringResource(R.string.sched_low_stock_threshold_label)) },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                OutlinedTextField(
                                    value = userNotes,
                                    onValueChange = { userNotes = it },
                                    label = { Text(stringResource(R.string.sched_user_notes_label)) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        // 6. REVIEW SUMMARY CARD
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("schedule_step_review_card"),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.sched_review_summary_title),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "• ${selectedMedicine?.tradeName ?: "-"} ${selectedMedicine?.strength ?: ""} (${selectedMedicine?.form ?: "-"})",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "• ${timeSlots.joinToString(", ") { s -> "${s.timeHm} (${s.dosage} $dosageUnit)" }}",
                                    fontSize = 12.sp
                                )
                                Text(
                                    text = "• $startDateIso → ${endDateIso.ifBlank { "∞" }} ($frequencyType)",
                                    fontSize = 12.sp
                                )
                                Text(
                                    text = stringResource(R.string.sched_safety_disclaimer),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // VALIDATION ERRORS DISPLAY
                    if (validationErrors.isNotEmpty() || submitErrorMessage != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                validationErrors.forEach { err ->
                                    val msg = when (err) {
                                        ScheduleValidationError.MISSING_MEDICINE -> stringResource(R.string.sched_err_missing_med)
                                        ScheduleValidationError.INVALID_DEFAULT_DOSAGE,
                                        ScheduleValidationError.INVALID_SLOT_DOSAGE -> stringResource(R.string.sched_err_invalid_dosage)
                                        ScheduleValidationError.MISSING_DOSAGE_UNIT -> stringResource(R.string.sched_err_missing_unit)
                                        ScheduleValidationError.INVALID_START_DATE -> stringResource(R.string.sched_err_invalid_start)
                                        ScheduleValidationError.END_DATE_BEFORE_START_DATE,
                                        ScheduleValidationError.SCHEDULE_EXTENDS_PAST_EXPIRY -> stringResource(R.string.sched_err_end_before_start)
                                        ScheduleValidationError.EMPTY_REMINDER_TIMES -> stringResource(R.string.sched_err_empty_times)
                                        ScheduleValidationError.INVALID_REMINDER_TIME_FORMAT -> stringResource(R.string.sched_err_invalid_time)
                                        ScheduleValidationError.EMPTY_SELECTED_DAYS -> stringResource(R.string.sched_err_empty_days)
                                        ScheduleValidationError.INVALID_INTERVAL_DAYS -> stringResource(R.string.sched_err_invalid_interval)
                                        ScheduleValidationError.MEDICINE_EXPIRED ->
                                            stringResource(R.string.sched_expired_med_cannot_schedule)
                                        ScheduleValidationError.MEDICINE_EXPIRY_MISSING,
                                        ScheduleValidationError.MEDICINE_EXPIRY_INVALID -> stringResource(
                                            R.string.sched_expiry_missing_warning,
                                            expiryEvaluation.medicineName
                                        )
                                        ScheduleValidationError.EXPIRING_SOON_NOT_ACKNOWLEDGED -> stringResource(
                                            R.string.sched_expiry_soon_warning,
                                            expiryEvaluation.medicineName,
                                            expiryEvaluation.expiryDateIso ?: "",
                                            expiryEvaluation.daysRemaining ?: 0
                                        )
                                    }
                                    Text(
                                        text = msg,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                submitErrorMessage?.let {
                                    Text(
                                        text = it,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }

                    // SAVE BUTTON — requires a valid registered medicine from the cabinet & non-blocked expiry status
                    val isBlockedByExpiry = selectedMedicine != null && (
                        isSelectedMedicineExpired ||
                            expiryEvaluation.status == ExpirySafetyStatus.EXPIRED ||
                            expiryEvaluation.status == ExpirySafetyStatus.MISSING_EXPIRY ||
                            expiryEvaluation.status == ExpirySafetyStatus.INVALID_EXPIRY ||
                            (expiryEvaluation.status == ExpirySafetyStatus.EXPIRING_SOON && !acknowledgeExpiringSoon)
                        )

                    val resolvedRoute = if (routeOfAdministration == "Other") {
                        customRouteText.trim().ifBlank { "Oral" }
                    } else {
                        routeOfAdministration
                    }

                    Button(
                        onClick = {
                            submitErrorMessage = null
                            if (routeOfAdministration == "Other" && customRouteText.isBlank()) {
                                submitErrorMessage = context.getString(R.string.err_specify_administration_route)
                                return@Button
                            }
                            val selectedMed = selectedMedicine
                            val draft = ScheduleDraftInput(
                                scheduleId = existingSchedule?.id,
                                medicineId = selectedMed?.id,
                                inventoryItemId = selectedInventoryItem?.id,
                                medicineName = selectedMed?.tradeName ?: "",
                                strength = selectedMed?.strength,
                                formulation = selectedMed?.form,
                                defaultDosage = dosageText.toDoubleOrNull() ?: 0.0,
                                dosageUnit = dosageUnit,
                                routeOfAdministration = resolvedRoute,
                                instructions = instructions.ifBlank { null },
                                startDateIso = startDateIso,
                                endDateIso = endDateIso.ifBlank { null },
                                frequency = ScheduleFrequency.fromKey(frequencyType),
                                selectedDaysOfWeek = selectedDaysOfWeek,
                                intervalDays = intervalDaysText.toIntOrNull() ?: 1,
                                timeSlots = timeSlots,
                                remindersEnabled = remindersEnabled,
                                snoozeMinutes = snoozeMinutesText.toIntOrNull() ?: 15,
                                autoDeductInventory = autoDeductInventory,
                                lowStockThreshold = lowStockThresholdText.toDoubleOrNull() ?: 5.0,
                                userNotes = userNotes.ifBlank { null },
                                recordedExpiryDateMillis = effectiveExpiryMillis,
                                expiringSoonAcknowledged = acknowledgeExpiringSoon
                            )

                            val validation = viewModel.validateDraft(draft)
                            validationErrors = validation.errors
                            if (validation.isValid) {
                                when {
                                    replacingSchedule != null -> {
                                        viewModel.replaceSchedule(
                                            oldScheduleId = replacingSchedule.id,
                                            replacementDraft = draft,
                                            onSuccess = onSaved,
                                            onError = { submitErrorMessage = it.message }
                                        )
                                    }
                                    existingSchedule != null -> {
                                        viewModel.updateSchedule(
                                            draft = draft,
                                            onSuccess = onSaved,
                                            onError = { submitErrorMessage = it.message }
                                        )
                                    }
                                    else -> {
                                        viewModel.createSchedule(
                                            draft = draft,
                                            onSuccess = onSaved,
                                            onError = { submitErrorMessage = it.message }
                                        )
                                    }
                                }
                            }
                        },
                        enabled = selectedMedicine != null && !isBlockedByExpiry,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("save_schedule_button"),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.sched_save_schedule_btn),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeSlotDropdownRow(
    index: Int,
    slot: ScheduledTimeSlot,
    dosageUnit: String,
    canDelete: Boolean,
    onTimeChange: (String) -> Unit,
    onDosageChange: (Double) -> Unit,
    onDelete: () -> Unit
) {
    var timeDropdownExpanded by remember { mutableStateOf(false) }
    var doseDropdownExpanded by remember { mutableStateOf(false) }

    var slotDoseText by remember(slot.dosage) {
        mutableStateOf(
            if (slot.dosage % 1.0 == 0.0) slot.dosage.toInt().toString()
            else slot.dosage.toString()
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Daily Reminder Time Dropdown (Section 6.3)
        ExposedDropdownMenuBox(
            expanded = timeDropdownExpanded,
            onExpandedChange = { timeDropdownExpanded = !timeDropdownExpanded },
            modifier = Modifier.weight(1.2f)
        ) {
            OutlinedTextField(
                value = slot.timeHm,
                onValueChange = { newTime -> onTimeChange(newTime) },
                label = { Text("${stringResource(R.string.sched_slot_time_label)} #${index + 1}") },
                placeholder = { Text("08:00") },
                singleLine = true,
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = timeDropdownExpanded)
                },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryEditable)
                    .fillMaxWidth()
                    .testTag("time_slot_input_$index")
            )
            ExposedDropdownMenu(
                expanded = timeDropdownExpanded,
                onDismissRequest = { timeDropdownExpanded = false },
                modifier = Modifier.heightIn(max = 260.dp)
            ) {
                STANDARD_REMINDER_TIMES.forEach { t ->
                    DropdownMenuItem(
                        text = { Text(t) },
                        onClick = {
                            onTimeChange(t)
                            timeDropdownExpanded = false
                        },
                        modifier = Modifier.testTag("time_slot_${index}_option_$t")
                    )
                }
            }
        }

        // Specific Dosage Amount Dropdown (Section 6.3)
        ExposedDropdownMenuBox(
            expanded = doseDropdownExpanded,
            onExpandedChange = { doseDropdownExpanded = !doseDropdownExpanded },
            modifier = Modifier.weight(1f)
        ) {
            OutlinedTextField(
                value = slotDoseText,
                onValueChange = { txt ->
                    slotDoseText = txt
                    val parsed = txt.toDoubleOrNull()
                    if (parsed != null && parsed > 0.0) {
                        onDosageChange(parsed)
                    }
                },
                label = { Text("${stringResource(R.string.sched_slot_dosage_label)} ($dosageUnit)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = doseDropdownExpanded)
                },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryEditable)
                    .fillMaxWidth()
                    .testTag("dose_slot_input_$index")
            )
            ExposedDropdownMenu(
                expanded = doseDropdownExpanded,
                onDismissRequest = { doseDropdownExpanded = false },
                modifier = Modifier.heightIn(max = 260.dp)
            ) {
                STANDARD_DOSAGE_AMOUNTS.forEach { amt ->
                    DropdownMenuItem(
                        text = { Text("$amt $dosageUnit") },
                        onClick = {
                            slotDoseText = amt
                            amt.toDoubleOrNull()?.let { onDosageChange(it) }
                            doseDropdownExpanded = false
                        },
                        modifier = Modifier.testTag("dose_slot_${index}_option_$amt")
                    )
                }
            }
        }

        if (canDelete) {
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = stringResource(R.string.delete),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

private fun formatMedicineLabel(medicine: Medicine): String {
    return buildString {
        append(medicine.tradeName)
        if (!medicine.strength.isNullOrBlank()) {
            append(" (${medicine.strength})")
        }
        if (medicine.form.isNotBlank()) {
            append(" • ${medicine.form}")
        }
    }
}

@Composable
private fun ExpiryProtectionCard(
    medicineName: String,
    evaluation: ExpiryEvaluationResult,
    isExpiredOverride: Boolean = false,
    manualExpiryInputIso: String,
    onManualExpiryChange: (String) -> Unit,
    onSaveConfirmedExpiry: () -> Unit,
    acknowledgeExpiringSoon: Boolean,
    onAcknowledgeChange: (Boolean) -> Unit
) {
    when {
        isExpiredOverride || evaluation.status == ExpirySafetyStatus.EXPIRED -> {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("expiry_blocked_banner"),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFEE2E2)),
                border = BorderStroke(1.5.dp, Color(0xFFDC2626))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = Color(0xFFDC2626),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.sched_expired_med_cannot_schedule),
                            color = Color(0xFFB91C1C),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.testTag("schedule_expired_medicine_warning_text")
                        )
                    }
                    if (!evaluation.expiryDateIso.isNullOrBlank()) {
                        Text(
                            text = stringResource(
                                R.string.sched_expiry_expired_warning,
                                medicineName.ifBlank { evaluation.medicineName },
                                evaluation.expiryDateIso
                            ),
                            color = Color(0xFF991B1B),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        evaluation.status == ExpirySafetyStatus.EXPIRING_SOON -> {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("expiry_soon_banner"),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7)),
                border = BorderStroke(1.dp, Color(0xFFF59E0B))
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(
                            R.string.sched_expiry_soon_warning,
                            medicineName.ifBlank { evaluation.medicineName },
                            evaluation.expiryDateIso ?: "",
                            evaluation.daysRemaining ?: 0
                        ),
                        color = Color(0xFF92400E),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onAcknowledgeChange(!acknowledgeExpiringSoon) }
                    ) {
                        Checkbox(
                            checked = acknowledgeExpiringSoon,
                            onCheckedChange = onAcknowledgeChange,
                            modifier = Modifier.testTag("acknowledge_expiring_soon_checkbox")
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(
                                R.string.sched_expiry_soon_ack_checkbox,
                                evaluation.expiryDateIso ?: ""
                            ),
                            color = Color(0xFF92400E),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        evaluation.status == ExpirySafetyStatus.MISSING_EXPIRY ||
            evaluation.status == ExpirySafetyStatus.INVALID_EXPIRY -> {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("expiry_missing_banner"),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7)),
                border = BorderStroke(1.dp, Color(0xFFF59E0B))
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(
                            R.string.sched_expiry_missing_warning,
                            medicineName.ifBlank { evaluation.medicineName }
                        ),
                        color = Color(0xFF92400E),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = manualExpiryInputIso,
                            onValueChange = onManualExpiryChange,
                            label = { Text(stringResource(R.string.expiry_date)) },
                            placeholder = { Text("YYYY-MM-DD") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("confirm_missing_expiry_input")
                        )
                        Button(
                            onClick = onSaveConfirmedExpiry,
                            modifier = Modifier.testTag("confirm_missing_expiry_button")
                        ) {
                            Text(stringResource(R.string.sched_expiry_confirm_btn), fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        else -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFDCFCE7))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(
                    Icons.Default.VerifiedUser,
                    contentDescription = null,
                    tint = Color(0xFF166534),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${stringResource(R.string.expiry_date)}: ${evaluation.expiryDateIso ?: ""}",
                    color = Color(0xFF166534),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
