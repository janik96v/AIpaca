package com.aipaca.app.ui.components.world

import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders the world off-device through the same [WorldRenderer] the app uses,
 * for the eight catalog models of the design reference (yaw 0.9 rad, pitch
 * 0.42 rad) — the visual-regression counterpart of the handoff's
 * `12-model-shapes-overview.png`.
 *
 * Images land in `build/world-snapshots/`; the assertions only guard that
 * something sensible was drawn.
 */
class WorldRenderSnapshotTest {

    private val catalog = listOf(
        "qwen35-4b"    to WorldParams(36, 8, 2560, 131072, 4.5f),
        "gemma4-e2b"   to WorldParams(30, 4, 2048, 32768, 4f),
        "whisper-base" to WorldParams(12, 8, 512, 448, 16f, split = true),
        "llama33-8b"   to WorldParams(32, 8, 4096, 131072, 4.5f),
        "qwen3-coder"  to WorldParams(36, 8, 2048, 32768, 4.5f),
        "phi4-mini"    to WorldParams(32, 8, 3072, 16384, 4.5f),
        "moondream2"   to WorldParams(24, 32, 2048, 2048, 8f),
        "nomic-embed"  to WorldParams(12, 12, 768, 8192, 16f)
    )

    private class AwtCanvas(val g: java.awt.Graphics2D) : WorldCanvas {
        private fun white(a: Float) = Color(1f, 1f, 1f, a.coerceIn(0f, 1f))
        override fun line(x0: Float, y0: Float, x1: Float, y1: Float, alpha: Float, width: Float) {
            g.color = white(alpha); g.stroke = BasicStroke(width)
            g.draw(Line2D.Float(x0, y0, x1, y1))
        }
        override fun dot(x: Float, y: Float, radius: Float, alpha: Float) {
            g.color = white(alpha)
            g.fill(Ellipse2D.Float(x - radius, y - radius, radius * 2, radius * 2))
        }
        override fun ring(x: Float, y: Float, radius: Float, alpha: Float, width: Float) {
            g.color = white(alpha); g.stroke = BasicStroke(width)
            g.draw(Ellipse2D.Float(x - radius, y - radius, radius * 2, radius * 2))
        }
    }

    private fun render(shape: WorldShape?, e: Float, px: Int = 520): BufferedImage {
        val img = BufferedImage(px, px, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color.BLACK; g.fillRect(0, 0, px, px)
        val motion = WorldMotion()
        // Settle into the shape, then pose it at the reference yaw.
        motion.settle(shape, loading = false)
        motion.m = e
        motion.yaw = 0.9f
        WorldRenderer.render(AwtCanvas(g), px.toFloat(), motion, FloatArray(WorldRenderer.SCRATCH_SIZE))
        g.dispose()
        return img
    }

    private fun litPixels(img: BufferedImage): Int {
        var n = 0
        for (y in 0 until img.height) for (x in 0 until img.width) if ((img.getRGB(x, y) and 0xFF) > 40) n++
        return n
    }

    @Test
    fun `renders the sphere and every catalog shape`() {
        val dir = File("build/world-snapshots").apply { mkdirs() }
        val sphere = render(null, 0f)
        ImageIO.write(sphere, "png", File(dir, "00-sphere.png"))
        assertTrue(litPixels(sphere) > 2000)

        catalog.forEachIndexed { i, (name, params) ->
            val img = render(ModelWorldGeometry.shapeFor(params), 1f)
            ImageIO.write(img, "png", File(dir, "%02d-%s.png".format(i + 1, name)))
            assertTrue(litPixels(img) > 500, "$name drew something")
        }
        // Half-way through the morph, for eyeballing the transition.
        ImageIO.write(render(ModelWorldGeometry.shapeFor(catalog[0].second), 0.5f), "png", File(dir, "09-qwen-midmorph.png"))
    }
}
