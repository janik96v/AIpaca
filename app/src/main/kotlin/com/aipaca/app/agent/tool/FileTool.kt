package com.aipaca.app.agent.tool

import android.util.Log
import com.aipaca.app.agent.mcp.ToolResult
import com.aipaca.app.agent.mcp.ToolSpec
import com.aipaca.app.agent.memory.AntiPoisoning
import com.aipaca.app.agent.memory.MemoryStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private const val TAG = "FileTool"

/**
 * Sandboxed filesystem access for the agent (issue #54).
 *
 * Before this, memory reached the model only as frozen prompt text and the
 * `memory` tool was write-only — so asked about its own `soul.md` the agent
 * truthfully answered it had none. This tool gives it a real, bounded filesystem:
 * it can list and read every one of its memory files, including soul, and keep
 * working files under `workspace/`.
 *
 * One tool with an `action` parameter rather than six tools, because tool-schema
 * size is a binary enablement factor under on-device context budgets
 * (`research/20_kurzbericht_edge_kontext.md`, Hebel C).
 *
 * **Write policy** — reads are unrestricted inside the sandbox; writes are not:
 *
 * - `workspace/…` — free, unfiltered scratch space
 * - `memory/agent_user.md`, `memory/agent_memory.md` — writable, but through the
 *   same [AntiPoisoning] filter the `memory` tool uses, so this is not a bypass
 * - `memory/agent_soul.md` — **read yes, direct write no.** A write is staged via
 *   [MemoryStore.writePending] for the user to approve, never applied inline. A
 *   model that can rewrite its own persona mid-conversation will do so, globally
 *   and invisibly.
 * - `skills/…` — writable (the skill files are already agent-managed)
 */
object FileTool {

    const val NAME = "files"
    const val DESCRIPTION = "List, read and write files in your own storage. " +
        "Roots: 'memory' (your soul/user/memory notes), 'skills' (learned procedures), " +
        "'workspace' (scratch files you create). Paths look like 'memory/agent_soul.md'."

    private const val TRUNCATION_MARKER = "\n[...truncated]"

