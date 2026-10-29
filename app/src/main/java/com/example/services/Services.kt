package com.example.services

import android.content.Context
import com.example.safety.DosageGate
import com.example.safety.DosageRequest
import com.example.safety.GateDecision
import com.example.safety.GateResult
import com.example.safety.QueryIntent
import com.example.safety.SourceRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MedInfoQuery(
    val medicineName: String,
    val question: String,
    val intent: QueryIntent,
    val isForChild: Boolean = false,
    val ageYears: Double? = null,
    val weightKg: Double? = null,
    val unit: String? = null,
    val concentration: String? = null
)

data class MedInfoAnswer(
    val decision: GateDecision,
    val message: String,
    val doseText: String? = null,
    val source: SourceRef? = null
) {
    companion object {
        fun fromGate(r: GateResult) = MedInfoAnswer(
            decision = r.decision,
            message = r.message,
            doseText = r.doseText,
            source = r.source
        )
    }
}

interface MedInfoService {
    suspend fun ask(q: MedInfoQuery): MedInfoAnswer
}

class MockMedInfoProvider : MedInfoService {
    private val verified = mapOf<String, Pair<String, SourceRef>>()

    private fun looksLikeEmergency(text: String): Boolean {
        val flags = listOf(
            "جرعة زائدة", "تسمم", "اختناق", "صعوبة تنفس", "حساسية شديدة",
            "overdose", "poison", "can't breathe", "anaphyla"
        )
        val t = text.lowercase()
        return flags.any { t.contains(it) }
    }

    override suspend fun ask(q: MedInfoQuery): MedInfoAnswer {
        if (looksLikeEmergency("${q.question} ${q.medicineName}")) {
            return MedInfoAnswer.fromGate(
                DosageGate.evaluate(DosageRequest(intent = q.intent, emergencySuspected = true))
            )
        }

        if (q.intent == QueryIntent.GENERAL_INFO) {
            return MedInfoAnswer.fromGate(
                DosageGate.evaluate(DosageRequest(intent = QueryIntent.GENERAL_INFO))
            )
        }

        val key = q.medicineName.trim().lowercase()
        val entry = verified[key]

        val result = DosageGate.evaluate(
            DosageRequest(
                intent = q.intent,
                doseText = entry?.first,
                source = entry?.second,
                isForChild = q.isForChild,
                aiAttemptedToInfer = false,
                ageYears = q.ageYears,
                weightKg = q.weightKg,
                unit = q.unit,
                concentration = q.concentration
            )
        )
        return MedInfoAnswer.fromGate(result)
    }
}

/**
 * All capabilities governed by the unified Home Medicine Cabinet Premium entitlement system.
 * Note: There is no standalone Local LLM purchase or separate add-on; Local LLM, AI Voice Search,
 * Online Medicine Search, and Human Assistance via Live Chat are all included in the single Premium plan.
 */
enum class PremiumFeature(
    val key: String,
    val title: String,
    val description: String
) {
    AI_VOICE_SEARCH(
        key = "AI_VOICE_SEARCH",
        title = "AI Voice Search",
        description = "Search for medicines hands-free using natural voice queries."
    ),
    LOCAL_LLM(
        key = "LOCAL_LLM",
        title = "On-Device Local LLM Access",
        description = "Use supported local AI models for private, on-device medicine assistance."
    ),
    ONLINE_MEDICINE_SEARCH(
        key = "ONLINE_MEDICINE_SEARCH",
        title = "AI-Powered Online Medicine Search",
        description = "Get intelligent online medicine insights and search results."
    ),
    LIVE_CHAT_SUPPORT(
        key = "LIVE_CHAT_SUPPORT",
        title = "Human Assistance via Live Chat",
        description = "Get help from real people through our in-app support chat, with convenient two-way messaging powered by Telegram."
    )
}

enum class SubscriptionStatus(val key: String, val displayLabel: String) {
    FREE("FREE", "Free Plan"),
    ACTIVE("ACTIVE", "Active Premium"),
    CANCELLED("CANCELLED", "Cancelled"),
    EXPIRED("EXPIRED", "Expired"),
    PENDING("PENDING", "Payment Pending"),
    VERIFICATION_FAILED("VERIFICATION_FAILED", "Verification Failed");

