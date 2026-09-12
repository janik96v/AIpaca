package com.aipaca.app.ui.memory

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aipaca.app.EngineState
import com.aipaca.app.agent.AgentTier
import com.aipaca.app.ui.components.ChipTone
import com.aipaca.app.ui.components.EditorialDivider
import com.aipaca.app.ui.components.EditorialMasthead
import com.aipaca.app.ui.components.EditorialSectionMark
import com.aipaca.app.ui.components.MonoLabel
import com.aipaca.app.ui.components.MonoLabelTone
import com.aipaca.app.ui.components.StatusChip
import com.aipaca.app.ui.theme.AIpacaTheme
import com.aipaca.app.ui.theme.AlpacaColors
import com.aipaca.app.ui.theme.AlpacaType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Memory screen — inspect and correct everything the self-learning loops write.
 *
 * Four surfaces, matching the prompt layers: soul (who the agent is), user (facts
 * about the user), facts (their environment), and sessions (the index of past
 * conversations). Anything a background pass proposes but does not apply on its own
 * shows up here as a pending version to approve or reject.
 */
@Composable
fun MemoryScreen(
    modifier: Modifier = Modifier,
    memoryViewModel: MemoryViewModel = viewModel()
) {
    val state by memoryViewModel.state.collectAsState()
    val toolCalling by EngineState.toolCallingSupported.collectAsState()
    val contextSize by EngineState.contextSize.collectAsState()
    val scrollState = rememberScrollState()

    // Local editor buffer, reset whenever the stored content changes underneath.
    var draft by remember(state.tab, state.content) { mutableStateOf(state.content) }

    LaunchedEffect(Unit) { memoryViewModel.reload() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AlpacaColors.Surface.Canvas)
            .verticalScroll(scrollState)
    ) {
        EditorialMasthead(
            title = "Memory",
            meta  = "What AIpaca remembers about you"
        )
        EditorialDivider()

        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {

            // ---- Status ----------------------------------------------------
            EditorialSectionMark("LEARNING LOOP")
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusChip(
                    text = if (state.loopEnabled) "Learning on" else "Learning off",
                    tone = if (state.loopEnabled) ChipTone.Success else ChipTone.Neutral
                )
                StatusChip(
                    text = tierLabel(toolCalling, contextSize),
                    tone = ChipTone.Accent
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text  = statusLine(state.lastConsolidationAt, state.entriesSinceConsolidation),
                style = AlpacaType.BodySm,
                color = AlpacaColors.Text.Muted
            )

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { memoryViewModel.runConsolidationNow() },
                    shape   = RoundedCornerShape(6.dp),
                    colors  = ButtonDefaults.buttonColors(
                        containerColor = AlpacaColors.Accent.Primary,
                        contentColor   = AlpacaColors.Text.OnAccent
                    )
                ) {
                    Text("Update memory now", style = AlpacaType.LabelLg)
                }
                TextButton(onClick = { memoryViewModel.setLoopEnabled(!state.loopEnabled) }) {
                    Text(
                        if (state.loopEnabled) "Turn learning off" else "Turn learning on",
                        style = AlpacaType.LabelLg,
                        color = AlpacaColors.Text.Muted
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            EditorialDivider()
            Spacer(Modifier.height(16.dp))

            // ---- Tabs ------------------------------------------------------
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MemoryViewModel.Tab.entries.forEach { tab ->
                    val selected = tab == state.tab
                    Text(
                        text     = tab.label,
                        style    = AlpacaType.LabelLg,
                        color    = if (selected) AlpacaColors.Accent.Primary else AlpacaColors.Text.Muted,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { memoryViewModel.selectTab(tab) }
                            .padding(vertical = 4.dp, horizontal = 2.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text  = tabHint(state.tab),
                style = AlpacaType.BodySm,
                color = AlpacaColors.Text.Subtle
            )

            // ---- Pending proposal ------------------------------------------
            val pending = state.pending
            if (pending != null) {
                Spacer(Modifier.height(16.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(AlpacaColors.Surface.Card)
                        .padding(12.dp)
                ) {
                    MonoLabel("PROPOSED VERSION", tone = MonoLabelTone.Warning)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "A background pass rewrote this file. Nothing changed until you approve it.",
                        style = AlpacaType.BodySm,
                        color = AlpacaColors.Text.Muted
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(pending, style = AlpacaType.MonoBody, color = AlpacaColors.Text.Body)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { memoryViewModel.approvePending() }) {
                            Text("Approve", style = AlpacaType.LabelLg, color = AlpacaColors.State.Success)
                        }
                        TextButton(onClick = { memoryViewModel.rejectPending() }) {
                            Text("Reject", style = AlpacaType.LabelLg, color = AlpacaColors.State.Error)
                        }
                    }
                }
            }

            // ---- Editor ----------------------------------------------------
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value         = draft,
                onValueChange = { draft = it },
                modifier      = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp),
                placeholder   = { Text("Nothing stored yet", style = AlpacaType.BodyMd) },
                textStyle     = AlpacaType.MonoBody.copy(color = AlpacaColors.Text.Primary),
                shape         = RoundedCornerShape(6.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor          = AlpacaColors.Text.Primary,
                    unfocusedTextColor        = AlpacaColors.Text.Primary,
                    cursorColor               = AlpacaColors.Accent.Primary,
                    focusedBorderColor        = AlpacaColors.Accent.Primary,
                    unfocusedBorderColor      = AlpacaColors.Line.Hairline,
                    focusedContainerColor     = AlpacaColors.Surface.Elevated,
                    unfocusedContainerColor   = AlpacaColors.Surface.Elevated,
                    focusedPlaceholderColor   = AlpacaColors.Text.Subtle,
                    unfocusedPlaceholderColor = AlpacaColors.Text.Subtle
                )
            )

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { memoryViewModel.save(draft) },
                    enabled = draft != state.content,
                    shape   = RoundedCornerShape(6.dp),
                    colors  = ButtonDefaults.buttonColors(
                        containerColor         = AlpacaColors.Accent.Primary,
                        contentColor           = AlpacaColors.Text.OnAccent,
                        disabledContainerColor = AlpacaColors.Surface.Elevated,
                        disabledContentColor   = AlpacaColors.Text.Subtle
                    )
                ) {
                    Text("Save", style = AlpacaType.LabelLg)
                }
                TextButton(onClick = { draft = state.content }, enabled = draft != state.content) {
                    Text("Revert", style = AlpacaType.LabelLg, color = AlpacaColors.Text.Muted)
                }
                if (state.hasBackup) {
                    TextButton(onClick = { memoryViewModel.restoreBackup() }) {
                        Text("Undo last change", style = AlpacaType.LabelLg, color = AlpacaColors.Text.Muted)
                    }
                }
            }

            val notice = state.savedNotice
            if (notice != null) {
                Spacer(Modifier.height(8.dp))
                MonoLabel(notice.uppercase(), tone = MonoLabelTone.Success)
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

/** Mirrors TierPolicy's decision so the user can see why a turn behaved as it did. */
private fun tierLabel(toolCallingSupported: Boolean, contextSize: Int): String {
    val tier = com.aipaca.app.agent.TierPolicy.select(
        com.aipaca.app.agent.TierInputs(
            toolCallingSupported = toolCallingSupported,
            hasAttachedImage = false,
            contextSize = contextSize,
            isRemoteBackend = EngineState.useOllama.value
        )
    )
    return when (tier) {
        AgentTier.PLAIN -> "Plain chat"
        AgentTier.ASSISTED -> "Assisted"
        AgentTier.DEEP -> "Deep"
    }
}

private fun statusLine(lastConsolidationAt: Long, entriesSince: Int): String {
    val last = if (lastConsolidationAt <= 0L) {
        "never run yet"
    } else {
        "last run " + SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(lastConsolidationAt))
    }
    return "Memory consolidation: $last · $entriesSince new entries since then. " +
        "Runs on its own while charging and idle."
}

private fun tabHint(tab: MemoryViewModel.Tab): String = when (tab) {
    MemoryViewModel.Tab.SOUL ->
        "Who AIpaca is and what you expect of it. Changes proposed here always need your approval."
    MemoryViewModel.Tab.USER ->
        "What AIpaca has learned about you from your conversations."
    MemoryViewModel.Tab.MEMORY ->
        "Technical facts about your projects, tools and conventions."
    MemoryViewModel.Tab.SESSIONS ->
        "One line per past conversation. AIpaca reads a full one only when a line looks relevant."
}

@Preview(showBackground = true, backgroundColor = 0xFF14140F)
@Composable
private fun MemoryScreenPreview() {
    AIpacaTheme {
        MemoryScreen()
    }
}
