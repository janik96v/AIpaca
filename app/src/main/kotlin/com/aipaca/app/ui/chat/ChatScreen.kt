package com.aipaca.app.ui.chat

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import java.io.ByteArrayOutputStream
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.aipaca.app.EngineState
import com.aipaca.app.data.ChatConversationStore
import com.aipaca.app.engine.ChatTurn
import com.aipaca.app.engine.GenerateParams
import com.aipaca.app.model.ChatMessage
import com.aipaca.app.model.Role
import com.aipaca.app.model.StoredConversation
import com.aipaca.app.ui.components.EditorialDivider
import com.aipaca.app.ui.components.EditorialSectionMark
import com.aipaca.app.ui.components.MonoLabel
import com.aipaca.app.ui.components.MonoLabelTone
import com.aipaca.app.ui.components.ModelPickerButton
import com.aipaca.app.ui.components.StatusChip
import com.aipaca.app.ui.components.ChipTone
import com.aipaca.app.ui.theme.AIpacaTheme
import com.aipaca.app.ui.theme.AlpacaColors
import com.aipaca.app.ui.theme.AlpacaType
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

// ---- Think-tag stream parser ------------------------------------------------

data class ThinkParseResult(val content: String, val thinking: String)

class ThinkTagParser {
    private data class TagPair(val open: String, val close: String)
    private val tagPairs = listOf(
        TagPair("<think>", "</think>"),
        TagPair("<|channel>thought\n", "<channel|>")
    )

    private var insideThink = false
    private var activeClose: String? = null
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
        val thinkParts   = StringBuilder()

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

    private val _generationError = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val generationError: SharedFlow<String> = _generationError.asSharedFlow()

    private val _thinkingEnabled = MutableStateFlow(true)
    val thinkingEnabled: StateFlow<Boolean> = _thinkingEnabled.asStateFlow()

    private var generationJob: Job? = null

    // ---- STT state ---------------------------------------------------------

    private val audioRecorder = com.aipaca.app.engine.AudioRecorder()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isTranscribing = MutableStateFlow(false)
    val isTranscribing: StateFlow<Boolean> = _isTranscribing.asStateFlow()

    private val _transcriptionResult = MutableStateFlow<String?>(null)
    val transcriptionResult: StateFlow<String?> = _transcriptionResult.asStateFlow()

    private val _transcriptionError = MutableStateFlow<String?>(null)
    val transcriptionError: StateFlow<String?> = _transcriptionError.asStateFlow()

    private var recordingJob: Job? = null

    fun startRecording() {
        if (_isRecording.value || _isGenerating.value) return
        _isRecording.value = true
        _transcriptionError.value = null

        recordingJob = viewModelScope.launch {
            try {
                val samples = audioRecorder.record()  // suspends until stopRecording()
                _isRecording.value = false
                _isTranscribing.value = true
                val result = EngineState.whisperEngine.transcribe(samples)
                result.fold(
                    onSuccess  = { text -> _transcriptionResult.value = text },
                    onFailure  = { e   -> _transcriptionError.value = e.message ?: "Transcription failed" }
                )
            } catch (e: Exception) {
                _transcriptionError.value = e.message ?: "Recording failed"
            } finally {
                _isRecording.value = false
                _isTranscribing.value = false
            }
        }
    }

    fun stopRecording() {
        audioRecorder.stopRecording()
        // recordingJob continues — it transitions to transcription automatically
    }

    fun consumeTranscriptionResult() {
        _transcriptionResult.value = null
    }

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

