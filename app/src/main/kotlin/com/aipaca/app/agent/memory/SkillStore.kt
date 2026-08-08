package com.aipaca.app.agent.memory

import android.content.Context
import android.util.Log
import java.io.File

private const val TAG = "SkillStore"

/**
 * Filesystem-backed storage for learned skills.
 *
 * Each skill is a file `agent_skills/<name>.md` with YAML frontmatter
 * (name, description, category) and a markdown body. Parsed with a simple
 * regex splitter — no YAML library needed.
 *
 * Example file:
 * ```
 * ---
 * name: kotlin-coroutines
 * description: How to use structured concurrency in this project
 * category: kotlin
 * ---
 * ## When to Use
 * ...
 * ```
 */
class SkillStore(private val context: Context) {

    companion object {
        const val MAX_SKILLS = 50           // Hermes has no hard cap; 50 is practical for index size
        const val MAX_DESCRIPTION_CHARS = 60
        const val MAX_BODY_CHARS = 4000     // Hermes skills can be substantial; match their scale
    }

    private val dir: File
        get() = File(context.filesDir, "agent_skills").also { it.mkdirs() }

    private val frontmatterRegex = Regex(
        """^---\s*\n(.*?)\n---\s*\n(.*)$""",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )

    /** List all skills as (name, description) pairs — the compact index. */
    fun index(): List<Pair<String, String>> {
        val files = dir.listFiles { f -> f.extension == "md" } ?: return emptyList()
        return files.mapNotNull { f ->
            val skill = parseSkillFile(f) ?: return@mapNotNull null
            skill.name to skill.description
        }.sortedBy { it.first }
    }

    /** Load the full skill by name. Returns null if not found. */
    fun view(name: String): Skill? {
        val f = File(dir, "$name.md")
        if (!f.exists()) return null
        return parseSkillFile(f)
    }

    /** Create or overwrite a skill. Truncates description to [MAX_DESCRIPTION_CHARS]. */
    fun save(skill: Skill) {
        // Enforce limits
        val safeName = skill.name.take(64).lowercase().replace(Regex("[^a-z0-9-]"), "-")
        val safeDesc = skill.description.take(MAX_DESCRIPTION_CHARS)
        val safeBody = skill.body.take(MAX_BODY_CHARS)

        // Check skill count limit (only for new skills)
        val existing = File(dir, "$safeName.md").exists()
        if (!existing && (dir.listFiles { f -> f.extension == "md" }?.size ?: 0) >= MAX_SKILLS) {
            Log.w(TAG, "save: skill limit ($MAX_SKILLS) reached, cannot create '$safeName'")
            return
        }

        val content = buildString {
            appendLine("---")
            appendLine("name: $safeName")
            appendLine("description: $safeDesc")
            if (skill.category.isNotBlank()) appendLine("category: ${skill.category}")
            appendLine("---")
            append(safeBody)
        }
        File(dir, "$safeName.md").writeText(content)
        Log.d(TAG, "save: $safeName (${content.length} chars)")
    }

    /** Update an existing skill's body (patch). */
    fun patch(name: String, newBody: String) {
        val existing = view(name) ?: return
        save(existing.copy(body = newBody))
        Log.d(TAG, "patch: $name")
    }

    /** Delete a skill. */
    fun delete(name: String) {
        val f = File(dir, "$name.md")
        if (f.exists()) {
            f.delete()
            Log.d(TAG, "delete: $name")
        }
    }

    private fun parseSkillFile(file: File): Skill? {
        val text = file.readText()
        val match = frontmatterRegex.matchEntire(text) ?: return null
        val frontmatter = match.groupValues[1]
        val body = match.groupValues[2].trim()

        val name = extractField(frontmatter, "name") ?: return null
        val description = extractField(frontmatter, "description") ?: ""
        val category = extractField(frontmatter, "category") ?: ""

        return Skill(name = name, description = description, category = category, body = body)
    }

    private fun extractField(frontmatter: String, key: String): String? {
        val regex = Regex("^$key:\\s*(.+)$", RegexOption.MULTILINE)
        return regex.find(frontmatter)?.groupValues?.get(1)?.trim()
    }
}
