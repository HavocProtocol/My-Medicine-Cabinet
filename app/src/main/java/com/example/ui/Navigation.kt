package com.example.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.boxscan.models.MedicineDraft
import com.example.boxscan.ui.BoxScanScreen
import com.example.ui.screens.*
import java.net.URLDecoder
import java.net.URLEncoder

@Composable
fun AppNavigation(
    navController: NavHostController = rememberNavController()
) {
    val context = LocalContext.current
    val startDest = rememberSaveable {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val isOnboardingComplete = prefs.getBoolean("onboarding_complete", false)
        if (isOnboardingComplete) "home" else "onboarding"
    }

    // Shared in-memory draft passed from BoxScanScreen -> AddMedicineScreen
    var pendingBoxDraft by remember { mutableStateOf<MedicineDraft?>(null) }

    NavHost(navController = navController, startDestination = startDest) {
        composable("onboarding") {
            OnboardingScreen(onFinish = {
                val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("onboarding_complete", true).apply()
                navController.navigate("home") {
                    popUpTo("onboarding") { inclusive = true }
                }
            })
        }
        composable("home") {
            DashboardScreen(
                onAdd = {
                    pendingBoxDraft = null
                    navController.navigate("add_medicine")
                },
                onScanBox = {
                    navController.navigate("scan_box")
                },
                onSchedule = {
                    navController.navigate("medication_schedule")
                },
                onStatCardClick = { filter -> navController.navigate("medicine_list/$filter") },
                onCategories = { navController.navigate("categories") },
                onPrint = { navController.navigate("print") },
                onSettings = { navController.navigate("settings") },
                onNotifications = { navController.navigate("notifications") },
                onAISearch = { navController.navigate("aisearch") },
                onVoice = { navController.navigate("voice") },
                onTutorial = { navController.navigate("tutorial") },
                onNavigateToPremium = { navController.navigate("premium_subscription") },
                onNavigateToHelpCenter = { navController.navigate("help_center") },
                onMedicineClick = { id -> navController.navigate("medicine_detail/$id") },
                onRefillNeeded = { navController.navigate("refill_needed") },
                onOneTimeMedications = { navController.navigate("one_time_medications") }
            )
        }
        composable("premium_subscription") {
            PremiumSubscriptionScreen(
                onBack = { navController.popBackStack() },
                onNavigateToVoiceSearch = { navController.navigate("voice") },
                onNavigateToAISearch = { navController.navigate("aisearch") },
                onNavigateToHelpCenter = { navController.navigate("help_center") },
                onNavigateToSettings = { navController.navigate("settings") }
            )
        }
        composable("help_center") {
            HelpCenterScreen(
                onBack = { navController.popBackStack() },
                onNavigateToPremiumSubscription = { navController.navigate("premium_subscription") },
                onNavigateToLiveChat = { navController.navigate("live_chat") }
            )
        }
        composable("live_chat") {
            LiveChatScreen(
                onBack = { navController.popBackStack() },
                onNavigateToPremiumSubscription = {
                    navController.navigate("premium_subscription") {
                        popUpTo("live_chat") { inclusive = true }
                    }
                }
            )
        }
        composable("refill_needed") {
            RefillNeededScreen(
                onBack = { navController.popBackStack() },
                onMedicineClick = { id -> navController.navigate("medicine_detail/$id") }
            )
        }
        composable("one_time_medications") {
            OneTimeMedicationsScreen(
                onBack = { navController.popBackStack() },
                onMedicineClick = { id -> navController.navigate("medicine_detail/$id") }
            )
        }
        composable(
            route = "medication_schedule?medicineId={medicineId}",
            arguments = listOf(
                navArgument("medicineId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val medId = backStackEntry.arguments?.getString("medicineId")?.toIntOrNull()
            MedicationScheduleScreen(
                initialMedicineId = medId,
                onBack = { navController.popBackStack() }
            )
        }
        composable("scan_box") {
            BoxScanScreen(
                onDraftConfirmed = { draft ->
                    pendingBoxDraft = draft
                    navController.navigate("add_medicine") {
                        popUpTo("scan_box") { inclusive = true }
                    }
                },
                onCancel = {
                    navController.popBackStack()
                }
            )
        }
        composable(
            route = "add_medicine?name={name}&manufacturer={manufacturer}",
            arguments = listOf(
                navArgument("name") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("manufacturer") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val name = backStackEntry.arguments?.getString("name")?.let {
                try { URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it }
            }
            val manufacturer = backStackEntry.arguments?.getString("manufacturer")?.let {
                try { URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it }
            }

            AddMedicineScreen(
                initialTradeName = name,
                initialManufacturer = manufacturer,
                prefillDraft = pendingBoxDraft,
                onSave = {
                    pendingBoxDraft = null
                    navController.navigate("home") { popUpTo("home") { inclusive = true } }
                },
                onBack = {
                    pendingBoxDraft = null
                    navController.popBackStack()
                }
            )
        }
        composable("medicine_detail/{id}") { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id")?.toIntOrNull()
            if (id != null) {
                MedicineDetailScreen(
                    medicineId = id,
                    onBack = { navController.popBackStack() },
                    onNavigateToMedInfo = { name, sci ->
                        val encodedName = URLEncoder.encode(name, "UTF-8")
                        val encodedSci = URLEncoder.encode(sci, "UTF-8")
                        navController.navigate("med_info?name=$encodedName&sci=$encodedSci")
                    },
                    onScheduleMedicine = { medId ->
                        navController.navigate("medication_schedule?medicineId=$medId")
                    }
                )
            }
        }
        composable(
            route = "med_info?name={name}&sci={sci}",
            arguments = listOf(
                navArgument("name") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = "Panadol"
                },
                navArgument("sci") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = "Paracetamol"
                }
            )
        ) { backStackEntry ->
            val rawName = backStackEntry.arguments?.getString("name") ?: "Panadol"
            val rawSci = backStackEntry.arguments?.getString("sci") ?: "Paracetamol"
            val name = try { URLDecoder.decode(rawName, "UTF-8") } catch (_: Exception) { rawName }
            val sci = try { URLDecoder.decode(rawSci, "UTF-8") } catch (_: Exception) { rawSci }

            MedInfoScreen(
                medicineName = name,
                scientificName = sci,
                onBack = { navController.popBackStack() },
                onNavigateToPremium = { navController.navigate("premium_subscription") }
            )
        }
        composable("medicine_list/{filter}") { backStackEntry ->
            val filter = backStackEntry.arguments?.getString("filter") ?: "total"
            MedicineListScreen(
                filter = filter,
                onBack = { navController.popBackStack() },
                onMedicineClick = { id -> navController.navigate("medicine_detail/$id") }
            )
        }

        composable("notifications") {
            NotificationsScreen(onBack = { navController.popBackStack() })
        }
        composable("settings") {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onNavigateToTutorial = { navController.navigate("tutorial") },
                onNavigateToPremium = { navController.navigate("premium_subscription") },
                onNavigateToHelpCenter = { navController.navigate("help_center") }
            )
        }
        composable("tutorial") {
            TutorialScreen(onBack = { navController.popBackStack() })
        }
        composable("print") {
            PrintScreen(onBack = { navController.popBackStack() })
        }
        composable("aisearch") {
            AISearchScreen(
                onBack = { navController.popBackStack() },
                onNavigateToPremium = { navController.navigate("premium_subscription") }
            )
        }
        composable("voice") {
            VoiceScreen(
                onBack = { navController.popBackStack() },
                onNavigateToPremium = { navController.navigate("premium_subscription") }
            )
        }
        composable("categories") {
            CategoriesScreen(onBack = { navController.popBackStack() })
        }
    }
}
