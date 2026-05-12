package com.aipaca.app.ui.chat

import android.app.Application
import androidx.compose.ui.res.painterResource
import com.aipaca.app.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aipaca.app.EngineState
import com.aipaca.app.data.ChatConversationStore
import com.aipaca.app.engine.ChatTurn
import com.aipaca.app.engine.GenerateParams
import com.aipaca.app.model.ChatMessage
import com.aipaca.app.model.Role
import com.aipaca.app.model.StoredConversation
import com.aipaca.app.ui.components.ModelPickerButton
import com.aipaca.app.ui.theme.AIpacaTheme
import com.aipaca.app.ui.theme.RetroCliColors
import com.aipaca.app.ui.theme.TerminalPanel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

// ---- Think-tag stream parser ------------------------------------------------

data class ThinkParseResult(val content: String, val thinking: String)

class ThinkTagParser {
    // Supported tag pairs: standard <think>/</ think> and Gemma 4 <|channel>thought\n / <channel|>
    private data class TagPair(val open: String, val close: String)
    private val tagPairs = listOf(
        TagPair("<think>", "</think>"),
        TagPair("<|channel>thought\n", "<channel|>")
    )

    private var insideThink = false
    private var activeClose: String? = null  // which close tag to look for
    private var buffer = ""

    private fun couldBePartialTag(text: String, tag: String): Boolean {
        for (i in 1 until tag.length) {
            if (text.endsWith(tag.substring(0, i))) return true
        }
        return false
    }

    private fun couldBeAnyPartialOpen(text: String): Boolean {
        return tagPairs.any { couldBePartialTag(text, it.open) }
    }

    fun feed(token: String): ThinkParseResult {
        buffer += token
        val contentParts = StringBuilder()
        val thinkParts = StringBuilder()

        while (buffer.isNotEmpty()) {
            if (insideThink) {
                val closeTag = activeClose ?: break
                val idx = buffer.indexOf(closeTag)
                if (idx >= 0) {
                    thinkParts.append(buffer.substring(0, idx))
                    buffer = buffer.substring(idx + closeTag.length)
                    insideThink = false
                    activeClose = null
                } else if (couldBePartialTag(buffer, closeTag)) {
                    break
                } else {
                    thinkParts.append(buffer)
                    buffer = ""
                }
            } else {
                // Try to find any open tag
                var bestIdx = -1
                var bestPair: TagPair? = null
                for (pair in tagPairs) {
                    val idx = buffer.indexOf(pair.open)
                    if (idx >= 0 && (bestIdx < 0 || idx < bestIdx)) {
                        bestIdx = idx
                        bestPair = pair
                    }
                }
                if (bestPair != null && bestIdx >= 0) {
                    contentParts.append(buffer.substring(0, bestIdx))
                    buffer = buffer.substring(bestIdx + bestPair.open.length)
                    insideThink = true
                    activeClose = bestPair.close
                } else if (couldBeAnyPartialOpen(buffer)) {
                    break
                } else {
                    contentParts.append(buffer)
                    buffer = ""
                }
            }
        }
        return ThinkParseResult(contentParts.toString(), thinkParts.toString())
    }
}