    companion object {
        fun fromKey(key: String?): SubscriptionStatus =
            entries.find { it.key.equals(key, ignoreCase = true) } ?: FREE
    }
}

data class SubscriptionPlanOption(
    val planId: String,
    val title: String,
    val priceDisplay: String,
    val billingIntervalLabel: String,
    val effectiveMonthlyDisplay: String,
    val durationDays: Int,
    val badgeText: String? = null,
    val renewalTerms: String
)

data class SubscriptionState(
    val status: SubscriptionStatus = SubscriptionStatus.FREE,
    val activePlanId: String? = null,
    val startedAtMillis: Long? = null,
    val renewsOrExpiresAtMillis: Long? = null,
    val autoRenewEnabled: Boolean = false,
    val isRestored: Boolean = false,
    val lastVerifiedAtMillis: Long = System.currentTimeMillis(),
    val verificationErrorMessage: String? = null
) {
    fun isEntitled(nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (verificationErrorMessage != null || status == SubscriptionStatus.VERIFICATION_FAILED) {
            return false
        }
        return when (status) {
            SubscriptionStatus.ACTIVE -> {
                renewsOrExpiresAtMillis == null || renewsOrExpiresAtMillis > nowMillis
            }
            SubscriptionStatus.CANCELLED -> {
                // Remains active until the paid billing period ends, unless already expired
                renewsOrExpiresAtMillis != null && renewsOrExpiresAtMillis > nowMillis
            }
            SubscriptionStatus.FREE,
            SubscriptionStatus.EXPIRED,
            SubscriptionStatus.PENDING,
            SubscriptionStatus.VERIFICATION_FAILED -> false
        }
    }
}

class PremiumAccessRequiredException(
    val feature: PremiumFeature = PremiumFeature.ONLINE_MEDICINE_SEARCH,
    message: String = "${feature.title} requires an active Home Medicine Cabinet Premium subscription."
) : SecurityException(message)

/**
 * Single authoritative Premium subscription and entitlement manager governing:
 * 1. AI Voice Search
 * 2. On-Device Local LLM Access
 * 3. AI-Powered Online Medicine Search
 * 4. Human Assistance via Live Chat (Online Help Center)
 *
 * Never grants false active status when entitlement verification fails.
 */
object PremiumEntitlementManager {
    private const val PREFS_NAME = "local_llm_prefs"
    private const val KEY_PREMIUM_SUBSCRIBED = "is_premium_subscribed"
    private const val KEY_SUBSCRIPTION_STATUS = "subscription_status"
    private const val KEY_ACTIVE_PLAN_ID = "subscription_plan_id"
    private const val KEY_STARTED_AT = "subscription_started_at"
    private const val KEY_EXPIRES_AT = "subscription_expires_at"
    private const val KEY_AUTO_RENEW = "subscription_auto_renew"
    private const val KEY_IS_RESTORED = "subscription_is_restored"
    private const val KEY_VERIFICATION_ERROR = "subscription_verification_error"
    private const val KEY_PURCHASE_HISTORY_PLAN = "subscription_last_purchased_plan"

    const val PLAN_MONTHLY_ID = "hmc_premium_monthly"
    const val PLAN_ANNUAL_ID = "hmc_premium_annual"

    val availablePlans: List<SubscriptionPlanOption> = listOf(
        SubscriptionPlanOption(
            planId = PLAN_ANNUAL_ID,
            title = "Annual Premium",
            priceDisplay = "$39.99",
            billingIntervalLabel = "per year",
            effectiveMonthlyDisplay = "$3.33 / month (Save 33%)",
            durationDays = 365,
            badgeText = "BEST VALUE",
            renewalTerms = "Billed annually at $39.99/year. Automatically renews each year unless cancelled at least 24 hours before the renewal date."
        ),
        SubscriptionPlanOption(
            planId = PLAN_MONTHLY_ID,
            title = "Monthly Premium",
            priceDisplay = "$4.99",
            billingIntervalLabel = "per month",
            effectiveMonthlyDisplay = "$4.99 / month",
            durationDays = 30,
            badgeText = "FLEXIBLE",
            renewalTerms = "Billed monthly at $4.99/month. Automatically renews each month unless cancelled at least 24 hours before the renewal date."
        )
    )

