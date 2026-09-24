package com.aipaca.app.ui.chat

import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.aipaca.app.ui.components.IconAction
import com.aipaca.app.ui.components.InkIcon
import com.aipaca.app.ui.components.InkMenu
import com.aipaca.app.ui.components.InkMenuItem
import com.aipaca.app.ui.components.Tracked
import com.aipaca.app.ui.theme.Ink
import com.aipaca.app.ui.theme.InkType
import com.aipaca.app.ui.theme.Ph
import kotlin.math.PI
import kotlin.math.sin

/** Session switches reachable from the composer's modes menu. */
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
 * The composer: a live sine line over a row of modes · attach · input · action.
 *
 * The square action is send (arrow), stop while the model streams or the mic
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
    modes: ComposerModes,
    onSystemPrompt: () -> Unit,
    onToggleThinking: () -> Unit,
    onWebSearch: () -> Unit,
    onOllama: () -> Unit,
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
            ModesButton(modes, onSystemPrompt, onToggleThinking, onWebSearch, onOllama)
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
            SquareButton(
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
    Send(Ph.ArrowUp, "Send message"),
    Stop(Ph.Stop, "Stop generation"),
    StopListening(Ph.Stop, "Stop recording"),
    Mic(Ph.Microphone, "Dictate")
}

/** 40×40dp square: `#FFF` border and glyph when enabled, `.2` / `.55` when not. */
@Composable
private fun SquareButton(action: SquareAction, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .border(1.dp, if (enabled) Ink.White else Ink.Border)
            .clickable(enabled = enabled, onClickLabel = action.label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        InkIcon(
            action.icon,
            16.dp,
            tint               = if (enabled) Ink.White else Ink.DisabledIcon,
            contentDescription = action.label
        )
    }
}

/** Session switches: system prompt, thinking, web search, Ollama. */
@Composable
private fun ModesButton(
    modes: ComposerModes,
    onSystemPrompt: () -> Unit,
    onToggleThinking: () -> Unit,
    onWebSearch: () -> Unit,
    onOllama: () -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.height(40.dp), contentAlignment = Alignment.Center) {
        IconAction(
            icon               = Ph.SlidersHorizontal,
            contentDescription = "Modes",
            onClick            = { open = true },
            size               = 19.dp,
            tint               = if (modes.anyActive) Ink.White else Ink.TertiaryIcon,
            touch              = 32.dp
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
