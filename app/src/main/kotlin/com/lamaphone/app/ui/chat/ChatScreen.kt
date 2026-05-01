package com.lamaphone.app.ui.chat

import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
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
                    contentPadding  = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
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
    onClear: () -> Unit,
    onModelSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier     = modifier.fillMaxWidth(),
        tonalElevation = 2.dp,
        color        = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier            = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment   = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Model name chip
            if (isLoaded && modelPath != null) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text  = "Model",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text  = modelPath.substringAfterLast('/'),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                }
            } else {
                Text(
                    text     = "No model loaded",
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }

            Row {
                // Clear button
                if (isLoaded) {
                    IconButton(onClick = onClear) {
                        Icon(
                            imageVector        = Icons.Filled.Clear,
                            contentDescription = "Clear chat",
                            tint               = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // Load model button
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
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier            = Modifier.padding(32.dp)
        ) {
            Text(
                text  = "🦙",   // llama emoji
                style = MaterialTheme.typography.displayLarge
            )
            Text(
                text  = if (isLoaded) "What's on your mind?" else "Load a model to get started",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
        modifier       = modifier.fillMaxWidth(),
        tonalElevation = 4.dp
    ) {
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value         = text,
                onValueChange = onTextChange,
                modifier      = Modifier.weight(1f),
                placeholder   = { Text("Type a message…") },
                enabled       = !isGenerating,
                maxLines      = 4,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction      = ImeAction.Send
                ),
                keyboardActions = KeyboardActions(
                    onSend = { onSend() }
                ),
                shape = RoundedCornerShape(24.dp)
            )

            if (isGenerating) {
                Button(
                    onClick  = onStop,
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
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
                    enabled  = text.isNotBlank()
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

    // Blinking cursor animation
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
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(
                    RoundedCornerShape(
                        topStart    = if (isUser) 12.dp else 4.dp,
                        topEnd      = if (isUser) 4.dp else 12.dp,
                        bottomStart = 12.dp,
                        bottomEnd   = 12.dp
                    )
                )
                .background(
                    if (isUser) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            val displayContent = if (isStreaming) message.content else message.content

            if (isStreaming && message.content.isEmpty()) {
                // Show loading dots while waiting for first token
                CircularProgressIndicator(
                    modifier  = Modifier.size(16.dp),
                    color     = MaterialTheme.colorScheme.onSurfaceVariant,
                    strokeWidth = 2.dp
                )
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text  = displayContent,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isUser) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isStreaming) {
                        Text(
                            text     = "|",
                            style    = MaterialTheme.typography.bodyMedium,
                            color    = if (isUser) MaterialTheme.colorScheme.onPrimary
                                       else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.alpha(cursorAlpha)
                        )
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
