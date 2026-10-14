import re

with open('app/src/main/java/com/example/ui/screens/AISearchScreen.kt', 'r') as f:
    content = f.read()

content = content.replace('"Error: ${e.message}"', '"حدث خطأ في الاتصال: ${e.message}"')

with open('app/src/main/java/com/example/ui/screens/AISearchScreen.kt', 'w') as f:
    f.write(content)
