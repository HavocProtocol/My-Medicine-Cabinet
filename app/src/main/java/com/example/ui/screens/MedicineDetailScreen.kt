package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.data.InventoryItem
import com.example.ui.utils.getLocalizedName
import com.example.ui.viewmodels.AppViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicineDetailScreen(
    medicineId: Int,
    onBack: () -> Unit,
    onNavigateToMedInfo: (String, String) -> Unit,
    onScheduleMedicine: (Int) -> Unit = {},
    viewModel: AppViewModel = viewModel()
) {
    val context = LocalContext.current
    val allMedicines by viewModel.allMedicines.collectAsState()
    val availableItems by viewModel.availableItems.collectAsState()
    val expiredItems by viewModel.expiredItems.collectAsState()
    val allCategories by viewModel.allCategories.collectAsState()
    val medCategoryIds by viewModel.getCategoriesForMedicine(medicineId).collectAsState(initial = emptyList())
    val medInventoryList by viewModel.getInventoryForMedicine(medicineId).collectAsState(initial = emptyList())
    val allInventory by viewModel.allInventoryItems.collectAsState()

    val medicine = allMedicines.find { it.id == medicineId }
    val inventory = medInventoryList.firstOrNull()
        ?: allInventory.find { it.medicineId == medicineId }
        ?: (availableItems + expiredItems).find { it.medicineId == medicineId }

    val isExpired = inventory?.let { it.expiryDate < System.currentTimeMillis() } ?: false
    val dateFormatter = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showLogUsageDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }

    fun shareMedicine() {
        if (medicine == null) return
        val shareText = buildString {
            appendLine("${medicine.tradeName} ${medicine.scientificName?.let { "($it)" } ?: ""}")
            if (!medicine.strength.isNullOrBlank()) {
                appendLine("${context.getString(R.string.strength)}: ${medicine.strength}")
            }
            appendLine("${context.getString(R.string.form_label)}: ${getLocalizedForm(medicine.form, context)}")
            val currentUnit = getLocalizedUnit(inventory?.unit, context)
            val qtyText = if (inventory != null) "${inventory.quantity} $currentUnit" else context.getString(R.string.not_recorded)
            appendLine("${context.getString(R.string.remaining_quantity)}: $qtyText")
            if (inventory != null) {
                appendLine("${context.getString(R.string.expiry_date)}: ${dateFormatter.format(Date(inventory.expiryDate))}")
                if (!inventory.storageLocation.isNullOrBlank()) {
                    appendLine("${context.getString(R.string.storage_location)}: ${getLocalizedStorageLocation(inventory.storageLocation, context)}")
                }
                appendLine("${context.getString(R.string.needs_refrigeration)}: ${if (inventory.needsRefrigeration) context.getString(R.string.yes) else context.getString(R.string.no)}")
            }
            if (!medicine.indications.isNullOrBlank()) {
                appendLine("${context.getString(R.string.indications)}: ${medicine.indications}")
            }
        }
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, shareText)
            putExtra(Intent.EXTRA_SUBJECT, medicine.tradeName)
            type = "text/plain"
        }
        val chooserIntent = Intent.createChooser(sendIntent, context.getString(R.string.share)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(chooserIntent)
        } catch (_: Exception) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText("Medicine Details", shareText)
            clipboard?.setPrimaryClip(clip)
            Toast.makeText(
                context,
                "${medicine.tradeName}: ${context.getString(R.string.copied_to_clipboard)}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface
                ),
                title = {
                    Text(
                        text = medicine?.tradeName ?: stringResource(R.string.medicine_detail_title),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    if (medicine != null) {
                        IconButton(onClick = { showEditDialog = true }) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = stringResource(R.string.edit)
                            )
                        }
                        IconButton(onClick = { shareMedicine() }) {
                            Icon(
                                Icons.Default.Share,
                                contentDescription = stringResource(R.string.share)
                            )
                        }
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.delete),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (medicine == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.medicine_not_found),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header Info Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = medicine.tradeName,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                        medicine.scientificName?.let {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                                )
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Form Badge
                            SuggestionChip(
                                onClick = {},
                                label = {
                                    Text(
                                        text = getLocalizedForm(medicine.form, context),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                                )
                            )

                            // Strength
                            medicine.strength?.let { strength ->
                                SuggestionChip(
                                    onClick = {},
                                    label = {
                                        Text(
                                            text = strength,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    },
                                    colors = SuggestionChipDefaults.suggestionChipColors(
                                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                                    )
                                )
                            }
                        }

                        // Categories Chips
                        val assignedCats = remember(allCategories, medCategoryIds) {
                            allCategories.filter { it.id in medCategoryIds }
                        }
                        if (assignedCats.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                assignedCats.forEach { cat ->
                                    AssistChip(
                                        onClick = {},
                                        label = { Text(cat.getLocalizedName(), fontSize = 11.sp) }
                                    )
                                }
                            }
                        }
                    }
                }

                // Expired Warning Banner
                if (isExpired) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.expired_warning),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                // Inventory Details
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.medicine_detail_title),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        if (inventory != null) {
                            val unitStr = getLocalizedUnit(inventory.unit, context)
                            Text(
                                text = "${stringResource(R.string.remaining_quantity)}: ${inventory.quantity} $unitStr",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "${stringResource(R.string.expiry_date)}: ${dateFormatter.format(Date(inventory.expiryDate))}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            inventory.storageLocation?.let { loc ->
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "${stringResource(R.string.storage_location)}: ${getLocalizedStorageLocation(loc, context)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "${stringResource(R.string.needs_refrigeration)}: ${if (inventory.needsRefrigeration) stringResource(R.string.yes) else stringResource(R.string.no)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                text = "${stringResource(R.string.remaining_quantity)}: ${stringResource(R.string.not_recorded)}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.edit_medicine_title),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { showEditDialog = true }
                            )
                        }
                    }
                }

                // Indications & Warnings
                medicine.indications?.let { ind ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                stringResource(R.string.indications),
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = ind,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Primary Actions Row (Log Used Quantity & Medicine Info)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        shape = RoundedCornerShape(14.dp),
                        onClick = { showLogUsageDialog = true },
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.log_used_quantity),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Button(
                        shape = RoundedCornerShape(14.dp),
                        onClick = {
                            onNavigateToMedInfo(medicine.tradeName, medicine.scientificName ?: "")
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.med_info_title),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Schedule Medication Reminder Button
                OutlinedButton(
                    shape = RoundedCornerShape(14.dp),
                    onClick = { onScheduleMedicine(medicine.id) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.AlarmAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.sched_title),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Secondary Action Buttons (Edit, Share, Delete)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        shape = RoundedCornerShape(12.dp),
                        onClick = { showEditDialog = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.edit), fontSize = 12.sp)
                    }

                    OutlinedButton(
                        shape = RoundedCornerShape(12.dp),
                        onClick = { shareMedicine() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.share), fontSize = 12.sp)
                    }

                    OutlinedButton(
                        shape = RoundedCornerShape(12.dp),
                        onClick = { showDeleteDialog = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.delete), fontSize = 12.sp)
                    }
                }
            }
        }
    }

    // 1. Delete Confirmation Dialog
    if (showDeleteDialog && medicine != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text(stringResource(R.string.delete_confirm_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.delete_confirm_msg)) },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deleteMedicine(medicineId) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.medicine_deleted_toast),
                                Toast.LENGTH_SHORT
                            ).show()
                            onBack()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.onError)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // 2. Log Used Quantity Dialog
    if (showLogUsageDialog && medicine != null) {
        var usedInput by remember { mutableStateOf("1") }
        var inputError by remember { mutableStateOf<String?>(null) }
        val remainingQty = inventory?.quantity ?: 1.0
        val remainingUnit = getLocalizedUnit(inventory?.unit, context)

        AlertDialog(
            onDismissRequest = { showLogUsageDialog = false },
            icon = {
                Icon(
                    Icons.Default.Medication,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = { Text(stringResource(R.string.log_used_quantity), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "${stringResource(R.string.remaining_quantity)}: $remainingQty $remainingUnit",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.log_usage_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1.0, 2.0, 0.5).forEach { chipVal ->
                            val chipLabel = if (chipVal % 1.0 == 0.0) chipVal.toInt().toString() else chipVal.toString()
                            FilterChip(
                                selected = usedInput == chipLabel,
                                onClick = {
                                    usedInput = chipLabel
                                    inputError = null
                                },
                                label = { Text("-$chipLabel") }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = usedInput,
                        onValueChange = {
                            usedInput = it
                            inputError = null
                        },
                        label = { Text(stringResource(R.string.quantity_used_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = inputError != null,
                        supportingText = inputError?.let { { Text(it) } },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    val entered = usedInput.toDoubleOrNull() ?: 0.0
                    val projectedRemaining = (remainingQty - entered).coerceAtLeast(0.0)
                    Text(
                        text = "${stringResource(R.string.remaining_quantity)}: $projectedRemaining $remainingUnit",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val amount = usedInput.toDoubleOrNull()
                        if (amount == null || amount <= 0.0) {
                            inputError = context.getString(R.string.amount_must_be_positive)
                        } else {
                            viewModel.logUsedQuantity(
                                medicineId = medicine.id,
                                inventoryItemId = inventory?.id,
                                currentQuantity = remainingQty,
                                amountUsed = amount
                            ) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.usage_logged_toast),
                                    Toast.LENGTH_SHORT
                                ).show()
                                showLogUsageDialog = false
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogUsageDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // 3. Edit Medicine Dialog
    if (showEditDialog && medicine != null) {
        var editTradeName by remember { mutableStateOf(medicine.tradeName) }
        var editScientificName by remember { mutableStateOf(medicine.scientificName ?: "") }
        var editStrength by remember { mutableStateOf(medicine.strength ?: "") }
        var editForm by remember { mutableStateOf(medicine.form) }
        var editQuantity by remember { mutableStateOf(inventory?.quantity?.toString() ?: "1") }
        var editStorageLocation by remember { mutableStateOf(inventory?.storageLocation ?: "") }
        var editNeedsRefrig by remember { mutableStateOf(inventory?.needsRefrigeration ?: false) }
        var editIndications by remember { mutableStateOf(medicine.indications ?: "") }
        var editExpiryDate by remember {
            mutableLongStateOf(inventory?.expiryDate ?: (System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000))
        }

        var formMenuExpanded by remember { mutableStateOf(false) }
        val formOptions = remember {
            listOf(
                context.getString(R.string.form_tablets),
                context.getString(R.string.form_capsules),
                context.getString(R.string.form_syrup),
                context.getString(R.string.form_cream),
                context.getString(R.string.form_spray),
                context.getString(R.string.form_drops),
                context.getString(R.string.form_injections),
                context.getString(R.string.form_other)
            )
        }

        var showDatePicker by remember { mutableStateOf(false) }
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = editExpiryDate)

        if (showDatePicker) {
            DatePickerDialog(
                onDismissRequest = { showDatePicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        datePickerState.selectedDateMillis?.let { editExpiryDate = it }
                        showDatePicker = false
                    }) {
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

        Dialog(
            onDismissRequest = { showEditDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .fillMaxHeight(0.85f),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp)
                ) {
                    Text(
                        text = stringResource(R.string.edit_medicine_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = editTradeName,
                            onValueChange = { editTradeName = it },
                            label = { Text(stringResource(R.string.trade_name)) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )

                        OutlinedTextField(
                            value = editScientificName,
                            onValueChange = { editScientificName = it },
                            label = { Text(stringResource(R.string.scientific_name)) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )

                        OutlinedTextField(
                            value = editStrength,
                            onValueChange = { editStrength = it },
                            label = { Text(stringResource(R.string.strength)) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )

                        // Form Selector
                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = getLocalizedForm(editForm, context),
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.form_label)) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { formMenuExpanded = true },
                                trailingIcon = {
                                    IconButton(onClick = { formMenuExpanded = true }) {
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                    }
                                },
                                shape = RoundedCornerShape(12.dp)
                            )
                            DropdownMenu(
                                expanded = formMenuExpanded,
                                onDismissRequest = { formMenuExpanded = false }
                            ) {
                                formOptions.forEach { formOpt ->
                                    DropdownMenuItem(
                                        text = { Text(formOpt) },
                                        onClick = {
                                            editForm = formOpt
                                            formMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        // Quantity
                        OutlinedTextField(
                            value = editQuantity,
                            onValueChange = { editQuantity = it },
                            label = { Text(stringResource(R.string.remaining_quantity)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )

                        // Expiry Date Clickable Field
                        OutlinedTextField(
                            value = dateFormatter.format(Date(editExpiryDate)),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.expiry_date)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showDatePicker = true },
                            trailingIcon = {
                                IconButton(onClick = { showDatePicker = true }) {
                                    Icon(Icons.Default.CalendarToday, contentDescription = null)
                                }
                            },
                            shape = RoundedCornerShape(12.dp)
                        )

                        // Storage Location
                        OutlinedTextField(
                            value = editStorageLocation,
                            onValueChange = { editStorageLocation = it },
                            label = { Text(stringResource(R.string.storage_location)) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )

                        // Refrigeration Toggle
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.needs_refrigeration),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Switch(
                                checked = editNeedsRefrig,
                                onCheckedChange = { editNeedsRefrig = it }
                            )
                        }

                        // Indications
                        OutlinedTextField(
                            value = editIndications,
                            onValueChange = { editIndications = it },
                            label = { Text(stringResource(R.string.indications)) },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 3,
                            shape = RoundedCornerShape(12.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { showEditDialog = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (editTradeName.isNotBlank()) {
                                    val updatedMed = medicine.copy(
                                        tradeName = editTradeName.trim(),
                                        scientificName = editScientificName.trim().takeIf { it.isNotBlank() },
                                        strength = editStrength.trim().takeIf { it.isNotBlank() },
                                        form = editForm,
                                        indications = editIndications.trim().takeIf { it.isNotBlank() }
                                    )
                                    val parsedQty = editQuantity.toDoubleOrNull() ?: inventory?.quantity ?: 1.0
                                    val updatedInv = inventory?.copy(
                                        quantity = parsedQty,
                                        expiryDate = editExpiryDate,
                                        storageLocation = editStorageLocation.trim().takeIf { it.isNotBlank() },
                                        needsRefrigeration = editNeedsRefrig
                                    ) ?: InventoryItem(
                                        medicineId = medicine.id,
                                        familyMemberId = null,
                                        quantity = parsedQty,
                                        unit = context.getString(R.string.unit_package),
                                        purchaseDate = System.currentTimeMillis(),
                                        expiryDate = editExpiryDate,
                                        batchNumber = null,
                                        storageLocation = editStorageLocation.trim().takeIf { it.isNotBlank() },
                                        needsRefrigeration = editNeedsRefrig,
                                        usageMode = null,
                                        userNotes = null
                                    )
                                    viewModel.updateMedicineWithInventory(updatedMed, updatedInv) {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.medicine_updated_toast),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        showEditDialog = false
                                    }
                                }
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(stringResource(R.string.save))
                        }
                    }
                }
            }
        }
    }
}

fun getLocalizedUnit(unit: String?, context: Context): String {
    if (unit.isNullOrBlank()) return context.getString(R.string.unit_package)
    val u = unit.lowercase().trim()
    return when {
        u.contains("عبو") || u.contains("علب") || u.contains("pack") || u.contains("boît") || u.contains("boit") || u.contains("box") -> context.getString(R.string.unit_package)
        u.contains("قرص") || u.contains("أقراص") || u.contains("tablet") || u.contains("comprim") -> context.getString(R.string.form_tablets)
        u.contains("كبسول") || u.contains("capsul") || u.contains("gélul") -> context.getString(R.string.form_capsules)
        u.contains("قطعة") || u.contains("piece") || u.contains("pièce") || u.contains("stk") || u.contains("stück") -> context.getString(R.string.unit_piece)
        u.contains("شريط") || u.contains("strip") || u.contains("plaquette") || u.contains("blister") -> context.getString(R.string.unit_strip)
        u.contains("مل") || u.contains("ml") -> "ml"
        u.contains("مغ") || u.contains("mg") -> "mg"
        else -> unit
    }
}

fun getLocalizedForm(form: String, context: Context): String {
    val f = form.lowercase().trim()
    return when {
        f.contains("tablet") || f.contains("comprim") || f.contains("tablett") || f.contains("قرص") || f.contains("أقراص") || f.contains("حبوب") -> context.getString(R.string.form_tablets)
        f.contains("capsul") || f.contains("gélul") || f.contains("gelul") || f.contains("kapsel") || f.contains("كبسول") -> context.getString(R.string.form_capsules)
        f.contains("syrup") || f.contains("sirop") || f.contains("sirup") || f.contains("saft") || f.contains("شراب") || f.contains("معلق") -> context.getString(R.string.form_syrup)
        f.contains("cream") || f.contains("crème") || f.contains("creme") || f.contains("pommade") || f.contains("salbe") || f.contains("مرهم") || f.contains("كريم") -> context.getString(R.string.form_cream)
        f.contains("spray") || f.contains("بخاخ") || f.contains("inhal") || f.contains("aerosol") -> context.getString(R.string.form_spray)
        f.contains("drop") || f.contains("goutte") || f.contains("tropf") || f.contains("قطر") -> context.getString(R.string.form_drops)
        f.contains("inject") || f.contains("injekt") || f.contains("حقن") || f.contains("seringue") -> context.getString(R.string.form_injections)
        f.contains("other") || f.contains("autre") || f.contains("sonstig") || f.contains("غير") -> context.getString(R.string.form_other)
        else -> form
    }
}

fun getLocalizedStorageLocation(location: String?, context: Context): String {
    if (location.isNullOrBlank()) return ""
    val l = location.lowercase().trim()
    return when {
        l.contains("ثلاج") || l.contains("براد") || l.contains("refrig") || l.contains("fridge") || l.contains("kühlschrank") -> context.getString(R.string.loc_refrigerator)
        l.contains("حمام") || l.contains("bath") || l.contains("salle de bain") || l.contains("badezimmer") -> context.getString(R.string.loc_bathroom)
        l.contains("غرفة") || l.contains("نوم") || l.contains("bedroom") || l.contains("chambre") || l.contains("schlafzimmer") -> context.getString(R.string.loc_bedroom)
        l.contains("مطبخ") || l.contains("kitchen") || l.contains("cuisine") || l.contains("küche") -> context.getString(R.string.loc_kitchen)
        l.contains("درج") || l.contains("drawer") || l.contains("tiroir") || l.contains("schublade") -> context.getString(R.string.loc_drawer)
        l.contains("إسعاف") || l.contains("صيدلية") || l.contains("first aid") || l.contains("secours") -> context.getString(R.string.loc_first_aid)
        else -> location
    }
}
