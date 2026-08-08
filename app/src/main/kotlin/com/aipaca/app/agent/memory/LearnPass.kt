package com.aipaca.app.agent.memory

import android.util.Log
import com.aipaca.app.agent.AgentConfig
import com.aipaca.app.agent.AgentMessage
import com.aipaca.app.agent.AgentOrchestrator
import com.aipaca.app.agent.AgentStep
import com.aipaca.app.agent.mcp.ToolSpec
import com.aipaca.app.agent.tool.ToolRegistry
import com.aipaca.app.engine.GenerateParams
import com.aipaca.app.engine.LlamaCppEngine
import kotlinx.coroutines.sync.Mutex

private const val TAG = "LearnPass"

/**
 * Counter-triggered sequential review pass that runs after the user gets their answer.
 *
 * Examines a digest of the recent conversation and decides whether to create/update
 * skills or memory entries. Runs sequentially (not parallel — single NPU) and only
 * when the counter thresholds are met.
 *
 * Key design choices (from Hermes):
 * - Counter-based triggers, not LLM judge — zero inference cost for the meta-decision.
 * - Restricted tool whitelist (only memory + skill_manage) — prevents runaway tool usage.
 * - Max 4 tool rounds — review shouldn't be expensive.
 * - Digest input (last 6-8 messages) — not full history replay.
 */
object LearnPass {

    const val SKILL_REVIEW_THRESHOLD = 10   // Hermes default: 10 tool iterations
    const val MEMORY_REVIEW_THRESHOLD = 10  // Hermes default: 10 user turns

    private const val MEMORY_REVIEW_PROMPT = """Review this conversation. What did the user reveal about themselves or their environment that's worth remembering? Use the memory tool to store useful facts.

Rules:
- Store in 'user' file for personal preferences, 'memory' file for technical facts
- Keep each entry to one concise sentence
- Do NOT store: transient errors, one-off task details, negative claims like "tool X doesn't work"
- Do NOT duplicate entries that already exist in the remembered context
- If nothing is worth saving, simply respond "Nothing to save." without calling any tools"""

    private const val SKILL_REVIEW_PROMPT = """Review this conversation. Was a non-trivial reusable procedure demonstrated?

If so, save it as a skill using skill_manage. Prefer: patch existing skill > create new skill.

Rules:
- Skills should be reusable procedures, not one-off task narratives
- Keep descriptions under 60 characters
- Do NOT capture: environment-specific errors, dead-end attempts, or debugging sessions
- If no reusable procedure was demonstrated, simply respond "Nothing to save." without calling any tools"""

    private const val COMBINED_REVIEW_PROMPT = """Review this conversation for two things:

1. MEMORY: What did the user reveal about themselves or their environment worth remembering?
   - Store in 'user' file for personal preferences, 'memory' file for technical facts
   - Keep each entry to one concise sentence

2. SKILLS: Was a non-trivial reusable procedure demonstrated?
   - Prefer: patch existing skill > create new skill
   - Keep descriptions under 60 characters

Rules for both:
- Do NOT store transient errors, one-off task details, or negative claims
- Do NOT duplicate existing entries
- If nothing is worth saving, simply respond "Nothing to save." without calling any tools"""

    /**
     * Runs a sequential review pass after a complex agent turn.
     *
     * @param conversationDigest Last N messages from the conversation (not full replay)
     * @param reviewType Which kind of review to perform
     * @param memoryStore For memory tool access
     * @param skillStore For skill tool access
     * @param memorySnapshot Current memory snapshot (injected into review so the model can avoid duplicates)
     * @param userSnapshot Current user snapshot
     * @param engine For inference
     * @param generateMutex Shared lock
     */
    suspend fun run(
        conversationDigest: List<AgentMessage>,
        reviewType: ReviewType,
        memoryStore: MemoryStore,
        skillStore: SkillStore,
        memorySnapshot: String,
        userSnapshot: String,
        engine: LlamaCppEngine,
        generateMutex: Mutex
    ) {
        Log.i(TAG, "Starting $reviewType review pass (digest=${conversationDigest.size} messages)")

        // Build a restricted tool registry with only memory + skill_manage
        val reviewRegistry = ToolRegistry()
        reviewRegistry.registerLocal(
            ToolSpec(name = MemoryTool.NAME, description = MemoryTool.DESCRIPTION, inputSchema = MemoryTool.INPUT_SCHEMA)
        ) { args -> MemoryTool.run(args, memoryStore) }

        if (reviewType == ReviewType.SKILL || reviewType == ReviewType.BOTH) {
            reviewRegistry.registerLocal(SkillTools.manageSpec()) { args ->
                SkillTools.manage(args, skillStore)
            }
        }

        // Build review prompt with existing memory context (so the model avoids duplicates)
        val reviewPrompt = when (reviewType) {
            ReviewType.MEMORY -> MEMORY_REVIEW_PROMPT
            ReviewType.SKILL -> SKILL_REVIEW_PROMPT
            ReviewType.BOTH -> COMBINED_REVIEW_PROMPT
        }

        val skillIdx = if (reviewType == ReviewType.SKILL || reviewType == ReviewType.BOTH) {
            skillStore.index().joinToString("\n") { (name, desc) -> "- $name: $desc" }
        } else ""

        val config = AgentConfig(
            persona = "You are a review agent. Your job is to extract reusable knowledge from conversations.",
            systemPrompt = reviewPrompt,
            memorySnapshot = memorySnapshot,
            userSnapshot = userSnapshot,
            skillIndex = skillIdx,
            maxToolRounds = 4,  // capped — review shouldn't be expensive
            generateParams = GenerateParams(maxTokens = 512, thinkingEnabled = false)
        )

        // Build the goal from the conversation digest
        val digestText = conversationDigest.joinToString("\n\n") { msg ->
            "${msg.role}: ${(msg as? AgentMessage.User)?.content
                ?: (msg as? AgentMessage.Assistant)?.content
                ?: (msg as? AgentMessage.System)?.content
                ?: ""}"
        }

        val orchestrator = AgentOrchestrator(
            engine = engine,
            generateMutex = generateMutex,
            tools = reviewRegistry,
            config = config,
            isModelLoaded = { engine.isLoaded() }
        )

        try {
            orchestrator.run(
                goal = "Here is the conversation to review:\n\n$digestText",
                history = emptyList()
            ).collect { step ->
                when (step) {
                    is AgentStep.ToolCall -> Log.d(TAG, "Review tool call: ${step.name}")
                    is AgentStep.FinalAnswer -> Log.d(TAG, "Review complete: ${step.text.take(100)}")
                    is AgentStep.Error -> Log.e(TAG, "Review error: ${step.message}")
                    else -> {} // ignore streaming chunks
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Learn pass failed", e)
        } finally {
            reviewRegistry.closeAll()
        }

        Log.i(TAG, "$reviewType review pass complete")
    }
}

enum class ReviewType { MEMORY, SKILL, BOTH }
