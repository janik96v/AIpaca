package com.aipaca.app.ui.models

import android.os.StatFs
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aipaca.app.EngineState
import com.aipaca.app.data.DownloadProgress
import com.aipaca.app.data.DownloadState
import com.aipaca.app.data.DownloadedModelEntry
import com.aipaca.app.data.ModelDownloadManager
import com.aipaca.app.data.ModelType
import com.aipaca.app.engine.ModelHeader
import com.aipaca.app.engine.ModelHeaders
import com.aipaca.app.engine.formatContext
import com.aipaca.app.ui.components.DotGrid
import com.aipaca.app.ui.components.Emphasis
import com.aipaca.app.ui.components.IconAction
import com.aipaca.app.ui.components.InkDialog
import com.aipaca.app.ui.components.InkSnackbarHost
import com.aipaca.app.ui.components.OutlineButton
import com.aipaca.app.ui.components.ProgressTrack
import com.aipaca.app.ui.components.ScreenTitle
import com.aipaca.app.ui.components.TextAction
import com.aipaca.app.ui.components.Tracked
import com.aipaca.app.ui.components.hairlineBottom
import com.aipaca.app.ui.shell.displayModelName
import com.aipaca.app.ui.theme.Ink
import com.aipaca.app.ui.theme.InkType
import com.aipaca.app.ui.theme.Ph
import kotlinx.coroutines.launch
import java.util.Locale

/** A quant-picker request from the browse list. */
data class DownloadRequest(val repoId: String, val modelType: ModelType, val fileHint: String? = null)

