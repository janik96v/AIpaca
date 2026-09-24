package com.aipaca.app.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelHeaderTest {

    private val qwenProbe = GgufProbeResult(
        architecture = "qwen3",
        nCtxTrain    = 131072,
        nParams      = 4_022_468_096L,
        nEmbd        = 2560,
        nLayer       = 36,
        nHeadKv      = 8,
        dHead        = 80,
        quant        = "Q4_K_M",
        nHead        = 32,
        name         = "Qwen3.5 4B Instruct"
    )

    @Test
    fun `probe maps onto the readout`() {
        val h = assertNotNull(ModelHeader.fromProbe(qwenProbe))
        assertEquals(
            listOf(
                "BLOCKS" to "36",
                "HEADS"  to "32 / 8 KV",
                "EMBD"   to "2560",
                "CTX"    to "128K",
                "QUANT"  to "Q4_K_M",
                "PARAMS" to "4.0 B"
            ),
            h.readout()
        )
        assertFalse(h.split)
        assertEquals("Qwen3.5 4B Instruct", h.name)
    }

    @Test
    fun `world params come from the header`() {
        val p = ModelHeader.fromProbe(qwenProbe)!!.worldParams()
        assertEquals(36, p.blocks)
        assertEquals(8, p.kv)
        assertEquals(2560, p.embd)
        assertEquals(131072, p.ctx)
        assertEquals(4.5f, p.bits)
    }

    @Test
    fun `kv falls back to heads when the file has no kv count`() {
        val p = ModelHeader.fromProbe(qwenProbe.copy(nHeadKv = 0))!!.worldParams()
        assertEquals(32, p.kv)
    }

    @Test
    fun `unreadable probe gives no header`() {
        assertNull(ModelHeader.fromProbe(GgufProbeResult()))
    }

    @Test
    fun `unknown quant renders as a dash`() {
        val h = ModelHeader.fromProbe(qwenProbe.copy(quant = "unknown", nParams = 0))!!
        assertEquals("—", h.readout().toMap()["QUANT"])
        assertEquals("—", h.readout().toMap()["PARAMS"])
    }

    @Test
    fun `t5 counts both stacks and splits`() {
        val h = ModelHeader.fromProbe(qwenProbe.copy(architecture = "t5", nLayer = 12))!!
        assertTrue(h.split)
        assertEquals(24, h.blocks)
    }

    @Test
    fun `context and parameter labels`() {
        assertEquals("128K", formatContext(131072))
        assertEquals("32K", formatContext(32768))
        assertEquals("2K", formatContext(2048))
        assertEquals("448", formatContext(448))
        assertEquals("1M", formatContext(1_048_576))
        assertEquals("4.0 B", formatParams(4_022_468_096L))
        assertEquals("137 M", formatParams(137_000_000L))
        assertEquals("74 M", formatParams(74_000_000L))
    }

    // ---- Whisper ggml header ----------------------------------------------------

    private fun whisperBytes(
        magic: Int = 0x67676d6c,
        audioLayer: Int = 6,
        textLayer: Int = 6,
        state: Int = 512,
        head: Int = 8,
        ftype: Int = 1
    ): ByteArray = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
        putInt(magic)
        putInt(51865)      // n_vocab
        putInt(1500)       // n_audio_ctx
        putInt(state)      // n_audio_state
        putInt(head)       // n_audio_head
        putInt(audioLayer) // n_audio_layer
        putInt(448)        // n_text_ctx
        putInt(state)      // n_text_state
        putInt(head)       // n_text_head
        putInt(textLayer)  // n_text_layer
        putInt(80)         // n_mels
        putInt(ftype)
    }.array()

    @Test
    fun `whisper base header matches the design readout`() {
        val h = assertNotNull(WhisperHeader.parse(whisperBytes()))
        assertEquals("whisper", h.arch)
        assertEquals(12, h.blocks)
        assertEquals(8, h.heads)
        assertEquals(8, h.kvHeads)
        assertEquals(512, h.embd)
        assertEquals(448, h.ctx)
        assertEquals("F16", h.quant)
        assertTrue(h.split)
        assertTrue(h.params in 65_000_000L..80_000_000L, "base is ~74 M, got ${h.params}")
        val p = h.worldParams()
        assertEquals(WorldParamsExpect(12, 8, 512, 448, 16f, true), WorldParamsExpect(p.blocks, p.kv, p.embd, p.ctx, p.bits, p.split))
    }

    private data class WorldParamsExpect(val b: Int, val kv: Int, val e: Int, val c: Int, val bits: Float, val split: Boolean)

    @Test
    fun `quantised whisper strips the version factor`() {
        assertEquals("Q5_0", WhisperHeader.parse(whisperBytes(ftype = 1008))!!.quant)
        assertEquals("Q4_0", WhisperHeader.parse(whisperBytes(ftype = 2002))!!.quant)
    }

    @Test
    fun `non-ggml files are rejected`() {
        assertNull(WhisperHeader.parse(whisperBytes(magic = 0x46554747)))  // "GGUF"
        assertNull(WhisperHeader.parse(ByteArray(10)))
        assertNull(WhisperHeader.parse(whisperBytes(audioLayer = 0)))
    }
}
