import re
with open('app/src/main/java/com/example/ui/screens/AddMedicineScreen.kt', 'r') as f:
    content = f.read()

replacement = """                            purchaseDate = System.currentTimeMillis(),
                            expiryDate = datePickerState.selectedDateMillis ?: (System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000),
                            batchNumber = batchNumber.takeIf { it.isNotBlank() },"""

pattern = re.compile(r'                            purchaseDate = System\.currentTimeMillis\(\),\s*expiryDate = System\.currentTimeMillis\(\) \+ 365L \* 24 \* 60 \* 60 \* 1000,\s*batchNumber = batchNumber\.takeIf \{ it\.isNotBlank\(\) \},')

content = pattern.sub(replacement, content)

with open('app/src/main/java/com/example/ui/screens/AddMedicineScreen.kt', 'w') as f:
    f.write(content)

