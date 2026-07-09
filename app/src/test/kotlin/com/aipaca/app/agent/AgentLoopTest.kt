package com.aipaca.app.agent

import com.aipaca.app.agent.mcp.McpClient
import com.aipaca.app.agent.mcp.ToolResult
import com.aipaca.app.agent.mcp.ToolSpec
import com.aipaca.app.agent.tool.ToolRegistry
import com.aipaca.app.engine.BenchResult
import com.aipaca.app.engine.ChatTurn
import com.aipaca.app.engine.GenerateParams
import com.aipaca.app.engine.GenerationChunk
import com.aipaca.app.engine.InferenceEngine
import com.aipaca.app.engine.InferenceStats
import com.aipaca.app.engine.ModelInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Fake [InferenceEngine] whose [generateChat] returns a scripted sequence of responses,
 * one per call — lets tests script a full think→tool→observe→repeat conversation without
 * any native llama.cpp dependency.
 */
private class ScriptedEngine(private val responses: List<String>) : InferenceEngine {
    var callCount = 0
        private set
    val turnsPerCall = mutableListOf<List<ChatTurn>>()

    override suspend fun loadModel(modelPath: String, nThreads: Int, contextSize: Int, nGpuLayers: Int): Result<Unit> =
        Result.success(Unit)

    override fun generate(userPrompt: String, params: GenerateParams): Flow<GenerationChunk> =
        generateChat(listOf(ChatTurn("user", userPrompt)), params)

    override fun generateChat(turns: List<ChatTurn>, params: GenerateParams): Flow<GenerationChunk> = flow {
        turnsPerCall += turns
        val response = responses.getOrElse(callCount) { responses.last() }
        callCount++
        emit(GenerationChunk(content = response))
    }

    override suspend fun benchmark(pp: Int, tg: Int, pl: Int, nr: Int): Result<BenchResult> =
        Result.success(BenchResult())

    override fun stopGeneration() {}
    override fun unload() {}
    override fun isLoaded(): Boolean = true
    override fun getModelPath(): String? = "fake-model.gguf"
    override fun getStats(): InferenceStats = InferenceStats()
    override fun getActiveGpuLayers(): Int = 0
    override fun getModelInfo(): ModelInfo = ModelInfo()
}

private class FakeMcpClient(
    private val tools: List<ToolSpec>,
    private val onCall: (String, JsonObject) -> ToolResult
) : McpClient {
    override suspend fun connect() {}
    override suspend fun listTools(): List<ToolSpec> = tools
    override suspend fun callTool(name: String, arguments: JsonObject): ToolResult = onCall(name, arguments)
    override fun close() {}
}

class AgentLoopTest {

    @Test
    fun `returns error step when no model is loaded`() = runTest {
        val engine = ScriptedEngine(listOf("unused"))
        val loop = AgentLoop(
            engine = engine,
            generateMutex = Mutex(),
            tools = ToolRegistry(),
            isModelLoaded = { false }
        )

        val steps = loop.run("What's the weather?")

        assertEquals(1, steps.size)
        assertIs<AgentStep.Error>(steps[0])
    }

    @Test
    fun `plain answer with no tool call returns FinalAnswer directly`() = runTest {
        val engine = ScriptedEngine(listOf("Paris is the capital of France."))
        val loop = AgentLoop(engine = engine, generateMutex = Mutex(), tools = ToolRegistry())

        val steps = loop.run("What is the capital of France?")

        assertEquals(2, steps.size) // Thinking + FinalAnswer
        assertIs<AgentStep.FinalAnswer>(steps.last())
        assertEquals("Paris is the capital of France.", (steps.last() as AgentStep.FinalAnswer).text)
        assertEquals(1, engine.callCount)
    }

    @Test
    fun `tool call round trip executes tool and grounds the final answer`() = runTest {
        val toolCallResponse = """<tool_call>{"name": "tavily_search", "arguments": {"query": "AIpaca release"}}</tool_call>"""
        val finalResponse = "AIpaca 0.3.0 was released recently, per the search results."
        val engine = ScriptedEngine(listOf(toolCallResponse, finalResponse))

        val registry = ToolRegistry()
        registry.register(
            FakeMcpClient(
                tools = listOf(ToolSpec(name = "tavily_search", description = "Web search")),
                onCall = { _, args -> ToolResult(text = "Found: AIpaca 0.3.0 changelog") }
            )
        )

        val loop = AgentLoop(engine = engine, generateMutex = Mutex(), tools = registry)

        val steps = loop.run("What's new in AIpaca?")

        assertEquals(5, steps.size) // Thinking, ToolCall, ToolObservation, Thinking, FinalAnswer
        assertIs<AgentStep.ToolCall>(steps[1])
        assertEquals("tavily_search", (steps[1] as AgentStep.ToolCall).name)
        assertIs<AgentStep.ToolObservation>(steps[2])
        assertTrue((steps[2] as AgentStep.ToolObservation).result.text.contains("AIpaca 0.3.0"))
        assertIs<AgentStep.FinalAnswer>(steps[4])
        assertEquals(finalResponse, (steps[4] as AgentStep.FinalAnswer).text)
        assertEquals(2, engine.callCount)

        // The second generateChat call must have the tool result fed back into context.
        val secondCallTurns = engine.turnsPerCall[1]
        assertTrue(secondCallTurns.any { it.content.contains("AIpaca 0.3.0 changelog") })
    }

    @Test
    fun `unknown tool name is fed back as an error observation instead of crashing`() = runTest {
        val hallucinatedCall = """<tool_call>{"name": "nonexistent_tool", "arguments": {}}</tool_call>"""
        val finalResponse = "I don't have a tool for that, but here's what I know."
        val engine = ScriptedEngine(listOf(hallucinatedCall, finalResponse))

        val loop = AgentLoop(engine = engine, generateMutex = Mutex(), tools = ToolRegistry())

        val steps = loop.run("Do something obscure")

        assertIs<AgentStep.ToolObservation>(steps[2])
        assertTrue((steps[2] as AgentStep.ToolObservation).result.isError)
        assertIs<AgentStep.FinalAnswer>(steps.last())
    }

    @Test
    fun `exhausting max tool rounds still returns a final answer`() = runTest {
        val alwaysToolCall = """<tool_call>{"name": "tavily_search", "arguments": {"query": "x"}}</tool_call>"""
        val engine = ScriptedEngine(List(10) { alwaysToolCall })
        val registry = ToolRegistry()
        registry.register(
            FakeMcpClient(
                tools = listOf(ToolSpec(name = "tavily_search")),
                onCall = { _, _ -> ToolResult(text = "some result") }
            )
        )
        val config = AgentConfig(maxToolRounds = 2)

        val loop = AgentLoop(engine = engine, generateMutex = Mutex(), tools = registry, config = config)

        val steps = loop.run("Keep searching forever")

        assertIs<AgentStep.FinalAnswer>(steps.last())
        // 2 rounds * (Thinking+ToolCall+ToolObservation) + final forced answer generation
        assertEquals(3, engine.callCount)
    }
}
