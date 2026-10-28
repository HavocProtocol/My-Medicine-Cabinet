package com.example.boxscan.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.boxscan.BoxScanService
import com.example.boxscan.models.BoxScanResult
import com.example.boxscan.models.CapturedSide
import com.example.boxscan.models.MedicineDraft
import com.example.boxscan.models.PackageSideType
import com.example.boxscan.ocr.ImagePreprocessor
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Multi-side medicine box capture screen ("مسح علبة الدواء").
 *
 * Allows capturing 1..6 package sides with guided step prompts, flash toggle, tap-to-focus,
 * side relabeling, retake, delete, and offline package sample presets for testing on emulators.
 * Transitions to [BoxScanReviewScreen] after running on-device OCR + extraction + merging.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoxScanScreen(
    onDraftConfirmed: (MedicineDraft) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    val boxScanService = remember { BoxScanService(context) }

    // Camera permission state (requested ONLY when user enters this screen)
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionRequestedOnce by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        permissionRequestedOnce = true
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Captured sides (1..6)
    var capturedSides by remember { mutableStateOf<List<CapturedSide>>(emptyList()) }
    var activeSideType by remember { mutableStateOf(PackageSideType.FRONT) }
    var replacingSideId by remember { mutableStateOf<String?>(null) }

    // CameraX state
    var flashEnabled by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var cameraRef by remember { mutableStateOf<Camera?>(null) }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    // Processing / Review state
    var isProcessing by remember { mutableStateOf(false) }
    var processingSideIndex by remember { mutableIntStateOf(0) }
    var processingTotalSides by remember { mutableIntStateOf(0) }
    var scanResult by remember { mutableStateOf<BoxScanResult?>(null) }

    // Dialog states
    var previewSide by remember { mutableStateOf<CapturedSide?>(null) }
    var relabelSide by remember { mutableStateOf<CapturedSide?>(null) }
    var showSamplePresetsSheet by remember { mutableStateOf(false) }

    // Clean up temporary files if user backs out completely
    val handleCancel = {
        BoxScanService.cleanupSessionFiles(capturedSides)
        onCancel()
    }

    BackHandler {
        if (scanResult != null) {
            scanResult = null
        } else {
            handleCancel()
        }
    }

    // If review result is ready, display BoxScanReviewScreen
    val currentResult = scanResult
    if (currentResult != null) {
        BoxScanReviewScreen(
            scanResult = currentResult,
            onConfirmDraft = { confirmedDraft ->
                // Clean up temporary captured side files after confirmation
                BoxScanService.cleanupSessionFiles(capturedSides)
                onDraftConfirmed(confirmedDraft)
            },
            onBackToCapture = {
                scanResult = null
            },
            onCancel = handleCancel
        )
        return
    }

    // Helper to add or replace a captured side
    fun addOrReplaceSide(file: File, sideType: PackageSideType) {
        val targetReplaceId = replacingSideId
        if (targetReplaceId != null) {
            val oldSide = capturedSides.find { it.id == targetReplaceId }
            if (oldSide != null) {
                BoxScanService.deleteSideFile(oldSide)
            }
            capturedSides = capturedSides.map { side ->
                if (side.id == targetReplaceId) {
                    CapturedSide(
                        id = UUID.randomUUID().toString(),
                        sideType = sideType,
                        imagePath = file.absolutePath
                    )
                } else {
                    side
                }
            }
            replacingSideId = null
        } else if (capturedSides.size < 6) {
            val newSide = CapturedSide(
                id = UUID.randomUUID().toString(),
                sideType = sideType,
                imagePath = file.absolutePath
            )
            val updated = capturedSides + newSide
            capturedSides = updated
            // Auto-advance active side suggestion
            activeSideType = suggestNextSideType(updated)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.scan_medicine_box_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${capturedSides.size} / 6 ${stringResource(R.string.captured_sides_count_suffix)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = handleCancel,
                        modifier = Modifier.testTag("box_scan_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cancel)
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = { showSamplePresetsSheet = true },
                        modifier = Modifier.testTag("box_scan_sample_presets_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Inventory2,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.box_scan_demo_samples))
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
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    if (isProcessing) {
                        LinearProgressIndicator(
                            progress = {
                                if (processingTotalSides > 0) {
                                    processingSideIndex.toFloat() / processingTotalSides.toFloat()
                                } else 0f
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(
                                R.string.box_scan_processing_progress,
                                processingSideIndex,
                                processingTotalSides
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    Button(
                        onClick = {
                            if (capturedSides.isNotEmpty() && !isProcessing) {
                                isProcessing = true
                                coroutineScope.launch {
                                    val result = boxScanService.processCapturedSides(
                                        sides = capturedSides,
                                        onProgress = { current, total ->
                                            processingSideIndex = current
                                            processingTotalSides = total
                                        }
                                    )
                                    isProcessing = false
                                    scanResult = result
                                }
                            }
                        },
                        enabled = capturedSides.isNotEmpty() && !isProcessing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("box_scan_extract_button"),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.DocumentScanner, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.box_scan_done_extract),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Guided Side Selector Chips
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (replacingSideId != null) {
                                stringResource(R.string.box_scan_retake_side, stringResource(activeSideType.labelRes))
                            } else {
                                stringResource(R.string.box_scan_current_side, stringResource(activeSideType.labelRes))
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        if (replacingSideId != null) {
                            TextButton(onClick = { replacingSideId = null }) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    }

                    Text(
                        text = stringResource(activeSideType.guidanceRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val allTypes = PackageSideType.entries
                        items(allTypes.size) { index ->
                            val type = allTypes[index]
                            val alreadyCaptured = capturedSides.any { it.sideType == type }
                            FilterChip(
                                selected = activeSideType == type,
                                onClick = { activeSideType = type },
                                label = { Text(stringResource(type.labelRes)) },
                                leadingIcon = if (alreadyCaptured) {
                                    {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                } else null,
                                modifier = Modifier.testTag("side_type_chip_${type.name}")
                            )
                        }
                    }
                }
            }

            // 2. Camera Preview or Permission/Fallback Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF0F172A))
            ) {
                if (hasCameraPermission && cameraError == null) {
                    AndroidView(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures { offset ->
                                    val cam = cameraRef ?: return@detectTapGestures
                                    val factory = androidx.camera.core.SurfaceOrientedMeteringPointFactory(
                                        size.width.toFloat(),
                                        size.height.toFloat()
                                    )
                                    val point = factory.createPoint(offset.x, offset.y)
                                    val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                                        .setAutoCancelDuration(3, TimeUnit.SECONDS)
                                        .build()
                                    cam.cameraControl.startFocusAndMetering(action)
                                }
                            },
                        factory = { ctx ->
                            val previewView = PreviewView(ctx).apply {
                                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            }
                            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                            cameraProviderFuture.addListener({
                                try {
                                    val cameraProvider = cameraProviderFuture.get()
                                    val preview = Preview.Builder().build().also {
                                        it.setSurfaceProvider(previewView.surfaceProvider)
                                    }
                                    cameraProvider.unbindAll()
                                    val camera = cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        CameraSelector.DEFAULT_BACK_CAMERA,
                                        preview,
                                        imageCapture
                                    )
                                    cameraRef = camera
                                } catch (e: Exception) {
                                    cameraError = e.localizedMessage ?: "Camera unavailable"
                                }
                            }, ContextCompat.getMainExecutor(ctx))
                            previewView
                        }
                    )

                    // Framing overlay rectangle
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp)
                            .border(
                                width = 2.dp,
                                color = Color.White.copy(alpha = 0.7f),
                                shape = RoundedCornerShape(16.dp)
                            )
                    )

                    // Flash toggle button
                    IconButton(
                        onClick = {
                            flashEnabled = !flashEnabled
                            cameraRef?.cameraControl?.enableTorch(flashEnabled)
                        },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                            .testTag("box_scan_flash_toggle")
                    ) {
                        Icon(
                            imageVector = if (flashEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                            contentDescription = "Flash",
                            tint = if (flashEnabled) Color(0xFFFACC15) else Color.White
                        )
                    }

                    // Capture button bar
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.45f))
                            .padding(vertical = 10.dp, horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (capturedSides.size >= 6 && replacingSideId == null) {
                                stringResource(R.string.box_scan_max_sides_reached)
                            } else {
                                stringResource(R.string.box_scan_point_camera, stringResource(activeSideType.labelRes))
                            },
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )

                        Button(
                            onClick = {
                                val photoFile = File(
                                    context.cacheDir,
                                    "box_side_${System.currentTimeMillis()}.jpg"
                                )
                                val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
                                imageCapture.takePicture(
                                    outputOptions,
                                    ContextCompat.getMainExecutor(context),
                                    object : ImageCapture.OnImageSavedCallback {
                                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                            addOrReplaceSide(photoFile, activeSideType)
                                        }

                                        override fun onError(exception: ImageCaptureException) {
                                            // If virtual emulator camera fails to save JPEG, synthesize a blank frame
                                            val fallback = ImagePreprocessor.renderSyntheticPackageSideImage(
                                                cacheDir = context.cacheDir,
                                                fileName = "box_side_fallback_${System.currentTimeMillis()}.jpg",
                                                titleText = context.getString(activeSideType.labelRes),
                                                lines = emptyList()
                                            )
                                            addOrReplaceSide(fallback, activeSideType)
                                        }
                                    }
                                )
                            },
                            enabled = (capturedSides.size < 6 || replacingSideId != null) && !isProcessing,
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.testTag("box_scan_capture_button")
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.box_scan_capture_photo))
                        }
                    }
                } else {
                    // Permission denied or Camera unavailable state
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.NoPhotography,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(42.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = if (!hasCameraPermission) {
                                stringResource(R.string.box_scan_camera_permission_needed)
                            } else {
                                stringResource(R.string.box_scan_camera_unavailable)
                            },
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (!hasCameraPermission) {
                                OutlinedButton(
                                    onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                                ) {
                                    Text(stringResource(R.string.retry))
                                }
                                if (permissionRequestedOnce) {
                                    OutlinedButton(
                                        onClick = {
                                            val intent = Intent(
                                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                Uri.fromParts("package", context.packageName, null)
                                            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                            runCatching { context.startActivity(intent) }
                                        },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                                    ) {
                                        Text(stringResource(R.string.settings))
                                    }
                                }
                            }
                            Button(
                                onClick = { showSamplePresetsSheet = true }
                            ) {
                                Icon(Icons.Default.Inventory2, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.box_scan_demo_samples))
                            }
                        }
                    }
                }
            }

            // 3. Quick Multi-Side Test Presets Bar (helpful for testing or emulator users)
            OutlinedCard(
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.box_scan_preset_banner_title),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(R.string.box_scan_preset_banner_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    FilledTonalButton(
                        onClick = { showSamplePresetsSheet = true },
                        modifier = Modifier.testTag("open_sample_presets_card_button")
                    ) {
                        Text(stringResource(R.string.box_scan_select_sample_box))
                    }
                }
            }

            // 4. Captured Sides Gallery (1..6)
            Text(
                text = stringResource(R.string.box_scan_captured_sides_header, capturedSides.size),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )

            if (capturedSides.isEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhotoLibrary,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.box_scan_empty_prompt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    itemsIndexed(capturedSides, key = { _, item -> item.id }) { idx, side ->
                        CapturedSideThumbnailCard(
                            index = idx + 1,
                            side = side,
                            isReplacing = replacingSideId == side.id,
                            onPreview = { previewSide = side },
                            onRelabel = { relabelSide = side },
                            onRetake = {
                                replacingSideId = side.id
                                activeSideType = side.sideType
                            },
                            onDelete = {
                                BoxScanService.deleteSideFile(side)
                                capturedSides = capturedSides.filterNot { it.id == side.id }
                                if (replacingSideId == side.id) replacingSideId = null
                            }
                        )
                    }
                }
            }
        }
    }

    // Preview Modal
    val currentPreviewSide = previewSide
    if (currentPreviewSide != null) {
        Dialog(onDismissRequest = { previewSide = null }) {
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
                            text = stringResource(currentPreviewSide.sideType.labelRes),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = { previewSide = null }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    val bmp = remember(currentPreviewSide.imagePath) {
                        BitmapFactory.decodeFile(currentPreviewSide.imagePath)
                    }
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = stringResource(currentPreviewSide.sideType.labelRes),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 200.dp, max = 360.dp)
                                .clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            }
        }
    }

    // Relabel Side Type Dialog
    val currentRelabelSide = relabelSide
    if (currentRelabelSide != null) {
        AlertDialog(
            onDismissRequest = { relabelSide = null },
            title = { Text(stringResource(R.string.box_scan_change_side_type)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PackageSideType.entries.forEach { type ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    capturedSides = capturedSides.map {
                                        if (it.id == currentRelabelSide.id) it.copy(sideType = type) else it
                                    }
                                    relabelSide = null
                                }
                                .padding(vertical = 10.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = currentRelabelSide.sideType == type,
                                onClick = null
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(stringResource(type.labelRes), fontWeight = FontWeight.SemiBold)
                                Text(
                                    stringResource(type.guidanceRes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { relabelSide = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Demo / Offline Multi-Side Sample Presets Dialog
    if (showSamplePresetsSheet) {
        AlertDialog(
            onDismissRequest = { showSamplePresetsSheet = false },
            title = { Text(stringResource(R.string.box_scan_sample_dialog_title)) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = stringResource(R.string.box_scan_sample_dialog_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Preset 1: Arabic + English Panadol Extra (3 sides)
                    OutlinedCard(
                        onClick = {
                            BoxScanService.cleanupSessionFiles(capturedSides)
                            val front = ImagePreprocessor.renderSyntheticPackageSideImage(
                                cacheDir = context.cacheDir,
                                fileName = "preset_panadol_front_${System.currentTimeMillis()}.jpg",
                                titleText = "FRONT - Panadol Extra",
                                lines = listOf(
                                    "Panadol Extra",
                                    "500 mg",
                                    "24 Film-Coated Tablets"
                                )
                            )
                            val back = ImagePreprocessor.renderSyntheticPackageSideImage(
                                cacheDir = context.cacheDir,
                                fileName = "preset_panadol_back_${System.currentTimeMillis()}.jpg",
                                titleText = "BACK - Panadol Extra",
                                lines = listOf(
                                    "المادة الفعالة: باراسيتامول 500 ملغ",
                                    "الشركة المصنعة: شركة جلاكسو سميثكلاين فارما",
                                    "يحفظ في درجة حرارة أقل من 25°C"
                                )
                            )
                            val flap = ImagePreprocessor.renderSyntheticPackageSideImage(
                                cacheDir = context.cacheDir,
                                fileName = "preset_panadol_flap_${System.currentTimeMillis()}.jpg",
                                titleText = "FLAP - Panadol Extra",
                                lines = listOf(
                                    "LOT: BX2409A",
                                    "MFG: 02/2025",
                                    "EXP: 02/2028"
                                )
                            )
                            capturedSides = listOf(
                                CapturedSide(UUID.randomUUID().toString(), PackageSideType.FRONT, front.absolutePath),
                                CapturedSide(UUID.randomUUID().toString(), PackageSideType.BACK, back.absolutePath),
                                CapturedSide(UUID.randomUUID().toString(), PackageSideType.FLAP, flap.absolutePath)
                            )
                            showSamplePresetsSheet = false
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("preset_panadol_3sides")
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(stringResource(R.string.box_scan_preset1_title), fontWeight = FontWeight.Bold)
                            Text(
                                stringResource(R.string.box_scan_preset1_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Preset 2: Refrigerated Insulin / Biologic Injection (3 sides)
                    OutlinedCard(
                        onClick = {
                            BoxScanService.cleanupSessionFiles(capturedSides)
                            val front = ImagePreprocessor.renderSyntheticPackageSideImage(
                                cacheDir = context.cacheDir,
                                fileName = "preset_insulin_front_${System.currentTimeMillis()}.jpg",
                                titleText = "FRONT - Lantus Solostar",
                                lines = listOf(
                                    "Lantus Solostar",
                                    "100 IU/ml",
                                    "Solution for Injection",
                                    "5 Vials"
                                )
                            )
                            val side1 = ImagePreprocessor.renderSyntheticPackageSideImage(
                                cacheDir = context.cacheDir,
                                fileName = "preset_insulin_side_${System.currentTimeMillis()}.jpg",
                                titleText = "SIDE 1 - Lantus Solostar",
                                lines = listOf(
                                    "Each ml contains: Insulin Glargine 100 IU",
                                    "Manufactured by: Sanofi Pharma GmbH",
                                    "يحفظ في الثلاجة (2 - 8°C)"
                                )
                            )
                            val flap = ImagePreprocessor.renderSyntheticPackageSideImage(
                                cacheDir = context.cacheDir,
                                fileName = "preset_insulin_flap_${System.currentTimeMillis()}.jpg",
                                titleText = "FLAP - Lantus Solostar",
                                lines = listOf(
                                    "رقم التشغيلة: INS-8841",
                                    "تاريخ الإنتاج: ٠٥/٢٠٢٥",
                                    "تاريخ الانتهاء: ٠٥/٢٠٢٧"
                                )
                            )
                            capturedSides = listOf(
                                CapturedSide(UUID.randomUUID().toString(), PackageSideType.FRONT, front.absolutePath),
                                CapturedSide(UUID.randomUUID().toString(), PackageSideType.SIDE_1, side1.absolutePath),
                                CapturedSide(UUID.randomUUID().toString(), PackageSideType.FLAP, flap.absolutePath)
                            )
                            showSamplePresetsSheet = false
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("preset_refrigerated_injection")
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(stringResource(R.string.box_scan_preset2_title), fontWeight = FontWeight.Bold)
                            Text(
                                stringResource(R.string.box_scan_preset2_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Preset 3: Expired Syrup Package (to test SafetyStrings.EXPIRED_DISPOSAL warning)
                    OutlinedCard(
                        onClick = {
                            BoxScanService.cleanupSessionFiles(capturedSides)
                            val front = ImagePreprocessor.renderSyntheticPackageSideImage(
                                cacheDir = context.cacheDir,
                                fileName = "preset_expired_front_${System.currentTimeMillis()}.jpg",
                                titleText = "FRONT - Amoxil Forte",
                                lines = listOf(
                                    "Amoxil Forte",
                                    "250mg/5ml",
                                    "Oral Syrup",
                                    "100 ml"
                                )
                            )
                            val flap = ImagePreprocessor.renderSyntheticPackageSideImage(
                                cacheDir = context.cacheDir,
                                fileName = "preset_expired_flap_${System.currentTimeMillis()}.jpg",
                                titleText = "FLAP - Amoxil Forte",
                                lines = listOf(
                                    "Each 5 ml contains: Amoxicillin",
                                    "LOT: AMX2104",
                                    "MFG: 03/2021",
                                    "EXP: 03/2023"
                                )
                            )
                            capturedSides = listOf(
                                CapturedSide(UUID.randomUUID().toString(), PackageSideType.FRONT, front.absolutePath),
                                CapturedSide(UUID.randomUUID().toString(), PackageSideType.FLAP, flap.absolutePath)
                            )
                            showSamplePresetsSheet = false
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("preset_expired_syrup")
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(stringResource(R.string.box_scan_preset3_title), fontWeight = FontWeight.Bold)
                            Text(
                                stringResource(R.string.box_scan_preset3_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSamplePresetsSheet = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun CapturedSideThumbnailCard(
    index: Int,
    side: CapturedSide,
    isReplacing: Boolean,
    onPreview: () -> Unit,
    onRelabel: () -> Unit,
    onRetake: () -> Unit,
    onDelete: () -> Unit
) {
    val bmp = remember(side.imagePath) {
        BitmapFactory.decodeFile(side.imagePath)
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        border = if (isReplacing) {
            androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else null,
        modifier = Modifier
            .width(156.dp)
            .testTag("captured_side_card_$index")
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(92.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.DarkGray)
                    .clickable(onClick = onPreview)
            ) {
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = stringResource(side.sideType.labelRes),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Surface(
                    color = Color.Black.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(bottomEnd = 8.dp),
                    modifier = Modifier.align(Alignment.TopStart)
                ) {
                    Text(
                        text = "#$index",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Side label button (tap to relabel)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onRelabel)
                    .padding(vertical = 2.dp)
            ) {
                Text(
                    text = stringResource(side.sideType.labelRes),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = stringResource(R.string.box_scan_change_side_type),
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Action icons: Preview, Retake, Delete
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = onPreview,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ZoomIn,
                        contentDescription = stringResource(R.string.box_scan_preview),
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = onRetake,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.box_scan_retake),
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = stringResource(R.string.delete),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

private fun suggestNextSideType(captured: List<CapturedSide>): PackageSideType {
    val existingTypes = captured.map { it.sideType }.toSet()
    val ordered = listOf(
        PackageSideType.FRONT,
        PackageSideType.BACK,
        PackageSideType.FLAP,
        PackageSideType.SIDE_1,
        PackageSideType.SIDE_2,
        PackageSideType.UNKNOWN
    )
    return ordered.firstOrNull { it !in existingTypes } ?: PackageSideType.UNKNOWN
}