// ---- ViewModel --------------------------------------------------------------

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val conversationStore = ChatConversationStore(application)
    private var activeConversationId: String? = null

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _conversations = MutableStateFlow<List<StoredConversation>>(emptyList())
    val conversations: StateFlow<List<StoredConversation>> = _conversations.asStateFlow()

    private val _activeConversationId = MutableStateFlow<String?>(null)
    val currentConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

    private val _systemPrompt = MutableStateFlow("")
    val systemPrompt: StateFlow<String> = _systemPrompt.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _thinkingEnabled = MutableStateFlow(true)
    val thinkingEnabled: StateFlow<Boolean> = _thinkingEnabled.asStateFlow()

    private var generationJob: Job? = null

    fun toggleThinking() {
        _thinkingEnabled.value = !_thinkingEnabled.value
    }

    init {
        val storedConversations = conversationStore.loadConversations()
        _conversations.value = storedConversations
        storedConversations.firstOrNull()?.let { conversation ->
            activeConversationId = conversation.id
            _activeConversationId.value = conversation.id
            _messages.value = conversation.messages
            _systemPrompt.value = conversation.systemPrompt
        }
    }

    fun sendMessage(userText: String) {
        if (userText.isBlank()) return
        if (activeConversationId == null) {
            activeConversationId = UUID.randomUUID().toString()
            _activeConversationId.value = activeConversationId
        }

        val userMsg = ChatMessage(role = Role.USER, content = userText.trim())
        val assistantMsg = ChatMessage(role = Role.ASSISTANT, content = "")

        _messages.value = _messages.value + userMsg + assistantMsg
        persistCurrentConversation()
        _isGenerating.value = true

        generationJob = viewModelScope.launch {
            try {
                val turns = buildTurns(_messages.value.dropLast(1))
                val thinkEnabled = _thinkingEnabled.value

                EngineState.engine.generateChat(turns, GenerateParams(thinkingEnabled = thinkEnabled))
                    .collect { chunk ->
                        val current = _messages.value
                        if (current.isNotEmpty()) {
                            val last = current.last()
                            val newContent = last.content + chunk.content
                            val newThinking = if (thinkEnabled)
                                last.thinkingContent + chunk.thinking
                            else
                                last.thinkingContent
                            _messages.value = current.dropLast(1) +
                                last.copy(content = newContent, thinkingContent = newThinking)
                        }
                    }
            } finally {
                _isGenerating.value = false
                persistCurrentConversation()
            }
        }
    }

    fun stopGeneration() {
        EngineState.engine.stopGeneration()
        generationJob?.cancel()
        _isGenerating.value = false
    }

    fun updateSystemPrompt(text: String) {
        _systemPrompt.value = text
        if (activeConversationId == null) {
            activeConversationId = UUID.randomUUID().toString()
            _activeConversationId.value = activeConversationId
        }
        persistCurrentConversation()
    }

    fun clearChat() {
        stopGeneration()
        _messages.value = emptyList()
        _systemPrompt.value = ""
        activeConversationId = null
        _activeConversationId.value = null
    }

    fun selectConversation(conversationId: String) {
        stopGeneration()
        val conversation = _conversations.value.firstOrNull { it.id == conversationId } ?: return
        activeConversationId = conversation.id
        _activeConversationId.value = conversation.id
        _messages.value = conversation.messages
        _systemPrompt.value = conversation.systemPrompt
    }

    fun deleteConversation(conversationId: String) {
        if (activeConversationId == conversationId) {
            stopGeneration()
        }
        _conversations.value = conversationStore.delete(conversationId)
        if (activeConversationId == conversationId) {
            activeConversationId = null
            _activeConversationId.value = null
            _messages.value = emptyList()
        }
    }

    private fun buildTurns(messages: List<ChatMessage>): List<ChatTurn> {
        val turns = messages.mapNotNull { msg ->
            if (msg.content.isBlank()) return@mapNotNull null
            val role = when (msg.role) {
                Role.USER      -> "user"
                Role.ASSISTANT -> "assistant"
                Role.SYSTEM    -> "system"
            }
            ChatTurn(role = role, content = msg.content)
        }
        val prompt = _systemPrompt.value
        return if (prompt.isNotBlank()) {
            listOf(ChatTurn(role = "system", content = prompt)) + turns
        } else {
            turns
        }
    }

    private fun persistCurrentConversation() {
        val conversationId = activeConversationId ?: return
        val currentMessages = _messages.value
        if (currentMessages.isEmpty() && _systemPrompt.value.isBlank()) return

        val title = currentMessages
            .firstOrNull { it.role == Role.USER }
            ?.content
            ?.replace(Regex("\\s+"), " ")
            ?.take(42)
            ?.ifBlank { null }
            ?: "Untitled chat"

        _conversations.value = conversationStore.upsert(
            StoredConversation(
                id = conversationId,
                title = title,
                messages = currentMessages,
                updatedAt = System.currentTimeMillis(),
                systemPrompt = _systemPrompt.value
            )
        )
    }
}

