package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.WarningAmber
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.boxscan.models.MedForm
import com.example.boxscan.models.MedicineDraft
import com.example.boxscan.ui.BoxScanScreen
import com.example.data.MedicationRefillClassification
import com.example.safety.ExpiryService
import com.example.safety.MedicineExpirationStatus
import com.example.ui.utils.getLocalizedName
import com.example.ui.viewmodels.AppViewModel
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddMedicineScreen(
    initialTradeName: String? = null,
    initialManufacturer: String? = null,
    prefillDraft: MedicineDraft? = null,
    onSave: () -> Unit,
    onBack: () -> Unit = {},
    viewModel: AppViewModel = viewModel()
) {
    val context = LocalContext.current
    // Allow launching BoxScanScreen directly from AddMedicineScreen as well as receiving prefillDraft from route
    var showInlineBoxScanner by remember { mutableStateOf(false) }
    var activeDraft by remember(prefillDraft) { mutableStateOf(prefillDraft) }
    val warningDays by viewModel.expiryWarningDays.collectAsState()

    val formTablets = stringResource(R.string.form_tablets)
    val formCapsules = stringResource(R.string.form_capsules)
    val formSyrup = stringResource(R.string.form_syrup)
    val formCream = stringResource(R.string.form_cream)
    val formSpray = stringResource(R.string.form_spray)
    val formDrops = stringResource(R.string.form_drops)
    val formInjections = stringResource(R.string.form_injections)
    val formOther = stringResource(R.string.form_other)

    val forms = listOf(
        formTablets,
        formCapsules,
        formSyrup,
        formCream,
        formSpray,
        formDrops,
        formInjections,
        formOther
    )
    val defaultForm = forms.firstOrNull() ?: ""
    val defaultUnit = stringResource(R.string.unit_package)
    val refrigeratorLocLabel = stringResource(R.string.loc_refrigerator)

    var tradeName by remember { mutableStateOf(initialTradeName ?: "") }
    var scientificName by remember { mutableStateOf("") }
    var strength by remember { mutableStateOf("") }
    var form by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("1") }
    var manufacturer by remember { mutableStateOf(initialManufacturer ?: "") }
    var batchNumber by remember { mutableStateOf("") }
    var storageLocation by remember { mutableStateOf("") }
    var needsRefrigeration by remember { mutableStateOf(false) }
    var indications by remember { mutableStateOf("") }
    var userNotes by remember { mutableStateOf("") }

    // Section 4.1: Medication Refill Classification (mutually exclusive checkboxes)
    var refillClassification by remember {
        mutableStateOf(MedicationRefillClassification.ONE_TIME)
    }

    // Section 6.1: Prescribed Dosage & Administration Route dropdowns
    val dosageAmountOptions = remember {
        listOf("0.5", "1", "1.5", "2", "2.5", "5", "10", "15", "20", "Other")
    }
    var selectedDosageOption by remember { mutableStateOf("1") }
    var customDosageAmount by remember { mutableStateOf("") }
    var dosageDropdownExpanded by remember { mutableStateOf(false) }

    val administrationRouteOptions = listOf(
        "Oral" to stringResource(R.string.sched_route_oral),
        "Topical" to stringResource(R.string.sched_route_topical),
        "Inhalation" to stringResource(R.string.sched_route_inhalation),
        "Injection" to stringResource(R.string.sched_route_injection),
        "Drops (Eye/Ear/Nasal)" to stringResource(R.string.route_drops_eye_ear_nasal),
        "Sublingual" to stringResource(R.string.route_sublingual),
        "Nasal" to stringResource(R.string.route_nasal),
        "Ophthalmic" to stringResource(R.string.route_ophthalmic),
        "Otic" to stringResource(R.string.route_otic),
        "Other" to stringResource(R.string.route_other)
    )
    var selectedRouteOption by remember { mutableStateOf("Oral") }
    var customRouteText by remember { mutableStateOf("") }
    var routeDropdownExpanded by remember { mutableStateOf(false) }

    // Track whether fields were prefilled from OCR and whether OCR expiry has been explicitly confirmed
    var isOcrPrefilled by remember { mutableStateOf(false) }
    var ocrExpiryConfirmed by remember { mutableStateOf(true) }
    var submissionErrorMessage by remember { mutableStateOf<String?>(null) }

    val allCategories by viewModel.allCategories.collectAsState()
    var selectedCategories by remember { mutableStateOf(setOf<Int>()) }
    var formExpanded by remember { mutableStateOf(false) }

    var showDatePicker by remember { mutableStateOf(false) }
    val defaultFutureLocalDate = remember { LocalDate.now().plusYears(1) }
    val futureOnlySelectableDates = remember {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val candidateDate = ExpiryService.datePickerMillisToLocalDate(utcTimeMillis)
                return ExpiryService.isValidNewMedicineExpiryDate(LocalDate.now(), candidateDate)
            }

            override fun isSelectableYear(year: Int): Boolean {
                return year >= LocalDate.now().year
            }
        }
    }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = ExpiryService.localDateToDatePickerMillis(defaultFutureLocalDate),
        selectableDates = futureOnlySelectableDates
    )

    val selectedLocalDate: LocalDate? = remember(datePickerState.selectedDateMillis) {
        datePickerState.selectedDateMillis?.let { ExpiryService.datePickerMillisToLocalDate(it) }
    }
    val selectedDateString = selectedLocalDate?.toString() ?: ""

    // Section 2: Strict future expiry validation (must be strictly later than today's local date)
    val isExpiryDateInvalid = remember(selectedLocalDate) {
        val date = selectedLocalDate ?: return@remember true
        !ExpiryService.isValidNewMedicineExpiryDate(LocalDate.now(), date)
    }

    val computedExpirationStatus = remember(selectedLocalDate, warningDays) {
        val date = selectedLocalDate ?: return@remember MedicineExpirationStatus.EXPIRED
        ExpiryService.evaluateExpirationStatus(LocalDate.now(), date, warningDays)
    }

    LaunchedEffect(defaultForm) {
        if (form.isEmpty()) {
            form = defaultForm
        }
    }

    // Apply MedicineDraft whenever activeDraft updates
    LaunchedEffect(activeDraft) {
        val draft = activeDraft ?: return@LaunchedEffect
        isOcrPrefilled = true
        draft.tradeName?.let { if (it.isNotBlank()) tradeName = it }
        draft.scientificName?.let { if (it.isNotBlank()) scientificName = it }
        draft.strength?.let { if (it.isNotBlank()) strength = it }
        draft.form?.let { medForm ->
            form = when (medForm) {
                MedForm.TABLET -> formTablets
                MedForm.CAPSULE -> formCapsules
                MedForm.SYRUP -> formSyrup
                MedForm.CREAM, MedForm.OINTMENT -> formCream
                MedForm.SPRAY -> formSpray
                MedForm.DROPS -> formDrops
                MedForm.INJECTION -> formInjections
            }
        }
        draft.packQuantity?.let { packStr ->
            val digits = Regex("""\d+""").find(packStr)?.value
            if (!digits.isNullOrBlank()) {
                quantity = digits
            }
        }
        draft.manufacturer?.let { if (it.isNotBlank()) manufacturer = it }
        draft.batchNumber?.let { if (it.isNotBlank()) batchNumber = it }
        if (draft.needsRefrigeration == true) {
            needsRefrigeration = true
            if (storageLocation.isBlank()) {
                storageLocation = refrigeratorLocLabel
            }
        }
        if (!draft.expiryDate.isNullOrBlank()) {
            runCatching {
                val parsedDate = LocalDate.parse(draft.expiryDate.trim())
                datePickerState.selectedDateMillis = ExpiryService.localDateToDatePickerMillis(parsedDate)
            }
            ocrExpiryConfirmed = draft.expiryExplicitlyConfirmed
        } else {
            ocrExpiryConfirmed = true
        }
        // Safety enforcement: never populate barcode, indications, warnings, or usageMode from BoxScan draft
    }

    if (showInlineBoxScanner) {
        BoxScanScreen(
            onDraftConfirmed = { confirmedDraft ->
                activeDraft = confirmedDraft
                showInlineBoxScanner = false
            },
            onCancel = {
                showInlineBoxScanner = false
            }
        )
        return
    }

    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = MaterialTheme.colorScheme.onSurface,
        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
        focusedLabelColor = MaterialTheme.colorScheme.primary,
        unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = Color.Transparent
    )

    val effectiveDosageValue = if (selectedDosageOption == "Other") {
        customDosageAmount.trim()
    } else {
        selectedDosageOption
    }
    val isDosageValid = effectiveDosageValue.toDoubleOrNull()?.let { it > 0.0 } == true

    val effectiveRouteValue = if (selectedRouteOption == "Other") {
        customRouteText.trim()
    } else {
        selectedRouteOption
    }
    val isRouteValid = effectiveRouteValue.isNotBlank()

    val parsedQuantity = quantity.toDoubleOrNull()
    val isQuantityValid = parsedQuantity != null && parsedQuantity >= 0.0

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_medicine_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    FilledTonalButton(
                        onClick = { showInlineBoxScanner = true },
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .testTag("add_screen_scan_box_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DocumentScanner,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.scan_medicine_box_title))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Prominent Scan medicine box banner card
            Card(
                onClick = { showInlineBoxScanner = true },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("scan_medicine_box_card")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.DocumentScanner,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.scan_medicine_box_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = stringResource(R.string.box_scan_banner_sub),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                        )
                    }
                }
            }

            // OCR Review Banner when prefilled from Box Scan
            if (isOcrPrefilled) {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFFEF3C7)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFFB45309)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.box_scan_prefilled_review_banner),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF92400E),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Step 1: Trade Name & Expiry Date (checked first to gate remaining steps when invalid)
            OutlinedTextField(
                shape = RoundedCornerShape(12.dp),
                colors = textFieldColors,
                value = tradeName,
                onValueChange = { tradeName = it },
                label = { Text(stringResource(R.string.trade_name)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("add_trade_name_input")
            )

            OutlinedTextField(
                shape = RoundedCornerShape(12.dp),
                colors = textFieldColors,
                value = selectedDateString,
                onValueChange = {},
                readOnly = true,
                isError = isExpiryDateInvalid,
                label = { Text(stringResource(R.string.expiry_date)) },
                trailingIcon = {
                    IconButton(onClick = { showDatePicker = true }) {
                        Icon(Icons.Default.DateRange, contentDescription = stringResource(R.string.expiry_date))
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showDatePicker = true }
                    .testTag("add_expiry_date_input")
            )

            if (showDatePicker) {
                DatePickerDialog(
                    onDismissRequest = { showDatePicker = false },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showDatePicker = false
                                ocrExpiryConfirmed = true
                                submissionErrorMessage = null
                            }
                        ) {
                            Text(stringResource(R.string.save))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDatePicker = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                ) {
                    DatePicker(state = datePickerState)
                }
            }

            // Section 2: If the entered/selected expiry date is invalid (today or earlier),
            // display the prominent red warning and hide all remaining Add Medicine form steps!
            if (isExpiryDateInvalid) {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFFEE2E2)
                    ),
                    border = BorderStroke(1.5.dp, Color(0xFFDC2626)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_medicine_expired_warning")
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = Color(0xFFB91C1C),
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.invalid_expiry_entry_error),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF991B1B)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.expired_warning),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFB91C1C)
                            )
                        }
                    }
                }
            } else {
                // Valid future expiry date selected: if within warning period (e.g., 7 days), show Expiring Soon notice
                if (computedExpirationStatus == MedicineExpirationStatus.EXPIRING_SOON) {
                    val daysLeft = selectedLocalDate?.let { ExpiryService.daysUntil(LocalDate.now(), it) } ?: 1L
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7)),
                        border = BorderStroke(1.dp, Color(0xFFD97706)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("add_medicine_expiring_soon_notice")
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.WarningAmber,
                                contentDescription = null,
                                tint = Color(0xFFB45309)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.expiring_soon_entry_warning, daysLeft, warningDays),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF92400E)
                            )
                        }
                    }
                }

                // Remaining Add Medicine form steps (visible only when expiry date is strictly in the future)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_medicine_remaining_steps"),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    OutlinedTextField(
                        shape = RoundedCornerShape(12.dp),
                        colors = textFieldColors,
                        value = scientificName,
                        onValueChange = { scientificName = it },
                        label = { Text(stringResource(R.string.scientific_name)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("add_scientific_name_input")
                    )
                    OutlinedTextField(
                        shape = RoundedCornerShape(12.dp),
                        colors = textFieldColors,
                        value = strength,
                        onValueChange = { strength = it },
                        label = { Text(stringResource(R.string.strength)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("add_strength_input")
                    )

                    ExposedDropdownMenuBox(
                        expanded = formExpanded,
                        onExpandedChange = { formExpanded = it }
                    ) {
                        OutlinedTextField(
                            shape = RoundedCornerShape(12.dp),
                            colors = textFieldColors,
                            value = form,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.dosage_form)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = formExpanded) },
                            modifier = Modifier
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth()
                                .testTag("add_form_dropdown")
                        )
                        ExposedDropdownMenu(
                            expanded = formExpanded,
                            onDismissRequest = { formExpanded = false }
                        ) {
                            forms.forEach { selectionOption ->
                                DropdownMenuItem(
                                    text = { Text(selectionOption) },
                                    onClick = {
                                        form = selectionOption
                                        formExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // Section 6.1: Prescribed Dosage & Administration Route Dropdowns
                    Text(
                        text = stringResource(R.string.prescribed_dosage_section_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        ExposedDropdownMenuBox(
                            expanded = dosageDropdownExpanded,
                            onExpandedChange = { dosageDropdownExpanded = it },
                            modifier = Modifier.weight(1f)
                        ) {
                            val selectedDosageDisplay = if (selectedDosageOption == "Other") {
                                stringResource(R.string.route_other)
                            } else {
                                selectedDosageOption
                            }
                            OutlinedTextField(
                                shape = RoundedCornerShape(12.dp),
                                colors = textFieldColors,
                                value = selectedDosageDisplay,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.prescribed_dosage_label, defaultUnit)) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dosageDropdownExpanded) },
                                modifier = Modifier
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                    .fillMaxWidth()
                                    .testTag("add_prescribed_dosage_dropdown")
                            )
                            ExposedDropdownMenu(
                                expanded = dosageDropdownExpanded,
                                onDismissRequest = { dosageDropdownExpanded = false }
                            ) {
                                dosageAmountOptions.forEach { option ->
                                    val optionLabel = if (option == "Other") stringResource(R.string.route_other) else option
                                    DropdownMenuItem(
                                        text = { Text(optionLabel) },
                                        onClick = {
                                            selectedDosageOption = option
                                            dosageDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        ExposedDropdownMenuBox(
                            expanded = routeDropdownExpanded,
                            onExpandedChange = { routeDropdownExpanded = it },
                            modifier = Modifier.weight(1f)
                        ) {
                            val selectedRouteDisplay = administrationRouteOptions.find { it.first == selectedRouteOption }?.second
                                ?: selectedRouteOption
                            OutlinedTextField(
                                shape = RoundedCornerShape(12.dp),
                                colors = textFieldColors,
                                value = selectedRouteDisplay,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.administration_route_label)) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = routeDropdownExpanded) },
                                modifier = Modifier
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                    .fillMaxWidth()
                                    .testTag("add_administration_route_dropdown")
                            )
                            ExposedDropdownMenu(
                                expanded = routeDropdownExpanded,
                                onDismissRequest = { routeDropdownExpanded = false }
                            ) {
                                administrationRouteOptions.forEach { (routeKey, routeLabel) ->
                                    DropdownMenuItem(
                                        text = { Text(routeLabel) },
                                        onClick = {
                                            selectedRouteOption = routeKey
                                            routeDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (selectedDosageOption == "Other") {
                        OutlinedTextField(
                            shape = RoundedCornerShape(12.dp),
                            colors = textFieldColors,
                            value = customDosageAmount,
                            onValueChange = { customDosageAmount = it },
                            label = { Text(stringResource(R.string.custom_prescribed_dosage_label, defaultUnit)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            isError = !isDosageValid,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("add_custom_dosage_input")
                        )
                    }

                    if (selectedRouteOption == "Other") {
                        OutlinedTextField(
                            shape = RoundedCornerShape(12.dp),
                            colors = textFieldColors,
                            value = customRouteText,
                            onValueChange = { customRouteText = it },
                            label = { Text(stringResource(R.string.custom_administration_route_label)) },
                            isError = !isRouteValid,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("add_custom_route_input")
                        )
                    }

                    // Section 4.1: Medication Refill Classification Checkboxes
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("add_refill_classification_section")
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = stringResource(R.string.med_classification_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        refillClassification = MedicationRefillClassification.ONGOING_REFILL
                                    }
                                    .padding(vertical = 4.dp)
                                    .testTag("checkbox_ongoing_refill"),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = refillClassification == MedicationRefillClassification.ONGOING_REFILL,
                                    onCheckedChange = {
                                        refillClassification = MedicationRefillClassification.ONGOING_REFILL
                                    }
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.classification_ongoing_refill),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        refillClassification = MedicationRefillClassification.ONE_TIME
                                    }
                                    .padding(vertical = 4.dp)
                                    .testTag("checkbox_one_time"),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = refillClassification == MedicationRefillClassification.ONE_TIME,
                                    onCheckedChange = {
                                        refillClassification = MedicationRefillClassification.ONE_TIME
                                    }
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.classification_one_time),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        shape = RoundedCornerShape(12.dp),
                        colors = textFieldColors,
                        value = quantity,
                        onValueChange = { quantity = it },
                        label = { Text(stringResource(R.string.remaining_quantity)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = !isQuantityValid,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("add_quantity_input")
                    )
                    OutlinedTextField(
                        shape = RoundedCornerShape(12.dp),
                        colors = textFieldColors,
                        value = manufacturer,
                        onValueChange = { manufacturer = it },
                        label = { Text(stringResource(R.string.manufacturer)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("add_manufacturer_input")
                    )
                    OutlinedTextField(
                        shape = RoundedCornerShape(12.dp),
                        colors = textFieldColors,
                        value = batchNumber,
                        onValueChange = { batchNumber = it },
                        label = { Text(stringResource(R.string.batch_number_label)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("add_batch_number_input")
                    )

                    OutlinedTextField(
                        shape = RoundedCornerShape(12.dp),
                        colors = textFieldColors,
                        value = storageLocation,
                        onValueChange = { storageLocation = it },
                        label = { Text(stringResource(R.string.storage_location)) },
                        modifier = Modifier.fillMaxWidth()
                    )

                    // If prefilled from OCR and not yet confirmed, require explicit expiry confirmation
                    if (isOcrPrefilled && !ocrExpiryConfirmed) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFFFEF3C7))
                                .clickable { ocrExpiryConfirmed = true }
                                .padding(10.dp)
                                .testTag("add_confirm_ocr_expiry_row"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = ocrExpiryConfirmed,
                                onCheckedChange = { ocrExpiryConfirmed = it }
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.box_scan_confirm_expiry_named, selectedDateString),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF92400E)
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = needsRefrigeration,
                            onCheckedChange = { needsRefrigeration = it }
                        )
                        Text(
                            stringResource(R.string.needs_refrigeration),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    if (allCategories.isNotEmpty()) {
                        Text(
                            stringResource(R.string.categories),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold
                        )
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            allCategories.forEach { category ->
                                val isSelected = selectedCategories.contains(category.id)
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        selectedCategories = if (isSelected) {
                                            selectedCategories - category.id
                                        } else {
                                            selectedCategories + category.id
                                        }
                                    },
                                    label = { Text(category.getLocalizedName()) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                        }
                    }

                    submissionErrorMessage?.let { errMsg ->
                        Text(
                            text = errMsg,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Button(
                        shape = RoundedCornerShape(12.dp),
                        enabled = tradeName.isNotBlank() &&
                            !isExpiryDateInvalid &&
                            isDosageValid &&
                            isRouteValid &&
                            isQuantityValid &&
                            (!isOcrPrefilled || ocrExpiryConfirmed),
                        onClick = {
                            val validDate = selectedLocalDate
                            if (validDate == null || !ExpiryService.isValidNewMedicineExpiryDate(LocalDate.now(), validDate)) {
                                submissionErrorMessage = context.getString(R.string.invalid_expiry_entry_error)
                                return@Button
                            }
                            if (tradeName.isNotBlank() && (!isOcrPrefilled || ocrExpiryConfirmed)) {
                                val storedExpiryMillis = ExpiryService.localDateToStoredExpiryMillis(validDate)
                                val derivedUnit = when (form) {
                                    formTablets -> "tablet"
                                    formCapsules -> "capsule"
                                    formSyrup -> "mL"
                                    formDrops -> "drop"
                                    formSpray -> "puff"
                                    formCream -> "application"
                                    formInjections -> "injection"
                                    else -> defaultUnit
                                }
                                viewModel.insertMedicine(
                                    tradeName = tradeName,
                                    scientificName = scientificName.takeIf { it.isNotBlank() },
                                    barcode = null,
                                    form = form,
                                    strength = strength.takeIf { it.isNotBlank() },
                                    manufacturer = manufacturer.takeIf { it.isNotBlank() },
                                    imagePath = null,
                                    indications = indications.takeIf { it.isNotBlank() },
                                    warnings = null,
                                    quantity = parsedQuantity ?: 1.0,
                                    unit = derivedUnit,
                                    purchaseDate = System.currentTimeMillis(),
                                    expiryDate = storedExpiryMillis,
                                    batchNumber = batchNumber.takeIf { it.isNotBlank() },
                                    storageLocation = storageLocation.takeIf { it.isNotBlank() },
                                    needsRefrigeration = needsRefrigeration,
                                    usageMode = "$effectiveDosageValue $derivedUnit ($effectiveRouteValue)",
                                    userNotes = userNotes.takeIf { it.isNotBlank() },
                                    categoryIds = selectedCategories,
                                    refillClassification = refillClassification.key,
                                    prescribedDosage = "$effectiveDosageValue $derivedUnit",
                                    administrationRoute = effectiveRouteValue,
                                    onValidationError = { err -> submissionErrorMessage = err },
                                    onSuccess = { onSave() }
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("save_medicine_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text(stringResource(R.string.save_medicine_button), color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
        }
    }
}
