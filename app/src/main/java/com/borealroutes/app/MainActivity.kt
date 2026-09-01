package com.borealroutes.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel

private const val PREFS_UI = "boreal_ui"
private const val PREF_THEME = "theme_mode"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val prefs = remember { context.getSharedPreferences(PREFS_UI, MODE_PRIVATE) }
            var themeMode by remember {
                mutableStateOf(AppThemeMode.fromStorage(prefs.getString(PREF_THEME, AppThemeMode.SYSTEM.storageValue)))
            }
            val systemDark = isSystemInDarkTheme()
            val effectiveDark = when (themeMode) {
                AppThemeMode.SYSTEM -> systemDark
                AppThemeMode.LIGHT -> false
                AppThemeMode.DARK -> true
            }

            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !effectiveDark
                    isAppearanceLightNavigationBars = !effectiveDark
                }
            }

            BorealTheme(themeMode = themeMode) {
                val vm: BorealViewModel = viewModel()
                BorealApp(
                    vm = vm,
                    themeMode = themeMode,
                    onThemeModeChange = { mode ->
                        themeMode = mode
                        prefs.edit().putString(PREF_THEME, mode.storageValue).apply()
                    }
                )
            }
        }
    }
}