    private val _subscriptionStateFlow = MutableStateFlow(SubscriptionState())
    val subscriptionStateFlow: StateFlow<SubscriptionState> = _subscriptionStateFlow.asStateFlow()

    fun getPlanById(planId: String?): SubscriptionPlanOption? =
        availablePlans.find { it.planId == planId }

    fun refreshSubscriptionState(
        context: Context,
        nowMillis: Long = System.currentTimeMillis()
    ): SubscriptionState {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val legacyBoolean = prefs.getBoolean(KEY_PREMIUM_SUBSCRIBED, false)
        val rawStatusKey = prefs.getString(KEY_SUBSCRIPTION_STATUS, null)
        val verificationErr = prefs.getString(KEY_VERIFICATION_ERROR, null)
        val planId = prefs.getString(KEY_ACTIVE_PLAN_ID, null) ?: if (legacyBoolean) PLAN_MONTHLY_ID else null
        val startedAt = if (prefs.contains(KEY_STARTED_AT)) prefs.getLong(KEY_STARTED_AT, nowMillis) else null
        val expiresAt = if (prefs.contains(KEY_EXPIRES_AT)) prefs.getLong(KEY_EXPIRES_AT, 0L) else null
        val autoRenew = prefs.getBoolean(KEY_AUTO_RENEW, legacyBoolean)

        var resolvedStatus = when {
            verificationErr != null -> SubscriptionStatus.VERIFICATION_FAILED
            rawStatusKey != null -> SubscriptionStatus.fromKey(rawStatusKey)
            legacyBoolean -> SubscriptionStatus.ACTIVE
            else -> SubscriptionStatus.FREE
        }

        // Automatically transition expired subscriptions
        if ((resolvedStatus == SubscriptionStatus.ACTIVE || resolvedStatus == SubscriptionStatus.CANCELLED) &&
            expiresAt != null && expiresAt <= nowMillis
        ) {
            resolvedStatus = SubscriptionStatus.EXPIRED
            prefs.edit()
                .putString(KEY_SUBSCRIPTION_STATUS, SubscriptionStatus.EXPIRED.key)
                .putBoolean(KEY_PREMIUM_SUBSCRIBED, false)
                .putBoolean(KEY_AUTO_RENEW, false)
                .putBoolean(KEY_IS_RESTORED, false)
                .apply()
        }

        val isRestored = prefs.getBoolean(KEY_IS_RESTORED, false) && resolvedStatus == SubscriptionStatus.ACTIVE

        val state = SubscriptionState(
            status = resolvedStatus,
            activePlanId = planId,
            startedAtMillis = startedAt,
            renewsOrExpiresAtMillis = expiresAt,
            autoRenewEnabled = autoRenew && resolvedStatus == SubscriptionStatus.ACTIVE,
            isRestored = isRestored,
            lastVerifiedAtMillis = nowMillis,
            verificationErrorMessage = verificationErr
        )
        _subscriptionStateFlow.value = state
        LocalLlmConfig.syncInternalPremiumFlag(state.isEntitled(nowMillis))
        return state
    }

    fun isPremiumActive(context: Context, nowMillis: Long = System.currentTimeMillis()): Boolean {
        return refreshSubscriptionState(context, nowMillis).isEntitled(nowMillis)
    }

    fun isEntitled(
        context: Context,
        feature: PremiumFeature,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        val state = refreshSubscriptionState(context, nowMillis)
        return state.isEntitled(nowMillis)
    }

    fun requirePremiumAccess(
        context: Context,
        feature: PremiumFeature,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        if (!isEntitled(context, feature, nowMillis)) {
            throw PremiumAccessRequiredException(
                feature = feature,
                message = "${feature.title} requires an active Home Medicine Cabinet Premium subscription."
            )
        }
    }