    fun inputSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                putJsonArray("enum") {
                    listOf("list", "read", "write", "append", "edit", "delete")
                        .forEach { add(JsonPrimitive(it)) }
                }
                put("description", "Operation to perform")
            }
            putJsonObject("path") {
                put("type", "string")
                put("description", "'<root>/<file>', e.g. 'memory/agent_soul.md'. " +
                    "Roots: ${AgentWorkspace.ROOTS.joinToString(", ")}")
            }
            putJsonObject("content") {
                put("type", "string")
                put("description", "Content for 'write' and 'append'")
            }
            putJsonObject("old_text") {
                put("type", "string")
                put("description", "Text to find (only for 'edit')")
            }
            putJsonObject("new_text") {
                put("type", "string")
                put("description", "Replacement text (only for 'edit')")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("action")); add(JsonPrimitive("path"))
        }
    }

    fun spec(): ToolSpec = ToolSpec(
        name = NAME,
        description = DESCRIPTION,
        inputSchema = inputSchema()
    )

    fun run(args: JsonObject, workspace: AgentWorkspace, memoryStore: MemoryStore): ToolResult {
        val action = args["action"]?.jsonPrimitive?.content
            ?: return ToolResult("Missing 'action' parameter", isError = true)
        val path = args["path"]?.jsonPrimitive?.content
            ?: return ToolResult("Missing 'path' parameter", isError = true)

        val resolution = when (val r = workspace.resolve(path)) {
            is AgentWorkspace.Resolution.Denied -> {
                Log.w(TAG, "denied '$path': ${r.reason}")
                return ToolResult(r.reason, isError = true)
            }
            is AgentWorkspace.Resolution.Ok -> r
        }

        return when (action) {
            "list" -> list(resolution, workspace)
            "read" -> read(resolution)
            "write" -> write(resolution, args, workspace, memoryStore, append = false)
            "append" -> write(resolution, args, workspace, memoryStore, append = true)
            "edit" -> edit(resolution, args, workspace, memoryStore)
            "delete" -> delete(resolution, workspace)
            else -> ToolResult(
                "Unknown action '$action'. Use list, read, write, append, edit or delete.",
                isError = true
            )
        }
    }

    // ---- Actions ------------------------------------------------------------

    private fun list(r: AgentWorkspace.Resolution.Ok, workspace: AgentWorkspace): ToolResult {
        val file = r.file
        if (!file.exists()) return ToolResult("'${display(r)}' does not exist.", isError = true)
        if (file.isFile) return ToolResult("${display(r)} (${file.length()} bytes)")

        val entries = (file.listFiles() ?: emptyArray())
            .filter { workspace.isListable(it) }
            .sortedBy { it.name }
        if (entries.isEmpty()) return ToolResult("'${display(r)}' is empty.")

        val rendered = entries.joinToString("\n") { entry ->
            if (entry.isDirectory) "- ${entry.name}/" else "- ${entry.name} (${entry.length()} bytes)"
        }
        return ToolResult("Contents of '${display(r)}':\n$rendered")
    }

    private fun read(r: AgentWorkspace.Resolution.Ok): ToolResult {
        val file = r.file
        if (!file.exists()) return ToolResult("'${display(r)}' does not exist.", isError = true)
        if (file.isDirectory) return ToolResult("'${display(r)}' is a directory — use action 'list'.", isError = true)

        val text = file.readText()
        if (text.isBlank()) return ToolResult("'${display(r)}' is empty.")
        return if (text.length > AgentWorkspace.MAX_READ_CHARS) {
            ToolResult(text.take(AgentWorkspace.MAX_READ_CHARS) + TRUNCATION_MARKER)
        } else {
            ToolResult(text)
        }
    }

    private fun write(
        r: AgentWorkspace.Resolution.Ok,
        args: JsonObject,
        workspace: AgentWorkspace,
        memoryStore: MemoryStore,
        append: Boolean
    ): ToolResult {
        val content = args["content"]?.jsonPrimitive?.content
            ?: return ToolResult("Missing 'content' parameter", isError = true)
        if (r.file.isDirectory) {
            return ToolResult("'${display(r)}' is a directory.", isError = true)
        }
        val existing = if (append && r.file.exists()) r.file.readText() else ""
        val newContent = if (append) existing + content else content
        return commit(r, newContent, content, workspace, memoryStore)
    }

    private fun edit(
        r: AgentWorkspace.Resolution.Ok,
        args: JsonObject,
        workspace: AgentWorkspace,
        memoryStore: MemoryStore
    ): ToolResult {
        val oldText = args["old_text"]?.jsonPrimitive?.content
            ?: return ToolResult("Missing 'old_text' parameter for edit", isError = true)
        val newText = args["new_text"]?.jsonPrimitive?.content
            ?: return ToolResult("Missing 'new_text' parameter for edit", isError = true)
        if (!r.file.exists() || r.file.isDirectory) {
            return ToolResult("'${display(r)}' does not exist.", isError = true)
        }
        val current = r.file.readText()
        if (!current.contains(oldText)) {
            return ToolResult("Text not found in '${display(r)}'.", isError = true)
        }
        return commit(r, current.replaceFirst(oldText, newText), newText, workspace, memoryStore)
    }

    private fun delete(r: AgentWorkspace.Resolution.Ok, workspace: AgentWorkspace): ToolResult {
        // Deleting a memory file would wipe the whole layer in one call, and the
        // soul file has no approval path for deletion — only scratch is removable.
        if (!workspace.isWorkspace(r)) {
            return ToolResult(
                "Only files under 'workspace/' can be deleted. Use the 'memory' tool to remove individual notes.",
                isError = true
            )
        }
        if (r.relativePath.isEmpty()) {
            return ToolResult("Refusing to delete the workspace root.", isError = true)
        }
        if (!r.file.exists()) return ToolResult("'${display(r)}' does not exist.", isError = true)
        val ok = r.file.deleteRecursively()
        return if (ok) ToolResult("Deleted ${display(r)}")
        else ToolResult("Could not delete '${display(r)}'.", isError = true)
    }

    // ---- Write policy -------------------------------------------------------

    /**
     * Applies a write after the policy checks: size caps, the anti-poisoning
     * filter for the user/memory layers, and approval-staging for soul.
     *
     * [newText] is the caller-supplied fragment the filter judges — checking the
     * whole merged file instead would let an append smuggle content past a filter
     * that only sees already-trusted text.
     */
    private fun commit(
        r: AgentWorkspace.Resolution.Ok,
        fullContent: String,
        newText: String,
        workspace: AgentWorkspace,
        memoryStore: MemoryStore
    ): ToolResult {
        if (fullContent.length > AgentWorkspace.MAX_WRITE_CHARS) {
            return ToolResult(
                "Too large: ${fullContent.length} chars, limit is ${AgentWorkspace.MAX_WRITE_CHARS}.",
                isError = true
            )
        }

        val memoryKey = workspace.memoryKeyFor(r)
        if (memoryKey == MemoryStore.KEY_SOUL) {
            // Staged, never applied inline — the user approves soul edits.
            memoryStore.writePending(MemoryStore.SOUL_FILE, fullContent)
            Log.i(TAG, "staged soul change as pending proposal")
            return ToolResult(
                "Your soul file cannot be changed directly. The change is saved as a proposal " +
                    "for the user to approve on the Memory screen."
            )
        }
        if (memoryKey == MemoryStore.KEY_USER || memoryKey == MemoryStore.KEY_MEMORY) {
            if (AntiPoisoning.isPoisoned(newText)) {
                Log.w(TAG, "rejected poisoned content: ${newText.take(60)}")
                return ToolResult(
                    "Rejected: do not persist transient errors or negative claims.",
                    isError = true
                )
            }
        }

        if (workspace.isWorkspace(r)) {
            val projected = workspace.workspaceBytes() -
                (if (r.file.exists()) r.file.length() else 0L) + fullContent.length
            if (projected > AgentWorkspace.MAX_WORKSPACE_BYTES) {
                return ToolResult(
                    "Workspace is full (limit ${AgentWorkspace.MAX_WORKSPACE_BYTES / 1024} KB). Delete a file first.",
                    isError = true
                )
            }
        }

        r.file.parentFile?.mkdirs()
        r.file.writeText(fullContent)
        Log.d(TAG, "wrote ${display(r)} (${fullContent.length} chars)")
        return ToolResult("Wrote ${fullContent.length} chars to ${display(r)}")
    }

    private fun display(r: AgentWorkspace.Resolution.Ok): String =
        if (r.relativePath.isEmpty()) r.root else "${r.root}/${r.relativePath}"
}
