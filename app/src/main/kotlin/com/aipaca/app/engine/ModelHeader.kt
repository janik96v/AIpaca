package com.aipaca.app.engine

import com.aipaca.app.ui.components.world.WorldParams
import com.aipaca.app.ui.components.world.bitsFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * What a model file's header says about its architecture — read without
 * loading any weights.
 *
 * One type for both model families on the phone: GGUF files (read natively by
 * [LlamaCppEngine.probeGgufMeta]) and Whisper's ggml `.bin` files (read by
 * [WhisperHeader]). It drives the Model World, its readout, the status line
 * and the installed-models list.
 */
data class ModelHeader(
    /** `general.architecture`, e.g. `qwen3`, `gemma3`, `whisper`. */
    val arch: String,
    /** `general.name` when the file has one, else empty. */
    val name: String = "",
    /** Transformer blocks, counted across both stacks for encoder–decoders. */
    val blocks: Int,
    /** Attention heads (readout only). */
    val heads: Int,
    /** KV heads. */
    val kvHeads: Int,
    val embd: Int,
    /** Trained context length. */
    val ctx: Int,
    /** Quantisation name, e.g. `Q4_K_M`, `F16`. */
    val quant: String,
    /** Parameter count, 0 when unknown. */
    val params: Long,
    /** Encoder–decoder: drawn as two towers. */
    val split: Boolean = false
) {
    /** True when there is enough structure to build a shape from. */
    val hasStructure: Boolean get() = blocks > 0

    fun worldParams(): WorldParams = WorldParams(
        blocks = blocks,
        kv     = if (kvHeads > 0) kvHeads else heads,
        embd   = embd,
        ctx    = ctx,
        bits   = bitsFor(quant),
        split  = split
    )

    /** `36`, `32 / 8 KV`, `2560`, `128K`, `Q4_K_M`, `4.0 B`. */
    fun readout(): List<Pair<String, String>> = listOf(
        "BLOCKS" to blocks.orDash(),
        "HEADS"  to if (heads > 0) "$heads / ${kvHeads.orDash()} KV" else "—",
        "EMBD"   to embd.orDash(),
        "CTX"    to if (ctx > 0) formatContext(ctx) else "—",
        "QUANT"  to quant.uppercase(Locale.ROOT).ifBlank { "—" },
        "PARAMS" to if (params > 0) formatParams(params) else "—"
    )

    companion object {
        /**
         * Architectures whose `block_count` is per stack rather than total. The
         * world wants the total so a tower holds one ring per block.
         */
        private val ENCODER_DECODER = setOf("t5")

        /** Null when the probe could not read the file (not a GGUF, unreadable). */
        fun fromProbe(p: GgufProbeResult): ModelHeader? {
            if (p.architecture.isBlank() && p.nLayer <= 0) return null
            val split = p.architecture in ENCODER_DECODER
            return ModelHeader(
                arch    = p.architecture,
                name    = p.name,
                blocks  = if (split) p.nLayer * 2 else p.nLayer,
                heads   = p.nHead,
                kvHeads = p.nHeadKv,
                embd    = p.nEmbd,
                ctx     = p.nCtxTrain,
                quant   = p.quant.takeUnless { it == "unknown" } ?: "",
                params  = p.nParams,
                split   = split
            )
        }

        private fun Int.orDash(): String = if (this > 0) toString() else "—"
    }
}

/** `131072 → 128K`, `448 → 448`, `2048 → 2K`, `1048576 → 1M`. */
fun formatContext(tokens: Int): String = when {
    tokens >= 1_048_576 && tokens % 1_048_576 == 0 -> "${tokens / 1_048_576}M"
    tokens >= 1_000_000 && tokens % 1_000_000 == 0 -> "${tokens / 1_000_000}M"
    tokens >= 1024 && tokens % 1024 == 0 -> "${tokens / 1024}K"
    tokens >= 1000 && tokens % 1000 == 0 -> "${tokens / 1000}K"
    else                                 -> tokens.toString()
}

/** `4_020_000_000 → 4.0 B`, `137_000_000 → 137 M`. */
fun formatParams(params: Long): String = when {
    params >= 1_000_000_000L -> String.format(Locale.US, "%.1f B", params / 1e9)
    params >= 1_000_000L     -> "${(params / 1e6).toLong()} M"
    params >= 1_000L         -> "${(params / 1e3).toLong()} K"
    else                     -> params.toString()
}

/**
 * Reader for whisper.cpp's ggml `.bin` header.
 *
 * Layout (little-endian): magic `ggml` as u32, then eleven i32 hyper-parameters —
 * `n_vocab, n_audio_ctx, n_audio_state, n_audio_head, n_audio_layer,
 * n_text_ctx, n_text_state, n_text_head, n_text_layer, n_mels, ftype`.
 */
