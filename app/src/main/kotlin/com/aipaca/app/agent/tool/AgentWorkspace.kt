package com.aipaca.app.agent.tool

import android.content.Context
import com.aipaca.app.agent.memory.MemoryStore
import java.io.File

/**
 * The sandbox boundary for [FileTool] (issue #54).
 *
 * The agent gets a filesystem, but only this one: three whitelisted roots under
 * app-internal storage. No source files, no APK or assets, no arbitrary paths,
 * nothing outside the app sandbox.
 *
 * | Root        | Backing directory      | Writable                      |
 * |-------------|------------------------|-------------------------------|
 * | `memory`    | `filesDir/agent_memory`| user/memory yes, soul staged  |
 * | `skills`    | `filesDir/agent_skills`| yes                           |
 * | `workspace` | `filesDir/agent_workspace` | yes, unfiltered scratch   |
 *
 * Resolution is the security-critical part. A path is only accepted when its
 * **canonical** form is still under a root — checking the raw string would let
 * `a/../../..` or a symlink out. [resolve] therefore canonicalizes first and
 * compares afterwards, and rejects absolute paths outright.
 */
class AgentWorkspace(
    private val memoryDir: File,
    private val skillsDir: File,
    private val workspaceDir: File
) {

    /** Production entry point — all three roots live under `filesDir`. */
    constructor(context: Context) : this(
        memoryDir = File(context.filesDir, MemoryStore.MEMORY_DIR),
        skillsDir = File(context.filesDir, SKILLS_DIR),
        workspaceDir = File(context.filesDir, WORKSPACE_DIR)
    )

    companion object {
        const val SKILLS_DIR = "agent_skills"
        const val WORKSPACE_DIR = "agent_workspace"

        const val ROOT_MEMORY = "memory"
        const val ROOT_SKILLS = "skills"
        const val ROOT_WORKSPACE = "workspace"

        val ROOTS = listOf(ROOT_MEMORY, ROOT_SKILLS, ROOT_WORKSPACE)

        /** Per-file read cap. Beyond this a read is truncated with a marker. */
        const val MAX_READ_CHARS = 8_000

        /** Per-file write cap — a model that loops on append must not fill storage. */
        const val MAX_WRITE_CHARS = 32_000

        /** Total bytes allowed under `workspace/`, checked before every write. */
        const val MAX_WORKSPACE_BYTES = 2L * 1024 * 1024

        /**
         * Approval-gated consolidation artifacts, invisible to the tool: `backup/`
         * holds the undo history and `*.pending` the proposals the user has not
         * approved yet. Letting the agent read or write either would route around
         * the approval step (see [MemoryStore.writePending]).
         */
        const val BACKUP_DIR = "backup"
        const val PENDING_SUFFIX = ".pending"
    }

    /** Outcome of resolving a caller-supplied path against the sandbox. */
    sealed class Resolution {
        data class Ok(val file: File, val root: String, val relativePath: String) : Resolution()
        data class Denied(val reason: String) : Resolution()
    }

    private fun dirForRoot(root: String): File? = when (root) {
        ROOT_MEMORY -> memoryDir
        ROOT_SKILLS -> skillsDir
        ROOT_WORKSPACE -> workspaceDir
        else -> null
    }

    /** Creates the roots. Safe to call repeatedly. */
    fun ensureRoots() {
        listOf(memoryDir, skillsDir, workspaceDir).forEach { it.mkdirs() }
    }

    /**
     * Resolves `"<root>/<relative path>"` (or just `"<root>"`) to a real file
     * inside the sandbox, or explains why it was refused.
     *
     * Rejects, in order: absolute paths, unknown roots, anything whose canonical
     * path escapes the root (covers `..` and symlinks), and the approval-gated
     * `backup/` and `*.pending` artifacts.
     */
    fun resolve(path: String): Resolution {
        val raw = path.trim()
        if (raw.isEmpty()) return Resolution.Denied("Path is empty. Use '<root>/<file>', root one of ${ROOTS.joinToString(", ")}.")

        // Absolute paths never name a sandbox location, so refuse before resolving.
        if (raw.startsWith("/") || raw.startsWith("\\") || raw.contains(":")) {
            return Resolution.Denied("Absolute paths are not allowed. Use '<root>/<file>'.")
        }

        val segments = raw.split('/', '\\').filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty()) return Resolution.Denied("Path is empty.")

        val root = segments.first()
        val rootDir = dirForRoot(root)
            ?: return Resolution.Denied("Unknown root '$root'. Allowed roots: ${ROOTS.joinToString(", ")}.")

        val relative = segments.drop(1)
        if (relative.any { it == BACKUP_DIR } || relative.any { it.endsWith(PENDING_SUFFIX) }) {
            return Resolution.Denied("'$BACKUP_DIR/' and '*$PENDING_SUFFIX' files are not accessible — they hold unapproved changes and backups.")
        }

        rootDir.mkdirs()
        val target = if (relative.isEmpty()) rootDir else File(rootDir, relative.joinToString(File.separator))

        // Canonicalize *then* compare: the raw string is not evidence of where the
        // path actually lands once `..` and symlinks are followed.
        val canonicalRoot = rootDir.canonicalFile
        val canonicalTarget = target.canonicalFile
        if (!canonicalTarget.isUnder(canonicalRoot)) {
            return Resolution.Denied("Path escapes the '$root' sandbox. '..', symlinks and absolute paths are not allowed.")
        }

        return Resolution.Ok(
            file = canonicalTarget,
            root = root,
            relativePath = relative.joinToString("/")
        )
    }

    /** True when this resolved path is one of the three prompt-resident memory files. */
    fun memoryKeyFor(resolution: Resolution.Ok): String? {
        if (resolution.root != ROOT_MEMORY) return null
        if (resolution.relativePath.contains('/')) return null
        return when (resolution.relativePath) {
            MemoryStore.SOUL_FILE -> MemoryStore.KEY_SOUL
            MemoryStore.USER_FILE -> MemoryStore.KEY_USER
            MemoryStore.MEMORY_FILE -> MemoryStore.KEY_MEMORY
            else -> null
        }
    }

    /** Current total size of `workspace/`, used to enforce [MAX_WORKSPACE_BYTES]. */
    fun workspaceBytes(): Long =
        workspaceDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** `true` when this file is inside the writable-scratch root. */
    fun isWorkspace(resolution: Resolution.Ok): Boolean = resolution.root == ROOT_WORKSPACE

    /** Hides the approval-gated artifacts from directory listings too. */
    fun isListable(file: File): Boolean =
        file.name != BACKUP_DIR && !file.name.endsWith(PENDING_SUFFIX)

    private fun File.isUnder(root: File): Boolean {
        if (this == root) return true
        val rootPath = root.path.trimEnd(File.separatorChar) + File.separator
        return path.startsWith(rootPath)
    }
}
