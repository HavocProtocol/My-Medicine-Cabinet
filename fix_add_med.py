import re

with open('app/src/main/java/com/example/ui/screens/AddMedicineScreen.kt', 'r') as f:
    content = f.read()

# Update AddMedicineScreen signature
old_sig = """fun AddMedicineScreen(
    onSave: () -> Unit,
    viewModel: AppViewModel = viewModel()
) {"""
new_sig = """fun AddMedicineScreen(
    initialBarcode: String? = null,
    initialTradeName: String? = null,
    initialManufacturer: String? = null,
    onSave: () -> Unit,
    viewModel: AppViewModel = viewModel()
) {"""
content = content.replace(old_sig, new_sig)

# Update state variables
content = content.replace('var tradeName by remember { mutableStateOf("") }', 'var tradeName by remember { mutableStateOf(initialTradeName ?: "") }')
content = content.replace('var manufacturer by remember { mutableStateOf("") }', 'var manufacturer by remember { mutableStateOf(initialManufacturer ?: "") }\n    var barcode by remember { mutableStateOf(initialBarcode ?: "") }')

# Add barcode text field before tradeName
text_fields_start = """        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {"""
barcode_field = """        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = com.example.ui.theme.BgLight,
                    unfocusedContainerColor = com.example.ui.theme.BgLight,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent
                ),
                value = barcode,
                onValueChange = { barcode = it },
                label = { Text("الباركود (GTIN)") },
                modifier = Modifier.fillMaxWidth()
            )"""
content = content.replace(text_fields_start, barcode_field)

# Update insertMedicine call
content = content.replace('barcode = null,', 'barcode = barcode.takeIf { it.isNotBlank() },')

with open('app/src/main/java/com/example/ui/screens/AddMedicineScreen.kt', 'w') as f:
    f.write(content)
