package com.lamaphone.app.ui.chat

import android.app.Application
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.lamaphone.app.EngineState
import com.lamaphone.app.data.ChatConversationStore
import com.lamaphone.app.engine.ChatTurn
import com.lamaphone.app.engine.GenerateParams
import com.lamaphone.app.model.ChatMessage
import com.lamaphone.app.model.Role
import com.lamaphone.app.model.StoredConversation
import com.lamaphone.app.ui.components.ModelPickerButton
import com.lamaphone.app.ui.theme.LamaPhoneTheme
import com.lamaphone.app.ui.theme.RetroCliColors
import com.lamaphone.app.ui.theme.TerminalPanel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

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

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private var generationJob: Job? = null

    init {
        val storedConversations = conversationStore.loadConversations()
        _conversations.value = storedConversations
        storedConversations.firstOrNull()?.let { conversation ->
            activeConversationId = conversation.id
            _activeConversationId.value = conversation.id
            _messages.value = conversation.messages
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

                EngineState.engine.generateChat(turns, GenerateParams())
                    .collect { token ->
                        val current = _messages.value
                        if (current.isNotEmpty()) {
                            val last = current.last()
                            _messages.value = current.dropLast(1) +
                                last.copy(content = last.content + token)
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

    fun clearChat() {
        stopGeneration()
        _messages.value = emptyList()
        activeConversationId = null
        _activeConversationId.value = null
    }

    fun selectConversation(conversationId: String) {
        stopGeneration()
        val conversation = _conversations.value.firstOrNull { it.id == conversationId } ?: return
        activeConversationId = conversation.id
        _activeConversationId.value = conversation.id
        _messages.value = conversation.messages
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
        return messages.mapNotNull { msg ->
            if (msg.content.isBlank()) return@mapNotNull null
            val role = when (msg.role) {
                Role.USER      -> "user"
                Role.ASSISTANT -> "assistant"
                Role.SYSTEM    -> "system"
            }
            ChatTurn(role = role, content = msg.content)
        }
    }

    private fun persistCurrentConversation() {
        val conversationId = activeConversationId ?: return
        val currentMessages = _messages.value
        if (currentMessages.isEmpty()) return

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
                updatedAt = System.currentTimeMillis()
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
    val isGenerating  by chatViewModel.isGenerating.collectAsState()
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
                    onClear      = { chatViewModel.clearChat() },
                    onModelSelected = { path ->
                        EngineState.scope.launch { EngineState.loadModel(path) }
                    }
                )

                if (isLoaded && gpuLayers > 0 && !modelInfo.pureQ4_0) {
                    GpuQuantWarningBanner(quantName = modelInfo.quant)
                }

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
}

// ---- Sub-composables --------------------------------------------------------

@Composable
private fun ChatHeaderBar(
    modelPath: String?,
    isLoaded: Boolean,
    isLoadingModel: Boolean,
    gpuLayers: Int,
    onClear: () -> Unit,
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
                            text = "BACKGROUND_PROCESS ACTIVE",
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
                    IconButton(onClick = onClear) {
                        Icon(
                            imageVector        = Icons.Filled.Clear,
                            contentDescription = "Clear chat",
                            tint               = RetroCliColors.Magenta
                        )
                    }
                }
                ModelPickerButton(
                    onModelSelected = onModelSelected,
                    modifier        = Modifier.height(36.dp),
                    isLoading = isLoadingModel
                )
            }
        }
    }
}

/**
 * Inline warning strip shown when a GPU-offloaded model is not pure Q4_0.
 * Adreno OpenCL can load mixed quants, but the S24 Ultra speed preset is built
 * around pure Q4_0 tensors with repacking enabled.
 */
@Composable
private fun GpuQuantWarningBanner(
    quantName: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        color = androidx.compose.ui.graphics.Color(0xFF3D2400),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, androidx.compose.ui.graphics.Color(0xFFFF8C00))
    ) {
        Text(
            text = "GPU WARN: $quantName is not Adreno-optimised. " +
                    "Use a pure Q4_0 GGUF for the fast S24 Ultra path.",
            style = MaterialTheme.typography.labelSmall,
            color = androidx.compose.ui.graphics.Color(0xFFFF8C00),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
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
                text  = "LAMAPHONE TERMINAL",
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
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value         = text,
                onValueChange = onTextChange,
                modifier      = Modifier.weight(1f),
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
            val displayContent = if (isStreaming) message.content else message.content

            if (isStreaming && message.content.isEmpty()) {
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
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text  = displayContent,
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

// ---- Previews ---------------------------------------------------------------

@Preview(showBackground = true, name = "ChatScreen — no model")
@Composable
private fun ChatScreenEmptyPreview() {
    LamaPhoneTheme(darkTheme = true) {
        ChatScreen()
    }
}

@Preview(showBackground = true, name = "MessageBubble — user")
@Composable
private fun MessageBubbleUserPreview() {
    LamaPhoneTheme(darkTheme = true) {
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
