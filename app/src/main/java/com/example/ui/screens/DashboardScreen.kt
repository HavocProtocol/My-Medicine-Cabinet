package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.ui.viewmodels.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onAdd: () -> Unit,
    onScan: () -> Unit,
    onCategories: () -> Unit,
    onPrint: () -> Unit,
    onSettings: () -> Unit,
    onAISearch: () -> Unit,
    onVoice: () -> Unit,
    onNotifications: () -> Unit,
    onTutorial: () -> Unit,
    viewModel: AppViewModel = viewModel()
) {
    val allMedicines by viewModel.allMedicines.collectAsState()
    val availableItems by viewModel.availableItems.collectAsState()
    val expired by viewModel.expiredItems.collectAsState()
    val unreadAlerts by viewModel.unreadAlerts.collectAsState()

    val totalCount = allMedicines.size
    val expiringSoonCount = availableItems.count { (it.expiryDate - viewModel.nowTime) <= 30L * 24 * 60 * 60 * 1000 }
    val expiredCount = expired.size

    var searchQuery by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onSettings,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                    .size(48.dp)
            ) {
                Icon(
                    Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.settings),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(id = R.drawable.custom_logo),
                    contentDescription = "App Logo",
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(R.string.app_name),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }

            Box(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                    .size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = onNotifications) {
                    Icon(
                        Icons.Outlined.Notifications,
                        contentDescription = stringResource(R.string.notifications),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (unreadAlerts.isNotEmpty()) {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                    ) {
                        Text(unreadAlerts.size.toString())
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Search bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            placeholder = {
                Text(
                    stringResource(R.string.search_placeholder),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            leadingIcon = {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = Color.Transparent
            ),
            shape = RoundedCornerShape(16.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Overview Section
        Text(
            stringResource(R.string.overview),
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(
                label = stringResource(R.string.total_medicines),
                value = totalCount,
                bgColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                icon = Icons.Outlined.Inventory2,
                modifier = Modifier.weight(1f)
            )
            StatCard(
                label = stringResource(R.string.expiring_soon),
                value = expiringSoonCount,
                bgColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                icon = Icons.Outlined.WarningAmber,
                modifier = Modifier.weight(1f)
            )
            StatCard(
                label = stringResource(R.string.expired_medicines),
                value = expiredCount,
                bgColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                icon = Icons.Outlined.ErrorOutline,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Quick Actions Section
        Text(
            stringResource(R.string.quick_actions),
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            QuickActionButton(
                label = stringResource(R.string.add_medicine),
                icon = Icons.Default.Add,
                bgColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                onClick = onAdd,
                modifier = Modifier.weight(1f)
            )
            QuickActionButton(
                label = stringResource(R.string.scan_barcode),
                icon = Icons.Outlined.QrCodeScanner,
                bgColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                onClick = onScan,
                modifier = Modifier.weight(1f),
                enabled = false
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            QuickActionButton(
                label = stringResource(R.string.categories),
                icon = Icons.Outlined.GridView,
                bgColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = onCategories,
                modifier = Modifier.weight(1f)
            )
            QuickActionButton(
                label = stringResource(R.string.ai_voice_search),
                icon = Icons.Outlined.Mic,
                bgColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                onClick = onVoice,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            QuickActionButton(
                label = stringResource(R.string.export_print),
                icon = Icons.Outlined.Print,
                bgColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = onPrint,
                modifier = Modifier.weight(1f)
            )
            QuickActionButton(
                label = stringResource(R.string.ai_search),
                icon = Icons.Outlined.AutoAwesome,
                bgColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = onAISearch,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            QuickActionButton(
                label = stringResource(R.string.tutorial_title),
                icon = Icons.Outlined.HelpOutline,
                bgColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                onClick = onTutorial,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
fun StatCard(
    label: String,
    value: Int,
    bgColor: Color,
    contentColor: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .aspectRatio(0.8f)
            .background(bgColor, RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.Start
        ) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(24.dp))
            Text(
                text = "$value",
                style = MaterialTheme.typography.headlineMedium.copy(color = contentColor, fontWeight = FontWeight.Bold)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(color = contentColor, fontWeight = FontWeight.Medium),
                maxLines = 2,
                lineHeight = 14.sp
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
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = bgColor,
            contentColor = contentColor,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(12.dp),
        modifier = modifier.height(100.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(contentColor.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(22.dp))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = label,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                color = contentColor,
                maxLines = 1
            )
        }
    }
}
