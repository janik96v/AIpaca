package com.aipaca.app.ui.models

import com.aipaca.app.data.DownloadProgress
import com.aipaca.app.data.DownloadState
import com.aipaca.app.data.DownloadedModelEntry
import com.aipaca.app.data.HfModel
import com.aipaca.app.data.ModelType
import java.util.Locale
import kotlin.math.ceil

/** Browse filters, in chip order. */
enum class ModelKind(val label: String) {
    Chat("Chat"),
    Code("Code"),
    Vision("Vision"),
    Speech("Speech"),
    Embedding("Embedding")
}

/**
 * A curated model: a Hugging Face repo the app has been tested against (or
 * flags as experimental), with the copy shown in the browse list.
 *
 * @param fileHint   identifies this entry's files inside a shared repo — the
 *                   Whisper sizes all live in `ggerganov/whisper.cpp`.
 * @param contextLabel trained context, only where it is known for certain.
 */
data class CatalogModel(
    val name: String,
    val repoId: String,
    val modelType: ModelType,
    val kind: ModelKind,
    val quant: String,
    val sizeBytes: Long,
    val note: String,
    val contextLabel: String? = null,
    val fileHint: String? = null,
    val experimental: Boolean = false
) {
    /** `Q4_K_M · 2.7 GB · 128K CTX` / `SPEECH · 142 MB` (rendered uppercase). */
    val meta: String
        get() = if (modelType == ModelType.WHISPER) {
            "Speech · ${approxSize(sizeBytes)}"
        } else {
            listOfNotNull(quant, approxSize(sizeBytes), contextLabel?.let { "$it ctx" }).joinToString(" · ")
        }

    /**
     * RAM this build needs to be worth offering: the weights plus ~2.5 GB for
     * the OS, the app and a working KV cache. Speech models are always small.
     */
    val needsRamGb: Int
        get() = if (modelType == ModelType.WHISPER) 0 else ceil(sizeBytes / 1e9 + 2.5).toInt()

    fun fitsIn(totalRamBytes: Long): Boolean =
        needsRamGb == 0 || totalRamBytes <= 0 || totalRamBytes / 1e9 >= needsRamGb

    fun matches(repo: String, fileName: String): Boolean =
        repo == repoId && (fileHint == null || fileName.lowercase().contains(fileHint))

    fun isInstalled(entries: List<DownloadedModelEntry>): Boolean =
        entries.any { it.modelType != ModelType.MMPROJ && matches(it.repoId, it.fileName) }

    /** The in-flight download for this entry, if any. */
    fun activeDownload(progress: Collection<DownloadProgress>): DownloadProgress? =
        progress.firstOrNull { it.state == DownloadState.DOWNLOADING && matches(it.repoId, it.fileName) }

    fun failedDownload(progress: Collection<DownloadProgress>): DownloadProgress? =
        progress.firstOrNull { it.state == DownloadState.FAILED && matches(it.repoId, it.fileName) }

    fun matchesQuery(query: String): Boolean {
        val q = query.trim().lowercase()
        return q.isEmpty() || name.lowercase().contains(q) || repoId.lowercase().contains(q) ||
            kind.label.lowercase().contains(q)
    }
}

private const val GB = 1_000_000_000L
private const val MB = 1_000_000L

