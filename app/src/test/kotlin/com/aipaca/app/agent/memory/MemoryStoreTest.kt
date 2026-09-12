package com.aipaca.app.agent.memory

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryStoreTest {

    private lateinit var root: File
    private lateinit var store: MemoryStore

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("aipaca-memory").toFile()
        store = MemoryStore(root)
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `added entries are date-stamped and read back`() {
        store.add(MemoryStore.USER_FILE, "Janik prefers Kotlin")
        val entries = store.readEntries(MemoryStore.USER_FILE)
        assertEquals(1, entries.size)
        assertEquals("Janik prefers Kotlin", entries[0].text)
        assertEquals(MemoryFormat.today(), entries[0].date)
    }

    @Test
    fun `blank entries are ignored`() {
        store.add(MemoryStore.USER_FILE, "   ")
        assertEquals(0, store.readEntries(MemoryStore.USER_FILE).size)
    }

    @Test
    fun `replace swaps the matching entry and refreshes its date`() {
        store.writeRaw(MemoryStore.USER_FILE, MemoryFormat.render(listOf(
            MemoryEntry("2020-01-01", "Janik lives in Zurich"),
            MemoryEntry("2020-01-02", "Janik prefers Kotlin")
        )))
        store.replace(MemoryStore.USER_FILE, "lives in Zurich", "Janik lives in Bern")

        val entries = store.readEntries(MemoryStore.USER_FILE)
        assertEquals(2, entries.size)
        assertEquals("Janik lives in Bern", entries[0].text)
        // The new information must look newer, otherwise consolidation cannot
        // resolve it against a contradicting older entry.
        assertEquals(MemoryFormat.today(), entries[0].date)
        assertEquals("Janik prefers Kotlin", entries[1].text)
    }

    @Test
    fun `remove deletes only the matching entry`() {
        store.add(MemoryStore.MEMORY_FILE, "uses gradle version catalogs")
        store.add(MemoryStore.MEMORY_FILE, "targets Android 15")
        store.remove(MemoryStore.MEMORY_FILE, "gradle version catalogs")

        val entries = store.readEntries(MemoryStore.MEMORY_FILE)
        assertEquals(1, entries.size)
        assertEquals("targets Android 15", entries[0].text)
    }

    @Test
    fun `replace and remove are no-ops when nothing matches`() {
        store.add(MemoryStore.USER_FILE, "Janik prefers Kotlin")
        store.replace(MemoryStore.USER_FILE, "nope", "something")
        store.remove(MemoryStore.USER_FILE, "also nope")
        assertEquals(1, store.readEntries(MemoryStore.USER_FILE).size)
    }

    @Test
    fun `writing past the char limit evicts oldest entries`() {
        repeat(60) { i -> store.add(MemoryStore.USER_FILE, "fact number $i about the user's daily routine") }
        val content = store.read(MemoryStore.USER_FILE)
        assertTrue(content.length <= MemoryStore.MAX_USER_CHARS)
        assertTrue(content.contains("fact number 59"), "newest entry must survive eviction")
        assertFalse(content.contains("fact number 0 "), "oldest entry should have been evicted")
    }

    @Test
    fun `soul is seeded once and not overwritten afterwards`() {
        store.seedSoulIfEmpty()
        val seeded = store.readEntries(MemoryStore.SOUL_FILE)
        assertEquals(MemoryStore.DEFAULT_SOUL.size, seeded.size)

        store.add(MemoryStore.SOUL_FILE, "I answer in German when the user writes German")
        store.seedSoulIfEmpty()
        assertEquals(MemoryStore.DEFAULT_SOUL.size + 1, store.readEntries(MemoryStore.SOUL_FILE).size)
    }

    @Test
    fun `snapshot then restore undoes the last write`() {
        store.add(MemoryStore.USER_FILE, "Janik prefers Kotlin")
        store.snapshot()
        store.writeRaw(MemoryStore.USER_FILE, "")
        assertEquals("", store.read(MemoryStore.USER_FILE))

        assertTrue(store.restoreLatestBackup(MemoryStore.USER_FILE))
        assertTrue(store.read(MemoryStore.USER_FILE).contains("Janik prefers Kotlin"))
    }

    @Test
    fun `restore reports failure when there is no backup`() {
        assertFalse(store.restoreLatestBackup(MemoryStore.USER_FILE))
    }

    @Test
    fun `only the newest backups are kept`() {
        repeat(MemoryStore.MAX_BACKUPS + 4) { i ->
            store.add(MemoryStore.USER_FILE, "entry $i")
            store.snapshot()
            Thread.sleep(2)   // backups are keyed by millisecond timestamp
        }
        assertTrue(store.backupsFor(MemoryStore.USER_FILE).size <= MemoryStore.MAX_BACKUPS)
    }

    @Test
    fun `pending changes do not touch the live file until approved`() {
        store.add(MemoryStore.SOUL_FILE, "original identity")
        store.writePending(MemoryStore.SOUL_FILE, "rewritten identity")

        assertTrue(store.hasPending(MemoryStore.SOUL_FILE))
        assertTrue(store.hasAnyPending())
        assertTrue(store.read(MemoryStore.SOUL_FILE).contains("original identity"))

        assertTrue(store.applyPending(MemoryStore.SOUL_FILE))
        assertEquals("rewritten identity", store.read(MemoryStore.SOUL_FILE))
        assertFalse(store.hasPending(MemoryStore.SOUL_FILE))
        // applying snapshots first, so the old identity is still recoverable
        assertTrue(store.backupsFor(MemoryStore.SOUL_FILE).isNotEmpty())
    }

    @Test
    fun `discarding a pending change leaves the live file alone`() {
        store.add(MemoryStore.USER_FILE, "keep me")
        store.writePending(MemoryStore.USER_FILE, "throw me away")
        store.discardPending(MemoryStore.USER_FILE)

        assertNull(store.readPending(MemoryStore.USER_FILE))
        assertTrue(store.read(MemoryStore.USER_FILE).contains("keep me"))
    }

    @Test
    fun `file keys map both ways`() {
        assertEquals(MemoryStore.SOUL_FILE, MemoryStore.fileForKey(MemoryStore.KEY_SOUL))
        assertEquals(MemoryStore.USER_FILE, MemoryStore.fileForKey(MemoryStore.KEY_USER))
        assertEquals(MemoryStore.MEMORY_FILE, MemoryStore.fileForKey(MemoryStore.KEY_MEMORY))
        assertNull(MemoryStore.fileForKey("nonsense"))
        assertEquals(MemoryStore.KEY_SOUL, MemoryStore.keyForFile(MemoryStore.SOUL_FILE))
    }
}
