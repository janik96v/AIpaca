package com.aipaca.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * AIpaca Editorial Retro — type system.
 *
 * Two font families:
 *   • [InterFamily]  — sans, 90% of UI text
 *   • [MonoFamily]   — mono, only for microlabels, code, IDs, metrics
 *
 * ## Font upgrade path (currently using system fallback)
 *
 * Inter and JetBrains Mono are the *target* fonts. They are currently mapped
 * to the system defaults (Roboto / Roboto Mono) which look ~90% identical for
 * UI purposes because the Editorial direction's style is driven by the
 * Type Scale + Hierarchy + Tracking, not glyph shapes.
 *
 * To swap in the real Inter and JetBrains Mono later, pick ONE of:
 *
 * **Option A — bundle as resources (offline, deterministic):**
 *   1. Download Inter from https://rsms.me/inter/  (use the static .ttf set)
 *   2. Download JetBrains Mono from https://www.jetbrains.com/lp/mono/
 *   3. Drop these into `app/src/main/res/font/`:
 *        inter_regular.ttf, inter_medium.ttf, inter_semibold.ttf,
 *        inter_bold.ttf, inter_extrabold.ttf,
 *        jetbrains_mono_regular.ttf, jetbrains_mono_medium.ttf,
 *        jetbrains_mono_bold.ttf
 *   4. Replace [InterFamily] / [MonoFamily] declarations below with
 *      `FontFamily(Font(R.font.inter_regular, FontWeight.Normal), …)`.
 *   Cost: ~600 KB APK growth. Both fonts are SIL OFL → Apache compatible.
 *
 * **Option B — GoogleFont provider (online, lazy):**
 *   1. Add `androidx.compose.ui:ui-text-google-fonts` to dependencies.
 *   2. Set up Provider with `com.google.android.gms.fonts` authority.
 *   3. Use `GoogleFont("Inter")` and `GoogleFont("JetBrains Mono")`.
 *   Cost: 0 APK, but requires Play Services and one-time network fetch.
 *
 * Neither is required for the Editorial style to look correct. Roboto is
 * a well-engineered Inter-adjacent typeface; the visual identity comes
 * from `AlpacaType.DisplayMasthead` size + weight + tracking, not the
 * specific letterforms.
 */

val InterFamily: FontFamily = FontFamily.Default      // → Roboto on Android
val MonoFamily:  FontFamily = FontFamily.Monospace    // → Roboto Mono on Android

// ---- Type tokens ------------------------------------------------------------
// Mirrors design/02_design_system_spec.md §3 and figma/tokens.json.

object AlpacaType {

    // Display ----------------------------------------------------------------

    /** Screen titles: "Alpaca." / "Models." / "Server." */
    val DisplayMasthead = TextStyle(
        fontFamily    = InterFamily,
        fontWeight    = FontWeight.ExtraBold,
        fontSize      = 36.sp,
        lineHeight    = 40.sp,
        letterSpacing = (-1.5).sp
    )

    /** Hero number / status display. */
    val DisplayHeadline = TextStyle(
        fontFamily    = InterFamily,
        fontWeight    = FontWeight.Bold,
        fontSize      = 28.sp,
        lineHeight    = 32.sp,
        letterSpacing = (-0.8).sp
    )

    // Title ------------------------------------------------------------------

    /** Entry title (model name). */
    val TitleLg = TextStyle(
        fontFamily    = InterFamily,
        fontWeight    = FontWeight.Bold,
        fontSize      = 20.sp,
        lineHeight    = 26.sp,
        letterSpacing = (-0.3).sp
    )

    /** Dialog title, section header. */
    val TitleMd = TextStyle(
        fontFamily    = InterFamily,
        fontWeight    = FontWeight.SemiBold,
        fontSize      = 17.sp,
        lineHeight    = 24.sp,
        letterSpacing = (-0.2).sp
    )

    // Body -------------------------------------------------------------------

    /** Message text. */
    val BodyLg = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Normal,
        fontSize   = 16.sp,
        lineHeight = 24.sp
    )

    val BodyMd = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Normal,
        fontSize   = 14.sp,
        lineHeight = 22.sp
    )

    /** Captions, footnotes. */
    val BodySm = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Normal,
        fontSize   = 13.sp,
        lineHeight = 20.sp
    )

    // Label ------------------------------------------------------------------

    /** Buttons, tabs. */
    val LabelLg = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize   = 13.sp,
        lineHeight = 18.sp
    )

    /** Chip labels. */
    val LabelMd = TextStyle(
        fontFamily    = InterFamily,
        fontWeight    = FontWeight.SemiBold,
        fontSize      = 11.sp,
        lineHeight    = 14.sp,
        letterSpacing = 0.4.sp
    )

    // Mono (microlabels & code) ----------------------------------------------

    /** Microlabel — `№ 01 · TESTED`, `YOU · 09:41`. Heavy tracking. */
    val MonoLabel = TextStyle(
        fontFamily    = MonoFamily,
        fontWeight    = FontWeight.Bold,
        fontSize      = 10.sp,
        lineHeight    = 14.sp,
        letterSpacing = 1.8.sp
    )

    /** Code block, multi-line code samples. */
    val MonoBody = TextStyle(
        fontFamily    = MonoFamily,
        fontWeight    = FontWeight.Normal,
        fontSize      = 12.sp,
        lineHeight    = 18.sp,
        letterSpacing = 0.4.sp
    )

    /** Metrics: `14.2 t/s · 1.8s · 412 tok`. Medium weight for emphasis. */
    val MonoMetric = TextStyle(
        fontFamily    = MonoFamily,
        fontWeight    = FontWeight.Medium,
        fontSize      = 11.sp,
        lineHeight    = 14.sp,
        letterSpacing = 0.6.sp
    )
}

// ---- M3 Typography mapping --------------------------------------------------
// Material 3 components (Button, Text, etc.) read from this. We map our tokens
// onto M3 slots so default components inherit the right type.

val Typography = Typography(
    displayLarge   = AlpacaType.DisplayMasthead,
    displayMedium  = AlpacaType.DisplayHeadline,
    displaySmall   = AlpacaType.DisplayHeadline.copy(fontSize = 22.sp, lineHeight = 28.sp),

    headlineLarge  = AlpacaType.DisplayHeadline,
    headlineMedium = AlpacaType.TitleLg,
    headlineSmall  = AlpacaType.TitleMd,

    titleLarge     = AlpacaType.TitleLg,
    titleMedium    = AlpacaType.TitleMd,
    titleSmall     = AlpacaType.LabelLg,

    bodyLarge      = AlpacaType.BodyLg,
    bodyMedium     = AlpacaType.BodyMd,
    bodySmall      = AlpacaType.BodySm,

    labelLarge     = AlpacaType.LabelLg,
    labelMedium    = AlpacaType.LabelMd,
    labelSmall     = AlpacaType.MonoLabel  // M3 labelSmall = our mono microlabel
)
