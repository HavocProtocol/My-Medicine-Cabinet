package com.example.ui.screens

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.services.LocalLlmConfig
import com.example.services.LocalLlmService
import com.example.ui.theme.LocalOnThemeChanged
import com.example.ui.theme.LocalThemeMode
import com.example.ui.viewmodels.AppViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToTutorial: () -> Unit,
    onNavigateToPremium: () -> Unit = {},
    onNavigateToHelpCenter: () -> Unit = {},
    viewModel: AppViewModel = viewModel()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val syncStatus by viewModel.syncStatus.collectAsState()
    val warningDays by viewModel.expiryWarningDays.collectAsState()
    val isPremium by viewModel.isPremiumSubscribed.collectAsState()

    var warningDaysInput by remember(warningDays) { mutableStateOf(warningDays.toString()) }
    var languageExpanded by remember { mutableStateOf(false) }
    var themeExpanded by remember { mutableStateOf(false) }

    val currentThemeMode = LocalThemeMode.current
    val onThemeChanged = LocalOnThemeChanged.current

    var hostUrl by remember { mutableStateOf(LocalLlmConfig.getHostUrl(context)) }
    var modelName by remember { mutableStateOf(LocalLlmConfig.getModelName(context)) }
    var isTestingLlmConnection by remember { mutableStateOf(false) }
    var llmConnectionStatusMessage by remember { mutableStateOf<String?>(null) }
    var llmConnectionSucceeded by remember { mutableStateOf<Boolean?>(null) }
    val localLlmService = remember { LocalLlmService() }

    val appCompatLocales = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    val currentLocale = if (appCompatLocales.isNotEmpty()) appCompatLocales else context.resources.configuration.locales[0].language

    val currentLangLabel = when {
        currentLocale.startsWith("en") -> "English"
        currentLocale.startsWith("fr") -> "Français"
        currentLocale.startsWith("de") -> "Deutsch"
        else -> "العربية"
    }

    val themeLabel = when (currentThemeMode) {
        1 -> stringResource(R.string.theme_light)
        2 -> stringResource(R.string.theme_dark)
        else -> stringResource(R.string.theme_system)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                ),
                title = { Text(stringResource(R.string.settings), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Near-Expiration Warning Period Configuration Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_expiry_warning_days_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.AccessTime,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(34.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.settings_warning_period_title),
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                            Text(
                                text = stringResource(R.string.settings_warning_period_desc, warningDays),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                )
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(3, 7, 14, 30).forEach { preset ->
                            FilterChip(
                                selected = warningDays == preset,
                                onClick = {
                                    warningDaysInput = preset.toString()
                                    viewModel.setExpiryWarningDays(preset)
                                },
                                label = { Text(stringResource(R.string.settings_preset_days, preset)) },
                                modifier = Modifier.testTag("expiry_warning_preset_$preset")
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = warningDaysInput,
                            onValueChange = { txt ->
                                if (txt.all { it.isDigit() }) {
                                    warningDaysInput = txt
                                    txt.toIntOrNull()?.takeIf { it in 1..365 }?.let { validDays ->
                                        viewModel.setExpiryWarningDays(validDays)
                                    }
                                }
                            },
                            label = { Text(stringResource(R.string.settings_warning_days_input_label)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("settings_expiry_warning_days_input")
                        )
                        Button(
                            onClick = {
                                val parsed = warningDaysInput.toIntOrNull()?.coerceIn(1, 365) ?: 7
                                warningDaysInput = parsed.toString()
                                viewModel.setExpiryWarningDays(parsed)
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.testTag("save_expiry_warning_days_button")
                        ) {
                            Text(stringResource(R.string.save))
                        }
                    }
                }
            }

            // Theme Mode Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_theme_mode_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.DarkMode,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                stringResource(R.string.theme_mode),
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                            Text(
                                themeLabel,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                ),
                                modifier = Modifier.testTag("settings_current_theme_label")
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Button(
                            shape = RoundedCornerShape(12.dp),
                            onClick = { themeExpanded = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_theme_dropdown_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text(
                                "${stringResource(R.string.theme_mode)}: $themeLabel",
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                        DropdownMenu(
                            expanded = themeExpanded,
                            onDismissRequest = { themeExpanded = false },
                            modifier = Modifier.fillMaxWidth(0.85f)
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.theme_system)) },
                                onClick = {
                                    LocalLlmConfig.setThemeMode(context, 0)
                                    onThemeChanged(0)
                                    themeExpanded = false
                                },
                                modifier = Modifier.testTag("settings_theme_option_system")
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.theme_light)) },
                                onClick = {
                                    LocalLlmConfig.setThemeMode(context, 1)
                                    onThemeChanged(1)
                                    themeExpanded = false
                                },
                                modifier = Modifier.testTag("settings_theme_option_light")
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.theme_dark)) },
                                onClick = {
                                    LocalLlmConfig.setThemeMode(context, 2)
                                    onThemeChanged(2)
                                    themeExpanded = false
                                },
                                modifier = Modifier.testTag("settings_theme_option_dark")
                            )
                        }
                    }
                }
            }

            // Language Selection Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Language,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                stringResource(R.string.language),
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                            Text(
                                "${stringResource(R.string.change_language)}: $currentLangLabel",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Button(
                            shape = RoundedCornerShape(12.dp),
                            onClick = { languageExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text(
                                "${stringResource(R.string.change_language)} ($currentLangLabel)",
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                        DropdownMenu(
                            expanded = languageExpanded,
                            onDismissRequest = { languageExpanded = false },
                            modifier = Modifier.fillMaxWidth(0.85f)
                        ) {
                            DropdownMenuItem(
                                text = { Text("العربية (Arabic)") },
                                onClick = { changeLanguage("ar"); languageExpanded = false }
                            )
                            DropdownMenuItem(
                                text = { Text("English") },
                                onClick = { changeLanguage("en"); languageExpanded = false }
                            )
                            DropdownMenuItem(
                                text = { Text("Français (French)") },
                                onClick = { changeLanguage("fr"); languageExpanded = false }
                            )
                            DropdownMenuItem(
                                text = { Text("Deutsch (German)") },
                                onClick = { changeLanguage("de"); languageExpanded = false }
                            )
                        }
                    }
                }
            }

            // Local LLM Configuration Card (Governed by Unified Premium Entitlement)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_local_llm_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isPremium)
                        MaterialTheme.colorScheme.surfaceVariant
                    else
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                ),
                border = BorderStroke(
                    1.dp,
                    if (isPremium)
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                    else
                        Color(0xFFF59E0B).copy(alpha = 0.6f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Outlined.Dns,
                                contentDescription = null,
                                tint = if (isPremium) MaterialTheme.colorScheme.primary else Color(0xFFD97706),
                                modifier = Modifier.size(34.dp)
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Column {
                                Text(
                                    stringResource(R.string.local_llm_settings),
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                                Text(
                                    stringResource(R.string.local_llm_desc),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isPremium) Color(0xFFDCFCE7) else Color(0xFFFEF3C7))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .testTag("settings_local_llm_entitlement_badge")
                        ) {
                            Text(
                                text = if (isPremium) stringResource(R.string.badge_included_in_premium) else stringResource(R.string.badge_premium),
                                fontSize = 10.sp,
                                color = if (isPremium) Color(0xFF166534) else Color(0xFFB45309),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = hostUrl,
                        onValueChange = {
                            hostUrl = it
                            LocalLlmConfig.saveConfig(context, hostUrl, modelName)
                        },
                        enabled = isPremium,
                        label = { Text(stringResource(R.string.local_llm_host)) },
                        placeholder = { Text("http://10.0.2.2:11434") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_local_llm_host_input"),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = modelName,
                        onValueChange = {
                            modelName = it
                            LocalLlmConfig.saveConfig(context, hostUrl, modelName)
                        },
                        enabled = isPremium,
                        label = { Text(stringResource(R.string.local_llm_model)) },
                        placeholder = { Text("llama3") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_local_llm_model_input"),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    if (isPremium) {
                        Button(
                            shape = RoundedCornerShape(12.dp),
                            onClick = {
                                LocalLlmConfig.saveConfig(context, hostUrl, modelName)
                                isTestingLlmConnection = true
                                llmConnectionStatusMessage = null
                                coroutineScope.launch {
                                    val connected = localLlmService.checkConnection(hostUrl)
                                    llmConnectionSucceeded = connected
                                    llmConnectionStatusMessage = if (connected) {
                                        context.getString(R.string.connection_success)
                                    } else {
                                        context.getString(R.string.connection_failed)
                                    }
                                    isTestingLlmConnection = false
                                }
                            },
                            enabled = !isTestingLlmConnection,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_local_llm_test_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            if (isTestingLlmConnection) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(
                                stringResource(R.string.test_connection),
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }

                        if (!llmConnectionStatusMessage.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = llmConnectionStatusMessage ?: "",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (llmConnectionSucceeded == true) Color(0xFF15803D) else MaterialTheme.colorScheme.error,
                                modifier = Modifier.testTag("settings_local_llm_status_text")
                            )
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.settings_local_llm_included_notice),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            shape = RoundedCornerShape(12.dp),
                            onClick = onNavigateToPremium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_local_llm_upgrade_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFD97706)
                            )
                        ) {
                            Icon(
                                Icons.Default.WorkspacePremium,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.settings_upgrade_unlock_llm_btn),
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Tutorial Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.AutoMirrored.Outlined.HelpOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                stringResource(R.string.tutorial_title),
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                            Text(
                                stringResource(R.string.tutorial_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Button(
                        shape = RoundedCornerShape(12.dp),
                        onClick = onNavigateToTutorial,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text(stringResource(R.string.tutorial_title), color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }

            // Offline Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.OfflinePin,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            stringResource(R.string.offline_mode_active),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                        Text(
                            stringResource(R.string.offline_mode_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                        )
                    }
                }
            }

            // Cloud Sync Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CloudSync,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                stringResource(R.string.cloud_sync),
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            )
                            Text(
                                stringResource(R.string.cloud_sync_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    val syncStatusText = when (syncStatus) {
                        com.example.ui.viewmodels.SyncStatus.IDLE -> stringResource(R.string.sync_status_idle)
                        com.example.ui.viewmodels.SyncStatus.SYNCING -> stringResource(R.string.sync_status_syncing)
                        com.example.ui.viewmodels.SyncStatus.SUCCESS -> stringResource(R.string.sync_status_success)
                        com.example.ui.viewmodels.SyncStatus.ERROR -> stringResource(R.string.sync_status_error)
                    }
                    Text(
                        text = "${stringResource(R.string.sync_status)} $syncStatusText",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        shape = RoundedCornerShape(12.dp),
                        onClick = { viewModel.syncWithCloud() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Text(stringResource(R.string.sync_now), color = MaterialTheme.colorScheme.onSecondary)
                    }
                }
            }
        }
    }
}

private fun changeLanguage(languageTag: String) {
    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageTag))
}
