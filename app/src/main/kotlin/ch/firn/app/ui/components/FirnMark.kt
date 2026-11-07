package ch.firn.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import ch.firn.app.ui.theme.Ink
import kotlin.math.min

/*
 * The firn mark, small version — a ridge with a 1·2·3 neural net inside —
 * transcribed from docs/design-rebranding/brand/firn-mark-small-white.svg
 * (viewBox 104 80 192 140). Drawn rather than shipped as a drawable so the
 * thinking state (firn-mark-thinking-small-animated.svg) can light one path
 * through the net at a time.
 */

private const val VIEW_X = 104f
private const val VIEW_Y = 80f
private const val VIEW_W = 192f
private const val VIEW_H = 140f

/** Width over height of the mark's viewBox. */
const val FIRN_MARK_ASPECT = VIEW_W / VIEW_H

private val NeuronX = floatArrayOf(200f, 184.63f, 215.37f, 169.27f, 200f, 230.73f)
private val NeuronY = floatArrayOf(144f, 165f, 165f, 186f, 186f, 186f)
private val NeuronR = floatArrayOf(8.4f, 7.2f, 7.2f, 6.4f, 6.4f, 6.4f)

/** Small version: each layer wired to the next, the two long crossing links removed. */
private val Links = arrayOf(0 to 1, 0 to 2, 1 to 3, 1 to 4, 2 to 4, 2 to 5)

private const val RIDGE_WIDTH = 12f
private const val LINK_WIDTH = 5.5f
private const val LINK_ALPHA = 0.8f

// ---- Thinking ------------------------------------------------------------------
// Six steps, 0.7 s each, looping (4.2 s): the lit path runs top → left →
// bottom-left, top → left → bottom-centre, top → right → bottom-centre,
// top → right → bottom-right, top → left → bottom-right, top → right →
// bottom-left. Keyframes copied from the animated SVG; values between them
// are interpolated linearly, as SMIL does.

private const val THINK_PERIOD_MS = 4_200
private val ThinkKeyTimes = floatArrayOf(0f, .17f, .33f, .5f, .67f, .83f, 1f)

private val LinkKeys = arrayOf(
    floatArrayOf(1f, 1f, .3f, .3f, 1f, .3f, 1f),
    floatArrayOf(.3f, .3f, 1f, 1f, .3f, 1f, .3f),
    floatArrayOf(1f, .3f, .3f, .3f, .3f, .3f, 1f),
    floatArrayOf(.3f, 1f, .3f, .3f, .3f, .3f, .3f),
    floatArrayOf(.3f, .3f, 1f, .3f, .3f, .3f, .3f),
    floatArrayOf(.3f, .3f, .3f, 1f, .3f, .3f, .3f)
)

private val NeuronKeys = arrayOf(
    floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, 1f),
    floatArrayOf(1f, 1f, .4f, .4f, 1f, .4f, 1f),
    floatArrayOf(.4f, .4f, 1f, 1f, .4f, 1f, .4f),
    floatArrayOf(1f, .4f, .4f, .4f, .4f, 1f, 1f),
    floatArrayOf(.4f, 1f, 1f, .4f, .4f, .4f, .4f),
    floatArrayOf(.4f, .4f, .4f, 1f, 1f, .4f, .4f)
)

/** Value of [keys] at [t] ∈ [0, 1). */
internal fun thinkAlpha(keys: FloatArray, t: Float): Float {
    for (i in 0 until ThinkKeyTimes.size - 1) {
        val t0 = ThinkKeyTimes[i]
        val t1 = ThinkKeyTimes[i + 1]
        if (t <= t1) {
            val f = ((t - t0) / (t1 - t0)).coerceIn(0f, 1f)
            return keys[i] + (keys[i + 1] - keys[i]) * f
        }
    }
    return keys.last()
}

/**
 * The firn mark, fitted inside its bounds (centred, aspect kept). Size it in
 * the design's px as dp: 24×18 on the rail, 20×15 in the collapsed header,
 * 18×13 in the status line, 20dp wide next to the agent's label.
 *
 * [thinking] lights one path through the net at a time and dims the rest.
 */
@Composable
fun FirnMark(
    modifier: Modifier = Modifier,
    thinking: Boolean = false,
    color: Color = Ink.Text,
    contentDescription: String? = null
) {
    val phase: State<Float>? = if (thinking) {
        rememberInfiniteTransition(label = "firnThinking").animateFloat(
            initialValue  = 0f,
            targetValue   = 1f,
            animationSpec = infiniteRepeatable(tween(THINK_PERIOD_MS, easing = LinearEasing)),
            label         = "firnThinkingPhase"
        )
    } else null
    val ridge = remember {
        Path().apply {
            moveTo(110f, 211f)
            lineTo(200f, 88f)
            lineTo(290f, 211f)
        }
    }
    val described = if (contentDescription != null) {
        Modifier.semantics { this.contentDescription = contentDescription }
    } else Modifier

    Canvas(modifier.then(described)) {
        val scale = min(size.width / VIEW_W, size.height / VIEW_H)
        val dx = (size.width - VIEW_W * scale) / 2f
        val dy = (size.height - VIEW_H * scale) / 2f
        // Read in the draw phase only: the animation invalidates the canvas, not the composition.
        val t = phase?.value

        withTransform({
            translate(dx, dy)
            scale(scale, scale, pivot = Offset.Zero)
            translate(-VIEW_X, -VIEW_Y)
        }) {
            drawPath(
                ridge,
                color,
                style = Stroke(width = RIDGE_WIDTH, cap = StrokeCap.Square, join = StrokeJoin.Miter)
            )
            Links.forEachIndexed { k, (a, b) ->
                drawLine(
                    color       = color,
                    start       = Offset(NeuronX[a], NeuronY[a]),
                    end         = Offset(NeuronX[b], NeuronY[b]),
                    strokeWidth = LINK_WIDTH,
                    alpha       = if (t == null) LINK_ALPHA else thinkAlpha(LinkKeys[k], t)
                )
            }
            for (i in NeuronX.indices) {
                drawCircle(
                    color  = color,
                    radius = NeuronR[i],
                    center = Offset(NeuronX[i], NeuronY[i]),
                    alpha  = if (t == null) 1f else thinkAlpha(NeuronKeys[i], t)
                )
            }
        }
    }
}
