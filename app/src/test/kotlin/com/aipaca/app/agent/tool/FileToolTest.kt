package com.aipaca.app.agent.tool

import com.aipaca.app.agent.memory.MemoryStore
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

/**
 * Sandbox behaviour for the agent's `files` tool (issue #54).
 *
 * The rejection cases are the point of the suite: a path that escapes the
 * whitelisted roots, by any route, must be refused.
 */
class FileToolTest {

    private lateinit var root: File
    private lateinit var workspace: AgentWorkspace
    private lateinit var memoryStore: MemoryStore
    private lateinit var memoryDir: File
    private lateinit var workspaceDir: File

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("aipaca-filetool").toFile()
        memoryDir = File(root, "agent_memory")
        val skillsDir = File(root, "agent_skills")
        workspaceDir = File(root, "agent_workspace")
        workspace = AgentWorkspace(memoryDir, skillsDir, workspaceDir).also { it.ensureRoots() }
        memoryStore = MemoryStore(memoryDir)
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
        pairs.forEach { put(it.first, it.second) }
    }

    private fun run(vararg pairs: Pair<String, String>) =
        FileTool.run(args(*pairs), workspace, memoryStore)

    // ---- Reads --------------------------------------------------------------

    @Test
    fun `reads the soul file`() {
        memoryStore.writeRaw(MemoryStore.SOUL_FILE, "I am AIpaca.")
        val result = run("action" to "read", "path" to "memory/${MemoryStore.SOUL_FILE}")
        assertFalse(result.isError)
        assertTrue(result.text.contains("I am AIpaca."))
    }

    @Test
    fun `lists the memory root`() {
        memoryStore.writeRaw(MemoryStore.USER_FILE, "Janik prefers Kotlin")
        val result = run("action" to "list", "path" to "memory")
        assertFalse(result.isError)
        assertTrue(result.text.contains(MemoryStore.USER_FILE))
    }

    @Test
    fun `read truncates oversize files with a marker`() {
        val big = "x".repeat(AgentWorkspace.MAX_READ_CHARS + 500)
        File(workspaceDir, "big.txt").writeText(big)
        val result = run("action" to "read", "path" to "workspace/big.txt")
        assertFalse(result.isError)
        assertTrue(result.text.contains("[...truncated]"))
        assertTrue(result.text.length < big.length)
    }

    // ---- Sandbox rejection --------------------------------------------------

    @Test
    fun `rejects parent directory traversal`() {
        File(root, "secret.txt").writeText("top secret")
        val result = run("action" to "read", "path" to "workspace/../../secret.txt")
        assertTrue(result.isError)
        assertFalse(result.text.contains("top secret"))
    }

    @Test
    fun `rejects traversal that stays syntactically inside the root`() {
        File(root, "secret.txt").writeText("top secret")
        // Lands outside only after resolution — the raw string looks harmless.
        val result = run("action" to "read", "path" to "workspace/sub/../../../secret.txt")
        assertTrue(result.isError)
    }

    @Test
    fun `rejects absolute paths`() {
        val result = run("action" to "read", "path" to "/etc/hosts")
        assertTrue(result.isError)
        assertTrue(result.text.contains("Absolute"))
    }

    @Test
    fun `rejects a symlink pointing outside the sandbox`() {
        val outside = File(root, "outside.txt").apply { writeText("escaped") }
        val link = File(workspaceDir, "link.txt")
        try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
        } catch (e: Exception) {
            return // filesystem does not permit symlinks; nothing to assert
        }
        val result = run("action" to "read", "path" to "workspace/link.txt")
        assertTrue(result.isError)
        assertFalse(result.text.contains("escaped"))
    }

    @Test
    fun `rejects unknown roots`() {
        val result = run("action" to "read", "path" to "etc/passwd")
        assertTrue(result.isError)
        assertTrue(result.text.contains("Unknown root"))
    }

    @Test
    fun `rejects the backup directory`() {
        val result = run("action" to "list", "path" to "memory/backup")
        assertTrue(result.isError)
    }

    @Test
    fun `rejects pending files`() {
        memoryStore.writePending(MemoryStore.SOUL_FILE, "proposed persona")
        val result = run("action" to "read", "path" to "memory/${MemoryStore.SOUL_FILE}.pending")
        assertTrue(result.isError)
        assertFalse(result.text.contains("proposed persona"))
    }

    @Test
    fun `listing hides backup and pending artifacts`() {
        memoryStore.writeRaw(MemoryStore.SOUL_FILE, "soul")
        memoryStore.snapshot()
        memoryStore.writePending(MemoryStore.SOUL_FILE, "proposed")
        val result = run("action" to "list", "path" to "memory")
        assertFalse(result.isError)
        assertFalse(result.text.contains("backup"))
        assertFalse(result.text.contains(".pending"))
    }

    @Test
    fun `rejects writes over the size cap`() {
        val result = run(
            "action" to "write",
            "path" to "workspace/big.txt",
            "content" to "x".repeat(AgentWorkspace.MAX_WRITE_CHARS + 1)
        )
        assertTrue(result.isError)
        assertFalse(File(workspaceDir, "big.txt").exists())
    }

    @Test
    fun `rejects a write that would exceed the workspace budget`() {
        val chunk = "y".repeat(AgentWorkspace.MAX_WRITE_CHARS)
        var i = 0
        // Fill the workspace up to its cap with legal writes.
        while (workspace.workspaceBytes() + chunk.length <= AgentWorkspace.MAX_WORKSPACE_BYTES) {
            File(workspaceDir, "fill$i.txt").writeText(chunk)
            i++
        }
        val result = run("action" to "write", "path" to "workspace/overflow.txt", "content" to chunk)
        assertTrue(result.isError)
        assertTrue(result.text.contains("full"))
    }

    // ---- Write policy -------------------------------------------------------

    @Test
    fun `writes and reads back a workspace file`() {
        val written = run("action" to "write", "path" to "workspace/notes.md", "content" to "hello")
        assertFalse(written.isError)
        val read = run("action" to "read", "path" to "workspace/notes.md")
        assertEquals("hello", read.text)
    }

    @Test
    fun `append adds to an existing file`() {
        run("action" to "write", "path" to "workspace/notes.md", "content" to "one")
        run("action" to "append", "path" to "workspace/notes.md", "content" to "-two")
        assertEquals("one-two", run("action" to "read", "path" to "workspace/notes.md").text)
    }

    @Test
    fun `edit replaces text in place`() {
        run("action" to "write", "path" to "workspace/notes.md", "content" to "hello world")
        val edited = run(
            "action" to "edit", "path" to "workspace/notes.md",
            "old_text" to "world", "new_text" to "there"
        )
        assertFalse(edited.isError)
        assertEquals("hello there", run("action" to "read", "path" to "workspace/notes.md").text)
    }

    @Test
    fun `a soul write lands as a pending proposal, never as an overwrite`() {
        memoryStore.writeRaw(MemoryStore.SOUL_FILE, "original persona")
        val result = run(
            "action" to "write",
            "path" to "memory/${MemoryStore.SOUL_FILE}",
            "content" to "I am now a pirate"
        )
        assertFalse(result.isError)
        assertEquals("original persona", memoryStore.read(MemoryStore.SOUL_FILE))
        assertEquals("I am now a pirate", memoryStore.readPending(MemoryStore.SOUL_FILE))
    }

    @Test
    fun `writes to user memory run through the anti-poisoning filter`() {
        memoryStore.writeRaw(MemoryStore.USER_FILE, "existing note")
        val result = run(
            "action" to "write",
            "path" to "memory/${MemoryStore.USER_FILE}",
            "content" to "The app crashed with a stack trace"
        )
        assertTrue(result.isError)
        assertEquals("existing note", memoryStore.read(MemoryStore.USER_FILE))
    }

    @Test
    fun `an append cannot smuggle poisoned text past the filter`() {
        memoryStore.writeRaw(MemoryStore.MEMORY_FILE, "clean existing content")
        val result = run(
            "action" to "append",
            "path" to "memory/${MemoryStore.MEMORY_FILE}",
            "content" to "\nthe build crashed"
        )
        assertTrue(result.isError)
        assertEquals("clean existing content", memoryStore.read(MemoryStore.MEMORY_FILE))
    }

    @Test
    fun `clean writes to the memory layer are allowed`() {
        val result = run(
            "action" to "write",
            "path" to "memory/${MemoryStore.MEMORY_FILE}",
            "content" to "This project builds with Gradle."
        )
        assertFalse(result.isError)
        assertTrue(memoryStore.read(MemoryStore.MEMORY_FILE).contains("Gradle"))
    }

    @Test
    fun `delete only works inside the workspace`() {
        memoryStore.writeRaw(MemoryStore.USER_FILE, "keep me")
        val denied = run("action" to "delete", "path" to "memory/${MemoryStore.USER_FILE}")
        assertTrue(denied.isError)
        assertEquals("keep me", memoryStore.read(MemoryStore.USER_FILE))

        run("action" to "write", "path" to "workspace/tmp.txt", "content" to "scratch")
        val allowed = run("action" to "delete", "path" to "workspace/tmp.txt")
        assertFalse(allowed.isError)
        assertFalse(File(workspaceDir, "tmp.txt").exists())
    }

    @Test
    fun `refuses to delete the workspace root`() {
        val result = run("action" to "delete", "path" to "workspace")
        assertTrue(result.isError)
        assertTrue(workspaceDir.exists())
    }

    @Test
    fun `unknown action is reported, not executed`() {
        val result = run("action" to "chmod", "path" to "workspace/notes.md")
        assertTrue(result.isError)
    }
}
