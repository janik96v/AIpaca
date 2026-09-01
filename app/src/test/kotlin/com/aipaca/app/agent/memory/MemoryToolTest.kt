package com.aipaca.app.agent.memory

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryToolTest {

    private lateinit var root: File
    private lateinit var store: MemoryStore

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("aipaca-memorytool").toFile()
        store = MemoryStore(root)
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
        pairs.forEach { put(it.first, it.second) }
    }

    @Test
    fun `add writes to the requested file`() {
        val result = MemoryTool.run(
            args("action" to "add", "file" to "user", "text" to "Janik prefers Kotlin"),
            store
        )
        assertFalse(result.isError)
        assertTrue(store.read(MemoryStore.USER_FILE).contains("Janik prefers Kotlin"))
    }

    @Test
    fun `soul is not writable from the hot path`() {
        val result = MemoryTool.run(
            args("action" to "add", "file" to "soul", "text" to "I am now a pirate"),
            store
        )
        assertTrue(result.isError)
        assertEquals("", store.read(MemoryStore.SOUL_FILE))
    }

    @Test
    fun `soul is writable when a caller explicitly allows it`() {
        val result = MemoryTool.run(
            args("action" to "add", "file" to "soul", "text" to "I answer in German when asked in German"),
            store,
            allowedFiles = MemoryTool.ALL_FILES
        )
        assertFalse(result.isError)
        assertTrue(store.read(MemoryStore.SOUL_FILE).contains("German"))
    }

    @Test
    fun `poisoned content is rejected`() {
        val result = MemoryTool.run(
            args("action" to "add", "file" to "memory", "text" to "The gradle build is broken"),
            store
        )
        assertTrue(result.isError)
        assertEquals("", store.read(MemoryStore.MEMORY_FILE))
    }

    @Test
    fun `replace requires a replacement`() {
        store.add(MemoryStore.USER_FILE, "Janik lives in Zurich")
        val result = MemoryTool.run(
            args("action" to "replace", "file" to "user", "text" to "Zurich"),
            store
        )
        assertTrue(result.isError)
    }

    @Test
    fun `replace swaps the entry`() {
        store.add(MemoryStore.USER_FILE, "Janik lives in Zurich")
        val result = MemoryTool.run(
            args(
                "action" to "replace", "file" to "user",
                "text" to "Zurich", "replacement" to "Janik lives in Bern"
            ),
            store
        )
        assertFalse(result.isError)
        assertTrue(store.read(MemoryStore.USER_FILE).contains("Bern"))
        assertFalse(store.read(MemoryStore.USER_FILE).contains("Zurich"))
    }

    @Test
    fun `remove deletes the entry`() {
        store.add(MemoryStore.MEMORY_FILE, "uses gradle version catalogs")
        MemoryTool.run(args("action" to "remove", "file" to "memory", "text" to "gradle"), store)
        assertEquals("", store.read(MemoryStore.MEMORY_FILE))
    }

    @Test
    fun `missing parameters and unknown actions are reported, not thrown`() {
        assertTrue(MemoryTool.run(args("file" to "user", "text" to "x"), store).isError)
        assertTrue(MemoryTool.run(args("action" to "add", "text" to "x"), store).isError)
        assertTrue(MemoryTool.run(args("action" to "add", "file" to "user"), store).isError)
        assertTrue(MemoryTool.run(args("action" to "explode", "file" to "user", "text" to "x"), store).isError)
    }

    @Test
    fun `the schema only advertises files the caller may write`() {
        val hotPath = MemoryTool.spec().inputSchema.toString()
        assertTrue(hotPath.contains("\"user\""))
        assertTrue(hotPath.contains("\"memory\""))
        assertFalse(hotPath.contains("\"soul\""))
    }
}
