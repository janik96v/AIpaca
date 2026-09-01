package com.aipaca.app.agent

/**
 * How much agency a single turn gets.
 *
 * AIpaca has no agent on/off switch. The old one was misleading anyway — it
 * required a Tavily key and silently did nothing without one, so it really meant
 * "web search on/off" rather than "agentic". What actually decides whether a small
 * on-device model succeeds is not a mode but two numbers: **how many tools sit in
 * the prompt** and **how many rounds the model may take**. Both are chosen here
 * from the model's measured capabilities.
 *
 * Evidence behind the thresholds:
 * - `docs/internal/research/20_kurzbericht_edge_kontext.md` Hebel C: at an 8K budget,
 *   uncompressed tool JSON collapses tool-calling to ~2.6 % EM while compact schemas
 *   restore +20.5 pp. Tool-schema size is a binary enablement factor, so the tool
 *   count is capped rather than "everything that is registered".
 * - Berkeley Function Calling Leaderboard: the 1–3B range is reliable for
 *   *single-turn* tool use and falls apart on multi-turn/parallel/nested calls.
 *   Hence a two-round default and a long round budget only for capable models.
 */
enum class AgentTier {
    /** No tools, one generation. Plain chat — also the fallback for everything unusual. */
    PLAIN,

    /** A small compact tool set, one or two rounds. The default for tool-capable models. */
    ASSISTED,

    /** The full tool set including skills and session recall, up to six rounds. */
    DEEP
}

/** Everything the tier decision looks at. Kept as data so the policy stays pure. */
data class TierInputs(
    /** The loaded GGUF actually has a tool-calling chat template (probed at load). */
    val toolCallingSupported: Boolean,
    /** Vision turns never get tools — mtmd and tool-templated prompts don't combine. */
    val hasAttachedImage: Boolean,
    /** KV context window in tokens. */
    val contextSize: Int,
    /** Ollama remote backend — not bound by on-device context or thermals. */
    val isRemoteBackend: Boolean
)

/**
 * Chooses the tier and the budgets that go with it.
 *
 * Deliberately a pure object with no Android dependencies: this is the piece whose
 * behaviour has to be verifiable without a device.
 */
object TierPolicy {

    /** Below this, the tool manifest itself crowds out the conversation. */
    const val MIN_CONTEXT_FOR_TOOLS = 4096

    /** Below this, multi-round tool loops thrash the context window. */
    const val MIN_CONTEXT_FOR_DEEP = 8192

    const val ASSISTED_MAX_ROUNDS = 2
    const val DEEP_MAX_ROUNDS = 6

    const val ASSISTED_MAX_TOOLS = 3
    const val DEEP_MAX_TOOLS = 6

    /**
     * After this many malformed tool calls in one turn the orchestrator drops the
     * tools and answers plainly, instead of showing the user an "Agent error".
     */
    const val MALFORMED_CALLS_BEFORE_DEGRADE = 2

    fun select(inputs: TierInputs): AgentTier {
        if (inputs.hasAttachedImage) return AgentTier.PLAIN
        if (!inputs.toolCallingSupported) return AgentTier.PLAIN
        if (inputs.isRemoteBackend) return AgentTier.DEEP
        if (inputs.contextSize < MIN_CONTEXT_FOR_TOOLS) return AgentTier.PLAIN
        if (inputs.contextSize < MIN_CONTEXT_FOR_DEEP) return AgentTier.ASSISTED
        return AgentTier.DEEP
    }

    fun maxRounds(tier: AgentTier): Int = when (tier) {
        AgentTier.PLAIN -> 1
        AgentTier.ASSISTED -> ASSISTED_MAX_ROUNDS
        AgentTier.DEEP -> DEEP_MAX_ROUNDS
    }

    fun maxTools(tier: AgentTier): Int = when (tier) {
        AgentTier.PLAIN -> 0
        AgentTier.ASSISTED -> ASSISTED_MAX_TOOLS
        AgentTier.DEEP -> DEEP_MAX_TOOLS
    }

    // Tool names, kept here so the budget rule is testable without a ToolRegistry.
    const val TOOL_MEMORY = "memory"
    const val TOOL_SESSION_SEARCH = "session_search"
    const val TOOL_SESSION_VIEW = "session_view"
    const val TOOL_SKILL_VIEW = "skill_view"
    const val TOOL_SKILL_MANAGE = "skill_manage"
    const val TOOL_WEB_SEARCH = "web_search"

    /**
     * The tools a turn may carry, most valuable first, already truncated to
     * [maxTools]. Web search is only offered when it is actually configured —
     * an unusable tool in the manifest costs context and invites failed calls.
     */
    fun toolNames(tier: AgentTier, webSearchConfigured: Boolean): List<String> {
        if (tier == AgentTier.PLAIN) return emptyList()
        val ordered = mutableListOf(TOOL_MEMORY, TOOL_SESSION_SEARCH)
        if (webSearchConfigured) ordered += TOOL_WEB_SEARCH
        if (tier == AgentTier.DEEP) {
            ordered += TOOL_SESSION_VIEW
            ordered += TOOL_SKILL_VIEW
            ordered += TOOL_SKILL_MANAGE
        }
        return ordered.take(maxTools(tier))
    }
}
