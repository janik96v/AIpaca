package com.aipaca.app.ui.models

import com.aipaca.app.data.DownloadProgress
import com.aipaca.app.data.DownloadState
import com.aipaca.app.data.DownloadedModelEntry
import com.aipaca.app.data.HfModel
import com.aipaca.app.data.ModelType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelCatalogTest {

    private val qwen = Catalog.first { it.name == "Qwen3.5 4B" }
    private val whisperBase = Catalog.first { it.name == "Whisper base" }

    private fun entry(repo: String, file: String, type: ModelType = ModelType.LLM) =
        DownloadedModelEntry(repo, file, "/m/$file", 1L, type)

    @Test
    fun `meta lines match the design format`() {
        assertEquals("Q4_K_M · 2.7 GB", qwen.meta)
        assertEquals("Q4_0 · 2.6 GB · 32K ctx", Catalog.first { it.name == "Qwen3 4B" }.meta)
        assertEquals("Speech · 142 MB", whisperBase.meta)
    }

    @Test
    fun `ram needs and fit`() {
        assertEquals(6, qwen.needsRamGb)
        assertTrue(qwen.fitsIn(8_000_000_000L))
        assertFalse(qwen.fitsIn(4_000_000_000L))
        assertTrue(qwen.fitsIn(0L), "unknown RAM never blocks")
        assertTrue(whisperBase.fitsIn(1L))
    }

    @Test
    fun `whisper sizes are told apart inside the shared repo`() {
        val installed = listOf(entry(WHISPER_REPO, "ggml-base.en.bin", ModelType.WHISPER))
        assertTrue(whisperBase.isInstalled(installed))
        assertFalse(Catalog.first { it.name == "Whisper tiny" }.isInstalled(installed))
    }

    @Test
    fun `a projector alone does not count as installed`() {
        assertFalse(qwen.isInstalled(listOf(entry(qwen.repoId, "mmproj-F16.gguf", ModelType.MMPROJ))))
        assertTrue(qwen.isInstalled(listOf(entry(qwen.repoId, "Qwen3.5-4B-Q4_K_M.gguf"))))
    }

    @Test
    fun `active and failed downloads are found per entry`() {
        val progress = listOf(
            DownloadProgress(WHISPER_REPO, "ggml-base.bin", 10, 100, DownloadState.DOWNLOADING),
            DownloadProgress(qwen.repoId, "Qwen3.5-4B-Q4_K_M.gguf", 0, -1, DownloadState.FAILED, "boom")
        )
        assertNotNull(whisperBase.activeDownload(progress))
        assertNull(Catalog.first { it.name == "Whisper small" }.activeDownload(progress))
        assertNotNull(qwen.failedDownload(progress))
        assertNull(qwen.activeDownload(progress))
    }

    @Test
    fun `search matches name, repo and kind`() {
        assertTrue(qwen.matchesQuery("qwen"))
        assertTrue(qwen.matchesQuery("UNSLOTH"))
        assertTrue(whisperBase.matchesQuery("speech"))
        assertFalse(whisperBase.matchesQuery("gemma"))
        assertTrue(whisperBase.matchesQuery("  "))
    }

    @Test
    fun `hub results are sorted into kinds`() {
        assertEquals(ModelKind.Speech, HfModel("ggerganov/whisper.cpp").kind())
        assertEquals(ModelKind.Vision, HfModel("unsloth/Qwen3.5-4B-GGUF", pipelineTag = "image-text-to-text").kind())
        assertEquals(ModelKind.Embedding, HfModel("nomic-ai/nomic-embed-text-v1.5-GGUF").kind())
        assertEquals(ModelKind.Code, HfModel("Qwen/Qwen2.5-Coder-3B-Instruct-GGUF").kind())
        assertEquals(ModelKind.Chat, HfModel("bartowski/Llama-3.2-3B-Instruct-GGUF", pipelineTag = "text-generation").kind())
    }

    @Test
    fun `sizes and counts`() {
        assertEquals("2.7 GB", approxSize(2_700_000_000L))
        assertEquals("142 MB", approxSize(142_000_000L))
        assertEquals("81K", compactCount(81_234))
        assertEquals("1.2M", compactCount(1_234_567))
        assertEquals("999", compactCount(999))
    }

    @Test
    fun `model type from file name`() {
        assertEquals(ModelType.WHISPER, modelTypeFor("ggml-base.bin"))
        assertEquals(ModelType.MMPROJ, modelTypeFor("mmproj-F16.gguf"))
        assertEquals(ModelType.LLM, modelTypeFor("Qwen3.5-4B-Q4_K_M.gguf"))
    }
}
