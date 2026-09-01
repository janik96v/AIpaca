package com.aipaca.app.agent.memory

import android.util.Log
import com.aipaca.app.agent.AgentConfig
import com.aipaca.app.agent.AgentOrchestrator
import com.aipaca.app.agent.AgentStep
import com.aipaca.app.agent.tool.ToolRegistry
import com.aipaca.app.engine.GenerateParams
import com.aipaca.app.engine.LlamaCppEngine
import com.aipaca.app.engine.OllamaEngine
import kotlinx.coroutines.sync.Mutex

private const val TAG = "SkillReviewPass"

/**
 * Counter-triggered review that turns a demonstrated procedure into a reusable skill.
 *
 * Unlike the memory passes this one stays on the tool-calling path: a skill body is
 * a multi-paragraph markdown document, which is exactly the shape that line-structured
 * text output handles badly and a `skill_manage` call handles well. The price is that
 * it only runs for models that can actually call tools — callers gate it on
 * [com.aipaca.app.agent.AgentTier.DEEP].
 */
object SkillReviewPass {

    /** Tool iterations between skill reviews. */
    const val SKILL_REVIEW_THRESHOLD = 10

    /** A review is a lookup-and-write, not an investigation. */
    private const val MAX_TOOL_ROUNDS = 4

    private const val REVIEW_PROMPT =
        """Review this conversation. Was a non-trivial reusable procedure demonstrated?

If so, save it as a skill using skill_manage. Prefer: patch existing skill > create new skill.

Rules:
- Skills should be reusable procedures, not one-off task narratives
- Keep descriptions under 60 characters
- Do NOT capture: environment-specific errors, dead-end attempts, or debugging sessions
- If no reusable procedure was demonstrated, simply respond "Nothing to save." without calling any tools"""

    /**
     * @param digest recent conversation as `role: text` lines
     * @param ollamaEngine non-null when the remote backend is active
     */
    suspend fun run(
        digest: String,
        skillStore: SkillStore,
        engine: LlamaCppEngine,
        generateMutex: Mutex,
        isModelLoaded: () -> Boolean,
        ollamaEngine: OllamaEngine? = null
    ) {
        if (digest.isBlank()) return
        Log.i(TAG, "starting skill review")

        val registry = ToolRegistry()
        registry.registerLocal(SkillTools.manageSpec()) { args -> SkillTools.manage(args, skillStore) }

        val config = AgentConfig(
            persona = "You are a review agent. Your job is to extract reusable procedures from conversations.",
            systemPrompt = REVIEW_PROMPT,
            skillIndex = skillStore.index().joinToString("\n") { pair -> "- " + pair.first + ": " + pair.second },
            maxToolRounds = MAX_TOOL_ROUNDS,
            generateParams = GenerateParams(maxTokens = 512, thinkingEnabled = false)
        )

        val orchestrator = AgentOrchestrator(
            engine = engine,
            generateMutex = generateMutex,
            tools = registry,
            config = config,
            isModelLoaded = isModelLoaded,
            ollamaEngine = ollamaEngine
        )

        try {
            orchestrator.run(
                goal = "Here is the conversation to review:\n\n" + digest,
                history = emptyList()
            ).collect { step ->
                when (step) {
                    is AgentStep.ToolCall -> Log.d(TAG, "review tool call: " + step.name)
                    is AgentStep.FinalAnswer -> Log.d(TAG, "review complete: " + step.text.take(100))
                    is AgentStep.Error -> Log.e(TAG, "review error: " + step.message)
                    else -> Unit
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "skill review failed", e)
        } finally {
            registry.closeAll()
        }
    }
}
