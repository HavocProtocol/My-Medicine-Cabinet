package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.services.LocalLlmConfig
import com.example.services.LocalLlmDiagnosticException
import com.example.services.LocalLlmService
import com.example.services.PremiumEntitlementManager
import com.example.services.PremiumFeature
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.launch
import java.util.*

enum class VoiceState {
    IDLE,
    LISTENING,
    PROCESSING,
    SPEAKING,
    ERROR
}

data class ChatBubble(
    val isUser: Boolean,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun VoiceScreen(
    onBack: () -> Unit,
    onNavigateToPremium: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val isPremium by LocalLlmConfig.premiumSubscribedFlow.collectAsState()
    val micPermissionState = rememberPermissionState(Manifest.permission.RECORD_AUDIO)
    val coroutineScope = rememberCoroutineScope()

    val hostUrl = remember(isPremium) { LocalLlmConfig.getHostUrl(context) }
    val modelName = remember(isPremium) { LocalLlmConfig.getModelName(context) }
    val llmService = remember { LocalLlmService() }

    var voiceState by remember { mutableStateOf(VoiceState.IDLE) }
    var statusErrorText by remember { mutableStateOf<String?>(null) }
    var textInput by remember { mutableStateOf("") }
    var isConnected by remember { mutableStateOf<Boolean?>(null) }
    var useCloudSearch by remember { mutableStateOf(false) }
    val welcomeMsg = stringResource(R.string.voice_welcome_message)
    var chatMessages by remember(welcomeMsg) {
        mutableStateOf(
            listOf(
                ChatBubble(
                    isUser = false,
                    text = welcomeMsg
                )
            )
        )
    }

    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var speechRecognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }

    LaunchedEffect(isPremium) {
        if (isPremium) {
            tts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.getDefault()
                }
            }
            isConnected = llmService.checkConnection(hostUrl)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            tts?.stop()
            tts?.shutdown()
            speechRecognizer?.destroy()
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (voiceState == VoiceState.LISTENING || voiceState == VoiceState.SPEAKING) 1.25f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    fun sendVoiceOrTextQuery(rawPrompt: String) {
        val prompt = rawPrompt.trim()
        if (prompt.isEmpty()) {
            voiceState = VoiceState.ERROR
            statusErrorText = context.getString(R.string.voice_listening_indicator)
            return
        }

        if (!PremiumEntitlementManager.isEntitled(context, PremiumFeature.AI_VOICE_SEARCH)) {
            voiceState = VoiceState.ERROR
            statusErrorText = context.getString(R.string.ai_dialog_sub)
            return
        }

        statusErrorText = null
        chatMessages = chatMessages + ChatBubble(isUser = true, text = prompt)
        voiceState = VoiceState.PROCESSING

        coroutineScope.launch {
            if (useCloudSearch) {
                try {
                    val reply = performGeminiSearch(prompt, context)
                    chatMessages = chatMessages + ChatBubble(isUser = false, text = reply)
                    voiceState = VoiceState.SPEAKING
                    tts?.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "llm_reply")
                    voiceState = VoiceState.IDLE
                } catch (e: Exception) {
                    val errMsg = "${context.getString(R.string.connection_failed)}: ${e.message ?: ""}"
                    statusErrorText = errMsg
                    chatMessages = chatMessages + ChatBubble(isUser = false, text = errMsg)
                    voiceState = VoiceState.ERROR
                }
            } else {
                val result = llmService.generateResponseForUser(
                    context = context,
                    hostUrl = hostUrl,
                    modelName = modelName,
                    prompt = prompt,
                    systemPrompt = "You are a professional, helpful home medicine cabinet AI assistant. Provide concise, clear, and accurate answers in the user's language. Remind the user to consult a doctor or pharmacist for medical decisions."
                )

                result.onSuccess { reply ->
                    chatMessages = chatMessages + ChatBubble(isUser = false, text = reply)
                    voiceState = VoiceState.SPEAKING
                    tts?.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "llm_reply")
                    voiceState = VoiceState.IDLE
                }.onFailure { err ->
                    val fallbackMsg = when (err) {
                        is LocalLlmDiagnosticException -> "${err.userFriendlyTitle}: ${err.message}"
                        else -> "${context.getString(R.string.connection_failed)} (${err.message ?: ""})"
                    }
                    statusErrorText = fallbackMsg
                    chatMessages = chatMessages + ChatBubble(isUser = false, text = fallbackMsg)
                    voiceState = VoiceState.ERROR
                }
            }
        }
    }

    fun startListening() {
        if (!PremiumEntitlementManager.isEntitled(context, PremiumFeature.AI_VOICE_SEARCH)) {
            statusErrorText = context.getString(R.string.ai_dialog_sub)
            voiceState = VoiceState.ERROR
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            statusErrorText = context.getString(R.string.connection_failed)
            voiceState = VoiceState.ERROR
            return
        }

        statusErrorText = null
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    voiceState = VoiceState.LISTENING
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    voiceState = VoiceState.PROCESSING
                }
                override fun onError(error: Int) {
                    statusErrorText = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> context.getString(R.string.tap_to_speak)
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> context.getString(R.string.connection_failed)
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> context.getString(R.string.mic_permission_needed)
                        else -> "${context.getString(R.string.connection_failed)} ($error)"
                    }
                    voiceState = VoiceState.ERROR
                }
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val spokenText = matches?.firstOrNull()?.trim().orEmpty()
                    if (spokenText.isNotEmpty()) {
                        sendVoiceOrTextQuery(spokenText)
                    } else {
                        statusErrorText = context.getString(R.string.tap_to_speak)
                        voiceState = VoiceState.ERROR
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
                        Text(stringResource(R.string.voice_chat_title), fontWeight = FontWeight.Bold)
                        Surface(
                            color = if (isPremium) Color(0xFFDCFCE7) else Color(0xFFFEF3C7),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("voice_screen_premium_badge")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = if (isPremium) Icons.Default.WorkspacePremium else Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = if (isPremium) Color(0xFF166534) else Color(0xFFB45309),
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = if (isPremium) stringResource(R.string.badge_premium) else stringResource(R.string.badge_pro),
                                    fontSize = 10.sp,
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
                .padding(16.dp)
        ) {
            if (!isPremium) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("voice_screen_premium_gate_card"),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFBEB)),
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
                                    text = stringResource(R.string.voice_paywall_banner_title),
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
                            text = stringResource(R.string.voice_paywall_banner_desc),
                            fontSize = 13.sp,
                            color = Color(0xFF78350F)
                        )

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
                                .testTag("voice_screen_upgrade_button"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706))
                        ) {
                            Icon(Icons.Default.WorkspacePremium, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.ai_upgrade_to_premium_btn), color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                // Engine Switcher: Local LLM vs Cloud Gemini AI
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !useCloudSearch,
                        onClick = { useCloudSearch = false },
                        label = { Text(stringResource(R.string.use_local_llm)) },
                        leadingIcon = {
                            Icon(Icons.Default.Dns, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        modifier = Modifier.testTag("voice_use_local_llm_chip")
                    )
                    FilterChip(
                        selected = useCloudSearch,
                        onClick = { useCloudSearch = true },
                        label = { Text(stringResource(R.string.use_cloud_ai)) },
                        leadingIcon = {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        modifier = Modifier.testTag("voice_use_cloud_ai_chip")
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Local LLM Connection Banner
                if (!useCloudSearch) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isConnected == true) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(
                                        color = if (isConnected == true) Color(0xFF16A34A) else Color(0xFFEAB308),
                                        shape = CircleShape
                                    )
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isConnected == true) stringResource(R.string.connected_to_local_llm) else stringResource(R.string.not_connected_local_llm),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "$hostUrl ($modelName)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Chat messages log
                val scrollState = rememberScrollState()
                LaunchedEffect(chatMessages.size) {
                    scrollState.animateScrollTo(scrollState.maxValue)
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    chatMessages.forEach { msg ->
                        val alignment = if (msg.isUser) Alignment.End else Alignment.Start
                        val containerColor = if (msg.isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                        val textColor = if (msg.isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = alignment
                        ) {
                            Box(
                                modifier = Modifier
                                    .widthIn(max = 300.dp)
                                    .background(
                                        color = containerColor,
                                        shape = RoundedCornerShape(
                                            topStart = 16.dp,
                                            topEnd = 16.dp,
                                            bottomStart = if (msg.isUser) 16.dp else 4.dp,
                                            bottomEnd = if (msg.isUser) 4.dp else 16.dp
                                        )
                                    )
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = msg.text,
                                    color = textColor,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Status message
                val statusLabel = when (voiceState) {
                    VoiceState.LISTENING -> stringResource(R.string.listening)
                    VoiceState.PROCESSING -> stringResource(R.string.processing)
                    VoiceState.SPEAKING -> stringResource(R.string.speaking)
                    VoiceState.ERROR -> statusErrorText ?: stringResource(R.string.connection_failed)
                    VoiceState.IDLE -> stringResource(R.string.tap_to_speak)
                }

                Text(
                    text = statusLabel,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("voice_status_label"),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (voiceState == VoiceState.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Voice Action Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(72.dp)
                    ) {
                        if (voiceState == VoiceState.LISTENING || voiceState == VoiceState.SPEAKING) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .scale(pulseScale)
                                    .background(
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                                        shape = CircleShape
                                    )
                            )
                        }

                        FloatingActionButton(
                            onClick = {
                                if (!micPermissionState.status.isGranted) {
                                    statusErrorText = context.getString(R.string.mic_permission_needed)
                                    voiceState = VoiceState.ERROR
                                    micPermissionState.launchPermissionRequest()
                                } else {
                                    if (voiceState == VoiceState.LISTENING) {
                                        speechRecognizer?.stopListening()
                                        voiceState = VoiceState.PROCESSING
                                    } else if (voiceState == VoiceState.SPEAKING) {
                                        tts?.stop()
                                        voiceState = VoiceState.IDLE
                                    } else {
                                        startListening()
                                    }
                                }
                            },
                            containerColor = if (voiceState == VoiceState.LISTENING) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            shape = CircleShape,
                            modifier = Modifier
                                .size(56.dp)
                                .testTag("voice_mic_fab")
                        ) {
                            Icon(
                                imageVector = when (voiceState) {
                                    VoiceState.LISTENING -> Icons.Default.MicOff
                                    VoiceState.SPEAKING -> Icons.AutoMirrored.Filled.VolumeUp
                                    else -> Icons.Default.Mic
                                },
                                contentDescription = stringResource(R.string.tap_to_speak)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Text Fallback input
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        placeholder = {
                            Text(
                                stringResource(R.string.type_message_placeholder),
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("voice_text_input"),
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Color.Transparent
                        ),
                        maxLines = 2
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            val prompt = textInput.trim()
                            textInput = ""
                            sendVoiceOrTextQuery(prompt)
                        },
                        enabled = voiceState != VoiceState.PROCESSING,
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                color = if (textInput.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                shape = CircleShape
                            )
                            .testTag("voice_send_button")
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.send_question),
                            tint = if (textInput.isNotBlank()) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
