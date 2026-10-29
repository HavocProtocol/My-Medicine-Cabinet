package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.services.PremiumEntitlementManager
import com.example.services.PremiumFeature

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpCenterScreen(
    onBack: () -> Unit,
    onNavigateToLiveChat: () -> Unit,
    onNavigateToPremiumSubscription: () -> Unit
) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        PremiumEntitlementManager.refreshSubscriptionState(context)
    }

    val subscriptionState by PremiumEntitlementManager.subscriptionStateFlow.collectAsState()
    val isPremiumEntitled = remember(subscriptionState) {
        PremiumEntitlementManager.isEntitled(context, PremiumFeature.LIVE_CHAT_SUPPORT)
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
                            text = stringResource(R.string.help_center_title),
                            fontWeight = FontWeight.Bold
                        )
                        Surface(
                            color = if (isPremiumEntitled) Color(0xFFDCFCE7) else Color(0xFFFEF3C7),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("help_center_premium_badge")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = if (isPremiumEntitled) Icons.Default.WorkspacePremium else Icons.Default.Lock,
                                    contentDescription = if (isPremiumEntitled) stringResource(R.string.premium_badge_unlocked) else stringResource(R.string.ai_dialog_sub),
                                    tint = if (isPremiumEntitled) Color(0xFF166534) else Color(0xFFB45309),
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = if (isPremiumEntitled) stringResource(R.string.badge_premium) else "${stringResource(R.string.badge_premium)} 🔒",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (isPremiumEntitled) Color(0xFF166534) else Color(0xFFB45309)
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("help_center_back_button")
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
                .testTag("help_center_screen"),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Hero Banner Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFF0284C7),
                                    Color(0xFF0369A1)
                                )
                            )
                        )
                        .padding(22.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color.White.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.SupportAgent,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        Text(
                            text = stringResource(R.string.help_center_hero_title),
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.testTag("help_center_heading")
                        )

                        Text(
                            text = stringResource(R.string.help_center_hero_desc),
                            color = Color.White.copy(alpha = 0.92f),
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            modifier = Modifier.testTag("help_center_description")
                        )
                    }
                }
            }

            // What We Can Help With Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.help_center_card_title),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    HelpCenterFeatureRow(
                        icon = Icons.Outlined.Forum,
                        title = stringResource(R.string.help_center_bullet_1_title),
                        subtitle = stringResource(R.string.help_center_bullet_1_desc)
                    )
                    HelpCenterFeatureRow(
                        icon = Icons.Outlined.Medication,
                        title = stringResource(R.string.help_center_bullet_2_title),
                        subtitle = stringResource(R.string.help_center_bullet_2_desc)
                    )
                    HelpCenterFeatureRow(
                        icon = Icons.Outlined.History,
                        title = stringResource(R.string.help_center_bullet_3_title),
                        subtitle = stringResource(R.string.help_center_bullet_3_desc)
                    )
                }
            }

            // Privacy & Telegram Support Group Notice
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("help_center_privacy_notice"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFF0F9FF)
                ),
                border = BorderStroke(1.dp, Color(0xFF7DD3FC))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Outlined.PrivacyTip,
                        contentDescription = null,
                        tint = Color(0xFF0284C7),
                        modifier = Modifier.size(22.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.help_center_privacy_title),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = Color(0xFF0C4A6E)
                        )
                        Text(
                            text = stringResource(R.string.help_center_privacy_desc),
                            fontSize = 12.sp,
                            color = Color(0xFF0369A1),
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            // Entitlement Status Notice for Free vs Premium Users
            if (!isPremiumEntitled) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("help_center_free_user_notice"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFBEB)),
                    border = BorderStroke(1.dp, Color(0xFFF59E0B))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFEF3C7)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = null,
                                tint = Color(0xFFD97706),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.help_center_premium_gate_title),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = Color(0xFF92400E)
                            )
                            Text(
                                text = stringResource(R.string.help_center_premium_gate_desc),
                                fontSize = 12.sp,
                                color = Color(0xFFB45309),
                                lineHeight = 17.sp
                            )
                        }
                    }
                }
            }

            // Prominent Request Assistance Button
            Button(
                onClick = {
                    val currentlyEntitled = PremiumEntitlementManager.isEntitled(
                        context = context,
                        feature = PremiumFeature.LIVE_CHAT_SUPPORT
                    )
                    if (currentlyEntitled) {
                        onNavigateToLiveChat()
                    } else {
                        onNavigateToPremiumSubscription()
                    }
                },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF0284C7),
                    contentColor = Color.White
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("help_center_request_assistance_button")
            ) {
                Icon(
                    imageVector = if (isPremiumEntitled) Icons.AutoMirrored.Filled.Chat else Icons.Default.WorkspacePremium,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.help_center_request_assistance_btn),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun HelpCenterFeatureRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String
) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFFE0F2FE)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color(0xFF0284C7),
                modifier = Modifier.size(20.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 17.sp
            )
        }
    }
}