object WhisperHeader {

    private const val GGML_MAGIC = 0x67676d6c
    private const val HEADER_BYTES = 4 + 11 * 4

    /** Quantised files store `ftype + 1000 × qnt_version`. */
    private const val QNT_VERSION_FACTOR = 1000

    fun read(file: File): ModelHeader? = try {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < HEADER_BYTES) return null
            val bytes = ByteArray(HEADER_BYTES)
            raf.readFully(bytes)
            parse(bytes)
        }
    } catch (_: Exception) {
        null
    }

    fun parse(bytes: ByteArray): ModelHeader? {
        if (bytes.size < HEADER_BYTES) return null
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (b.int != GGML_MAGIC) return null
        val nVocab     = b.int
        b.int                       // n_audio_ctx
        val audioState = b.int
        val audioHead  = b.int
        val audioLayer = b.int
        val textCtx    = b.int
        val textState  = b.int
        b.int                       // n_text_head
        val textLayer  = b.int
        val nMels      = b.int
        val ftype      = b.int % QNT_VERSION_FACTOR

        if (audioLayer <= 0 || textLayer <= 0 || audioState <= 0) return null

        return ModelHeader(
            arch    = "whisper",
            blocks  = audioLayer + textLayer,
            heads   = audioHead,
            kvHeads = audioHead,         // plain multi-head attention: one KV head per head
            embd    = audioState,
            ctx     = textCtx,
            quant   = ggmlFtypeName(ftype),
            params  = estimateParams(nVocab, audioState, audioLayer, textState, textLayer, textCtx, nMels),
            split   = true
        )
    }

    /**
     * Weight count from the dimensions: 12·d² per encoder block (attention + MLP),
     * 16·d² per decoder block (plus cross-attention), token embedding, positional
     * embedding and the two input convolutions. Lands within a few percent of
     * the published sizes (base ≈ 74 M).
     */
    private fun estimateParams(
        vocab: Int, audioState: Int, audioLayer: Int,
        textState: Int, textLayer: Int, textCtx: Int, nMels: Int
    ): Long {
        val a = audioState.toLong()
        val t = textState.toLong()
        val encoder = audioLayer * 12L * a * a + 3L * nMels * a + 3L * a * a
        val decoder = textLayer * 16L * t * t + vocab.toLong() * t + textCtx.toLong() * t
        return encoder + decoder
    }

    private fun ggmlFtypeName(ftype: Int): String = when (ftype) {
        0  -> "F32"
        1  -> "F16"
        2  -> "Q4_0"
        3  -> "Q4_1"
        7  -> "Q8_0"
        8  -> "Q5_0"
        9  -> "Q5_1"
        10 -> "Q2_K"
        11 -> "Q3_K"
        12 -> "Q4_K"
        13 -> "Q5_K"
        14 -> "Q6_K"
        else -> ""
    }
}

/**
 * Header reads, cached per file (path + size + mtime).
 *
 * Reads touch the file system and, for GGUF, native code — call from a
 * background dispatcher; [read] switches to IO itself.
 */
object ModelHeaders {

    private data class Key(val path: String, val length: Long, val modified: Long)

    private val cache = ConcurrentHashMap<Key, ModelHeader>()
    private val misses = ConcurrentHashMap.newKeySet<Key>()

    /** Last successful read per path, for [peek]. */
    private val latest = ConcurrentHashMap<String, ModelHeader>()

    /** GGUF files are probed natively; everything else is tried as a Whisper `.bin`. */
    suspend fun read(path: String, engine: LlamaCppEngine): ModelHeader? = withContext(Dispatchers.IO) {
        readBlocking(path, engine)
    }

    fun readBlocking(path: String, engine: LlamaCppEngine): ModelHeader? {
        val file = File(path)
        if (!file.isFile) return null
        val key = Key(path, file.length(), file.lastModified())
        cache[key]?.let { return it }
        if (key in misses) return null

        val header = if (isGguf(file)) {
            ModelHeader.fromProbe(engine.probeGgufMeta(path))
        } else {
            WhisperHeader.read(file)
        }
        if (header != null) {
            cache[key] = header
            latest[path] = header
        } else {
            misses += key
        }
        return header
    }

    /** Last header read for [path], without touching the disk. Safe during composition. */
    fun peek(path: String?): ModelHeader? = path?.let { latest[it] }

    private fun isGguf(file: File): Boolean = try {
        RandomAccessFile(file, "r").use { raf ->
            val magic = ByteArray(4)
            raf.length() >= 4 && raf.read(magic) == 4 && String(magic, Charsets.US_ASCII) == "GGUF"
        }
    } catch (_: Exception) {
        false
    }
}
