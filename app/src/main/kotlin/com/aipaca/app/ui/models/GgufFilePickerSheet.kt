package com.aipaca.app.ui.models

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aipaca.app.data.HfFile
import com.aipaca.app.data.HuggingFaceApi
import com.aipaca.app.data.HuggingFaceApiException
import com.aipaca.app.ui.components.ChipTone
import com.aipaca.app.ui.components.OutlineButton
import com.aipaca.app.ui.components.StatusChip
import com.aipaca.app.ui.components.TextAction
import com.aipaca.app.ui.components.Tracked
import com.aipaca.app.ui.components.hairlineBottom
import com.aipaca.app.ui.components.hairlineTop
import com.aipaca.app.ui.theme.Ink
import com.aipaca.app.ui.theme.InkType
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

/**
 * Loading/error/content state for the GGUF file listing fetched from
 * [HuggingFaceApi]. Kept private — callers only ever see the composable.
 */
private sealed interface PickerUiState {
    data object Loading : PickerUiState
    data class Error(val message: String) : PickerUiState
    data class Loaded(val files: List<HfFile>) : PickerUiState
}

/**
 * Bottom sheet listing the GGUF/BIN files available in a Hugging Face repo, so
 * the user can pick a quantisation variant to download.
 *
 * Fetches via [HuggingFaceApi.listModelFiles] on first composition (and on
 * every retry). Each row shows the file name, a human-readable size, and a
 * GPU-compatibility chip derived from the quant token in the file name
 * (mirrors the GPU compatibility guide on the browse screen).
 *
 * Selecting a row calls [onFileSelected] with the chosen [HfFile] — the
 * caller is expected to hand it to `ModelDownloadManager` to start the
 * actual download — and then dismisses the sheet.
 *
 * @param repoId        Hugging Face repo id, e.g. `"unsloth/gemma-4-E2B-it-GGUF"`.
 * @param nameContains  Only list files whose name contains this (case-insensitive) —
 *                       picks one size out of a shared repo such as whisper.cpp.
 * @param onDismiss     Called when the sheet should close (swipe down, scrim tap,
 *                       or after a successful selection).
 * @param onFileSelected Called with the tapped file; the sheet dismisses itself
 *                       right after.
 * @param api           Injectable [HuggingFaceApi] — tests can supply a fake.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GgufFilePickerSheet(
    repoId: String,
    onDismiss: () -> Unit,
    onFileSelected: (HfFile) -> Unit,
    modifier: Modifier = Modifier,
    nameContains: String? = null,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    api: HuggingFaceApi = remember { HuggingFaceApi() }
) {
    val scope = rememberCoroutineScope()
    var uiState by remember(repoId) { mutableStateOf<PickerUiState>(PickerUiState.Loading) }

    fun load() {
        uiState = PickerUiState.Loading
        scope.launch {
            uiState = try {
                val files = api.listModelFiles(repoId)
                PickerUiState.Loaded(
                    if (nameContains == null) files
                    else files.filter { it.name.lowercase().contains(nameContains.lowercase()) }
                )
            } catch (e: HuggingFaceApiException) {
                PickerUiState.Error(e.message ?: "Failed to load files for $repoId")
            }
        }
    }

    LaunchedEffect(repoId) { load() }

    fun dismiss() {
        scope.launch {
            sheetState.hide()
        }.invokeOnCompletion {
            onDismiss()
        }
    }

    InkSheet(onDismiss = ::dismiss, sheetState = sheetState, modifier = modifier) {
        SheetHeader(label = "Select quant", title = repoId)

        when (val state = uiState) {
            is PickerUiState.Loading -> PickerNote("Loading files")
            is PickerUiState.Error -> PickerErrorState(
                message = state.message,
                onRetry = { load() }
            )
            is PickerUiState.Loaded -> {
                if (state.files.isEmpty()) {
                    PickerNote("No GGUF or BIN files found in this repo.")
                } else {
                    LazyColumn(
                        modifier       = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)
                    ) {
                        items(state.files, key = { it.name }) { file ->
                            val (chipLabel, chipTone) = gpuCompatibility(file.name)
                            FileRow(
                                file    = file,
                                chip    = chipLabel,
                                tone    = chipTone,
                                onClick = {
                                    onFileSelected(file)
                                    dismiss()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---- Mmproj picker sheet ------------------------------------------------

/**
 * Bottom sheet listing the mmproj (multimodal projector) GGUF files available
 * in a Hugging Face repo, so the user can optionally download a vision adapter
 * alongside the main model.
 *
 * Fetches via [HuggingFaceApi.listMmprojFiles] on first composition. Each row
 * shows the file name and a human-readable size. The user can pick one to
 * download or tap "Skip" to dismiss without downloading. Repos without
 * projector files (or a failed listing) dismiss silently — the main download
 * has already started and must not be blocked on this.
 *
 * @param repoId        Hugging Face repo id, e.g. `"unsloth/gemma-4-E2B-it-GGUF"`.
 * @param onDismiss     Called when the sheet should close.
 * @param onFileSelected Called with the tapped mmproj file.
 * @param api           Injectable [HuggingFaceApi] — tests can supply a fake.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MmprojFilePickerSheet(
    repoId: String,
    onDismiss: () -> Unit,
    onFileSelected: (HfFile) -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    api: HuggingFaceApi = remember { HuggingFaceApi() }
) {
    val scope = rememberCoroutineScope()
    var files by remember(repoId) { mutableStateOf<List<HfFile>?>(null) }

    LaunchedEffect(repoId) {
        val found = try {
            api.listMmprojFiles(repoId)
        } catch (_: HuggingFaceApiException) {
            emptyList()
        }
        if (found.isEmpty()) onDismiss() else files = found
    }

    fun dismiss() {
        scope.launch {
            sheetState.hide()
        }.invokeOnCompletion {
            onDismiss()
        }
    }

    // Nothing to show until we know there is something to offer.
    val list = files ?: return

    InkSheet(onDismiss = ::dismiss, sheetState = sheetState, modifier = modifier) {
        SheetHeader(
            label = "Vision adapter",
            title = "Download a vision projector?",
            body  = "This repo has mmproj files for image understanding. Pick one, or skip."
        )
        LazyColumn(
            modifier       = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp)
        ) {
            items(list, key = { it.name }) { file ->
                FileRow(
                    file    = file,
                    chip    = "Vision",
                    tone    = ChipTone.Neutral,
                    onClick = {
                        onFileSelected(file)
                        dismiss()
                    }
                )
            }
        }
        Box(
            modifier         = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            TextAction("Skip", onClick = ::dismiss, color = Ink.Meta)
        }
    }
}

// ---- Sheet chrome -----------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InkSheet(
    onDismiss: () -> Unit,
    sheetState: SheetState,
    modifier: Modifier,
    content: @Composable () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        modifier         = modifier,
        shape            = RoundedCornerShape(0.dp),
        containerColor   = Ink.Sheet,
        contentColor     = Ink.Text,
        tonalElevation   = 0.dp,
        scrimColor       = Ink.Scrim,
        dragHandle       = null
    ) {
        Column(Modifier.fillMaxWidth().hairlineTop(Ink.Border)) { content() }
    }
}

@Composable
private fun SheetHeader(label: String, title: String, body: String? = null) {
    Column(
        modifier            = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Tracked(label, InkType.SheetTitle, color = Ink.Text)
        Text(title, style = InkType.Name, color = Ink.Meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (body != null) Text(body, style = InkType.Secondary, color = Ink.Secondary)
    }
}

@Composable
private fun FileRow(
    file: HfFile,
    chip: String,
    tone: ChipTone,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hairlineBottom(Ink.Divider)
            .clickable(onClickLabel = "Download ${file.name}", role = Role.Button, onClick = onClick)
            .padding(vertical = 15.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(
                text     = file.name,
                style    = InkType.Name,
                color    = Ink.Text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Tracked(formatFileSize(file.sizeBytes), InkType.Meta, color = Ink.Meta)
        }
        StatusChip(text = chip, tone = tone)
    }
}

// ---- Loading / error states ----------------------------------------------

@Composable
private fun PickerNote(text: String) {
    Tracked(
        text,
        InkType.Label,
        color    = Ink.Meta,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 40.dp)
    )
}

@Composable
private fun PickerErrorState(
    message: String,
    onRetry: () -> Unit
) {
    Column(
        modifier            = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Tracked("Couldn't load files", InkType.Label, color = Ink.White)
        Text(message, style = InkType.Secondary, color = Ink.Secondary)
        OutlineButton(label = "Retry", onClick = onRetry)
    }
}

// ---- Helpers --------------------------------------------------------------

/** GPU-compatible quant tokens — mirrors the GPU compatibility guide in BrowseModels. */
private val gpuCompatibleQuants = setOf(
    "Q4_0", "Q4_1", "Q4_K_S", "Q4_K_M",
    "Q5_K_S", "Q5_K_M", "Q6_K", "Q8_0", "IQ4_NL"
)

