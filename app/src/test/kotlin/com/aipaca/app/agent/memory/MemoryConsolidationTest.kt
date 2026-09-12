package com.aipaca.app.agent.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryConsolidationTest {

    private val today = "2026-09-02"

    private fun entries(vararg pairs: Pair<String, String>) =
        pairs.map { MemoryEntry(it.first, it.second) }

    @Test
    fun `parses every command form`() {
        val ops = MemoryConsolidation.parse(
            """
            DROP 3
            EDIT 1 -> Janik lives in Bern
            MERGE 4,5 -> Janik works on AIpaca and PostNetz
            ADD Janik writes German
            """.trimIndent()
        )
        assertEquals(4, ops.size)
        assertTrue(ops[0] is ConsolidationOp.Drop)
        assertTrue(ops[1] is ConsolidationOp.Edit)
        assertTrue(ops[2] is ConsolidationOp.Merge)
        assertTrue(ops[3] is ConsolidationOp.Add)
    }

    @Test
    fun `ignores prose, NONE and malformed commands`() {
        val ops = MemoryConsolidation.parse(
            """
            NONE
            The list looks fine to me.
            DROP abc
            EDIT -> missing index
            MERGE 4 -> needs at least two indices
            """.trimIndent()
        )
        assertTrue(ops.isEmpty())
    }

    @Test
    fun `rejects poisoned replacement text`() {
        val ops = MemoryConsolidation.parse("EDIT 1 -> The gradle build is broken")
        assertTrue(ops.isEmpty())
    }

    @Test
    fun `unmentioned entries survive — a truncated answer can never wipe the file`() {
        val before = entries(
            "2026-01-01" to "one",
            "2026-01-02" to "two",
            "2026-01-03" to "three"
        )
        val after = MemoryConsolidation.apply(before, MemoryConsolidation.parse("DROP 2"), today)
        assertEquals(listOf("one", "three"), after.map { it.text })
        assertEquals("2026-01-01", after[0].date, "untouched entries keep their original date")
    }

    @Test
    fun `edit rewrites in place and refreshes the date`() {
        val before = entries("2026-01-01" to "Janik lives in Zurich")
        val after = MemoryConsolidation.apply(before, MemoryConsolidation.parse("EDIT 1 -> Janik lives in Bern"), today)
        assertEquals("Janik lives in Bern", after[0].text)
        assertEquals(today, after[0].date)
    }

    @Test
    fun `merge collapses duplicates into one entry at the earlier position`() {
        val before = entries(
            "2026-01-01" to "Janik likes Kotlin",
            "2026-01-02" to "Janik prefers Kotlin",
            "2026-01-03" to "Janik lives in Bern"
        )
        val after = MemoryConsolidation.apply(before, MemoryConsolidation.parse("MERGE 1,2 -> Janik prefers Kotlin"), today)
        assertEquals(2, after.size)
        assertEquals("Janik prefers Kotlin", after[0].text)
        assertEquals("Janik lives in Bern", after[1].text)
    }

    @Test
    fun `contradiction resolves to the newer entry`() {
        // The model is told to drop the older of two contradicting entries.
        val before = entries(
            "2026-01-01" to "Janik lives in Zurich",
            "2026-06-01" to "Janik lives in Bern"
        )
        val after = MemoryConsolidation.apply(before, MemoryConsolidation.parse("DROP 1"), today)
        assertEquals(1, after.size)
        assertEquals("Janik lives in Bern", after[0].text)
    }

    @Test
    fun `additions are appended with today's date`() {
        val before = entries("2026-01-01" to "one")
        val after = MemoryConsolidation.apply(before, MemoryConsolidation.parse("ADD two"), today)
        assertEquals(2, after.size)
        assertEquals("two", after[1].text)
        assertEquals(today, after[1].date)
    }

    @Test
    fun `out of range indices are ignored`() {
        val before = entries("2026-01-01" to "one")
        val after = MemoryConsolidation.apply(before, MemoryConsolidation.parse("DROP 9\nEDIT 7 -> nope"), today)
        assertEquals(before, after)
    }

    @Test
    fun `large deletions need approval, small ones do not`() {
        val before = (1..10).map { MemoryEntry("2026-01-01", "entry $it") }
        assertFalse(MemoryConsolidation.needsApproval(before, before.drop(2)))     // 20 % removed
        assertTrue(MemoryConsolidation.needsApproval(before, before.drop(6)))      // 60 % removed
        assertFalse(MemoryConsolidation.needsApproval(before, before))             // nothing removed
        assertFalse(MemoryConsolidation.needsApproval(emptyList(), emptyList()))
    }

    @Test
    fun `numbering is one-based and shows dates so the model can compare them`() {
        val numbered = MemoryConsolidation.numbered(entries("2026-01-01" to "one", "" to "legacy"))
        assertTrue(numbered.startsWith("1. [2026-01-01] one"))
        assertTrue(numbered.contains("2. legacy"))
    }
}
