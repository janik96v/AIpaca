package com.lamaphone.app.ui.chat

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lamaphone.app.EngineState
import com.lamaphone.app.engine.GenerateParams
import com.lamaphone.app.model.ChatMessage
import com.lamaphone.app.model.Role
import com.lamaphone.app.ui.components.ModelPickerButton
import com.lamaphone.app.ui.theme.LamaPhoneTheme
import com.lamaphone.app.ui.theme.RetroCliColors
import com.lamaphone.app.ui.theme.TerminalPanel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ---- ViewModel --------------------------------------------------------------

class ChatViewModel : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private var generationJob: Job? = null

    fun sendMessage(userText: String) {
        if (userText.isBlank()) return

        val userMsg = ChatMessage(role = Role.USER, content = userText.trim())
        val assistantMsg = ChatMessage(role = Role.ASSISTANT, content = "")

        _messages.value = _messages.value + userMsg + assistantMsg
        _isGenerating.value = true

        generationJob = viewModelScope.launch {
            try {
                // Build a simple prompt from the full conversation history
                val prompt = buildPrompt(_messages.value.dropLast(1))

                EngineState.engine.generate(prompt, GenerateParams())
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
    }

    private fun buildPrompt(messages: List<ChatMessage>): String {
        return messages.joinToString(separator = "\n") { msg ->
            when (msg.role) {
                Role.USER      -> "User: ${msg.content}"
                Role.ASSISTANT -> "Assistant: ${msg.content}"
                Role.SYSTEM    -> msg.content
            }
        } + "\nAssistant:"
    }
}

// ---- Screen -----------------------------------------------------------------

@Composable
fun ChatScreen(
    modifier: Modifier = Modifier,
    chatViewModel: ChatViewModel = viewModel()
) {
    val messages      by chatViewModel.messages.collectAsState()
    val isGenerating  by chatViewModel.isGenerating.collectAsState()
    val isLoaded  by EngineState.isLoaded.collectAsState()
    val modelPath by EngineState.modelPath.collectAsState()
    val gpuLayers by EngineState.gpuLayers.collectAsState()

    val listState      = rememberLazyListState()
    val snackbarState  = remember { SnackbarHostState() }
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
            // Header bar: model info + action buttons
            ChatHeaderBar(
                modelPath    = modelPath,
                isLoaded     = isLoaded,
                gpuLayers    = gpuLayers,
                onClear      = { chatViewModel.clearChat() },
                onModelSelected = { path ->
                    EngineState.scope.launch { EngineState.loadModel(path) }
                }
            )

            if (messages.isEmpty()) {
                EmptyChatPlaceholder(
                    isLoaded    = isLoaded,
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

// ---- Sub-composables --------------------------------------------------------

@Composable
private fun ChatHeaderBar(
    modelPath: String?,
    isLoaded: Boolean,
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
            if (isLoaded && modelPath != null) {
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
                    modifier        = Modifier.height(36.dp)
                )
            }
        }
    }
}

@Composable
private fun EmptyChatPlaceholder(
    isLoaded: Boolean,
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
                text  = if (isLoaded) "> READY. TYPE A PROMPT." else "> LOAD_MODEL REQUIRED.",
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
