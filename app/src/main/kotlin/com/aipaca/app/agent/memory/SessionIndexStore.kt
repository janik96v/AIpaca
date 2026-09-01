package com.aipaca.app.agent.memory

import android.content.Context
import android.util.Log
import java.io.File

private const val TAG = "SessionIndexStore"

/**
 * One line in the session index — a past conversation the agent can recall by name.
 *
 * @property accessCount how often the agent opened this session via `session_view`.
 *           Used for eviction: a conversation the agent keeps coming back to
 *           outlives an untouched one of the same age.
 */
data class SessionNote(
    val sessionId: String,
    val date: String,
    val title: String,
    val summary: String,
    val accessCount: Int = 0
)

/**
 * `memory.md` — the rolling index of past conversations (issue #52, requirement 3).
 *
 * This is the progressive-disclosure counterpart to [SkillStore]: only a one-line
 * description per session lives in the system prompt, and the full transcript is
 * pulled in on demand via `session_view` ([SessionViewTool]).
 *
 * It complements rather than replaces [SessionSearchTool]: the index answers
 * "what do I know about this user's history", FTS answers "find that exact thing".
 * Without the index the agent has to already know what to search for.
 *
 * Stored as `agent_memory/agent_sessions.md`, one `§` entry per session:
 * ```
 * § 2026-09-01 | <sessionId> | <accessCount> | <title> | <summary>
 * ```
 */
class SessionIndexStore(rootDir: File) {

    /** Production entry point — shares the memory directory with [MemoryStore]. */
    constructor(context: Context) : this(File(context.filesDir, MemoryStore.MEMORY_DIR))


    companion object {
        const val FILE = "agent_sessions.md"

        /** Hard cap on indexed sessions; ~30 lines is ~1500 chars of prompt. */
        const val MAX_NOTES = 30

        /** Cap on the rendered index, so a long tail of titles can't blow the budget. */
        const val MAX_INDEX_CHARS = 1500

        const val MAX_TITLE_CHARS = 48
        const val MAX_SUMMARY_CHARS = 120

        private const val FIELD_SEP = " | "

        /** Field separators must not appear inside a field. */
        fun sanitize(value: String, max: Int): String =
            value.replace("|", "/").replace(Regex("\\s+"), " ").trim().take(max)

        /** Serializes to the text half of a [MemoryEntry]. */
        fun encode(note: SessionNote): String = listOf(
            note.sessionId,
            note.accessCount.toString(),
            sanitize(note.title, MAX_TITLE_CHARS),
            sanitize(note.summary, MAX_SUMMARY_CHARS)
        ).joinToString(FIELD_SEP)

        /** Inverse of [encode]; returns null for malformed lines (they are dropped). */
        fun decode(date: String, text: String): SessionNote? {
            val parts = text.split(FIELD_SEP)
            if (parts.size < 4) return null
            val id = parts[0].trim()
            if (id.isBlank()) return null
            return SessionNote(
                sessionId = id,
                date = date,
                title = parts[2].trim(),
                summary = parts.drop(3).joinToString(FIELD_SEP).trim(),
                accessCount = parts[1].trim().toIntOrNull() ?: 0
            )
        }

        /**
         * Keeps the [MAX_NOTES] highest-scoring notes.
         *
         * Score is recency rank plus twice the access count, so an old conversation
         * the agent actually uses beats a newer one it never opened. Pure function —
         * the eviction rule is the part most worth testing.
         */
        fun evict(notes: List<SessionNote>, maxNotes: Int = MAX_NOTES): List<SessionNote> {
            if (notes.size <= maxNotes) return notes
            // notes are stored oldest-first, so the list index is the recency rank
            val scored = notes.mapIndexed { index, note -> note to (index + 2 * note.accessCount) }
            val keep = scored.sortedByDescending { it.second }.take(maxNotes).map { it.first }.toSet()
            return notes.filter { it in keep }
        }

        /** Renders the prompt-resident index block. */
        fun renderIndex(notes: List<SessionNote>, maxChars: Int = MAX_INDEX_CHARS): String {
            if (notes.isEmpty()) return ""
            val lines = notes.sortedByDescending { it.date }.map { note ->
                "- " + note.date + " · " + note.title + " · " + note.summary + "  [" + note.sessionId + "]"
            }
            val out = StringBuilder()
            for (line in lines) {
                if (out.length + line.length + 1 > maxChars) break
                if (out.isNotEmpty()) out.append('\n')
                out.append(line)
            }
            return out.toString()
        }
    }

    private val dir: File = rootDir.also { it.mkdirs() }

    private val file: File
        get() = File(dir, FILE)

    fun readRaw(): String = if (file.exists()) file.readText() else ""

    fun writeRaw(content: String) {
        file.writeText(content)
    }

    /** All notes, oldest first. */
    fun list(): List<SessionNote> =
        MemoryFormat.parse(readRaw()).mapNotNull { decode(it.date, it.text) }

    fun note(sessionId: String): SessionNote? = list().firstOrNull { it.sessionId == sessionId }

    fun hasNote(sessionId: String): Boolean = note(sessionId) != null

    fun writeAll(notes: List<SessionNote>) {
        val kept = evict(notes)
        writeRaw(MemoryFormat.render(kept.map { MemoryEntry(it.date, encode(it)) }))
        if (kept.size < notes.size) {
            Log.d(TAG, "evicted " + (notes.size - kept.size) + " session notes")
        }
    }

    /** Inserts or replaces the note for a session, preserving its access count. */
    fun upsert(note: SessionNote) {
        val existing = list()
        val previous = existing.firstOrNull { it.sessionId == note.sessionId }
        val merged = note.copy(accessCount = maxOf(note.accessCount, previous?.accessCount ?: 0))
        writeAll(existing.filterNot { it.sessionId == note.sessionId } + merged)
        Log.d(TAG, "upsert session note " + note.sessionId + ": " + merged.summary.take(60))
    }

    /** Bumps the access counter — called whenever `session_view` opens a session. */
    fun markAccessed(sessionId: String) {
        val existing = list()
        val idx = existing.indexOfFirst { it.sessionId == sessionId }
        if (idx < 0) return
        val updated = existing.toMutableList()
        updated[idx] = updated[idx].copy(accessCount = updated[idx].accessCount + 1)
        writeAll(updated)
    }

    fun remove(sessionId: String) {
        writeAll(list().filterNot { it.sessionId == sessionId })
    }

    /** The block injected into the system prompt. */
    fun renderIndex(): String = renderIndex(list())
}
