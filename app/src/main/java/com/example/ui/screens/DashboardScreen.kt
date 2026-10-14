package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.data.Medicine
import com.example.ui.viewmodels.AppViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onAdd: () -> Unit,
    onStatCardClick: (String) -> Unit = {},
    onScan: () -> Unit,
    onCategories: () -> Unit,
    onPrint: () -> Unit,
    onSettings: () -> Unit,
    onAISearch: () -> Unit,
    onVoice: () -> Unit,
    onNotifications: () -> Unit,
    onTutorial: () -> Unit,
    onMedicineClick: (Int) -> Unit = {},
    viewModel: AppViewModel = viewModel()
) {
    val allMedicines by viewModel.allMedicines.collectAsState()
    val availableItems by viewModel.availableItems.collectAsState()
    val expired by viewModel.expiredItems.collectAsState()
    val unreadAlerts by viewModel.unreadAlerts.collectAsState()

    val totalCount = allMedicines.size
    val thirtyDaysMillis = 30L * 24 * 60 * 60 * 1000
    val expiringSoonCount = availableItems.count { (it.expiryDate - viewModel.nowTime) <= thirtyDaysMillis }
    val expiredCount = expired.size
    val context = LocalContext.current
    val unavailableMessage = stringResource(R.string.feature_unavailable)

    var searchQuery by remember { mutableStateOf("") }
    val dateFormatter = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }

    val itemsByMedicineId = remember(availableItems, expired) {
        (availableItems + expired).groupBy { it.medicineId }
    }

    val filteredMedicines = remember(allMedicines, searchQuery) {
        if (searchQuery.isBlank()) {
            allMedicines
        } else {
            allMedicines.filter { med ->
                med.tradeName.contains(searchQuery, ignoreCase = true) ||
                        (med.scientificName?.contains(searchQuery, ignoreCase = true) == true)
            }
        }
    }

    var menuExpanded by remember { mutableStateOf(false) }
    BackHandler(enabled = menuExpanded) { menuExpanded = false }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            // Top Header
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
                            .size(46.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                shape = RoundedCornerShape(14.dp)
                            )
                            .padding(5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.custom_logo),
                            contentDescription = "App Logo",
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = stringResource(R.string.app_name),
                            color = MaterialTheme.colorScheme.onBackground,
                            fontWeight = FontWeight.Bold,
                            fontSize = 19.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResource(R.string.app_subtitle),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Normal
                        )
                    }
                }

                // Menu button (three horizontal lines) at top right in LTR / top left in RTL
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Icon(
                            Icons.Default.Menu,
                            contentDescription = stringResource(R.string.menu),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    if (unreadAlerts.isNotEmpty()) {
                        Badge(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(2.dp)
                        ) {
                            Text(unreadAlerts.size.toString(), fontSize = 10.sp)
                        }
                    }
                }
            }

        Spacer(modifier = Modifier.height(18.dp))

        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            placeholder = {
                Text(
                    stringResource(R.string.search_placeholder),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    fontSize = 14.sp
                )
            },
            leadingIcon = {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(
                            Icons.Default.Clear,
                            contentDescription = "Clear",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
            ),
            shape = RoundedCornerShape(16.dp),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(18.dp))

        // Cabinet Health Status Hero Card
        CabinetHealthHeroCard(
            totalCount = totalCount,
            expiringSoonCount = expiringSoonCount,
            expiredCount = expiredCount
        )

        Spacer(modifier = Modifier.height(22.dp))

        // Overview KPI Section
        Text(
            text = stringResource(R.string.overview),
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatCard(
                label = stringResource(R.string.total_medicines),
                value = totalCount,
                bgColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                icon = Icons.Outlined.Inventory2,
                iconBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                modifier = Modifier
                    .weight(1f)
                    .clickable { onStatCardClick("total") }
            )
            StatCard(
                label = stringResource(R.string.expiring_soon),
                value = expiringSoonCount,
                bgColor = Color(0xFFFEF3C7),
                contentColor = Color(0xFF92400E),
                icon = Icons.Outlined.WarningAmber,
                iconBg = Color(0xFFF59E0B).copy(alpha = 0.2f),
                modifier = Modifier
                    .weight(1f)
                    .clickable { onStatCardClick("expiring") }
            )
            StatCard(
                label = stringResource(R.string.expired_medicines),
                value = expiredCount,
                bgColor = Color(0xFFFEE2E2),
                contentColor = Color(0xFF991B1B),
                icon = Icons.Outlined.ErrorOutline,
                iconBg = Color(0xFFEF4444).copy(alpha = 0.2f),
                modifier = Modifier
                    .weight(1f)
                    .clickable { onStatCardClick("expired") }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Quick Actions Section
        Text(
            text = stringResource(R.string.quick_actions),
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )
        Spacer(modifier = Modifier.height(12.dp))

        // Row 1
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            QuickActionButton(
                label = stringResource(R.string.add_medicine),
                icon = Icons.Default.Add,
                bgColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                onClick = onAdd,
                modifier = Modifier.weight(1f),
                isHighlighted = true
            )
            QuickActionButton(
                label = stringResource(R.string.find_pharmacy),
                icon = Icons.Filled.LocalPharmacy,
                bgColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                onClick = {
                    val gmmIntentUri = Uri.parse("geo:0,0?q=pharmacy")
                    val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
                    mapIntent.setPackage("com.google.android.apps.maps")
                    try {
                        context.startActivity(mapIntent)
                    } catch (_: Exception) {
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/pharmacy"))
                        context.startActivity(browserIntent)
                    }
                },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Tutorial / Help Guide Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onTutorial() },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.HelpOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.tutorial_title),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.tutorial_desc),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForwardIos,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.size(13.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // Saved Medicines Section
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.recent_cabinet_medicines),
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            TextButton(onClick = { onStatCardClick("total") }) {
                Text(
                    text = stringResource(R.string.view_all),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (filteredMedicines.isEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Outlined.MedicalServices,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.no_medicines_yet),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.add_first_med_prompt),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Button(
                        onClick = onAdd,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.add_medicine))
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                filteredMedicines.take(6).forEach { med ->
                    val minExpiryDate = itemsByMedicineId[med.id]?.minOfOrNull { it.expiryDate }
                    MedicinePreviewCard(
                        medicine = med,
                        expiryDate = minExpiryDate,
                        nowTime = viewModel.nowTime,
                        dateFormatter = dateFormatter,
                        onClick = { onMedicineClick(med.id) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }

    // Modern Full-Screen Blurred Menu Overlay
    AnimatedVisibility(
        visible = menuExpanded,
        enter = fadeIn(animationSpec = tween(220)) + slideInVertically(
            initialOffsetY = { -it / 6 },
            animationSpec = tween(260)
        ),
        exit = fadeOut(animationSpec = tween(180)) + slideOutVertically(
            targetOffsetY = { -it / 6 },
            animationSpec = tween(200)
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.background.copy(alpha = 0.94f),
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)
                        )
                    )
                )
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                // Modern Menu Top Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Menu,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.menu),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }

                    // Close "X" Button
                    IconButton(
                        onClick = { menuExpanded = false },
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f))
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.close),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 1. NOTIFICATIONS & SETTINGS (relocated inside menu)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Notifications Card
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                menuExpanded = false
                                onNotifications()
                            },
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (unreadAlerts.isNotEmpty())
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                            else
                                MaterialTheme.colorScheme.surface
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (unreadAlerts.isNotEmpty())
                                MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
                            else
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (unreadAlerts.isNotEmpty())
                                                MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                                            else
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Outlined.Notifications,
                                        contentDescription = null,
                                        tint = if (unreadAlerts.isNotEmpty())
                                            MaterialTheme.colorScheme.error
                                        else
                                            MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                if (unreadAlerts.isNotEmpty()) {
                                    Badge(
                                        containerColor = MaterialTheme.colorScheme.error,
                                        contentColor = MaterialTheme.colorScheme.onError
                                    ) {
                                        Text(unreadAlerts.size.toString(), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.notifications),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (unreadAlerts.isNotEmpty())
                                    stringResource(R.string.notifications_unread, unreadAlerts.size)
                                else
                                    stringResource(R.string.notifications_all_caught_up),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Settings Card
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                menuExpanded = false
                                onSettings()
                            },
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Outlined.Settings,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.settings),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.menu_preferences),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 2. CABINET & TOOLS (Categories and Export & Print moved here)
                Text(
                    text = stringResource(R.string.quick_actions),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                ) {
                    Column {
                        // Categories (Moved from main page Quick Actions)
                        ModernMenuItemRow(
                            icon = Icons.Outlined.GridView,
                            iconTint = MaterialTheme.colorScheme.primary,
                            title = stringResource(R.string.categories),
                            onClick = {
                                menuExpanded = false
                                onCategories()
                            }
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )

                        // Export & Print (Moved from main page Quick Actions)
                        ModernMenuItemRow(
                            icon = Icons.Outlined.Print,
                            iconTint = MaterialTheme.colorScheme.primary,
                            title = stringResource(R.string.export_print),
                            onClick = {
                                menuExpanded = false
                                onPrint()
                            }
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )

                        // Add Medicine
                        ModernMenuItemRow(
                            icon = Icons.Default.Add,
                            iconTint = MaterialTheme.colorScheme.primary,
                            title = stringResource(R.string.add_medicine),
                            onClick = {
                                menuExpanded = false
                                onAdd()
                            }
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )

                        // Find Pharmacy
                        ModernMenuItemRow(
                            icon = Icons.Filled.LocalPharmacy,
                            iconTint = MaterialTheme.colorScheme.secondary,
                            title = stringResource(R.string.find_pharmacy),
                            onClick = {
                                menuExpanded = false
                                val gmmIntentUri = Uri.parse("geo:0,0?q=pharmacy")
                                val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
                                mapIntent.setPackage("com.google.android.apps.maps")
                                try {
                                    context.startActivity(mapIntent)
                                } catch (_: Exception) {
                                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/pharmacy"))
                                    context.startActivity(browserIntent)
                                }
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 3. AI & SMART FEATURES
                Text(
                    text = stringResource(R.string.menu_ai_tools),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                ) {
                    Column {
                        // AI Voice Search (Unavailable)
                        ModernMenuItemRow(
                            icon = Icons.Outlined.Mic,
                            iconTint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                            title = stringResource(R.string.ai_voice_search),
                            badgeText = stringResource(R.string.unavailable_badge),
                            enabled = false,
                            onClick = {
                                Toast.makeText(context, unavailableMessage, Toast.LENGTH_SHORT).show()
                            }
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )

                        // Scan Barcode (Unavailable)
                        ModernMenuItemRow(
                            icon = Icons.Outlined.QrCodeScanner,
                            iconTint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                            title = stringResource(R.string.scan_barcode),
                            badgeText = stringResource(R.string.unavailable_badge),
                            enabled = false,
                            onClick = {
                                Toast.makeText(context, unavailableMessage, Toast.LENGTH_SHORT).show()
                            }
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )

                        // AI Search (Unavailable)
                        ModernMenuItemRow(
                            icon = Icons.Outlined.AutoAwesome,
                            iconTint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                            title = stringResource(R.string.ai_search),
                            badgeText = stringResource(R.string.unavailable_badge),
                            enabled = false,
                            onClick = {
                                Toast.makeText(context, unavailableMessage, Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 4. TUTORIAL & HELP GUIDE
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            menuExpanded = false
                            onTutorial()
                        },
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.AutoMirrored.Outlined.HelpOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.tutorial_title),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.tutorial_desc),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForwardIos,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(30.dp))
            }
        }
    }
}
}

