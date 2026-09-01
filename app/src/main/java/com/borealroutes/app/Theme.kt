package com.borealroutes.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class AppThemeMode(val storageValue: String, val label: String) {
    SYSTEM("system", "System default"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark");

    companion object {
        fun fromStorage(value: String?): AppThemeMode =
            entries.firstOrNull { it.storageValue == value } ?: SYSTEM
    }
}

val BorealPurple = Color(0xFF6750D8)
val BorealPurpleSoft = Color(0xFFAFA2FF)

private val BorealDarkColors = darkColorScheme(
    primary = BorealPurpleSoft,
    onPrimary = Color(0xFF1D173B),
    primaryContainer = Color(0xFF4938A5),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFFB9B0FF),
    background = Color(0xFF0D0D12),
    onBackground = Color(0xFFF4F1F7),
    surface = Color(0xFF17161D),
    onSurface = Color(0xFFF4F1F7),
    surfaceVariant = Color(0xFF211F27),
    onSurfaceVariant = Color(0xFFD0CAD5),
    outline = Color(0xFF34313D),
    error = Color(0xFFFFB4AB)
)

private val BorealLightColors = lightColorScheme(
    primary = BorealPurple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7DEFF),
    onPrimaryContainer = Color(0xFF20105F),
    secondary = Color(0xFF65558F),
    background = Color(0xFFF9F7FC),
    onBackground = Color(0xFF1B1B20),
    surface = Color(0xFFFFFBFF),
    onSurface = Color(0xFF1B1B20),
    surfaceVariant = Color(0xFFE9E5EE),
    onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFF79747E),
    error = Color(0xFFBA1A1A)
)

@Composable
fun BorealTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (darkTheme) BorealDarkColors else BorealLightColors,
        content = content
    )
}
