package ch.firn.app.ui.chat

import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import ch.firn.app.ui.components.IconAction
import ch.firn.app.ui.components.InkMenu
import ch.firn.app.ui.components.InkMenuItem
import ch.firn.app.ui.components.Tracked
import ch.firn.app.ui.theme.Ink
import ch.firn.app.ui.theme.InkType
import ch.firn.app.ui.theme.Ph
import kotlin.math.PI
import kotlin.math.sin

/** Session switches reachable from the modes menu at the bottom of the rail. */
@Immutable
data class ComposerModes(
    val systemPromptSet: Boolean,
    val supportsThinking: Boolean,
    val thinkingEnabled: Boolean,
    val webSearchOn: Boolean,
    val ollamaActive: Boolean,
    val ollamaModelName: String
) {
    val anyActive: Boolean get() = systemPromptSet || thinkingEnabled || webSearchOn || ollamaActive
}

/**
 * The composer: a live sine line over a row of attach · input · action.
 *
 * The action is send (arrow), stop while the model streams or the mic
 * records, and the microphone when the input is empty and a Whisper model is
 * loaded. Sending is gated on a loaded model.
 */
@Composable
fun Composer(
    text: String,
    onTextChange: (String) -> Unit,
    chatReady: Boolean,
    isGenerating: Boolean,
    listening: Boolean,
    transcribing: Boolean,
    whisperLoaded: Boolean,
    attachedImage: Uri?,
    attachedDocument: String?,
    canAttachImage: Boolean,
    onAttachImage: () -> Unit,
    onAttachDocument: () -> Unit,
    onClearAttachment: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onMic: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasAttachment = attachedImage != null || attachedDocument != null
    val canSend = chatReady && !isGenerating && (text.isNotBlank() || hasAttachment)

    Column(modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 14.dp)) {
        if (hasAttachment) {
            AttachmentPreview(attachedImage, attachedDocument, onClearAttachment)
        }

        SineLine(
            active   = isGenerating || listening,
            modifier = Modifier.padding(bottom = 6.dp)
        )

        Row(
            verticalAlignment     = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AttachButton(
                enabled          = chatReady && !isGenerating,
                canAttachImage   = canAttachImage,
                onAttachImage    = onAttachImage,
                onAttachDocument = onAttachDocument
            )

            BasicTextField(
                value           = text,
                onValueChange   = onTextChange,
                enabled         = !isGenerating && !transcribing,
                textStyle       = InkType.Input.copy(color = Ink.Text),
                cursorBrush     = SolidColor(Ink.White),
                maxLines        = 5,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction      = ImeAction.Send
                ),
                keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                modifier        = Modifier.weight(1f),
                decorationBox   = { inner ->
                    Box(Modifier.padding(vertical = 10.dp)) {
                        if (text.isEmpty()) {
                            val hint = when {
                                listening    -> "Listening"
                                transcribing -> "Transcribing"
                                chatReady    -> "Message"
                                else         -> "Load a model first"
                            }
                            Tracked(hint, InkType.Placeholder, color = Ink.Placeholder, maxLines = 1)
                        }
                        inner()
                    }
                }
            )

            val action = when {
                isGenerating                                   -> SquareAction.Stop
                listening                                      -> SquareAction.StopListening
                text.isBlank() && !hasAttachment && whisperLoaded && !transcribing -> SquareAction.Mic
                else                                           -> SquareAction.Send
            }
            ComposerAction(
                action  = action,
                enabled = when (action) {
                    SquareAction.Send -> canSend
                    else              -> true
                },
                onClick = {
                    when (action) {
                        SquareAction.Stop          -> onStop()
                        SquareAction.StopListening -> onMic()
                        SquareAction.Mic           -> onMic()
                        SquareAction.Send          -> onSend()
                    }
                }
            )
        }
    }
}

private enum class SquareAction(val icon: ImageVector, val label: String) {
    Send(SendArrow, "Send message"),
    Stop(StopGlyph, "Stop generation"),
    StopListening(StopGlyph, "Stop recording"),
    Mic(Ph.Microphone, "Dictate")
}

