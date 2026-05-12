package com.aipaca.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * AIpaca Editorial Retro — color tokens.
 *
 * Single source of truth for all colors used in the UI. Hex values match
 * `design/figma/tokens.json` and `design/02_design_system_spec.md`.
 *
 * Contrast: every text token on `Surface.Canvas` passes WCAG AA (≥4.5:1)
 * unless marked as "UI only" — those pass 3:1 and must not be used for
 * body text.
 *
 * Replaces the legacy `RetroCliColors` object.
 */
object AlpacaColors {

    // ---- Surfaces ----------------------------------------------------------
    object Surface {
        /** App background — warm near-black with slight olive. */
        val Canvas   = Color(0xFF14140F)

        /** Sub-section, slightly elevated. */
        val Elevated = Color(0xFF1A1913)

        /** Card background — used sparingly; prefer hairlines + canvas. */
        val Card     = Color(0xFF1E1D17)

        /** Recessed (input fields, codeblocks). */
        val Recess   = Color(0xFF11100B)

        /** Modal/dialog overlay (use with alpha .92f). */
        val Overlay  = Color(0xFF14140F)
    }

    // ---- Text --------------------------------------------------------------
    object Text {
        /** Headlines, primary labels — 15.6:1 on Canvas. */
        val Primary  = Color(0xFFF1ECD8)

        /** Body, message text — 14.3:1 on Canvas. */
        val Body     = Color(0xFFE8E2D0)

        /** Footnotes, mono microlabels — 5.8:1 on Canvas (WCAG AA). */
        val Muted    = Color(0xFF9A9078)

        /** Placeholders, disabled — 3.3:1 (UI only — never body text). */
        val Subtle   = Color(0xFF6E6750)

        /** Text on accent surfaces — 6.2:1 vs Accent.Primary. */
        val OnAccent = Color(0xFF14140F)
    }

    // ---- Accent ------------------------------------------------------------
    object Accent {
        /** Terracotta — the single hero accent. 6.2:1 on Canvas. */
        val Primary = Color(0xFFE07B3A)

        /** Hover/pressed lighter variant. 7.5:1 on Canvas. */
        val Hover   = Color(0xFFEA8E4F)

        /** Chip background — use only as Container fill behind Accent.Primary text. */
        val Soft    = Color(0xFF3A2418)

        /** Secondary accent — for under-emphasized links. 4.7:1 on Canvas (AA). */
        val Muted   = Color(0xFFAC734B)
    }

    // ---- Semantic states ---------------------------------------------------
    object State {
        /** Tested, online, success. 6.8:1 on Canvas. */
        val Success = Color(0xFF7FA86D)

        /** Experimental, caution. 7.7:1 on Canvas. */
        val Warning = Color(0xFFCFA050)

        /** Destructive, errors. 4.6:1 on Canvas. */
        val Error   = Color(0xFFCE5C50)

        /** Neutral info chip. 8.6:1 on Canvas. */
        val Info    = Color(0xFF9AB4C8)
    }

    // ---- Lines & borders ---------------------------------------------------
    // Deliberately low-contrast — these are decorative typographic hairlines,
    // not UI boundaries. WCAG 2.1 1.4.11 doesn't require contrast for pure
    // decoration. If you need an *interactive* boundary (e.g. focused input
    // border), use Accent.Primary instead.
    object Line {
        /** Standard 1dp divider. */
        val Hairline = Color(0xFF3A3528)

        /** Section divider, under headlines. */
        val Strong   = Color(0xFF4D4636)

        /** Very subtle list separator. */
        val Subtle   = Color(0xFF26241C)
    }
}
