package com.aipaca.app.ui.theme

/**
 * Product naming used in UI copy.
 *
 * The Instrument redesign ships before the rebrand, so the name lives in one
 * place: renaming the app is a change to these two constants, not a hunt
 * through every screen.
 */
object Brand {
    /** Sentence-case name, as used in body copy. */
    const val NAME = "AIpaca"

    /** Tracked-uppercase name, as used in chrome (e.g. the agent's message label). */
    val LABEL: String get() = NAME.uppercase()
}
