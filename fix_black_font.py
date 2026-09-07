import os
import glob
import re

for filepath in glob.glob("app/src/main/java/com/example/ui/screens/*.kt"):
    with open(filepath, 'r') as f:
        content = f.read()

    # DashboardScreen specific
    if "DashboardScreen" in filepath:
        content = re.sub(r'val darkBlueText\s*=\s*Color\(0xFF1A233A\)', 'val darkBlueText = Color.Black', content)
        
    # Replace other texts
    content = content.replace("color = Color.Gray", "color = Color.Black")
    content = content.replace("color = Color.DarkGray", "color = Color.Black")
    content = content.replace("Color(0xFF49454F)", "Color.Black")
    content = content.replace("Color(0xFF1A233A)", "Color.Black")
    content = content.replace("com.example.ui.theme.DarkBlueText", "Color.Black")
    
    with open(filepath, 'w') as f:
        f.write(content)