@Composable
private fun ModernMenuItemRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    badgeText: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (enabled) iconTint.copy(alpha = 0.12f)
                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = title,
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.weight(1f)
        )
        if (badgeText != null) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = badgeText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }
        Icon(
            Icons.AutoMirrored.Filled.ArrowForwardIos,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 0.4f else 0.2f),
            modifier = Modifier.size(13.dp)
        )
    }
}

@Composable
fun CabinetHealthHeroCard(
    totalCount: Int,
    expiringSoonCount: Int,
    expiredCount: Int
) {
    val gradientBrush = Brush.horizontalGradient(
        colors = listOf(
            Color(0xFF0284C7),
            Color(0xFF06B6D4)
        )
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(gradientBrush)
                .padding(18.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.Shield,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.cabinet_status_title),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }

                    // Status Badge Chip
                    val (statusText, statusBg) = when {
                        expiredCount > 0 -> Pair(
                            stringResource(R.string.cabinet_need_attention),
                            Color(0xFFDC2626)
                        )
                        expiringSoonCount > 0 -> Pair(
                            stringResource(R.string.cabinet_need_attention),
                            Color(0xFFD97706)
                        )
                        else -> Pair(
                            stringResource(R.string.cabinet_all_safe),
                            Color(0xFF059669)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(statusBg)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (totalCount == 0) stringResource(R.string.no_medicines_yet) else statusText,
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    HeroStatusMetric(
                        label = stringResource(R.string.total_medicines),
                        value = totalCount.toString(),
                        textColor = Color.White
                    )
                    HeroStatusMetric(
                        label = stringResource(R.string.expiring_soon),
                        value = expiringSoonCount.toString(),
                        textColor = if (expiringSoonCount > 0) Color(0xFFFDE68A) else Color.White
                    )
                    HeroStatusMetric(
                        label = stringResource(R.string.expired_medicines),
                        value = expiredCount.toString(),
                        textColor = if (expiredCount > 0) Color(0xFFFECACA) else Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun HeroStatusMetric(label: String, value: String, textColor: Color) {
    Column {
        Text(
            text = value,
            color = textColor,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.82f),
            fontSize = 11.sp
        )
    }
}

@Composable
fun StatCard(
    label: String,
    value: Int,
    bgColor: Color,
    contentColor: Color,
    icon: ImageVector,
    iconBg: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(iconBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(19.dp))
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "$value",
                style = MaterialTheme.typography.titleLarge.copy(
                    color = contentColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp
                )
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = contentColor.copy(alpha = 0.9f),
                    fontWeight = FontWeight.Medium
                ),
                maxLines = 2,
                lineHeight = 13.sp
            )
        }
    }
}

