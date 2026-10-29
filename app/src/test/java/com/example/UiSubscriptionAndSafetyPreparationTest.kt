package com.example

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.InventoryItem
import com.example.data.MedicationRefillClassification
import com.example.data.Medicine
import com.example.safety.*
import com.example.schedule.MedicationScheduleRepository
import com.example.schedule.ScheduleDraftInput
import com.example.schedule.ScheduleFrequency
import com.example.schedule.ScheduleStatus
import com.example.schedule.ScheduledTimeSlot
import com.example.services.LocalLlmConfig
import com.example.services.PremiumEntitlementManager
import com.example.services.SubscriptionStatus
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.PremiumSubscriptionScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.LocalOnThemeChanged
import com.example.ui.theme.LocalThemeMode
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UiSubscriptionAndSafetyPreparationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context

    private fun testMedicine(
        id: Int,
        tradeName: String,
        scientificName: String?
    ): Medicine = Medicine(
        id = id,
        tradeName = tradeName,
        scientificName = scientificName,
        barcode = null,
        form = "Tablet",
        strength = "500mg",
        manufacturer = null,
        imagePath = null,
        indications = null,
        warnings = null,
        sourceId = null,
        createdAt = 1704067200000L
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PremiumEntitlementManager.resetToFree(context)
        LocalLlmConfig.setThemeMode(context, 0)
        SafetyFeatureFlags.resetToDefaults()
    }

    @After
    fun tearDown() {
        PremiumEntitlementManager.resetToFree(context)
        SafetyFeatureFlags.resetToDefaults()
    }

    // =========================================================================
    // SECTION 1: THEME SWITCHING NAVIGATION PRESERVATION TESTS
    // =========================================================================

    @Test
    fun test01_themeSwitchingPreservesRouteAndPageStateAcrossAllScreens() {
        var capturedNavController: NavHostController? = null

        composeTestRule.setContent {
            val navController = rememberNavController()
            capturedNavController = navController
            var themeMode by remember { mutableStateOf(LocalLlmConfig.getThemeMode(context)) }
            val onThemeChanged = remember(context) {
                { newMode: Int ->
                    themeMode = newMode
                    LocalLlmConfig.setThemeMode(context, newMode)
                }
            }

            CompositionLocalProvider(
                LocalThemeMode provides themeMode,
                LocalOnThemeChanged provides onThemeChanged
            ) {
                MyApplicationTheme(
                    themeMode = themeMode,
                    onThemeChanged = onThemeChanged
                ) {
                    NavHost(navController = navController, startDestination = "home") {
                        composable("home") {
                            DashboardScreen(
                                onAdd = {},
                                onCategories = {},
                                onPrint = {},
                                onSettings = { navController.navigate("settings") },
                                onAISearch = {},
                                onVoice = {},
                                onNotifications = {},
                                onTutorial = {},
                                onNavigateToPremium = { navController.navigate("premium_subscription") },
                                onNavigateToHelpCenter = {}
                            )
                        }
                        composable("settings") {
                            SettingsScreen(
                                onBack = { navController.popBackStack() },
                                onNavigateToTutorial = {}
                            )
                        }
                        composable("premium_subscription") {
                            val currentMode = LocalThemeMode.current
                            val triggerTheme = LocalOnThemeChanged.current
                            Column {
                                Button(
                                    onClick = { triggerTheme((currentMode + 1) % 3) },
                                    modifier = Modifier.testTag("test_toggle_theme_on_premium")
                                ) {
                                    Text("Toggle Theme ($currentMode)")
                                }
                                PremiumSubscriptionScreen(onBack = { navController.popBackStack() })
                            }
                        }
                        composable("list") {
                            val currentMode = LocalThemeMode.current
                            val triggerTheme = LocalOnThemeChanged.current
                            var localFilterState by remember { mutableStateOf("Pain Relief") }
                            Column(modifier = Modifier.testTag("inventory_page_root")) {
                                Text(
                                    text = "Filter:$localFilterState|Theme:$currentMode",
                                    modifier = Modifier.testTag("inventory_state_label")
                                )
                                Button(
                                    onClick = { localFilterState = "Antibiotics" },
                                    modifier = Modifier.testTag("inventory_change_filter_btn")
                                ) {
                                    Text("Change Filter")
                                }
                                Button(
                                    onClick = { triggerTheme(2) },
                                    modifier = Modifier.testTag("inventory_switch_dark_btn")
                                ) {
                                    Text("Switch Dark")
                                }
                            }
                        }
                    }
                }
            }
        }

        // 1. Start on Home, open Menu overlay, and verify the theme changer is NOT on the Menu page
        assertEquals("home", capturedNavController?.currentBackStackEntry?.destination?.route)
        composeTestRule.onNodeWithTag("menu_open_button").performClick()
        composeTestRule.onNodeWithTag("menu_close_button").assertIsDisplayed()

        composeTestRule.onNodeWithTag("menu_theme_switcher_card").assertDoesNotExist()
        composeTestRule.onNodeWithTag("menu_theme_system").assertDoesNotExist()
        composeTestRule.onNodeWithTag("menu_theme_light").assertDoesNotExist()
        composeTestRule.onNodeWithTag("menu_theme_dark").assertDoesNotExist()

        // 2. Navigate to Settings and switch theme (System -> Dark -> Light -> System)
        composeTestRule.runOnUiThread {
            capturedNavController?.navigate("settings")
        }
        composeTestRule.waitForIdle()
        assertEquals("settings", capturedNavController?.currentBackStackEntry?.destination?.route)

        composeTestRule.onNodeWithTag("settings_theme_dropdown_button").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("settings_theme_option_dark").performClick()
        composeTestRule.waitForIdle()

        // Must remain on Settings page (NOT redirected to Home)
        assertEquals("settings", capturedNavController?.currentBackStackEntry?.destination?.route)
        composeTestRule.onNodeWithTag("settings_theme_mode_card").assertIsDisplayed()
        assertEquals(2, LocalLlmConfig.getThemeMode(context))

        composeTestRule.onNodeWithTag("settings_theme_dropdown_button").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("settings_theme_option_light").performClick()
        composeTestRule.waitForIdle()
        assertEquals("settings", capturedNavController?.currentBackStackEntry?.destination?.route)
        assertEquals(1, LocalLlmConfig.getThemeMode(context))

        composeTestRule.onNodeWithTag("settings_theme_dropdown_button").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("settings_theme_option_system").performClick()
        composeTestRule.waitForIdle()
        assertEquals("settings", capturedNavController?.currentBackStackEntry?.destination?.route)
        assertEquals(0, LocalLlmConfig.getThemeMode(context))

        // 3. Navigate to Medication Inventory ("list"), mutate local state, and switch theme
        composeTestRule.runOnUiThread {
            capturedNavController?.navigate("list")
        }
        composeTestRule.waitForIdle()
        assertEquals("list", capturedNavController?.currentBackStackEntry?.destination?.route)
        composeTestRule.onNodeWithTag("inventory_change_filter_btn").performClick()
        composeTestRule.onNodeWithTag("inventory_state_label").assertTextContains("Filter:Antibiotics|Theme:0")

        composeTestRule.onNodeWithTag("inventory_switch_dark_btn").performClick()
        composeTestRule.waitForIdle()
        // Route AND local page state ("Filter:Antibiotics") are preserved across theme switch!
        assertEquals("list", capturedNavController?.currentBackStackEntry?.destination?.route)
        composeTestRule.onNodeWithTag("inventory_state_label").assertTextContains("Filter:Antibiotics|Theme:2")
        assertEquals(2, LocalLlmConfig.getThemeMode(context))

        // 4. Navigate to Premium Subscription page and switch theme
        composeTestRule.runOnUiThread {
            capturedNavController?.navigate("premium_subscription")
        }
        composeTestRule.waitForIdle()
        assertEquals("premium_subscription", capturedNavController?.currentBackStackEntry?.destination?.route)
        composeTestRule.onNodeWithTag("test_toggle_theme_on_premium").performClick()
        composeTestRule.waitForIdle()
        assertEquals("premium_subscription", capturedNavController?.currentBackStackEntry?.destination?.route)
        composeTestRule.onNodeWithTag("premium_subscription_screen").assertIsDisplayed()
    }

    // =========================================================================
    // SECTION 2: PROMOTIONAL BOX PLACEMENT RULES TESTS
    // =========================================================================

    @Test
    fun test02_promotionalBoxesPlacementOnHomeMenuAndSettings() {
        var navigatedToPremiumCount = 0
        var navigatedToHelpCenterCount = 0

        composeTestRule.setContent {
            MyApplicationTheme {
                DashboardScreen(
                    onAdd = {},
                    onCategories = {},
                    onPrint = {},
                    onSettings = {},
                    onAISearch = {},
                    onVoice = {},
                    onNotifications = {},
                    onTutorial = {},
                    onNavigateToPremium = { navigatedToPremiumCount++ },
                    onNavigateToHelpCenter = { navigatedToHelpCenterCount++ }
                )
            }
        }

        // 1. On Home page (Menu closed):
        // - Refill Needed and One-Time Medications cards ARE displayed on the Home page.
        composeTestRule.onNodeWithTag("dashboard_refill_needed_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("dashboard_one_time_card").performScrollTo().assertIsDisplayed()

        // - Premium Subscription promotional box IS present at the bottom of the Home page
        //   with a button that opens the dedicated Premium Subscription page.
        composeTestRule.onNodeWithTag("dashboard_premium_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("dashboard_premium_button").performScrollTo().performClick()
        assertEquals(1, navigatedToPremiumCount)

        // - Live Chat promotional box MUST NOT be displayed on the Home page.
        composeTestRule.onNodeWithTag("dashboard_help_center_card").assertDoesNotExist()
        composeTestRule.onNodeWithTag("menu_live_chat_box").assertDoesNotExist()

        // 2. Scroll back up to the top header and open the Menu page overlay:
        // - Both the Online Help Center & Live Chat box AND the Premium Subscription box ARE displayed here,
        //   with Online Help Center & Live Chat positioned ON TOP and Premium Subscription BELOW it.
        composeTestRule.onNodeWithTag("menu_open_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("menu_close_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("menu_live_chat_box").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("menu_premium_subscription_box").performScrollTo().assertIsDisplayed()

        val liveChatTop = composeTestRule.onNodeWithTag("menu_live_chat_box").fetchSemanticsNode().boundsInRoot.top
        val premiumTop = composeTestRule.onNodeWithTag("menu_premium_subscription_box").fetchSemanticsNode().boundsInRoot.top
        assertTrue(
            "Online Help Center & Live Chat box ($liveChatTop) must appear above Premium Subscription box ($premiumTop) on Menu page",
            liveChatTop < premiumTop
        )

        // Clicking Premium box button in Menu navigates to Premium Subscription
        composeTestRule.onNodeWithTag("menu_open_premium_button").performScrollTo().performClick()
        assertEquals(2, navigatedToPremiumCount)
    }

    @Test
    fun test03_settingsPageDoesNotDisplayGeneralPromotionalBoxes() {
        composeTestRule.setContent {
            MyApplicationTheme {
                SettingsScreen(
                    onBack = {},
                    onNavigateToTutorial = {}
                )
            }
        }

        // General Premium Subscription and Live Chat promotional boxes must NOT appear on SettingsScreen
        composeTestRule.onNodeWithTag("settings_premium_subscription_card").assertDoesNotExist()
        composeTestRule.onNodeWithTag("settings_help_center_card").assertDoesNotExist()
    }

    // =========================================================================
    // SECTION 3: SUBSCRIBED-USER PREMIUM SUBSCRIPTION MANAGEMENT TESTS
    // =========================================================================

    @Test
    fun test04_premiumSubscriptionPageNonSubscriberVsActiveSubscriberManagement() {
        // Start as a non-subscriber (Free)
        PremiumEntitlementManager.resetToFree(context)

        composeTestRule.setContent {
            MyApplicationTheme {
                PremiumSubscriptionScreen(onBack = {})
            }
        }

        // A. Non-Subscriber View:
        // - Shows available subscription plans and subscribe button
        // - Does NOT show Current Subscription management view or Cancel Plan button
        composeTestRule.onNodeWithTag("plan_card_${PremiumEntitlementManager.PLAN_ANNUAL_ID}")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("plan_card_${PremiumEntitlementManager.PLAN_MONTHLY_ID}")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("premium_subscribe_button")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("premium_restore_button")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("current_subscription_management_card").assertDoesNotExist()
        composeTestRule.onNodeWithTag("premium_cancel_button").assertDoesNotExist()

        // Clicking Restore Purchase when no prior purchase exists does NOT grant access and shows error feedback
        composeTestRule.onNodeWithTag("premium_restore_button").performScrollTo().performClick()
        assertFalse(PremiumEntitlementManager.isPremiumActive(context))
        composeTestRule.onNodeWithTag("premium_feedback_banner").performScrollTo().assertIsDisplayed()

        // B. Subscribe to Annual Plan -> transitions to Active Subscriber View
        composeTestRule.onNodeWithTag("premium_subscribe_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertTrue(PremiumEntitlementManager.isPremiumActive(context))

        // Active Subscriber View:
        // - Does NOT display the list of available subscription plans to purchase or the purchase button
        composeTestRule.onNodeWithTag("plan_card_${PremiumEntitlementManager.PLAN_ANNUAL_ID}").assertDoesNotExist()
        composeTestRule.onNodeWithTag("plan_card_${PremiumEntitlementManager.PLAN_MONTHLY_ID}").assertDoesNotExist()
        composeTestRule.onNodeWithTag("premium_subscribe_button").assertDoesNotExist()

        // - Displays the dedicated Current Subscription management card with plan name, status, start date, renewal date,
        //   Restore Purchase button, and Cancel Plan button
        composeTestRule.onNodeWithTag("current_subscription_management_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("current_subscription_plan_name").assertIsDisplayed()
        composeTestRule.onNodeWithTag("current_subscription_status").assertIsDisplayed()
        composeTestRule.onNodeWithTag("current_subscription_start_date").assertIsDisplayed()
        composeTestRule.onNodeWithTag("current_subscription_renewal_date").assertIsDisplayed()
        composeTestRule.onNodeWithTag("premium_restore_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("premium_cancel_button").assertIsDisplayed()

        // C. Cancel Plan Flow:
        // - Clicking Cancel Plan opens confirmation dialog explaining auto-renewal stops and benefits remain active until period end
        composeTestRule.onNodeWithTag("premium_cancel_button").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("cancel_plan_confirmation_dialog").assertIsDisplayed()

        // - Confirming cancellation sets status to CANCELLED, disables auto-renewal, but retains entitlement until period end
        // - Must NOT display any "End Access Now" option or wording
        composeTestRule.onNodeWithTag("confirm_cancel_plan_button").performClick()
        composeTestRule.waitForIdle()

        val canceledState = PremiumEntitlementManager.subscriptionStateFlow.value
        assertEquals(SubscriptionStatus.CANCELLED, canceledState.status)
        assertFalse(canceledState.autoRenewEnabled)
        assertTrue("Canceled subscription must remain entitled until the paid period ends", canceledState.isEntitled())
        composeTestRule.onNodeWithTag("current_subscription_management_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("cancelled_period_end_notice").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("current_subscription_status")
            .assertTextContains("Cancels at the end of the billing period")
        composeTestRule.onNodeWithText("End Access Now", substring = true, ignoreCase = true).assertDoesNotExist()

        // D. Restore Purchase from Canceled state restores active auto-renewing status and marks isRestored = true
        composeTestRule.onNodeWithTag("premium_restore_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        val restoredState = PremiumEntitlementManager.subscriptionStateFlow.value
        assertEquals(SubscriptionStatus.ACTIVE, restoredState.status)
        assertTrue(restoredState.isRestored)
        assertTrue(restoredState.isEntitled())

        // E. When subscription expires, user is no longer entitled and sees purchase plans again
        PremiumEntitlementManager.setSubscriptionExpiredForTesting(context)
        composeTestRule.waitForIdle()
        assertFalse(PremiumEntitlementManager.isPremiumActive(context))
        composeTestRule.onNodeWithTag("plan_card_${PremiumEntitlementManager.PLAN_ANNUAL_ID}")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("premium_subscribe_button")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("current_subscription_management_card").assertDoesNotExist()
    }

    // =========================================================================
    // SECTIONS 4, 5, 6, 7 & 8: DORMANT MEDICATION SAFETY FEATURES TESTS
    // =========================================================================

    @Test
    fun test05_safetyFeatureFlagsAreDisabledByDefaultAndDoNotInterfereWithLiveWorkflows() {
        // Verify all future feature flags default to false
        assertFalse(SafetyFeatureFlags.userAccountsEnabled)
        assertFalse(SafetyFeatureFlags.isMedicationCompatibilityEnabled)
        assertFalse(SafetyFeatureFlags.isAllergyAlertsEnabled)
        assertFalse(SafetyFeatureFlags.isChronicConditionsProfileEnabled)
        assertFalse(SafetyFeatureFlags.isAnySafetyFeatureActive())

        val medA = testMedicine(id = 1, tradeName = "Warfarin 5mg", scientificName = "Warfarin")
        val medB = testMedicine(id = 2, tradeName = "Brufen 400mg", scientificName = "Ibuprofen")

        // Services must return disabled results immediately when flags are false
        val compatResult = MedicationSafetyCoordinator.compatibilityService.evaluateCabinetCompatibility(listOf(medA, medB))
        assertFalse(compatResult.featureEnabled)
        assertTrue(compatResult.warnings.isEmpty())

        val allergyResult = MedicationSafetyCoordinator.allergyAlertService.checkMedicineAgainstAllergies(
            medicine = medB,
            recordedAllergies = listOf(UserAllergyRecord(allergyId = "a1", allergenName = "Ibuprofen"))
        )
        assertFalse(allergyResult.featureEnabled)
        assertTrue(allergyResult.alerts.isEmpty())

        val chronicResult = MedicationSafetyCoordinator.chronicConditionService.evaluateMedicineForConditions(
            medicine = medB,
            userConditions = listOf(
                UserChronicConditionRecord(
                    recordId = "c1",
                    conditionCode = ChronicConditionCatalog.CHRONIC_KIDNEY_DISEASE
                )
            )
        )
        assertFalse(chronicResult.featureEnabled)
        assertTrue(chronicResult.warnings.isEmpty())

        // Coordinator hook is a no-op when disabled
        MedicationSafetyCoordinator.onCabinetMedicinesUpdated(listOf(medA, medB))
        assertFalse(MedicationSafetyCoordinator.lastCompatibilityResult.value.featureEnabled)
    }

    @Test
    fun test06_medicationCompatibilityServiceDetectsInteractionsDuplicatesAndIncompleteDataWhenEnabled() {
        SafetyFeatureFlags.setFeatureFlagsForTesting(
            userAccounts = true,
            medicationCompatibility = true
        )

        val service = MedicationCompatibilityService()

        // 1. Duplicate active ingredient detection (Panadol + Tylenol -> both Paracetamol)
        val panadol = testMedicine(id = 1, tradeName = "Panadol Extra", scientificName = "Paracetamol + Caffeine")
        val tylenol = testMedicine(id = 2, tradeName = "Tylenol Forte", scientificName = "Acetaminophen 500mg")
        val duplicateResult = service.evaluateCabinetCompatibility(listOf(panadol, tylenol))
        assertTrue(duplicateResult.featureEnabled)
        assertTrue(duplicateResult.hasSeriousContraindications)
        assertTrue(duplicateResult.warnings.any {
            it.severity == InteractionSeverity.CONTRAINDICATED_SERIOUS &&
                "paracetamol" in it.involvedIngredients
        })

        // 2. Serious drug-drug interaction (Warfarin + Ibuprofen) and professional consultation advice
        val warfarin = testMedicine(id = 3, tradeName = "Coumadin", scientificName = "Warfarin")
        val ibuprofen = testMedicine(id = 4, tradeName = "Brufen", scientificName = "Ibuprofen")
        val interactionResult = service.evaluateCabinetCompatibility(listOf(warfarin, ibuprofen))
        assertTrue(interactionResult.hasSeriousContraindications)
        val bleedWarning = interactionResult.warnings.first()
        assertEquals(InteractionSeverity.CONTRAINDICATED_SERIOUS, bleedWarning.severity)
        assertTrue(bleedWarning.patientGuidance.contains("pharmacist or physician", ignoreCase = true))
        assertTrue(bleedWarning.patientGuidance.contains("Do not stop taking a prescribed medication", ignoreCase = true))
        assertTrue(bleedWarning.sourceRefs.isNotEmpty())

        // 3. Moderate interaction (Lisinopril + Spironolactone -> Hyperkalemia risk)
        val lisinopril = testMedicine(id = 5, tradeName = "Zestril", scientificName = "Lisinopril")
        val spironolactone = testMedicine(id = 6, tradeName = "Aldactone", scientificName = "Spironolactone")
        val moderateResult = service.evaluateCabinetCompatibility(listOf(lisinopril, spironolactone))
        assertTrue(moderateResult.warnings.any { it.severity == InteractionSeverity.MODERATE })

        // 4. Incomplete ingredient data is explicitly flagged (never falsely claims compatibility)
        val unknownHerbal = testMedicine(id = 7, tradeName = "Mystery Custom Capsule", scientificName = null)
        val incompleteResult = service.evaluateCabinetCompatibility(listOf(lisinopril, unknownHerbal))
        assertTrue(incompleteResult.hasIncompleteData)
        assertTrue(incompleteResult.insufficientDataMedicines.contains("Mystery Custom Capsule"))
        assertTrue(incompleteResult.warnings.any { it.severity == InteractionSeverity.INSUFFICIENT_DATA })
    }

    @Test
    fun test07_allergyAlertServiceDetectsDirectMatchesCrossSensitivityAndIncompleteIngredientsWhenEnabled() {
        SafetyFeatureFlags.setFeatureFlagsForTesting(
            userAccounts = true,
            allergyAlerts = true
        )

        val service = AllergyAlertService()
        val userAllergies = listOf(
            UserAllergyRecord(
                allergyId = "alg_penicillin",
                allergenName = "Penicillin",
                reactionSeverity = AllergyReactionSeverity.ANAPHYLAXIS_SEVERE
            ),
            UserAllergyRecord(
                allergyId = "alg_ibuprofen",
                allergenName = "Ibuprofen",
                reactionSeverity = AllergyReactionSeverity.MODERATE
            )
        )

        // 1. Direct ingredient match (Advil -> Ibuprofen)
        val advil = testMedicine(id = 10, tradeName = "Advil Liqui-Gels", scientificName = "Ibuprofen 200mg")
        val directEval = service.checkMedicineAgainstAllergies(advil, userAllergies)
        assertTrue(directEval.featureEnabled)
        assertEquals(AllergyMatchStatus.DIRECT_INGREDIENT_MATCH, directEval.matchStatus)
        assertTrue(directEval.hasConfirmedOrCrossReactiveMatch)

        // 2. Combination product & cross-sensitive drug class match (Augmentin -> Amoxicillin + Clavulanic Acid vs Penicillin allergy)
        val crossEval = service.checkRawMedicineAgainstAllergies(
            tradeName = "Augmentin 1g",
            scientificName = "Amoxicillin + Potassium Clavulanate",
            recordedAllergies = userAllergies
        )
        assertEquals(AllergyMatchStatus.CROSS_SENSITIVITY_CLASS_MATCH, crossEval.matchStatus)
        assertTrue(crossEval.hasConfirmedOrCrossReactiveMatch)

        // 3. Incomplete ingredient data is distinguished from confirmed match or no match
        val incompleteMed = testMedicine(id = 30, tradeName = "Unlabeled Compound", scientificName = "")
        val incompleteEval = service.checkMedicineAgainstAllergies(incompleteMed, userAllergies)
        assertEquals(AllergyMatchStatus.INCOMPLETE_INGREDIENT_DATA, incompleteEval.matchStatus)
        assertFalse(incompleteEval.hasConfirmedOrCrossReactiveMatch)

        // 4. No known match found includes mandatory non-guarantee safety disclaimer (never claims "allergy-safe")
        val loratadine = testMedicine(id = 40, tradeName = "Claritin", scientificName = "Loratadine 10mg")
        val noMatchEval = service.checkMedicineAgainstAllergies(loratadine, userAllergies)
        assertEquals(AllergyMatchStatus.NO_KNOWN_MATCH_FOUND, noMatchEval.matchStatus)
        assertTrue(noMatchEval.safetyDisclaimer.contains("does not guarantee", ignoreCase = true))
    }

    @Test
    fun test08_chronicConditionProfileServiceDetectsConditionContraindicationsWhenEnabled() {
        SafetyFeatureFlags.setFeatureFlagsForTesting(
            userAccounts = true,
            chronicConditionsProfile = true
        )

        val service = ChronicConditionProfileService()

        // Verify predefined catalog & verified terminology resolution (ICD-10 / SNOMED-CT)
        assertEquals(ChronicConditionCatalog.HYPERTENSION, ChronicConditionCatalog.resolveVerifiedCondition("I10"))
        assertEquals(ChronicConditionCatalog.ASTHMA, ChronicConditionCatalog.resolveVerifiedCondition("195967001"))
        assertEquals(
            ChronicConditionCatalog.CHRONIC_KIDNEY_DISEASE,
            ChronicConditionCatalog.resolveVerifiedCondition("Chronic renal insufficiency")
        )

        // User with multiple chronic conditions: Hypertension, Chronic Kidney Disease, and Asthma
        val userConditions = listOf(
            UserChronicConditionRecord("cond_1", ChronicConditionCatalog.HYPERTENSION),
            UserChronicConditionRecord("cond_2", ChronicConditionCatalog.CHRONIC_KIDNEY_DISEASE),
            UserChronicConditionRecord("cond_3", ChronicConditionCatalog.ASTHMA)
        )

        // 1. Decongestant combination product (Panadol Cold & Flu -> Pseudoephedrine) contraindicated in Hypertension
        val coldAndFlu = testMedicine(
            id = 50,
            tradeName = "Panadol Cold & Flu",
            scientificName = "Paracetamol + Chlorpheniramine + Pseudoephedrine"
        )
        val htnResult = service.evaluateMedicineForConditions(coldAndFlu, userConditions)
        assertTrue(htnResult.featureEnabled)
        assertTrue(htnResult.warnings.any {
            it.condition == ChronicConditionCatalog.HYPERTENSION &&
                it.riskLevel == ConditionRiskLevel.CONTRAINDICATED
        })

        // 2. NSAID (Diclofenac) contraindicated in Chronic Kidney Disease and cautionary in Hypertension & Asthma
        val voltaren = testMedicine(id = 51, tradeName = "Voltaren", scientificName = "Diclofenac Sodium")
        val ckdResult = service.evaluateMedicineForConditions(voltaren, userConditions)
        assertTrue(ckdResult.warnings.any {
            it.condition == ChronicConditionCatalog.CHRONIC_KIDNEY_DISEASE &&
                it.riskLevel == ConditionRiskLevel.CONTRAINDICATED
        })
        assertTrue(ckdResult.warnings.all { it.sourceRefs.isNotEmpty() })

        // 3. Non-selective Beta Blocker (Propranolol) contraindicated in Asthma
        val inderal = testMedicine(id = 52, tradeName = "Inderal", scientificName = "Propranolol")
        val asthmaResult = service.evaluateMedicineForConditions(inderal, userConditions)
        assertTrue(asthmaResult.warnings.any {
            it.condition == ChronicConditionCatalog.ASTHMA &&
                it.riskLevel == ConditionRiskLevel.CONTRAINDICATED
        })
    }

    // =========================================================================
    // SECTION 9: REFILL NEEDED VS ONE-TIME MEDICATION ROUTINE INTEGRATION & KEYBOARD RESIZING
    // =========================================================================

    @Test
    fun test09_refillNeededAndOneTimeMedicationRoutineIntegration() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = db.appDao()
        val scheduleRepo = MedicationScheduleRepository(dao = dao, notificationScheduler = null)

        try {
            val futureExpiry = System.currentTimeMillis() + 90L * 24L * 3600L * 1000L

            // 1. Insert an ONGOING_REFILL medicine with 1 tablet left
            val ongoingMedId = dao.insertMedicine(
                Medicine(
                    id = 0,
                    tradeName = "Atorvastatin 20mg",
                    scientificName = "Atorvastatin",
                    barcode = null,
                    form = "Tablet",
                    strength = "20mg",
                    manufacturer = null,
                    imagePath = null,
                    indications = null,
                    warnings = null,
                    sourceId = null,
                    createdAt = System.currentTimeMillis(),
                    refillClassification = MedicationRefillClassification.ONGOING_REFILL.key
                )
            ).toInt()
            dao.insertInventoryItem(
                InventoryItem(
                    id = 0,
                    medicineId = ongoingMedId,
                    familyMemberId = null,
                    quantity = 1.0,
                    unit = "tablets",
                    purchaseDate = null,
                    expiryDate = futureExpiry,
                    batchNumber = null,
                    storageLocation = "Cabinet",
                    needsRefrigeration = false,
                    usageMode = null,
                    userNotes = null
                )
            )

            // 2. Insert a ONE_TIME medicine with 1 capsule left
            val oneTimeMedId = dao.insertMedicine(
                Medicine(
                    id = 0,
                    tradeName = "Amoxicillin Course",
                    scientificName = "Amoxicillin",
                    barcode = null,
                    form = "Capsule",
                    strength = "500mg",
                    manufacturer = null,
                    imagePath = null,
                    indications = null,
                    warnings = null,
                    sourceId = null,
                    createdAt = System.currentTimeMillis(),
                    refillClassification = MedicationRefillClassification.ONE_TIME.key
                )
            ).toInt()
            val oneTimeInvId = dao.insertInventoryItem(
                InventoryItem(
                    id = 0,
                    medicineId = oneTimeMedId,
                    familyMemberId = null,
                    quantity = 1.0,
                    unit = "capsules",
                    purchaseDate = null,
                    expiryDate = futureExpiry,
                    batchNumber = null,
                    storageLocation = "Cabinet",
                    needsRefrigeration = false,
                    usageMode = null,
                    userNotes = null
                )
            ).toInt()

            val todayIso = ExpiryService.currentLocalDate().toString()

            // Create active daily schedules for both medicines
            val ongoingSchedule = scheduleRepo.createSchedule(
                ScheduleDraftInput(
                    userId = "default_user",
                    medicineId = ongoingMedId,
                    medicineName = "Atorvastatin 20mg",
                    defaultDosage = 1.0,
                    dosageUnit = "tablet",
                    routeOfAdministration = "Oral",
                    instructions = "Take at night",
                    startDateIso = todayIso,
                    endDateIso = null,
                    frequency = ScheduleFrequency.DAILY,
                    timeSlots = listOf(ScheduledTimeSlot("21:00", 1.0, "tablet")),
                    lowStockThreshold = 5.0
                )
            ).getOrThrow()

            val oneTimeSchedule = scheduleRepo.createSchedule(
                ScheduleDraftInput(
                    userId = "default_user",
                    medicineId = oneTimeMedId,
                    medicineName = "Amoxicillin Course",
                    defaultDosage = 1.0,
                    dosageUnit = "capsule",
                    routeOfAdministration = "Oral",
                    instructions = "Finish course",
                    startDateIso = todayIso,
                    endDateIso = null,
                    frequency = ScheduleFrequency.DAILY,
                    timeSlots = listOf(ScheduledTimeSlot("09:00", 1.0, "capsule")),
                    lowStockThreshold = 5.0
                )
            ).getOrThrow()

            // Mark the first pending dose TAKEN for the ONGOING_REFILL medicine -> remaining stock becomes 0.0
            val ongoingFirstDose = dao.getDoseRecordsForScheduleSnapshot(ongoingSchedule.id).first()
            scheduleRepo.markDoseTaken(doseRecordId = ongoingFirstDose.id, userId = "default_user")

            // Ongoing refill schedule MUST stay ACTIVE awaiting refill (and still have future pending doses)
            val updatedOngoingSchedule = dao.getScheduleById(ongoingSchedule.id, "default_user")!!
            assertEquals(ScheduleStatus.ACTIVE.key, updatedOngoingSchedule.status)
            val remainingOngoingPendingDoses = dao.getDoseRecordsForScheduleSnapshot(ongoingSchedule.id)
                .filter { it.status == "PENDING" }
            assertTrue(
                "Ongoing refill medicine must keep its routine active with upcoming reminders",
                remainingOngoingPendingDoses.isNotEmpty()
            )

            // Mark the first pending dose TAKEN for the ONE_TIME medicine -> remaining stock becomes 0.0
            val oneTimeFirstDose = dao.getDoseRecordsForScheduleSnapshot(oneTimeSchedule.id).first()
            scheduleRepo.markDoseTaken(doseRecordId = oneTimeFirstDose.id, userId = "default_user")

            // One-time schedule MUST automatically transition to COMPLETED and clear future pending reminders
            val updatedOneTimeSchedule = dao.getScheduleById(oneTimeSchedule.id, "default_user")!!
            assertEquals(ScheduleStatus.COMPLETED.key, updatedOneTimeSchedule.status)
            val remainingOneTimePendingDoses = dao.getDoseRecordsForScheduleSnapshot(oneTimeSchedule.id)
                .filter { it.status == "PENDING" }
            assertTrue(
                "Completed one-time medicine must not leave future pending reminders",
                remainingOneTimePendingDoses.isEmpty()
            )

            // If the user restocks the medicine (e.g., adds 10 capsules), the routine reactivates cleanly without duplicates
            dao.updateInventoryQuantity(oneTimeInvId, 10.0)
            scheduleRepo.onMedicineInventoryUpdated(medicineId = oneTimeMedId)

            val reactivatedOneTimeSchedule = dao.getScheduleById(oneTimeSchedule.id, "default_user")!!
            assertEquals(ScheduleStatus.ACTIVE.key, reactivatedOneTimeSchedule.status)
            assertEquals(
                1,
                dao.getSchedulesForMedicineSnapshot(oneTimeMedId).size
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun test10_mobileKeyboardWindowSoftInputModeIsAdjustResize() {
        val pm = context.packageManager
        val componentName = ComponentName(context, MainActivity::class.java)
        val activityInfo = pm.getActivityInfo(componentName, PackageManager.GET_META_DATA)
        val softInputMode = activityInfo.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST
        assertEquals(
            "MainActivity must use adjustResize so opening the keyboard does not push headers off-screen",
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            softInputMode
        )
    }
}
