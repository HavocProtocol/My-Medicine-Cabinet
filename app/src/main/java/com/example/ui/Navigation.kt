package com.example.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.ui.screens.*
import java.net.URLDecoder
import java.net.URLEncoder

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    val isOnboardingComplete = prefs.getBoolean("onboarding_complete", false)
    val startDest = if (isOnboardingComplete) "home" else "onboarding"

    NavHost(navController = navController, startDestination = startDest) {
        composable("onboarding") {
            OnboardingScreen(onFinish = {
                prefs.edit().putBoolean("onboarding_complete", true).apply()
                navController.navigate("home") {
                    popUpTo("onboarding") { inclusive = true }
                }
            })
        }
        composable("home") {
            DashboardScreen(
                onAdd = { navController.navigate("add_medicine") },
                onScan = { navController.navigate("scan") },
                onCategories = { navController.navigate("categories") },
                onPrint = { navController.navigate("print") },
                onSettings = { navController.navigate("settings") },
                onNotifications = { navController.navigate("notifications") },
                onAISearch = { navController.navigate("aisearch") },
                onVoice = { navController.navigate("voice") },
                onTutorial = { navController.navigate("tutorial") }
            )
        }
        composable(
            route = "add_medicine?barcode={barcode}&name={name}&manufacturer={manufacturer}",
            arguments = listOf(
                navArgument("barcode") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
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
            val barcode = backStackEntry.arguments?.getString("barcode")
            val name = backStackEntry.arguments?.getString("name")?.let {
                try { URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it }
            }
            val manufacturer = backStackEntry.arguments?.getString("manufacturer")?.let {
                try { URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it }
            }

            AddMedicineScreen(
                initialBarcode = barcode,
                initialTradeName = name,
                initialManufacturer = manufacturer,
                onSave = { navController.navigate("home") { popUpTo("home") { inclusive = true } } },
                onBack = { navController.popBackStack() }
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
                    defaultValue = "بنادول إكسترا (Panadol Extra)"
                },
                navArgument("sci") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = "Paracetamol 500mg + Caffeine 65mg"
                }
            )
        ) { backStackEntry ->
            val rawName = backStackEntry.arguments?.getString("name") ?: "بنادول إكسترا (Panadol Extra)"
            val rawSci = backStackEntry.arguments?.getString("sci") ?: "Paracetamol 500mg + Caffeine 65mg"
            val name = try { URLDecoder.decode(rawName, "UTF-8") } catch (_: Exception) { rawName }
            val sci = try { URLDecoder.decode(rawSci, "UTF-8") } catch (_: Exception) { rawSci }

            MedInfoScreen(
                medicineName = name,
                scientificName = sci,
                onBack = { navController.popBackStack() }
            )
        }
        composable("notifications") {
            NotificationsScreen(onBack = { navController.popBackStack() })
        }
        composable("settings") {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onNavigateToTutorial = { navController.navigate("tutorial") }
            )
        }
        composable("tutorial") {
            TutorialScreen(onBack = { navController.popBackStack() })
        }
        composable("scan") {
            ScanScreen(
                onBack = { navController.popBackStack() },
                onBarcodeScanned = { barcode, name, manufacturer ->
                    val encodedName = URLEncoder.encode(name, "UTF-8")
                    val encodedMan = URLEncoder.encode(manufacturer, "UTF-8")
                    navController.navigate("add_medicine?barcode=$barcode&name=$encodedName&manufacturer=$encodedMan") {
                        popUpTo("home")
                    }
                }
            )
        }
        composable("print") {
            PrintScreen(onBack = { navController.popBackStack() })
        }
        composable("aisearch") {
            AISearchScreen(onBack = { navController.popBackStack() })
        }
        composable("voice") {
            VoiceScreen(onBack = { navController.popBackStack() })
        }
        composable("categories") {
            CategoriesScreen(onBack = { navController.popBackStack() })
        }
    }
}
