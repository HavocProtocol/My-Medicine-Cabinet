import re
with open('app/src/main/java/com/example/ui/Navigation.kt', 'r') as f:
    content = f.read()

replacement_home = """        composable("home") {
            DashboardScreen(
                onAdd = { navController.navigate("add_medicine") },
                onStatCardClick = { filter -> navController.navigate("medicine_list/$filter") },"""

content = content.replace('        composable("home") {\n            DashboardScreen(\n                onAdd = { navController.navigate("add_medicine") },', replacement_home)

new_screen = """        composable("medicine_list/{filter}") { backStackEntry ->
            val filter = backStackEntry.arguments?.getString("filter") ?: "total"
            MedicineListScreen(
                filter = filter,
                onBack = { navController.popBackStack() },
                onMedicineClick = { id -> navController.navigate("medicine_detail/$id") }
            )
        }
        
        composable("notifications") {"""

content = content.replace('        composable("notifications") {', new_screen)

with open('app/src/main/java/com/example/ui/Navigation.kt', 'w') as f:
    f.write(content)

