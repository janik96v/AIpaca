package com.aipaca.app.ui.models

import com.aipaca.app.ui.components.ChipTone
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for the pure helper functions behind [GgufFilePickerSheet] —
 * GPU-compatibility chip derivation and byte-size formatting. Both are
 * plain functions with no Compose/Android dependency, so they run as
 * regular JVM unit tests.
 */
class GgufFilePickerSheetTest {

    @Test
    fun `gpuCompatibility flags known GPU quants as Success`() {
        val (label, tone) = gpuCompatibility("llama-2-7b-chat.Q4_K_M.gguf")
        assertEquals("GPU · Q4_K_M", label)
        assertEquals(ChipTone.Success, tone)
    }

    @Test
    fun `gpuCompatibility flags known CPU-only quants as Warning`() {
        val (label, tone) = gpuCompatibility("llama-2-7b-chat.Q5_0.gguf")
        assertEquals("CPU ONLY · Q5_0", label)
        assertEquals(ChipTone.Warning, tone)
    }

    @Test
    fun `gpuCompatibility falls back to Neutral for unrecognised quants`() {
        val (label, tone) = gpuCompatibility("some-custom-model.gguf")
        assertEquals("GPU · UNKNOWN", label)
        assertEquals(ChipTone.Neutral, tone)
    }

    @Test
    fun `gpuCompatibility is case-insensitive`() {
        val (label, tone) = gpuCompatibility("model.q6_k.gguf")
        assertEquals("GPU · Q6_K", label)
        assertEquals(ChipTone.Success, tone)
    }

    @Test
    fun `formatFileSize renders bytes below 1024 as raw B`() {
        assertEquals("512 B", formatFileSize(512))
    }

    @Test
    fun `formatFileSize renders MB and GB with one decimal`() {
        assertEquals("2.2 GB", formatFileSize(2_400_000_000L))
        assertEquals("1.8 MB", formatFileSize(1_900_000L))
    }

    @Test
    fun `formatFileSize handles zero and negative sizes`() {
        assertEquals("0 B", formatFileSize(0))
        assertEquals("0 B", formatFileSize(-5))
    }
}
