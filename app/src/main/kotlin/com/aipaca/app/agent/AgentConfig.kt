package com.aipaca.app.agent

import com.aipaca.app.agent.mcp.ToolSpec
import com.aipaca.app.engine.GenerateParams

/**
 * Agent-side configuration: system prompt / persona and the tool-manifest rendering
 * used to make the model aware of available tools without touching the public
 * OpenAI wire format (`server/models/OpenAIModels.kt` has no tools/tool_calls fields
 * by design — see 00_repo_analysis.md §7.2).
 *
 * Mirrors OpenClaw's AGENTS.md/SOUL.md/TOOLS.md split at a much smaller scale
 * (spec_issue_43_agent_mode.md §3): [persona] ~= SOUL.md, [systemPrompt] ~= AGENTS.md,
 * and the tool manifest rendered into the prompt ~= TOOLS.md.
 */
data class AgentConfig(
    val persona: String = "You are AIpaca's on-device agent: helpful, concise, and honest about uncertainty.",
    val systemPrompt: String = "You can call tools to look things up on the web when your own knowledge " +
        "is insufficient or the user asks about current events. Only call a tool when it is actually needed. " +
        "Never simulate or pretend to use tools — always use the actual tool_call format.\n\n" +
        "You have a persistent memory tool. Use it to remember user preferences, corrections, " +
        "and environment facts. Store in 'user' file for personal preferences, 'memory' file for technical facts. " +
        "Keep entries concise. Do not store transient errors or one-off task details.\n\n" +
        "When you solve a non-trivial multi-step problem, consider saving the procedure as a skill. " +
        "Before starting a task, check if a relevant skill exists in your skill index. " +
        "You can search past conversations using session_search when the user references something discussed before.",
    val memorySnapshot: String = "",   // frozen at session start — next session sees writes
    val userSnapshot: String = "",     // frozen at session start
    val skillIndex: String = "",       // compact name+description index of learned skills
    val maxToolRounds: Int = 4,
    val generateParams: GenerateParams = GenerateParams(maxTokens = 768)
)

/**
 * Renders the combined system prompt handed to [com.aipaca.app.engine.InferenceEngine.generateChat]
 * for an agent turn: persona + task instructions + a compact tool manifest.
 *
 * Kept intentionally terse — research/20_kurzbericht_edge_kontext_agentik.md flags tool-schema
 * size as a binary enablement factor under tight on-device context budgets.
 */
fun AgentConfig.renderSystemPrompt(tools: List<ToolSpec>): String {
    // NOTE: Do NOT include tool-calling format instructions here.
    // The Jinja chat template (applied via common_chat_templates_apply in C++) already
    // renders the model's native tool-call syntax. Duplicating instructions here confuses
    // the model and causes it to hallucinate fake tool calls as plain text.
    return buildString {
        append(persona)
        append("\n\n")
        append(systemPrompt)
        // Memory/user snapshots are frozen at session start — writes persist to disk
        // but only appear in the next session (preserves KV cache across turns).
        if (userSnapshot.isNotBlank()) {
            append("\n\n## About the User\n")
            append(userSnapshot)
        }
        if (memorySnapshot.isNotBlank()) {
            append("\n\n## Remembered Context\n")
            append(memorySnapshot)
        }
        if (skillIndex.isNotBlank()) {
            append("\n\n## Your Learned Skills\n")
            append(skillIndex)
            append("\nUse skill_view(name) to load a skill's full procedure before applying it.")
        }
    }
}
