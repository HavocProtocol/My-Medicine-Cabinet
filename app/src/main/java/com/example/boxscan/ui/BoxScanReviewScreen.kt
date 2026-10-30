package com.example.boxscan.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.R
import com.example.boxscan.merge.MultiSideMerger
import com.example.boxscan.models.BoxScanField
import com.example.boxscan.models.BoxScanResult
import com.example.boxscan.models.CapturedSide
import com.example.boxscan.models.FieldCandidate
import com.example.boxscan.models.MedForm
import com.example.boxscan.models.MedicineDraft
import com.example.boxscan.models.NormalizedRect
import com.example.safety.ExpiryService
import com.example.safety.ExpiryStatus
import com.example.safety.SafetyStrings
import java.time.LocalDate

/**
 * Interactive review screen for multi-side medicine box OCR results.
 *
 * Enforces:
 * - Confidence indicators (Green >= 0.85, Amber 0.60..0.849, None < 0.60) + "تقديري / Estimated" tag
 * - Source side thumbnail + zoomed bounding-box highlight dialog
 * - Alternative candidate selection chips (especially when ambiguous / tied)
 * - Explicit human confirmation for expiry date and refrigeration suggestion
 * - Expired disposal safety banner ([SafetyStrings.EXPIRED_DISPOSAL]) when expiry is in the past
 * - Raw recognized text viewer per side ("عرض النص المستخرج")
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoxScanReviewScreen(
    scanResult: BoxScanResult,
    onConfirmDraft: (MedicineDraft) -> Unit,
    onBackToCapture: () -> Unit,
    onCancel: () -> Unit
) {
    val initialDraft = scanResult.draft

    // Editable state for each permitted field
    var tradeName by remember(scanResult) { mutableStateOf(initialDraft.tradeName.orEmpty()) }
    var scientificName by remember(scanResult) { mutableStateOf(initialDraft.scientificName.orEmpty()) }
    var strength by remember(scanResult) { mutableStateOf(initialDraft.strength.orEmpty()) }
    var selectedForm by remember(scanResult) { mutableStateOf(initialDraft.form) }
    var packQuantity by remember(scanResult) { mutableStateOf(initialDraft.packQuantity.orEmpty()) }
    var manufacturer by remember(scanResult) { mutableStateOf(initialDraft.manufacturer.orEmpty()) }
    var batchNumber by remember(scanResult) { mutableStateOf(initialDraft.batchNumber.orEmpty()) }
    var manufacturingDate by remember(scanResult) { mutableStateOf(initialDraft.manufacturingDate.orEmpty()) }
    var expiryDate by remember(scanResult) { mutableStateOf(initialDraft.expiryDate.orEmpty()) }
    var needsRefrigeration by remember(scanResult) { mutableStateOf(initialDraft.needsRefrigeration == true) }

    // Explicit human review confirmation flags
    var expiryConfirmed by remember(scanResult) { mutableStateOf(false) }
    var refrigerationConfirmed by remember(scanResult) { mutableStateOf(false) }

    // Track whether the user has detected refrigeration from OCR
    val hasRefrigerationCandidate = remember(scanResult) {
        scanResult.bestCandidates.containsKey(BoxScanField.NEEDS_REFRIGERATION) ||
            (scanResult.alternatives[BoxScanField.NEEDS_REFRIGERATION]?.isNotEmpty() == true)
    }

    // Re-evaluate whether the current expiryDate is in the past
    val parsedExpiryLocalDate = remember(expiryDate) {
        runCatching {
            if (expiryDate.isNotBlank()) LocalDate.parse(expiryDate.trim()) else null
        }.getOrNull()
    }
    val isCurrentExpiryPast = remember(parsedExpiryLocalDate) {
        if (parsedExpiryLocalDate != null) {
            ExpiryService.classify(LocalDate.now(), parsedExpiryLocalDate) == ExpiryStatus.EXPIRED
        } else {
            scanResult.isExpiryPast
        }
    }

    // Dialog states
    var showRawTextDialog by remember { mutableStateOf(false) }
    var zoomedCandidate by remember { mutableStateOf<Pair<CapturedSide, NormalizedRect?>?>(null) }

    val noTextExtracted = remember(scanResult) {
        scanResult.sideOutputs.all { it.fullText.isBlank() } && scanResult.bestCandidates.isEmpty()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.box_scan_review_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.box_scan_review_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackToCapture) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cancel)
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = { showRawTextDialog = true },
                        modifier = Modifier.testTag("show_recognized_text_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Article,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.box_scan_show_raw_text))
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 4.dp,
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (expiryDate.isNotBlank() && !expiryConfirmed) {
                        Text(
                            text = stringResource(R.string.box_scan_confirm_expiry_prompt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onBackToCapture,
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.box_scan_add_retake_side))
                        }
                        Button(
                            onClick = {
                                val finalDraft = MedicineDraft(
                                    tradeName = tradeName.trim().ifBlank { null },
                                    scientificName = scientificName.trim().ifBlank { null },
                                    strength = strength.trim().ifBlank { null },
                                    form = selectedForm,
                                    manufacturer = manufacturer.trim().ifBlank { null },
                                    batchNumber = batchNumber.trim().ifBlank { null },
                                    expiryDate = expiryDate.trim().ifBlank { null },
                                    manufacturingDate = manufacturingDate.trim().ifBlank { null },
                                    packQuantity = packQuantity.trim().ifBlank { null },
                                    needsRefrigeration = if (needsRefrigeration) true else null,
                                    expiryExplicitlyConfirmed = expiryConfirmed,
                                    refrigerationExplicitlyConfirmed = refrigerationConfirmed,
                                    barcode = null,
                                    indications = null,
                                    warnings = null,
                                    usageMode = null
                                )
                                onConfirmDraft(finalDraft)
                            },
                            enabled = expiryDate.isBlank() || expiryConfirmed,
                            modifier = Modifier
                                .weight(1.4f)
                                .height(50.dp)
                                .testTag("box_scan_continue_to_form_button"),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.box_scan_continue_to_add),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Expired Medicine Safety Banner (Mandatory SafetyStrings.EXPIRED_DISPOSAL)
            if (isCurrentExpiryPast) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("expired_disposal_warning_card")
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.expired_warning),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // 2. Date Order Conflict Banner (MFG >= EXP)
            if (scanResult.hasDateOrderConflict) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFFEF3C7)
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("date_order_conflict_card")
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = Color(0xFFB45309)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.box_scan_date_order_conflict),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF92400E),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // 3. No Text Extracted Warning
            if (noTextExtracted) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = stringResource(R.string.box_scan_no_text_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.box_scan_no_text_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 4. Field Review Cards
            // 4a. Trade Name
            ReviewFieldCard(
                field = BoxScanField.TRADE_NAME,
                value = tradeName,
                onValueChange = { tradeName = it },
                scanResult = scanResult,
                onSelectCandidate = { tradeName = it.value },
                onZoomSide = { side, rect -> zoomedCandidate = side to rect }
            )

            // 4b. Scientific Name (Active Ingredient)
            ReviewFieldCard(
                field = BoxScanField.SCIENTIFIC_NAME,
                value = scientificName,
                onValueChange = { scientificName = it },
                scanResult = scanResult,
                onSelectCandidate = { scientificName = it.value },
                onZoomSide = { side, rect -> zoomedCandidate = side to rect }
            )

            // 4c. Strength
            ReviewFieldCard(
                field = BoxScanField.STRENGTH,
                value = strength,
                onValueChange = { strength = it },
                scanResult = scanResult,
                onSelectCandidate = { strength = it.value },
                onZoomSide = { side, rect -> zoomedCandidate = side to rect }
            )

            // 4d. Dosage Form (Mapped strictly to supported MedForm enum)
            ReviewFormFieldCard(
                selectedForm = selectedForm,
                onSelectForm = { selectedForm = it },
                scanResult = scanResult,
                onZoomSide = { side, rect -> zoomedCandidate = side to rect }
            )

            // 4e. Pack Quantity
            ReviewFieldCard(
                field = BoxScanField.PACK_QUANTITY,
                value = packQuantity,
                onValueChange = { packQuantity = it },
                scanResult = scanResult,
                onSelectCandidate = { packQuantity = it.value },
                onZoomSide = { side, rect -> zoomedCandidate = side to rect }
            )

            // 4f. Expiry Date (With Mandatory Explicit Confirmation)
            ReviewExpiryFieldCard(
                expiryDate = expiryDate,
                onExpiryDateChange = {
                    expiryDate = it
                    expiryConfirmed = true
                },
                expiryConfirmed = expiryConfirmed,
                onToggleConfirmed = { expiryConfirmed = it },
                scanResult = scanResult,
                onSelectCandidate = {
                    expiryDate = it.normalizedValue
                    expiryConfirmed = true
                },
                onZoomSide = { side, rect -> zoomedCandidate = side to rect }
            )

            // 4g. Batch Number
            ReviewFieldCard(
                field = BoxScanField.BATCH_NUMBER,
                value = batchNumber,
                onValueChange = { batchNumber = it },
                scanResult = scanResult,
                onSelectCandidate = { batchNumber = it.value },
                onZoomSide = { side, rect -> zoomedCandidate = side to rect }
            )

            // 4h. Manufacturing Date
            ReviewFieldCard(
                field = BoxScanField.MANUFACTURING_DATE,
                value = manufacturingDate,
                onValueChange = { manufacturingDate = it },
                scanResult = scanResult,
                onSelectCandidate = { manufacturingDate = it.normalizedValue },
                onZoomSide = { side, rect -> zoomedCandidate = side to rect }
            )

            // 4i. Manufacturer
            ReviewFieldCard(
                field = BoxScanField.MANUFACTURER,
                value = manufacturer,
                onValueChange = { manufacturer = it },
                scanResult = scanResult,
                onSelectCandidate = { manufacturer = it.value },
                onZoomSide = { side, rect -> zoomedCandidate = side to rect }
            )

            // 4j. Refrigeration Storage Suggestion
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (needsRefrigeration) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.AcUnit,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = stringResource(BoxScanField.NEEDS_REFRIGERATION.labelRes),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                if (hasRefrigerationCandidate) {
                                    Text(
                                        text = stringResource(R.string.box_scan_refrigeration_detected),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                        Switch(
                            checked = needsRefrigeration,
                            onCheckedChange = {
                                needsRefrigeration = it
                                refrigerationConfirmed = true
                            },
                            modifier = Modifier.testTag("refrigeration_switch")
                        )
                    }

                    if (needsRefrigeration) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { refrigerationConfirmed = !refrigerationConfirmed }
                        ) {
                            Checkbox(
                                checked = refrigerationConfirmed,
                                onCheckedChange = { refrigerationConfirmed = it },
                                modifier = Modifier.testTag("refrigeration_confirm_checkbox")
                            )
                            Text(
                                text = stringResource(R.string.box_scan_confirm_refrigeration_checkbox),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }

    // Raw Recognized Text Dialog ("عرض النص المستخرج")
    if (showRawTextDialog) {
        AlertDialog(
            onDismissRequest = { showRawTextDialog = false },
            title = { Text(stringResource(R.string.box_scan_show_raw_text)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (scanResult.sideOutputs.isEmpty()) {
                        Text(stringResource(R.string.box_scan_no_raw_text))
                    } else {
                        scanResult.sideOutputs.forEachIndexed { idx, output ->
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        text = stringResource(
                                            R.string.box_scan_side_header,
                                            idx + 1,
                                            stringResource(output.sideType.labelRes)
                                        ),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = output.fullText.ifBlank {
                                            stringResource(R.string.box_scan_side_no_text)
                                        },
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRawTextDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Zoomed Side Image + Bounding Box Highlight Dialog
    val currentZoom = zoomedCandidate
    if (currentZoom != null) {
        val (side, rect) = currentZoom
        Dialog(onDismissRequest = { zoomedCandidate = null }) {
            Card(
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.box_scan_source_prefix, stringResource(side.sideType.labelRes)),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = { zoomedCandidate = null }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    val bmp = remember(side.imagePath) {
                        BitmapFactory.decodeFile(side.imagePath)
                    }
                    if (bmp != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Black)
                        ) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = stringResource(side.sideType.labelRes),
                                contentScale = ContentScale.FillBounds,
                                modifier = Modifier.fillMaxSize()
                            )
                            if (rect != null) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    val left = rect.left * size.width
                                    val top = rect.top * size.height
                                    val width = (rect.right - rect.left).coerceAtLeast(0.05f) * size.width
                                    val height = (rect.bottom - rect.top).coerceAtLeast(0.05f) * size.height
                                    drawRect(
                                        color = Color(0xFF22C55E).copy(alpha = 0.25f),
                                        topLeft = Offset(left, top),
                                        size = Size(width, height)
                                    )
                                    drawRect(
                                        color = Color(0xFF22C55E),
                                        topLeft = Offset(left, top),
                                        size = Size(width, height),
                                        style = Stroke(width = 3.dp.toPx())
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewFieldCard(
    field: BoxScanField,
    value: String,
    onValueChange: (String) -> Unit,
    scanResult: BoxScanResult,
    onSelectCandidate: (FieldCandidate) -> Unit,
    onZoomSide: (CapturedSide, NormalizedRect?) -> Unit
) {
    val bestCandidate = scanResult.bestCandidates[field]
    val alternatives = scanResult.alternatives[field].orEmpty()
    val isAmbiguous = field in scanResult.ambiguousFields
    val primaryCandidate = bestCandidate ?: alternatives.firstOrNull()
    val sourceSide = primaryCandidate?.sideIndex?.let { scanResult.capturedSides.getOrNull(it) }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("review_field_card_${field.name}")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header row: Field label + Confidence badge + Source side chip
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(field.labelRes),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                ConfidenceIndicatorBadge(
                    candidate = primaryCandidate,
                    hasValue = value.isNotBlank()
                )
            }

            if (isAmbiguous && alternatives.size > 1) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.box_scan_ambiguous_prompt),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB45309),
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text(stringResource(R.string.box_scan_manual_entry_placeholder)) },
                singleLine = true,
                trailingIcon = {
                    if (value.isNotEmpty()) {
                        IconButton(onClick = { onValueChange("") }) {
                            Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.box_scan_clear))
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("review_input_${field.name}")
            )

            // Source side + alternatives footer
            if (primaryCandidate != null || alternatives.size > 1) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (primaryCandidate != null && sourceSide != null) {
                        AssistChip(
                            onClick = { onZoomSide(sourceSide, primaryCandidate.boundingBox) },
                            label = {
                                val sideLabel = stringResource(primaryCandidate.sideType.labelRes)
                                val sourceText = if (primaryCandidate.corroboratingSideCount > 1) {
                                    stringResource(R.string.box_scan_source_prefix, sideLabel) +
                                        stringResource(R.string.box_scan_corroborated_suffix, primaryCandidate.corroboratingSideCount)
                                } else {
                                    stringResource(R.string.box_scan_source_prefix, sideLabel)
                                }
                                Text(
                                    text = sourceText,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.ZoomIn,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }
                }

                if (alternatives.size > 1 || (isAmbiguous && alternatives.isNotEmpty())) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.box_scan_suggested_values),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(alternatives.size) { idx ->
                            val cand = alternatives[idx]
                            val sideLabel = stringResource(cand.sideType.labelRes)
                            FilterChip(
                                selected = value == cand.value || value == cand.normalizedValue,
                                onClick = { onSelectCandidate(cand) },
                                label = {
                                    Text("${cand.value} ($sideLabel)")
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewFormFieldCard(
    selectedForm: MedForm?,
    onSelectForm: (MedForm?) -> Unit,
    scanResult: BoxScanResult,
    onZoomSide: (CapturedSide, NormalizedRect?) -> Unit
) {
    val primaryCandidate = scanResult.bestCandidates[BoxScanField.FORM]
        ?: scanResult.alternatives[BoxScanField.FORM]?.firstOrNull()
    val sourceSide = primaryCandidate?.sideIndex?.let { scanResult.capturedSides.getOrNull(it) }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("review_field_card_FORM")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(BoxScanField.FORM.labelRes),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                ConfidenceIndicatorBadge(
                    candidate = primaryCandidate,
                    hasValue = selectedForm != null
                )
            }

            if (primaryCandidate != null && primaryCandidate.medForm == null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.box_scan_unsupported_form_alert, primaryCandidate.value),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB45309)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val forms = MedForm.entries
                items(forms.size) { index ->
                    val form = forms[index]
                    FilterChip(
                        selected = selectedForm == form,
                        onClick = {
                            onSelectForm(if (selectedForm == form) null else form)
                        },
                        label = { Text(stringResource(form.labelRes)) },
                        modifier = Modifier.testTag("review_form_chip_${form.name}")
                    )
                }
            }

            if (primaryCandidate != null && sourceSide != null) {
                Spacer(modifier = Modifier.height(6.dp))
                AssistChip(
                    onClick = { onZoomSide(sourceSide, primaryCandidate.boundingBox) },
                    label = {
                        Text(
                            text = stringResource(R.string.box_scan_source_prefix, stringResource(primaryCandidate.sideType.labelRes)),
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.ZoomIn,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun ReviewExpiryFieldCard(
    expiryDate: String,
    onExpiryDateChange: (String) -> Unit,
    expiryConfirmed: Boolean,
    onToggleConfirmed: (Boolean) -> Unit,
    scanResult: BoxScanResult,
    onSelectCandidate: (FieldCandidate) -> Unit,
    onZoomSide: (CapturedSide, NormalizedRect?) -> Unit
) {
    val bestCandidate = scanResult.bestCandidates[BoxScanField.EXPIRY_DATE]
    val alternatives = scanResult.alternatives[BoxScanField.EXPIRY_DATE].orEmpty()
    val primaryCandidate = bestCandidate ?: alternatives.firstOrNull()
    val sourceSide = primaryCandidate?.sideIndex?.let { scanResult.capturedSides.getOrNull(it) }

    Card(
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.5.dp,
            color = if (expiryConfirmed) Color(0xFF16A34A) else Color(0xFFD97706)
        ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("review_field_card_EXPIRY_DATE")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(
                        R.string.box_scan_expiry_requires_confirmation,
                        stringResource(BoxScanField.EXPIRY_DATE.labelRes)
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                ConfidenceIndicatorBadge(
                    candidate = primaryCandidate,
                    hasValue = expiryDate.isNotBlank()
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = expiryDate,
                onValueChange = onExpiryDateChange,
                placeholder = { Text(stringResource(R.string.box_scan_expiry_placeholder)) },
                singleLine = true,
                trailingIcon = {
                    if (expiryDate.isNotEmpty()) {
                        IconButton(onClick = { onExpiryDateChange("") }) {
                            Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.box_scan_clear))
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("review_input_EXPIRY_DATE")
            )

            // Alternatives if multiple expiry candidates exist
            if (alternatives.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(alternatives.size) { idx ->
                        val cand = alternatives[idx]
                        FilterChip(
                            selected = expiryDate == cand.normalizedValue,
                            onClick = { onSelectCandidate(cand) },
                            label = {
                                Text("${cand.normalizedValue} (${cand.rawText})")
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Mandatory Explicit Confirmation Checkbox
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (expiryConfirmed) Color(0xFFDCFCE7) else Color(0xFFFEF3C7)
                    )
                    .clickable { onToggleConfirmed(!expiryConfirmed) }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("expiry_explicit_confirm_row"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = expiryConfirmed,
                    onCheckedChange = onToggleConfirmed,
                    modifier = Modifier.testTag("expiry_explicit_confirm_checkbox")
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.box_scan_confirm_expiry_checkbox),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (expiryConfirmed) Color(0xFF166534) else Color(0xFF92400E)
                )
            }

            if (primaryCandidate != null && sourceSide != null) {
                Spacer(modifier = Modifier.height(6.dp))
                AssistChip(
                    onClick = { onZoomSide(sourceSide, primaryCandidate.boundingBox) },
                    label = {
                        Text(
                            text = stringResource(R.string.box_scan_source_prefix, stringResource(primaryCandidate.sideType.labelRes)) +
                                " (${primaryCandidate.rawText})",
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.ZoomIn,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun ConfidenceIndicatorBadge(
    candidate: FieldCandidate?,
    hasValue: Boolean
) {
    val conf = candidate?.confidence ?: 0.0
    val isEstimated = candidate?.isEstimatedConfidence == true

    when {
        !hasValue || candidate == null || conf < MultiSideMerger.DRAFT_THRESHOLD -> {
            // < 0.60: No dot, "لم يتم التأكد — أدخل يدوياً"
            Text(
                text = stringResource(R.string.box_scan_conf_unconfirmed),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        conf >= MultiSideMerger.HIGH_CONFIDENCE_THRESHOLD -> {
            // >= 0.85: Green dot
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF16A34A))
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = if (isEstimated) {
                        stringResource(R.string.box_scan_conf_high_estimated)
                    } else {
                        stringResource(R.string.box_scan_conf_high)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF16A34A),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        else -> {
            // 0.60 - 0.849: Amber dot
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFD97706))
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = if (isEstimated) {
                        stringResource(R.string.box_scan_conf_review_estimated)
                    } else {
                        stringResource(R.string.box_scan_conf_review)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFD97706),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