    fun subscribeToPlan(
        context: Context,
        planId: String = PLAN_ANNUAL_ID,
        nowMillis: Long = System.currentTimeMillis()
    ): Result<SubscriptionState> {
        val plan = getPlanById(planId)
            ?: return Result.failure(IllegalArgumentException("Unsupported subscription plan: $planId"))
        val expiresAt = nowMillis + plan.durationDays * 24L * 60L * 60L * 1000L
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_PREMIUM_SUBSCRIBED, true)
            .putString(KEY_SUBSCRIPTION_STATUS, SubscriptionStatus.ACTIVE.key)
            .putString(KEY_ACTIVE_PLAN_ID, plan.planId)
            .putString(KEY_PURCHASE_HISTORY_PLAN, plan.planId)
            .putLong(KEY_STARTED_AT, nowMillis)
            .putLong(KEY_EXPIRES_AT, expiresAt)
            .putBoolean(KEY_AUTO_RENEW, true)
            .putBoolean(KEY_IS_RESTORED, false)
            .remove(KEY_VERIFICATION_ERROR)
            .apply()

        val updated = refreshSubscriptionState(context, nowMillis)
        return Result.success(updated)
    }

    fun cancelSubscription(
        context: Context,
        nowMillis: Long = System.currentTimeMillis()
    ): SubscriptionState {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val defaultExpiry = nowMillis + 30L * 24L * 60L * 60L * 1000L
        val currentExpires = if (prefs.contains(KEY_EXPIRES_AT)) {
            prefs.getLong(KEY_EXPIRES_AT, defaultExpiry).takeIf { it > nowMillis } ?: defaultExpiry
        } else {
            defaultExpiry
        }
        val editor = prefs.edit()
            .putString(KEY_SUBSCRIPTION_STATUS, SubscriptionStatus.CANCELLED.key)
            .putBoolean(KEY_AUTO_RENEW, false)
            .putBoolean(KEY_IS_RESTORED, false)
            .putLong(KEY_EXPIRES_AT, currentExpires)
            .putBoolean(KEY_PREMIUM_SUBSCRIBED, currentExpires > nowMillis)
        if (!prefs.contains(KEY_STARTED_AT)) {
            editor.putLong(KEY_STARTED_AT, nowMillis)
        }
        editor.apply()
        return refreshSubscriptionState(context, nowMillis)
    }

    fun restorePurchases(
        context: Context,
        nowMillis: Long = System.currentTimeMillis()
    ): Result<SubscriptionState> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val verificationErr = prefs.getString(KEY_VERIFICATION_ERROR, null)
        if (verificationErr != null) {
            refreshSubscriptionState(context, nowMillis)
            return Result.failure(IllegalStateException(verificationErr))
        }

        val lastPlanId = prefs.getString(KEY_PURCHASE_HISTORY_PLAN, null)
            ?: prefs.getString(KEY_ACTIVE_PLAN_ID, null)
        val currentlySubscribed = prefs.getBoolean(KEY_PREMIUM_SUBSCRIBED, false)

        if (lastPlanId == null && !currentlySubscribed) {
            return Result.failure(NoSuchElementException("No previous Premium purchases were found for this account."))
        }

        val plan = getPlanById(lastPlanId) ?: availablePlans.first()
        val existingExpiry = if (prefs.contains(KEY_EXPIRES_AT)) prefs.getLong(KEY_EXPIRES_AT, 0L) else 0L
        if (existingExpiry in 1..nowMillis) {
            // Purchase exists in history but is expired
            prefs.edit()
                .putString(KEY_SUBSCRIPTION_STATUS, SubscriptionStatus.EXPIRED.key)
                .putBoolean(KEY_PREMIUM_SUBSCRIBED, false)
                .putBoolean(KEY_AUTO_RENEW, false)
                .putBoolean(KEY_IS_RESTORED, false)
                .apply()
            refreshSubscriptionState(context, nowMillis)
            return Result.failure(IllegalStateException("Your previous ${plan.title} subscription has expired. Please renew to restore Premium access."))
        }

        val newExpiry = if (existingExpiry > nowMillis) existingExpiry else (nowMillis + plan.durationDays * 24L * 60L * 60L * 1000L)
        val existingStart = if (prefs.contains(KEY_STARTED_AT)) prefs.getLong(KEY_STARTED_AT, nowMillis) else nowMillis
        prefs.edit()
            .putBoolean(KEY_PREMIUM_SUBSCRIBED, true)
            .putString(KEY_SUBSCRIPTION_STATUS, SubscriptionStatus.ACTIVE.key)
            .putString(KEY_ACTIVE_PLAN_ID, plan.planId)
            .putString(KEY_PURCHASE_HISTORY_PLAN, plan.planId)
            .putLong(KEY_STARTED_AT, existingStart)
            .putLong(KEY_EXPIRES_AT, newExpiry)
            .putBoolean(KEY_AUTO_RENEW, true)
            .putBoolean(KEY_IS_RESTORED, true)
            .remove(KEY_VERIFICATION_ERROR)
            .apply()

        return Result.success(refreshSubscriptionState(context, nowMillis))
    }

    fun setVerificationFailure(
        context: Context,
        errorMessage: String?,
        nowMillis: Long = System.currentTimeMillis()
    ): SubscriptionState {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (errorMessage.isNullOrBlank()) {
            prefs.edit().remove(KEY_VERIFICATION_ERROR).apply()
        } else {
            // Never grant false active status when entitlement verification fails
            prefs.edit()
                .putString(KEY_VERIFICATION_ERROR, errorMessage)
                .putString(KEY_SUBSCRIPTION_STATUS, SubscriptionStatus.VERIFICATION_FAILED.key)
                .putBoolean(KEY_PREMIUM_SUBSCRIBED, false)
                .apply()
        }
        return refreshSubscriptionState(context, nowMillis)
    }

    fun activateSubscription(
        context: Context,
        planId: String = PLAN_ANNUAL_ID,
        nowMillis: Long = System.currentTimeMillis()
    ): Result<SubscriptionState> = subscribeToPlan(context, planId, nowMillis)

    fun resetToFree(
        context: Context,
        nowMillis: Long = System.currentTimeMillis()
    ): SubscriptionState {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_PREMIUM_SUBSCRIBED)
            .remove(KEY_SUBSCRIPTION_STATUS)
            .remove(KEY_ACTIVE_PLAN_ID)
            .remove(KEY_PURCHASE_HISTORY_PLAN)
            .remove(KEY_STARTED_AT)
            .remove(KEY_EXPIRES_AT)
            .remove(KEY_AUTO_RENEW)
            .remove(KEY_IS_RESTORED)
            .remove(KEY_VERIFICATION_ERROR)
            .apply()
        return refreshSubscriptionState(context, nowMillis)
    }

    fun entitledFeatures(
        context: Context,
        nowMillis: Long = System.currentTimeMillis()
    ): Set<PremiumFeature> {
        return if (isPremiumActive(context, nowMillis)) {
            PremiumFeature.entries.toSet()
        } else {
            emptySet()
        }
    }

    fun setSubscriptionExpiredForTesting(
        context: Context,
        expiredAtMillis: Long = System.currentTimeMillis() - 60_000L
    ): SubscriptionState {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_PREMIUM_SUBSCRIBED, false)
            .putString(KEY_SUBSCRIPTION_STATUS, SubscriptionStatus.EXPIRED.key)
            .putLong(KEY_EXPIRES_AT, expiredAtMillis)
            .putBoolean(KEY_AUTO_RENEW, false)
            .putBoolean(KEY_IS_RESTORED, false)
            .apply()
        return refreshSubscriptionState(context)
    }
}

