package com.aipaca.app.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aipaca.app.model.StoredConversation
import com.aipaca.app.ui.components.IconAction
import com.aipaca.app.ui.components.OutlineButton
import com.aipaca.app.ui.components.Tracked
import com.aipaca.app.ui.components.hairlineBottom
import com.aipaca.app.ui.components.hairlineTop
import com.aipaca.app.ui.theme.Ink
import com.aipaca.app.ui.theme.InkType
import com.aipaca.app.ui.theme.Ph
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Chat history as a bottom sheet over the content column: scrim, square sheet
 * at most 78% tall, `+ NEW CHAT`, then one row per stored conversation.
 */
@Composable
fun HistorySheet(
    open: Boolean,
    conversations: List<StoredConversation>,
    currentConversationId: String?,
    onDismiss: () -> Unit,
    onNewChat: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(enabled = open, onBack = onDismiss)

    BoxWithConstraints(modifier.fillMaxSize()) {
        val maxSheet = maxHeight * 0.78f

        AnimatedVisibility(
            visible = open,
            enter   = fadeIn(tween(180)),
            exit    = fadeOut(tween(160))
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Ink.Scrim)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication        = null,
                        onClickLabel      = "Close history",
                        onClick           = onDismiss
                    )
            )
        }

        AnimatedVisibility(
            visible  = open,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter    = slideInVertically(tween(240)) { it } + fadeIn(tween(120)),
            exit     = slideOutVertically(tween(200)) { it } + fadeOut(tween(160))
        ) {
            Column {
                // Upward shadow: 0 −24dp 60dp rgba(0,0,0,.6)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .background(Brush.verticalGradient(0f to Color.Transparent, 1f to Ink.Black.copy(alpha = .6f)))
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxSheet)
                        .background(Ink.Sheet)
                        .hairlineTop(Ink.Border)
                        // Swallow taps so they don't reach the scrim.
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication        = null,
                            onClick           = {}
                        )
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Tracked("History", InkType.SheetTitle, modifier = Modifier.weight(1f))
                        IconAction(
                            icon               = Ph.X,
                            contentDescription = "Close history",
                            onClick            = onDismiss,
                            size               = 16.dp,
                            tint               = Ink.white(.5f),
                            touch              = 32.dp
                        )
                    }
                    OutlineButton(
                        label    = "New chat",
                        icon     = Ph.Plus,
                        iconSize = 14.dp,
                        padding  = PaddingValues(horizontal = 15.dp, vertical = 13.dp),
                        onClick  = onNewChat
                    )
                    if (conversations.isEmpty()) {
                        Text(
                            "No saved conversations yet.",
                            style = InkType.Secondary,
                            color = Ink.Secondary
                        )
                    } else {
                        LazyColumn(Modifier.weight(1f, fill = false)) {
                            items(conversations, key = { it.id }) { conversation ->
                                HistoryRow(
                                    conversation = conversation,
                                    isCurrent    = conversation.id == currentConversationId,
                                    onSelect     = { onSelect(conversation.id) },
                                    onDelete     = { onDelete(conversation.id) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    conversation: StoredConversation,
    isCurrent: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hairlineBottom(Ink.Divider)
            .clickable(onClickLabel = "Open conversation", role = Role.Button, onClick = onSelect)
            .padding(vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier            = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(
                text     = conversation.title,
                style    = InkType.NameLoose,
                color    = Ink.Text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val meta = buildString {
                append("${conversation.messages.size} lines · ")
                append(SimpleDateFormat("MMM d HH:mm", Locale.US).format(Date(conversation.updatedAt)))
                if (isCurrent) append(" · open")
            }
            Tracked(meta, InkType.Meta, color = Ink.Meta, maxLines = 1)
        }
        IconAction(
            icon               = Ph.Trash,
            contentDescription = "Delete conversation",
            onClick            = onDelete,
            size               = 16.dp,
            tint               = Ink.DisabledIcon
        )
    }
}
