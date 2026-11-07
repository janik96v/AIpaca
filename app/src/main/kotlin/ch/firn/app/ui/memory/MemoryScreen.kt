package ch.firn.app.ui.memory

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.firn.app.EngineState
import ch.firn.app.agent.AgentTier
import ch.firn.app.agent.TierInputs
import ch.firn.app.agent.TierPolicy
import ch.firn.app.ui.components.ButtonKind
import ch.firn.app.ui.components.FadeRule
import ch.firn.app.ui.components.InkTextField
import ch.firn.app.ui.components.ScreenTitle
import ch.firn.app.ui.components.SegmentedControl
import ch.firn.app.ui.components.SoftButton
import ch.firn.app.ui.components.ToggleCard
import ch.firn.app.ui.components.Tracked
import ch.firn.app.ui.theme.Brand
import ch.firn.app.ui.theme.Ink
import ch.firn.app.ui.theme.InkType
import ch.firn.app.ui.theme.Ph
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Memory — inspect and correct everything the self-learning loops write.
 *
 * Four surfaces, matching the prompt layers: soul (who the agent is), you
 * (facts about the user), facts (their environment) and sessions (the index of
 * past conversations). Anything a background pass proposes but does not apply
 * on its own shows up as a pending version to approve or reject.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemoryScreen(
    modifier: Modifier = Modifier,
    memoryViewModel: MemoryViewModel = viewModel()
) {
    val state       by memoryViewModel.state.collectAsState()
    val toolCalling by EngineState.toolCallingSupported.collectAsState()
    val contextSize by EngineState.contextSize.collectAsState()

    var editing by rememberSaveable(state.tab) { mutableStateOf(false) }
    // Local editor buffer, reset whenever the stored content changes underneath.
    var draft by remember(state.tab, state.content) { mutableStateOf(state.content) }
    val lines = remember(state.tab, state.content) {
        memoryLines(state.content, sessions = state.tab == MemoryViewModel.Tab.SESSIONS)
    }

    LaunchedEffect(Unit) { memoryViewModel.reload() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        ScreenTitle("Memory")

        // ---- Learning ----------------------------------------------------------
        ToggleCard(
            title           = "Learning",
            meta            = if (state.loopEnabled) {
                "Consolidates while charging · ${state.entriesSinceConsolidation} new " +
                    if (state.entriesSinceConsolidation == 1) "note" else "notes"
            } else {
                "Paused — nothing is written"
            },
            checked         = state.loopEnabled,
            onCheckedChange = memoryViewModel::setLoopEnabled
        )

        // ---- Tabs --------------------------------------------------------------
        SegmentedControl(
            options  = MemoryViewModel.Tab.entries,
            selected = state.tab,
            label    = { it.label },
            onSelect = memoryViewModel::selectTab
        )

        Text(tabHint(state.tab), style = InkType.Secondary, color = Ink.Secondary)

        // ---- Proposed rewrite --------------------------------------------------
        state.pending?.let { pending ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Ink.Border)
                    .padding(start = 14.dp, end = 14.dp, top = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Tracked("Proposed version", InkType.Label, color = Ink.White)
                Text(
                    "A background pass rewrote this file. Nothing changes until you approve it.",
                    style = InkType.Secondary,
                    color = Ink.Secondary
                )
                Text(pending, style = InkType.BodyLoose, color = Ink.MemoryText)
                Row(
                    modifier              = Modifier.padding(bottom = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SoftButton("Approve", onClick = memoryViewModel::approvePending, kind = ButtonKind.Primary, small = true)
                    SoftButton("Reject", onClick = memoryViewModel::rejectPending, small = true)
                }
            }
        }

        // ---- Entries / editor --------------------------------------------------
        if (editing) {
            InkTextField(
                value         = draft,
                onValueChange = { draft = it },
                placeholder   = "Nothing stored yet",
                boxed         = true,
                minLines      = 8,
                textStyle     = InkType.BodyLoose
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement   = Arrangement.spacedBy(8.dp)
            ) {
                SoftButton(
                    "Save",
                    onClick = { memoryViewModel.save(draft); editing = false },
                    kind    = ButtonKind.Primary,
                    enabled = draft != state.content
                )
                SoftButton("Revert", onClick = { draft = state.content }, enabled = draft != state.content)
                if (state.hasBackup) {
                    SoftButton("Undo last change", onClick = memoryViewModel::restoreBackup)
                }
                SoftButton("Close", onClick = { draft = state.content; editing = false })
            }
        } else {
            Column(
                modifier            = Modifier.padding(top = 2.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                if (lines.isEmpty()) {
                    Text("Nothing stored yet.", style = InkType.Secondary, color = Ink.Secondary)
                }
                lines.forEach { line ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (line.stamp.isNotEmpty()) Tracked(line.stamp, InkType.Stamp, color = Ink.Meta)
                        Text(line.text, style = InkType.BodyLoose, color = Ink.MemoryText)
                        FadeRule(Modifier.padding(top = 4.dp), color = Ink.white(.13f), fadeAt = 0.82f)
                    }
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement   = Arrangement.spacedBy(8.dp)
            ) {
                SoftButton("Edit this file", onClick = { editing = true }, icon = Ph.PencilSimple)
                SoftButton("Update now", onClick = memoryViewModel::runConsolidationNow, icon = Ph.ArrowsClockwise)
            }
        }

        state.savedNotice?.let { Tracked(it, InkType.Label, color = Ink.White) }

        Tracked(
            loopFooter(state.lastConsolidationAt, tierLabel(toolCalling, contextSize)),
            InkType.Meta,
            color = Ink.Meta
        )
    }
}

/** Mirrors TierPolicy's decision so the user can see why a turn behaved as it did. */
private fun tierLabel(toolCallingSupported: Boolean, contextSize: Int): String {
    val tier = TierPolicy.select(
        TierInputs(
            toolCallingSupported = toolCallingSupported,
            hasAttachedImage     = false,
            contextSize          = contextSize,
            isRemoteBackend      = EngineState.useOllama.value
        )
    )
    return when (tier) {
        AgentTier.PLAIN    -> "Plain chat"
        AgentTier.ASSISTED -> "Assisted"
        AgentTier.DEEP     -> "Deep"
    }
}

/** `LAST CONSOLIDATED 13 SEP 09:41 · AGENT ASSISTED`. */
private fun loopFooter(lastConsolidationAt: Long, tier: String): String {
    val last = if (lastConsolidationAt <= 0L) "Not consolidated yet"
    else "Last consolidated " + SimpleDateFormat("d MMM HH:mm", Locale.US).format(Date(lastConsolidationAt))
    return "$last · Agent $tier"
}

private fun tabHint(tab: MemoryViewModel.Tab): String = when (tab) {
    MemoryViewModel.Tab.SOUL     -> "Who ${Brand.NAME} is. Proposed changes always wait for you."
    MemoryViewModel.Tab.USER     -> "What ${Brand.NAME} has picked up about you."
    MemoryViewModel.Tab.MEMORY   -> "Technical facts about your projects, tools and conventions."
    MemoryViewModel.Tab.SESSIONS -> "One line per past conversation. Read in full only when relevant."
}