/**
 * Authoritative service-level gate for AI-Powered Online Medicine Search and all Premium features.
 * Delegates to PremiumEntitlementManager so all Premium capabilities share the exact same entitlement check.
 */
object OnlineMedicineSearchGate {
    fun requirePremiumAccess(
        isPremiumUser: Boolean,
        feature: PremiumFeature = PremiumFeature.ONLINE_MEDICINE_SEARCH
    ) {
        if (!isPremiumUser) {
            throw PremiumAccessRequiredException(feature = feature)
        }
    }

    fun canAccessOnlineSearch(context: Context): Boolean {
        return PremiumEntitlementManager.isEntitled(context, PremiumFeature.ONLINE_MEDICINE_SEARCH)
    }

    fun canAccessVoiceSearch(context: Context): Boolean {
        return PremiumEntitlementManager.isEntitled(context, PremiumFeature.AI_VOICE_SEARCH)
    }

    fun canAccessLocalLlm(context: Context): Boolean {
        return PremiumEntitlementManager.isEntitled(context, PremiumFeature.LOCAL_LLM)
    }

    fun canAccessLiveChatSupport(context: Context): Boolean {
        return PremiumEntitlementManager.isEntitled(context, PremiumFeature.LIVE_CHAT_SUPPORT)
    }
}
