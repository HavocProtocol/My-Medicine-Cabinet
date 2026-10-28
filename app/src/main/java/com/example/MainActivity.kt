package com.example

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.services.LocalLlmConfig
import com.example.ui.AppNavigation
import com.example.ui.theme.MyApplicationTheme

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val prefs = getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        val isOnboardingComplete = prefs.getBoolean("onboarding_complete", false)
        val isLangInit = prefs.getBoolean("lang_initialized", false)
        
        if (!isLangInit) {
            // Only apply default Light mode and English if the user hasn't completed onboarding yet (fresh install).
            // Users who already configured the app keep their existing theme and language untouched.
            val isFreshInstall = !isOnboardingComplete &&
                androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().isEmpty &&
                !LocalLlmConfig.hasThemeModeConfigured(this)

            prefs.edit().putBoolean("lang_initialized", true).apply()

            if (isFreshInstall) {
                LocalLlmConfig.setThemeMode(this, 1) // 1 = Light Mode
                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                    androidx.core.os.LocaleListCompat.forLanguageTags("en")
                )
            }
        }

        setContent {
            var themeMode by remember { mutableIntStateOf(LocalLlmConfig.getThemeMode(this)) }

            MyApplicationTheme(
                themeMode = themeMode,
                onThemeChanged = { newMode ->
                    themeMode = newMode
                    LocalLlmConfig.setThemeMode(this, newMode)
                }
            ) {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        AppNavigation()
                    }
                }
            }
        }
    }
}
