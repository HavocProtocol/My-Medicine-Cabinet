import re

files = [
    ('app/src/main/res/values/strings.xml', '    <string name="find_pharmacy">Find Pharmacy</string>\n</resources>'),
    ('app/src/main/res/values-en/strings.xml', '    <string name="find_pharmacy">Find Pharmacy</string>\n</resources>'),
    ('app/src/main/res/values-ar/strings.xml', '    <string name="find_pharmacy">أقرب صيدلية</string>\n</resources>'),
    ('app/src/main/res/values-fr/strings.xml', '    <string name="find_pharmacy">Pharmacie la plus proche</string>\n</resources>'),
    ('app/src/main/res/values-de/strings.xml', '    <string name="find_pharmacy">Nächste Apotheke</string>\n</resources>')
]

for filepath, replacement in files:
    try:
        with open(filepath, 'r') as f:
            content = f.read()
        content = content.replace('</resources>', replacement)
        with open(filepath, 'w') as f:
            f.write(content)
        print(f"Patched {filepath}")
    except Exception as e:
        print(f"Failed {filepath}: {e}")
