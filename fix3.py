import re

# Fix AddMedicineScreen
with open('app/src/main/java/com/example/ui/screens/AddMedicineScreen.kt', 'r') as f:
    content = f.read()

# Remove the duplicated colors block we injected
content = re.sub(r'shape = androidx.compose.foundation.shape.RoundedCornerShape\(12.dp\),\ncolors = androidx.compose.material3.OutlinedTextFieldDefaults.colors\([\s\S]*?\),\n                    value = form,', 
                 'shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),\n                    value = form,', 
                 content)

with open('app/src/main/java/com/example/ui/screens/AddMedicineScreen.kt', 'w') as f:
    f.write(content)

# Fix OnboardingScreen
with open('app/src/main/java/com/example/ui/screens/OnboardingScreen.kt', 'r') as f:
    content = f.read()

content = content.replace("RadioButton(\nshape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),", "RadioButton(")

with open('app/src/main/java/com/example/ui/screens/OnboardingScreen.kt', 'w') as f:
    f.write(content)
