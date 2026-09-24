package com.aipaca.app.ui.components.world

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The Model World: a 3D point cloud drawn on the chat empty state.
 *
 * With no model loaded it is a Fibonacci sphere. Once a model's header has been
 * read, the same 420 points fold into a shape built from that model's
 * architecture — one ring per transformer block, one point per KV head, ring
 * radius from the embedding width, a helical twist from the trained context and
 * dot weight from the quantisation. Nothing in the shape is decoration.
 *
 * Everything in this file is plain Kotlin with no Android dependency, so the
 * maths can be unit-tested and rendered off-device.
 */

/** The header fields the world is built from. */
data class WorldParams(
    /** Transformer blocks (`{arch}.block_count`), counted across both stacks for encoder–decoders. */
    val blocks: Int,
    /** KV heads per block (`{arch}.attention.head_count_kv`). */
    val kv: Int,
    /** Embedding width (`{arch}.embedding_length`). */
    val embd: Int,
    /** Trained context length (`{arch}.context_length`). */
    val ctx: Int,
    /** Bits per weight, see [bitsFor]. */
    val bits: Float,
    /** Encoder–decoder models (Whisper, T5) are drawn as two towers. */
    val split: Boolean = false
)

/** Bits per weight for a quantisation name — drives the dot weight. */
fun bitsFor(quant: String): Float {
    val q = quant.uppercase()
    return when {
        q.startsWith("Q4_0")                         -> 4f
        q.startsWith("Q4_") || q.startsWith("IQ4")   -> 4.5f
        q.startsWith("Q5_")                          -> 5.5f
        q.startsWith("Q6_")                          -> 6.5f
        q.startsWith("Q8_")                          -> 8f
        q.startsWith("Q3_") || q.startsWith("IQ3")   -> 3.5f
        q.startsWith("Q2_") || q.startsWith("IQ2")   -> 2.6f
        q.startsWith("F16") || q.startsWith("BF16")  -> 16f
        q.startsWith("F32")                          -> 32f
        else                                         -> 4.5f
    }
}

/**
 * Point cloud in unit space (sphere radius 1).
 *
 * @property xyz   packed `x, y, z` per point.
 * @property links packed index pairs `a, b` per edge.
 * @property dotW  dot-radius multiplier once fully morphed into this shape.
 */
class WorldShape(val xyz: FloatArray, val links: IntArray, val dotW: Float) {
    val size: Int get() = xyz.size / 3
    val linkCount: Int get() = links.size / 2
}

object ModelWorldGeometry {

    /** Sphere and model share one point budget, so every point has a morph partner. */
    const val N = 420

    /** Fixed camera pitch, radians. */
    const val PITCH = 0.42f

    private const val TAU = (2 * PI).toFloat()

    /** Fibonacci lattice, each point linked to its first two neighbours within reach. */
    val sphere: WorldShape by lazy {
        val golden = (PI * (3 - sqrt(5.0))).toFloat()
        val p = FloatArray(N * 3)
        for (i in 0 until N) {
            val y = 1f - (i / (N - 1f)) * 2f
            val r = sqrt(max(0f, 1f - y * y))
            val th = golden * i
            p[i * 3] = cos(th) * r
            p[i * 3 + 1] = y
            p[i * 3 + 2] = sin(th) * r
        }
        val reach = 1.25f * sqrt(4f * PI.toFloat() / N)
        val links = ArrayList<Int>()
        for (i in 0 until N) {
            var made = 0
            var j = i + 1
            while (j < N && made < 2) {
                val dx = p[i * 3] - p[j * 3]
                val dy = p[i * 3 + 1] - p[j * 3 + 1]
                val dz = p[i * 3 + 2] - p[j * 3 + 2]
                if (sqrt(dx * dx + dy * dy + dz * dz) < reach) {
                    links += i; links += j; made++
                }
                j++
            }
        }
        WorldShape(p, links.toIntArray(), 1f)
    }

