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
        "is insufficient or the user asks about current events. Only call a tool when it is actually needed.",
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
    if (tools.isEmpty()) return "$persona\n\n$systemPrompt"
    val toolLines = tools.joinToString("\n") { tool ->
        val desc = tool.description?.takeIf { it.isNotBlank() } ?: "no description"
        "- ${tool.name}: $desc"
    }
    return buildString {
        append(persona)
        append("\n\n")
        append(systemPrompt)
        append("\n\nAvailable tools:\n")
        append(toolLines)
        append(
            "\n\nTo call a tool, reply with exactly one tool call in your model's native format " +
                "and nothing else. After the tool result is returned to you, give the user a final " +
                "answer that cites the tool result."
        )
    }
}
