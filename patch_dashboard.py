import re

with open('app/src/main/java/com/example/ui/screens/DashboardScreen.kt', 'r') as f:
    content = f.read()

# Add imports if they don't exist
imports_to_add = """
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.outlined.LocalPharmacy
import androidx.compose.material.icons.filled.LocalPharmacy
"""
content = content.replace('import androidx.lifecycle.viewmodel.compose.viewModel', imports_to_add + 'import androidx.lifecycle.viewmodel.compose.viewModel')

# Add the button
button_code = """            QuickActionButton(
                label = stringResource(R.string.find_pharmacy),
                icon = Icons.Default.LocalPharmacy,
                bgColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                onClick = {
                    val gmmIntentUri = Uri.parse("geo:0,0?q=pharmacy")
                    val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
                    mapIntent.setPackage("com.google.android.apps.maps")
                    try {
                        context.startActivity(mapIntent)
                    } catch (e: Exception) {
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/pharmacy"))
                        context.startActivity(browserIntent)
                    }
                },
                modifier = Modifier.weight(1f)
            )
"""

# First, we need a context reference inside DashboardScreen
if 'val context = LocalContext.current' not in content:
    content = content.replace('    val expiredCount = expired.size', '    val expiredCount = expired.size\n    val context = LocalContext.current')

# Then we replace the spacer with the button
spacer_target = """            QuickActionButton(
                label = stringResource(R.string.tutorial_title),
                icon = Icons.AutoMirrored.Outlined.HelpOutline,
                bgColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                onClick = onTutorial,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.weight(1f))"""

button_replacement = """            QuickActionButton(
                label = stringResource(R.string.tutorial_title),
                icon = Icons.AutoMirrored.Outlined.HelpOutline,
                bgColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                onClick = onTutorial,
                modifier = Modifier.weight(1f)
            )
""" + button_code

content = content.replace(spacer_target, button_replacement)

with open('app/src/main/java/com/example/ui/screens/DashboardScreen.kt', 'w') as f:
    f.write(content)