    /**
     * The model's own shape. Returns null when the header carries no blocks —
     * there is nothing to build, and the world stays a sphere.
     */
    fun shapeFor(g: WorldParams): WorldShape? {
        if (g.blocks <= 0) return null
        val per = g.kv.coerceIn(6, 12)
        val maxRings = N / per
        val rad0 = 0.28f + 0.26f * min(1f, g.embd / 4096f)
        val ctxF = (log2(max(2, g.ctx) / 1024f) / 7f).coerceIn(0f, 1f)
        // A fraction of the ring's own angular spacing, so the twist never aliases.
        val twist = (TAU / per) * (0.10f + 0.34f * ctxF)
        val stacks = if (g.split) {
            listOf(ceil(g.blocks / 2f).toInt() to -0.44f, (g.blocks / 2) to 0.44f)
        } else {
            listOf(min(g.blocks, maxRings) to 0f)
        }

        val pts = ArrayList<Float>(N * 3)
        val links = ArrayList<Int>()
        for ((rings, ox) in stacks) {
            val rad = if (g.split) rad0 * 0.62f else rad0
            val hspan = 0.42f + 0.50f * min(1f, rings / 36f)
            var prev = -1
            for (l in 0 until rings) {
                val y = if (rings == 1) 0f else -hspan + 2f * hspan * (l / (rings - 1f))
                val first = pts.size / 3
                for (k in 0 until per) {
                    val th = l * twist + (k.toFloat() / per) * TAU
                    pts += cos(th) * rad + ox
                    pts += y
                    pts += sin(th) * rad
                    links += first + k; links += first + (k + 1) % per          // ring
                    if (prev >= 0) { links += prev + k; links += first + k }    // residual
                }
                prev = first
            }
        }
        val n = min(N, pts.size / 3)
        val xyz = FloatArray(n * 3) { pts[it] }
        val kept = ArrayList<Int>(links.size)
        for (i in links.indices step 2) {
            if (links[i] < n && links[i + 1] < n) { kept += links[i]; kept += links[i + 1] }
        }
        return WorldShape(xyz, kept.toIntArray(), 0.62f + 0.52f * min(1f, g.bits / 8f))
    }
}

/**
 * Per-frame motion state. Not recomposition state: hold one instance in
 * `remember {}` (or higher, so it survives the canvas leaving composition) and
 * mutate it from the frame loop.
 *
 * | state              | yaw rad/s | conv | m |
 * |--------------------|-----------|------|---|
 * | idle, no model     | 0.11      | 1    | 0 |
 * | loading            | 1.6       | 0.34 | 0 |
 * | ready              | 0.11      | 1    | 1 |
 * | streaming          | 0.8       | 1    | 1 |
 */
class WorldMotion {
    /** Accumulated yaw, radians. */
    var yaw = 0f
    /** Radius multiplier — contracts while a model loads. */
    var conv = 1f
    /** Morph progress sphere → model, 0…1 before easing. */
    var m = 0f

    /**
     * The shape currently being morphed towards. Lags the requested target: a
     * new shape is only swapped in once the world has folded back into the
     * sphere, so switching or unloading a model folds out of the old shape
     * rather than jumping.
     */
    var drawn: WorldShape? = null
        private set

    /** Smoothstep of [m]. */
    val ease: Float get() = m * m * (3 - 2 * m)

    fun step(dt: Float, target: WorldShape?, loading: Boolean, streaming: Boolean) {
        val d = min(0.05f, max(0f, dt))
        if (drawn !== target && (drawn == null || m < 0.02f)) drawn = target
        val want = if (drawn != null && drawn === target && !loading) 1f else 0f
        m += (want - m) * min(1f, d * if (want == 1f) 2.2f else 3.6f)
        conv += ((if (loading) 0.34f else 1f) - conv) * min(1f, d * 3.2f)
        yaw += d * when {
            streaming -> 0.8f
            loading   -> 1.6f
            else      -> 0.11f
        }
    }

    /** Jumps straight to the resting state for [target] — used when motion is disabled. */
    fun settle(target: WorldShape?, loading: Boolean) {
        drawn = target
        m = if (target != null && !loading) 1f else 0f
        conv = if (loading) 0.34f else 1f
    }
}