/**
 * Models: what is on this phone (load, unload, delete), and — one level in —
 * the catalog and Hugging Face search to get more.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelScreen(modifier: Modifier = Modifier) {
    val scope    = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var browsing        by rememberSaveable { mutableStateOf(false) }
    var pendingLoad     by remember { mutableStateOf<DownloadedModelEntry?>(null) }
    var pendingDelete   by remember { mutableStateOf<DownloadedModelEntry?>(null) }
    var download        by remember { mutableStateOf<DownloadRequest?>(null) }
    var mmprojRepo      by remember { mutableStateOf<String?>(null) }
    var importing       by remember { mutableStateOf(false) }
    var speechLoadPath  by remember { mutableStateOf<String?>(null) }
    var visionLoadPath  by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = browsing) { browsing = false }

    fun load(entry: DownloadedModelEntry) {
        when (entry.modelType) {
            ModelType.LLM     -> pendingLoad = entry
            ModelType.WHISPER -> {
                speechLoadPath = entry.filePath
                EngineState.scope.launch { EngineState.loadWhisperModel(entry.filePath) }
            }
            ModelType.MMPROJ  -> {
                visionLoadPath = entry.filePath
                EngineState.scope.launch { EngineState.loadMmproj(entry.filePath) }
            }
        }
    }

    val importer = rememberModelImporter(
        onImporting = { importing = it },
        onImported  = { entry ->
            // A projector needs a chat model underneath; otherwise just keep it listed.
            if (entry.modelType != ModelType.MMPROJ || EngineState.isLoaded.value) load(entry)
        },
        onFailed    = { scope.launch { snackbar.showSnackbar("Could not open that file.") } }
    )

    Box(modifier.fillMaxSize()) {
        if (browsing) {
            BrowseModels(
                onBack     = { browsing = false },
                onDownload = { download = it }
            )
        } else {
            InstalledModels(
                importing      = importing,
                speechLoadPath = speechLoadPath,
                visionLoadPath = visionLoadPath,
                onLoad         = ::load,
                onDelete       = { pendingDelete = it },
                onBrowse       = { browsing = true },
                onOpenFile     = importer
            )
        }
        InkSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).fillMaxWidth())
    }

    pendingLoad?.let { entry ->
        ContextWindowDialog(
            path      = entry.filePath,
            name      = displayModelName(entry.filePath, ModelHeaders.peek(entry.filePath)),
            onPick    = { size ->
                pendingLoad = null
                // Process scope: leaving this screen must not cancel a load in flight.
                EngineState.scope.launch { EngineState.loadModel(entry.filePath, contextSize = size) }
            },
            onDismiss = { pendingLoad = null }
        )
    }

    pendingDelete?.let { entry ->
        InkDialog(
            title     = "Delete model",
            subtitle  = displayModelName(entry.filePath, ModelHeaders.peek(entry.filePath)),
            onDismiss = { pendingDelete = null },
            actions   = {
                TextAction("Cancel", onClick = { pendingDelete = null }, color = Ink.Meta)
                TextAction("Delete", onClick = {
                    pendingDelete = null
                    unloadIfLoaded(entry)
                    ModelDownloadManager.deleteDownload(entry.repoId, entry.fileName)
                })
            }
        ) {
            Text(
                "${entry.fileName} · ${approxSize(entry.sizeBytes)} will be removed from this phone.",
                style = InkType.Secondary,
                color = Ink.Secondary
            )
        }
    }

    download?.let { request ->
        GgufFilePickerSheet(
            repoId       = request.repoId,
            nameContains = request.fileHint,
            onDismiss    = { download = null },
            onFileSelected = { file ->
                ModelDownloadManager.clearProgress(request.repoId, file.name)
                ModelDownloadManager.startDownload(
                    repoId      = request.repoId,
                    fileName    = file.name,
                    modelType   = request.modelType,
                    downloadUrl = file.downloadUrl
                )
                download = null
                // Offer the vision projector alongside a chat model.
                if (request.modelType == ModelType.LLM) mmprojRepo = request.repoId
            }
        )
    }

    mmprojRepo?.let { repoId ->
        MmprojFilePickerSheet(
            repoId         = repoId,
            onDismiss      = { mmprojRepo = null },
            onFileSelected = { file ->
                ModelDownloadManager.startDownload(
                    repoId      = repoId,
                    fileName    = file.name,
                    modelType   = ModelType.MMPROJ,
                    downloadUrl = file.downloadUrl
                )
                mmprojRepo = null
            }
        )
    }
}

private fun unloadIfLoaded(entry: DownloadedModelEntry) {
    when (entry.modelType) {
        ModelType.LLM     -> if (EngineState.modelPath.value == entry.filePath) EngineState.unload()
        ModelType.WHISPER -> if (EngineState.whisperModelPath.value == entry.filePath) EngineState.unloadWhisper()
        ModelType.MMPROJ  -> if (EngineState.mmprojPath.value == entry.filePath) EngineState.unloadMmproj()
    }
}

// ---- Installed ----------------------------------------------------------------------

private enum class RowState { Idle, Reading, Loaded, Unavailable }

@Composable
private fun InstalledModels(
    importing: Boolean,
    speechLoadPath: String?,
    visionLoadPath: String?,
    onLoad: (DownloadedModelEntry) -> Unit,
    onDelete: (DownloadedModelEntry) -> Unit,
    onBrowse: () -> Unit,
    onOpenFile: () -> Unit
) {
    val entries        by ModelDownloadManager.downloadedModels.collectAsState()
    val progress       by ModelDownloadManager.downloadProgress.collectAsState()
    val modelPath      by EngineState.modelPath.collectAsState()
    val loadingPath    by EngineState.loadingModelPath.collectAsState()
    val remote         by EngineState.useOllama.collectAsState()
    val whisperPath    by EngineState.whisperModelPath.collectAsState()
    val whisperLoading by EngineState.isLoadingWhisperModel.collectAsState()
    val mmprojPath     by EngineState.mmprojPath.collectAsState()
    val mmprojLoading  by EngineState.isLoadingMmproj.collectAsState()
    val chatLoaded     by EngineState.isLoaded.collectAsState()
    val loadError      by EngineState.errorMessage.collectAsState()
    val whisperError   by EngineState.whisperError.collectAsState()
    val mmprojError    by EngineState.mmprojError.collectAsState()

    val context = LocalContext.current
    val totalStorage = remember { runCatching { StatFs(context.filesDir.path).totalBytes }.getOrDefault(0L) }
    val sorted = remember(entries) { entries.sortedBy { it.modelType.ordinal } }
    val active = progress.values.filter { it.state == DownloadState.DOWNLOADING }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        ScreenTitle("Models")

        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Tracked("On this phone · ${entries.size}", InkType.Count, color = Ink.Meta, maxLines = 1)
            Tracked(storageLine(entries.sumOf { it.sizeBytes }, totalStorage), InkType.Count, color = Ink.Meta, maxLines = 1)
        }

        Column {
            if (sorted.isEmpty()) {
                Text(
                    "Nothing on this phone yet. Browse the catalog, or open a .gguf file you already have.",
                    style    = InkType.Secondary,
                    color    = Ink.Secondary,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
            sorted.forEach { entry ->
                val state = when (entry.modelType) {
                    ModelType.LLM -> when {
                        !remote && loadingPath == entry.filePath -> RowState.Reading
                        !remote && modelPath == entry.filePath   -> RowState.Loaded
                        else                                     -> RowState.Idle
                    }
                    ModelType.WHISPER -> when {
                        whisperLoading && speechLoadPath == entry.filePath -> RowState.Reading
                        whisperPath == entry.filePath                      -> RowState.Loaded
                        else                                               -> RowState.Idle
                    }
                    ModelType.MMPROJ -> when {
                        mmprojLoading && visionLoadPath == entry.filePath -> RowState.Reading
                        mmprojPath == entry.filePath                      -> RowState.Loaded
                        !chatLoaded || remote                             -> RowState.Unavailable
                        else                                              -> RowState.Idle
                    }
                }
                InstalledRow(
                    entry    = entry,
                    state    = state,
                    onToggle = {
                        when (state) {
                            RowState.Idle        -> onLoad(entry)
                            RowState.Loaded      -> unloadIfLoaded(entry)
                            RowState.Reading,
                            RowState.Unavailable -> Unit
                        }
                    },
                    onDelete = { onDelete(entry) }
                )
            }
        }

        if (active.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Tracked("Downloading · ${active.size}", InkType.Count, color = Ink.Meta)
                active.forEach { DownloadRow(it) }
            }
        }

        listOfNotNull(
            loadError?.takeIf { !chatLoaded },
            whisperError,
            mmprojError
        ).forEach { message ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Tracked("Load failed", InkType.Label, color = Ink.White)
                Text(message, style = InkType.Secondary, color = Ink.Secondary)
            }
        }

        Column(
            modifier            = Modifier.padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlineButton(
                label        = "Browse models",
                icon         = Ph.MagnifyingGlass,
                trailingIcon = Ph.ArrowRight,
                fillWidth    = true,
                padding      = PaddingValues(15.dp),
                onClick      = onBrowse
            )
            OutlineButton(
                label    = if (importing) "Copying file" else "Open a .gguf file",
                icon     = Ph.FolderOpen,
                emphasis = Emphasis.Secondary,
                enabled  = !importing,
                padding  = PaddingValues(15.dp),
                onClick  = onOpenFile
            )
        }
    }
}

@Composable
private fun InstalledRow(
    entry: DownloadedModelEntry,
    state: RowState,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    val header by produceState(ModelHeaders.peek(entry.filePath), entry.filePath) {
        if (entry.modelType != ModelType.MMPROJ) value = ModelHeaders.read(entry.filePath, EngineState.engine)
    }
    val loaded = state == RowState.Loaded

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hairlineBottom(Ink.Divider)
            .padding(vertical = 16.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        DotGrid(
            columns  = 2,
            rows     = 2,
            gap      = 2.dp,
            modifier = Modifier.alpha(if (loaded) 1f else .22f)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(enabled = state != RowState.Unavailable, role = Role.Button, onClick = onToggle),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(
                text     = displayModelName(entry.filePath, header),
                style    = InkType.Name,
                color    = if (loaded) Ink.White else Ink.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Tracked(rowMeta(entry, header, loaded), InkType.Meta, color = Ink.Meta)
        }
        val (label, tint) = when (state) {
            RowState.Idle        -> "Load" to Ink.Meta
            RowState.Reading     -> "Reading" to Ink.White
            RowState.Loaded      -> "Unload" to Ink.White
            RowState.Unavailable -> "Load" to Ink.Placeholder
        }
        TextAction(
            label    = label,
            onClick  = onToggle,
            color    = tint,
            enabled  = state == RowState.Idle || state == RowState.Loaded,
            style    = InkType.Action,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
        IconAction(
            icon               = Ph.Trash,
            contentDescription = "Delete ${entry.fileName}",
            onClick            = onDelete,
            size               = 16.dp,
            tint               = Ink.DisabledIcon,
            touch              = 40.dp,
            // As narrow as the design's glyph so the name column keeps its width.
            modifier           = Modifier.width(24.dp)
        )
    }
}

/**
 * Loaded: `36 BLOCKS · 32/8 HEADS`. Otherwise `Q4_K_M · 2.7 GB · 128K CTX`;
 * speech and vision files say what they are.
 */
