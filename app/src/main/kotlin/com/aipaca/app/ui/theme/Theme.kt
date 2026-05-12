package com.aipaca.app.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * AIpaca Editorial Retro — main theme entry point.
 *
 * Dark-first (Editorial direction); light mode comes in a later pass.
 */

private val AlpacaDarkScheme = darkColorScheme(
    // --- Primary (terracotta accent) ----------------------------------------
    primary            = AlpacaColors.Accent.Primary,
    onPrimary          = AlpacaColors.Text.OnAccent,
    primaryContainer   = AlpacaColors.Accent.Soft,
    onPrimaryContainer = AlpacaColors.Accent.Primary,

    // --- Secondary (kept neutral — Editorial uses one accent) ---------------
    secondary            = AlpacaColors.Text.Primary,
    onSecondary          = AlpacaColors.Surface.Canvas,
    secondaryContainer   = AlpacaColors.Surface.Elevated,
    onSecondaryContainer = AlpacaColors.Text.Body,

    // --- Tertiary (semantic info) -------------------------------------------
    tertiary            = AlpacaColors.State.Info,
    onTertiary          = AlpacaColors.Surface.Canvas,
    tertiaryContainer   = AlpacaColors.Surface.Elevated,
    onTertiaryContainer = AlpacaColors.State.Info,

    // --- Backgrounds & surfaces --------------------------------------------
    background        = AlpacaColors.Surface.Canvas,
    onBackground      = AlpacaColors.Text.Primary,

    surface           = AlpacaColors.Surface.Canvas,
    onSurface         = AlpacaColors.Text.Primary,
    surfaceVariant    = AlpacaColors.Surface.Elevated,
    onSurfaceVariant  = AlpacaColors.Text.Muted,
    surfaceContainer  = AlpacaColors.Surface.Elevated,
    surfaceContainerHigh    = AlpacaColors.Surface.Card,
    surfaceContainerHighest = AlpacaColors.Surface.Card,
    surfaceContainerLow     = AlpacaColors.Surface.Canvas,
    surfaceContainerLowest  = AlpacaColors.Surface.Recess,

    // --- Lines & outlines ---------------------------------------------------
    outline         = AlpacaColors.Line.Hairline,
    outlineVariant  = AlpacaColors.Line.Subtle,

    // --- Errors -------------------------------------------------------------
    error              = AlpacaColors.State.Error,
    onError            = AlpacaColors.Surface.Canvas,
    errorContainer     = AlpacaColors.Surface.Elevated,
    onErrorContainer   = AlpacaColors.State.Error,

    // --- Inverse + scrim ----------------------------------------------------
    inverseSurface     = AlpacaColors.Text.Primary,
    inverseOnSurface   = AlpacaColors.Surface.Canvas,
    inversePrimary     = AlpacaColors.Accent.Muted,
    scrim              = AlpacaColors.Surface.Canvas
)

@Composable
fun AIpacaTheme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            @Suppress("DEPRECATION")
            window.statusBarColor     = AlpacaColors.Surface.Canvas.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = AlpacaColors.Surface.Canvas.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars     = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = AlpacaDarkScheme,
        typography  = Typography,
        content     = content
    )
}
