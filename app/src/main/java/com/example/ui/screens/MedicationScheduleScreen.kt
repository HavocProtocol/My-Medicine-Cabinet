package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.EventNote
import androidx.compose.material.icons.filled.*
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.data.DoseRecord
import com.example.data.MedicationSchedule
import com.example.schedule.DayScheduleSummary
import com.example.schedule.DoseStatus
import com.example.schedule.InventorySufficiencyInfo
import com.example.schedule.MedicationScheduleEngine
import com.example.schedule.OverlappingTimeSlotGroup
import com.example.schedule.ScheduleStatus
import com.example.schedule.ScheduledTimeSlot
import com.example.ui.viewmodels.ScheduleViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicationScheduleScreen(
    initialMedicineId: Int? = null,
    onBack: () -> Unit,
    viewModel: ScheduleViewModel = viewModel()
) {
    val context = LocalContext.current
    val todayIso by viewModel.todayDateIso.collectAsState()
    val selectedDateIso by viewModel.selectedDateIso.collectAsState()
    val todayDoses by viewModel.todayDoses.collectAsState()
    val selectedDateDoses by viewModel.selectedDateDoses.collectAsState()
    val schedules by viewModel.schedules.collectAsState()
    val allDoseRecords by viewModel.allDoseRecords.collectAsState()
    val overlappingGroups by viewModel.overlappingSlotsForSelectedDate.collectAsState()
    val inventorySufficiency by viewModel.inventorySufficiency.collectAsState()
    val daySummaries by viewModel.daySummariesByDate.collectAsState()

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var showAddEditDialog by remember { mutableStateOf(initialMedicineId != null) }
    var editingSchedule by remember { mutableStateOf<MedicationSchedule?>(null) }
    var replacingSchedule by remember { mutableStateOf<MedicationSchedule?>(null) }
    var preselectedMedId by remember { mutableStateOf(initialMedicineId) }
    var schedulePendingDelete by remember { mutableStateOf<MedicationSchedule?>(null) }

    // Notification permission check
    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotificationPermission = granted
    }

    LaunchedEffect(Unit) {
        viewModel.refreshAndGenerateOccurrences()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("schedule_back_button")
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.sched_title),
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResource(R.string.sched_subtitle),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            editingSchedule = null
                            replacingSchedule = null
                            preselectedMedId = null
                            showAddEditDialog = true
                        },
                        modifier = Modifier.testTag("top_add_schedule_button")
                    ) {
                        Icon(
                            Icons.Default.AddAlarm,
                            contentDescription = stringResource(R.string.sched_add_button),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    editingSchedule = null
                    replacingSchedule = null
                    preselectedMedId = null
                    showAddEditDialog = true
                },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.sched_add_button)) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("fab_add_schedule")
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 3 Tabs
            TabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Tab(
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    text = {
                        Text(
                            stringResource(R.string.sched_tab_today),
                            fontWeight = if (selectedTabIndex == 0) FontWeight.Bold else FontWeight.Medium
                        )
                    },
                    modifier = Modifier.testTag("schedule_tab_today")
                )
                Tab(
                    selected = selectedTabIndex == 1,
                    onClick = { selectedTabIndex = 1 },
                    text = {
                        Text(
                            "${stringResource(R.string.sched_tab_schedules)} (${schedules.count { it.status != ScheduleStatus.DISCONTINUED.key }})",
                            fontWeight = if (selectedTabIndex == 1) FontWeight.Bold else FontWeight.Medium
                        )
                    },
                    modifier = Modifier.testTag("schedule_tab_routines")
                )
                Tab(
                    selected = selectedTabIndex == 2,
                    onClick = { selectedTabIndex = 2 },
                    text = {
                        Text(
                            stringResource(R.string.sched_tab_history),
                            fontWeight = if (selectedTabIndex == 2) FontWeight.Bold else FontWeight.Medium
                        )
                    },
                    modifier = Modifier.testTag("schedule_tab_history")
                )
            }

            when (selectedTabIndex) {
                0 -> TodayAndCalendarTab(
                    todayIso = todayIso,
                    selectedDateIso = selectedDateIso,
                    todayDoses = todayDoses,
                    selectedDateDoses = selectedDateDoses,
                    overlappingGroups = overlappingGroups,
                    inventorySufficiency = inventorySufficiency,
                    daySummaries = daySummaries,
                    hasNotificationPermission = hasNotificationPermission,
                    onRequestPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onOpenNotificationSettings = {
                        try {
                            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            }
                            context.startActivity(intent)
                        } catch (_: Exception) {}
                    },
                    onSelectDate = { viewModel.selectDate(it) },
                    onMarkTaken = { dose -> viewModel.markDoseTaken(dose.id) },
                    onMarkSkipped = { dose -> viewModel.markDoseSkipped(dose.id) },
                    onSnooze = { dose ->
                        viewModel.snoozeDose(dose.id, 15) {
                            Toast.makeText(
                                context,
                                "${dose.medicineName}: +15 min",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )

                1 -> RoutinesTab(
                    schedules = schedules.filter { it.status != ScheduleStatus.DISCONTINUED.key },
                    onEdit = { sched ->
                        editingSchedule = sched
                        replacingSchedule = null
                        preselectedMedId = sched.medicineId
                        showAddEditDialog = true
                    },
                    onPause = { sched -> viewModel.pauseSchedule(sched.id) },
                    onResume = { sched ->
                        viewModel.resumeSchedule(sched.id) { res ->
                            res.onFailure { err ->
                                Toast.makeText(
                                    context,
                                    err.message ?: context.getString(
                                        R.string.sched_expiry_expired_warning,
                                        sched.medicineName,
                                        sched.recordedExpiryDateMillis?.let {
                                            MedicationScheduleEngine.millisToLocalDateIso(it)
                                        } ?: ""
                                    ),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    },
                    onReplace = { sched ->
                        editingSchedule = null
                        replacingSchedule = sched
                        preselectedMedId = null
                        showAddEditDialog = true
                    },
                    onRemove = { sched -> schedulePendingDelete = sched }
                )

                2 -> HistoryTab(
                    allDoseRecords = allDoseRecords,
                    todayIso = todayIso
                )
            }
        }
    }

    if (showAddEditDialog) {
        AddEditScheduleDialog(
            viewModel = viewModel,
            existingSchedule = editingSchedule,
            replacingSchedule = replacingSchedule,
            preselectedMedicineId = preselectedMedId,
            onDismiss = {
                showAddEditDialog = false
                editingSchedule = null
                replacingSchedule = null
                preselectedMedId = null
            },
            onSaved = {
                showAddEditDialog = false
                editingSchedule = null
                replacingSchedule = null
                preselectedMedId = null
            }
        )
    }

    schedulePendingDelete?.let { sched ->
        AlertDialog(
            onDismissRequest = { schedulePendingDelete = null },
            title = { Text(stringResource(R.string.sched_remove_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.sched_remove_confirm_body,
                        sched.medicineName
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.removeSchedule(sched.id) {
                            schedulePendingDelete = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("confirm_remove_schedule_button")
                ) {
                    Text(stringResource(R.string.sched_action_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { schedulePendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun TodayAndCalendarTab(
    todayIso: String,
    selectedDateIso: String,
    todayDoses: List<DoseRecord>,
    selectedDateDoses: List<DoseRecord>,
    overlappingGroups: List<OverlappingTimeSlotGroup>,
    inventorySufficiency: List<InventorySufficiencyInfo>,
    daySummaries: Map<String, DayScheduleSummary>,
    hasNotificationPermission: Boolean,
    onRequestPermission: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onSelectDate: (String) -> Unit,
    onMarkTaken: (DoseRecord) -> Unit,
    onMarkSkipped: (DoseRecord) -> Unit,
    onSnooze: (DoseRecord) -> Unit
) {
    var calendarAnchorIso by remember(selectedDateIso) { mutableStateOf(selectedDateIso) }
    val weekDates = remember(calendarAnchorIso) {
        (-3..3).map { offset ->
            MedicationScheduleEngine.addDaysToIso(calendarAnchorIso, offset)
        }
    }

    val completedToday = todayDoses.count { it.status == DoseStatus.TAKEN.key }
    val remainingToday = todayDoses.count { it.status == DoseStatus.PENDING.key }
    val missedToday = todayDoses.count { it.status == DoseStatus.MISSED.key }
    val nextUpcomingDose = todayDoses
        .filter { it.status == DoseStatus.PENDING.key }
        .minByOrNull { it.scheduledTimestampMillis }

    val lowStockWarnings = inventorySufficiency.filter { it.isInsufficientForUpcoming || it.isLowStock }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Notification Permission Banner
        if (!hasNotificationPermission) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.NotificationsActive,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.sched_perm_banner_title),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        Text(
                            text = stringResource(R.string.sched_perm_banner_body),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onRequestPermission) {
                                Text(stringResource(R.string.sched_perm_enable_btn), fontSize = 12.sp)
                            }
                            OutlinedButton(onClick = onOpenNotificationSettings) {
                                Text(stringResource(R.string.sched_perm_settings_btn), fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // Today's Progress Summary Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("today_progress_card"),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.sched_today_summary_title),
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = todayIso,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SummaryKpiPill(
                            label = stringResource(R.string.sched_completed_doses),
                            count = completedToday,
                            bgColor = Color(0xFFDCFCE7),
                            textColor = Color(0xFF166534),
                            modifier = Modifier.weight(1f)
                        )
                        SummaryKpiPill(
                            label = stringResource(R.string.sched_remaining_doses),
                            count = remainingToday,
                            bgColor = MaterialTheme.colorScheme.surface,
                            textColor = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        SummaryKpiPill(
                            label = stringResource(R.string.sched_missed_doses),
                            count = missedToday,
                            bgColor = Color(0xFFFEE2E2),
                            textColor = Color(0xFF991B1B),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Icon(
                            Icons.Default.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (nextUpcomingDose != null) {
                                stringResource(
                                    R.string.sched_next_reminder,
                                    "${nextUpcomingDose.medicineName} (${formatAmount(nextUpcomingDose.configuredDosage)} ${nextUpcomingDose.dosageUnit})",
                                    nextUpcomingDose.scheduledTimeHm
                                )
                            } else {
                                stringResource(R.string.sched_no_upcoming_today)
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        // Low-Stock / Inventory Sufficiency Banner
        if (lowStockWarnings.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("low_stock_warning_card"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7)),
                    border = BorderStroke(1.dp, Color(0xFFF59E0B))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Inventory,
                                contentDescription = null,
                                tint = Color(0xFF92400E),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.sched_low_stock_banner_title),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = Color(0xFF92400E)
                            )
                        }
                        lowStockWarnings.forEach { info ->
                            Text(
                                text = stringResource(
                                    R.string.sched_low_stock_banner_body,
                                    info.medicineName,
                                    formatAmount(info.recordedQuantity ?: 0.0),
                                    info.unit,
                                    formatAmount(info.upcoming7DayRequired)
                                ),
                                fontSize = 12.sp,
                                color = Color(0xFF92400E)
                            )
                        }
                    }
                }
            }
        }

        // Interactive Calendar Strip
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.sched_calendar_title),
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = {
                                    val prev = MedicationScheduleEngine.addDaysToIso(selectedDateIso, -7)
                                    calendarAnchorIso = prev
                                    onSelectDate(prev)
                                },
                                modifier = Modifier.testTag("calendar_prev_week_btn")
                            ) {
                                Text(stringResource(R.string.sched_prev_week), fontSize = 12.sp)
                            }
                            TextButton(
                                onClick = {
                                    calendarAnchorIso = todayIso
                                    onSelectDate(todayIso)
                                },
                                modifier = Modifier.testTag("calendar_today_btn")
                            ) {
                                Text(stringResource(R.string.sched_today_btn), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            TextButton(
                                onClick = {
                                    val next = MedicationScheduleEngine.addDaysToIso(selectedDateIso, 7)
                                    calendarAnchorIso = next
                                    onSelectDate(next)
                                },
                                modifier = Modifier.testTag("calendar_next_week_btn")
                            ) {
                                Text(stringResource(R.string.sched_next_week), fontSize = 12.sp)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        weekDates.forEach { dateIso ->
                            val isSelected = dateIso == selectedDateIso
                            val isToday = dateIso == todayIso
                            val summary = daySummaries[dateIso]
                            val dayOfMonth = dateIso.takeLast(2)
                            val dowNum = MedicationScheduleEngine.getIsoDayOfWeek(dateIso) ?: 1
                            val dowShort = when (dowNum) {
                                1 -> stringResource(R.string.sched_day_mon)
                                2 -> stringResource(R.string.sched_day_tue)
                                3 -> stringResource(R.string.sched_day_wed)
                                4 -> stringResource(R.string.sched_day_thu)
                                5 -> stringResource(R.string.sched_day_fri)
                                6 -> stringResource(R.string.sched_day_sat)
                                else -> stringResource(R.string.sched_day_sun)
                            }

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primary
                                        else if (isToday) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                    )
                                    .clickable { onSelectDate(dateIso) }
                                    .padding(vertical = 8.dp)
                                    .testTag("calendar_day_$dateIso")
                            ) {
                                Text(
                                    text = dowShort,
                                    fontSize = 10.sp,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = dayOfMonth,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                // Status dot
                                val dotColor = when {
                                    summary == null || summary.totalCount == 0 -> Color.Transparent
                                    summary.blockedCount > 0 || summary.missedCount > 0 -> Color(0xFFEF4444)
                                    summary.pendingCount > 0 -> Color(0xFFF59E0B)
                                    else -> Color(0xFF22C55E)
                                }
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(dotColor)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Overlapping Schedule Notice
        if (overlappingGroups.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("overlap_schedule_card"),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.sched_overlap_notice_title),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                        overlappingGroups.forEach { group ->
                            val names = group.doses.joinToString(", ") { it.medicineName }
                            Text(
                                text = stringResource(
                                    R.string.sched_overlap_notice_body,
                                    group.timeHm,
                                    names
                                ),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }
            }
        }

        // Header for Selected Date Doses
        item {
            Text(
                text = stringResource(R.string.sched_doses_for_date, selectedDateIso),
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        if (selectedDateDoses.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Outlined.EventAvailable,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                            modifier = Modifier.size(42.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.sched_empty_day_title),
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.sched_empty_day_desc),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            items(selectedDateDoses, key = { it.id }) { dose ->
                DoseOccurrenceCard(
                    dose = dose,
                    onMarkTaken = { onMarkTaken(dose) },
                    onMarkSkipped = { onMarkSkipped(dose) },
                    onSnooze = { onSnooze(dose) }
                )
            }
        }
    }
}

@Composable
private fun DoseOccurrenceCard(
    dose: DoseRecord,
    onMarkTaken: () -> Unit,
    onMarkSkipped: () -> Unit,
    onSnooze: () -> Unit
) {
    val status = DoseStatus.fromKey(dose.status)
    val (badgeBg, badgeFg, badgeLabel) = when (status) {
        DoseStatus.TAKEN -> Triple(Color(0xFFDCFCE7), Color(0xFF166534), stringResource(R.string.sched_dose_taken))
        DoseStatus.SKIPPED -> Triple(Color(0xFFE0E7FF), Color(0xFF1E40AF), stringResource(R.string.sched_dose_skipped))
        DoseStatus.MISSED -> Triple(Color(0xFFFEE2E2), Color(0xFF991B1B), stringResource(R.string.sched_dose_missed))
        DoseStatus.BLOCKED -> Triple(Color(0xFFFEE2E2), Color(0xFF991B1B), stringResource(R.string.sched_dose_blocked))
        DoseStatus.PENDING -> Triple(Color(0xFFFEF3C7), Color(0xFF92400E), stringResource(R.string.sched_dose_pending))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("dose_card_${dose.id}"),
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = dose.scheduledTimeHm,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = dose.medicineName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${formatAmount(dose.configuredDosage)} ${dose.dosageUnit}",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(badgeBg)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = badgeLabel,
                        color = badgeFg,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (status != DoseStatus.BLOCKED) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onMarkTaken,
                        enabled = status != DoseStatus.TAKEN,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("mark_taken_btn_${dose.id}"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.sched_action_taken), fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = onMarkSkipped,
                        enabled = status != DoseStatus.SKIPPED,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("mark_skipped_btn_${dose.id}"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(stringResource(R.string.sched_action_skipped), fontSize = 12.sp)
                    }

                    if (status == DoseStatus.PENDING) {
                        OutlinedButton(
                            onClick = onSnooze,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("snooze_btn_${dose.id}"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Snooze, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.sched_action_remind_later), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoutinesTab(
    schedules: List<MedicationSchedule>,
    onEdit: (MedicationSchedule) -> Unit,
    onPause: (MedicationSchedule) -> Unit,
    onResume: (MedicationSchedule) -> Unit,
    onReplace: (MedicationSchedule) -> Unit,
    onRemove: (MedicationSchedule) -> Unit
) {
    if (schedules.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.AutoMirrored.Outlined.EventNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.sched_empty_routines_title),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.sched_empty_routines_desc),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(schedules, key = { it.id }) { sched ->
            val status = ScheduleStatus.fromKey(sched.status)
            val slots = ScheduledTimeSlot.decodeList(sched.doseTimesJson)
            val expIso = sched.recordedExpiryDateMillis?.let {
                MedicationScheduleEngine.millisToLocalDateIso(it)
            } ?: "-"

            val (statusBg, statusFg, statusLabel) = when (status) {
                ScheduleStatus.ACTIVE -> Triple(Color(0xFFDCFCE7), Color(0xFF166534), stringResource(R.string.sched_status_active))
                ScheduleStatus.PAUSED -> Triple(Color(0xFFFEF3C7), Color(0xFF92400E), stringResource(R.string.sched_status_paused))
                ScheduleStatus.COMPLETED -> Triple(Color(0xFFE0E7FF), Color(0xFF1E40AF), stringResource(R.string.sched_status_completed))
                ScheduleStatus.BLOCKED_EXPIRED -> Triple(Color(0xFFFEE2E2), Color(0xFF991B1B), stringResource(R.string.sched_status_blocked_expired))
                ScheduleStatus.DISCONTINUED -> Triple(Color(0xFFF3F4F6), Color(0xFF374151), stringResource(R.string.sched_status_discontinued))
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("routine_card_${sched.id}"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(
                    1.dp,
                    if (status == ScheduleStatus.BLOCKED_EXPIRED) Color(0xFFEF4444)
                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = buildString {
                                    append(sched.medicineName)
                                    if (!sched.strength.isNullOrBlank()) {
                                        append(" (${sched.strength})")
                                    }
                                },
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                text = "${sched.formulation ?: ""} • ${sched.routeOfAdministration ?: ""}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(statusBg)
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = statusLabel,
                                color = statusFg,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Text(
                        text = slots.joinToString(" • ") { slot ->
                            "${slot.timeHm} (${formatAmount(slot.dosage)} ${sched.dosageUnit})"
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Text(
                        text = "${sched.startDateIso} → ${sched.endDateIso ?: "∞"} | ${stringResource(R.string.expiry_date)}: $expIso",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (!sched.instructions.isNullOrBlank()) {
                        Text(
                            text = sched.instructions,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (status == ScheduleStatus.BLOCKED_EXPIRED) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFEE2E2)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.sched_expired_suspended_body,
                                    sched.medicineName,
                                    expIso
                                ),
                                color = Color(0xFF991B1B),
                                fontSize = 12.sp,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (status == ScheduleStatus.BLOCKED_EXPIRED) {
                            Button(
                                onClick = { onReplace(sched) },
                                modifier = Modifier
                                    .weight(1.4f)
                                    .testTag("replace_schedule_btn_${sched.id}")
                            ) {
                                Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.sched_action_replace), fontSize = 12.sp)
                            }
                        } else if (status == ScheduleStatus.ACTIVE) {
                            OutlinedButton(
                                onClick = { onPause(sched) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("pause_schedule_btn_${sched.id}")
                            ) {
                                Text(stringResource(R.string.sched_action_pause), fontSize = 12.sp)
                            }
                        } else if (status == ScheduleStatus.PAUSED) {
                            Button(
                                onClick = { onResume(sched) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("resume_schedule_btn_${sched.id}")
                            ) {
                                Text(stringResource(R.string.sched_action_resume), fontSize = 12.sp)
                            }
                        }

                        OutlinedButton(
                            onClick = { onEdit(sched) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("edit_schedule_btn_${sched.id}")
                        ) {
                            Text(stringResource(R.string.sched_action_edit), fontSize = 12.sp)
                        }

                        IconButton(
                            onClick = { onRemove(sched) },
                            modifier = Modifier.testTag("remove_schedule_btn_${sched.id}")
                        ) {
                            Icon(
                                Icons.Default.DeleteOutline,
                                contentDescription = stringResource(R.string.sched_action_remove),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryTab(
    allDoseRecords: List<DoseRecord>,
    todayIso: String
) {
    var dateFilter by remember { mutableStateOf("ALL") }
    var selectedMedicineFilter by remember { mutableStateOf<String?>(null) }

    val distinctMedicines = remember(allDoseRecords) {
        allDoseRecords.map { it.medicineName }.distinct().sorted()
    }

    val filteredRecords = remember(allDoseRecords, dateFilter, selectedMedicineFilter, todayIso) {
        val minDateIso = when (dateFilter) {
            "TODAY" -> todayIso
            "7DAYS" -> MedicationScheduleEngine.addDaysToIso(todayIso, -7)
            "30DAYS" -> MedicationScheduleEngine.addDaysToIso(todayIso, -30)
            else -> null
        }
        allDoseRecords.filter { rec ->
            val dateMatches = minDateIso == null || rec.scheduledDateIso >= minDateIso
            val medMatches = selectedMedicineFilter == null || rec.medicineName == selectedMedicineFilter
            dateMatches && medMatches
        }
    }

    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        // Date Filter Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val dateOptions = listOf(
                "ALL" to stringResource(R.string.sched_filter_all_dates),
                "TODAY" to stringResource(R.string.sched_filter_today),
                "7DAYS" to stringResource(R.string.sched_filter_7days),
                "30DAYS" to stringResource(R.string.sched_filter_30days)
            )
            dateOptions.forEach { (key, label) ->
                FilterChip(
                    selected = dateFilter == key,
                    onClick = { dateFilter = key },
                    label = { Text(label, fontSize = 12.sp) }
                )
            }
        }

        // Medicine Filter Row
        if (distinctMedicines.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedMedicineFilter == null,
                    onClick = { selectedMedicineFilter = null },
                    label = { Text(stringResource(R.string.sched_filter_all_meds), fontSize = 12.sp) }
                )
                distinctMedicines.forEach { medName ->
                    FilterChip(
                        selected = selectedMedicineFilter == medName,
                        onClick = { selectedMedicineFilter = medName },
                        label = { Text(medName, fontSize = 12.sp) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (filteredRecords.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.sched_empty_history_title),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.sched_empty_history_desc),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredRecords, key = { it.id }) { rec ->
                    val status = DoseStatus.fromKey(rec.status)
                    val (bg, fg, label) = when (status) {
                        DoseStatus.TAKEN -> Triple(Color(0xFFDCFCE7), Color(0xFF166534), stringResource(R.string.sched_dose_taken))
                        DoseStatus.SKIPPED -> Triple(Color(0xFFE0E7FF), Color(0xFF1E40AF), stringResource(R.string.sched_dose_skipped))
                        DoseStatus.MISSED -> Triple(Color(0xFFFEE2E2), Color(0xFF991B1B), stringResource(R.string.sched_dose_missed))
                        DoseStatus.BLOCKED -> Triple(Color(0xFFFEE2E2), Color(0xFF991B1B), stringResource(R.string.sched_dose_blocked))
                        DoseStatus.PENDING -> Triple(Color(0xFFFEF3C7), Color(0xFF92400E), stringResource(R.string.sched_dose_pending))
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = rec.medicineName,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                                Text(
                                    text = "${rec.scheduledDateIso} • ${rec.scheduledTimeHm} • ${formatAmount(rec.configuredDosage)} ${rec.dosageUnit}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (rec.actualTakenTimestampMillis != null && status == DoseStatus.TAKEN) {
                                    Text(
                                        text = "${stringResource(R.string.sched_dose_taken)}: ${timeFormatter.format(Date(rec.actualTakenTimestampMillis))}",
                                        fontSize = 11.sp,
                                        color = Color(0xFF166534)
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(bg)
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = label,
                                    color = fg,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryKpiPill(
    label: String,
    count: Int,
    bgColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .padding(vertical = 10.dp, horizontal = 8.dp)
    ) {
        Text(
            text = count.toString(),
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            color = textColor
        )
        Text(
            text = label,
            fontSize = 11.sp,
            color = textColor.copy(alpha = 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun formatAmount(value: Double): String {
    return if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
}
