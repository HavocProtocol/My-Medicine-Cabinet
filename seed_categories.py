with open('app/src/main/java/com/example/ui/viewmodels/AppViewModel.kt', 'r') as f:
    content = f.read()

seed_code = """
        viewModelScope.launch {
            val cats = dao.getAllCategories().first()
            if (cats.isEmpty()) {
                dao.insertCategory(Category(name = "مسكنات الألم", description = null, color = null, icon = null))
                dao.insertCategory(Category(name = "مضادات حيوية", description = null, color = null, icon = null))
                dao.insertCategory(Category(name = "فيتامينات", description = null, color = null, icon = null))
                dao.insertCategory(Category(name = "أدوية مزمنة", description = null, color = null, icon = null))
            }
        }
"""

content = content.replace("checkAndGenerateAlerts()", "checkAndGenerateAlerts()\n" + seed_code)

with open('app/src/main/java/com/example/ui/viewmodels/AppViewModel.kt', 'w') as f:
    f.write(content)
