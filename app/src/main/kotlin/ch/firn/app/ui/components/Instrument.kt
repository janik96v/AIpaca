package ch.firn.app.ui.components

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ch.firn.app.ui.theme.Ink
import ch.firn.app.ui.theme.InkType
import kotlinx.coroutines.launch

/*
 * Instrument component kit: lines, chrome text, icons and marks.
 *
 * Zero corner radius for layout, 1dp borders, white at an alpha for every
 * level of hierarchy, tracked uppercase for chrome. Press feedback is a flat
 * white fill rather than a ripple — see [PressFill]. Buttons, chips, tabs and
 * switches are the soft-square controls in SoftSquare.kt.
 */

// ---- Press feedback ----------------------------------------------------------

/**
 * App-wide indication: a flat white fill while pressed. Installed as
 * `LocalIndication` by the theme, so every `Modifier.clickable` uses it.
 */
object PressFill : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        PressFillNode(interactionSource)

    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = System.identityHashCode(this)
}

private class PressFillNode(private val source: InteractionSource) : Modifier.Node(), DrawModifierNode {
    private var presses = 0

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press   -> presses++
                    is PressInteraction.Release -> presses = (presses - 1).coerceAtLeast(0)
                    is PressInteraction.Cancel  -> presses = (presses - 1).coerceAtLeast(0)
                }
                invalidateDraw()
            }
        }
    }

    override fun ContentDrawScope.draw() {
        if (presses > 0) drawRect(Ink.PressedFill)
        drawContent()
    }
}

// ---- Lines -------------------------------------------------------------------

fun Modifier.hairlineTop(color: Color = Ink.Hairline, width: Dp = 1.dp): Modifier = drawBehind {
    val w = width.toPx()
    drawRect(color, topLeft = Offset.Zero, size = size.copy(height = w))
}

fun Modifier.hairlineBottom(color: Color = Ink.Divider, width: Dp = 1.dp): Modifier = drawBehind {
    val w = width.toPx()
    drawRect(color, topLeft = Offset(0f, size.height - w), size = size.copy(height = w))
}

fun Modifier.hairlineStart(color: Color = Ink.Divider, width: Dp = 1.dp): Modifier = drawBehind {
    val w = width.toPx()
    drawRect(color, topLeft = Offset.Zero, size = size.copy(width = w))
}

/**
 * Hairline that fades out to the right: `linear-gradient(to right, c, transparent f)`.
 * Used under screen titles (.2 → 76%), the empty-state title (.2 → 70%) and
 * between memory entries (.13 → 82%).
 */
@Composable
fun FadeRule(
    modifier: Modifier = Modifier,
    color: Color = Ink.Border,
    fadeAt: Float = 0.76f
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Brush.horizontalGradient(0f to color, fadeAt to Color.Transparent))
    )
}

// ---- Text --------------------------------------------------------------------

/** Chrome text: always uppercase. */
@Composable
fun Tracked(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Ink.Text,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null
) {
    Text(
        text      = text.uppercase(),
        style     = style,
        color     = color,
        modifier  = modifier,
        maxLines  = maxLines,
        overflow  = overflow,
        softWrap  = maxLines != 1,
        textAlign = textAlign
    )
}

/** `MEMORY` / `MODELS` / `SERVER` with its fading hairline, 18dp apart. */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Tracked(text, InkType.ScreenTitle, color = Ink.Text)
        FadeRule(fadeAt = 0.76f)
    }
}

// ---- Icons -------------------------------------------------------------------

/** A Phosphor Thin glyph at [size] (the design's icon `font-size`). */
@Composable
fun InkIcon(
    icon: ImageVector,
    size: Dp,
    modifier: Modifier = Modifier,
    tint: Color = Ink.White,
    contentDescription: String? = null
) {
    Icon(
        imageVector        = icon,
        contentDescription = contentDescription,
        tint               = tint,
        modifier           = modifier.size(size)
    )
}

/** Icon-only action with a ≥ 40dp touch target. */
@Composable
fun IconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    tint: Color = Ink.DisabledIcon,
    enabled: Boolean = true,
    touch: Dp = 40.dp
) {
    Box(
        modifier = modifier
            .size(touch)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        InkIcon(icon, size, tint = tint, contentDescription = contentDescription)
    }
}

// ---- Marks -------------------------------------------------------------------

/** A grid of square dots — the installed-model 2×2 mark. */
@Composable
fun DotGrid(
    modifier: Modifier = Modifier,
    columns: Int = 3,
    rows: Int = 3,
    dot: Dp = 2.dp,
    gap: Dp = 3.dp,
    color: Color = Ink.White
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        repeat(rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                repeat(columns) { Box(Modifier.size(dot).background(color)) }
            }
        }
    }
}

/** 4dp square status mark. */
@Composable
fun StateSquare(modifier: Modifier = Modifier, color: Color = Ink.White, size: Dp = 4.dp) {
    Box(modifier.size(size).background(color))
}

/** 1dp progress track with a white fill and an optional `NN%` label. */
@Composable
fun ProgressTrack(
    fraction: Float?,
    modifier: Modifier = Modifier,
    label: String? = null
) {
    Row(
        modifier              = modifier.fillMaxWidth(),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(Ink.Track)
        ) {
            if (fraction != null) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .height(1.dp)
                        .background(Ink.White)
                )
            }
        }
        if (label != null) {
            Tracked(label, InkType.Button, color = Ink.Status, maxLines = 1)
        }
    }
}

/** Fixed-width spacer, for rows that mirror the design's `gap`. */
@Composable
fun HSpace(width: Dp) = Spacer(Modifier.width(width))
