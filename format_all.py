import os
import glob

def process_file(filepath):
    with open(filepath, 'r') as f:
        content = f.read()

    # Apply general rounded corners to OutlinedTextField
    if "OutlinedTextField(" in content and "shape =" not in content:
        content = content.replace("OutlinedTextField(", "OutlinedTextField(\nshape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),\ncolors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(\nfocusedContainerColor = com.example.ui.theme.BgLight,\nunfocusedContainerColor = com.example.ui.theme.BgLight,\nfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,\nunfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent\n),")

    # Apply button shape
    if "Button(" in content and "shape =" not in content:
        content = content.replace("Button(", "Button(\nshape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),")

    # TopAppBar colors
    if "TopAppBar(" in content and "colors =" not in content:
        content = content.replace("TopAppBar(", "TopAppBar(\ncolors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.White, titleContentColor = com.example.ui.theme.DarkBlueText),")

    # Scaffold background
    if "Scaffold(" in content and "containerColor =" not in content:
         content = content.replace("Scaffold(", "Scaffold(\ncontainerColor = androidx.compose.ui.graphics.Color.White,")

    with open(filepath, 'w') as f:
        f.write(content)

for filepath in glob.glob("app/src/main/java/com/example/ui/screens/*.kt"):
    if "DashboardScreen" not in filepath:
        process_file(filepath)

