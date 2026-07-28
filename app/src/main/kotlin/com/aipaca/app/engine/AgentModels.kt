package com.aipaca.app.engine

/**
 * Structured agent-inference types for the native tool-calling path (PR1).
 *
 * These are produced by [LlamaCppEngine.generateAgent], which drives llama.cpp's Jinja
 * tool template (`inputs.tools`) and its native PEG tool-call parser (`common_chat_parse`) —
 * replacing the old regex-based [com.aipaca.app.agent.ToolCallParser] path.
 *
 * See docs/roadmap/PR1_agent_foundation.md.
 */

/**
 * A single tool call the model requested, parsed natively by llama.cpp (not by regex).
 *
 * @property id            Tool-call id assigned by llama.cpp (used to key the matching tool result).
 * @property name          Tool name to dispatch (matched against the [com.aipaca.app.agent.tool.ToolRegistry]).
 * @property argumentsJson Raw JSON object string of the arguments, exactly as llama.cpp emitted it.
 */
data class AgentToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String
)

/**
 * One streamed chunk during an agent generation pass — mirrors [GenerationChunk] but scoped
 * to the agent path so future agent-only fields (e.g. tool-call deltas) don't leak into the
 * public chat/server types.
 */
data class AgentChunk(
    val content: String = "",
    val thinking: String = ""
)

/**
 * Final structured result of one agent generation pass: the assistant's free-text [content]
 * plus any [toolCalls] it requested. When [toolCalls] is empty the loop treats [content] as
 * the final answer; otherwise it executes the calls and feeds results back (see
 * `AgentOrchestrator`, Increment 3).
 */
data class AgentResult(
    val content: String,
    val toolCalls: List<AgentToolCall> = emptyList()
) {
    val hasToolCalls: Boolean get() = toolCalls.isNotEmpty()
}
