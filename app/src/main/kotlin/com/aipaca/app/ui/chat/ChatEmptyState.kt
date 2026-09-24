package com.aipaca.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aipaca.app.ui.components.ChipButton
import com.aipaca.app.ui.components.FadeRule
import com.aipaca.app.ui.components.OutlineButton
import com.aipaca.app.ui.components.Tracked
import com.aipaca.app.ui.components.world.HeaderReadout
import com.aipaca.app.ui.components.world.ModelWorld
import com.aipaca.app.ui.components.world.WorldMotion
import com.aipaca.app.ui.components.world.WorldSize
import com.aipaca.app.ui.shell.ModelPresence
import com.aipaca.app.ui.theme.Brand
import com.aipaca.app.ui.theme.Ink
import com.aipaca.app.ui.theme.InkType
import com.aipaca.app.ui.theme.Ph

/** Below this the world is left out rather than drawn as a speck. */
private val MinWorld = 96.dp

/**
 * The chat empty state: the Model World and its header readout, bottom-aligned
 * above a copy block that says what to do next.
 */
@Composable
fun ChatEmptyState(
    presence: ModelPresence,
    worldMotion: WorldMotion,
    onOpenModels: () -> Unit,
    onSummarise: () -> Unit,
    onRecall: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val viewport = maxHeight
        Box(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            WorldOverCopy(
                viewport = viewport,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 22.dp),
                world = {
                    ModelWorld(
                        header    = presence.worldHeader,
                        loading   = presence.worldLoading,
                        streaming = presence.streaming,
                        motion    = worldMotion,
                        size      = WorldSize
                    )
                },
                readout = {
                    HeaderReadout(
                        header   = presence.worldHeader,
                        idleText = if (presence.remote) "Remote model · no header read" else "No header read · idle"
                    )
                },
                copy = {
                    CopyBlock(
                        presence     = presence,
                        onOpenModels = onOpenModels,
                        onSummarise  = onSummarise,
                        onRecall     = onRecall
                    )
                }
            )
        }
    }
}

/**
 * Stacks world → readout → copy against the bottom of [viewport]. The world
 * gets whatever height is left, up to its natural 224dp, and is dropped below
 * [MinWorld]; if even the readout and copy don't fit, the column grows and the
 * parent scrolls.
 *
 * Spacing from the reference: 4dp above the world, 13dp between world and
 * readout, 14dp between readout and copy.
 */
@Composable
private fun WorldOverCopy(
    viewport: Dp,
    modifier: Modifier = Modifier,
    world: @Composable () -> Unit,
    readout: @Composable () -> Unit,
    copy: @Composable () -> Unit
) {
    Layout(
        contents = listOf(world, readout, copy),
        modifier = modifier
    ) { (worldM, readoutM, copyM), constraints ->
        val width = constraints.maxWidth
        val loose = Constraints(maxWidth = width)
        val copyP = copyM.first().measure(loose)
        val readoutP = readoutM.first().measure(loose)

        val top = 4.dp.roundToPx()
        val gapWorld = 13.dp.roundToPx()
        val gapCopy = 14.dp.roundToPx()
        // The modifier's vertical padding is outside this layout.
        val available = viewport.roundToPx() - 28.dp.roundToPx()

        val free = available - copyP.height - readoutP.height - gapCopy - gapWorld - top
        val worldPx = free.coerceAtMost(WorldSize.roundToPx())
        val worldP = if (worldPx >= MinWorld.roundToPx()) {
            worldM.first().measure(Constraints.fixed(worldPx, worldPx))
        } else null

        val content = copyP.height + gapCopy + readoutP.height +
            (worldP?.let { it.height + gapWorld + top } ?: 0)
        val height = maxOf(available, content)

        layout(width, height) {
            var y = height - copyP.height
            copyP.place(0, y)
            y -= gapCopy + readoutP.height
            readoutP.place(0, y)
            if (worldP != null) {
                y -= gapWorld + worldP.height
                worldP.place((width - worldP.width) / 2, y)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CopyBlock(
    presence: ModelPresence,
    onOpenModels: () -> Unit,
    onSummarise: () -> Unit,
    onRecall: () -> Unit
) {
    Column(
        modifier            = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(15.dp)
    ) {
        when {
            presence.chatReady -> {
                Tracked("Ask me anything", InkType.EmptyTitle, color = Ink.Text)
                FadeRule(fadeAt = 0.70f)
                Text(
                    text     = if (presence.remote) {
                        "Runs on your Ollama server. Nothing leaves your network unless you say so."
                    } else {
                        "Runs on this phone. Nothing leaves it unless you say so."
                    },
                    style    = InkType.Body,
                    color    = Ink.Body,
                    modifier = Modifier.widthIn(max = 252.dp)
                )
                FlowRow(
                    modifier              = Modifier.padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement   = Arrangement.spacedBy(8.dp)
                ) {
                    ChipButton("Summarise a PDF", onClick = onSummarise)
                    ChipButton("What do you recall", onClick = onRecall)
                }
            }
            presence.chatLoading -> {
                Tracked("Loading", InkType.EmptyTitle, color = Ink.Text)
                FadeRule(fadeAt = 0.70f)
                Text(
                    text     = "Header read. The weights are loading — the model unfolds when it is ready.",
                    style    = InkType.Body,
                    color    = Ink.Body,
                    modifier = Modifier.widthIn(max = 268.dp)
                )
            }
            else -> {
                // With only a speech model loaded, the world already shows that model.
                val speechOnly = presence.worldHeader?.arch == "whisper"
                Tracked(if (speechOnly) "Load a chat model" else "Load a model", InkType.EmptyTitle, color = Ink.Text)
                FadeRule(fadeAt = 0.70f)
                Text(
                    text     = "${Brand.NAME} reads the .gguf header and builds the model from it — " +
                        "one ring per block, one point per KV head.",
                    style    = InkType.Body,
                    color    = Ink.Body,
                    modifier = Modifier.widthIn(max = 268.dp)
                )
                OutlineButton(
                    label    = "Open models",
                    icon     = Ph.Cube,
                    onClick  = onOpenModels,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}