val Catalog: List<CatalogModel> = listOf(
    CatalogModel(
        name      = "Qwen3.5 4B",
        repoId    = "unsloth/Qwen3.5-4B-GGUF",
        modelType = ModelType.LLM,
        kind      = ModelKind.Chat,
        quant     = "Q4_K_M",
        sizeBytes = 2_700 * MB,
        note      = "Best all-rounder that still fits comfortably in 8 GB of RAM."
    ),
    CatalogModel(
        name      = "Gemma 4 E2B Instruct",
        repoId    = "unsloth/gemma-4-E2B-it-GGUF",
        modelType = ModelType.LLM,
        kind      = ModelKind.Chat,
        quant     = "Q4_0",
        sizeBytes = 2_500 * MB,
        note      = "Fastest first token on Adreno GPUs. Good default for older phones."
    ),
    CatalogModel(
        name         = "Qwen3 4B",
        repoId       = "Qwen/Qwen3-4B-GGUF",
        modelType    = ModelType.LLM,
        kind         = ModelKind.Chat,
        quant        = "Q4_0",
        sizeBytes    = 2_600 * MB,
        contextLabel = "32K",
        note         = "Reasoning, coding and many languages, with a thinking mode."
    ),
    CatalogModel(
        name         = "Qwen 2.5 3B Instruct",
        repoId       = "Qwen/Qwen2.5-3B-Instruct-GGUF",
        modelType    = ModelType.LLM,
        kind         = ModelKind.Chat,
        quant        = "Q4_0",
        sizeBytes    = 1_900 * MB,
        contextLabel = "32K",
        note         = "Small and quick. Follows instructions well, codes and does maths."
    ),
    CatalogModel(
        name         = "HY-MT 1.5 1.8B",
        repoId       = "tencent/HY-MT1.5-1.8B-GGUF",
        modelType    = ModelType.LLM,
        kind         = ModelKind.Chat,
        quant        = "TBD",
        sizeBytes    = 440 * MB,
        note         = "Translation across many language pairs. Experimental — its GGUF builds are unconfirmed.",
        experimental = true
    ),
    CatalogModel(
        name      = "Whisper tiny",
        repoId    = WHISPER_REPO,
        modelType = ModelType.WHISPER,
        kind      = ModelKind.Speech,
        quant     = "F16",
        sizeBytes = 75 * MB,
        fileHint  = "tiny",
        note      = "Fastest, smallest dictation model."
    ),
    CatalogModel(
        name      = "Whisper base",
        repoId    = WHISPER_REPO,
        modelType = ModelType.WHISPER,
        kind      = ModelKind.Speech,
        quant     = "F16",
        sizeBytes = 142 * MB,
        fileHint  = "base",
        note      = "On-device dictation. Runs while the LLM is loaded."
    ),
    CatalogModel(
        name      = "Whisper small",
        repoId    = WHISPER_REPO,
        modelType = ModelType.WHISPER,
        kind      = ModelKind.Speech,
        quant     = "F16",
        sizeBytes = 466 * MB,
        fileHint  = "small",
        note      = "More accurate dictation, a little slower."
    )
)

const val WHISPER_REPO = "ggerganov/whisper.cpp"

/** `2.7 GB`, `142 MB` — decimal units, as model cards quote them. */
fun approxSize(bytes: Long): String = when {
    bytes >= GB -> String.format(Locale.US, "%.1f GB", bytes / 1e9)
    bytes >= MB -> "${bytes / MB} MB"
    else        -> "${bytes / 1000} KB"
}

/** What kind of model a Hub search result most likely is. */
fun HfModel.kind(): ModelKind {
    val id = id.lowercase()
    return when {
        pipelineTag == "automatic-speech-recognition" || "whisper" in id -> ModelKind.Speech
        pipelineTag in setOf("feature-extraction", "sentence-similarity") || "embed" in id -> ModelKind.Embedding
        pipelineTag in setOf("image-text-to-text", "image-to-text", "visual-question-answering") -> ModelKind.Vision
        "coder" in id || "-code" in id -> ModelKind.Code
        else -> ModelKind.Chat
    }
}

/** `81K`, `1.2M` for download counts. */
fun compactCount(n: Long): String = when {
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1e6)
    n >= 1_000     -> "${n / 1_000}K"
    else           -> n.toString()
}

/** Kind of a model file opened from the device, by name: `*.bin` → Whisper, `*mmproj*` → projector. */
fun modelTypeFor(fileName: String): ModelType {
    val name = fileName.lowercase()
    return when {
        name.endsWith(".bin") -> ModelType.WHISPER
        "mmproj" in name      -> ModelType.MMPROJ
        else                  -> ModelType.LLM
    }
}
