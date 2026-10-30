package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.services.PremiumEntitlementManager
import com.example.services.PremiumFeature
import com.example.services.SubscriptionPlanOption
import com.example.services.SubscriptionStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PremiumSubscriptionScreen(
    onBack: () -> Unit,
    onNavigateToVoiceSearch: () -> Unit = {},
    onNavigateToAISearch: () -> Unit = {},
    onNavigateToHelpCenter: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        PremiumEntitlementManager.refreshSubscriptionState(context)
    }

    val subscriptionState by PremiumEntitlementManager.subscriptionStateFlow.collectAsState()
    val isEntitled = subscriptionState.isEntitled()
    val availablePlans = remember { PremiumEntitlementManager.availablePlans }

    var selectedPlanId by remember(subscriptionState.activePlanId) {
        mutableStateOf(
            subscriptionState.activePlanId ?: PremiumEntitlementManager.PLAN_ANNUAL_ID
        )
    }
    var isProcessingAction by remember { mutableStateOf(false) }
    var feedbackBannerMessage by remember { mutableStateOf<String?>(null) }
    var isFeedbackError by remember { mutableStateOf(false) }
    var showCancelConfirmDialog by remember { mutableStateOf(false) }

    val dateFormatter = remember { SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()) }

    val selectedPlan: SubscriptionPlanOption? = remember(selectedPlanId, availablePlans) {
        availablePlans.find { it.planId == selectedPlanId } ?: availablePlans.firstOrNull()
    }
    val activePlanOption: SubscriptionPlanOption? = remember(subscriptionState.activePlanId, availablePlans) {
        PremiumEntitlementManager.getPlanById(subscriptionState.activePlanId) ?: availablePlans.firstOrNull()
    }

    if (showCancelConfirmDialog) {
        val currentPlanTitle = activePlanOption?.let { getLocalizedPlanTitle(it) }
            ?: stringResource(R.string.premium_hero_headline_active)
        val expiryDateText = subscriptionState.renewsOrExpiresAtMillis?.let {
            dateFormatter.format(Date(it))
        } ?: "—"

        AlertDialog(
            onDismissRequest = { showCancelConfirmDialog = false },
            modifier = Modifier.testTag("cancel_plan_confirmation_dialog"),
            icon = {
                Icon(
                    Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.cancel_plan_dialog_title),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.cancel_plan_dialog_body, currentPlanTitle, expiryDateText),
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCancelConfirmDialog = false
                        PremiumEntitlementManager.cancelSubscription(
                            context = context
                        )
                        isFeedbackError = false
                        feedbackBannerMessage = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.testTag("confirm_cancel_plan_button")
                ) {
                    Text(
                        text = stringResource(R.string.cancel_plan_confirm_btn),
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showCancelConfirmDialog = false },
                    modifier = Modifier.testTag("dismiss_cancel_plan_button")
                ) {
                    Text(stringResource(R.string.cancel_plan_keep_btn))
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
                            text = stringResource(R.string.premium_page_title),
                            fontWeight = FontWeight.Bold
                        )
                        Surface(
                            color = if (isEntitled) Color(0xFFDCFCE7) else Color(0xFFE0F2FE),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("premium_page_status_badge")
                        ) {
                            Text(
                                text = if (isEntitled) stringResource(R.string.badge_active) else stringResource(R.string.premium_badge_free_plan),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (isEntitled) Color(0xFF166534) else Color(0xFF0369A1),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("premium_page_back_button")
                    ) {
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .testTag("premium_subscription_screen"),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // A. Premium Hero Section (Blue-and-White visual treatment)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("premium_hero_card"),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFF0284C7),
                                    Color(0xFF0369A1),
                                    Color(0xFF1E3A8A)
                                )
                            )
                        )
                        .padding(24.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color.White.copy(alpha = 0.18f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.WorkspacePremium,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                            Surface(
                                color = Color.White.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Text(
                                    text = if (isEntitled) stringResource(R.string.premium_hero_pill_unlocked) else stringResource(R.string.premium_hero_pill_all_in_one),
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                                )
                            }
                        }

                        Text(
                            text = if (isEntitled) stringResource(R.string.premium_hero_headline_active) else stringResource(R.string.premium_hero_headline_upgrade),
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                            lineHeight = 28.sp,
                            modifier = Modifier.testTag("premium_hero_title")
                        )

                        Text(
                            text = stringResource(R.string.premium_hero_subtitle),
                            color = Color.White.copy(alpha = 0.92f),
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            modifier = Modifier.testTag("premium_hero_subtitle")
                        )
                    }
                }
            }

            // D. Subscription Status Card
            SubscriptionStatusBannerCard(
                status = subscriptionState.status,
                isEntitled = isEntitled,
                activePlan = PremiumEntitlementManager.getPlanById(subscriptionState.activePlanId),
                renewsOrExpiresAtMillis = subscriptionState.renewsOrExpiresAtMillis,
                autoRenewEnabled = subscriptionState.autoRenewEnabled,
                verificationError = subscriptionState.verificationErrorMessage,
                dateFormatter = dateFormatter
            )

            // Feedback / Error / Cancellation Confirmation Banner (rendered once)
            val cancellationConfirmationMessage = if (subscriptionState.status == SubscriptionStatus.CANCELLED) {
                val formattedExp = subscriptionState.renewsOrExpiresAtMillis?.let {
                    dateFormatter.format(Date(it))
                }
                if (!formattedExp.isNullOrBlank()) {
                    stringResource(R.string.premium_status_cancelled_with_date_msg, formattedExp)
                } else {
                    stringResource(R.string.premium_status_cancelled_msg)
                }
            } else {
                null
            }
            val effectiveBannerMessage = feedbackBannerMessage ?: cancellationConfirmationMessage

            effectiveBannerMessage?.let { msg ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("premium_feedback_banner"),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isFeedbackError) Color(0xFFFEE2E2) else Color(0xFFDCFCE7)
                    ),
                    border = BorderStroke(
                        1.dp,
                        if (isFeedbackError) Color(0xFFEF4444) else Color(0xFF10B981)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = if (isFeedbackError) Icons.Default.ErrorOutline else Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = if (isFeedbackError) Color(0xFF991B1B) else Color(0xFF065F46)
                        )
                        Text(
                            text = msg,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (isFeedbackError) Color(0xFF991B1B) else Color(0xFF065F46),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // B. Premium Benefits Section (All 4 Unified Capabilities)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.premium_included_section_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )

                PremiumBenefitCard(
                    icon = Icons.Outlined.Mic,
                    title = stringResource(R.string.premium_feat_voice_title),
                    description = stringResource(R.string.premium_feat_voice_desc),
                    isUnlocked = isEntitled,
                    tag = "benefit_card_voice_search",
                    actionLabel = if (isEntitled) stringResource(R.string.premium_feat_voice_cta) else null,
                    onActionClick = onNavigateToVoiceSearch
                )

                PremiumBenefitCard(
                    icon = Icons.Outlined.Dns,
                    title = stringResource(R.string.premium_feat_llm_title),
                    description = stringResource(R.string.premium_feat_llm_desc),
                    isUnlocked = isEntitled,
                    tag = "benefit_card_local_llm",
                    actionLabel = if (isEntitled) stringResource(R.string.premium_feat_llm_cta) else null,
                    onActionClick = onNavigateToSettings
                )

                PremiumBenefitCard(
                    icon = Icons.Outlined.AutoAwesome,
                    title = stringResource(R.string.premium_feat_online_title),
                    description = stringResource(R.string.premium_feat_online_desc),
                    isUnlocked = isEntitled,
                    tag = "benefit_card_online_search",
                    actionLabel = if (isEntitled) stringResource(R.string.premium_feat_online_cta) else null,
                    onActionClick = onNavigateToAISearch
                )

                PremiumBenefitCard(
                    icon = Icons.Outlined.SupportAgent,
                    title = stringResource(R.string.premium_feat_chat_title),
                    description = stringResource(R.string.premium_feat_chat_desc),
                    isUnlocked = isEntitled,
                    tag = "benefit_card_live_chat",
                    actionLabel = if (isEntitled) stringResource(R.string.premium_feat_chat_cta) else null,
                    onActionClick = onNavigateToHelpCenter
                )
            }

            if (!isEntitled) {
                // C. Non-Subscribers: Purchase-Focused Available Plans & Checkout Controls
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.testTag("non_subscriber_plans_section")
                ) {
                    Text(
                        text = stringResource(R.string.premium_choose_plan_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    if (availablePlans.isEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Text(
                                text = stringResource(R.string.connection_failed),
                                modifier = Modifier.padding(16.dp),
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        availablePlans.forEach { plan ->
                            val isSelected = selectedPlanId == plan.planId
                            val localizedTitle = getLocalizedPlanTitle(plan)
                            val localizedBadge = getLocalizedPlanBadge(plan)
                            val localizedEffective = getLocalizedPlanEffectiveMonthly(plan)
                            val localizedInterval = getLocalizedPlanInterval(plan)
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("plan_card_${plan.planId}")
                                    .clickable { selectedPlanId = plan.planId },
                                shape = RoundedCornerShape(18.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) {
                                        Color(0xFFF0F9FF)
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    }
                                ),
                                border = BorderStroke(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) Color(0xFF0284C7) else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                )
                            ) {
                                Column(modifier = Modifier.padding(18.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            RadioButton(
                                                selected = isSelected,
                                                onClick = { selectedPlanId = plan.planId },
                                                colors = RadioButtonDefaults.colors(
                                                    selectedColor = Color(0xFF0284C7)
                                                )
                                            )
                                            Column {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Text(
                                                        text = localizedTitle,
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 16.sp,
                                                        color = if (isSelected) Color(0xFF0C4A6E) else MaterialTheme.colorScheme.onSurface
                                                    )
                                                    localizedBadge?.let { badge ->
                                                        Surface(
                                                            color = if (isSelected) Color(0xFF0284C7) else MaterialTheme.colorScheme.primaryContainer,
                                                            shape = RoundedCornerShape(6.dp)
                                                        ) {
                                                            Text(
                                                                text = badge,
                                                                color = if (isSelected) Color.White else MaterialTheme.colorScheme.onPrimaryContainer,
                                                                fontSize = 10.sp,
                                                                fontWeight = FontWeight.ExtraBold,
                                                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                                Text(
                                                    text = localizedEffective,
                                                    fontSize = 12.sp,
                                                    color = if (isSelected) Color(0xFF0369A1) else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }

                                        Column(horizontalAlignment = Alignment.End) {
                                            Text(
                                                text = plan.priceDisplay,
                                                fontWeight = FontWeight.ExtraBold,
                                                fontSize = 20.sp,
                                                color = if (isSelected) Color(0xFF0284C7) else MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = localizedInterval,
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Primary Subscribe / Upgrade Action Button (shown only for non-subscribers)
                    val localizedSelectedTitle = selectedPlan?.let { getLocalizedPlanTitle(it) } ?: stringResource(R.string.badge_premium)
                    Button(
                        onClick = {
                            val targetPlan = selectedPlan ?: return@Button
                            val targetPlanTitle = if (targetPlan.planId == PremiumEntitlementManager.PLAN_ANNUAL_ID) {
                                context.getString(R.string.premium_plan_annual_title)
                            } else {
                                context.getString(R.string.premium_plan_monthly_title)
                            }
                            isProcessingAction = true
                            feedbackBannerMessage = null
                            val result = PremiumEntitlementManager.subscribeToPlan(context, targetPlan.planId)
                            isProcessingAction = false
                            result.onSuccess {
                                isFeedbackError = false
                                feedbackBannerMessage = context.getString(R.string.premium_status_activated_msg, targetPlanTitle)
                            }.onFailure { err ->
                                isFeedbackError = true
                                feedbackBannerMessage = err.message ?: context.getString(R.string.connection_failed)
                            }
                        },
                        enabled = !isProcessingAction && selectedPlan != null,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF0284C7),
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .testTag("premium_subscribe_button")
                    ) {
                        if (isProcessingAction) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = Color.White,
                                strokeWidth = 2.5.dp
                            )
                        } else {
                            Icon(Icons.Default.WorkspacePremium, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            val priceStr = selectedPlan?.priceDisplay ?: ""
                            Text(
                                text = stringResource(R.string.premium_cta_subscribe, localizedSelectedTitle, priceStr),
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                    }

                    // Billing & Renewal Terms
                    selectedPlan?.let { plan ->
                        Text(
                            text = "${getLocalizedPlanTerms(plan)} ${stringResource(R.string.premium_included_section_sub)}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            lineHeight = 17.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp)
                                .testTag("premium_renewal_terms")
                        )
                    }
                }

                // Restore Purchases Section for Non-Subscribers
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("premium_manage_card"),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.premium_manage_restore_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        OutlinedButton(
                            onClick = {
                                isProcessingAction = true
                                feedbackBannerMessage = null
                                val res = PremiumEntitlementManager.restorePurchases(context)
                                isProcessingAction = false
                                res.onSuccess { restoredState ->
                                    isFeedbackError = false
                                    val restoredPlanName = if (restoredState.activePlanId == PremiumEntitlementManager.PLAN_MONTHLY_ID) {
                                        context.getString(R.string.premium_plan_monthly_title)
                                    } else {
                                        context.getString(R.string.premium_plan_annual_title)
                                    }
                                    feedbackBannerMessage = context.getString(R.string.premium_status_restored_msg, restoredPlanName)
                                }.onFailure { err ->
                                    isFeedbackError = true
                                    feedbackBannerMessage = err.message ?: context.getString(R.string.connection_failed)
                                }
                            },
                            enabled = !isProcessingAction,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("premium_restore_button")
                        ) {
                            Icon(
                                Icons.Default.Restore,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                stringResource(R.string.premium_restore_btn),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            } else {
                // C. Active Subscribers: Dedicated Current Subscription Management View
                // Does NOT display the list of available subscription plans to purchase.
                val currentPlanDisplay = activePlanOption?.let { getLocalizedPlanTitle(it) }
                    ?: stringResource(R.string.premium_hero_headline_active)

                val currentStatusDisplay = when {
                    subscriptionState.status == SubscriptionStatus.CANCELLED ->
                        stringResource(R.string.current_subscription_status_cancelled_valid)
                    subscriptionState.isRestored ->
                        stringResource(R.string.current_subscription_status_active_restored)
                    else ->
                        stringResource(R.string.premium_sub_status_active)
                }

                val startDateDisplay = subscriptionState.startedAtMillis?.let {
                    dateFormatter.format(Date(it))
                } ?: "—"

                val renewalOrExpiryLabel = if (subscriptionState.autoRenewEnabled && subscriptionState.status == SubscriptionStatus.ACTIVE) {
                    stringResource(R.string.current_subscription_renews_label)
                } else {
                    stringResource(R.string.current_subscription_expires_label)
                }

                val renewalOrExpiryDisplay = subscriptionState.renewsOrExpiresAtMillis?.let {
                    dateFormatter.format(Date(it))
                } ?: "—"

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("current_subscription_management_card"),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.5.dp, Color(0xFF0284C7).copy(alpha = 0.45f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(20.dp)
                            .testTag("premium_manage_card"),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                Icons.Default.VerifiedUser,
                                contentDescription = null,
                                tint = Color(0xFF0284C7),
                                modifier = Modifier.size(24.dp)
                            )
                            Column {
                                Text(
                                    text = stringResource(R.string.current_subscription_section_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(R.string.premium_manage_restore_title),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                        // 1. Current Plan Name
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.current_subscription_plan_label),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = currentPlanDisplay,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.testTag("current_subscription_plan_name")
                            )
                        }

                        // 2. Subscription Status
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.current_subscription_status_label),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = currentStatusDisplay,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (subscriptionState.status == SubscriptionStatus.CANCELLED) Color(0xFFB45309) else Color(0xFF166534),
                                modifier = Modifier.testTag("current_subscription_status")
                            )
                        }

                        // 3. Subscription Start Date
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.current_subscription_started_label),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = startDateDisplay,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.testTag("current_subscription_start_date")
                            )
                        }

                        // 4. Renewal or Expiration Date
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = renewalOrExpiryLabel,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = renewalOrExpiryDisplay,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.testTag("current_subscription_renewal_date")
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                        // 5. Restore Purchase & Cancel Plan Actions
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    isProcessingAction = true
                                    feedbackBannerMessage = null
                                    val res = PremiumEntitlementManager.restorePurchases(context)
                                    isProcessingAction = false
                                    res.onSuccess { restoredState ->
                                        isFeedbackError = false
                                        val restoredPlanName = if (restoredState.activePlanId == PremiumEntitlementManager.PLAN_MONTHLY_ID) {
                                            context.getString(R.string.premium_plan_monthly_title)
                                        } else {
                                            context.getString(R.string.premium_plan_annual_title)
                                        }
                                        feedbackBannerMessage = context.getString(R.string.premium_status_restored_msg, restoredPlanName)
                                    }.onFailure { err ->
                                        isFeedbackError = true
                                        feedbackBannerMessage = err.message ?: context.getString(R.string.connection_failed)
                                    }
                                },
                                enabled = !isProcessingAction,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("premium_restore_button")
                            ) {
                                Icon(
                                    Icons.Default.Restore,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    stringResource(R.string.premium_restore_btn),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            OutlinedButton(
                                onClick = {
                                    showCancelConfirmDialog = true
                                },
                                enabled = !isProcessingAction && subscriptionState.status != SubscriptionStatus.CANCELLED,
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                ),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("premium_cancel_button")
                            ) {
                                Text(
                                    stringResource(R.string.premium_cancel_btn),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun getLocalizedPlanTitle(plan: SubscriptionPlanOption): String = when (plan.planId) {
    PremiumEntitlementManager.PLAN_ANNUAL_ID -> stringResource(R.string.premium_plan_annual_title)
    PremiumEntitlementManager.PLAN_MONTHLY_ID -> stringResource(R.string.premium_plan_monthly_title)
    else -> plan.title
}

@Composable
private fun getLocalizedPlanInterval(plan: SubscriptionPlanOption): String = when (plan.planId) {
    PremiumEntitlementManager.PLAN_ANNUAL_ID -> stringResource(R.string.premium_plan_annual_interval)
    PremiumEntitlementManager.PLAN_MONTHLY_ID -> stringResource(R.string.premium_plan_monthly_interval)
    else -> plan.billingIntervalLabel
}

@Composable
private fun getLocalizedPlanEffectiveMonthly(plan: SubscriptionPlanOption): String = when (plan.planId) {
    PremiumEntitlementManager.PLAN_ANNUAL_ID -> stringResource(R.string.premium_plan_annual_effective)
    PremiumEntitlementManager.PLAN_MONTHLY_ID -> stringResource(R.string.premium_plan_monthly_effective)
    else -> plan.effectiveMonthlyDisplay
}

@Composable
private fun getLocalizedPlanBadge(plan: SubscriptionPlanOption): String? = when (plan.planId) {
    PremiumEntitlementManager.PLAN_ANNUAL_ID -> stringResource(R.string.premium_plan_annual_badge)
    PremiumEntitlementManager.PLAN_MONTHLY_ID -> stringResource(R.string.premium_plan_monthly_badge)
    else -> plan.badgeText
}

@Composable
private fun getLocalizedPlanTerms(plan: SubscriptionPlanOption): String = when (plan.planId) {
    PremiumEntitlementManager.PLAN_ANNUAL_ID -> stringResource(R.string.premium_plan_annual_terms)
    PremiumEntitlementManager.PLAN_MONTHLY_ID -> stringResource(R.string.premium_plan_monthly_terms)
    else -> plan.renewalTerms
}

@Composable
private fun SubscriptionStatusBannerCard(
    status: SubscriptionStatus,
    isEntitled: Boolean,
    activePlan: SubscriptionPlanOption?,
    renewsOrExpiresAtMillis: Long?,
    autoRenewEnabled: Boolean,
    verificationError: String?,
    dateFormatter: SimpleDateFormat
) {
    val (bgColor, borderColor, titleColor, icon) = when {
        status == SubscriptionStatus.VERIFICATION_FAILED || verificationError != null ->
            Quad(Color(0xFFFEE2E2), Color(0xFFEF4444), Color(0xFF991B1B), Icons.Default.ErrorOutline)
        isEntitled ->
            Quad(Color(0xFFECFDF5), Color(0xFF10B981), Color(0xFF065F46), Icons.Default.Verified)
        status == SubscriptionStatus.EXPIRED ->
            Quad(Color(0xFFFEF3C7), Color(0xFFF59E0B), Color(0xFF92400E), Icons.Default.WarningAmber)
        else ->
            Quad(Color(0xFFF0F9FF), Color(0xFF38BDF8), Color(0xFF0C4A6E), Icons.Default.Info)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("premium_status_card"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(borderColor.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = titleColor,
                    modifier = Modifier.size(24.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                val activePlanTitle = activePlan?.let { getLocalizedPlanTitle(it) } ?: stringResource(R.string.premium_hero_headline_active)
                Text(
                    text = when {
                        verificationError != null -> stringResource(R.string.premium_sub_status_failed)
                        isEntitled -> stringResource(R.string.premium_current_plan_prefix, activePlanTitle)
                        status == SubscriptionStatus.EXPIRED -> stringResource(R.string.premium_sub_status_expired)
                        else -> stringResource(R.string.premium_current_plan_prefix, stringResource(R.string.premium_sub_status_free))
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = titleColor
                )
                Spacer(modifier = Modifier.height(2.dp))
                val detailText = when {
                    verificationError != null ->
                        verificationError
                    isEntitled && renewsOrExpiresAtMillis != null -> {
                        val dateStr = dateFormatter.format(Date(renewsOrExpiresAtMillis))
                        if (autoRenewEnabled) stringResource(R.string.premium_renews_on, dateStr) else stringResource(R.string.premium_access_until, dateStr)
                    }
                    isEntitled ->
                        stringResource(R.string.settings_hmc_premium_active_desc)
                    status == SubscriptionStatus.EXPIRED ->
                        stringResource(R.string.settings_hmc_premium_free_desc)
                    else ->
                        stringResource(R.string.settings_hmc_premium_free_desc)
                }
                Text(
                    text = detailText,
                    fontSize = 12.sp,
                    color = titleColor.copy(alpha = 0.85f),
                    lineHeight = 17.sp
                )
            }
        }
    }
}

@Composable
private fun PremiumBenefitCard(
    icon: ImageVector,
    title: String,
    description: String,
    isUnlocked: Boolean,
    tag: String,
    actionLabel: String? = null,
    onActionClick: () -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            1.dp,
            if (isUnlocked) Color(0xFF38BDF8).copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFFE0F2FE)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Color(0xFF0284C7),
                        modifier = Modifier.size(24.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = title,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = if (isUnlocked) Color(0xFFDCFCE7) else Color(0xFFF0F9FF),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = if (isUnlocked) stringResource(R.string.premium_badge_unlocked) else stringResource(R.string.premium_badge_included),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (isUnlocked) Color(0xFF166534) else Color(0xFF0284C7),
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = description,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 19.sp
                    )

                    if (isUnlocked && actionLabel != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        TextButton(
                            onClick = onActionClick,
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text(
                                text = actionLabel,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF0284C7)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                tint = Color(0xFF0284C7),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
