package com.aipaca.app.ui.memory

import kotlin.test.Test
import kotlin.test.assertEquals

class MemoryLinesTest {

    @Test
    fun `dated entries become stamped lines, newest first`() {
        val content = "2026-09-01 | Prefers Kotlin over Java\n§ 2026-09-13 | Moved from Zürich to Bern"
        assertEquals(
            listOf(
                MemoryLine("13 Sep", "Moved from Zürich to Bern"),
                MemoryLine("1 Sep", "Prefers Kotlin over Java")
            ),
            memoryLines(content, sessions = false)
        )
    }

    @Test
    fun `entries sharing a date keep file order`() {
        val content = "2026-09-13 | I run on this phone.\n§ 2026-09-13 | I answer concisely.\n§ 2026-09-01 | Older."
        assertEquals(
            listOf("I run on this phone.", "I answer concisely.", "Older."),
            memoryLines(content, sessions = false).map { it.text }
        )
    }

    @Test
    fun `undated entries sort after dated ones`() {
        val content = "Legacy line\n§ 2026-09-13 | Dated line"
        assertEquals(listOf("Dated line", "Legacy line"), memoryLines(content, sessions = false).map { it.text })
    }

    @Test
    fun `legacy undated entries keep their text without a stamp`() {
        assertEquals(listOf(MemoryLine("", "I run on this phone.")), memoryLines("I run on this phone.", sessions = false))
    }

    @Test
    fun `session index lines read as title and summary`() {
        val content = "2026-09-13 | abc-123 | 2 | Context window on 4 GB devices | Settled on 4K.\n" +
            "§ 2026-09-12 | not-a-session-line"
        assertEquals(
            listOf(MemoryLine("13 Sep", "Context window on 4 GB devices — Settled on 4K.")),
            memoryLines(content, sessions = true)
        )
    }

    @Test
    fun `empty files have no lines`() {
        assertEquals(emptyList(), memoryLines("", sessions = false))
        assertEquals(emptyList(), memoryLines("   ", sessions = true))
    }

    @Test
    fun `stamps`() {
        assertEquals("13 Sep", stampFor("2026-09-13"))
        assertEquals("", stampFor(""))
        assertEquals("garbage", stampFor("garbage"))
    }
}
