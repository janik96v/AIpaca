package ch.firn.app.ui.theme

/**
 * Product naming used in UI copy.
 *
 * In running text the name is lowercase — firn — even at the start of a
 * sentence. In chrome (tracked uppercase) it is FIRN.
 */
object Brand {
    /** Running-text name, as used in body copy. */
    const val NAME = "firn"

    /** Tracked-uppercase name, as used in chrome (e.g. the agent's message label). */
    val LABEL: String get() = NAME.uppercase()
}
