with open('app/src/main/java/com/example/ui/screens/CategoriesScreen.kt', 'r') as f:
    content = f.read()

if 'import com.example.ui.utils.getLocalizedName' not in content:
    content = content.replace('import com.example.ui.viewmodels.AppViewModel', 'import com.example.ui.viewmodels.AppViewModel\nimport com.example.ui.utils.getLocalizedName')

with open('app/src/main/java/com/example/ui/screens/CategoriesScreen.kt', 'w') as f:
    f.write(content)