/** CPU-only quant tokens with a *known* GPU kernel gap (called out explicitly, not just unmatched). */
private val cpuOnlyQuants = setOf("Q5_0", "Q5_1", "Q2_K", "Q3_K")

/**
 * Derives a (label, tone) pair for the GPU-compatibility chip from a GGUF
 * file name, by matching the known quantization tokens used across the
 * repo's Adreno OpenCL backend (see the GPU compatibility guide in BrowseModels for the
 * authoritative list).
 *
 * `internal` (not `private`) so [GgufFilePickerSheetTest] can exercise it
 * directly as a pure function, without pulling in Compose/Robolectric.
 */
internal fun gpuCompatibility(fileName: String): Pair<String, ChipTone> {
    val upper = fileName.uppercase()
    val matchedGpu = gpuCompatibleQuants.firstOrNull { upper.contains(it) }
    if (matchedGpu != null) return "GPU · $matchedGpu" to ChipTone.Success

    val matchedCpu = cpuOnlyQuants.firstOrNull { upper.contains(it) }
    if (matchedCpu != null) return "CPU ONLY · $matchedCpu" to ChipTone.Warning

    return "GPU · UNKNOWN" to ChipTone.Neutral
}

/**
 * Formats a byte count as a human-readable size string, e.g. "2.4 GB".
 *
 * `internal` (not `private`) so [GgufFilePickerSheetTest] can exercise it directly.
 */
internal fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(0, units.size - 1)
    val value = bytes / 1024.0.pow(digitGroups)
    return if (digitGroups == 0) {
        "$bytes ${units[digitGroups]}"
    } else {
        String.format(Locale.US, "%.1f %s", value, units[digitGroups])
    }
}
