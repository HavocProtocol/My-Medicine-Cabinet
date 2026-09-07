import re

with open('app/src/main/java/com/example/ui/Navigation.kt', 'r') as f:
    content = f.read()

# Update add_medicine composable
old_add = """        composable("add_medicine") {
            AddMedicineScreen(onSave = { navController.popBackStack() })
        }"""
new_add = """        composable(
            route = "add_medicine?barcode={barcode}&name={name}&manufacturer={manufacturer}",
            arguments = listOf(
                androidx.navigation.navArgument("barcode") { type = androidx.navigation.NavType.StringType; nullable = true },
                androidx.navigation.navArgument("name") { type = androidx.navigation.NavType.StringType; nullable = true },
                androidx.navigation.navArgument("manufacturer") { type = androidx.navigation.NavType.StringType; nullable = true }
            )
        ) { backStackEntry ->
            val barcode = backStackEntry.arguments?.getString("barcode")
            val name = backStackEntry.arguments?.getString("name")
            val manufacturer = backStackEntry.arguments?.getString("manufacturer")
            
            AddMedicineScreen(
                initialBarcode = barcode,
                initialTradeName = name,
                initialManufacturer = manufacturer,
                onSave = { navController.navigate("home") { popUpTo("home") { inclusive = true } } }
            )
        }"""
content = content.replace(old_add, new_add)

# Update scan composable
old_scan = """        composable("scan") {
            ScanScreen(onBack = { navController.popBackStack() })
        }"""
new_scan = """        composable("scan") {
            ScanScreen(
                onBack = { navController.popBackStack() },
                onNavigateToAddMedicine = { barcode, name, manufacturer ->
                    // Navigate to add medicine with args
                    val encodedName = java.net.URLEncoder.encode(name, "UTF-8")
                    val encodedMan = java.net.URLEncoder.encode(manufacturer, "UTF-8")
                    navController.navigate("add_medicine?barcode=$barcode&name=$encodedName&manufacturer=$encodedMan") {
                        popUpTo("home")
                    }
                }
            )
        }"""
content = content.replace(old_scan, new_scan)

with open('app/src/main/java/com/example/ui/Navigation.kt', 'w') as f:
    f.write(content)
