package com.aipaca.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// ---- Schemes ----------------------------------------------------------------

private val LightColors = lightColorScheme(
    primary = RetroCliColors.Cyan,
    onPrimary = RetroCliColors.Void,
    primaryContainer = Color(0xFF071A3A),
    onPrimaryContainer = RetroCliColors.Text,
    secondary = RetroCliColors.Magenta,
    onSecondary = RetroCliColors.Void,
    secondaryContainer = Color(0xFF321044),
    onSecondaryContainer = RetroCliColors.Text,
    tertiary = RetroCliColors.Blue,
    surface = RetroCliColors.Terminal,
    onSurface = RetroCliColors.Text,
    surfaceVariant = RetroCliColors.TerminalSoft,
    onSurfaceVariant = RetroCliColors.Muted,
    background = RetroCliColors.Void,
    onBackground = RetroCliColors.Text,
    outline = RetroCliColors.Purple,
    outlineVariant = RetroCliColors.Cyan.copy(alpha = 0.35f),
    error = RetroCliColors.Error,
    onError = RetroCliColors.Void,
    errorContainer = Color(0xFF3A102D),
    onErrorContainer = RetroCliColors.Text
)

private val DarkColors = darkColorScheme(
    primary = RetroCliColors.Cyan,
    onPrimary = RetroCliColors.Void,
    primaryContainer = Color(0xFF071A3A),
    onPrimaryContainer = RetroCliColors.Text,
    secondary = RetroCliColors.Magenta,
    onSecondary = RetroCliColors.Void,
    secondaryContainer = Color(0xFF321044),
    onSecondaryContainer = RetroCliColors.Text,
    tertiary = RetroCliColors.Blue,
    onTertiary = RetroCliColors.Void,
    surface = RetroCliColors.Terminal,
    onSurface = RetroCliColors.Text,
    surfaceVariant = RetroCliColors.TerminalSoft,
    onSurfaceVariant = RetroCliColors.Muted,
    background = RetroCliColors.Void,
    onBackground = RetroCliColors.Text,
    outline = RetroCliColors.Purple,
    outlineVariant = RetroCliColors.Cyan.copy(alpha = 0.35f),
    error = RetroCliColors.Error,
    onError = RetroCliColors.Void,
    errorContainer = Color(0xFF3A102D),
    onErrorContainer = RetroCliColors.Text
)

// ---- Theme ------------------------------------------------------------------

@Composable
fun AIpacaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+; disabled by default so our
    // purple/teal palette is always shown.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else      -> LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            @Suppress("DEPRECATION")
            window.statusBarColor = RetroCliColors.Void.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = RetroCliColors.Void.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = Typography,
        content     = content
    )
}
