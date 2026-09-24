package com.aipaca.app.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aipaca.app.EngineState
import com.aipaca.app.engine.ModelHeader
import com.aipaca.app.ui.chat.ChatViewModel
import com.aipaca.app.ui.components.DotGrid
import com.aipaca.app.ui.components.IconAction
import com.aipaca.app.ui.components.InkIcon
import com.aipaca.app.ui.components.StateSquare
import com.aipaca.app.ui.components.Tracked
import com.aipaca.app.ui.theme.Ink
import com.aipaca.app.ui.theme.InkType
import com.aipaca.app.ui.theme.Ph

/** The four top-level screens, in rail and ladder order. */
enum class Screen(val route: String, val railLabel: String, val title: String, val icon: ImageVector) {
    Chat  ("chat",   "Chat", "Chat",   Ph.ChatCircle),
    Memory("memory", "Mem",  "Memory", Ph.Brain),
    Models("models", "Mdl",  "Models", Ph.Cube),
    Server("server", "Srv",  "Server", Ph.HardDrives);

    companion object {
        fun fromRoute(route: String?): Screen = entries.firstOrNull { it.route == route } ?: Chat
    }
}

// ---- Model presence -----------------------------------------------------------

/**
 * What is loaded, as far as the chrome is concerned: drives the Model World,
 * its readout, the status line and composer gating.
 *
 * The world shows the chat model when one is loaded (or loading); with no chat
 * model it shows the speech model, if any. A remote (Ollama) model has no
 * header, so the world stays a sphere.
 */
@Immutable
data class ModelPresence(
    /** A chat model (local or remote) is ready: the composer may send. */
    val chatReady: Boolean,
    /** A local chat model is loading. */
    val chatLoading: Boolean,
    /** The chat model runs on a remote (Ollama) server. */
    val remote: Boolean,
    /** Header the world is built from; null → idle sphere. */
    val worldHeader: ModelHeader?,
    /** A load is in flight for the model the world shows. */
    val worldLoading: Boolean,
    /** The model is generating. */
    val streaming: Boolean,
    /** Microphone is live — the composer wave runs at full amplitude. */
    val listening: Boolean,
    val statusLine: String
)

@Composable
fun rememberModelPresence(chat: ChatViewModel): ModelPresence {
    val loaded         by EngineState.isLoaded.collectAsState()
    val loading        by EngineState.isLoadingModel.collectAsState()
    val header         by EngineState.modelHeader.collectAsState()
    val loadingPath    by EngineState.loadingModelPath.collectAsState()
    val modelPath      by EngineState.modelPath.collectAsState()
    val gpuLayers      by EngineState.gpuLayers.collectAsState()
    val remote         by EngineState.useOllama.collectAsState()
    val remoteName     by EngineState.ollamaModelName.collectAsState()
    val loadError      by EngineState.errorMessage.collectAsState()
    val whisperHeader  by EngineState.whisperHeader.collectAsState()
    val whisperPath    by EngineState.whisperModelPath.collectAsState()
    val whisperLoading by EngineState.isLoadingWhisperModel.collectAsState()
    val generating     by chat.isGenerating.collectAsState()
    val recording      by chat.isRecording.collectAsState()
    val transcribing   by chat.isTranscribing.collectAsState()

    val localActive = !remote && (loaded || loading)
    val speechOnly = !remote && !localActive && (whisperPath != null || whisperLoading)

    val worldHeader = when {
        localActive -> header
        speechOnly  -> whisperHeader
        else        -> null
    }
    val worldLoading = when {
        localActive -> loading
        speechOnly  -> whisperLoading
        else        -> false
    }

    val status = statusLine(
        StatusInputs(
            listening      = recording,
            transcribing   = transcribing,
            streaming      = generating,
            llmLoading     = !remote && loading,
            llmHeader      = header,
            whisperLoading = whisperLoading,
            remoteModel    = if (remote) remoteName.ifBlank { "Ollama" } else null,
            llmLoaded      = !remote && loaded,
            llmName        = displayModelName(modelPath ?: loadingPath, header),
            gpu            = gpuLayers > 0,
            loadFailed     = loadError != null && !loaded,
            whisperLoaded  = whisperPath != null,
            whisperName    = displayModelName(whisperPath, whisperHeader)
        )
    )

    return ModelPresence(
        chatReady    = loaded,
        chatLoading  = !remote && loading,
        remote       = remote,
        worldHeader  = worldHeader,
        worldLoading = worldLoading,
        streaming    = generating,
        listening    = recording,
        statusLine   = status
    )
}

// ---- Rail ---------------------------------------------------------------------

/**
 * Left rail, 66dp. The 3×3 mark with a caret collapses it; items are icon over
 * a tracked label, the active one white with a 1×16dp bar on the left edge.
 * At the bottom: session modes and chat history.
 */
