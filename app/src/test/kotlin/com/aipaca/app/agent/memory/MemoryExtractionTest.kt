package com.aipaca.app.agent.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryExtractionTest {

    @Test
    fun `parses the two line forms`() {
        val raw = """
            USER: Janik prefers Kotlin over Java
            FACT: The project uses gradle version catalogs
        """.trimIndent()
        val facts = MemoryExtraction.parse(raw)
        assertEquals(2, facts.size)
        assertEquals(MemoryTarget.USER, facts[0].target)
        assertEquals("Janik prefers Kotlin over Java", facts[0].text)
        assertEquals(MemoryTarget.MEMORY, facts[1].target)
    }

    @Test
    fun `tolerates the bullets and quotes small models add`() {
        val facts = MemoryExtraction.parse("- USER: \"Janik lives in Bern\"\n* FACT: uses Android Studio")
        assertEquals(2, facts.size)
        assertEquals("Janik lives in Bern", facts[0].text)
        assertEquals("uses Android Studio", facts[1].text)
    }

    @Test
    fun `ignores prose and NONE answers`() {
        assertEquals(emptyList(), MemoryExtraction.parse("NONE"))
        assertEquals(emptyList(), MemoryExtraction.parse("Nothing in this conversation was worth saving."))
        assertEquals(emptyList(), MemoryExtraction.parse(""))
    }

    @Test
    fun `drops poisoned facts before they reach disk`() {
        val facts = MemoryExtraction.parse("FACT: The tavily_search tool doesn't work\nUSER: Janik prefers Kotlin")
        assertEquals(1, facts.size)
        assertEquals("Janik prefers Kotlin", facts[0].text)
    }

    @Test
    fun `drops facts that duplicate what is already stored`() {
        val facts = MemoryExtraction.parse(
            "USER: Janik prefers Kotlin over Java",
            existingUser = listOf("Janik prefers Kotlin over Java for everything")
        )
        assertTrue(facts.isEmpty())
    }

    @Test
    fun `drops facts that duplicate each other within one answer`() {
        val facts = MemoryExtraction.parse(
            "USER: Janik prefers Kotlin over Java\nUSER: Janik prefers Kotlin over Java"
        )
        assertEquals(1, facts.size)
    }

    @Test
    fun `the same fact in a different file is not a duplicate`() {
        val facts = MemoryExtraction.parse(
            "FACT: Janik prefers Kotlin over Java",
            existingUser = listOf("Janik prefers Kotlin over Java")
        )
        assertEquals(1, facts.size)
    }

    @Test
    fun `caps how much a single pass can write`() {
        val distinct = listOf(
            "Janik cycles to work every morning",
            "The living room has a green sofa",
            "Coffee gets brewed at seven sharp",
            "Weekends belong to mountain hiking",
            "Trumpet practice happens on Thursdays",
            "The cat answers to Mirabelle"
        )
        val raw = distinct.joinToString("\n") { "USER: " + it }
        assertEquals(MemoryExtraction.MAX_FACTS_PER_PASS, MemoryExtraction.parse(raw).size)
    }

    @Test
    fun `truncates over-long facts`() {
        val long = "x".repeat(500)
        val facts = MemoryExtraction.parse("USER: $long")
        assertEquals(MemoryExtraction.MAX_FACT_CHARS, facts[0].text.length)
    }

    @Test
    fun `duplicate detection needs real overlap`() {
        assertTrue(MemoryExtraction.isDuplicate("Janik prefers Kotlin", "Janik prefers Kotlin"))
        assertFalse(MemoryExtraction.isDuplicate("Janik prefers Kotlin", "Janik lives in Bern"))
        assertFalse(MemoryExtraction.isDuplicate("", "anything"))
    }
}
