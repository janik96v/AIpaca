package com.aipaca.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.aipaca.app.ui.components.PressFill

/**
 * Instrument — theme entry point.
 *
 * Minimal-futurist register: true black, white hairlines, tracked uppercase
 * chrome. Dark only; the design has no light variant.
 */

private val InkScheme = darkColorScheme(
    primary              = Ink.White,
    onPrimary            = Ink.Black,
    primaryContainer     = Ink.Sheet,
    onPrimaryContainer   = Ink.Text,

    secondary            = Ink.Text,
    onSecondary          = Ink.Black,
    secondaryContainer   = Ink.Sheet,
    onSecondaryContainer = Ink.Text,

    tertiary             = Ink.Meta,
    onTertiary           = Ink.Black,
    tertiaryContainer    = Ink.Sheet,
    onTertiaryContainer  = Ink.Text,

    background           = Ink.Black,
    onBackground         = Ink.Text,

    surface                 = Ink.Black,
    onSurface               = Ink.Text,
    surfaceVariant          = Ink.Sheet,
    onSurfaceVariant        = Ink.Meta,
    surfaceTint             = Ink.Black,
    surfaceContainer        = Ink.Sheet,
    surfaceContainerHigh    = Ink.Sheet,
    surfaceContainerHighest = Ink.Sheet,
    surfaceContainerLow     = Ink.Black,
    surfaceContainerLowest  = Ink.Black,

    outline              = Ink.Border,
    outlineVariant       = Ink.Divider,

    // No colour for errors: severity is carried by copy, not hue.
    error                = Ink.White,
    onError              = Ink.Black,
    errorContainer       = Ink.Sheet,
    onErrorContainer     = Ink.Text,

    inverseSurface       = Ink.Text,
    inverseOnSurface     = Ink.Black,
    inversePrimary       = Ink.Meta,
    scrim                = Ink.Scrim
)

/** Zero corner radius everywhere. */
private val SquareShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small      = RoundedCornerShape(0.dp),
    medium     = RoundedCornerShape(0.dp),
    large      = RoundedCornerShape(0.dp),
    extraLarge = RoundedCornerShape(0.dp)
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
            window.statusBarColor     = Ink.Black.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = Ink.Black.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars     = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = InkScheme,
        typography  = Typography,
        shapes      = SquareShapes
    ) {
        // Inside MaterialTheme, which installs a ripple of its own.
        CompositionLocalProvider(LocalIndication provides PressFill, content = content)
    }
}
