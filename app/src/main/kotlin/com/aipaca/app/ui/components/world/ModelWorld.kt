package com.aipaca.app.ui.components.world

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aipaca.app.engine.ModelHeader
import com.aipaca.app.ui.components.hairlineTop
import com.aipaca.app.ui.theme.Ink
import com.aipaca.app.ui.theme.InkType

/** Default on-screen size of the world. */
val WorldSize: Dp = 224.dp

/**
 * Builds world shapes once per model — the shape is pure geometry of the
 * header, and [WorldMotion] relies on shape identity to detect a model switch.
 */
object WorldShapes {
    private val cache = object : LinkedHashMap<WorldParams, WorldShape?>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<WorldParams, WorldShape?>?) = size > 8
    }

    @Synchronized
    fun forHeader(header: ModelHeader?): WorldShape? {
        if (header == null || !header.hasStructure) return null
        val params = header.worldParams()
        return cache.getOrPut(params) { ModelWorldGeometry.shapeFor(params) }
    }
}

/**
 * The live point cloud.
 *
 * @param header    model to morph into, or null for the idle sphere.
 * @param loading   a model load is in flight: the sphere contracts and spins fast.
 * @param streaming the model is generating: faster spin.
 * @param motion    hoisted so the world keeps its pose while the canvas is off screen.
 */
@Composable
fun ModelWorld(
    header: ModelHeader?,
    loading: Boolean,
    streaming: Boolean,
    motion: WorldMotion,
    modifier: Modifier = Modifier,
    size: Dp = WorldSize
) {
    val target = remember(header) { WorldShapes.forHeader(header) }
    val targetState    by rememberUpdatedState(target)
    val loadingState   by rememberUpdatedState(loading)
    val streamingState by rememberUpdatedState(streaming)

    val context = LocalContext.current
    val animate = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }

    // Written once per frame and read only in the draw phase, so each frame
    // redraws the canvas without recomposing anything.
    val frame = remember { mutableLongStateOf(0L) }
    val scratch = remember { FloatArray(WorldRenderer.SCRATCH_SIZE) }
    val painter = remember { DrawScopeWorldCanvas() }

    LaunchedEffect(motion, animate) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (animate) {
                    val dt = if (last == 0L) 0f else (now - last) / 1_000_000_000f
                    motion.step(dt, targetState, loadingState, streamingState)
                } else {
                    motion.settle(targetState, loadingState)
                }
                last = now
                frame.longValue = now
            }
        }
    }

    Canvas(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = worldDescription(header, loading) }
    ) {
        frame.longValue
        painter.scope = this
        WorldRenderer.render(painter, this.size.minDimension, motion, scratch)
        painter.scope = null
    }
}

private fun worldDescription(header: ModelHeader?, loading: Boolean): String = when {
    loading        -> "Model loading"
    header == null -> "No model loaded"
    else           -> "Model shape: ${header.blocks} blocks, ${header.kvHeads} KV heads"
}

/** Adapter from [WorldCanvas] to a Compose [DrawScope]; reused across frames. */
private class DrawScopeWorldCanvas : WorldCanvas {
    var scope: DrawScope? = null

    override fun line(x0: Float, y0: Float, x1: Float, y1: Float, alpha: Float, width: Float) {
        scope?.drawLine(
            color       = Ink.White.copy(alpha = alpha.coerceIn(0f, 1f)),
            start       = Offset(x0, y0),
            end         = Offset(x1, y1),
            strokeWidth = width
        )
    }

    override fun dot(x: Float, y: Float, radius: Float, alpha: Float) {
        scope?.drawCircle(
            color  = Ink.White.copy(alpha = alpha.coerceIn(0f, 1f)),
            radius = radius,
            center = Offset(x, y)
        )
    }

    override fun ring(x: Float, y: Float, radius: Float, alpha: Float, width: Float) {
        scope?.drawCircle(
            color  = Ink.White.copy(alpha = alpha.coerceIn(0f, 1f)),
            radius = radius,
            center = Offset(x, y),
            style  = Stroke(width = width)
        )
    }
}

/**
 * The header readout under the world: six cells (`BLOCKS 36`, `HEADS 32 / 8 KV`…)
 * in a three-column grid, or a single idle line when no header has been read.
 */
@Composable
fun HeaderReadout(
    header: ModelHeader?,
    modifier: Modifier = Modifier,
    idleText: String = "No header read · idle"
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .hairlineTop(Ink.Hairline)
            .padding(top = 12.dp)
    ) {
        if (header == null) {
            Text(
                text  = idleText.uppercase(),
                style = InkType.MicroLine,
                color = Ink.Micro
            )
            return@Column
        }
        val cells = header.readout()
        Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
            cells.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { (label, value) ->
                        Column(
                            modifier            = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text(label, style = InkType.ReadoutLabel, color = Ink.Micro, maxLines = 1)
                            Text(
                                text     = value,
                                style    = InkType.ReadoutValue,
                                color    = Ink.Text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}
