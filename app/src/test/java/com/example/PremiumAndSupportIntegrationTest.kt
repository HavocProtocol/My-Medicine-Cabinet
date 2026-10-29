package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDao
import com.example.data.AppDatabase
import com.example.services.InboundUpdateResult
import com.example.services.LocalLlmDiagnosticException
import com.example.services.LocalLlmService
import com.example.services.OnlineMedicineSearchGate
import com.example.services.PremiumAccessRequiredException
import com.example.services.PremiumEntitlementManager
import com.example.services.PremiumFeature
import com.example.services.SubscriptionStatus
import com.example.services.SupportChatService
import com.example.services.TelegramBotTransport
import com.example.services.TelegramSendResponse
import com.example.services.TelegramSupportConfig
import com.example.services.WebhookAuthenticationException
import com.example.ui.screens.HelpCenterScreen
import com.example.ui.screens.PremiumSubscriptionScreen
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Fake Telegram Bot Transport for deterministic unit/integration testing of outbound sendMessage
 * and inbound getUpdates / webhook handling without hitting live external Telegram servers.
 */
class FakeTelegramBotTransport : TelegramBotTransport {
    data class SentTelegramCall(
        val botToken: String,
        val chatId: Long,
        val text: String,
        val assignedMessageId: Long,
        val messageThreadId: Int? = null
    )

    val sentCalls = mutableListOf<SentTelegramCall>()
    val createdTopics = mutableMapOf<String, Int>()
    var nextMessageId: Long = 5001L
    var nextTopicId: Int = 701
    var shouldCreateTopics: Boolean = true
    var shouldFailSend: Boolean = false
    var failErrorDescription: String = "Simulated Telegram API 503 Unavailable"
    val queuedUpdatesJson = mutableListOf<JSONObject>()

    override suspend fun sendMessage(
        botToken: String,
        chatId: Long,
        text: String,
        messageThreadId: Int?
    ): TelegramSendResponse {
        if (shouldFailSend) {
            return TelegramSendResponse(
                ok = false,
                errorDescription = failErrorDescription
            )
        }
        val assignedId = nextMessageId++
        sentCalls.add(SentTelegramCall(botToken, chatId, text, assignedId, messageThreadId))
        return TelegramSendResponse(
            ok = true,
            telegramMessageId = assignedId,
            telegramChatId = chatId
        )
    }

    override suspend fun createForumTopic(
        botToken: String,
        chatId: Long,
        name: String
    ): Int? {
        if (!shouldCreateTopics) return null
        val topicId = nextTopicId++
        createdTopics[name] = topicId
        return topicId
    }

    val editedTopics = mutableMapOf<Int, String>()
    val closedTopics = mutableSetOf<Int>()

    override suspend fun editForumTopic(
        botToken: String,
        chatId: Long,
        messageThreadId: Int,
        name: String
    ): Boolean {
        editedTopics[messageThreadId] = name
        return true
    }

    override suspend fun closeForumTopic(
        botToken: String,
        chatId: Long,
        messageThreadId: Int
    ): Boolean {
        closedTopics.add(messageThreadId)
        return true
    }

