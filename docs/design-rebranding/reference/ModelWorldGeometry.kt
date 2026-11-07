// Reference port of the Model World maths from firn-prototype.dc.html.
// Pure Kotlin, no Android deps — drop into ui/components/world/ and drive from a Compose Canvas.
// Package follows the rebrand (README §0): com.aipaca.app → ch.firn.app.
package ch.firn.app.ui.components.world

import kotlin.math.*

data class WorldParams(
    val blocks: Int,      // GgufProbeResult.nLayer
    val kv: Int,          // GgufProbeResult.nHeadKv
    val embd: Int,        // GgufProbeResult.nEmbd
    val ctx: Int,         // GgufProbeResult.nCtxTrain
    val bits: Float,      // bitsFor(quant)
    val split: Boolean = false // encoder–decoder (whisper, t5)
)

fun bitsFor(quant: String): Float = when {
    quant.startsWith("Q4_0") -> 4f
    quant.startsWith("Q4_") -> 4.5f
    quant.startsWith("Q5_") -> 5.5f
    quant.startsWith("Q6_") -> 6.5f
    quant.startsWith("Q8_") -> 8f
    quant.startsWith("F16") || quant.startsWith("BF16") -> 16f
    else -> 4.5f
}

class Shape(val xyz: FloatArray, val links: IntArray, val dotW: Float) {
    val size get() = xyz.size / 3
}

object ModelWorldGeometry {
    const val N = 420
    private const val TAU = (2 * PI).toFloat()

    /** Fibonacci sphere + first-2-neighbour chords. */
    val sphere: Shape by lazy {
        val golden = (PI * (3 - sqrt(5.0))).toFloat()
        val p = FloatArray(N * 3)
        for (i in 0 until N) {
            val y = 1f - (i / (N - 1f)) * 2f
            val r = sqrt(max(0f, 1f - y * y))
            val th = golden * i
            p[i * 3] = cos(th) * r; p[i * 3 + 1] = y; p[i * 3 + 2] = sin(th) * r
        }
        val reach = 1.25f * sqrt(4f * PI.toFloat() / N)
        val links = ArrayList<Int>()
        for (i in 0 until N) {
            var made = 0
            var j = i + 1
            while (j < N && made < 2) {
                val dx = p[i * 3] - p[j * 3]; val dy = p[i * 3 + 1] - p[j * 3 + 1]; val dz = p[i * 3 + 2] - p[j * 3 + 2]
                if (sqrt(dx * dx + dy * dy + dz * dz) < reach) { links += i; links += j; made++ }
                j++
            }
        }
        Shape(p, links.toIntArray(), 1f)
    }

    /** One ring per block, one point per KV head, radius ← embd, twist ← ctx. */
    fun shapeFor(g: WorldParams): Shape {
        val per = g.kv.coerceIn(6, 12)
        val maxRings = N / per
        val rad0 = 0.28f + 0.26f * min(1f, g.embd / 4096f)
        val ctxF = (log2(max(2, g.ctx) / 1024f) / 7f).coerceIn(0f, 1f)
        val twist = (TAU / per) * (0.10f + 0.34f * ctxF)
        val stacks = if (g.split)
            listOf(ceil(g.blocks / 2f).toInt() to -0.44f, (g.blocks / 2) to 0.44f)
        else listOf(min(g.blocks, maxRings) to 0f)

        val pts = ArrayList<Float>(); val links = ArrayList<Int>()
        for ((rings, ox) in stacks) {
            val rad = if (g.split) rad0 * 0.62f else rad0
            val hspan = 0.42f + 0.50f * min(1f, rings / 36f)
            var prev = -1
            for (l in 0 until rings) {
                val y = if (rings == 1) 0f else -hspan + 2f * hspan * (l / (rings - 1f))
                val first = pts.size / 3
                for (k in 0 until per) {
                    val th = l * twist + (k.toFloat() / per) * TAU
                    pts += cos(th) * rad + ox; pts += y; pts += sin(th) * rad
                    links += first + k; links += first + (k + 1) % per
                    if (prev >= 0) { links += prev + k; links += first + k }
                }
                prev = first
            }
        }
        val n = min(N, pts.size / 3)
        val xyz = FloatArray(n * 3) { pts[it] }
        val kept = ArrayList<Int>()
        for (i in links.indices step 2) if (links[i] < n && links[i + 1] < n) { kept += links[i]; kept += links[i + 1] }
        return Shape(xyz, kept.toIntArray(), 0.62f + 0.52f * min(1f, g.bits / 8f))
    }
}

/** Mutable per-frame state. Hold in remember{}; mutate inside withFrameNanos. */
class WorldMotion {
    var yaw = 0f; var conv = 1f; var m = 0f

    /** loading/streaming from EngineState; hasShape = a model is loaded. */
    fun step(dt: Float, loading: Boolean, streaming: Boolean, hasShape: Boolean) {
        val d = min(0.05f, dt)
        val want = if (hasShape && !loading) 1f else 0f
        m += (want - m) * min(1f, d * if (want == 1f) 2.2f else 3.6f)
        conv += ((if (loading) 0.34f else 1f) - conv) * min(1f, d * 3.2f)
        yaw += d * when { streaming -> 0.8f; loading -> 1.6f; else -> 0.11f }
    }
    val ease get() = m * m * (3 - 2 * m)
}

/**
 * Projects all N points into screen space for one frame.
 * out: [x, y, depth01, alpha] × N. Pitch fixed at 0.42 rad.
 * Render: sphere.links with fade (1−e), shape.links with fade e — stroke α = (0.05 + 0.23·avg(d))·fade;
 * dots α = (0.10 + 0.82·d)·alpha, radius = (0.8 + 2.1·d)·lerp(1, dotW, e) at a 520 px canvas (scale by W/520);
 * then a frame circle r = W·0.455, α 0.09.
 */
fun project(out: FloatArray, sphere: Shape, shape: Shape?, mo: WorldMotion, widthPx: Float) {
    val e = mo.ease
    val cx = widthPx / 2; val cy = cx
    val r = widthPx * 0.40f * mo.conv
    val ca = cos(mo.yaw); val sa = sin(mo.yaw)
    val ct = cos(0.42f); val st = sin(0.42f)
    for (i in 0 until ModelWorldGeometry.N) {
        var px = sphere.xyz[i * 3]; var py = sphere.xyz[i * 3 + 1]; var pz = sphere.xyz[i * 3 + 2]
        var alpha = 1f
        if (shape != null) {
            if (i < shape.size) {
                px += (shape.xyz[i * 3] - px) * e
                py += (shape.xyz[i * 3 + 1] - py) * e
                pz += (shape.xyz[i * 3 + 2] - pz) * e
            } else {
                val k = 1f - e * 0.94f
                px *= k; py *= k; pz *= k; alpha = 1f - e
            }
        }
        val x = px * ca + pz * sa
        val z0 = -px * sa + pz * ca
        out[i * 4] = cx + x * r
        out[i * 4 + 1] = cy + (py * ct - z0 * st) * r
        out[i * 4 + 2] = ((py * st + z0 * ct) + 1f) / 2f
        out[i * 4 + 3] = alpha
    }
}
