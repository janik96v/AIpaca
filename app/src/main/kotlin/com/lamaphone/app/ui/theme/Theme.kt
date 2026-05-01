package com.lamaphone.app.ui.theme

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

// ---- Palette ----------------------------------------------------------------
// Deep purple / teal tech aesthetic

private val PurplePrimary   = Color(0xFF7C3AED)   // violet-600
private val PurpleOnPrimary = Color(0xFFFFFFFF)
private val PurpleContainer = Color(0xFFEDE9FE)   // violet-100
private val PurpleOnContainer = Color(0xFF3B0764)

private val TealSecondary   = Color(0xFF0D9488)   // teal-600
private val TealOnSecondary = Color(0xFFFFFFFF)
private val TealContainer   = Color(0xFFCCFBF1)   // teal-100
private val TealOnContainer = Color(0xFF042F2E)

private val SurfaceLight    = Color(0xFFF8F7FF)
private val BackgroundLight = Color(0xFFFAF9FF)

private val PurplePrimaryDark      = Color(0xFF7B61FF)   // spec: vibrant violet
private val PurpleOnPrimaryDark    = Color(0xFFFFFFFF)
private val PurpleContainerDark    = Color(0xFF4A3580)   // muted container
private val PurpleOnContainerDark  = Color(0xFFE8E0FF)

private val TealSecondaryDark      = Color(0xFF2DD4BF)   // teal-400
private val TealOnSecondaryDark    = Color(0xFF042F2E)
private val TealContainerDark      = Color(0xFF0F766E)   // teal-700
private val TealOnContainerDark    = Color(0xFFCCFBF1)

private val SurfaceDark    = Color(0xFF1A1A2E)   // spec: dark navy
private val BackgroundDark = Color(0xFF0F0F1A)   // spec: near-black

// ---- Schemes ----------------------------------------------------------------

private val LightColors = lightColorScheme(
    primary          = PurplePrimary,
    onPrimary        = PurpleOnPrimary,
    primaryContainer = PurpleContainer,
    onPrimaryContainer = PurpleOnContainer,
    secondary        = TealSecondary,
    onSecondary      = TealOnSecondary,
    secondaryContainer = TealContainer,
    onSecondaryContainer = TealOnContainer,
    surface          = SurfaceLight,
    background       = BackgroundLight,
    error            = Color(0xFFB91C1C),
    onError          = Color(0xFFFFFFFF)
)

private val DarkColors = darkColorScheme(
    primary               = PurplePrimaryDark,
    onPrimary             = PurpleOnPrimaryDark,
    primaryContainer      = PurpleContainerDark,
    onPrimaryContainer    = PurpleOnContainerDark,
    secondary             = TealSecondaryDark,
    onSecondary           = TealOnSecondaryDark,
    secondaryContainer    = TealContainerDark,
    onSecondaryContainer  = TealOnContainerDark,
    surface               = SurfaceDark,
    onSurface             = Color(0xFFE6E1E5),
    surfaceVariant        = Color(0xFF232340),   // slightly lighter than surface
    onSurfaceVariant      = Color(0xFFCAC4D0),
    background            = BackgroundDark,
    onBackground          = Color(0xFFE6E1E5),
    outline               = Color(0xFF49454F),
    outlineVariant        = Color(0xFF49454F),
    error                 = Color(0xFFF87171),
    onError               = Color(0xFF450A0A)
)

// ---- Theme ------------------------------------------------------------------

@Composable
fun LamaPhoneTheme(
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
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = Typography,
        content     = content
    )
}
