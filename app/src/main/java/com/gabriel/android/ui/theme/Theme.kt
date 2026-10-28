package com.gabriel.android.ui.theme

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsetsController
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView

// GABRIEL Material 3 Color Schemes
// Palette: Teal #0F6E7C, Dark bg #0E1416, Light bg #F7F8F8, SOS Red #C62828, Amber #E0A100, Green #2E7D32
val DarkGabrielColorScheme = darkColorScheme(
    primary = Color(0xFF0F6E7C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1E3C42),
    onPrimaryContainer = Color(0xFFAEE4EC),
    secondary = Color(0xFF2E7D32),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF1A381D),
    onSecondaryContainer = Color(0xFFB5E4B9),
    tertiary = Color(0xFFE0A100),
    onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF3F2F00),
    onTertiaryContainer = Color(0xFFFFE082),
    background = Color(0xFF0E1416),
    onBackground = Color(0xFFE3E8E9),
    surface = Color(0xFF131B1E),
    onSurface = Color(0xFFE3E8E9),
    surfaceVariant = Color(0xFF1C272A),
    onSurfaceVariant = Color(0xFFA0ADB0),
    outline = Color(0xFF384649),
    outlineVariant = Color(0xFF253134),
    error = Color(0xFFC62828), // SOS Red (SOS only)
    onError = Color.White
)

val LightGabrielColorScheme = lightColorScheme(
    primary = Color(0xFF0F6E7C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD4EFF3),
    onPrimaryContainer = Color(0xFF06333A),
    secondary = Color(0xFF2E7D32),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3EED6),
    onSecondaryContainer = Color(0xFF0E3812),
    tertiary = Color(0xFFE0A100),
    onTertiary = Color.Black,
    tertiaryContainer = Color(0xFFFFF0C2),
    onTertiaryContainer = Color(0xFF4A3700),
    background = Color(0xFFF7F8F8),
    onBackground = Color(0xFF141A1C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF141A1C),
    surfaceVariant = Color(0xFFE9EDEE),
    onSurfaceVariant = Color(0xFF4C575A),
    outline = Color(0xFFBCC6C9),
    outlineVariant = Color(0xFFDCE2E4),
    error = Color(0xFFC62828),
    onError = Color.White
)

val HighContrastGabrielColorScheme = darkColorScheme(
    primary = Color(0xFF00E5FF),
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF005662),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFF00E676),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF005024),
    onSecondaryContainer = Color.White,
    tertiary = Color(0xFFFFD600),
    onTertiary = Color.Black,
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF1A1A1A),
    onSurfaceVariant = Color(0xFFCCCCCC),
    outline = Color(0xFFFFFFFF),
    outlineVariant = Color(0xFF888888),
    error = Color(0xFFFF1744),
    onError = Color.Black
)


@Composable
fun GabrielTheme(
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit
) {
    // App-level override from ThemePreferenceManager
    val themePref by ThemePreferenceManager.themeFlow.collectAsState(initial = ThemePreference.Dark)
    val colorScheme = when {
        themePref.isHighContrast -> HighContrastGabrielColorScheme
        darkTheme == true -> DarkGabrielColorScheme
        darkTheme == false -> LightGabrielColorScheme
        themePref.isDark -> DarkGabrielColorScheme
        themePref.isLight -> LightGabrielColorScheme
        else -> if (isSystemInDarkTheme()) DarkGabrielColorScheme else LightGabrielColorScheme
    }
    val shouldUseDark = colorScheme != LightGabrielColorScheme
    val palette = if (shouldUseDark) DarkGabrielPalette else LightGabrielPalette

    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.setSystemBarsAppearance(
                    if (!shouldUseDark) WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else 0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                )
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = if (!shouldUseDark) {
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                } else 0
            }
            window.navigationBarColor = colorScheme.background.toArgb()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
        }
    }

    CompositionLocalProvider(LocalGabrielPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
