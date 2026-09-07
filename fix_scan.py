import re

with open('app/src/main/java/com/example/ui/screens/ScanScreen.kt', 'r') as f:
    content = f.read()

# Replace ScanScreen signature
content = content.replace(
    'fun ScanScreen(onBack: () -> Unit) {',
    'fun ScanScreen(onBack: () -> Unit, onNavigateToAddMedicine: (String, String, String) -> Unit) {'
)

# Add isResolving state and coroutineScope
new_states = """    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
    val coroutineScope = rememberCoroutineScope()
    var isResolving by remember { mutableStateOf(false) }"""
content = content.replace('    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)', new_states)

# Replace the onBarcodeScanned callback in ScanScreen
old_preview_call = """                BarcodeScannerPreview(
                    onBarcodeScanned = { barcode ->
                        println("Scanned: $barcode")
                    }
                )"""
new_preview_call = """                BarcodeScannerPreview(
                    onBarcodeScanned = { barcode ->
                        if (!isResolving) {
                            isResolving = true
                            coroutineScope.launch {
                                val drugInfo = com.example.data.SfdaRepository.lookupGtin(barcode)
                                onNavigateToAddMedicine(drugInfo.barcode, drugInfo.tradeName, drugInfo.manufacturer)
                            }
                        }
                    }
                )
                if (isResolving) {
                    Box(modifier = Modifier.fillMaxSize().androidx.compose.foundation.background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = androidx.compose.ui.graphics.Color.White)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("جارٍ التحقق من قاعدة بيانات SFDA / GS1...", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }"""
content = content.replace(old_preview_call, new_preview_call)

# Add kotlinx.coroutines.launch import
if 'import kotlinx.coroutines.launch' not in content:
    content = content.replace('import androidx.compose.runtime.*', 'import androidx.compose.runtime.*\nimport kotlinx.coroutines.launch')

with open('app/src/main/java/com/example/ui/screens/ScanScreen.kt', 'w') as f:
    f.write(content)
