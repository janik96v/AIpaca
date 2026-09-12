package com.aipaca.app.agent

import com.aipaca.app.agent.mcp.ToolSpec
import com.aipaca.app.agent.memory.MemoryStore
import com.aipaca.app.engine.GenerateParams

/**
 * Agent-side configuration: the prompt-resident memory layers plus the tool-manifest
 * rendering used to make the model aware of available tools without touching the
 * public OpenAI wire format (`server/models/OpenAIModels.kt` has no tools/tool_calls
 * fields by design — see 00_repo_analysis.md §7.2).
 *
 * The four layers, in prompt order (issue #52):
 *
 * | Layer          | Field             | What it holds                                  |
 * |----------------|-------------------|------------------------------------------------|
 * | soul.md        | [soulSnapshot]    | who the agent is, what the user expects of it   |
 * | user.md        | [userSnapshot]    | facts and preferences about the user            |
 * | memory.md      | [sessionIndex]    | one line per past conversation                  |
 * | skills/        | [skillIndex]      | one line per learned procedure                  |
 *
 * The last two are indexes only: full bodies load on demand via `session_view` and
 * `skill_view`. [memorySnapshot] carries environment facts alongside the user layer.
 */
data class AgentConfig(
    /** Fallback identity, used only until soul.md has content. */
    val persona: String = "You are AIpaca's on-device agent: helpful, concise, and honest about uncertainty.",
    val systemPrompt: String = "Only call a tool when it is actually needed, and never simulate or " +
        "pretend to use one — always use the real tool-call format.\n\n" +
        "You have a persistent memory tool. Use it to remember user preferences, corrections, " +
        "and environment facts. Store personal preferences in the 'user' file and technical facts " +
        "in the 'memory' file. Keep entries to one short sentence. Do not store transient errors " +
        "or one-off task details.",
    val soulSnapshot: String = "",     // frozen at session start
    val memorySnapshot: String = "",   // frozen at session start — next session sees writes
    val userSnapshot: String = "",     // frozen at session start
    val sessionIndex: String = "",     // compact index of past conversations
    val skillIndex: String = "",       // compact name+description index of learned skills
    val maxToolRounds: Int = TierPolicy.ASSISTED_MAX_ROUNDS,
    /**
     * Malformed tool calls tolerated before the loop drops its tools and answers
     * plainly. Small models occasionally emit tool syntax as prose; showing the
     * user an error in that case is strictly worse than answering without tools.
     */
    val malformedCallsBeforeDegrade: Int = TierPolicy.MALFORMED_CALLS_BEFORE_DEGRADE,
    val generateParams: GenerateParams = GenerateParams(maxTokens = 768)
)

/**
 * Renders the combined system prompt handed to the engine for an agent turn:
 * identity + task instructions + memory layers + a compact tool manifest.
 *
 * Kept intentionally terse — research/20_kurzbericht_edge_kontext.md flags tool-schema
 * and prompt size as a binary enablement factor under tight on-device context budgets.
 */
fun AgentConfig.renderSystemPrompt(tools: List<ToolSpec>): String {
    // NOTE: Do NOT include tool-calling format instructions here.
    // The Jinja chat template (applied via common_chat_templates_apply in C++) already
    // renders the model's native tool-call syntax. Duplicating instructions here confuses
    // the model and causes it to hallucinate fake tool calls as plain text.
    val hasFileTool = tools.any { it.name == TierPolicy.TOOL_FILES }

    return buildString {
        if (soulSnapshot.isNotBlank()) {
            append("## Who You Are (memory/" + MemoryStore.SOUL_FILE + ")\n")
            append(soulSnapshot)
        } else {
            append(persona)
        }
        append("\n\n")
        append(systemPrompt)
        // Naming the files matters: asked about "soul.md" with only anonymous
        // headings in the prompt, the model has no referent for the name and
        // truthfully answers it has no such file (issue #54).
        if (hasFileTool) {
            append("\n\nThe sections below are your own persistent memory files on disk. " +
                "Read them in full with files(action='read', path='memory/<name>') and list them " +
                "with files(action='list', path='memory'). You can also read your learned skills " +
                "under 'skills/' and keep working files under 'workspace/'. " +
                "You cannot edit " + MemoryStore.SOUL_FILE + " directly — a write to it becomes " +
                "a proposal the user approves.")
        }
        // Memory snapshots are frozen at session start — writes persist to disk
        // but only appear in the next session (preserves KV cache across turns).
        if (userSnapshot.isNotBlank()) {
            append("\n\n## About the User (memory/" + MemoryStore.USER_FILE + ")\n")
            append(userSnapshot)
        }
        if (memorySnapshot.isNotBlank()) {
            append("\n\n## Remembered Context (memory/" + MemoryStore.MEMORY_FILE + ")\n")
            append(memorySnapshot)
        }
        if (sessionIndex.isNotBlank()) {
            append("\n\n## Past Conversations\n")
            append(sessionIndex)
            append("\nUse session_view(session_id) with an id in square brackets to read one in full.")
        }
        if (skillIndex.isNotBlank()) {
            append("\n\n## Your Learned Skills\n")
            append(skillIndex)
            append("\nUse skill_view(name) to load a skill's full procedure before applying it.")
        }
    }
}
