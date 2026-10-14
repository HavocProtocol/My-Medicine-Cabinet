package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.ui.viewmodels.AppViewModel
import com.example.ui.utils.getLocalizedName

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddMedicineScreen(
    initialBarcode: String? = null,
    initialTradeName: String? = null,
    initialManufacturer: String? = null,
    onSave: () -> Unit,
    onBack: () -> Unit = {},
    viewModel: AppViewModel = viewModel()
) {
    var tradeName by remember { mutableStateOf(initialTradeName ?: "") }
    var scientificName by remember { mutableStateOf("") }
    var strength by remember { mutableStateOf("") }
    val forms = listOf(
        stringResource(R.string.form_tablets),
        stringResource(R.string.form_capsules),
        stringResource(R.string.form_syrup),
        stringResource(R.string.form_cream),
        stringResource(R.string.form_spray),
        stringResource(R.string.form_drops),
        stringResource(R.string.form_injections),
        stringResource(R.string.form_other)
    )
    val defaultForm = forms.firstOrNull() ?: ""
    val defaultUnit = stringResource(R.string.unit_package)
    var form by remember { mutableStateOf("") }
    LaunchedEffect(defaultForm) {
        if (form.isEmpty()) {
            form = defaultForm
        }
    }
    var quantity by remember { mutableStateOf("1") }
    var manufacturer by remember { mutableStateOf(initialManufacturer ?: "") }
    var barcode by remember { mutableStateOf(initialBarcode ?: "") }
    var batchNumber by remember { mutableStateOf("") }
    var storageLocation by remember { mutableStateOf("") }
    var needsRefrigeration by remember { mutableStateOf(false) }
    var usageMode by remember { mutableStateOf("") }
    var indications by remember { mutableStateOf("") }
    var userNotes by remember { mutableStateOf("") }
    val allCategories by viewModel.allCategories.collectAsState()
    var selectedCategories by remember { mutableStateOf(setOf<Int>()) }
    var formExpanded by remember { mutableStateOf(false) }
    
    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000)
    val dateFormatter = remember { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()) }
    val selectedDateString = datePickerState.selectedDateMillis?.let { dateFormatter.format(java.util.Date(it)) } ?: ""

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
            OutlinedTextField(
                shape = RoundedCornerShape(12.dp),
                colors = textFieldColors,
                value = tradeName,
                onValueChange = { tradeName = it },
                label = { Text(stringResource(R.string.trade_name)) },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                shape = RoundedCornerShape(12.dp),
                colors = textFieldColors,
                value = scientificName,
                onValueChange = { scientificName = it },
                label = { Text(stringResource(R.string.scientific_name)) },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                shape = RoundedCornerShape(12.dp),
                colors = textFieldColors,
                value = strength,
                onValueChange = { strength = it },
                label = { Text(stringResource(R.string.strength)) },
                modifier = Modifier.fillMaxWidth()
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
                    modifier = Modifier.menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
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
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                shape = RoundedCornerShape(12.dp),
                colors = textFieldColors,
                value = manufacturer,
                onValueChange = { manufacturer = it },
                label = { Text(stringResource(R.string.manufacturer)) },
                modifier = Modifier.fillMaxWidth()
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
                modifier = Modifier.fillMaxWidth()
            )

            if (showDatePicker) {
                DatePickerDialog(
                    onDismissRequest = { showDatePicker = false },
                    confirmButton = {
                        TextButton(onClick = { showDatePicker = false }) {
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
                Checkbox(checked = needsRefrigeration, onCheckedChange = { needsRefrigeration = it })
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
                onClick = {
                    if (tradeName.isNotBlank()) {
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
                            expiryDate = datePickerState.selectedDateMillis ?: (System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000),
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
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(stringResource(R.string.save_medicine_button), color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}