@Composable
fun QuickActionButton(
    label: String,
    icon: ImageVector,
    bgColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isUnavailable: Boolean = false,
    isHighlighted: Boolean = false
) {
    val context = LocalContext.current
    val unavailableMessage = stringResource(R.string.feature_unavailable)

    val effectiveBgColor = if (isUnavailable) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    } else {
        bgColor
    }

    val effectiveContentColor = if (isUnavailable) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    } else {
        contentColor
    }

    val effectiveIconBg = if (isUnavailable) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f)
    } else if (isHighlighted) {
        Color.White.copy(alpha = 0.2f)
    } else {
        contentColor.copy(alpha = 0.12f)
    }

    Card(
        modifier = modifier
            .height(88.dp)
            .clickable(enabled = enabled) {
                if (isUnavailable) {
                    Toast.makeText(context, unavailableMessage, Toast.LENGTH_SHORT).show()
                } else {
                    onClick()
                }
            },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = effectiveBgColor),
        border = if (isUnavailable) {
            androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            )
        } else if (!isHighlighted) {
            androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
            )
        } else null,
        elevation = if (isHighlighted) CardDefaults.cardElevation(2.dp) else CardDefaults.cardElevation(0.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(effectiveIconBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = effectiveContentColor,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = label,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                color = effectiveContentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun MedicinePreviewCard(
    medicine: Medicine,
    expiryDate: Long?,
    nowTime: Long,
    dateFormatter: SimpleDateFormat,
    onClick: () -> Unit
) {
    val thirtyDaysMillis = 30L * 24 * 60 * 60 * 1000

    val (badgeText, badgeBg, badgeColor) = when {
        expiryDate == null -> Triple("—", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        expiryDate < nowTime -> Triple(stringResource(R.string.expired_badge), Color(0xFFFEE2E2), Color(0xFF991B1B))
        (expiryDate - nowTime) <= thirtyDaysMillis -> {
            val days = ((expiryDate - nowTime) / (24 * 60 * 60 * 1000)).toInt().coerceAtLeast(0)
            Triple("$days d", Color(0xFFFEF3C7), Color(0xFF92400E))
        }
        else -> Triple(stringResource(R.string.valid_badge), Color(0xFFD1FAE5), Color(0xFF065F46))
    }

    val formIcon = when (medicine.form.lowercase().trim()) {
        "tablets", "أقراص", "capsules", "كبسولات", "comprimés", "gélules", "tabletten", "kapseln" -> Icons.Outlined.Medication
        "syrup", "شراب", "sirop", "sirup / saft", "sirup", "saft" -> Icons.Outlined.WaterDrop
        "cream", "مرهم / كريم", "crème / pommade", "crème", "creme / salbe", "creme", "salbe" -> Icons.Outlined.Healing
        "spray", "بخاخ" -> Icons.Outlined.Air
        "drops", "قطرات", "gouttes", "tropfen" -> Icons.Outlined.Opacity
        "injections", "حقن", "injektionen" -> Icons.Outlined.Vaccines
        else -> Icons.Outlined.MedicalServices
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    formIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = medicine.tradeName,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!medicine.scientificName.isNullOrBlank()) {
                    Text(
                        text = medicine.scientificName,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(horizontalAlignment = Alignment.End) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(badgeBg)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = badgeText,
                        color = badgeColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
                if (expiryDate != null) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = dateFormatter.format(Date(expiryDate)),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}