/** Drawing surface the renderer paints through — a Compose DrawScope on device, AWT in tests. */
interface WorldCanvas {
    /** White line, [alpha] 0…1. */
    fun line(x0: Float, y0: Float, x1: Float, y1: Float, alpha: Float, width: Float)
    /** Filled white dot. */
    fun dot(x: Float, y: Float, radius: Float, alpha: Float)
    /** Unfilled white circle. */
    fun ring(x: Float, y: Float, radius: Float, alpha: Float, width: Float)
}

object WorldRenderer {

    /** The reference was drawn into a 520 px backing store; all px values scale from it. */
    private const val REFERENCE_PX = 520f

    /** Scratch buffer size for [render]: `x, y, depth01, alpha` per point. */
    const val SCRATCH_SIZE = ModelWorldGeometry.N * 4

    /**
     * Draws one frame into a square of [widthPx]. [scratch] must hold
     * [SCRATCH_SIZE] floats and is overwritten.
     *
     * Order: sphere links → shape links → dots → frame ring.
     */
    fun render(canvas: WorldCanvas, widthPx: Float, motion: WorldMotion, scratch: FloatArray) {
        val sphere = ModelWorldGeometry.sphere
        val shape = motion.drawn
        val e = motion.ease
        project(scratch, sphere, shape, e, motion.yaw, motion.conv, widthPx)

        val px = widthPx / REFERENCE_PX

        drawLinks(canvas, sphere.links, scratch, 1f - e, px)
        if (shape != null) drawLinks(canvas, shape.links, scratch, e, px)

        val dw = 1f + ((shape?.dotW ?: 1f) - 1f) * e
        for (i in 0 until ModelWorldGeometry.N) {
            val alive = scratch[i * 4 + 3]
            if (alive <= 0.004f) continue
            val d = scratch[i * 4 + 2]
            canvas.dot(
                scratch[i * 4], scratch[i * 4 + 1],
                radius = (0.8f + 2.1f * d) * dw * px,
                alpha  = (0.10f + 0.82f * d) * alive
            )
        }

        canvas.ring(widthPx / 2f, widthPx / 2f, widthPx * 0.455f, 0.09f, px)
    }

    private fun drawLinks(canvas: WorldCanvas, links: IntArray, p: FloatArray, fade: Float, px: Float) {
        if (fade < 0.03f) return
        var k = 0
        while (k < links.size) {
            val a = links[k] * 4
            val b = links[k + 1] * 4
            val alpha = (0.05f + 0.23f * (p[a + 2] + p[b + 2]) / 2f) * fade
            canvas.line(p[a], p[a + 1], p[b], p[b + 1], alpha, px)
            k += 2
        }
    }

    /**
     * Projects all N points into screen space: `out = [x, y, depth01, alpha] × N`.
     *
     * Points without a partner in [shape] collapse towards the centre and fade.
     */
    fun project(
        out: FloatArray,
        sphere: WorldShape,
        shape: WorldShape?,
        e: Float,
        yaw: Float,
        conv: Float,
        widthPx: Float
    ) {
        val cx = widthPx / 2f
        val cy = cx
        val r = widthPx * 0.40f * conv
        val ca = cos(yaw); val sa = sin(yaw)
        val ct = cos(ModelWorldGeometry.PITCH); val st = sin(ModelWorldGeometry.PITCH)
        val s = sphere.xyz
        for (i in 0 until ModelWorldGeometry.N) {
            var x = s[i * 3]; var y = s[i * 3 + 1]; var z = s[i * 3 + 2]
            var alpha = 1f
            if (shape != null) {
                if (i < shape.size) {
                    x += (shape.xyz[i * 3] - x) * e
                    y += (shape.xyz[i * 3 + 1] - y) * e
                    z += (shape.xyz[i * 3 + 2] - z) * e
                } else {
                    val k = 1f - e * 0.94f
                    x *= k; y *= k; z *= k
                    alpha = 1f - e
                }
            }
            val rx = x * ca + z * sa
            val z0 = -x * sa + z * ca
            out[i * 4] = cx + rx * r
            out[i * 4 + 1] = cy + (y * ct - z0 * st) * r
            out[i * 4 + 2] = ((y * st + z0 * ct) + 1f) / 2f
            out[i * 4 + 3] = alpha
        }
    }
}
