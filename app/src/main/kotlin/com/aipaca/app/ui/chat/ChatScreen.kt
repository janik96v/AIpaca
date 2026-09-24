package com.aipaca.app.ui.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aipaca.app.EngineState
import com.aipaca.app.ui.components.InkSnackbarHost
import com.aipaca.app.ui.components.world.WorldMotion
import com.aipaca.app.ui.shell.ModelPresence
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val DOCUMENT_TYPES = arrayOf(
    "text/plain", "text/markdown", "text/csv",
    "application/json", "text/x-markdown", "text/comma-separated-values",
    "application/pdf"
)

/**
 * Chat. Empty, it shows the Model World and what to do next; with messages, the
 * conversation. The composer sits below either.
 */
@Composable
fun ChatScreen(
    presence: ModelPresence,
    worldMotion: WorldMotion,
    onOpenModels: () -> Unit,
    modifier: Modifier = Modifier,
    chatViewModel: ChatViewModel = viewModel()
) {
    val messages        by chatViewModel.messages.collectAsState()
    val isGenerating    by chatViewModel.isGenerating.collectAsState()
    val contextSize     by EngineState.contextSize.collectAsState()
    val isMmprojLoaded  by EngineState.isMmprojLoaded.collectAsState()
    val whisperPath     by EngineState.whisperModelPath.collectAsState()

    val isRecording         by chatViewModel.isRecording.collectAsState()
    val isTranscribing      by chatViewModel.isTranscribing.collectAsState()
    val transcriptionResult by chatViewModel.transcriptionResult.collectAsState()
    val transcriptionError  by chatViewModel.transcriptionError.collectAsState()

    // Reserve 25% of context for generation output; ~4 chars per token.
    val docCharLimit = ((contextSize * 0.75) * 4).toInt().coerceAtLeast(2_000)
    val whisperLoaded = whisperPath != null

    val listState     = rememberLazyListState()
    val snackbarState = remember { SnackbarHostState() }
    val scope         = rememberCoroutineScope()
    val context       = LocalContext.current

    var inputText              by remember { mutableStateOf("") }

    var selectedImageUri     by remember { mutableStateOf<Uri?>(null) }
    var selectedDocumentName by remember { mutableStateOf<String?>(null) }
    var selectedDocumentText by remember { mutableStateOf<String?>(null) }
    // Text to put in the composer once a document picked from a suggestion is attached.
    var promptAfterDocument  by remember { mutableStateOf<String?>(null) }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) selectedImageUri = uri }

    val documentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val prompt = promptAfterDocument
        promptAfterDocument = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            when (val doc = readDocument(context, uri, docCharLimit)) {
                is DocumentRead.Ok -> {
                    selectedDocumentName = doc.name
                    selectedDocumentText = doc.text
                    if (prompt != null && inputText.isBlank()) inputText = prompt
                }
                DocumentRead.Unreadable -> snackbarState.showSnackbar("Could not read document.")
                DocumentRead.TooLarge   -> snackbarState.showSnackbar(
                    "Document too large for the current context window ($contextSize tokens). " +
                        "Load the model with a larger context or use a shorter document."
                )
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) chatViewModel.startRecording()
        else scope.launch { snackbarState.showSnackbar("Microphone permission denied") }
    }

    // Transcription lands in the composer.
    LaunchedEffect(transcriptionResult) {
        transcriptionResult?.let { text ->
            inputText = text
            chatViewModel.consumeTranscriptionResult()
        }
    }
    LaunchedEffect(transcriptionError) {
        transcriptionError?.let { snackbarState.showSnackbar(it) }
    }
    LaunchedEffect(Unit) {
        chatViewModel.generationError.collect { error -> snackbarState.showSnackbar(error) }
    }

    // Follow the conversation as it grows and streams.
    val lastContent = messages.lastOrNull()?.let { it.content.length + it.thinkingContent.length }
    LaunchedEffect(messages.size, lastContent) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    fun send() {
        if (!presence.chatReady) {
            scope.launch { snackbarState.showSnackbar("Load a model first") }
            return
        }
        if (inputText.isBlank() && selectedImageUri == null && selectedDocumentName == null) return
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

    fun toggleMic() {
        when {
            isRecording    -> chatViewModel.stopRecording()
            isTranscribing -> Unit
            !whisperLoaded -> scope.launch { snackbarState.showSnackbar("Load a Whisper model first — go to Models") }
            else -> {
                val granted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) chatViewModel.startRecording()
                else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (messages.isEmpty()) {
                ChatEmptyState(
                    presence     = presence,
                    worldMotion  = worldMotion,
                    onOpenModels = onOpenModels,
                    onSummarise  = {
                        promptAfterDocument = "Summarise this document."
                        documentPicker.launch(DOCUMENT_TYPES)
                    },
                    onRecall     = { inputText = "What do you remember about me?" },
                    modifier     = Modifier.weight(1f)
                )
            } else {
                MessageList(
                    messages     = messages,
                    isGenerating = isGenerating,
                    listState    = listState,
                    modifier     = Modifier.weight(1f)
                )
            }

            Composer(
                text               = inputText,
                onTextChange       = { inputText = it },
                chatReady          = presence.chatReady,
                isGenerating       = isGenerating,
                listening          = isRecording,
                transcribing       = isTranscribing,
                whisperLoaded      = whisperLoaded,
                attachedImage      = selectedImageUri,
                attachedDocument   = selectedDocumentName,
                canAttachImage     = isMmprojLoaded && presence.chatReady,
                onAttachImage      = {
                    imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onAttachDocument   = { documentPicker.launch(DOCUMENT_TYPES) },
                onClearAttachment  = {
                    selectedImageUri = null
                    selectedDocumentName = null
                    selectedDocumentText = null
                },
                onSend             = ::send,
                onStop             = { chatViewModel.stopGeneration() },
                onMic              = ::toggleMic
            )
        }

        InkSnackbarHost(
            snackbarState,
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 96.dp)
        )
    }
}

// ---- Document import ---------------------------------------------------------------

private sealed interface DocumentRead {
    data class Ok(val name: String, val text: String) : DocumentRead
    data object Unreadable : DocumentRead
    data object TooLarge : DocumentRead
}

/** Extracts text from a picked document, off the main thread. */
private suspend fun readDocument(context: Context, uri: Uri, charLimit: Int): DocumentRead =
    withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = resolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: "Document"
        val extracted = try {
            if (resolver.getType(uri) == "application/pdf") {
                if (android.os.Build.VERSION.SDK_INT >= 35) {
                    resolver.openFileDescriptor(uri, "r")?.use { pfd ->
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
                    resolver.openInputStream(uri)?.use { stream ->
                        PDDocument.load(stream).use { doc ->
                            PDFTextStripper().getText(doc).ifEmpty { null }
                        }
                    }
                }
            } else {
                resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }
        } catch (_: Exception) {
            null
        }
        when {
            extracted == null            -> DocumentRead.Unreadable
            extracted.length > charLimit -> DocumentRead.TooLarge
            else                         -> DocumentRead.Ok(name, extracted)
        }
    }
