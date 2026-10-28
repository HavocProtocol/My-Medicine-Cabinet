package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
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
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    onBack: () -> Unit,
    viewModel: AppViewModel = viewModel()
) {
    val alerts by viewModel.unreadAlerts.collectAsState()
    val dateFormatter = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

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
                title = { Text(stringResource(R.string.notifications_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    if (alerts.isNotEmpty()) {
                        IconButton(onClick = { viewModel.markAllAlertsAsRead() }) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = stringResource(R.string.mark_all_read)
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (alerts.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.no_notifications),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(alerts) { alertWithMed ->
                        val isExpired = alertWithMed.alert.type == "EXPIRED" ||
                            alertWithMed.alert.type == "SCHEDULE_EXPIRED_BLOCKED"
                        val cardBg = if (isExpired) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
                        val contentColor = if (isExpired) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer

                        Card(
                            colors = CardDefaults.cardColors(containerColor = cardBg),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (isExpired) Icons.Default.Warning else Icons.Default.Notifications,
                                        contentDescription = null,
                                        tint = contentColor
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = when (alertWithMed.alert.type) {
                                                "SCHEDULE_EXPIRED_BLOCKED" -> stringResource(R.string.sched_status_blocked_expired)
                                                "EXPIRED" -> stringResource(R.string.expired_alert_title)
                                                else -> stringResource(R.string.expiring_soon_alert_title)
                                            },
                                            fontWeight = FontWeight.Bold,
                                            color = contentColor
                                        )
                                        Text(
                                            text = "${stringResource(R.string.medicine_label)} ${alertWithMed.tradeName}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = contentColor
                                        )
                                        Text(
                                            text = "${stringResource(R.string.date_label)} ${dateFormatter.format(Date(alertWithMed.alert.triggerDate))}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = contentColor.copy(alpha = 0.7f)
                                        )
                                    }
                                }
                                IconButton(onClick = { viewModel.markAlertAsRead(alertWithMed.alert.id) }) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = stringResource(R.string.mark_all_read),
                                        tint = contentColor
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
