package com.aipaca.app.ui.models

import android.app.ActivityManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.aipaca.app.data.HfModel
import com.aipaca.app.data.HuggingFaceApi
import com.aipaca.app.data.HuggingFaceApiException
import com.aipaca.app.data.ModelDownloadManager
import com.aipaca.app.data.ModelType
import com.aipaca.app.ui.components.ChipButton
import com.aipaca.app.ui.components.Emphasis
import com.aipaca.app.ui.components.InkIcon
import com.aipaca.app.ui.components.InkTextField
import com.aipaca.app.ui.components.OutlineButton
import com.aipaca.app.ui.components.ProgressTrack
import com.aipaca.app.ui.components.Tracked
import com.aipaca.app.ui.components.hairlineBottom
import com.aipaca.app.ui.theme.Ink
import com.aipaca.app.ui.theme.InkType
import com.aipaca.app.ui.theme.Ph
import kotlinx.coroutines.delay

private sealed interface SearchState {
    data object Idle : SearchState
    data object Searching : SearchState
    data class Results(val models: List<HfModel>) : SearchState
    data class Failed(val message: String) : SearchState
}

/** `← MODELS`, `AVAILABLE / MODELS`, search, filter chips, then the list. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrowseModels(
    onBack: () -> Unit,
    onDownload: (DownloadRequest) -> Unit,
    modifier: Modifier = Modifier
) {
    val installed by ModelDownloadManager.downloadedModels.collectAsState()
    val progress  by ModelDownloadManager.downloadProgress.collectAsState()

    var query  by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }   // ModelKind name, null = All
    val kind = filter?.let { name -> ModelKind.entries.firstOrNull { it.name == name } }

    val context = LocalContext.current
    val totalRam = remember { deviceRamBytes(context) }

    // Hub search, debounced; the curated list filters locally as you type.
    val api = remember { HuggingFaceApi() }
    DisposableEffect(api) { onDispose { api.close() } }
    var search by remember { mutableStateOf<SearchState>(SearchState.Idle) }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) { search = SearchState.Idle; return@LaunchedEffect }
        delay(400)
        search = SearchState.Searching
        search = try {
            SearchState.Results(api.searchModels(q))
        } catch (e: HuggingFaceApiException) {
            SearchState.Failed(e.message ?: "Search failed")
        }
    }

    val curated = Catalog.filter { (kind == null || it.kind == kind) && it.matchesQuery(query) }
    val curatedRepos = Catalog.map { it.repoId }.toSet()

    Column(modifier.fillMaxSize()) {
        Column(
            modifier            = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .clickable(onClickLabel = "Back to models", role = Role.Button, onClick = onBack)
                    .padding(vertical = 6.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                InkIcon(Ph.ArrowLeft, 14.dp, tint = Ink.Meta)
                Tracked("Models", InkType.Label, color = Ink.Meta)
            }
            Tracked("Available\nmodels", InkType.BrowseTitle, color = Ink.Text)
            InkTextField(
                value           = query,
                onValueChange   = { query = it },
                placeholder     = "Search Hugging Face",
                leadingIcon     = Ph.MagnifyingGlass,
                textStyle       = InkType.Secondary.copy(lineHeight = InkType.Input.lineHeight),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement   = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip("All", selected = kind == null) { filter = null }
                ModelKind.entries.forEach { k ->
                    FilterChip(k.label, selected = kind == k) { filter = k.name }
                }
            }
        }

        LazyColumn(
            modifier       = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp)
        ) {
            items(curated, key = { it.repoId + (it.fileHint ?: "") }) { model ->
                val active = model.activeDownload(progress.values)
                val failed = model.failedDownload(progress.values)
                val has = model.isInstalled(installed)
                val blocked = !has && !model.fitsIn(totalRam)
                CatalogRow(
                    name    = model.name,
                    meta    = model.meta,
                    note    = buildString {
                        append(model.note)
                        if (blocked) append(" Needs ${model.needsRamGb} GB RAM.")
                        if (failed != null && active == null) append(" Last download failed — try again.")
                    },
                    action  = when {
                        blocked        -> RowAction.TooLarge
                        has            -> RowAction.Installed
                        active != null -> RowAction.Cancel
                        else           -> RowAction.Download
                    },
                    progress = active?.let { it.fraction },
                    onAction = {
                        when {
                            active != null -> ModelDownloadManager.cancelDownload(active.repoId, active.fileName)
                            !has && !blocked -> onDownload(DownloadRequest(model.repoId, model.modelType, model.fileHint))
                        }
                    }
                )
            }

            if (curated.isEmpty() && query.trim().length < 2) {
                item {
                    Text(
                        "Nothing curated here yet — search Hugging Face above.",
                        style    = InkType.Secondary,
                        color    = Ink.Secondary,
                        modifier = Modifier.padding(top = 18.dp)
                    )
                }
            }

            when (val s = search) {
                SearchState.Idle -> Unit
                SearchState.Searching -> item { SectionNote("Searching Hugging Face") }
                is SearchState.Failed -> item { SectionNote("Search failed · ${s.message}") }
                is SearchState.Results -> {
                    val hits = s.models.filter { it.id !in curatedRepos && (kind == null || it.kind() == kind) }
                    item { SectionNote(if (hits.isEmpty()) "No other GGUF repos on Hugging Face" else "On Hugging Face · ${hits.size}") }
                    items(hits, key = { "hf:" + it.id }) { hit ->
                        val type = if (hit.kind() == ModelKind.Speech) ModelType.WHISPER else ModelType.LLM
                        val active = progress.values.firstOrNull {
                            it.repoId == hit.id && it.state == com.aipaca.app.data.DownloadState.DOWNLOADING
                        }
                        val has = installed.any { it.repoId == hit.id && it.modelType != ModelType.MMPROJ }
                        CatalogRow(
                            name     = hit.id.substringAfter('/'),
                            meta     = "${hit.id.substringBefore('/')} · ${compactCount(hit.downloads)} downloads",
                            note     = null,
                            action   = when {
                                has            -> RowAction.Installed
                                active != null -> RowAction.Cancel
                                else           -> RowAction.Download
                            },
                            progress = active?.fraction,
                            onAction = {
                                when {
                                    active != null -> ModelDownloadManager.cancelDownload(active.repoId, active.fileName)
                                    !has -> onDownload(DownloadRequest(hit.id, type))
                                }
                            }
                        )
                    }
                }
            }

            item {
                Text(
                    "Builds larger than this device's memory stay listed but dimmed, with the reason in place of the action.",
                    style    = InkType.Secondary,
                    color    = Ink.Secondary,
                    modifier = Modifier.padding(top = 18.dp)
                )
            }
            item { QuantGuide(Modifier.padding(top = 22.dp)) }
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    ChipButton(
        label     = label,
        selected  = selected,
        idleColor = Ink.Filter,
        padding   = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
        onClick   = onClick
    )
}

@Composable
private fun SectionNote(text: String) {
    Tracked(text, InkType.Count, color = Ink.Meta, modifier = Modifier.padding(top = 22.dp, bottom = 4.dp))
}

private enum class RowAction(val label: String, val icon: ImageVector, val emphasis: Emphasis) {
    Download("Download", Ph.DownloadSimple, Emphasis.Primary),
    Cancel("Cancel", Ph.X, Emphasis.Primary),
    Installed("Installed", Ph.Check, Emphasis.Secondary),
    TooLarge("Too large", Ph.Prohibit, Emphasis.Secondary)
}

@Composable
private fun CatalogRow(
    name: String,
    meta: String,
    note: String?,
    action: RowAction,
    progress: Float?,
    onAction: () -> Unit
) {
    val dimmed = action == RowAction.TooLarge
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .hairlineBottom(Ink.Divider)
            .padding(vertical = 18.dp)
            .alpha(if (dimmed) .55f else 1f),
        verticalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(name, style = InkType.Name, color = Ink.Text)
                Tracked(meta, InkType.Meta, color = Ink.Meta)
                if (note != null) Text(note, style = InkType.Secondary, color = Ink.Secondary)
            }
            OutlineButton(
                label    = action.label,
                icon     = action.icon,
                iconSize = 13.dp,
                emphasis = action.emphasis,
                tint     = if (action.emphasis == Emphasis.Secondary) Ink.Meta else null,
                padding  = PaddingValues(horizontal = 13.dp, vertical = 12.dp),
                style    = InkType.chrome(.18f),
                gap      = 7.dp,
                onClick  = onAction
            )
        }
        if (progress != null) {
            ProgressTrack(
                fraction = progress.takeIf { it >= 0f },
                label    = if (progress >= 0f) "${(progress * 100).toInt()}%" else null
            )
        }
    }
}

// ---- Quantisation guide -----------------------------------------------------------

private data class QuantEntry(val format: String, val gpu: Boolean, val note: String)

private val quantGuideEntries = listOf(
    QuantEntry("Q4_0",   gpu = true,  note = "Fastest, smallest, lowest quality"),
    QuantEntry("Q4_1",   gpu = true,  note = "Slightly higher quality than Q4_0"),
    QuantEntry("Q4_K_S", gpu = true,  note = "Good quality, compact size"),
    QuantEntry("Q4_K_M", gpu = true,  note = "Best all-round choice"),
    QuantEntry("Q5_0",   gpu = false, note = "CPU only — no OpenCL kernel"),
    QuantEntry("Q5_K_S", gpu = true,  note = "Higher quality, modest size increase"),
    QuantEntry("Q5_K_M", gpu = true,  note = "High quality, recommended over Q5_0"),
    QuantEntry("Q6_K",   gpu = true,  note = "Near-lossless quality"),
    QuantEntry("Q8_0",   gpu = true,  note = "Effectively lossless, largest"),
    QuantEntry("IQ4_NL", gpu = true,  note = "Importance quant, efficient")
)

/** Which GGUF quantisations run on the Adreno GPU — collapsed by default. */
@Composable
private fun QuantGuide(modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = if (expanded) "Collapse" else "Expand", role = Role.Button) { expanded = !expanded }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Tracked("GPU compatibility", InkType.Count, color = Ink.Meta, modifier = Modifier.weight(1f))
            InkIcon(if (expanded) Ph.CaretUp else Ph.CaretDown, 12.dp, tint = Ink.Meta)
        }
        AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "The Adreno OpenCL backend has optimised kernels for some formats only; everything else falls back to the CPU, which is much slower.",
                    style = InkType.Secondary,
                    color = Ink.Secondary
                )
                quantGuideEntries.forEach { entry ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Tracked(
                            entry.format,
                            InkType.ReadoutValue,
                            color    = if (entry.gpu) Ink.Text else Ink.Placeholder,
                            modifier = Modifier.width(64.dp)
                        )
                        Text(
                            (if (entry.gpu) "GPU · " else "CPU · ") + entry.note,
                            style    = InkType.Secondary,
                            color    = Ink.Secondary,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Text(
                    "Look for Q4_0, Q4_K, Q5_K, Q6_K, Q8_0 or IQ4_NL in a file name. Avoid Q5_0, Q5_1 and Q2/Q3 builds.",
                    style = InkType.Secondary,
                    color = Ink.Placeholder
                )
            }
        }
    }
}

private fun deviceRamBytes(context: Context): Long = try {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
} catch (_: Exception) {
    0L
}
