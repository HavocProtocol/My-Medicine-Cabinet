package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.AppDatabase
import com.example.data.SupportMessage
import com.example.services.PremiumEntitlementManager
import com.example.services.PremiumFeature
import com.example.services.SupportChatService
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveChatScreen(
    userId: String = "default_user",
    onBack: () -> Unit,
    onNavigateToPremiumSubscription: () -> Unit,
    customSupportChatService: SupportChatService? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val dao = remember { AppDatabase.getDatabase(context).appDao() }
    val supportChatService = remember(customSupportChatService) {
        customSupportChatService ?: SupportChatService(dao = dao)
    }

    LaunchedEffect(Unit) {
        PremiumEntitlementManager.refreshSubscriptionState(context)
    }

    val subscriptionState by PremiumEntitlementManager.subscriptionStateFlow.collectAsState()
    val isPremiumEntitled = remember(subscriptionState) {
        PremiumEntitlementManager.isEntitled(context, PremiumFeature.LIVE_CHAT_SUPPORT)
    }

    val activeConversation by dao.observeActiveSupportConversationForUser(userId)
        .collectAsState(initial = null)
    val activeConversationId = activeConversation?.id
    val messagesFlow = remember(activeConversationId, userId) {
        if (activeConversationId != null) {
            dao.observeSupportMessagesForConversation(activeConversationId, userId)
        } else {
            kotlinx.coroutines.flow.flowOf(emptyList())
        }
    }
    val messages by messagesFlow.collectAsState(initial = emptyList())

    var draftText by remember { mutableStateOf("") }
    var pendingIdempotencyKey by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var isSending by remember { mutableStateOf(false) }
    var isSyncingReplies by remember { mutableStateOf(false) }
    var isEndingSupport by remember { mutableStateOf(false) }
    var showEndSupportDialog by remember { mutableStateOf(false) }
    var bannerErrorMessage by remember { mutableStateOf<String?>(null) }

    val listState = rememberLazyListState()
    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    // Ensure conversation is initialized when a Premium user opens Live Chat
    LaunchedEffect(isPremiumEntitled, userId) {
        if (isPremiumEntitled) {
            runCatching {
                supportChatService.getOrCreateActiveConversation(context, userId)
            }
        }
    }

    // Automatically scroll to newest message when messages change
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Automatic real-time/polling synchronization for incoming Telegram support replies
    LaunchedEffect(isPremiumEntitled, userId) {
        if (!isPremiumEntitled) return@LaunchedEffect
        while (isActive) {
            runCatching {
                supportChatService.pollIncomingUpdates()
            }
            delay(4000L)
        }
    }

    if (showEndSupportDialog) {
        AlertDialog(
            onDismissRequest = { if (!isEndingSupport) showEndSupportDialog = false },
            modifier = Modifier.testTag("live_chat_end_support_dialog"),
            icon = {
                Icon(
                    Icons.Default.CallEnd,
                    contentDescription = null,
                    tint = Color(0xFFDC2626)
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.live_chat_end_dialog_title),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                val chatUid = activeConversation?.conversationUid ?: ""
                val supportRef = activeConversation?.userReference ?: ""
                Text(
                    text = stringResource(R.string.live_chat_end_dialog_body, "$chatUid • $supportRef"),
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (isEndingSupport) return@Button
                        isEndingSupport = true
                        coroutineScope.launch {
                            supportChatService.endActiveConversation(context, userId)
                            isEndingSupport = false
                            showEndSupportDialog = false
                            onBack()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                    modifier = Modifier.testTag("confirm_end_live_support_button")
                ) {
                    if (isEndingSupport) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.live_chat_end_confirm_btn), color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showEndSupportDialog = false },
                    enabled = !isEndingSupport,
                    modifier = Modifier.testTag("cancel_end_live_support_button")
                ) {
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
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = stringResource(R.string.live_chat_title),
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            activeConversation?.conversationUid?.let { uid ->
                                Surface(
                                    color = Color(0xFFE0F2FE),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.testTag("live_chat_conversation_id_badge")
                                ) {
                                    Text(
                                        text = stringResource(R.string.live_chat_id_prefix, uid),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF0369A1),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            activeConversation?.userReference?.let { supportId ->
                                Surface(
                                    color = Color(0xFFF0F9FF),
                                    shape = RoundedCornerShape(6.dp),
                                    border = BorderStroke(0.5.dp, Color(0xFFBAE6FD)),
                                    modifier = Modifier.testTag("live_chat_support_id_badge")
                                ) {
                                    Text(
                                        text = stringResource(R.string.live_chat_support_id_prefix, supportId),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFF0284C7),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("live_chat_back_button")
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    if (isPremiumEntitled) {
                        IconButton(
                            onClick = {
                                if (!isSyncingReplies) {
                                    isSyncingReplies = true
                                    coroutineScope.launch {
                                        supportChatService.pollIncomingUpdates()
                                        isSyncingReplies = false
                                    }
                                }
                            },
                            modifier = Modifier.testTag("live_chat_sync_button")
                        ) {
                            if (isSyncingReplies) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Icon(
                                    Icons.Default.Sync,
                                    contentDescription = stringResource(R.string.refresh_online_info)
                                )
                            }
                        }

                        if (activeConversation != null) {
                            OutlinedButton(
                                onClick = { showEndSupportDialog = true },
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, Color(0xFFDC2626).copy(alpha = 0.5f)),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = Color(0xFFDC2626)
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .height(34.dp)
                                    .testTag("live_chat_end_support_button")
                            ) {
                                Icon(
                                    Icons.Default.CallEnd,
                                    contentDescription = stringResource(R.string.live_chat_end_confirm_btn),
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.live_chat_end_btn),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (!isPremiumEntitled) {
            // Defense-in-depth gate if a Free user navigates directly to LiveChatScreen
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp)
                    .testTag("live_chat_locked_gate"),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F9FF)),
                    border = BorderStroke(1.5.dp, Color(0xFF0284C7))
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFE0F2FE)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = null,
                                tint = Color(0xFF0284C7),
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Text(
                            text = stringResource(R.string.live_chat_gate_title),
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = Color(0xFF0C4A6E)
                        )
                        Text(
                            text = stringResource(R.string.live_chat_gate_desc),
                            fontSize = 14.sp,
                            color = Color(0xFF0369A1),
                            textAlign = TextAlign.Center
                        )
                        Button(
                            onClick = onNavigateToPremiumSubscription,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("live_chat_upgrade_button")
                        ) {
                            Icon(Icons.Default.WorkspacePremium, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.live_chat_view_plans_btn), color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .testTag("live_chat_screen")
        ) {
            // Optional error banner
            bannerErrorMessage?.let { errorMsg ->
                Surface(
                    color = Color(0xFFFEE2E2),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("live_chat_error_banner")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = Color(0xFF991B1B),
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = errorMsg,
                                fontSize = 12.sp,
                                color = Color(0xFF991B1B),
                                fontWeight = FontWeight.Medium
                            )
                        }
                        IconButton(
                            onClick = { bannerErrorMessage = null },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.cancel),
                                tint = Color(0xFF991B1B),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // Message Thread Area
            if (messages.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(24.dp)
                        .testTag("live_chat_empty_state"),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFE0F2FE)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Outlined.SupportAgent,
                                    contentDescription = null,
                                    tint = Color(0xFF0284C7),
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                            Text(
                                text = stringResource(R.string.live_chat_empty_title),
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.live_chat_empty_desc),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                lineHeight = 19.sp
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .testTag("live_chat_message_list"),
                    contentPadding = PaddingValues(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(messages, key = { it.id }) { msg ->
                        SupportMessageBubble(
                            message = msg,
                            formattedTime = timeFormatter.format(Date(msg.createdAt)),
                            onRetryClick = {
                                if (!isSending) {
                                    isSending = true
                                    bannerErrorMessage = null
                                    coroutineScope.launch {
                                        val res = supportChatService.retryFailedMessage(
                                            context = context,
                                            userId = userId,
                                            messageId = msg.id
                                        )
                                        isSending = false
                                        res.onFailure { err ->
                                            bannerErrorMessage = err.message
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }

            // Bottom Composer Bar
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = draftText,
                        onValueChange = { newValue ->
                            if (newValue != draftText) {
                                draftText = newValue
                                pendingIdempotencyKey = UUID.randomUUID().toString()
                            }
                        },
                        placeholder = {
                            Text(
                                text = stringResource(R.string.live_chat_input_placeholder),
                                fontSize = 14.sp
                            )
                        },
                        shape = RoundedCornerShape(18.dp),
                        maxLines = 4,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("live_chat_input_field"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            focusedBorderColor = Color(0xFF0284C7),
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                        )
                    )

                    val canSend = draftText.trim().isNotEmpty() && !isSending
                    IconButton(
                        onClick = {
                            val textToSend = draftText.trim()
                            val keyToUse = pendingIdempotencyKey
                            if (textToSend.isEmpty() || isSending) return@IconButton

                            isSending = true
                            bannerErrorMessage = null
                            draftText = ""
                            pendingIdempotencyKey = UUID.randomUUID().toString()

                            coroutineScope.launch {
                                val result = supportChatService.sendUserMessage(
                                    context = context,
                                    userId = userId,
                                    rawMessageText = textToSend,
                                    clientIdempotencyKey = keyToUse
                                )
                                isSending = false
                                result.onFailure { err ->
                                    bannerErrorMessage = err.message
                                }
                            }
                        },
                        enabled = canSend,
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(
                                if (canSend) Color(0xFF0284C7)
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .testTag("live_chat_send_button")
                    ) {
                        if (isSending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = stringResource(R.string.send_question),
                                tint = if (canSend) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SupportMessageBubble(
    message: SupportMessage,
    formattedTime: String,
    onRetryClick: () -> Unit
) {
    val isUser = message.senderType == "USER"
    val alignment = if (isUser) Alignment.End else Alignment.Start

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("chat_message_bubble_${message.id}"),
        horizontalAlignment = alignment
    ) {
        // Sender label for Support Representative replies
        if (!isUser) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
            ) {
                Icon(
                    Icons.Default.SupportAgent,
                    contentDescription = null,
                    tint = Color(0xFF0284C7),
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = message.senderName ?: stringResource(R.string.live_chat_support_rep_name),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0284C7)
                )
            }
        }

        Surface(
            color = if (isUser) Color(0xFF0284C7) else MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 18.dp
            ),
            border = if (isUser) null else BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
            ),
            modifier = Modifier.widthIn(max = 310.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    text = message.messageText,
                    color = if (isUser) Color.White else MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(
                        text = formattedTime,
                        fontSize = 10.sp,
                        color = if (isUser) Color.White.copy(alpha = 0.8f)
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isUser) {
                        val statusText = when (message.deliveryStatus) {
                            "SENDING" -> stringResource(R.string.live_chat_status_sending)
                            "SENT_TO_TELEGRAM" -> stringResource(R.string.live_chat_status_sent_telegram)
                            "DELIVERED" -> stringResource(R.string.live_chat_status_delivered)
                            "FAILED" -> stringResource(R.string.live_chat_status_failed)
                            else -> ""
                        }
                        if (statusText.isNotEmpty()) {
                            Text(
                                text = statusText,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (message.deliveryStatus == "FAILED") Color(0xFFFECACA)
                                else Color.White.copy(alpha = 0.9f),
                                modifier = Modifier.testTag("message_status_${message.id}")
                            )
                        }
                    }
                }
            }
        }

        if (isUser && message.deliveryStatus == "FAILED") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clickable(onClick = onRetryClick)
                    .testTag("retry_message_button_${message.id}")
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.live_chat_retry_failed),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = stringResource(R.string.live_chat_retry_failed),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
