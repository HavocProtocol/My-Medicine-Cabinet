import glob
import re

for filepath in glob.glob("app/src/main/java/com/example/ui/screens/*.kt"):
    if "DashboardScreen" in filepath:
        continue
    with open(filepath, 'r') as f:
        content = f.read()

    # Remove the bad IconButton shape
    content = re.sub(r'IconButton\(\nshape = androidx\.compose\.foundation\.shape\.RoundedCornerShape\(12\.dp\),', 'IconButton(', content)
    
    # Remove bad TextButton shape
    content = re.sub(r'TextButton\(\nshape = androidx\.compose\.foundation\.shape\.RoundedCornerShape\(12\.dp\),', 'TextButton(', content)
    
    # Remove bad OutlinedTextField in AddMedicineScreen (the one with ExposedDropdownMenuDefaults)
    content = re.sub(r'OutlinedTextField\([^)]*colors = ExposedDropdownMenuDefaults\.outlinedTextFieldColors\(\),', 
                     lambda m: m.group(0).replace('colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(focusedContainerColor = com.example.ui.theme.BgLight,unfocusedContainerColor = com.example.ui.theme.BgLight,focusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent),', ''), 
                     content)

    with open(filepath, 'w') as f:
        f.write(content)