private val ActionShape = RoundedCornerShape(12.dp)

/**
 * 40×40dp, radius 12. Send is `#FAFAFA` with a black arrow when it can send,
 * `#1A1A1A` with a `.45` arrow when it can't; stop is the same white square
 * with a black stop glyph. The microphone sits on the secondary surface.
 */
@Composable
private fun ComposerAction(action: SquareAction, enabled: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val secondary = action == SquareAction.Mic
    val filled = enabled && !secondary
    val fill = when {
        filled  -> if (pressed) Ink.PrimaryPressed else Ink.Text
        pressed -> Ink.ControlPressed
        else    -> Ink.Control
    }
    val tint = when {
        filled  -> Ink.Black
        enabled -> Ink.Text
        else    -> Ink.TertiaryIcon
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(ActionShape)
            .background(fill)
            .then(if (secondary) Modifier.border(1.dp, Ink.ControlBorder, ActionShape) else Modifier)
            .clickable(
                interactionSource = source,
                indication        = null,
                enabled           = enabled,
                onClickLabel      = action.label,
                role              = Role.Button,
                onClick           = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(action.icon, contentDescription = action.label, tint = tint, modifier = Modifier.size(18.dp))
    }
}

/**
 * Send arrow: a custom 24-grid vector at a 2.2 stroke with round caps — heavier
 * than the thin icon set, so it reads on the white fill.
 */
private val SendArrow: ImageVector by lazy {
    ImageVector.Builder("firn-send", 18.dp, 18.dp, 24f, 24f).apply {
        path(
            stroke          = SolidColor(Color.White),
            strokeLineWidth = 2.2f,
            strokeLineCap   = StrokeCap.Round,
            strokeLineJoin  = StrokeJoin.Round
        ) {
            moveTo(12f, 19f); lineTo(12f, 5f)
            moveTo(5.5f, 11.5f); lineTo(12f, 5f); lineTo(18.5f, 11.5f)
        }
    }.build()
}

/** Stop: a filled, slightly rounded square on the same grid as [SendArrow]. */
private val StopGlyph: ImageVector by lazy {
    ImageVector.Builder("firn-stop", 18.dp, 18.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(9f, 7f); lineTo(15f, 7f)
            arcTo(2f, 2f, 0f, false, true, 17f, 9f); lineTo(17f, 15f)
            arcTo(2f, 2f, 0f, false, true, 15f, 17f); lineTo(9f, 17f)
            arcTo(2f, 2f, 0f, false, true, 7f, 15f); lineTo(7f, 9f)
            arcTo(2f, 2f, 0f, false, true, 9f, 7f); close()
        }
    }.build()
}

/**
 * Session switches — system prompt, thinking, web search, Ollama — as a glyph
 * for the rail; white while any of them is on.
 */
@Composable
fun ModesButton(
    modes: ComposerModes,
    onSystemPrompt: () -> Unit,
    onToggleThinking: () -> Unit,
    onWebSearch: () -> Unit,
    onOllama: () -> Unit,
    iconSize: Dp = 18.dp,
    touch: Dp = 44.dp
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconAction(
            icon               = Ph.SlidersHorizontal,
            contentDescription = "Modes",
            onClick            = { open = true },
            size               = iconSize,
            tint               = if (modes.anyActive) Ink.White else Ink.Micro,
            touch              = touch
        )
        InkMenu(expanded = open, onDismissRequest = { open = false }) {
            InkMenuItem(
                label    = "System prompt",
                icon     = Ph.FileText,
                active   = modes.systemPromptSet,
                trailing = if (modes.systemPromptSet) "Set" else null,
                onClick  = { open = false; onSystemPrompt() }
            )
            if (modes.supportsThinking) {
                InkMenuItem(
                    label    = "Thinking",
                    icon     = Ph.Brain,
                    active   = modes.thinkingEnabled,
                    trailing = if (modes.thinkingEnabled) "On" else "Off",
                    onClick  = onToggleThinking
                )
            }
            // No agent toggle: how many tools a turn carries is decided from the
            // model's measured capabilities (agent/AgentTier.kt). What is left is
            // the one real decision — whether queries may leave the device.
            InkMenuItem(
                label    = "Web search",
                icon     = Ph.MagnifyingGlass,
                active   = modes.webSearchOn,
                trailing = if (modes.webSearchOn) "On" else "Off",
                onClick  = { open = false; onWebSearch() }
            )
            InkMenuItem(
                label    = if (modes.ollamaActive) "Ollama · ${modes.ollamaModelName}" else "Ollama",
                icon     = Ph.HardDrives,
                active   = modes.ollamaActive,
                trailing = if (modes.ollamaActive) "On" else "Off",
                onClick  = { open = false; onOllama() }
            )
        }
    }
}

