package com.aipaca.app.ui.components.world

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ModelWorldGeometryTest {

    private val qwen = WorldParams(blocks = 36, kv = 8, embd = 2560, ctx = 131072, bits = 4.5f)
    private val whisper = WorldParams(blocks = 12, kv = 8, embd = 512, ctx = 448, bits = 16f, split = true)

    @Test
    fun `sphere has N unit-length points and at most two links per point`() {
        val s = ModelWorldGeometry.sphere
        assertEquals(ModelWorldGeometry.N, s.size)
        for (i in 0 until s.size) {
            val x = s.xyz[i * 3]; val y = s.xyz[i * 3 + 1]; val z = s.xyz[i * 3 + 2]
            assertEquals(1f, sqrt(x * x + y * y + z * z), 1e-4f)
        }
        assertTrue(s.linkCount in 1..ModelWorldGeometry.N * 2)
        val out = IntArray(ModelWorldGeometry.N)
        for (k in 0 until s.linkCount) out[s.links[k * 2]]++
        assertTrue(out.all { it <= 2 })
    }

    @Test
    fun `one ring per block and one point per kv head`() {
        val shape = assertNotNull(ModelWorldGeometry.shapeFor(qwen))
        assertEquals(36 * 8, shape.size)
        // ring links for every point + residual links for every ring after the first
        assertEquals(36 * 8 + 35 * 8, shape.linkCount)
    }

    @Test
    fun `points per ring are clamped to 6 to 12`() {
        assertEquals(10 * 6, ModelWorldGeometry.shapeFor(qwen.copy(blocks = 10, kv = 2))!!.size)
        assertEquals(10 * 12, ModelWorldGeometry.shapeFor(qwen.copy(blocks = 10, kv = 32))!!.size)
    }

    @Test
    fun `ring count never exceeds the point budget`() {
        val deep = ModelWorldGeometry.shapeFor(qwen.copy(blocks = 200, kv = 8))!!
        assertTrue(deep.size <= ModelWorldGeometry.N)
        assertEquals((ModelWorldGeometry.N / 8) * 8, deep.size)
    }

    @Test
    fun `split models build two towers either side of the axis`() {
        val shape = assertNotNull(ModelWorldGeometry.shapeFor(whisper))
        assertEquals(12 * 8, shape.size)
        val xs = (0 until shape.size).map { shape.xyz[it * 3] }
        val left = xs.take(6 * 8)
        val right = xs.drop(6 * 8)
        assertTrue(left.all { it < 0f }, "encoder tower sits left")
        assertTrue(right.all { it > 0f }, "decoder tower sits right")
        assertEquals(-0.44f, left.average().toFloat(), 1e-3f)
        assertEquals(0.44f, right.average().toFloat(), 1e-3f)
    }

    @Test
    fun `oversized split models are truncated and keep only valid links`() {
        val shape = ModelWorldGeometry.shapeFor(whisper.copy(blocks = 100, kv = 12))!!
        assertEquals(ModelWorldGeometry.N, shape.size)
        assertTrue(shape.links.all { it in 0 until shape.size })
    }

    @Test
    fun `radius grows with embedding width and caps at 4096`() {
        fun radius(embd: Int): Float {
            val s = ModelWorldGeometry.shapeFor(qwen.copy(embd = embd))!!
            return sqrt(s.xyz[0] * s.xyz[0] + s.xyz[2] * s.xyz[2])
        }
        assertEquals(0.28f + 0.26f * (512f / 4096f), radius(512), 1e-4f)
        assertEquals(0.54f, radius(4096), 1e-4f)
        assertEquals(0.54f, radius(8192), 1e-4f)
    }

    @Test
    fun `dot weight follows quantisation bits`() {
        assertEquals(0.62f + 0.52f * 4.5f / 8f, ModelWorldGeometry.shapeFor(qwen)!!.dotW, 1e-5f)
        assertEquals(1.14f, ModelWorldGeometry.shapeFor(whisper)!!.dotW, 1e-5f)
    }

    @Test
    fun `no blocks means no shape`() {
        assertNull(ModelWorldGeometry.shapeFor(qwen.copy(blocks = 0)))
    }

    @Test
    fun `bits per weight by quant name`() {
        assertEquals(4f, bitsFor("Q4_0"))
        assertEquals(4.5f, bitsFor("Q4_K_M"))
        assertEquals(4.5f, bitsFor("Q4_K_S"))
        assertEquals(5.5f, bitsFor("Q5_K_M"))
        assertEquals(6.5f, bitsFor("Q6_K"))
        assertEquals(8f, bitsFor("Q8_0"))
        assertEquals(16f, bitsFor("F16"))
        assertEquals(16f, bitsFor("BF16"))
        assertEquals(4.5f, bitsFor("unknown"))
        assertEquals(4.5f, bitsFor(""))
    }

    // ---- Motion ----------------------------------------------------------------

    private fun WorldMotion.run(seconds: Float, target: WorldShape?, loading: Boolean = false, streaming: Boolean = false) {
        repeat((seconds * 60).toInt()) { step(1f / 60f, target, loading, streaming) }
    }

    @Test
    fun `loading contracts the sphere and ready unfolds the model`() {
        val shape = ModelWorldGeometry.shapeFor(qwen)
        val m = WorldMotion()
        m.run(2f, shape, loading = true)
        assertEquals(0.34f, m.conv, 0.02f)
        assertTrue(m.m < 0.01f, "stays a sphere while loading")
        m.run(3f, shape)
        assertEquals(1f, m.conv, 0.01f)
        assertTrue(m.ease > 0.99f, "fully morphed once ready")
        assertSame(shape, m.drawn)
    }

    @Test
    fun `unloading folds back out of the old shape before letting go of it`() {
        val shape = ModelWorldGeometry.shapeFor(qwen)
        val m = WorldMotion()
        m.run(4f, shape)
        m.step(1f / 60f, null, false, false)
        assertSame(shape, m.drawn, "old shape kept while folding")
        m.run(3f, null)
        assertNull(m.drawn)
        assertTrue(m.m < 0.02f)
    }

    @Test
    fun `switching models passes through the sphere`() {
        val a = ModelWorldGeometry.shapeFor(qwen)
        val b = ModelWorldGeometry.shapeFor(whisper)
        val m = WorldMotion()
        m.run(4f, a)
        var minM = 1f
        repeat(300) {
            m.step(1f / 60f, b, false, false)
            if (m.drawn === a) minM = minOf(minM, m.m)
        }
        assertSame(b, m.drawn)
        assertTrue(m.ease > 0.9f)
        assertTrue(minM < 0.05f, "folded into the sphere before swapping")
    }

    @Test
    fun `yaw speed depends on state and dt is clamped`() {
        val m = WorldMotion()
        m.step(1f, null, false, false)
        assertEquals(0.05f * 0.11f, m.yaw, 1e-6f)
        val s = WorldMotion(); s.step(0.02f, null, false, true)
        assertEquals(0.02f * 0.8f, s.yaw, 1e-6f)
        val l = WorldMotion(); l.step(0.02f, null, true, false)
        assertEquals(0.02f * 1.6f, l.yaw, 1e-6f)
    }

    // ---- Projection ------------------------------------------------------------

    @Test
    fun `idle sphere projects inside the frame ring`() {
        val out = FloatArray(WorldRenderer.SCRATCH_SIZE)
        WorldRenderer.project(out, ModelWorldGeometry.sphere, null, 0f, 0.9f, 1f, 520f)
        for (i in 0 until ModelWorldGeometry.N) {
            val dx = out[i * 4] - 260f
            val dy = out[i * 4 + 1] - 260f
            assertTrue(sqrt(dx * dx + dy * dy) <= 520f * 0.40f + 0.5f)
            assertTrue(out[i * 4 + 2] in -0.001f..1.001f)
            assertEquals(1f, out[i * 4 + 3])
        }
    }

    @Test
    fun `orphan points collapse and fade once morphed`() {
        val shape = ModelWorldGeometry.shapeFor(qwen)!!
        val out = FloatArray(WorldRenderer.SCRATCH_SIZE)
        WorldRenderer.project(out, ModelWorldGeometry.sphere, shape, 1f, 0f, 1f, 520f)
        val orphan = shape.size
        assertEquals(0f, out[orphan * 4 + 3])
        assertTrue(abs(out[orphan * 4] - 260f) < 520f * 0.4f * 0.07f)
    }
}
