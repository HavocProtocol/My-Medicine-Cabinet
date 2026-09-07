import re

with open('app/src/main/java/com/example/ui/screens/ScanScreen.kt', 'r') as f:
    content = f.read()

content = content.replace('.androidx.compose.foundation.background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f))', '.background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f))')
content = content.replace('color = androidx.compose.ui.graphics.Color.White', 'color = androidx.compose.ui.graphics.Color.White')

with open('app/src/main/java/com/example/ui/screens/ScanScreen.kt', 'w') as f:
    f.write(content)
