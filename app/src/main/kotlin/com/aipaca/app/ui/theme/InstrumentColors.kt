package com.aipaca.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Instrument — colour tokens.
 *
 * Only white on black: every level of hierarchy is white at a given alpha.
 * The named steps below are the ones the design uses; reach for [Ink.white]
 * only for a one-off that has no name yet.
 *
 * Contrast: every text step on [Ink.Black] passes WCAG AA — the placeholder
 * step (.62) is the lowest used for text at ≈ 6:1. Steps at .55 and below are
 * for icons, borders and fills only.
 */
object Ink {

    // ---- Surfaces -----------------------------------------------------------
    /** App background — true black. */
    val Black = Color(0xFF000000)

    /** Bottom sheets and dialogs. */
    val Sheet = Color(0xFF050505)

    // ---- Foreground ---------------------------------------------------------
    /** Primary text. */
    val Text = Color(0xFFFAFAFA)

    /** Accent / active: selected rail item, primary borders, enabled icons. */
    val White = Color(0xFFFFFFFF)

    fun white(alpha: Float): Color = White.copy(alpha = alpha)

    val AgentBody    = white(.92f)
    val MemoryText   = white(.90f)
    val Chip         = white(.90f)
    val Filter       = white(.85f)
    val Status       = white(.80f)
    val Meta         = white(.78f)
    val Body         = white(.76f)
    val RailInactive = white(.75f)
    val Secondary    = white(.74f)
    val Micro        = white(.72f)
    val Placeholder  = white(.62f)

    // ---- Icons, lines and fills (not for text) ----------------------------
    val DisabledIcon = white(.55f)
    val AgentRule    = white(.55f)
    val TertiaryIcon = white(.45f)
    val Border       = white(.20f)
    val Track        = white(.16f)
    val Hairline     = white(.12f)
    val Divider      = white(.08f)
    val PressedFill  = white(.06f)
    val HoverFill    = white(.04f)

    /** Scrim behind the history sheet. */
    val Scrim = Black.copy(alpha = .76f)
}
