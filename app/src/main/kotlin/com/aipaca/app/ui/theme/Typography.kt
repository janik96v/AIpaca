package com.aipaca.app.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.aipaca.app.R

/**
 * Instrument — type system.
 *
 * Inter only, in two weights: 300 for titles, 400 for everything else. The
 * static instances are bundled in `res/font` (SIL OFL 1.1, see NOTICE).
 *
 * Tracked uppercase is for chrome only — status, labels, actions. Anything the
 * user actually reads (messages, memory, notes) stays sentence case.
 *
 * Tracking and line height are expressed in `em`, exactly as in the design
 * reference, and every style centres its glyphs in the full line box with no
 * trimming — the CSS box model the reference was built in. That keeps the
 * reference's spacing values usable 1:1 as dp.
 */
val Inter: FontFamily = FontFamily(
    Font(R.font.inter_light,   FontWeight.Light),
    Font(R.font.inter_regular, FontWeight.Normal)
)

private val CssLineBox = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim      = LineHeightStyle.Trim.None
)

private fun inter(
    size: TextUnit,
    lineHeight: Float,
    tracking: Float = 0f,
    weight: FontWeight = FontWeight.Normal
) = TextStyle(
    fontFamily      = Inter,
    fontWeight      = weight,
    fontSize        = size,
    lineHeight      = lineHeight.em,
    letterSpacing   = tracking.em,
    lineHeightStyle = CssLineBox
)

object InkType {

    // ---- Titles (weight 300) -----------------------------------------------
    /** `MEMORY`, `MODELS`, `SERVER`. */
    val ScreenTitle   = inter(16.sp, 1.6f, .42f, FontWeight.Light)

    /** `AVAILABLE / MODELS` — the two-line browse title. */
    val BrowseTitle   = inter(16.sp, 1.8f, .40f, FontWeight.Light)

    /** `LOAD A MODEL`, `ASK ME ANYTHING`. */
    val EmptyTitle    = inter(15.sp, 1.9f, .36f, FontWeight.Light)

    /** `HISTORY`, dialog titles. */
    val SheetTitle    = inter(12.sp, 1.4f, .34f, FontWeight.Light)

    // ---- Reading text (sentence case) --------------------------------------
    /** Empty-state copy, server blurb. */
    val Body          = inter(13.sp, 1.7f)

    /** Messages and memory entries. */
    val BodyLoose     = inter(13.sp, 1.75f)

    /** Row names: installed models, catalog entries, paired devices. */
    val Name          = inter(13.sp, 1.3f)

    /** History rows. */
    val NameLoose     = inter(13.sp, 1.4f)

    /** Composer input. */
    val Input         = inter(13.sp, 1.6f)

    /** Tab hints, catalog notes. */
    val Secondary     = inter(12.sp, 1.7f)

    /** Learning meta line. */
    val SecondaryTight = inter(12.sp, 1.6f)

    // ---- Chrome (always uppercase) -----------------------------------------
    /** 10sp chrome at the given tracking — labels, actions, tabs. */
    fun chrome(tracking: Float, lineHeight: Float = 1f) = inter(10.sp, lineHeight, tracking)

    val RailLabel     = chrome(.18f)
    val Action        = chrome(.20f)
    val Button        = chrome(.22f)
    val Status        = chrome(.24f, 1.4f)
    val Label         = chrome(.24f)
    val Count         = chrome(.26f)
    val Stamp         = chrome(.28f)

    /** Row meta under a name: `Q4_K_M · 2.7 GB · 128K CTX`. */
    val Meta          = chrome(.24f, 1.6f)

    /** Server URL / `OFFLINE`. */
    val Url           = inter(13.sp, 1.6f, .16f)

    // ---- Micro (9sp) -------------------------------------------------------
    /** Header readout label: `BLOCKS`. */
    val ReadoutLabel  = inter(9.sp, 1f, .20f)

    /** Header readout value: `36`, `Q4_K_M`. */
    val ReadoutValue  = inter(11.sp, 1.1f, .08f)

    /** `NO HEADER READ · IDLE`. */
    val MicroLine     = inter(9.sp, 1.8f, .24f)

    /** Composer placeholder: `MESSAGE`, `LOAD A MODEL FIRST`. */
    val Placeholder   = inter(13.sp, 1.6f, .22f)
}
