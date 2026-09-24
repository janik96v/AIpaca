package com.aipaca.app.ui.shell

import com.aipaca.app.engine.ModelHeader
import kotlin.test.Test
import kotlin.test.assertEquals

class ModelStatusTest {

    private val qwen = ModelHeader(
        arch = "qwen3", name = "", blocks = 36, heads = 32, kvHeads = 8,
        embd = 2560, ctx = 131072, quant = "Q4_K_M", params = 4_000_000_000L
    )

    @Test
    fun `names come from general name when the file has one`() {
        assertEquals("Qwen3.5 4B Instruct", displayModelName("/m/x.gguf", qwen.copy(name = "Qwen3.5-4B-Instruct")))
    }

    @Test
    fun `names fall back to the file name without quant suffix`() {
        assertEquals("Qwen3.5 4B", displayModelName("/data/models/Qwen3.5-4B-Q4_K_M.gguf", qwen))
        assertEquals("gemma 4 E2B it", displayModelName("/data/models/gemma-4-E2B-it-Q4_0.gguf"))
        assertEquals("llama 2 7b chat", displayModelName("/m/llama-2-7b-chat.Q4_K_M.gguf"))
        assertEquals("Qwen3 4B", displayModelName("/m/Qwen3-4B-UD-Q4_K_XL.gguf"))
        assertEquals("Model", displayModelName(null))
    }

    @Test
    fun `whisper files are named by size`() {
        assertEquals("Whisper base", displayModelName("/m/ggml-base.bin"))
        assertEquals("Whisper base.en", displayModelName("/m/ggml-base.en.bin"))
        assertEquals("Whisper small", displayModelName("/m/ggml-small-q5_1.bin"))
    }

    @Test
    fun `status line follows the design copy`() {
        assertEquals("No model loaded", statusLine(StatusInputs()))
        assertEquals("Reading qwen3.gguf header", statusLine(StatusInputs(llmLoading = true, llmHeader = qwen)))
        assertEquals("Reading gguf header", statusLine(StatusInputs(llmLoading = true)))
        assertEquals(
            "Qwen3.5 4B Instruct · GPU · Ready",
            statusLine(StatusInputs(llmLoaded = true, llmName = "Qwen3.5 4B Instruct", gpu = true))
        )
        assertEquals("Phi · CPU · Ready", statusLine(StatusInputs(llmLoaded = true, llmName = "Phi")))
        assertEquals("Thinking", statusLine(StatusInputs(llmLoaded = true, llmName = "Phi", streaming = true)))
        assertEquals("qwen3:30b · Remote · Ready", statusLine(StatusInputs(remoteModel = "qwen3:30b", llmLoaded = true)))
        assertEquals("Whisper base · Speech · Ready", statusLine(StatusInputs(whisperLoaded = true, whisperName = "Whisper base")))
        assertEquals("Load failed", statusLine(StatusInputs(loadFailed = true, whisperLoaded = true)))
    }

    @Test
    fun `live audio outranks everything`() {
        assertEquals("Listening", statusLine(StatusInputs(listening = true, streaming = true)))
        assertEquals("Transcribing", statusLine(StatusInputs(transcribing = true, llmLoading = true)))
    }
}
