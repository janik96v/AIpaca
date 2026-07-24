package com.aipaca.app.ui.models

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aipaca.app.data.HfFile
import com.aipaca.app.data.HuggingFaceApi
import com.aipaca.app.data.HuggingFaceApiException
import com.aipaca.app.ui.components.ChipTone
import com.aipaca.app.ui.components.EditorialDivider
import com.aipaca.app.ui.components.MonoLabel
import com.aipaca.app.ui.components.MonoLabelTone
import com.aipaca.app.ui.components.StatusChip
import com.aipaca.app.ui.theme.AIpacaTheme
import com.aipaca.app.ui.theme.AlpacaColors
import com.aipaca.app.ui.theme.AlpacaType
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
 * Material3 modal bottom sheet listing the GGUF/BIN files available in a
 * Hugging Face repo, so the user can pick a quantisation variant to
 * download.
 *
 * Fetches via [HuggingFaceApi.listModelFiles] on first composition (and on
 * every retry). Each row shows the file name, a human-readable size, and a
 * GPU-compatibility chip derived from the quant token in the file name
 * (mirrors the reference table in [QuantGuide] on [ModelScreen]).
 *
 * Selecting a row calls [onFileSelected] with the chosen [HfFile] — the
 * caller is expected to hand it to `ModelDownloadManager` to start the
 * actual download — and then dismisses the sheet.
 *
 * @param repoId        Hugging Face repo id, e.g. `"unsloth/gemma-4-E2B-it-GGUF"`.
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
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    api: HuggingFaceApi = remember { HuggingFaceApi() }
) {
    val scope = rememberCoroutineScope()
    var uiState by remember(repoId) { mutableStateOf<PickerUiState>(PickerUiState.Loading) }

    fun load() {
        uiState = PickerUiState.Loading
        scope.launch {
            uiState = try {
                PickerUiState.Loaded(api.listModelFiles(repoId))
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

    ModalBottomSheet(
        onDismissRequest = ::dismiss,
        sheetState = sheetState,
        modifier = modifier,
        containerColor = AlpacaColors.Surface.Elevated
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        ) {
            MonoLabel(text = "SELECT QUANT", tone = MonoLabelTone.Accent)
            Spacer(Modifier.height(6.dp))
            Text(
                text = repoId,
                style = AlpacaType.TitleMd,
                color = AlpacaColors.Text.Primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(16.dp))
            EditorialDivider(color = AlpacaColors.Line.Subtle)
        }

        when (val state = uiState) {
            is PickerUiState.Loading -> PickerLoadingState()
            is PickerUiState.Error -> PickerErrorState(
                message = state.message,
                onRetry = { load() }
            )
            is PickerUiState.Loaded -> {
                if (state.files.isEmpty()) {
                    PickerEmptyState()
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        items(state.files, key = { it.name }) { file ->
                            GgufFileRow(
                                file = file,
                                onClick = {
                                    onFileSelected(file)
                                    dismiss()
                                }
                            )
                            EditorialDivider(
                                color = AlpacaColors.Line.Subtle,
                                modifier = Modifier.padding(horizontal = 24.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---- Row ----------------------------------------------------------------

@Composable
private fun GgufFileRow(
    file: HfFile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (chipLabel, chipTone) = gpuCompatibility(file.name)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                style = AlpacaType.BodyMd,
                color = AlpacaColors.Text.Primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = formatFileSize(file.sizeBytes),
                style = AlpacaType.BodySm,
                color = AlpacaColors.Text.Muted
            )
        }
        Spacer(Modifier.width(12.dp))
        StatusChip(text = chipLabel, tone = chipTone)
    }
}

// ---- Loading / error / empty states --------------------------------------

@Composable
private fun PickerLoadingState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(color = AlpacaColors.Accent.Primary)
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Loading files…",
            style = AlpacaType.BodySm,
            color = AlpacaColors.Text.Muted
        )
    }
}

@Composable
private fun PickerErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = AlpacaColors.State.Error,
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Couldn't load files",
            style = AlpacaType.TitleMd,
            color = AlpacaColors.Text.Primary
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = message,
            style = AlpacaType.BodySm,
            color = AlpacaColors.Text.Muted
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(
                containerColor = AlpacaColors.Accent.Primary,
                contentColor = AlpacaColors.Text.OnAccent
            )
        ) {
            Text(text = "Retry", style = AlpacaType.LabelLg)
        }
    }
}

@Composable
private fun PickerEmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 40.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "No GGUF or BIN files found in this repo.",
            style = AlpacaType.BodySm,
            color = AlpacaColors.Text.Muted
        )
    }
}

// ---- Helpers --------------------------------------------------------------

/** GPU-compatible quant tokens — mirrors the reference table in ModelScreen's QuantGuide. */
private val gpuCompatibleQuants = setOf(
    "Q4_0", "Q4_1", "Q4_K_S", "Q4_K_M",
    "Q5_K_S", "Q5_K_M", "Q6_K", "Q8_0", "IQ4_NL"
)

/** CPU-only quant tokens with a *known* GPU kernel gap (called out explicitly, not just unmatched). */
private val cpuOnlyQuants = setOf("Q5_0", "Q5_1", "Q2_K", "Q3_K")

/**
 * Derives a (label, tone) pair for the GPU-compatibility chip from a GGUF
 * file name, by matching the known quantization tokens used across the
 * repo's Adreno OpenCL backend (see [ModelScreen]'s QuantGuide for the
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

// ---- Previews ---------------------------------------------------------------

@Preview(showBackground = true, backgroundColor = 0xFF14140F)
@Composable
private fun GgufFileRowPreview() {
    AIpacaTheme {
        Column {
            GgufFileRow(
                file = HfFile(
                    name = "gemma-4-E2B-it-Q4_K_M.gguf",
                    sizeBytes = 2_400_000_000L,
                    downloadUrl = "https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q4_K_M.gguf"
                ),
                onClick = {}
            )
            EditorialDivider(color = AlpacaColors.Line.Subtle)
            GgufFileRow(
                file = HfFile(
                    name = "gemma-4-E2B-it-Q5_0.gguf",
                    sizeBytes = 2_800_000_000L,
                    downloadUrl = "https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q5_0.gguf"
                ),
                onClick = {}
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF14140F)
@Composable
private fun PickerErrorStatePreview() {
    AIpacaTheme {
        PickerErrorState(message = "Hugging Face returned HTTP 404", onRetry = {})
    }
}
