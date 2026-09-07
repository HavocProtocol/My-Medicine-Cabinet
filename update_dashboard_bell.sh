sed -i '/val expired by viewModel.expiredItems.collectAsState()/a \    val unreadAlerts by viewModel.unreadAlerts.collectAsState()' app/src/main/java/com/example/ui/screens/DashboardScreen.kt

sed -i 's/onVoice: () -> Unit,/onVoice: () -> Unit,\n    onNotifications: () -> Unit,/' app/src/main/java/com/example/ui/screens/DashboardScreen.kt

cat << 'INNER_EOF' > patch_bell.patch
--- app/src/main/java/com/example/ui/screens/DashboardScreen.kt
+++ app/src/main/java/com/example/ui/screens/DashboardScreen.kt
@@ -48,19 +48,27 @@
             .verticalScroll(rememberScrollState())
             .padding(16.dp)
     ) {
-        Row(
-            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
-            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
-            horizontalArrangement = Arrangement.Center
-        ) {
-            Image(
-                painter = painterResource(id = R.drawable.custom_logo),
-                contentDescription = "App Logo",
-                modifier = Modifier.size(48.dp)
-            )
-            Spacer(modifier = Modifier.width(12.dp))
-            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold))
-        }
+        Box(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
+            Row(
+                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
+                modifier = Modifier.align(androidx.compose.ui.Alignment.Center)
+            ) {
+                Image(
+                    painter = painterResource(id = R.drawable.custom_logo),
+                    contentDescription = "App Logo",
+                    modifier = Modifier.size(48.dp)
+                )
+                Spacer(modifier = Modifier.width(12.dp))
+                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold))
+            }
+            Box(modifier = Modifier.align(androidx.compose.ui.Alignment.CenterEnd)) {
+                IconButton(onClick = onNotifications) {
+                    Icon(Icons.Outlined.Notifications, contentDescription = "Notifications")
+                }
+                if (unreadAlerts.isNotEmpty()) {
+                    Badge(modifier = Modifier.align(androidx.compose.ui.Alignment.TopEnd).padding(4.dp)) { Text(unreadAlerts.size.toString()) }
+                }
+            }
+        }
 
         OutlinedTextField(
             value = searchQuery,
INNER_EOF
patch app/src/main/java/com/example/ui/screens/DashboardScreen.kt patch_bell.patch