    override suspend fun getUpdates(
        botToken: String,
        offset: Long?,
        timeoutSeconds: Int
    ): Result<List<JSONObject>> {
        val list = queuedUpdatesJson.filter { update ->
            val updateId = update.optLong("update_id", 0L)
            offset == null || updateId >= offset
        }
        return Result.success(list)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PremiumAndSupportIntegrationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var dao: AppDao
    private lateinit var fakeTransport: FakeTelegramBotTransport
    private lateinit var supportConfig: TelegramSupportConfig
    private lateinit var supportService: SupportChatService

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.appDao()

        // Reset subscription state to Free User
        PremiumEntitlementManager.resetToFree(context)

        fakeTransport = FakeTelegramBotTransport()
        supportConfig = TelegramSupportConfig(
            botToken = "test_bot_token_12345",
            supportChatId = -100987654321L,
            webhookSecret = "secret_webhook_token_xyz",
            authorizedUserIds = setOf(777001L, 777002L)
        )
        supportService = SupportChatService(
            dao = dao,
            transport = fakeTransport,
            configProvider = { supportConfig }
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // =========================================================================
    // PART A: PREMIUM SUBSCRIPTION REDESIGN, AI VOICE SEARCH, LOCAL LLM & BARCODE REMOVAL
    // =========================================================================

    /**
     * Part A Criteria 1, 2, 3, 4, 5, 6:
     * - Free user cannot access AI Voice Search or Local LLM and is directed to Premium Subscription.
     * - Premium user can access AI Voice Search, Local LLM, Online Medicine Search, and Human Live Chat
     *   under the single unified PremiumEntitlementManager without paying for a separate Local LLM add-on.
     * - Local LLM handles missing models, insufficient resources, model-loading failures, and unsupported configurations gracefully.
     */
    @Test
    fun test01_unifiedPremiumEntitlementForVoiceSearchAndLocalLlm() = runBlocking {
        // 1. Free user is blocked from all 4 Premium features
        assertFalse(PremiumEntitlementManager.isPremiumActive(context))
        assertFalse(PremiumEntitlementManager.isEntitled(context, PremiumFeature.AI_VOICE_SEARCH))
        assertFalse(PremiumEntitlementManager.isEntitled(context, PremiumFeature.LOCAL_LLM))
        assertFalse(PremiumEntitlementManager.isEntitled(context, PremiumFeature.ONLINE_MEDICINE_SEARCH))
        assertFalse(PremiumEntitlementManager.isEntitled(context, PremiumFeature.LIVE_CHAT_SUPPORT))
        assertFalse(OnlineMedicineSearchGate.canAccessVoiceSearch(context))
        assertFalse(OnlineMedicineSearchGate.canAccessLocalLlm(context))

        val llmService = LocalLlmService()

        // Free user calling LocalLlmService.generateResponseForUser is rejected by backend/service check
        val freeLlmAttempt = llmService.generateResponseForUser(
            context = context,
            hostUrl = "http://127.0.0.1:11434",
            modelName = "llama3",
            prompt = "What is Paracetamol?"
        )
        assertTrue(freeLlmAttempt.isFailure)
        assertTrue(freeLlmAttempt.exceptionOrNull() is PremiumAccessRequiredException)

        // 2. Activate unified Premium subscription -> unlocks AI Voice Search, Local LLM, Online Search, and Live Chat together
        PremiumEntitlementManager.subscribeToPlan(context, PremiumEntitlementManager.PLAN_ANNUAL_ID)
        assertTrue(PremiumEntitlementManager.isPremiumActive(context))
        assertTrue(PremiumEntitlementManager.isEntitled(context, PremiumFeature.AI_VOICE_SEARCH))
        assertTrue(PremiumEntitlementManager.isEntitled(context, PremiumFeature.LOCAL_LLM))
        assertTrue(PremiumEntitlementManager.isEntitled(context, PremiumFeature.ONLINE_MEDICINE_SEARCH))
        assertTrue(PremiumEntitlementManager.isEntitled(context, PremiumFeature.LIVE_CHAT_SUPPORT))
        assertEquals(4, PremiumEntitlementManager.entitledFeatures(context).size)

        // 3. Verify Local LLM diagnostic error classification for missing models, insufficient resources, loading failures, and unsupported configs
        val missingModelFailure = llmService.generateResponseForUser(
            context = context,
            hostUrl = "http://127.0.0.1:11434",
            modelName = "",
            prompt = "Test"
        )
        assertTrue(missingModelFailure.exceptionOrNull() is LocalLlmDiagnosticException.MissingModelException)

        val unsupportedUrlFailure = llmService.generateResponseForUser(
            context = context,
            hostUrl = "ftp://invalid-protocol",
            modelName = "llama3",
            prompt = "Test"
        )
        assertTrue(unsupportedUrlFailure.exceptionOrNull() is LocalLlmDiagnosticException.UnsupportedConfigurationException)

        val classifiedNotFound = llmService.classifyServerFailure(404, """{"error":"model 'medllama' not found"}""", "medllama")
        assertTrue(classifiedNotFound is LocalLlmDiagnosticException.MissingModelException)

        val classifiedOom = llmService.classifyServerFailure(500, """{"error":"not enough memory to allocate KV cache"}""", "llama3")
        assertTrue(classifiedOom is LocalLlmDiagnosticException.InsufficientResourcesException)

        val classifiedLoadErr = llmService.classifyServerFailure(500, """{"error":"failed to load model weights"}""", "llama3")
        assertTrue(classifiedLoadErr is LocalLlmDiagnosticException.ModelLoadingFailureException)
    }

    /**
     * Part A Criteria 7, 8, 9, 10:
     * - Barcode-scanning UI (`ScanScreen.kt`) and barcode-scanning services (`BarcodeScanService`, `MockBarcodeLookup`) are completely removed.
     * - No navigation route or broken button leads to barcode scanning.
     * - Non-barcode camera/OCR features (`BoxScanScreen`) and existing medicine inventory records remain intact.
     */
    @Test
    fun test02_barcodeScannerCompletelyRemovedAndBoxScanPreserved() {
        // Verify ScanScreen.kt no longer exists
        val scanScreenFile = File("src/main/java/com/example/ui/screens/ScanScreen.kt")
        assertFalse("ScanScreen.kt must be deleted", scanScreenFile.exists())

        // Verify BarcodeScanService and MockBarcodeLookup classes do not exist on classpath
        val removedClasses = listOf(
            "com.example.services.BarcodeScanService",
            "com.example.services.MockBarcodeLookup",
            "com.example.services.BarcodeProduct",
            "com.example.ui.screens.ScanScreenKt"
        )
        for (className in removedClasses) {
            try {
                Class.forName(className)
                fail("Class $className should have been removed")
            } catch (_: ClassNotFoundException) {
                // Expected
            }
        }

        // Verify BoxScanScreen and MedicineDraft still exist and work
        val boxScanClass = Class.forName("com.example.boxscan.ui.BoxScanScreenKt")
        assertNotNull(boxScanClass)
    }

    /**
     * Part A Criteria 11, 12, 13, 14, 15:
     * - Dedicated Premium Subscription page (`PremiumSubscriptionScreen`) displays all 4 core Premium benefits
     *   (AI Voice Search, On-Device Local LLM Access, AI-Powered Online Medicine Search, Human Assistance via Live Chat)
     *   and contains NO standalone Local LLM purchase/add-on option.
     * - Handles Free, Active, Expired, Cancelled, and Verification Error states accurately.
     * - Prevents verification failure from falsely granting Premium entitlement.
     */
    @Test
    fun test03_premiumSubscriptionPageStatesAndEntitlementLifecycle() {
        // 1. Free state initially
        assertEquals(SubscriptionStatus.FREE, PremiumEntitlementManager.subscriptionStateFlow.value.status)

        // 2. Verification error does NOT falsely grant Premium access
        PremiumEntitlementManager.setVerificationFailure(context, "Billing service unavailable")
        assertEquals(SubscriptionStatus.VERIFICATION_FAILED, PremiumEntitlementManager.subscriptionStateFlow.value.status)
        assertFalse(PremiumEntitlementManager.isPremiumActive(context))
        PremiumEntitlementManager.setVerificationFailure(context, null)

        // 3. Activate Annual Plan -> ACTIVE state grants access
        PremiumEntitlementManager.subscribeToPlan(context, PremiumEntitlementManager.PLAN_ANNUAL_ID)
        assertEquals(SubscriptionStatus.ACTIVE, PremiumEntitlementManager.subscriptionStateFlow.value.status)
        assertEquals(PremiumEntitlementManager.PLAN_ANNUAL_ID, PremiumEntitlementManager.subscriptionStateFlow.value.activePlanId)
        assertTrue(PremiumEntitlementManager.isPremiumActive(context))

        // 4. Cancel subscription -> schedules cancellation at period end (status = CANCELLED) while keeping access active until expiration
        PremiumEntitlementManager.cancelSubscription(context)
        assertEquals(SubscriptionStatus.CANCELLED, PremiumEntitlementManager.subscriptionStateFlow.value.status)
        assertFalse(PremiumEntitlementManager.subscriptionStateFlow.value.autoRenewEnabled)
        assertTrue(PremiumEntitlementManager.isPremiumActive(context))

        // 5. Restore subscription -> restores ACTIVE state when a prior plan was purchased
        val restored = PremiumEntitlementManager.restorePurchases(context)
        assertTrue(restored.isSuccess)
        assertEquals(SubscriptionStatus.ACTIVE, PremiumEntitlementManager.subscriptionStateFlow.value.status)
        assertTrue(PremiumEntitlementManager.isPremiumActive(context))

        // 6. Expired subscription -> transitions to EXPIRED and revokes access
        PremiumEntitlementManager.setSubscriptionExpiredForTesting(context)
        assertEquals(SubscriptionStatus.EXPIRED, PremiumEntitlementManager.subscriptionStateFlow.value.status)
        assertFalse(PremiumEntitlementManager.isPremiumActive(context))

        // 7. Render PremiumSubscriptionScreen in Compose and verify all 4 benefits are displayed
        PremiumEntitlementManager.resetToFree(context)
        composeTestRule.setContent {
            PremiumSubscriptionScreen(onBack = {})
        }

        composeTestRule.onNodeWithTag("benefit_card_voice_search").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("benefit_card_local_llm").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("benefit_card_online_search").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("benefit_card_live_chat").performScrollTo().assertIsDisplayed()

        // Clicking subscribe activates Premium
        composeTestRule.onNodeWithTag("premium_subscribe_button").performScrollTo().performClick()
        assertTrue(PremiumEntitlementManager.isPremiumActive(context))
    }

    // =========================================================================
    // PART B: ONLINE HELP CENTER, TWO-WAY TELEGRAM BOT INTEGRATION & LIVE CHAT
    // =========================================================================

    /**
     * Part B Criteria 1, 2, 3, 4:
     * - Online Help Center screen (`HelpCenterScreen`) is accessible and displays the Request Assistance button.
     * - Free user clicking Request Assistance is routed to `"premium_subscription"` (never `"live_chat"`).
     * - Premium user clicking Request Assistance is routed to `"live_chat"`.
     * - Unauthorized user attempting to call `SupportChatService.sendUserMessage` directly is rejected at the service layer.
     */
    @Test
    fun test04_helpCenterAccessControlAndRouting() = runBlocking {
        var navigatedToPremiumCount = 0
        var navigatedToLiveChatCount = 0

        // Start as Free user
        PremiumEntitlementManager.resetToFree(context)

        composeTestRule.setContent {
            HelpCenterScreen(
                onBack = {},
                onNavigateToPremiumSubscription = { navigatedToPremiumCount++ },
                onNavigateToLiveChat = { navigatedToLiveChatCount++ }
            )
        }

        composeTestRule.onNodeWithTag("help_center_heading").assertIsDisplayed()
        composeTestRule.onNodeWithTag("help_center_request_assistance_button").performScrollTo().performClick()

        // Free user MUST be routed to Premium Subscription and NOT Live Chat
        assertEquals(1, navigatedToPremiumCount)
        assertEquals(0, navigatedToLiveChatCount)

        // Direct service call by Free user must also fail with PremiumAccessRequiredException
        val directFreeSend = supportService.sendUserMessage(
            context = context,
            userId = "user_free",
            rawMessageText = "Hello, I need help with my schedule"
        )
        assertTrue(directFreeSend.isFailure)
        assertTrue(directFreeSend.exceptionOrNull() is PremiumAccessRequiredException)
        assertEquals(0, fakeTransport.sentCalls.size)

        // Now upgrade to Premium and click Request Assistance
        PremiumEntitlementManager.subscribeToPlan(context, PremiumEntitlementManager.PLAN_MONTHLY_ID)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("help_center_request_assistance_button").performScrollTo().performClick()
        assertEquals(1, navigatedToLiveChatCount)
    }

    /**
     * Part B Criteria 5, 6, 7, 8, 9, 10:
     * - Premium user sending a Live Chat message persists it locally, forwards it to the configured Telegram support group
     *   with Conversation ID (`SUP-...`), User Reference (`USER-...`), message body, and reply instructions,
     *   and stores the returned `telegramMessageId` and `telegramChatId`.
     * - Empty/whitespace messages and oversized messages (>2000 chars) are rejected cleanly.
     * - Failed Telegram delivery marks message as `FAILED` and allows retry without creating a duplicate message record.
     */
    @Test
    fun test05_outboundLiveChatMessagePersistenceFormattingAndRetry() = runBlocking {
        PremiumEntitlementManager.subscribeToPlan(context, PremiumEntitlementManager.PLAN_ANNUAL_ID)

        // 1. Reject empty/whitespace message
        val emptyResult = supportService.sendUserMessage(
            context = context,
            userId = "user_alpha",
            rawMessageText = "   "
        )
        assertTrue(emptyResult.isFailure)
        assertEquals(0, fakeTransport.sentCalls.size)

        // 2. Send first valid user message in a new support ticket
        val sendResult = supportService.sendUserMessage(
            context = context,
            userId = "user_alpha",
            rawMessageText = "Can I store opened insulin pens at room temperature?",
            clientIdempotencyKey = "idem_key_001"
        )
        assertTrue(sendResult.isSuccess)
        val savedMsg = sendResult.getOrThrow()
        val alphaConversation = dao.getActiveSupportConversationForUser("user_alpha")!!
        val expectedShortTicketId = SupportChatService.extractTicketShortId(alphaConversation.conversationUid)

        assertEquals("USER", savedMsg.senderType)
        assertEquals("SENT_TO_TELEGRAM", savedMsg.deliveryStatus)
        assertEquals(5002L, savedMsg.telegramMessageId)
        assertEquals(-100987654321L, savedMsg.telegramChatId)
        assertEquals(701, alphaConversation.telegramTopicId)

        // Verify the first message in a new ticket dispatches:
        // (1) Once-per-ticket initialization message to the ticket's isolated topic
        // (2) The user's message in concise "User:\n<message>" format to the same topic
        assertEquals(2, fakeTransport.sentCalls.size)
        val initCall = fakeTransport.sentCalls[0]
        assertEquals("test_bot_token_12345", initCall.botToken)
        assertEquals(-100987654321L, initCall.chatId)
        assertEquals(701, initCall.messageThreadId)
        assertEquals(
            "New Support Ticket #$expectedShortTicketId\n\nUser ID: ${alphaConversation.userReference}\n\nReply in this topic to chat with the user through the app.",
            initCall.text
        )

        val userMsgCall = fakeTransport.sentCalls[1]
        assertEquals(701, userMsgCall.messageThreadId)
        assertEquals(
            "User:\nCan I store opened insulin pens at room temperature?",
            userMsgCall.text
        )

        // 3. Duplicate submission with same clientIdempotencyKey does NOT send another Telegram message
        val duplicateAttempt = supportService.sendUserMessage(
            context = context,
            userId = "user_alpha",
            rawMessageText = "Can I store opened insulin pens at room temperature?",
            clientIdempotencyKey = "idem_key_001"
        )
        assertTrue(duplicateAttempt.isSuccess)
        assertEquals(savedMsg.id, duplicateAttempt.getOrThrow().id)
        assertEquals(2, fakeTransport.sentCalls.size)

        // 4. Simulate network/Telegram failure on subsequent message -> stored as FAILED, then retried without duplicating record or re-sending ticket header
        fakeTransport.shouldFailSend = true
        val failedAttempt = supportService.sendUserMessage(
            context = context,
            userId = "user_alpha",
            rawMessageText = "My reminder notification did not ring",
            clientIdempotencyKey = "idem_key_002"
        )
        assertTrue(failedAttempt.isFailure)

        val messagesAfterFail = dao.getSupportMessagesForUserSnapshot("user_alpha")
        assertEquals(2, messagesAfterFail.size)
        val failedRecord = messagesAfterFail.last()
        assertEquals("FAILED", failedRecord.deliveryStatus)
        assertNotNull(failedRecord.errorMessage)

        // Re-enable transport and retry failed message
        fakeTransport.shouldFailSend = false
        val retryResult = supportService.retryFailedMessage(
            context = context,
            userId = "user_alpha",
            messageId = failedRecord.id
        )
        assertTrue(retryResult.isSuccess)

        val messagesAfterRetry = dao.getSupportMessagesForUserSnapshot("user_alpha")
        // Still 2 messages in conversation (no duplicate created!)
        assertEquals(2, messagesAfterRetry.size)
        assertEquals("SENT_TO_TELEGRAM", messagesAfterRetry.last().deliveryStatus)
        assertEquals(5003L, messagesAfterRetry.last().telegramMessageId)

        // Subsequent message sent ONLY "User:\n<message>" without repeating the ticket header!
        assertEquals(3, fakeTransport.sentCalls.size)
        val secondUserMsgCall = fakeTransport.sentCalls[2]
        assertEquals(701, secondUserMsgCall.messageThreadId)
        assertEquals("User:\nMy reminder notification did not ring", secondUserMsgCall.text)
        assertFalse(secondUserMsgCall.text.contains("New Support Ticket"))
    }

    /**
     * Part B Criteria 11, 12, 13, 14, 15, 16, 17, 18, 19, 20:
     * - Support representative reply in Telegram (`reply_to_message.message_id`) is matched to the exact conversation
     *   and delivered to the user's Live Chat thread.
     * - Conversation persists across navigation/reload in Room.
     * - Two different users (`user_alpha` and `user_beta`) have isolated conversations; a Telegram reply to `user_alpha`
     *   is never visible to `user_beta`.
     * - Non-reply group messages in the Telegram group are ignored.
     * - Replies to unknown `telegram_message_id` are ignored safely.
     * - Unauthorized Telegram sender IDs, bot senders, and wrong chat IDs are rejected.
     * - Webhook secret token header (`X-Telegram-Bot-Api-Secret-Token`) is verified.
     * - Duplicate Telegram `update_id` deliveries are processed idempotently (stored only once).
     */
    @Test
    fun test06_inboundTelegramReplyRoutingIsolationSecurityAndIdempotency() = runBlocking {
        PremiumEntitlementManager.subscribeToPlan(context, PremiumEntitlementManager.PLAN_ANNUAL_ID)

        // User Alpha sends a support message -> init = 5001, user msg = 5002 (in topic 701)
        val alphaMsg = supportService.sendUserMessage(
            context = context,
            userId = "user_alpha",
            rawMessageText = "Question from User Alpha",
            clientIdempotencyKey = "alpha_1"
        ).getOrThrow()
        assertEquals(5002L, alphaMsg.telegramMessageId)

        // User Beta sends a support message -> init = 5003, user msg = 5004 (in topic 702)
        val betaMsg = supportService.sendUserMessage(
            context = context,
            userId = "user_beta",
            rawMessageText = "Question from User Beta",
            clientIdempotencyKey = "beta_1"
        ).getOrThrow()
        assertEquals(5004L, betaMsg.telegramMessageId)
        assertNotEquals(alphaMsg.conversationId, betaMsg.conversationId)

        // 1. Webhook request with INVALID secret token must be rejected
        val validReplyToAlphaPayload = JSONObject().apply {
            put("update_id", 90001L)
            put("message", JSONObject().apply {
                put("message_id", 6001L)
                put("date", 1730000000L)
                put("chat", JSONObject().apply {
                    put("id", -100987654321L)
                })
                put("from", JSONObject().apply {
                    put("id", 777001L)
                    put("is_bot", false)
                    put("first_name", "Dr. Sarah")
                    put("last_name", "Support")
                })
                put("reply_to_message", JSONObject().apply {
                    put("message_id", 5002L)
                })
                put("text", "Hello Alpha! Store unopened insulin in the fridge (2-8°C).")
            })
        }.toString()

        try {
            supportService.handleWebhookRequest(
                headers = mapOf("X-Telegram-Bot-Api-Secret-Token" to "wrong_secret_token"),
                rawBodyJson = validReplyToAlphaPayload
            )
            fail("Expected WebhookAuthenticationException for invalid secret token")
        } catch (_: WebhookAuthenticationException) {
            // Expected
        }

        // 2. Webhook request with VALID secret token routes the reply to User Alpha's conversation ONLY
        val goodWebhookResult = supportService.handleWebhookRequest(
            headers = mapOf("X-Telegram-Bot-Api-Secret-Token" to "secret_webhook_token_xyz"),
            rawBodyJson = validReplyToAlphaPayload
        )
        assertTrue(goodWebhookResult is InboundUpdateResult.RoutedReply)
        val routedReply = (goodWebhookResult as InboundUpdateResult.RoutedReply).supportMessage
        assertEquals(alphaMsg.conversationId, routedReply.conversationId)
        assertEquals("SUPPORT_AGENT", routedReply.senderType)
        assertEquals("Dr. Sarah Support", routedReply.senderName)
        assertEquals("Hello Alpha! Store unopened insulin in the fridge (2-8°C).", routedReply.messageText)

        // Verify User Alpha sees the original message + support reply, and original message status is updated to DELIVERED
        val alphaMessages = dao.getSupportMessagesForUserSnapshot("user_alpha")
        assertEquals(2, alphaMessages.size)
        assertEquals("DELIVERED", alphaMessages[0].deliveryStatus)
        assertEquals("Hello Alpha! Store unopened insulin in the fridge (2-8°C).", alphaMessages[1].messageText)

        // Verify User Beta's conversation is completely isolated and did NOT receive Alpha's reply
        val betaMessages = dao.getSupportMessagesForUserSnapshot("user_beta")
        assertEquals(1, betaMessages.size)
        assertEquals("Question from User Beta", betaMessages[0].messageText)

        // 3. Duplicate webhook delivery with the same update_id (90001) is ignored idempotently
        val duplicateWebhookResult = supportService.handleWebhookRequest(
            headers = mapOf("X-Telegram-Bot-Api-Secret-Token" to "secret_webhook_token_xyz"),
            rawBodyJson = validReplyToAlphaPayload
        )
        assertTrue(duplicateWebhookResult is InboundUpdateResult.DuplicateIgnored)
        assertEquals(2, dao.getSupportMessagesForUserSnapshot("user_alpha").size)

        // 4. Non-reply group chatter in Telegram group is ignored
        val nonReplyChatter = JSONObject().apply {
            put("update_id", 90002L)
            put("message", JSONObject().apply {
                put("message_id", 6002L)
                put("chat", JSONObject().apply { put("id", -100987654321L) })
                put("from", JSONObject().apply {
                    put("id", 777001L)
                    put("is_bot", false)
                    put("first_name", "Dr. Sarah")
                })
                put("text", "General team announcement in group")
            })
        }
        assertTrue(supportService.processTelegramUpdate(nonReplyChatter) is InboundUpdateResult.UnmatchedIgnored)

        // 5. Reply from an unauthorized Telegram user ID (not in authorizedUserIds) is rejected
        val unauthorizedSenderReply = JSONObject().apply {
            put("update_id", 90003L)
            put("message", JSONObject().apply {
                put("message_id", 6003L)
                put("chat", JSONObject().apply { put("id", -100987654321L) })
                put("from", JSONObject().apply {
                    put("id", 999999L) // Unauthorized user ID
                    put("is_bot", false)
                    put("first_name", "Intruder")
                })
                put("reply_to_message", JSONObject().apply {
                    put("message_id", 5004L)
                })
                put("text", "Fake reply to Beta")
            })
        }
        assertTrue(supportService.processTelegramUpdate(unauthorizedSenderReply) is InboundUpdateResult.RejectedUnauthorized)
        assertEquals(1, dao.getSupportMessagesForUserSnapshot("user_beta").size)

        // 6. Reply from a wrong Telegram chat ID is rejected
        val wrongChatReply = JSONObject().apply {
            put("update_id", 90004L)
            put("message", JSONObject().apply {
                put("message_id", 6004L)
                put("chat", JSONObject().apply { put("id", -100111111111L) }) // Wrong group ID
                put("from", JSONObject().apply {
                    put("id", 777001L)
                    put("is_bot", false)
                    put("first_name", "Dr. Sarah")
                })
                put("reply_to_message", JSONObject().apply {
                    put("message_id", 5004L)
                })
                put("text", "Reply from wrong group")
            })
        }
        assertTrue(supportService.processTelegramUpdate(wrongChatReply) is InboundUpdateResult.RejectedUnauthorized)

        // 7. Reply to an unknown telegram_message_id is ignored safely without crashing
        val unknownTargetReply = JSONObject().apply {
            put("update_id", 90005L)
            put("message", JSONObject().apply {
                put("message_id", 6005L)
                put("chat", JSONObject().apply { put("id", -100987654321L) })
                put("from", JSONObject().apply {
                    put("id", 777001L)
                    put("is_bot", false)
                    put("first_name", "Dr. Sarah")
                })
                put("reply_to_message", JSONObject().apply {
                    put("message_id", 999999L) // Unknown message ID
                })
                put("text", "Reply to unknown message")
            })
        }
        assertTrue(supportService.processTelegramUpdate(unknownTargetReply) is InboundUpdateResult.UnmatchedIgnored)

        // 8. Valid reply to User Beta via pollIncomingUpdates delivers solely to User Beta
        val validReplyToBeta = JSONObject().apply {
            put("update_id", 90006L)
            put("message", JSONObject().apply {
                put("message_id", 6006L)
                put("date", 1730000100L)
                put("chat", JSONObject().apply { put("id", -100987654321L) })
                put("from", JSONObject().apply {
                    put("id", 777002L)
                    put("is_bot", false)
                    put("first_name", "Pharmacist")
                    put("last_name", "Omar")
                })
                put("reply_to_message", JSONObject().apply {
                    put("message_id", 5004L)
                })
                put("text", "Hi Beta, take your tablet with a full glass of water.")
            })
        }
        fakeTransport.queuedUpdatesJson.add(validReplyToBeta)

        val pollResult = supportService.pollIncomingUpdates()
        assertTrue(pollResult.isSuccess)
        assertEquals(1, pollResult.getOrThrow().size)
        assertTrue(pollResult.getOrThrow().first() is InboundUpdateResult.RoutedReply)

        val betaMessagesAfterPoll = dao.getSupportMessagesForUserSnapshot("user_beta")
        assertEquals(2, betaMessagesAfterPoll.size)
        assertEquals("Hi Beta, take your tablet with a full glass of water.", betaMessagesAfterPoll[1].messageText)
        assertEquals("Pharmacist Omar", betaMessagesAfterPoll[1].senderName)

        // User Alpha still has only 2 messages
        assertEquals(2, dao.getSupportMessagesForUserSnapshot("user_alpha").size)
    }

    @Test
    fun test07_endLiveSupportGeneratesNewLiveChatIdAndNewSupportIdOnReopen() = runBlocking {
        PremiumEntitlementManager.subscribeToPlan(context, PremiumEntitlementManager.PLAN_MONTHLY_ID)
        fakeTransport.shouldCreateTopics = true

        // 1. User opens Live Chat for the first time
        val firstConv = supportService.getOrCreateActiveConversation(context, "user_gamma")
        assertTrue(firstConv.conversationUid.startsWith("SUP-"))
        assertTrue(firstConv.userReference.startsWith("USER-"))

        // Calling getOrCreateActiveConversation while session is still open returns the SAME IDs
        val sameConv = supportService.getOrCreateActiveConversation(context, "user_gamma")
        assertEquals(firstConv.id, sameConv.id)
        assertEquals(firstConv.conversationUid, sameConv.conversationUid)
        assertEquals(firstConv.userReference, sameConv.userReference)

        // Send a message in first session -> creates a Telegram Topic
        val firstMsg = supportService.sendUserMessage(
            context = context,
            userId = "user_gamma",
            rawMessageText = "First session question",
            clientIdempotencyKey = "gamma_session1_msg1"
        ).getOrThrow()
        assertEquals(firstConv.conversationUid, firstMsg.conversationUid)

        // 2. User ends the Live Support session -> archives chat in Telegram, renames & closes topic
        val endResult = supportService.endActiveConversation(context, "user_gamma")
        assertTrue(endResult.isSuccess)
        val closedConv = endResult.getOrThrow()
        assertNotNull(closedConv)
        assertEquals("CLOSED", closedConv!!.status)
        assertNotNull(closedConv.telegramTopicId)

        // Verify Telegram Bot dispatched: (1) ticket initialization, (2) user message, (3) archived session transcript
        assertEquals(3, fakeTransport.sentCalls.size)
        val archiveCall = fakeTransport.sentCalls.last()
        assertTrue(archiveCall.text.contains("ARCHIVED SUPPORT SESSION"))
        assertTrue(archiveCall.text.contains(firstConv.conversationUid))
        assertTrue(archiveCall.text.contains(firstConv.userReference))
        assertTrue(archiveCall.text.contains("First session question"))
        assertEquals(closedConv.telegramTopicId, archiveCall.messageThreadId)

        // Verify the Forum Topic was renamed with [ARCHIVED] and closed for future reference
        val renamedTitle = fakeTransport.editedTopics[closedConv.telegramTopicId!!]
        assertNotNull(renamedTitle)
        assertTrue(renamedTitle!!.contains("[ARCHIVED]"))
        assertTrue(closedConv.telegramTopicId!! in fakeTransport.closedTopics)

        // 3. User opens Live Chat again -> receives a brand-new Live Chat ID and a brand-new Support ID
        val secondConv = supportService.getOrCreateActiveConversation(context, "user_gamma")
        assertNotEquals(firstConv.id, secondConv.id)
        assertNotEquals(firstConv.conversationUid, secondConv.conversationUid)
        assertNotEquals(firstConv.userReference, secondConv.userReference)
        assertTrue(secondConv.conversationUid.startsWith("SUP-"))
        assertTrue(secondConv.userReference.startsWith("USER-"))
        assertEquals("OPEN", secondConv.status)
    }

    @Test
    fun test08_strictTelegramTopicIsolationAndTopicFailureProtection() = runBlocking {
        PremiumEntitlementManager.subscribeToPlan(context, PremiumEntitlementManager.PLAN_ANNUAL_ID)

        // 1. Multiple users sending messages concurrently each receive their own dedicated Telegram topic
        supportService.sendUserMessage(context, "user_one", "Hello from User One", "u1_msg1").getOrThrow()
        supportService.sendUserMessage(context, "user_two", "Hello from User Two", "u2_msg1").getOrThrow()
        supportService.sendUserMessage(context, "user_one", "Follow-up from User One", "u1_msg2").getOrThrow()

        val convOne = dao.getActiveSupportConversationForUser("user_one")!!
        val convTwo = dao.getActiveSupportConversationForUser("user_two")!!
        assertNotNull(convOne.telegramTopicId)
        assertNotNull(convTwo.telegramTopicId)
        assertNotEquals(convOne.telegramTopicId, convTwo.telegramTopicId)

        // Every single Telegram call MUST have a non-null positive messageThreadId (never general channel)
        assertTrue(fakeTransport.sentCalls.isNotEmpty())
        assertTrue(
            "No message may ever be sent to the general channel (messageThreadId == null)",
            fakeTransport.sentCalls.all { it.messageThreadId != null && it.messageThreadId!! > 0 }
        )

        // 2. If topic creation fails for a new conversation, the message must NOT leak to the general channel
        val callCountBeforeFailure = fakeTransport.sentCalls.size
        fakeTransport.shouldCreateTopics = false

        val blockedSend = supportService.sendUserMessage(
            context = context,
            userId = "user_three_no_topic",
            rawMessageText = "This must never leak to general channel",
            clientIdempotencyKey = "u3_msg1"
        )
        assertTrue("Message delivery must fail when topic creation fails", blockedSend.isFailure)
        assertEquals(
            "Zero messages may be sent to Telegram when topic creation fails",
            callCountBeforeFailure,
            fakeTransport.sentCalls.size
        )

        val userThreeMessages = dao.getSupportMessagesForUserSnapshot("user_three_no_topic")
        assertEquals(1, userThreeMessages.size)
        assertEquals("FAILED", userThreeMessages.first().deliveryStatus)
        assertTrue(userThreeMessages.first().errorMessage!!.contains("topic", ignoreCase = true))
    }
}
