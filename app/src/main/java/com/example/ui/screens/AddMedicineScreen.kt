package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.boxscan.models.MedForm
import com.example.boxscan.models.MedicineDraft
import com.example.boxscan.ui.BoxScanScreen
import com.example.safety.ExpiryService
import com.example.safety.ExpiryStatus
import com.example.safety.SafetyStrings
import com.example.ui.utils.getLocalizedName
import com.example.ui.viewmodels.AppViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddMedicineScreen(
    initialBarcode: String? = null,
    initialTradeName: String? = null,
    initialManufacturer: String? = null,
    prefillDraft: MedicineDraft? = null,
    onSave: () -> Unit,
    onBack: () -> Unit = {},
    viewModel: AppViewModel = viewModel()
) {
    // Allow launching BoxScanScreen directly from AddMedicineScreen as well as receiving prefillDraft from route
    var showInlineBoxScanner by remember { mutableStateOf(false) }
    var activeDraft by remember(prefillDraft) { mutableStateOf(prefillDraft) }

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
    var barcode by remember { mutableStateOf(initialBarcode ?: "") }
    var batchNumber by remember { mutableStateOf("") }
    var storageLocation by remember { mutableStateOf("") }
    var needsRefrigeration by remember { mutableStateOf(false) }
    var usageMode by remember { mutableStateOf("") }
    var indications by remember { mutableStateOf("") }
    var userNotes by remember { mutableStateOf("") }

    // Track whether fields were prefilled from OCR and whether OCR expiry has been explicitly confirmed
    var isOcrPrefilled by remember { mutableStateOf(false) }
    var ocrExpiryConfirmed by remember { mutableStateOf(true) }

    val allCategories by viewModel.allCategories.collectAsState()
    var selectedCategories by remember { mutableStateOf(setOf<Int>()) }
    var formExpanded by remember { mutableStateOf(false) }

    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000
    )
    val dateFormatter = remember {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
    }
    val selectedDateString = datePickerState.selectedDateMillis?.let {
        dateFormatter.format(java.util.Date(it))
    } ?: ""

    // Compute whether the currently selected expiry date is already in the past
    val isSelectedExpiryPast = remember(datePickerState.selectedDateMillis) {
        val millis = datePickerState.selectedDateMillis ?: return@remember false
        val selectedLocalDate = Instant.ofEpochMilli(millis)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
        ExpiryService.classify(LocalDate.now(), selectedLocalDate) == ExpiryStatus.EXPIRED
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
                val epochMillis = parsedDate
                    .atStartOfDay(java.time.ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli()
                datePickerState.selectedDateMillis = epochMillis
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Prominent "مسح علبة الدواء" (Scan medicine box) banner card
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

            // Expired Medicine Safety Disposal Warning (Mandatory SafetyStrings.EXPIRED_DISPOSAL)
            if (isSelectedExpiryPast) {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_medicine_expired_warning")
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.expired_warning),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

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

            OutlinedTextField(
                shape = RoundedCornerShape(12.dp),
                colors = textFieldColors,
                value = quantity,
                onValueChange = { quantity = it },
                label = { Text(stringResource(R.string.remaining_quantity)) },
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

            OutlinedTextField(
                shape = RoundedCornerShape(12.dp),
                colors = textFieldColors,
                value = selectedDateString,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.expiry_date)) },
                trailingIcon = {
                    IconButton(onClick = { showDatePicker = true }) {
                        Icon(Icons.Default.DateRange, contentDescription = "Select Date")
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("add_expiry_date_input")
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

            if (showDatePicker) {
                DatePickerDialog(
                    onDismissRequest = { showDatePicker = false },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showDatePicker = false
                                ocrExpiryConfirmed = true
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

            Button(
                shape = RoundedCornerShape(12.dp),
                enabled = tradeName.isNotBlank() && (!isOcrPrefilled || ocrExpiryConfirmed),
                onClick = {
                    if (tradeName.isNotBlank() && (!isOcrPrefilled || ocrExpiryConfirmed)) {
                        viewModel.insertMedicine(
                            tradeName = tradeName,
                            scientificName = scientificName.takeIf { it.isNotBlank() },
                            barcode = barcode.takeIf { it.isNotBlank() },
                            form = form,
                            strength = strength.takeIf { it.isNotBlank() },
                            manufacturer = manufacturer.takeIf { it.isNotBlank() },
                            imagePath = null,
                            indications = indications.takeIf { it.isNotBlank() },
                            warnings = null,
                            quantity = quantity.toDoubleOrNull() ?: 1.0,
                            unit = defaultUnit,
                            purchaseDate = System.currentTimeMillis(),
                            expiryDate = datePickerState.selectedDateMillis
                                ?: (System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000),
                            batchNumber = batchNumber.takeIf { it.isNotBlank() },
                            storageLocation = storageLocation.takeIf { it.isNotBlank() },
                            needsRefrigeration = needsRefrigeration,
                            usageMode = usageMode,
                            userNotes = userNotes.takeIf { it.isNotBlank() },
                            categoryIds = selectedCategories
                        )
                        onSave()
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
