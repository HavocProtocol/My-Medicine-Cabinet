package com.example.services

import android.content.Context
import com.example.BuildConfig
import com.example.data.AppDao
import com.example.data.ProcessedTelegramUpdate
import com.example.data.SupportConversation
import com.example.data.SupportMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class TelegramSupportConfig(
    val botToken: String = BuildConfig.TELEGRAM_BOT_TOKEN,
    val supportChatId: Long? = BuildConfig.TELEGRAM_SUPPORT_CHAT_ID.trim().toLongOrNull(),
    val webhookSecret: String = BuildConfig.TELEGRAM_WEBHOOK_SECRET,
    val authorizedUserIds: Set<Long> = parseAuthorizedUserIds(BuildConfig.TELEGRAM_AUTHORIZED_USER_IDS)
) {
    fun isConfigured(): Boolean {
        return botToken.isNotBlank() &&
            botToken != "MY_TELEGRAM_BOT_TOKEN" &&
            supportChatId != null &&
            supportChatId != 0L
    }

    companion object {
        fun parseAuthorizedUserIds(raw: String?): Set<Long> {
            if (raw.isNullOrBlank()) return emptySet()
            return raw.split(",")
                .mapNotNull { it.trim().toLongOrNull() }
                .toSet()
        }
    }
}

data class TelegramSendResponse(
    val ok: Boolean,
    val telegramMessageId: Long? = null,
    val telegramChatId: Long? = null,
    val migratedToChatId: Long? = null,
    val errorDescription: String? = null
)

interface TelegramBotTransport {
    suspend fun sendMessage(
        botToken: String,
        chatId: Long,
        text: String,
        messageThreadId: Int? = null
    ): TelegramSendResponse

    suspend fun createForumTopic(
        botToken: String,
        chatId: Long,
        name: String
    ): Int? = null

    suspend fun editForumTopic(
        botToken: String,
        chatId: Long,
        messageThreadId: Int,
        name: String
    ): Boolean = false

    suspend fun closeForumTopic(
        botToken: String,
        chatId: Long,
        messageThreadId: Int
    ): Boolean = false

    suspend fun getUpdates(
        botToken: String,
        offset: Long?,
        timeoutSeconds: Int = 0
    ): Result<List<JSONObject>>
}

class OkHttpTelegramBotTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
) : TelegramBotTransport {

    override suspend fun sendMessage(
        botToken: String,
        chatId: Long,
        text: String,
        messageThreadId: Int?
    ): TelegramSendResponse = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("chat_id", chatId)
                put("text", text)
                put("disable_web_page_preview", true)
                if (messageThreadId != null && messageThreadId > 0) {
                    put("message_thread_id", messageThreadId)
                }
            }
            val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("https://api.telegram.org/bot$botToken/sendMessage")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string().orEmpty()
                if (respBody.isBlank()) {
                    return@withContext TelegramSendResponse(
                        ok = false,
                        errorDescription = "Empty response from Telegram API (HTTP ${response.code})."
                    )
                }
                val json = JSONObject(respBody)
                val ok = json.optBoolean("ok", false)
                if (ok) {
                    val resultObj = json.optJSONObject("result")
                    val msgId = resultObj?.optLong("message_id")?.takeIf { it != 0L }
                    val returnedChatId = resultObj?.optJSONObject("chat")?.optLong("id") ?: chatId
                    if (msgId != null) {
                        TelegramSendResponse(
                            ok = true,
                            telegramMessageId = msgId,
                            telegramChatId = returnedChatId
                        )
                    } else {
                        TelegramSendResponse(
                            ok = false,
                            errorDescription = "Telegram API response did not include a valid message_id."
                        )
                    }
                } else {
                    val desc = json.optString("description", "Telegram API error (HTTP ${response.code})")
                    val migratedId = json.optJSONObject("parameters")
                        ?.optLong("migrate_to_chat_id", 0L)
                        ?.takeIf { it != 0L }
                    TelegramSendResponse(
                        ok = false,
                        migratedToChatId = migratedId,
                        errorDescription = desc
                    )
                }
            }
        } catch (e: Exception) {
            TelegramSendResponse(
                ok = false,
                errorDescription = "Network error communicating with Telegram: ${e.message ?: "Connection failed"}"
            )
        }
    }

    override suspend fun createForumTopic(
        botToken: String,
        chatId: Long,
        name: String
    ): Int? = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("chat_id", chatId)
                put("name", name.take(128))
            }
            val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("https://api.telegram.org/bot$botToken/createForumTopic")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string().orEmpty()
                if (respBody.isBlank()) return@withContext null
                val json = JSONObject(respBody)
                if (!json.optBoolean("ok", false)) return@withContext null
                json.optJSONObject("result")
                    ?.optInt("message_thread_id", 0)
                    ?.takeIf { it > 0 }
            }
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun editForumTopic(
        botToken: String,
        chatId: Long,
        messageThreadId: Int,
        name: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("chat_id", chatId)
                put("message_thread_id", messageThreadId)
                put("name", name.take(128))
            }
            val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("https://api.telegram.org/bot$botToken/editForumTopic")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string().orEmpty()
                if (respBody.isBlank()) return@withContext false
                JSONObject(respBody).optBoolean("ok", false)
            }
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun closeForumTopic(
        botToken: String,
        chatId: Long,
        messageThreadId: Int
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("chat_id", chatId)
                put("message_thread_id", messageThreadId)
            }
            val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("https://api.telegram.org/bot$botToken/closeForumTopic")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string().orEmpty()
                if (respBody.isBlank()) return@withContext false
                JSONObject(respBody).optBoolean("ok", false)
            }
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun getUpdates(
        botToken: String,
        offset: Long?,
        timeoutSeconds: Int
    ): Result<List<JSONObject>> = withContext(Dispatchers.IO) {
        try {
            val urlBuilder = StringBuilder("https://api.telegram.org/bot$botToken/getUpdates?timeout=$timeoutSeconds")
            if (offset != null) {
                urlBuilder.append("&offset=").append(offset)
            }
            val request = Request.Builder()
                .url(urlBuilder.toString())
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string().orEmpty()
                if (!response.isSuccessful || respBody.isBlank()) {
                    return@withContext Result.failure(
                        IllegalStateException("Telegram getUpdates failed with HTTP ${response.code}")
                    )
                }
                val json = JSONObject(respBody)
                if (!json.optBoolean("ok", false)) {
                    return@withContext Result.failure(
                        IllegalStateException(json.optString("description", "Telegram getUpdates returned ok=false"))
                    )
                }
                val resultsArray = json.optJSONArray("result") ?: JSONArray()
                val list = mutableListOf<JSONObject>()
                for (i in 0 until resultsArray.length()) {
                    resultsArray.optJSONObject(i)?.let { list.add(it) }
                }
                Result.success(list)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

sealed class InboundUpdateResult {
    data class RoutedReply(
        val conversationUid: String,
        val userId: String,
        val supportMessage: SupportMessage
    ) : InboundUpdateResult()

    data class DuplicateIgnored(val updateId: Long, val reason: String) : InboundUpdateResult()
    data class UnmatchedIgnored(val updateId: Long, val reason: String) : InboundUpdateResult()
    data class RejectedUnauthorized(val updateId: Long, val reason: String) : InboundUpdateResult()
}

class WebhookAuthenticationException(message: String) : SecurityException(message)

/**
 * Authoritative service for the Online Help Center & Two-Way Telegram Live Chat.
 *
 * Responsibilities:
 * 1. Enforces user authentication and unified Premium subscription entitlement before any support chat action.
 * 2. Persists outbound user messages in Room (`support_conversations`, `support_messages`) and forwards them
 *    to the configured Telegram support group using the official Telegram Bot API (`sendMessage`).
 * 3. Stores the returned Telegram `message_id` and `chat_id` for deterministic reply routing.
 * 4. Verifies and processes inbound Telegram replies (both via webhook with `X-Telegram-Bot-Api-Secret-Token`
 *    validation and via `getUpdates` polling), matching `reply_to_message.message_id` to the exact user
 *    conversation idempotently.
 */
class SupportChatService(
    private val dao: AppDao,
    private val transport: TelegramBotTransport = OkHttpTelegramBotTransport(),
    private val configProvider: () -> TelegramSupportConfig = { TelegramSupportConfig() }
) {
    private val sendMutex = Mutex()
    private val inFlightKeys = ConcurrentHashMap.newKeySet<String>()

    companion object {
        const val MAX_MESSAGE_LENGTH = 2000
        const val HEADER_TELEGRAM_SECRET_TOKEN = "X-Telegram-Bot-Api-Secret-Token"

        // Maps legacy regular group IDs (positive or negative) to their upgraded supergroup ID
        // when Telegram Topics (Forum mode) is enabled on the group.
        private val migratedSupergroupMap = ConcurrentHashMap<Long, Long>().apply {
            put(5536710598L, -1003956666941L)
            put(-5536710598L, -1003956666941L)
        }

        fun registerMigratedSupergroup(oldChatId: Long, newSupergroupChatId: Long) {
            migratedSupergroupMap[oldChatId] = newSupergroupChatId
            migratedSupergroupMap[-kotlin.math.abs(oldChatId)] = newSupergroupChatId
            migratedSupergroupMap[kotlin.math.abs(oldChatId)] = newSupergroupChatId
        }

        fun resolveTargetChatId(configuredChatId: Long): Long {
            return migratedSupergroupMap[configuredChatId] ?: configuredChatId
        }

        fun sanitizeMessageText(raw: String): String {
            return raw
                .replace("\u0000", "")
                .replace(Regex("[\\p{Cntrl}&&[^\r\n\t]]"), "")
                .trim()
        }

        fun generateOpaqueUserReference(
            userId: String,
            sessionNonce: String = UUID.randomUUID().toString()
        ): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("${userId.trim()}_$sessionNonce".toByteArray(Charsets.UTF_8))
            val hex = digest.take(4).joinToString("") { "%02X".format(it) }
            return "USER-$hex"
        }

        fun extractTicketShortId(conversationUid: String): String {
            return conversationUid.trim().removePrefix("SUP-").trim().ifBlank { conversationUid.trim() }
        }

        fun formatTicketInitializationMessage(
            conversationUid: String,
            userReference: String
        ): String {
            val ticketShortId = extractTicketShortId(conversationUid)
            return buildString {
                appendLine("New Support Ticket #$ticketShortId")
                appendLine()
                appendLine("User ID: $userReference")
                appendLine()
                append("Reply in this topic to chat with the user through the app.")
            }
        }

        fun formatSubsequentUserMessage(
            messageText: String
        ): String {
            return buildString {
                appendLine("User:")
                append(messageText.trim())
            }
        }

        fun formatOutboundTelegramMessage(
            conversationUid: String,
            userReference: String,
            messageText: String,
            timestampMillis: Long
        ): String {
            return formatSubsequentUserMessage(messageText)
        }
    }

    /**
     * Gets or creates the authenticated Premium user's active support conversation.
     * When a previous conversation was ended (status == "CLOSED"), this generates a brand-new
     * Live Chat ID (conversationUid) and a brand-new Support ID (userReference).
     */
    suspend fun getOrCreateActiveConversation(
        context: Context,
        userId: String,
        nowMillis: Long = System.currentTimeMillis()
    ): SupportConversation {
        val cleanUserId = userId.trim()
        if (cleanUserId.isBlank()) {
            throw SecurityException("User authentication is required to access Live Chat support.")
        }
        PremiumEntitlementManager.requirePremiumAccess(
            context = context,
            feature = PremiumFeature.LIVE_CHAT_SUPPORT,
            nowMillis = nowMillis
        )

        val existing = dao.getActiveSupportConversationForUser(cleanUserId)
        if (existing != null) {
            return existing
        }

        val shortCode = UUID.randomUUID().toString().replace("-", "").uppercase().take(6)
        val conversationUid = "SUP-$shortCode"
        val userRef = generateOpaqueUserReference(cleanUserId, UUID.randomUUID().toString())
        val newConversation = SupportConversation(
            conversationUid = conversationUid,
            userId = cleanUserId,
            userReference = userRef,
            subject = "Medicine Cabinet Support",
            status = "OPEN",
            createdAt = nowMillis,
            updatedAt = nowMillis,
            lastMessagePreview = null
        )
        val insertedId = dao.insertSupportConversationIgnore(newConversation).toInt()
        return if (insertedId > 0) {
            newConversation.copy(id = insertedId)
        } else {
            dao.getActiveSupportConversationForUser(cleanUserId)
                ?: dao.getSupportConversationByUid(conversationUid)
                ?: newConversation
        }
    }

    /**
     * Ends the user's current active Live Support session, marks the conversation as CLOSED in the app,
     * and locks/closes the corresponding Telegram Forum Topic without generating or storing a duplicate
     * chat summary. The complete conversation history remains preserved in its locked Telegram ticket topic.
     */
    suspend fun endActiveConversation(
        context: Context,
        userId: String,
        nowMillis: Long = System.currentTimeMillis()
    ): Result<SupportConversation?> = sendMutex.withLock {
        val cleanUserId = userId.trim()
        if (cleanUserId.isBlank()) {
            return@withLock Result.failure(SecurityException("User authentication is required."))
        }
        try {
            PremiumEntitlementManager.requirePremiumAccess(
                context = context,
                feature = PremiumFeature.LIVE_CHAT_SUPPORT,
                nowMillis = nowMillis
            )
        } catch (e: PremiumAccessRequiredException) {
            return@withLock Result.failure(e)
        }

        val active = dao.getActiveSupportConversationForUser(cleanUserId)
            ?: return@withLock Result.success(null)

        val closedConversation = active.copy(
            status = "CLOSED",
            updatedAt = nowMillis
        )
        dao.updateSupportConversation(closedConversation)

        val config = configProvider()
        val supportChatId = config.supportChatId
        if (config.isConfigured() && supportChatId != null) {
            val targetChatId = resolveTargetChatId(supportChatId)
            val topicId = active.telegramTopicId?.takeIf { it > 0 }
            if (topicId != null) {
                // Lock/close the dedicated Forum Topic in Telegram while keeping its conversation history intact
                runCatching {
                    transport.closeForumTopic(
                        botToken = config.botToken,
                        chatId = targetChatId,
                        messageThreadId = topicId
                    )
                }
            }
        }

        Result.success(closedConversation)
    }

    /**
     * Submits a user message to the support chat, persists it locally, and forwards it to the
     * configured Telegram support group via the official Telegram Bot API.
     *
     * Idempotent with respect to [clientIdempotencyKey]: repeated button taps or network retries
     * with the same key will never create duplicate message bubbles or duplicate Telegram dispatches.
     */
    suspend fun sendUserMessage(
        context: Context,
        userId: String,
        rawMessageText: String,
        clientIdempotencyKey: String = UUID.randomUUID().toString(),
        nowMillis: Long = System.currentTimeMillis()
    ): Result<SupportMessage> {
        val cleanUserId = userId.trim()
        if (cleanUserId.isBlank()) {
            return Result.failure(SecurityException("User authentication is required to send support messages."))
        }

        // 1. Server/service-level Premium entitlement gate
        try {
            PremiumEntitlementManager.requirePremiumAccess(
                context = context,
                feature = PremiumFeature.LIVE_CHAT_SUPPORT,
                nowMillis = nowMillis
            )
        } catch (e: PremiumAccessRequiredException) {
            return Result.failure(e)
        }

        // 2. Validate and sanitize message text
        val sanitizedText = sanitizeMessageText(rawMessageText)
        if (sanitizedText.isBlank()) {
            return Result.failure(IllegalArgumentException("Message cannot be empty."))
        }
        if (sanitizedText.length > MAX_MESSAGE_LENGTH) {
            return Result.failure(
                IllegalArgumentException("Message exceeds the maximum length of $MAX_MESSAGE_LENGTH characters.")
            )
        }

        // 3. Idempotency check & in-flight duplicate guard
        if (!inFlightKeys.add(clientIdempotencyKey)) {
            val existingInFlight = dao.getSupportMessageByIdempotencyKey(clientIdempotencyKey)
            if (existingInFlight != null) {
                return Result.success(existingInFlight)
            }
            return Result.failure(IllegalStateException("Message submission is already in progress."))
        }

        try {
            val persistedMessage = sendMutex.withLock {
                val existing = dao.getSupportMessageByIdempotencyKey(clientIdempotencyKey)
                if (existing != null) {
                    if (existing.userId != cleanUserId) {
                        return Result.failure(SecurityException("Unauthorized access to support message."))
                    }
                    if (existing.deliveryStatus == "SENT_TO_TELEGRAM" || existing.deliveryStatus == "DELIVERED") {
                        return Result.success(existing)
                    }
                    existing
                } else {
                    val conversation = getOrCreateActiveConversation(context, cleanUserId, nowMillis)
                    val initialMsg = SupportMessage(
                        conversationId = conversation.id,
                        conversationUid = conversation.conversationUid,
                        userId = cleanUserId,
                        senderType = "USER",
                        senderName = "You",
                        messageText = sanitizedText,
                        deliveryStatus = "SENDING",
                        clientIdempotencyKey = clientIdempotencyKey,
                        createdAt = nowMillis,
                        updatedAt = nowMillis
                    )
                    val rowId = dao.insertSupportMessageIgnore(initialMsg).toInt()
                    val saved = if (rowId > 0) {
                        initialMsg.copy(id = rowId)
                    } else {
                        dao.getSupportMessageByIdempotencyKey(clientIdempotencyKey) ?: initialMsg
                    }
                    dao.updateSupportConversation(
                        conversation.copy(
                            status = "WAITING_FOR_SUPPORT",
                            updatedAt = nowMillis,
                            lastMessagePreview = sanitizedText.take(120)
                        )
                    )
                    saved
                }
            }

            return dispatchMessageToTelegram(persistedMessage, nowMillis)
        } finally {
            inFlightKeys.remove(clientIdempotencyKey)
        }
    }

    /**
     * Retries sending a previously FAILED message without creating a duplicate message record.
     */
    suspend fun retryFailedMessage(
        context: Context,
        userId: String,
        messageId: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): Result<SupportMessage> {
        val cleanUserId = userId.trim()
        try {
            PremiumEntitlementManager.requirePremiumAccess(
                context = context,
                feature = PremiumFeature.LIVE_CHAT_SUPPORT,
                nowMillis = nowMillis
            )
        } catch (e: PremiumAccessRequiredException) {
            return Result.failure(e)
        }

        val existing = dao.getSupportMessageById(messageId, cleanUserId)
            ?: return Result.failure(NoSuchElementException("Support message not found or access denied."))

        if (existing.deliveryStatus == "SENT_TO_TELEGRAM" || existing.deliveryStatus == "DELIVERED") {
            return Result.success(existing)
        }

        val sendingState = existing.copy(
            deliveryStatus = "SENDING",
            errorMessage = null,
            updatedAt = nowMillis
        )
        dao.updateSupportMessage(sendingState)
        return dispatchMessageToTelegram(sendingState, nowMillis)
    }

    private suspend fun ensureTopicForConversation(
        botToken: String,
        targetChatId: Long,
        conversation: SupportConversation?,
        nowMillis: Long
    ): Int? {
        if (conversation == null) return null
        if (conversation.telegramTopicId != null && conversation.telegramTopicId > 0) {
            return conversation.telegramTopicId
        }
        val shortTicketId = extractTicketShortId(conversation.conversationUid)
        val topicTitle = "Ticket #$shortTicketId • ${conversation.userReference}"
        val createdTopicId = transport.createForumTopic(
            botToken = botToken,
            chatId = targetChatId,
            name = topicTitle
        )
        if (createdTopicId != null && createdTopicId > 0) {
            dao.updateSupportConversation(
                conversation.copy(
                    telegramTopicId = createdTopicId,
                    updatedAt = nowMillis
                )
            )
            return createdTopicId
        }
        return null
    }

    private fun ticketInitRecordId(conversationId: Int): Long {
        return -(1_000_000L + conversationId.toLong())
    }

    private suspend fun sendMessageToIsolatedTopic(
        botToken: String,
        supportChatId: Long,
        initialTargetChatId: Long,
        initialTopicId: Int,
        conversation: SupportConversation?,
        text: String,
        nowMillis: Long
    ): Triple<TelegramSendResponse, Long, Int?> {
        var targetChatId = initialTargetChatId
        var topicId: Int? = initialTopicId
        var currentConv = conversation

        var resp = transport.sendMessage(
            botToken = botToken,
            chatId = targetChatId,
            text = text,
            messageThreadId = topicId
        )

        // If the topic was deleted in Telegram ("message thread not found"), recreate the dedicated topic (NEVER fall back to general channel).
        if (!resp.ok && topicId != null &&
            resp.errorDescription?.contains("thread not found", ignoreCase = true) == true
        ) {
            val clearedConv = currentConv?.copy(telegramTopicId = null)
            if (clearedConv != null) {
                dao.updateSupportConversation(clearedConv)
                currentConv = clearedConv
            }
            topicId = ensureTopicForConversation(
                botToken = botToken,
                targetChatId = targetChatId,
                conversation = currentConv,
                nowMillis = nowMillis
            )
            if (topicId == null || topicId <= 0) {
                return Triple(
                    TelegramSendResponse(
                        ok = false,
                        errorDescription = "Dedicated Telegram ticket topic could not be recreated after thread not found."
                    ),
                    targetChatId,
                    null
                )
            }
            resp = transport.sendMessage(
                botToken = botToken,
                chatId = targetChatId,
                text = text,
                messageThreadId = topicId
            )
        }

        // Automatic fallback 1: Supergroup migration
        if (!resp.ok && resp.migratedToChatId != null) {
            val migratedId = resp.migratedToChatId
            registerMigratedSupergroup(supportChatId, migratedId)
            targetChatId = migratedId
            topicId = ensureTopicForConversation(
                botToken = botToken,
                targetChatId = targetChatId,
                conversation = currentConv,
                nowMillis = nowMillis
            )
            if (topicId == null || topicId <= 0) {
                return Triple(
                    TelegramSendResponse(
                        ok = false,
                        errorDescription = "Failed to create dedicated Telegram ticket topic in migrated supergroup."
                    ),
                    targetChatId,
                    null
                )
            }
            resp = transport.sendMessage(
                botToken = botToken,
                chatId = targetChatId,
                text = text,
                messageThreadId = topicId
            )
        }

        // Automatic fallback 2: Positive group ID entered instead of negative supergroup ID
        if (!resp.ok && supportChatId > 0 &&
            resp.errorDescription?.contains("chat not found", ignoreCase = true) == true
        ) {
            targetChatId = resolveTargetChatId(-supportChatId)
            topicId = ensureTopicForConversation(
                botToken = botToken,
                targetChatId = targetChatId,
                conversation = currentConv,
                nowMillis = nowMillis
            )
            if (topicId == null || topicId <= 0) {
                return Triple(
                    TelegramSendResponse(
                        ok = false,
                        errorDescription = "Failed to create dedicated Telegram ticket topic in resolved support group."
                    ),
                    targetChatId,
                    null
                )
            }
            resp = transport.sendMessage(
                botToken = botToken,
                chatId = targetChatId,
                text = text,
                messageThreadId = topicId
            )
        }

        return Triple(resp, targetChatId, topicId)
    }

    private suspend fun dispatchMessageToTelegram(
        message: SupportMessage,
        nowMillis: Long
    ): Result<SupportMessage> {
        val config = configProvider()
        val supportChatId = config.supportChatId
        if (!config.isConfigured() || supportChatId == null) {
            val failed = message.copy(
                deliveryStatus = "FAILED",
                errorMessage = "Telegram Bot credentials are not configured. Set TELEGRAM_BOT_TOKEN and TELEGRAM_SUPPORT_CHAT_ID in Secrets.",
                updatedAt = nowMillis
            )
            dao.updateSupportMessage(failed)
            return Result.failure(IllegalStateException(failed.errorMessage))
        }

        var conversation = dao.getSupportConversationByUid(message.conversationUid)
            ?: dao.getSupportConversationById(message.conversationId, message.userId)
        val userRef = conversation?.userReference ?: generateOpaqueUserReference(message.userId)

        var targetChatId = resolveTargetChatId(supportChatId)
        var topicId = ensureTopicForConversation(
            botToken = config.botToken,
            targetChatId = targetChatId,
            conversation = conversation,
            nowMillis = nowMillis
        )
        if ((topicId == null || topicId <= 0) && supportChatId > 0) {
            targetChatId = resolveTargetChatId(-supportChatId)
            topicId = ensureTopicForConversation(
                botToken = config.botToken,
                targetChatId = targetChatId,
                conversation = conversation,
                nowMillis = nowMillis
            )
        }

        // Strict Topic Isolation (Section 6): NEVER send user messages to the general channel (messageThreadId == null).
        if (topicId == null || topicId <= 0) {
            val topicErrMsg = "Unable to create or resolve dedicated Telegram forum topic for ticket ${message.conversationUid}. Delivery to general channel is prohibited."
            val failed = message.copy(
                deliveryStatus = "FAILED",
                errorMessage = topicErrMsg,
                updatedAt = nowMillis
            )
            dao.updateSupportMessage(failed)
            return Result.failure(IllegalStateException(topicErrMsg))
        }

        if (conversation != null && conversation.telegramTopicId != topicId) {
            conversation = conversation.copy(telegramTopicId = topicId, updatedAt = nowMillis)
            dao.updateSupportConversation(conversation)
        }

        // Section 5A: Send concise ticket initialization message ONCE per ticket (idempotent across retries/reconnects)
        val convId = conversation?.id ?: message.conversationId
        val initMarkerId = ticketInitRecordId(convId)
        val existingInitMarker = dao.getProcessedTelegramUpdateById(initMarkerId)
        if (existingInitMarker == null) {
            val initText = formatTicketInitializationMessage(
                conversationUid = message.conversationUid,
                userReference = userRef
            )
            val (initResp, resolvedChatAfterInit, resolvedTopicAfterInit) = sendMessageToIsolatedTopic(
                botToken = config.botToken,
                supportChatId = supportChatId,
                initialTargetChatId = targetChatId,
                initialTopicId = topicId,
                conversation = conversation,
                text = initText,
                nowMillis = nowMillis
            )
            targetChatId = resolvedChatAfterInit
            if (resolvedTopicAfterInit == null || resolvedTopicAfterInit <= 0 || !initResp.ok || initResp.telegramMessageId == null) {
                val errMsg = initResp.errorDescription ?: "Failed to initialize support ticket in dedicated Telegram topic."
                val failed = message.copy(
                    deliveryStatus = "FAILED",
                    errorMessage = errMsg,
                    updatedAt = nowMillis
                )
                dao.updateSupportMessage(failed)
                return Result.failure(IllegalStateException(errMsg))
            }
            topicId = resolvedTopicAfterInit
            if (conversation != null && conversation.telegramTopicId != topicId) {
                conversation = conversation.copy(telegramTopicId = topicId, updatedAt = nowMillis)
            }
            dao.insertProcessedTelegramUpdate(
                ProcessedTelegramUpdate(
                    updateId = initMarkerId,
                    telegramMessageId = initResp.telegramMessageId,
                    conversationUid = message.conversationUid,
                    status = "TICKET_INITIALIZED",
                    processedAt = nowMillis
                )
            )
        }

        // Section 5B: Format user message as clean chat message inside the ticket topic
        val formattedUserText = formatSubsequentUserMessage(message.messageText)
        val (telegramResp, finalChatId, finalTopicId) = sendMessageToIsolatedTopic(
            botToken = config.botToken,
            supportChatId = supportChatId,
            initialTargetChatId = targetChatId,
            initialTopicId = topicId,
            conversation = conversation,
            text = formattedUserText,
            nowMillis = nowMillis
        )

        return if (telegramResp.ok && telegramResp.telegramMessageId != null && finalTopicId != null && finalTopicId > 0) {
            val confirmed = message.copy(
                deliveryStatus = "SENT_TO_TELEGRAM",
                telegramChatId = telegramResp.telegramChatId ?: finalChatId,
                telegramMessageId = telegramResp.telegramMessageId,
                errorMessage = null,
                updatedAt = nowMillis
            )
            dao.updateSupportMessage(confirmed)
            Result.success(confirmed)
        } else {
            val errMsg = telegramResp.errorDescription ?: "Failed to deliver message to dedicated Telegram ticket topic."
            val failed = message.copy(
                deliveryStatus = "FAILED",
                errorMessage = errMsg,
                updatedAt = nowMillis
            )
            dao.updateSupportMessage(failed)
            Result.failure(IllegalStateException(errMsg))
        }
    }

    /**
     * Handles an inbound Telegram webhook request after verifying the secret token header.
     */
    suspend fun handleWebhookRequest(
        headers: Map<String, String>,
        rawBodyJson: String,
        nowMillis: Long = System.currentTimeMillis()
    ): InboundUpdateResult {
        val config = configProvider()
        val expectedSecret = config.webhookSecret.trim()
        val providedSecret = headers.entries
            .firstOrNull { it.key.equals(HEADER_TELEGRAM_SECRET_TOKEN, ignoreCase = true) }
            ?.value
            ?.trim()

        if (expectedSecret.isBlank() || providedSecret.isNullOrBlank() || providedSecret != expectedSecret) {
            throw WebhookAuthenticationException("Invalid or missing $HEADER_TELEGRAM_SECRET_TOKEN header.")
        }

        val updateJson = try {
            JSONObject(rawBodyJson)
        } catch (e: Exception) {
            throw IllegalArgumentException("Malformed Telegram update JSON payload: ${e.message}")
        }

        return processTelegramUpdate(updateJson, nowMillis)
    }

    /**
     * Polls Telegram `getUpdates` for new replies in the support group and routes any valid
     * `reply_to_message` or topic (`message_thread_id`) responses to their matching user conversation.
     */
    suspend fun pollIncomingUpdates(
        nowMillis: Long = System.currentTimeMillis()
    ): Result<List<InboundUpdateResult>> {
        val config = configProvider()
        if (!config.isConfigured()) {
            return Result.failure(IllegalStateException("Telegram Bot API is not configured."))
        }
        val maxProcessedId = dao.getMaxProcessedTelegramUpdateId()
        val nextOffset = maxProcessedId?.let { it + 1L }
        val updatesResult = transport.getUpdates(
            botToken = config.botToken,
            offset = nextOffset,
            timeoutSeconds = 0
        )
        if (updatesResult.isFailure) {
            return Result.failure(updatesResult.exceptionOrNull()!!)
        }
        val updates = updatesResult.getOrThrow()
        val results = mutableListOf<InboundUpdateResult>()
        for (updateObj in updates) {
            results.add(processTelegramUpdate(updateObj, nowMillis))
        }
        return Result.success(results)
    }

    /**
     * Processes a single Telegram Update JSON object and routes valid replies to the matching user.
     *
     * Strict security and routing rules:
     * 1. Idempotency check on `update_id` and `message.message_id`.
     * 2. Verifies `message.chat.id` matches the configured `TELEGRAM_SUPPORT_CHAT_ID` (or its migrated supergroup).
     * 3. Verifies sender is a human support representative (`is_bot == false`) and in `authorizedUserIds` (if configured).
     * 4. Matches either `reply_to_message.message_id` against stored `SupportMessage.telegramMessageId` OR
     *    `message_thread_id` against the user conversation's dedicated Forum Topic (`SupportConversation.telegramTopicId`).
     *    Never matches by text alone, and never exposes unrelated group chat messages to users.
     */
    suspend fun processTelegramUpdate(
        updateJson: JSONObject,
        nowMillis: Long = System.currentTimeMillis()
    ): InboundUpdateResult = sendMutex.withLock {
        val config = configProvider()
        val updateId = updateJson.optLong("update_id", -1L)
        if (updateId <= 0L) {
            return@withLock InboundUpdateResult.UnmatchedIgnored(-1L, "Missing or invalid update_id.")
        }

        // 1. Idempotency check on update_id
        val existingUpdate = dao.getProcessedTelegramUpdateById(updateId)
        if (existingUpdate != null) {
            return@withLock InboundUpdateResult.DuplicateIgnored(
                updateId = updateId,
                reason = "Update $updateId was already processed (${existingUpdate.status})."
            )
        }

        val messageObj = updateJson.optJSONObject("message")
            ?: updateJson.optJSONObject("edited_message")
        if (messageObj == null) {
            dao.insertProcessedTelegramUpdate(
                ProcessedTelegramUpdate(
                    updateId = updateId,
                    telegramMessageId = -1L,
                    conversationUid = null,
                    status = "IGNORED_UNMATCHED",
                    processedAt = nowMillis
                )
            )
            return@withLock InboundUpdateResult.UnmatchedIgnored(updateId, "Update does not contain a message.")
        }

        // Check if this update is a group->supergroup migration service message
        val migrateToChatId = messageObj.optLong("migrate_to_chat_id", 0L).takeIf { it != 0L }
        if (migrateToChatId != null && config.supportChatId != null) {
            registerMigratedSupergroup(config.supportChatId, migrateToChatId)
        }

        val inboundTelegramMessageId = messageObj.optLong("message_id", -1L)
        if (inboundTelegramMessageId > 0L) {
            val duplicateByMsgId = dao.getProcessedTelegramUpdateByMessageId(inboundTelegramMessageId)
            if (duplicateByMsgId != null && duplicateByMsgId.status != "TICKET_INITIALIZED") {
                dao.insertProcessedTelegramUpdate(
                    ProcessedTelegramUpdate(
                        updateId = updateId,
                        telegramMessageId = inboundTelegramMessageId,
                        conversationUid = duplicateByMsgId.conversationUid,
                        status = "ROUTED",
                        processedAt = nowMillis
                    )
                )
                return@withLock InboundUpdateResult.DuplicateIgnored(
                    updateId = updateId,
                    reason = "Telegram message $inboundTelegramMessageId was already routed."
                )
            }
        }

        // 2. Verify message originates from the configured Telegram support group (or its upgraded supergroup)
        val chatObj = messageObj.optJSONObject("chat")
        val chatId = chatObj?.optLong("id")
        val configuredChatId = config.supportChatId
        val migratedChatId = configuredChatId?.let { resolveTargetChatId(it) }
        val isChatMatch = configuredChatId != null && chatId != null && (
            chatId == configuredChatId ||
            chatId == -kotlin.math.abs(configuredChatId) ||
            kotlin.math.abs(chatId) == kotlin.math.abs(configuredChatId) ||
            (migratedChatId != null && chatId == migratedChatId)
        )
        if (!isChatMatch) {
            dao.insertProcessedTelegramUpdate(
                ProcessedTelegramUpdate(
                    updateId = updateId,
                    telegramMessageId = inboundTelegramMessageId,
                    conversationUid = null,
                    status = "REJECTED_UNAUTHORIZED",
                    processedAt = nowMillis
                )
            )
            return@withLock InboundUpdateResult.RejectedUnauthorized(
                updateId = updateId,
                reason = "Update chat_id ($chatId) does not match configured support group ($configuredChatId)."
            )
        }

        // 3. Verify sender is an authorized human support representative (not a bot)
        val fromObj = messageObj.optJSONObject("from")
        val senderId = fromObj?.optLong("id", -1L) ?: -1L
        val isBot = fromObj?.optBoolean("is_bot", true) ?: true
        if (fromObj == null || senderId <= 0L || isBot) {
            dao.insertProcessedTelegramUpdate(
                ProcessedTelegramUpdate(
                    updateId = updateId,
                    telegramMessageId = inboundTelegramMessageId,
                    conversationUid = null,
                    status = "REJECTED_UNAUTHORIZED",
                    processedAt = nowMillis
                )
            )
            return@withLock InboundUpdateResult.RejectedUnauthorized(
                updateId = updateId,
                reason = "Sender is missing or is a bot account."
            )
        }

        if (config.authorizedUserIds.isNotEmpty() && senderId !in config.authorizedUserIds) {
            dao.insertProcessedTelegramUpdate(
                ProcessedTelegramUpdate(
                    updateId = updateId,
                    telegramMessageId = inboundTelegramMessageId,
                    conversationUid = null,
                    status = "REJECTED_UNAUTHORIZED",
                    processedAt = nowMillis
                )
            )
            return@withLock InboundUpdateResult.RejectedUnauthorized(
                updateId = updateId,
                reason = "Sender $senderId is not in the authorized support representative allowlist."
            )
        }

        // 4. Match either by reply_to_message.message_id OR by dedicated Forum Topic (message_thread_id)
        val replyToObj = messageObj.optJSONObject("reply_to_message")
        val replyToTelegramMessageId = replyToObj?.optLong("message_id", -1L) ?: -1L
        val messageThreadId = messageObj.optInt("message_thread_id", -1)

        var originalMessage = if (replyToTelegramMessageId > 0L) {
            dao.getSupportMessageByTelegramMessageId(replyToTelegramMessageId)
        } else {
            null
        }

        val initTicketConversation = if (originalMessage == null && replyToTelegramMessageId > 0L) {
            val initUpdate = dao.getProcessedTelegramUpdateByMessageId(replyToTelegramMessageId)
            if (initUpdate != null && initUpdate.status == "TICKET_INITIALIZED" && !initUpdate.conversationUid.isNullOrBlank()) {
                dao.getSupportConversationByUid(initUpdate.conversationUid)
            } else {
                null
            }
        } else {
            null
        }

        val conversation = when {
            originalMessage != null -> {
                dao.getSupportConversationByUid(originalMessage.conversationUid)
                    ?: dao.getSupportConversationById(originalMessage.conversationId, originalMessage.userId)
            }
            initTicketConversation != null -> initTicketConversation
            messageThreadId > 0 -> dao.getSupportConversationByTopicId(messageThreadId)
            else -> null
        }

        if (conversation == null) {
            dao.insertProcessedTelegramUpdate(
                ProcessedTelegramUpdate(
                    updateId = updateId,
                    telegramMessageId = inboundTelegramMessageId,
                    conversationUid = null,
                    status = "IGNORED_UNMATCHED",
                    processedAt = nowMillis
                )
            )
            val reason = if (replyToTelegramMessageId <= 0L && messageThreadId <= 0) {
                "Non-reply group message ignored (missing reply_to_message.message_id and message_thread_id)."
            } else {
                "No stored support conversation matches reply_to_message.message_id=$replyToTelegramMessageId or message_thread_id=$messageThreadId."
            }
            return@withLock InboundUpdateResult.UnmatchedIgnored(
                updateId = updateId,
                reason = reason
            )
        }

        if (originalMessage == null) {
            originalMessage = dao.getLatestUserSupportMessageForConversation(conversation.id)
        }

        // 5. Validate and sanitize reply content
        val rawReplyText = messageObj.optString("text").ifBlank { messageObj.optString("caption") }
        val sanitizedReply = sanitizeMessageText(rawReplyText)
        if (sanitizedReply.isBlank()) {
            dao.insertProcessedTelegramUpdate(
                ProcessedTelegramUpdate(
                    updateId = updateId,
                    telegramMessageId = inboundTelegramMessageId,
                    conversationUid = conversation.conversationUid,
                    status = "IGNORED_UNMATCHED",
                    processedAt = nowMillis
                )
            )
            return@withLock InboundUpdateResult.UnmatchedIgnored(
                updateId = updateId,
                reason = "Inbound Telegram reply contains no text content."
            )
        }

        val firstName = fromObj.optString("first_name", "Support")
        val lastName = fromObj.optString("last_name", "")
        val senderDisplayName = listOf(firstName, lastName)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "Support Representative" }

        val idempotencyKey = "tg_inbound_${updateId}_${inboundTelegramMessageId}"
        val replyMessage = SupportMessage(
            conversationId = conversation.id,
            conversationUid = conversation.conversationUid,
            userId = conversation.userId,
            senderType = "SUPPORT_AGENT",
            senderName = senderDisplayName,
            senderTelegramUserId = senderId,
            messageText = sanitizedReply.take(MAX_MESSAGE_LENGTH),
            deliveryStatus = "DELIVERED",
            clientIdempotencyKey = idempotencyKey,
            telegramChatId = chatId,
            telegramMessageId = inboundTelegramMessageId,
            replyToTelegramMessageId = replyToTelegramMessageId.takeIf { it > 0L } ?: originalMessage?.telegramMessageId,
            telegramUpdateId = updateId,
            createdAt = nowMillis,
            updatedAt = nowMillis
        )

        val insertedId = dao.insertSupportMessageIgnore(replyMessage).toInt()
        val persistedReply = if (insertedId > 0) {
            replyMessage.copy(id = insertedId)
        } else {
            dao.getSupportMessageByIdempotencyKey(idempotencyKey) ?: replyMessage
        }

        // Mark original user message as DELIVERED if it was SENT_TO_TELEGRAM
        if (originalMessage != null && originalMessage.deliveryStatus != "DELIVERED") {
            dao.updateSupportMessage(
                originalMessage.copy(
                    deliveryStatus = "DELIVERED",
                    updatedAt = nowMillis
                )
            )
        }

        // Update conversation status & preview
        dao.updateSupportConversation(
            conversation.copy(
                status = "REPLIED",
                updatedAt = nowMillis,
                lastMessagePreview = sanitizedReply.take(120)
            )
        )

        dao.insertProcessedTelegramUpdate(
            ProcessedTelegramUpdate(
                updateId = updateId,
                telegramMessageId = inboundTelegramMessageId,
                conversationUid = conversation.conversationUid,
                status = "ROUTED",
                processedAt = nowMillis
            )
        )

        InboundUpdateResult.RoutedReply(
            conversationUid = conversation.conversationUid,
            userId = conversation.userId,
            supportMessage = persistedReply
        )
    }
}
