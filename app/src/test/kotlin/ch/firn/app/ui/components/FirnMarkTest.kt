package ch.firn.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class FirnMarkTest {

    private val keys = floatArrayOf(1f, 1f, .3f, .3f, 1f, .3f, 1f)

    @Test
    fun `hits every keyframe exactly`() {
        val times = floatArrayOf(0f, .17f, .33f, .5f, .67f, .83f, 1f)
        times.forEachIndexed { i, t -> assertEquals(keys[i], thinkAlpha(keys, t), 1e-6f) }
    }

    @Test
    fun `interpolates linearly between keyframes`() {
        // Halfway from .17 (1) to .33 (.3).
        assertEquals(.65f, thinkAlpha(keys, .25f), 1e-4f)
    }

    @Test
    fun `loop closes where it opened`() {
        assertEquals(thinkAlpha(keys, 0f), thinkAlpha(keys, 1f), 1e-6f)
    }
}
