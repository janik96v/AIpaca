package com.aipaca.app.agent.memory

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AntiPoisoningTest {

    @Test
    fun `rejects english machine failure`() {
        assertTrue(AntiPoisoning.isPoisoned("Error: connection refused on port 8080"))
        assertTrue(AntiPoisoning.isPoisoned("The build crashed with a segfault"))
        assertTrue(AntiPoisoning.isPoisoned("The request timed out"))
    }

    @Test
    fun `rejects german machine failure — the app's user writes german`() {
        assertTrue(AntiPoisoning.isPoisoned("Fehler: Verbindung abgelehnt"))
        assertTrue(AntiPoisoning.isPoisoned("Das Tool funktioniert nicht"))
        assertTrue(AntiPoisoning.isPoisoned("Der Build ist abgestürzt"))
        assertTrue(AntiPoisoning.isPoisoned("Die Zeitüberschreitung trat erneut auf"))
    }

    @Test
    fun `rejects negative tool claims`() {
        assertTrue(AntiPoisoning.isPoisoned("The tavily_search tool doesn't work"))
        assertTrue(AntiPoisoning.isPoisoned("Unable to install the gradle package"))
        assertTrue(AntiPoisoning.isPoisoned("Das Skript wurde nicht gefunden"))
    }

    @Test
    fun `keeps legitimate facts that merely sound negative`() {
        // These are exactly the false positives the old broad regexes produced.
        assertFalse(AntiPoisoning.isPoisoned("Janik could not attend the review on Tuesday"))
        assertFalse(AntiPoisoning.isPoisoned("Janik is not a morning person"))
        assertFalse(AntiPoisoning.isPoisoned("Janik konnte am Montag nicht teilnehmen"))
        assertFalse(AntiPoisoning.isPoisoned("Der Schlüssel zum Briefkasten ist nicht gefunden worden"))
    }

    @Test
    fun `keeps ordinary facts`() {
        assertFalse(AntiPoisoning.isPoisoned("Janik prefers Kotlin over Java"))
        assertFalse(AntiPoisoning.isPoisoned("Janik arbeitet an einem Postnetz-Projekt"))
        assertFalse(AntiPoisoning.isPoisoned("The user lives in Bern and speaks German"))
    }
}
