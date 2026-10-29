package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.outlined.*
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.safety.ExpiryService
import com.example.safety.MedicationRefillEvaluator
import com.example.ui.viewmodels.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefillNeededScreen(
    onBack: () -> Unit,
    onMedicineClick: (Int) -> Unit,
    viewModel: AppViewModel = viewModel()
) {
    val refillEval by viewModel.refillEvaluation.collectAsState()
    val entries = refillEval.refillNeeded
    var refillTarget by remember { mutableStateOf<MedicationRefillEvaluator.DepletedMedicineEntry?>(null) }

    LaunchedEffect(Unit) {
        viewModel.refreshExpirationStatuses()
    }

    Scaffold(
        modifier = Modifier.testTag("refill_needed_screen"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.refill_needed_title),
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Text(
                            text = stringResource(R.string.refill_needed_subtitle),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("refill_needed_back_btn")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Autorenew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        modifier = Modifier.size(52.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.refill_empty_title),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.refill_empty_desc),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(entries, key = { it.medicine.id }) { entry ->
                    DepletedMedicineCard(
                        entry = entry,
                        isRefillPage = true,
                        onOpenDetails = { onMedicineClick(entry.medicine.id) },
                        onQuickRefill = { refillTarget = entry }
                    )
                }
            }
        }
    }

    refillTarget?.let { target ->
        QuickRefillDialog(
            entry = target,
            onDismiss = { refillTarget = null },
            onConfirmRefill = { newQuantity ->
                val item = target.primaryInventoryItem
                val expMillis = item?.expiryDate ?: (System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000)
                viewModel.insertOrUpdateInventoryItem(
                    medicineId = target.medicine.id,
                    quantity = newQuantity,
                    unit = item?.unit ?: target.medicine.form,
                    expiryDate = expMillis,
                    storageLocation = item?.storageLocation,
                    needsRefrigeration = item?.needsRefrigeration ?: false,
                    onComplete = { refillTarget = null }
                )
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OneTimeMedicationsScreen(
    onBack: () -> Unit,
    onMedicineClick: (Int) -> Unit,
    viewModel: AppViewModel = viewModel()
) {
    val refillEval by viewModel.refillEvaluation.collectAsState()
    val entries = refillEval.oneTimeCompleted
    var restockTarget by remember { mutableStateOf<MedicationRefillEvaluator.DepletedMedicineEntry?>(null) }

    LaunchedEffect(Unit) {
        viewModel.refreshExpirationStatuses()
    }

    Scaffold(
        modifier = Modifier.testTag("one_time_medications_screen"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.one_time_meds_title),
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Text(
                            text = stringResource(R.string.one_time_meds_subtitle),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("one_time_medications_back_btn")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.CheckCircleOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f),
                        modifier = Modifier.size(52.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.one_time_empty_title),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.one_time_empty_desc),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(entries, key = { it.medicine.id }) { entry ->
                    DepletedMedicineCard(
                        entry = entry,
                        isRefillPage = false,
                        onOpenDetails = { onMedicineClick(entry.medicine.id) },
                        onQuickRefill = { restockTarget = entry }
                    )
                }
            }
        }
    }

    restockTarget?.let { target ->
        QuickRefillDialog(
            entry = target,
            onDismiss = { restockTarget = null },
            onConfirmRefill = { newQuantity ->
                val item = target.primaryInventoryItem
                val expMillis = item?.expiryDate ?: (System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000)
                viewModel.insertOrUpdateInventoryItem(
                    medicineId = target.medicine.id,
                    quantity = newQuantity,
                    unit = item?.unit ?: target.medicine.form,
                    expiryDate = expMillis,
                    storageLocation = item?.storageLocation,
                    needsRefrigeration = item?.needsRefrigeration ?: false,
                    onComplete = { restockTarget = null }
                )
            }
        )
    }
}

@Composable
private fun DepletedMedicineCard(
    entry: MedicationRefillEvaluator.DepletedMedicineEntry,
    isRefillPage: Boolean,
    onOpenDetails: () -> Unit,
    onQuickRefill: () -> Unit
) {
    val context = LocalContext.current
    val medicine = entry.medicine
    val item = entry.primaryInventoryItem
    val expIso = item?.expiryDate?.let { ExpiryService.millisToLocalDate(it).toString() } ?: "—"
    val rawUnit = item?.unit?.ifBlank { medicine.form } ?: medicine.form
    val unitLabel = getLocalizedUnit(rawUnit, context)
    val formLabel = getLocalizedForm(medicine.form, context)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(if (isRefillPage) "refill_needed_card_${medicine.id}" else "one_time_card_${medicine.id}")
            .clickable { onOpenDetails() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            1.dp,
            if (isRefillPage) Color(0xFFF59E0B).copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = medicine.tradeName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!medicine.scientificName.isNullOrBlank()) {
                        Text(
                            text = medicine.scientificName,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isRefillPage) Color(0xFFFEF3C7) else MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = if (isRefillPage) stringResource(R.string.refill_needed_title) else stringResource(R.string.badge_one_time),
                        color = if (isRefillPage) Color(0xFF92400E) else MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            val detailsText = buildList {
                add(stringResource(R.string.form_label_value, formLabel))
                if (!medicine.strength.isNullOrBlank()) add(stringResource(R.string.strength_label_value, medicine.strength))
                add(stringResource(R.string.remaining_label_value, "0 $unitLabel"))
                if (!medicine.prescribedDosage.isNullOrBlank()) add(stringResource(R.string.dosage_label_value, medicine.prescribedDosage))
                if (!medicine.administrationRoute.isNullOrBlank()) add(stringResource(R.string.route_label_value, getLocalizedRoute(medicine.administrationRoute, context)))
                add(stringResource(R.string.expiry_label_value, expIso))
            }.joinToString(" • ")

            Text(
                text = detailsText,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onOpenDetails,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("open_details_btn_${medicine.id}"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(stringResource(R.string.med_details_btn), fontSize = 12.sp)
                }
                Button(
                    onClick = onQuickRefill,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("update_inventory_btn_${medicine.id}"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.AddCircleOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isRefillPage) stringResource(R.string.refill_stock_btn) else stringResource(R.string.update_stock_btn), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun QuickRefillDialog(
    entry: MedicationRefillEvaluator.DepletedMedicineEntry,
    onDismiss: () -> Unit,
    onConfirmRefill: (Double) -> Unit
) {
    var qtyText by remember { mutableStateOf("30") }
    val parsedQty = qtyText.toDoubleOrNull()
    val isValid = parsedQty != null && parsedQty > 0.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.update_inventory_title, entry.medicine.tradeName), fontWeight = FontWeight.Bold, fontSize = 17.sp)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.update_inventory_desc),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = qtyText,
                    onValueChange = { qtyText = it },
                    label = { Text(stringResource(R.string.new_available_qty_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = !isValid,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("quick_refill_qty_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (parsedQty != null && parsedQty > 0.0) onConfirmRefill(parsedQty) },
                enabled = isValid,
                modifier = Modifier.testTag("quick_refill_confirm_btn")
            ) {
                Text(stringResource(R.string.save_quantity_btn))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
