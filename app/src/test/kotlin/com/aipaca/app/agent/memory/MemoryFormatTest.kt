package com.aipaca.app.agent.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryFormatTest {

    @Test
    fun `parses dated entries`() {
        val content = "2026-09-01 | Janik prefers Kotlin\n§ 2026-09-02 | Janik lives in Bern"
        val entries = MemoryFormat.parse(content)
        assertEquals(2, entries.size)
        assertEquals("2026-09-01", entries[0].date)
        assertEquals("Janik prefers Kotlin", entries[0].text)
        assertEquals("Janik lives in Bern", entries[1].text)
    }

    @Test
    fun `parses legacy undated entries written before dates existed`() {
        val entries = MemoryFormat.parse("Janik prefers Kotlin\n§ Janik lives in Zurich")
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.date.isEmpty() })
        assertEquals("Janik prefers Kotlin", entries[0].text)
    }

    @Test
    fun `render then parse is a round trip`() {
        val entries = listOf(
            MemoryEntry("2026-09-01", "one"),
            MemoryEntry("2026-09-02", "two"),
            MemoryEntry("", "legacy")
        )
        assertEquals(entries, MemoryFormat.parse(MemoryFormat.render(entries)))
    }

    @Test
    fun `blank content parses to nothing`() {
        assertEquals(emptyList(), MemoryFormat.parse(""))
        assertEquals(emptyList(), MemoryFormat.parse("   \n  "))
    }

    @Test
    fun `trim drops oldest entries first and reports how many`() {
        val entries = (1..10).map { MemoryEntry("2026-09-0${it % 9 + 1}", "entry number $it") }
        val result = MemoryFormat.trimToLimit(entries, 100)
        assertTrue(MemoryFormat.render(result.first).length <= 100)
        assertTrue(result.second > 0)
        // the newest entry always survives
        assertEquals("entry number 10", result.first.last().text)
    }

    @Test
    fun `trim keeps everything when already under the limit`() {
        val entries = listOf(MemoryEntry("2026-09-01", "short"))
        val result = MemoryFormat.trimToLimit(entries, 1000)
        assertEquals(entries, result.first)
        assertEquals(0, result.second)
    }
}