// ---- Screen -----------------------------------------------------------------

@Composable
fun ChatScreen(
    modifier: Modifier = Modifier,
    chatViewModel: ChatViewModel = viewModel()
) {
    val messages      by chatViewModel.messages.collectAsState()
    val conversations by chatViewModel.conversations.collectAsState()
    val currentConversationId by chatViewModel.currentConversationId.collectAsState()
    val systemPrompt  by chatViewModel.systemPrompt.collectAsState()
    val isGenerating  by chatViewModel.isGenerating.collectAsState()
    val thinkingEnabled by chatViewModel.thinkingEnabled.collectAsState()
    val isLoaded  by EngineState.isLoaded.collectAsState()
    val isLoadingModel by EngineState.isLoadingModel.collectAsState()
    val modelPath by EngineState.modelPath.collectAsState()
    val gpuLayers by EngineState.gpuLayers.collectAsState()
    val modelInfo by EngineState.modelInfo.collectAsState()

    val listState      = rememberLazyListState()
    val snackbarState  = remember { SnackbarHostState() }
    val drawerState    = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope          = rememberCoroutineScope()
    var inputText      by remember { mutableStateOf("") }
    var showSystemPromptDialog by remember { mutableStateOf(false) }
    var editingSystemPrompt by remember(systemPrompt) { mutableStateOf(systemPrompt) }

    // Auto-scroll to bottom when messages change
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Also scroll when the last message content changes (streaming)
    val lastContent = messages.lastOrNull()?.content
    LaunchedEffect(lastContent) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    LaunchedEffect(drawerState) {
        ChatHistoryBus.openRequests.collect {
            drawerState.open()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ChatHistoryDrawer(
                conversations = conversations,
                currentConversationId = currentConversationId,
                onNewChat = {
                    chatViewModel.clearChat()
                    scope.launch { drawerState.close() }
                },
                onSelectConversation = { conversationId ->
                    chatViewModel.selectConversation(conversationId)
                    scope.launch { drawerState.close() }
                },
                onDeleteConversation = { conversationId ->
                    chatViewModel.deleteConversation(conversationId)
                }
            )
        }
    ) {
        Scaffold(
            modifier    = modifier,
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarState) },
            bottomBar   = {
                ChatInputBar(
                    text         = inputText,
                    onTextChange = { inputText = it },
                    isGenerating = isGenerating,
                    supportsThinking = modelInfo.supportsThinking,
                    thinkingEnabled  = thinkingEnabled,
                    onThinkingToggle = { chatViewModel.toggleThinking() },
                    systemPrompt = systemPrompt,
                    onSystemPromptClick = { showSystemPromptDialog = true },
                    onSend       = {
                        if (!isLoaded) {
                            scope.launch {
                                snackbarState.showSnackbar("Please load a model first")
                            }
                        } else if (inputText.isNotBlank()) {
                            chatViewModel.sendMessage(inputText)
                            inputText = ""
                        }
                    },
                    onStop       = { chatViewModel.stopGeneration() }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                ChatHeaderBar(
                    modelPath    = modelPath,
                    isLoaded     = isLoaded,
                    isLoadingModel = isLoadingModel,
                    gpuLayers    = gpuLayers,
                    onUnload     = { EngineState.unload() },
                    onModelSelected = { path ->
                        EngineState.scope.launch { EngineState.loadModel(path) }
                    }
                )

                if (messages.isEmpty()) {
                    EmptyChatPlaceholder(
                        isLoaded    = isLoaded,
                        isLoadingModel = isLoadingModel,
                        modifier    = Modifier.fillMaxSize()
                    )
                } else {
                    LazyColumn(
                        state           = listState,
                        modifier        = Modifier.fillMaxSize(),
                        contentPadding  = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(messages, key = { it.id }) { message ->
                            val isLastAssistant = message.id == messages.lastOrNull()?.id &&
                                message.role == Role.ASSISTANT
                            MessageBubble(
                                message     = message,
                                isStreaming = isGenerating && isLastAssistant
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSystemPromptDialog) {
        AlertDialog(
            onDismissRequest = {
                chatViewModel.updateSystemPrompt(editingSystemPrompt)
                showSystemPromptDialog = false
            },
            containerColor = RetroCliColors.Void,
            titleContentColor = RetroCliColors.Purple,
            title = {
                Text(
                    text = "> SYSTEM_PROMPT",
                    style = MaterialTheme.typography.titleSmall,
                    color = RetroCliColors.Purple
                )
            },
            text = {
                OutlinedTextField(
                    value = editingSystemPrompt,
                    onValueChange = { editingSystemPrompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("> set system instructions...") },
                    minLines = 3,
                    maxLines = 8,
                    textStyle = MaterialTheme.typography.bodySmall,
                    shape = RoundedCornerShape(4.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = RetroCliColors.Text,
                        unfocusedTextColor = RetroCliColors.Text,
                        cursorColor = RetroCliColors.Cyan,
                        focusedBorderColor = RetroCliColors.Purple,
                        unfocusedBorderColor = RetroCliColors.Purple.copy(alpha = 0.5f),
                        focusedPlaceholderColor = RetroCliColors.Muted,
                        unfocusedPlaceholderColor = RetroCliColors.Muted
                    )
                )
            },
            dismissButton = {
                TextButton(onClick = {
                    editingSystemPrompt = ""
                    chatViewModel.updateSystemPrompt("")
                    showSystemPromptDialog = false
                }) {
                    Text("CLEAR", color = RetroCliColors.Error)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    chatViewModel.updateSystemPrompt(editingSystemPrompt)
                    showSystemPromptDialog = false
                }) {
                    Text("DONE", color = RetroCliColors.Cyan)
                }
            }
        )
    }
}

// ---- Sub-composables --------------------------------------------------------

@Composable
private fun ChatHeaderBar(
    modelPath: String?,
    isLoaded: Boolean,
    isLoadingModel: Boolean,
    gpuLayers: Int,
    onUnload: () -> Unit,
    onModelSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    TerminalPanel(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        title = "SESSION",
        accent = if (isLoaded) RetroCliColors.Cyan else RetroCliColors.Magenta
    ) {
        Row(
            modifier            = Modifier
                .fillMaxWidth(),
            verticalAlignment   = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            if (isLoadingModel) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = RetroCliColors.Magenta,
                        strokeWidth = 2.dp
                    )
                    Column {
                        Text(
                            text = "STATUS: LOADING_MODEL",
                            style = MaterialTheme.typography.labelSmall,
                            color = RetroCliColors.Warning
                        )
                        Text(
                            text = "BACKGROUND PROCESS ACTIVE",
                            style = MaterialTheme.typography.bodySmall,
                            color = RetroCliColors.Muted
                        )
                    }
                }
            } else if (isLoaded && modelPath != null) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text  = "STATUS: ONLINE",
                        style = MaterialTheme.typography.labelSmall,
                        color = RetroCliColors.Success
                    )
                    Text(
                        text  = "MODEL: ${modelPath.substringAfterLast('/')}",
                        style = MaterialTheme.typography.bodySmall,
                        color = RetroCliColors.Cyan,
                        maxLines = 1
                    )
                    val (badgeText, badgeColor) = when {
                        gpuLayers > 0  -> "BACKEND: GPU / $gpuLayers LAYERS" to RetroCliColors.Magenta
                        gpuLayers == 0 -> "BACKEND: CPU FALLBACK" to RetroCliColors.Warning
                        else           -> null to null
                    }
                    if (badgeText != null && badgeColor != null) {
                        Text(
                            text  = badgeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = badgeColor
                        )
                    }
                }
            } else {
                Text(
                    text     = "STATUS: WAITING_FOR_MODEL",
                    style    = MaterialTheme.typography.bodySmall,
                    color    = RetroCliColors.Muted,
                    modifier = Modifier.weight(1f)
                )
            }

            Row {
                if (isLoaded) {
                    Button(
                        onClick  = onUnload,
                        modifier = Modifier.height(36.dp),
                        shape    = RoundedCornerShape(4.dp),
                        colors   = ButtonDefaults.buttonColors(
                            containerColor = RetroCliColors.Error,
                            contentColor   = RetroCliColors.Void
                        )
                    ) {
                        Icon(
                            imageVector        = Icons.Filled.Close,
                            contentDescription = null,
                            modifier           = Modifier.size(16.dp)
                        )
                        Text(
                            text  = " UNLOAD_MODEL",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                } else {
                    ModelPickerButton(
                        onModelSelected = onModelSelected,
                        modifier        = Modifier.height(36.dp),
                        isLoading       = isLoadingModel
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyChatPlaceholder(
    isLoaded: Boolean,
    isLoadingModel: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier         = modifier,
        contentAlignment = Alignment.Center
    ) {
        TerminalPanel(
            modifier = Modifier
                .padding(28.dp)
                .fillMaxWidth(),
            title = "BOOT",
            accent = RetroCliColors.Magenta
        ) {
            Text(
                text  = "AIpaca TERMINAL",
                style = MaterialTheme.typography.titleLarge,
                color = RetroCliColors.Cyan
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text  = when {
                    isLoadingModel -> "> LOADING_MODEL. PLEASE WAIT."
                    isLoaded -> "> READY. TYPE A PROMPT."
                    else -> "> LOAD_MODEL REQUIRED."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (isLoaded) RetroCliColors.Success else RetroCliColors.Warning
            )
            Text(
                text = "> LOCAL INFERENCE / OFFLINE CHAT",
                style = MaterialTheme.typography.bodySmall,
                color = RetroCliColors.Muted
            )
        }
    }
}

@Composable
private fun ChatHistoryDrawer(
    conversations: List<StoredConversation>,
    currentConversationId: String?,
    onNewChat: () -> Unit,
    onSelectConversation: (String) -> Unit,
    onDeleteConversation: (String) -> Unit
) {
    ModalDrawerSheet(
        modifier = Modifier.width(320.dp),
        drawerContainerColor = RetroCliColors.Void,
        drawerContentColor = RetroCliColors.Text
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TerminalPanel(
                modifier = Modifier.fillMaxWidth(),
                title = "HISTORY",
                accent = RetroCliColors.Magenta
            ) {
                Text(
                    text = "STORED_CONVERSATIONS",
                    style = MaterialTheme.typography.titleSmall,
                    color = RetroCliColors.Cyan
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onNewChat,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RetroCliColors.Cyan,
                        contentColor = RetroCliColors.Void
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null
                    )
                    Text(
                        text = " NEW_CHAT",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            if (conversations.isEmpty()) {
                TerminalPanel(
                    modifier = Modifier.fillMaxWidth(),
                    title = "EMPTY",
                    accent = RetroCliColors.Purple
                ) {
                    Text(
                        text = "> No saved conversations yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = RetroCliColors.Muted
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(conversations, key = { it.id }) { conversation ->
                        ConversationHistoryRow(
                            conversation = conversation,
                            isSelected = conversation.id == currentConversationId,
                            onSelect = { onSelectConversation(conversation.id) },
                            onDelete = { onDeleteConversation(conversation.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationHistoryRow(
    conversation: StoredConversation,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    TerminalPanel(
        modifier = Modifier.fillMaxWidth(),
        title = if (isSelected) "ACTIVE" else "CHAT",
        accent = if (isSelected) RetroCliColors.Cyan else RetroCliColors.Purple
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TextButton(
                onClick = onSelect,
                modifier = Modifier.weight(1f)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = conversation.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isSelected) RetroCliColors.Cyan else RetroCliColors.Text,
                        maxLines = 2
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${conversation.messages.size} lines · ${formatConversationTime(conversation.updatedAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = RetroCliColors.Muted
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete conversation",
                    tint = RetroCliColors.Error
                )
            }
        }
    }
}

private fun formatConversationTime(timestamp: Long): String {
    return SimpleDateFormat("MMM d HH:mm", Locale.getDefault()).format(Date(timestamp))
}

@Composable
private fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    isGenerating: Boolean,
    supportsThinking: Boolean = false,
    thinkingEnabled: Boolean = false,
    onThinkingToggle: () -> Unit = {},
    systemPrompt: String = "",
    onSystemPromptClick: () -> Unit = {},
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = RetroCliColors.Void,
        contentColor = RetroCliColors.Text,
        border = BorderStroke(1.dp, RetroCliColors.Magenta.copy(alpha = 0.55f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Top row: text input
            OutlinedTextField(
                value         = text,
                onValueChange = onTextChange,
                modifier      = Modifier.fillMaxWidth(),
                placeholder   = { Text("> enter prompt") },
                enabled       = !isGenerating,
                maxLines      = 4,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction      = ImeAction.Send
                ),
                keyboardActions = KeyboardActions(
                    onSend = { onSend() }
                ),
                shape = RoundedCornerShape(4.dp),
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = RetroCliColors.Text,
                    unfocusedTextColor = RetroCliColors.Text,
                    disabledTextColor = RetroCliColors.Muted,
                    cursorColor = RetroCliColors.Cyan,
                    focusedBorderColor = RetroCliColors.Cyan,
                    unfocusedBorderColor = RetroCliColors.Purple,
                    disabledBorderColor = RetroCliColors.Purple.copy(alpha = 0.45f),
                    focusedPlaceholderColor = RetroCliColors.Muted,
                    unfocusedPlaceholderColor = RetroCliColors.Muted
                )
            )

            // Bottom row: sys prompt + think toggle (left) + send/stop (right)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // System prompt button (always visible)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { onSystemPromptClick() }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .alpha(if (systemPrompt.isNotBlank()) 1f else 0.35f)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_robot),
                            contentDescription = "System prompt",
                            modifier = Modifier.size(20.dp),
                            tint = RetroCliColors.Purple
                        )
                        Text(
                            text = "Sys",
                            style = MaterialTheme.typography.labelSmall,
                            color = RetroCliColors.Purple
                        )
                    }

                    // Thinking toggle (conditional)
                    if (supportsThinking) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { onThinkingToggle() }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                .alpha(if (thinkingEnabled) 1f else 0.35f)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_atom),
                                contentDescription = "Toggle thinking",
                                modifier = Modifier.size(20.dp),
                                tint = RetroCliColors.Cyan
                            )
                            Text(
                                text = "Think",
                                style = MaterialTheme.typography.labelSmall,
                                color = RetroCliColors.Cyan
                            )
                        }
                    }
                }

                if (isGenerating) {
                    Button(
                        onClick  = onStop,
                        colors   = ButtonDefaults.buttonColors(
                            containerColor = RetroCliColors.Error,
                            contentColor = RetroCliColors.Void
                        )
                    ) {
                        Icon(
                            imageVector        = Icons.Filled.Stop,
                            contentDescription = "Stop generation"
                        )
                    }
                } else {
                    Button(
                        onClick  = onSend,
                        enabled  = text.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = RetroCliColors.Cyan,
                            contentColor = RetroCliColors.Void,
                            disabledContainerColor = RetroCliColors.Purple.copy(alpha = 0.35f),
                            disabledContentColor = RetroCliColors.Muted
                        )
                    ) {
                        Icon(
                            imageVector        = Icons.Filled.Send,
                            contentDescription = "Send message"
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MessageBubble(
    message: ChatMessage,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier
) {
    val isUser = message.role == Role.USER
    val accent = if (isUser) RetroCliColors.Magenta else RetroCliColors.Cyan

    val infiniteTransition = rememberInfiniteTransition(label = "cursor")
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue    = 1f,
        targetValue     = 0f,
        animationSpec   = InfiniteRepeatableSpec(
            animation  = tween(durationMillis = 500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label           = "cursorAlpha"
    )

    Row(
        modifier            = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(
                    RoundedCornerShape(
                        topStart = 4.dp,
                        topEnd = 4.dp,
                        bottomStart = 4.dp,
                        bottomEnd = 4.dp
                    )
                )
                .background(
                    if (isUser) RetroCliColors.TerminalSoft else RetroCliColors.Terminal
                )
                .border(1.dp, accent.copy(alpha = 0.72f), RoundedCornerShape(4.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            if (isStreaming && message.content.isEmpty() && message.thinkingContent.isEmpty()) {
                CircularProgressIndicator(
                    modifier  = Modifier.size(16.dp),
                    color     = RetroCliColors.Cyan,
                    strokeWidth = 2.dp
                )
            } else {
                Column {
                    Text(
                        text = if (isUser) "> USER" else "> LAMA",
                        style = MaterialTheme.typography.labelSmall,
                        color = accent
                    )

                    if (message.thinkingContent.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        ThinkingBlock(
                            thinkingContent = message.thinkingContent,
                            isStreaming = isStreaming && message.content.isEmpty()
                        )
                    }

                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text  = message.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = RetroCliColors.Text
                    )
                    if (isStreaming) {
                        Text(
                            text     = "|",
                            style    = MaterialTheme.typography.bodyMedium,
                            color    = RetroCliColors.Cyan,
                            modifier = Modifier.alpha(cursorAlpha)
                        )
                    }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThinkingBlock(
    thinkingContent: String,
    isStreaming: Boolean = false
) {
    var expanded by remember { mutableStateOf(isStreaming) }

    // Auto-expand while streaming thinking content
    LaunchedEffect(isStreaming) {
        if (isStreaming) expanded = true
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(RetroCliColors.Void.copy(alpha = 0.5f))
            .border(1.dp, RetroCliColors.Warning.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = if (expanded) "v THINKING" else "> THINKING",
                style = MaterialTheme.typography.labelSmall,
                color = RetroCliColors.Warning
            )
        }

        AnimatedVisibility(visible = expanded) {
            Text(
                text = thinkingContent,
                style = MaterialTheme.typography.bodySmall,
                color = RetroCliColors.Muted,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

// ---- Previews ---------------------------------------------------------------

@Preview(showBackground = true, name = "ChatScreen — no model")
@Composable
private fun ChatScreenEmptyPreview() {
    AIpacaTheme(darkTheme = true) {
        ChatScreen()
    }
}

@Preview(showBackground = true, name = "MessageBubble — user")
@Composable
private fun MessageBubbleUserPreview() {
    AIpacaTheme(darkTheme = true) {
        Column(modifier = Modifier.padding(8.dp)) {
            MessageBubble(
                message = ChatMessage(role = Role.USER, content = "Hello! How are you?")
            )
            Spacer(Modifier.height(4.dp))
            MessageBubble(
                message     = ChatMessage(role = Role.ASSISTANT, content = "I'm doing great, running entirely on your phone!"),
                isStreaming = true
            )
        }
    }
}