    fun sendMessage(
        userText: String,
        imageUri: Uri? = null,
        documentName: String? = null,
        documentText: String? = null
    ) {
        val content = buildString {
            if (!documentText.isNullOrBlank()) append("[Document: $documentName]\n$documentText\n\n")
            if (userText.isNotBlank()) append(userText.trim())
        }.trim()
        if (content.isBlank() && imageUri == null) return

        if (activeConversationId == null) {
            activeConversationId = UUID.randomUUID().toString()
            _activeConversationId.value = activeConversationId
        }

        val userMsg = ChatMessage(
            role = Role.USER,
            content = content,
            attachedImageUri = imageUri?.toString(),
            attachedDocumentName = documentName,
            displayText = if (documentName != null) userText.trim().ifBlank { null } else null
        )
        val assistantMsg = ChatMessage(role = Role.ASSISTANT, content = "")

        _messages.value = _messages.value + userMsg + assistantMsg
        persistCurrentConversation()
        _isGenerating.value = true

        generationJob = viewModelScope.launch {
            var tokenCount = 0
            try {
                val turns = buildTurns(_messages.value.dropLast(1))
                val thinkEnabled = _thinkingEnabled.value
                val params = GenerateParams(thinkingEnabled = thinkEnabled)

                // Choose vision or text-only generation path
                val flow = if (imageUri != null) {
                    if (!EngineState.engine.isMmprojLoaded()) {
                        _generationError.tryEmit("Load a vision projector first — go to Models tab")
                        return@launch
                    }
                    val rawBytes = getApplication<Application>().contentResolver
                        .openInputStream(imageUri)?.use { it.readBytes() }
                    if (rawBytes == null || rawBytes.isEmpty()) {
                        _generationError.tryEmit("Failed to read image")
                        return@launch
                    }
                    // Downscale large images to reduce vision token count
                    val imageBytes = downscaleImageIfNeeded(rawBytes, maxLongEdge = 768)
                    EngineState.engine.generateChatWithImage(turns, imageBytes, params)
                } else {
                    EngineState.engine.generateChat(turns, params)
                }

                flow.collect { chunk ->
                        tokenCount++
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
                if (tokenCount == 0) {
                    _generationError.tryEmit("Generation failed — prompt may exceed context window")
                }
            } catch (e: Exception) {
                _generationError.tryEmit("Generation error: ${e.message ?: "unknown error"}")
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

    private fun downscaleImageIfNeeded(rawBytes: ByteArray, maxLongEdge: Int): ByteArray {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, opts)
        val w = opts.outWidth
        val h = opts.outHeight
        if (w <= 0 || h <= 0) return rawBytes // can't decode dimensions, pass through
        val longEdge = maxOf(w, h)
        if (longEdge <= maxLongEdge) return rawBytes // already small enough

        val scale = maxLongEdge.toFloat() / longEdge
        val newW = (w * scale).toInt().coerceAtLeast(1)
        val newH = (h * scale).toInt().coerceAtLeast(1)

        val full = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return rawBytes
        val scaled = Bitmap.createScaledBitmap(full, newW, newH, true)
        if (scaled !== full) full.recycle()

        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        scaled.recycle()
        return out.toByteArray()
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
                id           = conversationId,
                title        = title,
                messages     = currentMessages,
                updatedAt    = System.currentTimeMillis(),
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
    val messages              by chatViewModel.messages.collectAsState()
    val conversations         by chatViewModel.conversations.collectAsState()
    val currentConversationId by chatViewModel.currentConversationId.collectAsState()
    val systemPrompt          by chatViewModel.systemPrompt.collectAsState()
    val isGenerating          by chatViewModel.isGenerating.collectAsState()
    val thinkingEnabled       by chatViewModel.thinkingEnabled.collectAsState()
    val isLoaded              by EngineState.isLoaded.collectAsState()
    val isLoadingModel        by EngineState.isLoadingModel.collectAsState()
    val modelPath             by EngineState.modelPath.collectAsState()
    val gpuLayers             by EngineState.gpuLayers.collectAsState()
    val modelInfo             by EngineState.modelInfo.collectAsState()
    val contextSize           by EngineState.contextSize.collectAsState()
    val isMmprojLoaded        by EngineState.isMmprojLoaded.collectAsState()
    // Reserve 25% of context for generation output; ~4 chars per token.
    val docCharLimit = ((contextSize * 0.75) * 4).toInt().coerceAtLeast(2_000)

    val isRecording         by chatViewModel.isRecording.collectAsState()
    val isTranscribing      by chatViewModel.isTranscribing.collectAsState()
    val transcriptionResult by chatViewModel.transcriptionResult.collectAsState()
    val transcriptionError  by chatViewModel.transcriptionError.collectAsState()
    val whisperLoaded       = EngineState.whisperEngine.isLoaded

    val listState     = rememberLazyListState()
    val snackbarState = remember { SnackbarHostState() }
    val drawerState   = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope         = rememberCoroutineScope()
    val context       = LocalContext.current
    var inputText     by remember { mutableStateOf("") }
    var showSystemPromptDialog by remember { mutableStateOf(false) }
    var editingSystemPrompt    by remember(systemPrompt) { mutableStateOf(systemPrompt) }
    var pendingModelPath       by remember { mutableStateOf<String?>(null) }

    var selectedImageUri     by remember { mutableStateOf<Uri?>(null) }
    var selectedDocumentName by remember { mutableStateOf<String?>(null) }
    var selectedDocumentText by remember { mutableStateOf<String?>(null) }
    var documentError        by remember { mutableStateOf<String?>(null) }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> selectedImageUri = uri }

    val documentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { docUri ->
            val name = context.contentResolver
                .query(docUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                ?: "Document"
            val extracted = try {
                val mimeType = context.contentResolver.getType(docUri)
                if (mimeType == "application/pdf") {
                    if (android.os.Build.VERSION.SDK_INT >= 35) {
                        context.contentResolver.openFileDescriptor(docUri, "r")?.use { pfd ->
                            PdfRenderer(pfd).use { renderer ->
                                buildString {
                                    for (i in 0 until renderer.pageCount) {
                                        renderer.openPage(i).use { page ->
                                            page.textContents.forEach { block ->
                                                append(block.text)
                                                append(' ')
                                            }
                                            append('\n')
                                        }
                                    }
                                }.trim().ifEmpty { null }
                            }
                        }
                    } else {
                        PDFBoxResourceLoader.init(context)
                        context.contentResolver.openInputStream(docUri)?.use { stream ->
                            PDDocument.load(stream).use { doc ->
                                PDFTextStripper().getText(doc).ifEmpty { null }
                            }
                        }
                    }
                } else {
                    context.contentResolver.openInputStream(docUri)
                        ?.bufferedReader()?.use { it.readText() }
                }
            } catch (_: Exception) { null }

            when {
                extracted == null -> {
                    documentError = "Could not read document."
                }
                extracted.length > docCharLimit -> {
                    documentError = "Document too large for the current context window " +
                        "($contextSize tokens). Load the model with a larger context or use a shorter document."
                }
                else -> {
                    selectedDocumentName = name
                    selectedDocumentText = extracted
                }
            }
        }
    }

    // Consume transcription result → populate input field
    LaunchedEffect(transcriptionResult) {
        transcriptionResult?.let { text ->
            inputText = text
            chatViewModel.consumeTranscriptionResult()
        }
    }

    // Show transcription errors as snackbars
    LaunchedEffect(transcriptionError) {
        transcriptionError?.let { snackbarState.showSnackbar(it) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) chatViewModel.startRecording()
        else scope.launch { snackbarState.showSnackbar("Microphone permission denied") }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    val lastContent = messages.lastOrNull()?.content
    LaunchedEffect(lastContent) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    LaunchedEffect(Unit) {
        chatViewModel.generationError.collect { error ->
            snackbarState.showSnackbar(error)
        }
    }

    LaunchedEffect(documentError) {
        documentError?.let {
            snackbarState.showSnackbar(it)
            documentError = null
        }
    }

    ModalNavigationDrawer(
        drawerState   = drawerState,
        drawerContent = {
            ChatHistoryDrawer(
                conversations         = conversations,
                currentConversationId = currentConversationId,
                onNewChat             = {
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
            modifier       = modifier.background(AlpacaColors.Surface.Canvas),
            containerColor = AlpacaColors.Surface.Canvas,
            snackbarHost   = { SnackbarHost(snackbarState) },
            bottomBar      = {
                ChatInputBar(
                    text             = inputText,
                    onTextChange     = { inputText = it },
                    isGenerating     = isGenerating,
                    isRecording      = isRecording,
                    isTranscribing   = isTranscribing,
                    whisperLoaded    = whisperLoaded,
                    supportsThinking = modelInfo.supportsThinking,
                    thinkingEnabled  = thinkingEnabled,
                    onThinkingToggle = { chatViewModel.toggleThinking() },
                    systemPrompt     = systemPrompt,
                    onSystemPromptClick = { showSystemPromptDialog = true },
                    supportsAttachments  = isLoaded,
                    supportsMultimodal   = isMmprojLoaded && isLoaded,
                    selectedImageUri     = selectedImageUri,
                    selectedDocumentName = selectedDocumentName,
                    onAttachImage = {
                        imagePicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onAttachDocument = {
                        documentPicker.launch(
                            arrayOf(
                                "text/plain", "text/markdown", "text/csv",
                                "application/json", "text/x-markdown", "text/comma-separated-values",
                                "application/pdf"
                            )
                        )
                    },
                    onClearAttachment = {
                        selectedImageUri = null
                        selectedDocumentName = null
                        selectedDocumentText = null
                    },
                    onSend = {
                        if (!isLoaded) {
                            scope.launch {
                                snackbarState.showSnackbar("Please load a model first")
                            }
                        } else if (inputText.isNotBlank() || selectedImageUri != null || selectedDocumentName != null) {
                            chatViewModel.sendMessage(
                                userText     = inputText,
                                imageUri     = selectedImageUri,
                                documentName = selectedDocumentName,
                                documentText = selectedDocumentText
                            )
                            inputText = ""
                            selectedImageUri = null
                            selectedDocumentName = null
                            selectedDocumentText = null
                        }
                    },
                    onStop = { chatViewModel.stopGeneration() },
                    onMicClick = {
                        when {
                            isRecording    -> chatViewModel.stopRecording()
                            isTranscribing -> { /* no-op while transcribing */ }
                            !whisperLoaded -> scope.launch {
                                snackbarState.showSnackbar("Load a Whisper model first — go to Models tab")
                            }
                            else -> {
                                val granted = ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (granted) chatViewModel.startRecording()
                                else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // ----- Masthead -----
                ChatMasthead(
                    modelPath      = modelPath,
                    isLoaded       = isLoaded,
                    isLoadingModel = isLoadingModel,
                    gpuLayers      = gpuLayers,
                    onModelSelected = { path ->
                        pendingModelPath = path
                    },
                    onUnload  = { EngineState.unload() },
                    onHistory = { scope.launch { drawerState.open() } }
                )

                if (messages.isEmpty()) {
                    EmptyChatPlaceholder(
                        isLoaded       = isLoaded,
                        isLoadingModel = isLoadingModel,
                        modifier       = Modifier.fillMaxSize()
                    )
                } else {
                    Spacer(Modifier.height(8.dp))
                    EditorialSectionMark(
                        label    = "DISPATCH",
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(
                        state               = listState,
                        modifier            = Modifier.fillMaxSize(),
                        contentPadding      = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
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

    pendingModelPath?.let { path ->
        val contextOptions = listOf(512, 1024, 2048, 4096, 8192)
        val recommended = 1024
        AlertDialog(
            onDismissRequest = { pendingModelPath = null },
            title = { Text("Context Window", style = AlpacaType.TitleMd) },
            text = {
                Column {
                    Text(
                        "Choose how many tokens the model can hold in memory at once. Larger = more document/history, but uses more RAM and is slower to start.",
                        style = AlpacaType.BodySm,
                        color = AlpacaColors.Text.Muted
                    )
                    Spacer(Modifier.height(16.dp))
                    contextOptions.forEach { size ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    pendingModelPath = null
                                    EngineState.scope.launch {
                                        EngineState.loadModel(path, contextSize = size)
                                    }
                                }
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "$size tokens",
                                style = AlpacaType.BodyMd,
                                color = AlpacaColors.Text.Primary
                            )
                            if (size == recommended) {
                                MonoLabel(text = "RECOMMENDED", tone = MonoLabelTone.Accent)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pendingModelPath = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showSystemPromptDialog) {
        SystemPromptDialog(
            initialValue = editingSystemPrompt,
            onValueChange = { editingSystemPrompt = it },
            onConfirm = {
                chatViewModel.updateSystemPrompt(editingSystemPrompt)
                showSystemPromptDialog = false
            },
            onClear = {
                editingSystemPrompt = ""
                chatViewModel.updateSystemPrompt("")
                showSystemPromptDialog = false
            },
            onDismiss = {
                chatViewModel.updateSystemPrompt(editingSystemPrompt)
                showSystemPromptDialog = false
            }
        )
    }
}

// ---- Masthead ---------------------------------------------------------------

@Composable
private fun ChatMasthead(
    modelPath: String?,
    isLoaded: Boolean,
    isLoadingModel: Boolean,
    gpuLayers: Int,
    onModelSelected: (String) -> Unit,
    onUnload: () -> Unit,
    onHistory: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Row(
            verticalAlignment   = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier            = Modifier.fillMaxWidth()
        ) {
            Text(
                text  = "Alpaca.",
                style = AlpacaType.DisplayMasthead,
                color = AlpacaColors.Text.Primary
            )
            IconButton(onClick = onHistory) {
                Icon(
                    imageVector        = Icons.Outlined.Menu,
                    contentDescription = "Open chat history",
                    tint               = AlpacaColors.Text.Primary
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        EditorialDivider()
        Spacer(Modifier.height(12.dp))

        val statusText = when {
            isLoadingModel -> "MODEL · LOADING…"
            isLoaded       -> {
                val name    = modelPath?.substringAfterLast('/')?.removeSuffix(".gguf").orEmpty()
                val backend = if (gpuLayers > 0) "GPU · $gpuLayers LAYERS" else "CPU"
                "${name.uppercase()} · $backend · ONLINE"
            }
            else -> "NO MODEL LOADED"
        }
        val statusTone = when {
            isLoadingModel -> MonoLabelTone.Warning
            isLoaded       -> MonoLabelTone.Accent
            else           -> MonoLabelTone.Muted
        }

        Row(
            modifier            = Modifier.fillMaxWidth(),
            verticalAlignment   = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            MonoLabel(text = statusText, tone = statusTone, modifier = Modifier.weight(1f))

            if (isLoaded) {
                TextButton(onClick = onUnload) {
                    Text("Unload", style = AlpacaType.LabelLg, color = AlpacaColors.State.Error)
                }
            } else {
                ModelPickerButton(
                    onModelSelected = onModelSelected,
                    isLoading       = isLoadingModel
                )
            }
        }
    }
}

// ---- Sub-composables --------------------------------------------------------

@Composable
private fun EmptyChatPlaceholder(
    isLoaded: Boolean,
    isLoadingModel: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier            = modifier.padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.Start
    ) {
        EditorialSectionMark(label = "READY")
        Text(
            text  = when {
                isLoadingModel -> "Loading model…"
                isLoaded       -> "Ready. Write a message below."
                else           -> "Load a model to start chatting."
            },
            style = AlpacaType.BodyLg,
            color = AlpacaColors.Text.Body
        )
        Text(
            text  = "Local inference. No data leaves this device.",
            style = AlpacaType.BodySm,
            color = AlpacaColors.Text.Muted
        )
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
        modifier             = Modifier.width(320.dp),
        drawerContainerColor = AlpacaColors.Surface.Canvas,
        drawerContentColor   = AlpacaColors.Text.Primary
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 20.dp)
        ) {
            Text(
                text  = "History.",
                style = AlpacaType.DisplayHeadline,
                color = AlpacaColors.Text.Primary
            )
            Spacer(Modifier.height(8.dp))
            EditorialDivider()
            Spacer(Modifier.height(8.dp))
            MonoLabel("STORED CONVERSATIONS · ${conversations.size}")

            Spacer(Modifier.height(20.dp))
            Button(
                onClick  = onNewChat,
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(6.dp),
                colors   = ButtonDefaults.buttonColors(
                    containerColor = AlpacaColors.Accent.Primary,
                    contentColor   = AlpacaColors.Text.OnAccent
                )
            ) {
                Text("New chat", style = AlpacaType.LabelLg)
            }

            Spacer(Modifier.height(20.dp))
            EditorialDivider()

            if (conversations.isEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text  = "No saved conversations yet.",
                    style = AlpacaType.BodyMd,
                    color = AlpacaColors.Text.Muted
                )
            } else {
                LazyColumn(
                    modifier            = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    items(conversations, key = { it.id }) { conversation ->
                        ConversationHistoryRow(
                            conversation = conversation,
                            isSelected   = conversation.id == currentConversationId,
                            onSelect     = { onSelectConversation(conversation.id) },
                            onDelete     = { onDeleteConversation(conversation.id) }
                        )
                        EditorialDivider(color = AlpacaColors.Line.Subtle)
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
    Row(
        modifier            = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 12.dp),
        verticalAlignment   = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text     = conversation.title,
                style    = AlpacaType.BodyMd,
                color    = if (isSelected) AlpacaColors.Accent.Primary else AlpacaColors.Text.Primary,
                maxLines = 2
            )
            Spacer(Modifier.height(4.dp))
            MonoLabel(
                text = "${conversation.messages.size} LINES · ${formatConversationTime(conversation.updatedAt)}"
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector        = Icons.Outlined.Delete,
                contentDescription = "Delete conversation",
                tint               = AlpacaColors.Text.Muted,
                modifier           = Modifier.size(18.dp)
            )
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
    isRecording: Boolean = false,
    isTranscribing: Boolean = false,
    whisperLoaded: Boolean = false,
    supportsThinking: Boolean = false,
    thinkingEnabled: Boolean = false,
    onThinkingToggle: () -> Unit = {},
    systemPrompt: String = "",
    onSystemPromptClick: () -> Unit = {},
    supportsAttachments: Boolean = false,
    supportsMultimodal: Boolean = false,
    selectedImageUri: Uri? = null,
    selectedDocumentName: String? = null,
    onAttachImage: () -> Unit = {},
    onAttachDocument: () -> Unit = {},
    onClearAttachment: () -> Unit = {},
    onSend: () -> Unit,
    onStop: () -> Unit,
    onMicClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val hasAttachment = selectedImageUri != null || selectedDocumentName != null

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(AlpacaColors.Surface.Canvas)
    ) {
        EditorialDivider()
        Column(
            modifier            = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (hasAttachment) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (selectedImageUri != null) {
                        AsyncImage(
                            model              = selectedImageUri,
                            contentDescription = "Attached image",
                            modifier           = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(6.dp))
                        )
                    } else if (selectedDocumentName != null) {
                        SuggestionChip(
                            onClick = {},
                            label   = { Text(selectedDocumentName, style = AlpacaType.LabelMd) },
                            icon    = { Icon(Icons.Outlined.Description, null, Modifier.size(14.dp)) },
                            colors  = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = AlpacaColors.Surface.Elevated
                            )
                        )
                    }
                    IconButton(onClick = onClearAttachment, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Remove attachment",
                            modifier = Modifier.size(16.dp),
                            tint     = AlpacaColors.Text.Muted
                        )
                    }
                }
            }

            OutlinedTextField(
                value         = text,
                onValueChange = onTextChange,
                modifier      = Modifier.fillMaxWidth(),
                placeholder   = {
                    Text("Write a message…", style = AlpacaType.BodyMd, color = AlpacaColors.Text.Subtle)
                },
                enabled       = !isGenerating,
                maxLines      = 4,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction      = ImeAction.Send
                ),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                shape           = RoundedCornerShape(6.dp),
                textStyle       = AlpacaType.BodyLg.copy(color = AlpacaColors.Text.Primary),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor          = AlpacaColors.Text.Primary,
                    unfocusedTextColor        = AlpacaColors.Text.Primary,
                    disabledTextColor         = AlpacaColors.Text.Muted,
                    cursorColor               = AlpacaColors.Accent.Primary,
                    focusedBorderColor        = AlpacaColors.Accent.Primary,
                    unfocusedBorderColor      = AlpacaColors.Line.Hairline,
                    disabledBorderColor       = AlpacaColors.Line.Subtle,
                    focusedContainerColor     = AlpacaColors.Surface.Elevated,
                    unfocusedContainerColor   = AlpacaColors.Surface.Elevated,
                    disabledContainerColor    = AlpacaColors.Surface.Elevated,
                    focusedPlaceholderColor   = AlpacaColors.Text.Subtle,
                    unfocusedPlaceholderColor = AlpacaColors.Text.Subtle
                )
            )

            Row(
                modifier            = Modifier.fillMaxWidth(),
                verticalAlignment   = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    InputToggleChip(
                        icon     = Icons.Outlined.SmartToy,
                        label    = "Sys",
                        active   = systemPrompt.isNotBlank(),
                        onClick  = onSystemPromptClick
                    )
                    if (supportsThinking) {
                        Spacer(Modifier.width(8.dp))
                        InputToggleChip(
                            icon     = Icons.Outlined.Psychology,
                            label    = "Think",
                            active   = thinkingEnabled,
                            onClick  = onThinkingToggle
                        )
                    }
                    if (supportsAttachments) {
                        Spacer(Modifier.width(4.dp))
                        var showAttachMenu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(
                                onClick  = { showAttachMenu = true },
                                enabled  = !isGenerating,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Add,
                                    contentDescription = "Attach file",
                                    tint     = AlpacaColors.Text.Muted,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            DropdownMenu(
                                expanded         = showAttachMenu,
                                onDismissRequest = { showAttachMenu = false }
                            ) {
                                if (supportsMultimodal) {
                                    DropdownMenuItem(
                                        text        = { Text("Image", style = AlpacaType.BodyMd) },
                                        leadingIcon = { Icon(Icons.Outlined.Image, null, Modifier.size(18.dp)) },
                                        onClick     = { showAttachMenu = false; onAttachImage() }
                                    )
                                }
                                DropdownMenuItem(
                                    text        = { Text("Document", style = AlpacaType.BodyMd) },
                                    leadingIcon = { Icon(Icons.Outlined.Description, null, Modifier.size(18.dp)) },
                                    onClick     = { showAttachMenu = false; onAttachDocument() }
                                )
                            }
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    MicButton(
                        isRecording    = isRecording,
                        isTranscribing = isTranscribing,
                        whisperLoaded  = whisperLoaded,
                        onClick        = onMicClick
                    )
                    Spacer(Modifier.width(4.dp))

                if (isGenerating) {
                    Button(
                        onClick = onStop,
                        shape   = RoundedCornerShape(6.dp),
                        colors  = ButtonDefaults.buttonColors(
                            containerColor = AlpacaColors.State.Error,
                            contentColor   = AlpacaColors.Text.OnAccent
                        )
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = "Stop generation")
                    }
                } else {
                    Button(
                        onClick = onSend,
                        enabled = text.isNotBlank() || hasAttachment,
                        shape   = RoundedCornerShape(6.dp),
                        colors  = ButtonDefaults.buttonColors(
                            containerColor         = AlpacaColors.Accent.Primary,
                            contentColor           = AlpacaColors.Text.OnAccent,
                            disabledContainerColor = AlpacaColors.Surface.Elevated,
                            disabledContentColor   = AlpacaColors.Text.Subtle
                        )
                    ) {
                        Icon(Icons.Filled.Send, contentDescription = "Send message")
                    }
                }
                } // end mic+send Row
            }
        }
    }
}

@Composable
private fun MicButton(
    isRecording: Boolean,
    isTranscribing: Boolean,
    whisperLoaded: Boolean,
    onClick: () -> Unit
) {
    if (isTranscribing) {
        CircularProgressIndicator(
            modifier    = Modifier
                .size(36.dp)
                .padding(6.dp),
            color       = AlpacaColors.Accent.Primary,
            strokeWidth = 2.dp
        )
        return
    }

    val icon: androidx.compose.ui.graphics.vector.ImageVector
    val tint: Color
    val scaleModifier: Modifier

    when {
        isRecording -> {
            val infiniteTransition = rememberInfiniteTransition(label = "mic_pulse")
            val scale by infiniteTransition.animateFloat(
                initialValue  = 1f,
                targetValue   = 1.3f,
                animationSpec = infiniteRepeatable(
                    animation  = tween(600),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "mic_scale"
            )
            icon          = Icons.Outlined.Mic
            tint          = AlpacaColors.State.Error
            scaleModifier = Modifier.scale(scale)
        }
        !whisperLoaded -> {
            icon          = Icons.Outlined.MicOff
            tint          = AlpacaColors.Text.Muted
            scaleModifier = Modifier
        }
        else -> {
            icon          = Icons.Outlined.Mic
            tint          = AlpacaColors.Text.Primary
            scaleModifier = Modifier
        }
    }

    IconButton(
        onClick  = onClick,
        modifier = scaleModifier
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = if (isRecording) "Stop recording" else "Start recording",
            tint               = tint,
            modifier           = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun InputToggleChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    val color = if (active) AlpacaColors.Accent.Primary else AlpacaColors.Text.Muted
    Row(
        verticalAlignment   = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = label,
            tint               = color,
            modifier           = Modifier.size(18.dp)
        )
        Text(label, style = AlpacaType.LabelMd, color = color)
    }
}

@Composable
private fun SystemPromptDialog(
    initialValue: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }

    AlertDialog(
        onDismissRequest    = onDismiss,
        containerColor      = AlpacaColors.Surface.Card,
        titleContentColor   = AlpacaColors.Text.Primary,
        textContentColor    = AlpacaColors.Text.Body,
        shape               = RoundedCornerShape(12.dp),
        title = {
            Column {
                Text("System prompt", style = AlpacaType.TitleMd, color = AlpacaColors.Text.Primary)
                Spacer(Modifier.height(4.dp))
                MonoLabel("INSTRUCTION FOR THE MODEL")
            }
        },
        text = {
            OutlinedTextField(
                value         = value,
                onValueChange = { value = it; onValueChange(it) },
                modifier      = Modifier.fillMaxWidth(),
                placeholder   = { Text("Set system instructions…", style = AlpacaType.BodyMd) },
                minLines      = 3,
                maxLines      = 8,
                textStyle     = AlpacaType.BodyMd.copy(color = AlpacaColors.Text.Primary),
                shape         = RoundedCornerShape(6.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor          = AlpacaColors.Text.Primary,
                    unfocusedTextColor        = AlpacaColors.Text.Primary,
                    cursorColor               = AlpacaColors.Accent.Primary,
                    focusedBorderColor        = AlpacaColors.Accent.Primary,
                    unfocusedBorderColor      = AlpacaColors.Line.Hairline,
                    focusedPlaceholderColor   = AlpacaColors.Text.Subtle,
                    unfocusedPlaceholderColor = AlpacaColors.Text.Subtle
                )
            )
        },
        dismissButton = {
            TextButton(onClick = onClear) {
                Text("Clear", style = AlpacaType.LabelLg, color = AlpacaColors.State.Error)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Done", style = AlpacaType.LabelLg, color = AlpacaColors.Accent.Primary)
            }
        }
    )
}

// ---- Message bubble ---------------------------------------------------------

@Composable
fun MessageBubble(
    message: ChatMessage,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier
) {
    val isUser = message.role == Role.USER

    if (isUser) {
        UserMessage(message = message, modifier = modifier)
    } else {
        AssistantMessage(message = message, isStreaming = isStreaming, modifier = modifier)
    }
}

@Composable
private fun UserMessage(
    message: ChatMessage,
    modifier: Modifier = Modifier
) {
    Row(
        modifier            = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(AlpacaColors.Surface.Elevated)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.Start
        ) {
            MonoLabel("YOU · ${currentTimeShort()}")
            Spacer(Modifier.height(6.dp))
            if (message.attachedImageUri != null) {
                AsyncImage(
                    model              = Uri.parse(message.attachedImageUri),
                    contentDescription = "Attached image",
                    modifier           = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                if (message.content.isNotBlank()) Spacer(Modifier.height(8.dp))
            } else if (message.attachedDocumentName != null) {
                SuggestionChip(
                    onClick = {},
                    label   = { Text(message.attachedDocumentName, style = AlpacaType.LabelMd) },
                    icon    = { Icon(Icons.Outlined.Description, null, Modifier.size(14.dp)) },
                    colors  = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = AlpacaColors.Surface.Canvas
                    )
                )
                if (message.content.isNotBlank()) Spacer(Modifier.height(4.dp))
            }
            val bubbleText = message.displayText ?: message.content
            if (bubbleText.isNotBlank()) {
                Text(
                    text  = bubbleText,
                    style = AlpacaType.BodyLg,
                    color = AlpacaColors.Text.Primary
                )
            }
        }
    }
}

@Composable
private fun AssistantMessage(
    message: ChatMessage,
    isStreaming: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        MonoLabel(text = "ALPACA · ON-DEVICE", tone = MonoLabelTone.Accent)
        Spacer(Modifier.height(8.dp))

        if (message.thinkingContent.isNotEmpty()) {
            ThinkingBlock(
                thinkingContent = message.thinkingContent,
                isStreaming     = isStreaming && message.content.isEmpty()
            )
            Spacer(Modifier.height(10.dp))
        }

        if (isStreaming && message.content.isEmpty() && message.thinkingContent.isEmpty()) {
            CircularProgressIndicator(
                modifier    = Modifier.size(16.dp),
                color       = AlpacaColors.Accent.Primary,
                strokeWidth = 2.dp
            )
        } else {
            Text(
                text  = message.content,
                style = AlpacaType.BodyLg,
                color = AlpacaColors.Text.Body
            )
        }

        if (isStreaming && message.content.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .size(width = 24.dp, height = 1.dp)
                    .background(AlpacaColors.Accent.Primary)
                    .alpha(0.8f)
            )
        }
    }
}

@Composable
private fun ThinkingBlock(
    thinkingContent: String,
    isStreaming: Boolean = false
) {
    var expanded by remember { mutableStateOf(isStreaming) }

    LaunchedEffect(isStreaming) {
        if (isStreaming) expanded = true
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 6.dp)
    ) {
        MonoLabel(
            text = if (expanded) "THINKING · TAP TO COLLAPSE" else "THINKING · TAP TO EXPAND",
            tone = MonoLabelTone.Warning
        )
        AnimatedVisibility(
            visible = expanded,
            enter   = expandVertically(),
            exit    = shrinkVertically()
        ) {
            Text(
                text     = thinkingContent,
                style    = AlpacaType.BodySm,
                color    = AlpacaColors.Text.Muted,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

private fun currentTimeShort(): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

// ---- Previews ---------------------------------------------------------------

@Preview(showBackground = true, name = "ChatScreen — no model")
@Composable
private fun ChatScreenEmptyPreview() {
    AIpacaTheme {
        ChatScreen()
    }
}

@Preview(showBackground = true, name = "MessageBubbles")
@Composable
private fun MessageBubbleUserPreview() {
    AIpacaTheme {
        Column(modifier = Modifier
            .background(AlpacaColors.Surface.Canvas)
            .padding(24.dp)) {
            MessageBubble(message = ChatMessage(role = Role.USER, content = "How fast is this?"))
            Spacer(Modifier.height(16.dp))
            MessageBubble(
                message     = ChatMessage(role = Role.ASSISTANT, content = "I'm running on your phone — 14.2 t/s right now."),
                isStreaming = true
            )
        }
    }
}