@Composable
private fun AttachButton(
    enabled: Boolean,
    canAttachImage: Boolean,
    onAttachImage: () -> Unit,
    onAttachDocument: () -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.height(40.dp), contentAlignment = Alignment.Center) {
        IconAction(
            modifier           = Modifier.width(28.dp),
            icon               = Ph.Paperclip,
            contentDescription = "Attach",
            onClick            = {
                if (canAttachImage) open = true else onAttachDocument()
            },
            enabled            = enabled,
            size               = 19.dp,
            tint               = Ink.TertiaryIcon,
            touch              = 32.dp
        )
        InkMenu(expanded = open, onDismissRequest = { open = false }) {
            InkMenuItem("Document", icon = Ph.FileText, onClick = { open = false; onAttachDocument() })
            InkMenuItem("Image", icon = Ph.Image, onClick = { open = false; onAttachImage() })
        }
    }
}

@Composable
private fun AttachmentPreview(image: Uri?, document: String?, onClear: () -> Unit) {
    Row(
        modifier              = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (image != null) {
            AsyncImage(
                model              = image,
                contentDescription = "Attached image",
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.size(48.dp).border(1.dp, Ink.Border)
            )
        } else if (document != null) {
            AttachmentLabel(
                document,
                Modifier
                    .border(1.dp, Ink.Border)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .weight(1f, fill = false)
            )
        }
        IconAction(Ph.X, "Remove attachment", onClear, size = 14.dp, tint = Ink.TertiaryIcon, touch = 32.dp)
    }
}

/**
 * 20dp sine line above the input, drifting left on a 5.5s loop. Nearly flat
 * (×0.12, 45% opacity) when idle; full amplitude while the model streams or the
 * microphone listens.
 */
@Composable
fun SineLine(active: Boolean, modifier: Modifier = Modifier) {
    val amplitude by animateFloatAsState(if (active) 1f else 0.12f, tween(400), label = "sineAmp")
    val opacity by animateFloatAsState(if (active) 1f else 0.45f, tween(400), label = "sineAlpha")
    val phase by rememberInfiniteTransition(label = "sine").animateFloat(
        initialValue  = 0f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(tween(5_500, easing = LinearEasing)),
        label         = "sinePhase"
    )
    val path = remember { Path() }
    Canvas(modifier.fillMaxWidth().height(20.dp)) {
        val w = size.width
        val mid = size.height / 2f
        // Reference path: 8 waves across the width, crest 3.5/20 of the height.
        val wave = w / 8f
        val a = size.height * (3.5f / 20f) * amplitude
        val shift = phase * w
        val step = 1.5.dp.toPx()
        path.reset()
        var x = 0f
        path.moveTo(0f, mid - a * sin(2f * PI.toFloat() * shift / wave))
        while (x < w) {
            x = (x + step).coerceAtMost(w)
            path.lineTo(x, mid - a * sin(2f * PI.toFloat() * (x + shift) / wave))
        }
        drawPath(path, Ink.white(0.4f * opacity), style = Stroke(width = 1.dp.toPx()))
    }
}
