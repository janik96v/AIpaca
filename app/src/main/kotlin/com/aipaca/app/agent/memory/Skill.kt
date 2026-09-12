package com.aipaca.app.agent.memory

import kotlinx.serialization.Serializable

/**
 * A reusable procedure the agent has learned, stored as a SKILL.md file
 * with YAML frontmatter.
 *
 * Only [name] and [description] are included in the system prompt index.
 * The full [body] is loaded on demand via `skill_view` to keep context tight.
 */
@Serializable
data class Skill(
    val name: String,           // lowercase-hyphenated, max 64 chars
    val description: String,    // max 60 chars — this is what goes in the index
    val category: String = "",  // optional grouping
    val body: String = ""       // full procedure (loaded on demand, not in index)
)
