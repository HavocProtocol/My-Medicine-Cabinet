package com.example.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.WorkspacePremium
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.R
import com.example.services.LocalLlmConfig
import com.example.services.LocalLlmDiagnosticException
import com.example.services.LocalLlmService
import com.example.services.OnlineMedicineSearchGate
import com.example.services.PremiumAccessRequiredException
import com.example.services.PremiumEntitlementManager
import com.example.services.PremiumFeature
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class VoiceSearchUiState {
    IDLE,
    LISTENING,
    PROCESSING,
    RESULT,
    PERMISSION_DENIED,
    SPEECH_UNAVAILABLE,
    EMPTY_QUERY,
    ERROR
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun AISearchScreen(
    onBack: () -> Unit,
    onNavigateToPremium: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val isPremium by LocalLlmConfig.premiumSubscribedFlow.collectAsState()
    val micPermissionState = rememberPermissionState(Manifest.permission.RECORD_AUDIO)

    var query by remember { mutableStateOf("") }
    var responseText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var useLocalLlm by remember { mutableStateOf(false) }
    var showPaywallDialog by remember { mutableStateOf(false) }
    var voiceUiState by remember { mutableStateOf(VoiceSearchUiState.IDLE) }
    var voiceStatusMessage by remember { mutableStateOf<String?>(null) }
    var speechRecognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }

    val coroutineScope = rememberCoroutineScope()
    val localLlmService = remember { LocalLlmService() }
    val hostUrl = remember(isPremium) { LocalLlmConfig.getHostUrl(context) }
    val modelName = remember(isPremium) { LocalLlmConfig.getModelName(context) }

    DisposableEffect(Unit) {
        onDispose {
            speechRecognizer?.destroy()
        }
    }

    fun executeAiSearch(inputQuery: String, triggeredByVoice: Boolean = false) {
        val trimmed = inputQuery.trim()
        if (trimmed.isEmpty()) {
            voiceUiState = VoiceSearchUiState.EMPTY_QUERY
            voiceStatusMessage = context.getString(R.string.voice_listening_indicator)
            return
        }

        val requiredFeature = when {
            triggeredByVoice -> PremiumFeature.AI_VOICE_SEARCH
            useLocalLlm -> PremiumFeature.LOCAL_LLM
            else -> PremiumFeature.ONLINE_MEDICINE_SEARCH
        }

        if (!PremiumEntitlementManager.isEntitled(context, requiredFeature)) {
            showPaywallDialog = true
            return
        }

        isLoading = true
        voiceUiState = VoiceSearchUiState.PROCESSING
        voiceStatusMessage = if (useLocalLlm) {
            context.getString(R.string.ai_voice_processing_status)
        } else {
            context.getString(R.string.searching_online)
        }
        responseText = ""

        coroutineScope.launch {
            if (useLocalLlm) {
                val res = localLlmService.generateResponseForUser(
                    context = context,
                    hostUrl = hostUrl,
                    modelName = modelName,
                    prompt = trimmed
                )
                res.onSuccess { reply ->
                    responseText = reply
                    voiceUiState = VoiceSearchUiState.RESULT
                    voiceStatusMessage = context.getString(R.string.ai_voice_result_prefix, trimmed)
                }.onFailure { err ->
                    voiceUiState = VoiceSearchUiState.ERROR
                    responseText = when (err) {
                        is LocalLlmDiagnosticException -> err.userFriendlyTitle + ": " + (err.message ?: "")
                        else -> "${context.getString(R.string.connection_failed)}: ${err.message}"
                    }
                    voiceStatusMessage = responseText
                }
            } else {
                try {
                    val result = performGeminiSearch(trimmed, context)
                    responseText = result
                    if (result.startsWith("Error") || result.startsWith(context.getString(R.string.connection_failed))) {
                        voiceUiState = VoiceSearchUiState.ERROR
                        voiceStatusMessage = result
                    } else {
                        voiceUiState = VoiceSearchUiState.RESULT
                        voiceStatusMessage = context.getString(R.string.ai_voice_result_prefix, trimmed)
                    }
                } catch (e: PremiumAccessRequiredException) {
                    showPaywallDialog = true
                    voiceUiState = VoiceSearchUiState.IDLE
                    voiceStatusMessage = null
                } catch (e: Exception) {
                    voiceUiState = VoiceSearchUiState.ERROR
                    responseText = "${context.getString(R.string.connection_failed)}: ${e.message}"
                    voiceStatusMessage = responseText
                }
            }
            isLoading = false
        }
    }

    fun startVoiceSearch() {
        if (!PremiumEntitlementManager.isEntitled(context, PremiumFeature.AI_VOICE_SEARCH)) {
            showPaywallDialog = true
            return
        }

        if (!micPermissionState.status.isGranted) {
            voiceUiState = VoiceSearchUiState.PERMISSION_DENIED
            voiceStatusMessage = context.getString(R.string.mic_permission_needed)
            micPermissionState.launchPermissionRequest()
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            voiceUiState = VoiceSearchUiState.SPEECH_UNAVAILABLE
            voiceStatusMessage = context.getString(R.string.connection_failed)
            return
        }

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    voiceUiState = VoiceSearchUiState.LISTENING
                    voiceStatusMessage = context.getString(R.string.ai_voice_listening_status)
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    voiceUiState = VoiceSearchUiState.PROCESSING
                    voiceStatusMessage = context.getString(R.string.ai_voice_processing_status)
                }

                override fun onError(error: Int) {
                    val reason = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                            voiceUiState = VoiceSearchUiState.EMPTY_QUERY
                            context.getString(R.string.tap_to_speak)
                        }
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                            voiceUiState = VoiceSearchUiState.ERROR
                            context.getString(R.string.connection_failed)
                        }
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                            voiceUiState = VoiceSearchUiState.PERMISSION_DENIED
                            context.getString(R.string.mic_permission_needed)
                        }
                        else -> {
                            voiceUiState = VoiceSearchUiState.ERROR
                            "${context.getString(R.string.connection_failed)} ($error)"
                        }
                    }
                    voiceStatusMessage = reason
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val spokenText = matches?.firstOrNull()?.trim().orEmpty()
                    if (spokenText.isEmpty()) {
                        voiceUiState = VoiceSearchUiState.EMPTY_QUERY
                        voiceStatusMessage = context.getString(R.string.tap_to_speak)
                    } else {
                        query = spokenText
                        executeAiSearch(spokenText, triggeredByVoice = true)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        }
        speechRecognizer?.startListening(intent)
    }

    if (showPaywallDialog && !isPremium) {
        AlertDialog(
            onDismissRequest = { showPaywallDialog = false },
            icon = {
                Icon(
                    Icons.Default.WorkspacePremium,
                    contentDescription = null,
                    tint = Color(0xFFD97706),
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.ai_paywall_card_title),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.ai_paywall_card_desc),
                        fontSize = 14.sp
                    )
                    Text(
                        text = "• ${stringResource(R.string.ai_dialog_point_1)}\n• ${stringResource(R.string.ai_dialog_point_2)}\n• ${stringResource(R.string.ai_dialog_point_3)}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPaywallDialog = false
                        if (onNavigateToPremium != null) {
                            onNavigateToPremium()
                        } else {
                            PremiumEntitlementManager.activateSubscription(context)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                    modifier = Modifier.testTag("dialog_upgrade_to_premium_button")
                ) {
                    Text(stringResource(R.string.ai_upgrade_to_premium_btn), color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPaywallDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
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
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.ai_search_title),
                            fontWeight = FontWeight.Bold
                        )
                        Surface(
                            color = if (isPremium) Color(0xFFDCFCE7) else Color(0xFFFEF3C7),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("ai_search_premium_badge")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = if (isPremium) Icons.Default.WorkspacePremium else Icons.Default.Lock,
                                    contentDescription = if (isPremium) stringResource(R.string.dashboard_premium_active) else stringResource(R.string.ai_dialog_sub),
                                    tint = if (isPremium) Color(0xFF166534) else Color(0xFFB45309),
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = if (isPremium) stringResource(R.string.badge_premium) else stringResource(R.string.badge_pro),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (isPremium) Color(0xFF166534) else Color(0xFFB45309)
                                )
                            }
                        }
                    }
                },
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
                .padding(16.dp)
        ) {
            Text(
                stringResource(R.string.ai_search_subtitle),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Toggle Online Cloud AI (Premium) vs Local LLM (Premium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !useLocalLlm,
                    onClick = {
                        useLocalLlm = false
                        if (!isPremium) {
                            showPaywallDialog = true
                        }
                    },
                    label = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(stringResource(R.string.use_cloud_ai))
                            if (!isPremium) {
                                Icon(
                                    Icons.Default.Lock,
                                    contentDescription = stringResource(R.string.ai_dialog_sub),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier.testTag("cloud_ai_filter_chip")
                )
                FilterChip(
                    selected = useLocalLlm,
                    onClick = {
                        useLocalLlm = true
                        if (!isPremium) {
                            showPaywallDialog = true
                        }
                    },
                    label = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(stringResource(R.string.use_local_llm))
                            if (!isPremium) {
                                Icon(
                                    Icons.Default.Lock,
                                    contentDescription = stringResource(R.string.ai_dialog_sub),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Dns,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier.testTag("local_llm_filter_chip")
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (!isPremium) {
                // Free User Paywall / Upgrade Screen Card (covers Online Search, AI Voice Search, and Local LLM)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("premium_paywall_card"),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFFFFBEB)
                    ),
                    border = BorderStroke(1.5.dp, Color(0xFFF59E0B))
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFFFEF3C7)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Lock,
                                    contentDescription = stringResource(R.string.ai_dialog_sub),
                                    tint = Color(0xFFD97706),
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = if (useLocalLlm) stringResource(R.string.ai_local_llm_card_title) else stringResource(R.string.ai_dialog_title),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = Color(0xFF92400E)
                                )
                                Text(
                                    text = stringResource(R.string.ai_dialog_sub),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFFB45309)
                                )
                            }
                        }

                        Text(
                            text = if (useLocalLlm) stringResource(R.string.ai_local_llm_card_desc) else stringResource(R.string.ai_paywall_card_desc),
                            fontSize = 13.sp,
                            color = Color(0xFF78350F)
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                stringResource(R.string.ai_dialog_point_1),
                                stringResource(R.string.ai_dialog_point_2),
                                stringResource(R.string.ai_dialog_point_3)
                            ).forEach { benefit ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = Color(0xFFD97706),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = benefit,
                                        fontSize = 12.sp,
                                        color = Color(0xFF78350F),
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }

                        Button(
                            onClick = {
                                if (onNavigateToPremium != null) {
                                    onNavigateToPremium()
                                } else {
                                    PremiumEntitlementManager.activateSubscription(context)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("upgrade_to_premium_button"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706))
                        ) {
                            Icon(
                                Icons.Default.WorkspacePremium,
                                contentDescription = null,
                                tint = Color.White
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.ai_upgrade_to_premium_btn),
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            } else {
                // Search Query Input + AI Voice Search Microphone Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        shape = RoundedCornerShape(12.dp),
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
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("ai_search_query_input"),
                        placeholder = { Text(stringResource(R.string.ask_med_placeholder)) },
                        maxLines = 3
                    )

                    FilledIconButton(
                        onClick = {
                            if (voiceUiState == VoiceSearchUiState.LISTENING) {
                                speechRecognizer?.stopListening()
                                voiceUiState = VoiceSearchUiState.IDLE
                                voiceStatusMessage = null
                            } else {
                                startVoiceSearch()
                            }
                        },
                        modifier = Modifier
                            .size(52.dp)
                            .testTag("ai_search_voice_button"),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (voiceUiState == VoiceSearchUiState.LISTENING)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = if (voiceUiState == VoiceSearchUiState.LISTENING)
                                MaterialTheme.colorScheme.onError
                            else
                                MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) {
                        Icon(
                            imageVector = if (voiceUiState == VoiceSearchUiState.LISTENING) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = stringResource(R.string.ai_voice_search)
                        )
                    }
                }

                // Voice / AI Status Banner
                if (voiceUiState != VoiceSearchUiState.IDLE && !voiceStatusMessage.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    val isErrorState = voiceUiState == VoiceSearchUiState.ERROR ||
                        voiceUiState == VoiceSearchUiState.PERMISSION_DENIED ||
                        voiceUiState == VoiceSearchUiState.SPEECH_UNAVAILABLE ||
                        voiceUiState == VoiceSearchUiState.EMPTY_QUERY

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ai_search_voice_status_banner"),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isErrorState)
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                            else
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = if (isErrorState) Icons.Default.ErrorOutline else Icons.Default.Mic,
                                contentDescription = null,
                                tint = if (isErrorState) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = voiceStatusMessage ?: "",
                                fontSize = 12.sp,
                                color = if (isErrorState) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.weight(1f)
                            )
                            if (voiceUiState == VoiceSearchUiState.PERMISSION_DENIED) {
                                TextButton(
                                    onClick = { micPermissionState.launchPermissionRequest() },
                                    modifier = Modifier.testTag("ai_search_grant_mic_permission_button")
                                ) {
                                    Text(stringResource(R.string.grant_permission), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    shape = RoundedCornerShape(12.dp),
                    onClick = {
                        executeAiSearch(query, triggeredByVoice = false)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("ai_search_submit_button"),
                    enabled = !isLoading,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text(
                            stringResource(R.string.search_button),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (responseText.isNotBlank()) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("ai_search_result_card"),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Text(
                                text = responseText,
                                modifier = Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }
        }
    }
}

private val geminiOkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()

suspend fun performGeminiSearch(query: String, context: Context): String = withContext(Dispatchers.IO) {
    // Enforce backend/service-layer premium check so UI state alone cannot bypass it
    OnlineMedicineSearchGate.requirePremiumAccess(LocalLlmConfig.isPremiumSubscribed(context))

    try {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty()) return@withContext "API Key is missing."

        val jsonObj = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", query)
                        })
                    })
                })
            })
            put("tools", JSONArray().apply {
                put(JSONObject().apply {
                    put("googleSearch", JSONObject())
                })
            })
        }

        val requestBody = jsonObj.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey")
            .post(requestBody)
            .build()

        val response = geminiOkHttpClient.newCall(request).execute()
        val responseBody = response.body?.string()

        if (response.isSuccessful && responseBody != null) {
            val responseJson = JSONObject(responseBody)
            val candidates = responseJson.optJSONArray("candidates")
            val content = candidates?.optJSONObject(0)?.optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            val text = parts?.optJSONObject(0)?.optString("text")

            val groundingMetadata = candidates?.optJSONObject(0)?.optJSONObject("groundingMetadata")
            val searchChunks = groundingMetadata?.optJSONArray("groundingChunks")
            var sourcesText = ""
            if (searchChunks != null && searchChunks.length() > 0) {
                sourcesText = "\n\n${context.getString(R.string.sources)}\n"
                for (i in 0 until searchChunks.length()) {
                    val chunk = searchChunks.optJSONObject(i)
                    val web = chunk?.optJSONObject("web")
                    if (web != null) {
                        val title = web.optString("title")
                        val uri = web.optString("uri")
                        sourcesText += "- $title\n  $uri\n"
                    }
                }
            }

            if (text != null) {
                text + sourcesText
            } else {
                "No response."
            }
        } else {
            if (response.code == 429) {
                "Quota Exceeded (429). Please verify your API Key or try again later."
            } else {
                try {
                    val errorJson = JSONObject(responseBody ?: "{}").optJSONObject("error")
                    val errorMessage = errorJson?.optString("message") ?: "Unknown error"
                    "Error (${response.code}): $errorMessage"
                } catch (e: Exception) {
                    "Error (${response.code}): $responseBody"
                }
            }
        }
    } catch (e: PremiumAccessRequiredException) {
        throw e
    } catch (e: Exception) {
        "${context.getString(R.string.connection_failed)}: ${e.message}"
    }
}
