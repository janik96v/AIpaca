package com.aipaca.app.ui.chat

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.aipaca.app.model.ChatMessage
import com.aipaca.app.model.Role as MessageRole
import com.aipaca.app.ui.components.InkIcon
import com.aipaca.app.ui.components.StateSquare
import com.aipaca.app.ui.components.Tracked
import com.aipaca.app.ui.components.hairlineStart
import com.aipaca.app.ui.theme.Brand
import com.aipaca.app.ui.theme.Ink
import com.aipaca.app.ui.theme.InkType
import com.aipaca.app.ui.theme.Ph

/** Messages never run wider than this. */
private val MessageMaxWidth = 268.dp

@Composable
fun MessageList(
    messages: List<ChatMessage>,
    isGenerating: Boolean,
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        state               = listState,
        modifier            = modifier.fillMaxWidth(),
        contentPadding      = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        items(messages, key = { it.id }) { message ->
            val isLast = message.id == messages.lastOrNull()?.id
            when (message.role) {
                MessageRole.USER -> UserMessage(message)
                else             -> AgentMessage(message, streaming = isGenerating && isLast)
            }
        }
    }
}

/** Right-aligned, framed: `YOU` over a 1dp `.2` box. */
@Composable
private fun UserMessage(message: ChatMessage) {
    Column(
        modifier            = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Tracked("You", InkType.Stamp, color = Ink.Meta)
        Column(
            modifier = Modifier
                .widthIn(max = MessageMaxWidth)
                .border(1.dp, Ink.Border)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            message.attachedImageUri?.let { uri ->
                AsyncImage(
                    model              = Uri.parse(uri),
                    contentDescription = "Attached image",
                    contentScale       = ContentScale.Fit,
                    modifier           = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                )
            }
            message.attachedDocumentName?.let { name -> AttachmentLabel(name) }
            val text = message.displayText ?: message.content
            if (text.isNotBlank()) {
                SelectionContainer {
                    Text(text, style = InkType.BodyLoose, color = Ink.Text)
                }
            }
        }
    }
}

/**
 * Left-aligned, unframed: the agent's label over its text, set off by a 1dp
 * `.55` rule on the left.
 */
@Composable
private fun AgentMessage(message: ChatMessage, streaming: Boolean) {
    Column(
        modifier            = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Tracked(Brand.LABEL, InkType.Stamp, color = Ink.White)
        Column(
            modifier = Modifier
                .widthIn(max = MessageMaxWidth)
                .hairlineStart(Ink.AgentRule)
                .padding(start = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (message.thinkingContent.isNotEmpty()) {
                ThinkingBlock(
                    thinking  = message.thinkingContent,
                    streaming = streaming && message.content.isEmpty()
                )
            }
            when {
                message.content.isNotEmpty() -> SelectionContainer {
                    Text(message.content, style = InkType.BodyLoose, color = Ink.AgentBody)
                }
                streaming && message.thinkingContent.isEmpty() -> PendingMark()
            }
        }
    }
}

/** Collapsible reasoning trace; open while it streams. */
@Composable
private fun ThinkingBlock(thinking: String, streaming: Boolean) {
    var expanded by remember { mutableStateOf(streaming) }
    LaunchedEffect(streaming) { if (streaming) expanded = true }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = if (expanded) "Collapse reasoning" else "Expand reasoning",
                role         = Role.Button
            ) { expanded = !expanded }
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tracked(
                if (expanded) "Thinking · tap to collapse" else "Thinking · tap to expand",
                InkType.Stamp,
                color    = Ink.Meta,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            InkIcon(
                if (expanded) Ph.CaretUp else Ph.CaretDown,
                size     = 10.dp,
                tint     = Ink.Meta,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
            Text(thinking, style = InkType.Secondary, color = Ink.Secondary)
        }
    }
}

/** Blinking square while the first token is on its way. */
@Composable
private fun PendingMark() {
    val transition = rememberInfiniteTransition(label = "pending")
    val a by transition.animateFloat(
        initialValue  = 1f,
        targetValue   = 0.2f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label         = "pendingAlpha"
    )
    Box(Modifier.height(22.dp), contentAlignment = Alignment.CenterStart) {
        StateSquare(Modifier.alpha(a), size = 5.dp)
    }
}

/** Tracked file name with a document glyph, used in messages and the composer. */
@Composable
fun AttachmentLabel(name: String, modifier: Modifier = Modifier) {
    Row(
        modifier              = modifier,
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        InkIcon(Ph.FileText, 14.dp, tint = Ink.Meta)
        Tracked(name, InkType.Label, color = Ink.Meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