@Composable
fun Rail(
    current: Screen,
    onSelect: (Screen) -> Unit,
    onCollapse: () -> Unit,
    onHistory: () -> Unit,
    modifier: Modifier = Modifier,
    modes: @Composable (iconSize: Dp, touch: Dp) -> Unit = { _, _ -> }
) {
    Column(
        modifier            = modifier
            .width(66.dp)
            .fillMaxHeight()
            .padding(top = 18.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .clickable(onClickLabel = "Collapse navigation", role = Role.Button, onClick = onCollapse)
                .padding(4.dp)
                .semantics { contentDescription = "Collapse navigation" },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DotGrid()
            InkIcon(Ph.CaretLeft, 12.dp, tint = Ink.white(.5f))
        }
        Spacer(Modifier.height(26.dp))

        Screen.entries.forEach { screen ->
            RailItem(screen, selected = screen == current, onClick = { onSelect(screen) })
        }

        Spacer(Modifier.weight(1f))
        modes(18.dp, 44.dp)
        IconAction(
            icon               = Ph.ClockCounterClockwise,
            contentDescription = "Chat history",
            onClick            = onHistory,
            size               = 18.dp,
            tint               = Ink.Micro,
            touch              = 44.dp
        )
    }
}

@Composable
private fun RailItem(screen: Screen, selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) Ink.White else Ink.RailInactive
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { this.selected = selected }
            .clickable(onClickLabel = screen.title, role = Role.Tab, onClick = onClick)
            .padding(vertical = 14.dp)
    ) {
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .size(width = 1.dp, height = 16.dp)
                    .background(Ink.White)
            )
        }
        Column(
            modifier            = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            InkIcon(screen.icon, 20.dp, tint = tint)
            Tracked(screen.railLabel, InkType.RailLabel, color = tint, maxLines = 1)
        }
    }
}

// ---- Status line ------------------------------------------------------------------

/**
 * Top line of the content column: a 4dp square and the model state. With the
 * rail collapsed it also carries the rail handle (left) and the rail's bottom
 * actions — modes and history — on the right.
 */
@Composable
fun StatusLine(
    text: String,
    railCollapsed: Boolean,
    onExpandRail: () -> Unit,
    onHistory: () -> Unit,
    modifier: Modifier = Modifier,
    modes: @Composable (iconSize: Dp, touch: Dp) -> Unit = { _, _ -> }
) {
    Row(
        modifier              = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 20.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (railCollapsed) {
            Row(
                modifier = Modifier
                    .clickable(onClickLabel = "Show navigation", role = Role.Button, onClick = onExpandRail)
                    .padding(vertical = 2.dp)
                    .semantics { contentDescription = "Show navigation" },
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                InkIcon(Ph.List, 16.dp, tint = Ink.Text)
                DotGrid()
            }
        }
        StateSquare()
        Tracked(
            text,
            InkType.Status,
            color    = Ink.Status,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (railCollapsed) {
            // Touch targets keep 32dp but must not make the line taller than its
            // text, or content would shift when the rail is toggled.
            Row(
                modifier          = Modifier.height(14.dp).wrapContentHeight(unbounded = true),
                verticalAlignment = Alignment.CenterVertically
            ) {
                modes(16.dp, 32.dp)
                IconAction(
                    icon               = Ph.ClockCounterClockwise,
                    contentDescription = "Chat history",
                    onClick            = onHistory,
                    size               = 16.dp,
                    tint               = Ink.Micro,
                    touch              = 32.dp
                )
            }
        }
    }
}

// ---- Position ladder ----------------------------------------------------------------

/** Four 3dp squares on the right edge; the current screen's square is lit. */
@Composable
fun PositionLadder(current: Screen, modifier: Modifier = Modifier) {
    Column(
        modifier            = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Screen.entries.forEach { screen ->
            Box(
                Modifier
                    .size(3.dp)
                    .alpha(if (screen == current) 1f else .26f)
                    .background(Ink.White)
            )
        }
    }
}

// ---- Haze ----------------------------------------------------------------------

/**
 * The cool haze at the top of the screen:
 * `radial-gradient(120% 52% at 50% −6%, rgba(150,175,205,.13), rgba(90,110,135,.05) 38%, transparent 72%)`.
 * An elliptical gradient, drawn as a circular one under a vertical scale.
 */
fun Modifier.topHaze(): Modifier = drawWithCache {
    val rx = size.width * 1.20f
    val ry = size.height * 0.52f
    val center = Offset(size.width / 2f, -0.06f * size.height)
    val sy = if (rx > 0f) ry / rx else 1f
    val brush = Brush.radialGradient(
        0f    to Color(150, 175, 205).copy(alpha = .13f),
        0.38f to Color(90, 110, 135).copy(alpha = .05f),
        0.72f to Color.Transparent,
        center = center,
        radius = rx.coerceAtLeast(1f)
    )
    // Pre-scale rectangle that covers the whole box once scaled about the centre.
    val top = center.y + (0f - center.y) / sy
    val bottom = center.y + (size.height - center.y) / sy
    onDrawBehind {
        scale(scaleX = 1f, scaleY = sy, pivot = center) {
            drawRect(brush, topLeft = Offset(0f, top), size = Size(size.width, bottom - top))
        }
    }
}