private fun rowMeta(entry: DownloadedModelEntry, header: ModelHeader?, loaded: Boolean): String {
    val size = approxSize(entry.sizeBytes)
    return when (entry.modelType) {
        ModelType.WHISPER -> "Speech · $size"
        ModelType.MMPROJ  -> "Vision · $size"
        ModelType.LLM     -> when {
            loaded && header != null && header.blocks > 0 ->
                "${header.blocks} blocks · ${header.heads}/${header.kvHeads} heads"
            header != null -> listOfNotNull(
                header.quant.ifBlank { null },
                size,
                header.ctx.takeIf { it > 0 }?.let { "${formatContext(it)} ctx" }
            ).joinToString(" · ")
            else -> size
        }
    }
}

@Composable
private fun DownloadRow(progress: DownloadProgress) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                progress.fileName,
                style    = InkType.Name,
                color    = Ink.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextAction(
                "Cancel",
                onClick = { ModelDownloadManager.cancelDownload(progress.repoId, progress.fileName) },
                style   = InkType.Action
            )
        }
        ProgressTrack(
            fraction = progress.fraction.takeIf { it >= 0f },
            label    = if (progress.fraction >= 0f) "${(progress.fraction * 100).toInt()}%" else approxSize(progress.bytesRead)
        )
    }
}

/** `5.3 / 128 GB`. */
private fun storageLine(used: Long, total: Long): String {
    val usedGb = String.format(Locale.US, "%.1f", used / 1e9)
    return if (total > 0) "$usedGb / ${Math.round(total / 1e9)} GB" else "$usedGb GB"
}
