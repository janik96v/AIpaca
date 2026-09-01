package com.aipaca.app.agent.memory

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionIndexStoreTest {

    private lateinit var root: File
    private lateinit var store: SessionIndexStore

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("aipaca-sessions").toFile()
        store = SessionIndexStore(root)
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun note(id: String, date: String = "2026-09-01", accessCount: Int = 0) = SessionNote(
        sessionId = id,
        date = date,
        title = "title $id",
        summary = "summary for $id",
        accessCount = accessCount
    )

    @Test
    fun `encode then decode is a round trip`() {
        val original = note("abc-123", accessCount = 3)
        val decoded = SessionIndexStore.decode(original.date, SessionIndexStore.encode(original))
        assertNotNull(decoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `field separators inside titles cannot corrupt a line`() {
        val original = note("id-1").copy(title = "a | b | c", summary = "x | y")
        val decoded = SessionIndexStore.decode(original.date, SessionIndexStore.encode(original))
        assertNotNull(decoded)
        assertEquals("id-1", decoded.sessionId)
        assertEquals("a / b / c", decoded.title)
        assertEquals("x / y", decoded.summary)
    }

    @Test
    fun `malformed lines are dropped rather than crashing`() {
        assertNull(SessionIndexStore.decode("2026-09-01", "not enough fields"))
        assertNull(SessionIndexStore.decode("2026-09-01", " | 0 | title | summary"))
    }

    @Test
    fun `upsert replaces the note for a session instead of duplicating it`() {
        store.upsert(note("s1"))
        store.upsert(note("s1").copy(summary = "updated summary"))
        val all = store.list()
        assertEquals(1, all.size)
        assertEquals("updated summary", all[0].summary)
    }

    @Test
    fun `upsert preserves an existing access count`() {
        store.upsert(note("s1"))
        store.markAccessed("s1")
        store.markAccessed("s1")
        store.upsert(note("s1").copy(summary = "rewritten"))
        assertEquals(2, store.note("s1")?.accessCount)
    }

    @Test
    fun `markAccessed on an unknown session is a no-op`() {
        store.upsert(note("s1"))
        store.markAccessed("does-not-exist")
        assertEquals(1, store.list().size)
    }

    @Test
    fun `remove deletes a single session note`() {
        store.upsert(note("s1"))
        store.upsert(note("s2"))
        store.remove("s1")
        assertFalse(store.hasNote("s1"))
        assertTrue(store.hasNote("s2"))
    }

    @Test
    fun `eviction keeps the cap`() {
        val notes = (1..SessionIndexStore.MAX_NOTES + 10).map { note("s$it") }
        assertEquals(SessionIndexStore.MAX_NOTES, SessionIndexStore.evict(notes).size)
    }

    @Test
    fun `an old but frequently opened session outlives a newer untouched one`() {
        // stored oldest-first, so index 0 is the oldest
        val notes = listOf(
            note("old-but-used", accessCount = 20),
            note("newer-untouched-a"),
            note("newer-untouched-b")
        )
        val kept = SessionIndexStore.evict(notes, maxNotes = 2)
        assertTrue(kept.any { it.sessionId == "old-but-used" })
        assertTrue(kept.any { it.sessionId == "newer-untouched-b" }, "the newest untouched one is kept")
        assertFalse(kept.any { it.sessionId == "newer-untouched-a" })
    }

    @Test
    fun `writing more than the cap evicts on disk too`() {
        (1..SessionIndexStore.MAX_NOTES + 5).forEach { store.upsert(note("s$it")) }
        assertEquals(SessionIndexStore.MAX_NOTES, store.list().size)
    }

    @Test
    fun `rendered index is newest first and carries the id for session_view`() {
        store.upsert(note("older", date = "2026-08-01"))
        store.upsert(note("newer", date = "2026-09-01"))
        val rendered = store.renderIndex()
        assertTrue(rendered.indexOf("newer") < rendered.indexOf("older"))
        assertTrue(rendered.contains("[newer]"))
    }

    @Test
    fun `rendered index respects the char budget`() {
        (1..SessionIndexStore.MAX_NOTES).forEach { store.upsert(note("session-id-$it")) }
        assertTrue(store.renderIndex().length <= SessionIndexStore.MAX_INDEX_CHARS)
    }

    @Test
    fun `empty index renders as nothing so the prompt block is skipped`() {
        assertEquals("", store.renderIndex())
    }
}
