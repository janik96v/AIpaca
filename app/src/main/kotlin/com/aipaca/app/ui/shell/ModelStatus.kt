package com.aipaca.app.ui.shell

import com.aipaca.app.engine.ModelHeader

/*
 * Pure helpers behind the status line and model names. No Android or Compose
 * dependency, so the copy rules are unit-tested directly.
 */

private val QUANT_SUFFIX = Regex(
    """[-_.](?:UD-)?(?:I?Q\d(?:_[A-Z0-9]+)*|F16|BF16|F32|MXFP4(?:_MOE)?)$""",
    RegexOption.IGNORE_CASE
)

/**
 * Human name for a model file: the header's `general.name` when present, else
 * the file name without extension and quant suffix. Separators become spaces:
 * `Qwen3.5-4B-Q4_K_M.gguf` → `Qwen3.5 4B`, `ggml-base.en.bin` → `Whisper base.en`.
 */
fun displayModelName(path: String?, header: ModelHeader? = null): String {
    val file = path?.substringAfterLast('/').orEmpty()
    if (header?.arch == "whisper" || file.startsWith("ggml-")) {
        val size = file.removePrefix("ggml-").removeSuffix(".bin")
            .replace(QUANT_SUFFIX, "")
            .ifBlank { "model" }
        return "Whisper $size"
    }
    val named = header?.name?.trim().orEmpty()
    val raw = named.ifBlank {
        file.removeSuffix(".gguf").removeSuffix(".bin").replace(QUANT_SUFFIX, "")
    }
    return raw.replace('_', ' ').replace('-', ' ').replace(Regex("\\s+"), " ").trim()
        .ifBlank { "Model" }
}

/** Everything the status line can report, in priority order. */
data class StatusInputs(
    val listening: Boolean = false,
    val transcribing: Boolean = false,
    val streaming: Boolean = false,
    val llmLoading: Boolean = false,
    val llmHeader: ModelHeader? = null,
    val whisperLoading: Boolean = false,
    val remoteModel: String? = null,
    val llmLoaded: Boolean = false,
    val llmName: String = "",
    val gpu: Boolean = false,
    val loadFailed: Boolean = false,
    val whisperLoaded: Boolean = false,
    val whisperName: String = ""
)

/**
 * The status line's copy (rendered uppercase):
 * `NO MODEL LOADED` · `READING QWEN3.GGUF HEADER` · `THINKING` ·
 * `QWEN3.5 4B INSTRUCT · GPU · READY`.
 */
fun statusLine(s: StatusInputs): String = when {
    s.listening      -> "Listening"
    s.transcribing   -> "Transcribing"
    s.streaming      -> "Thinking"
    s.llmLoading     -> s.llmHeader?.arch?.takeIf { it.isNotBlank() }
                            ?.let { "Reading $it.gguf header" } ?: "Reading gguf header"
    s.remoteModel != null -> "${s.remoteModel} · Remote · Ready"
    s.llmLoaded      -> "${s.llmName} · ${if (s.gpu) "GPU" else "CPU"} · Ready"
    s.whisperLoading -> "Reading whisper header"
    s.loadFailed     -> "Load failed"
    s.whisperLoaded  -> "${s.whisperName} · Speech · Ready"
    else             -> "No model loaded"
}
