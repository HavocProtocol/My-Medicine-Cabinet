import os
import glob
import re

def process_file(filepath):
    with open(filepath, 'r') as f:
        content = f.read()

    # Fix IconButton shape injection
    content = content.replace("IconButton(\nshape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),", "IconButton(")
    
    # Fix TextButton shape injection (TextButton has shape, but let's be safe)
    content = content.replace("TextButton(\nshape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),", "TextButton(")

    # Fix OutlinedTextField colors collision (if there's two colors parameters)
    # We injected colors = ... before colors = ExposedDropdownMenu...
    # It's easier to find OutlinedTextField( ... colors = X, ... colors = Y
    # Wait, the error for OutlinedTextField was in AddMedicineScreen.kt:94:17 None of the following candidates is applicable
    # That's because we provided `colors` twice for the ExposedDropdownMenu OutlinedTextField
    
    with open(filepath, 'w') as f:
        f.write(content)

for filepath in glob.glob("app/src/main/java/com/example/ui/screens/*.kt"):
    if "DashboardScreen" not in filepath:
        process_file(filepath)
