import re
with open('app/src/main/java/com/example/ui/screens/AddMedicineScreen.kt', 'r') as f:
    content = f.read()

replacement = """
            OutlinedTextField(
                shape = RoundedCornerShape(12.dp),
                colors = textFieldColors,
                value = storageLocation,
                onValueChange = { storageLocation = it },
                label = { Text(stringResource(R.string.storage_location)) },
                modifier = Modifier.fillMaxWidth()
            )
            
            OutlinedTextField(
                shape = RoundedCornerShape(12.dp),
                colors = textFieldColors,
                value = selectedDateString,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.expiry_date)) },
                trailingIcon = {
                    IconButton(onClick = { showDatePicker = true }) {
                        Icon(Icons.Default.DateRange, contentDescription = "Select Date")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            if (showDatePicker) {
                DatePickerDialog(
                    onDismissRequest = { showDatePicker = false },
                    confirmButton = {
                        TextButton(onClick = { showDatePicker = false }) {
                            Text(stringResource(R.string.save))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDatePicker = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                ) {
                    DatePicker(state = datePickerState)
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {"""

pattern = re.compile(r'            OutlinedTextField\(\s*shape = RoundedCornerShape\(12\.dp\),\s*colors = textFieldColors,\s*value = storageLocation,\s*onValueChange = \{ storageLocation = it \},\s*label = \{ Text\(stringResource\(R\.string\.storage_location\)\) \},\s*modifier = Modifier\.fillMaxWidth\(\)\s*\)\s*Row\(verticalAlignment = Alignment\.CenterVertically\) \{')

content = pattern.sub(replacement, content)

with open('app/src/main/java/com/example/ui/screens/AddMedicineScreen.kt', 'w') as f:
    f.write(content)

