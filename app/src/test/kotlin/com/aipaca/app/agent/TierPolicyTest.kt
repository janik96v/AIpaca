package com.aipaca.app.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TierPolicyTest {

    private fun inputs(
        toolCalling: Boolean = true,
        image: Boolean = false,
        context: Int = 10240,
        remote: Boolean = false
    ) = TierInputs(
        toolCallingSupported = toolCalling,
        hasAttachedImage = image,
        contextSize = context,
        isRemoteBackend = remote
    )

    @Test
    fun `a model without a tool template never gets tools`() {
        assertEquals(AgentTier.PLAIN, TierPolicy.select(inputs(toolCalling = false)))
    }

    @Test
    fun `vision turns stay plain — mtmd and tool templates do not combine`() {
        assertEquals(AgentTier.PLAIN, TierPolicy.select(inputs(image = true)))
        assertEquals(AgentTier.PLAIN, TierPolicy.select(inputs(image = true, remote = true)))
    }

    @Test
    fun `a tiny context window gets no tool manifest at all`() {
        assertEquals(AgentTier.PLAIN, TierPolicy.select(inputs(context = 2048)))
    }

    @Test
    fun `a mid-sized context gets the compact tool set`() {
        assertEquals(AgentTier.ASSISTED, TierPolicy.select(inputs(context = 4096)))
        assertEquals(AgentTier.ASSISTED, TierPolicy.select(inputs(context = 8191)))
    }

    @Test
    fun `a roomy context gets the full set`() {
        assertEquals(AgentTier.DEEP, TierPolicy.select(inputs(context = 8192)))
        assertEquals(AgentTier.DEEP, TierPolicy.select(inputs(context = 32768)))
    }

    @Test
    fun `the remote backend is not bound by on-device context`() {
        assertEquals(AgentTier.DEEP, TierPolicy.select(inputs(context = 2048, remote = true)))
    }

    @Test
    fun `round budgets grow with the tier`() {
        assertEquals(1, TierPolicy.maxRounds(AgentTier.PLAIN))
        assertTrue(TierPolicy.maxRounds(AgentTier.ASSISTED) < TierPolicy.maxRounds(AgentTier.DEEP))
    }

    @Test
    fun `plain turns carry no tools`() {
        assertTrue(TierPolicy.toolNames(AgentTier.PLAIN, webSearchConfigured = true).isEmpty())
    }

    @Test
    fun `assisted stays inside its tool budget`() {
        val tools = TierPolicy.toolNames(AgentTier.ASSISTED, webSearchConfigured = true)
        assertTrue(tools.size <= TierPolicy.ASSISTED_MAX_TOOLS)
        assertTrue(tools.contains(TierPolicy.TOOL_MEMORY))
        assertTrue(tools.contains(TierPolicy.TOOL_WEB_SEARCH))
        assertFalse(tools.contains(TierPolicy.TOOL_SKILL_MANAGE))
    }

    @Test
    fun `unconfigured web search is not offered — an unusable tool only costs context`() {
        val tools = TierPolicy.toolNames(AgentTier.ASSISTED, webSearchConfigured = false)
        assertFalse(tools.contains(TierPolicy.TOOL_WEB_SEARCH))
        assertTrue(tools.contains(TierPolicy.TOOL_MEMORY))
    }

    @Test
    fun `deep adds skills and session recall, still within budget`() {
        val tools = TierPolicy.toolNames(AgentTier.DEEP, webSearchConfigured = true)
        assertTrue(tools.size <= TierPolicy.DEEP_MAX_TOOLS)
        assertTrue(tools.contains(TierPolicy.TOOL_SESSION_VIEW))
        assertTrue(tools.contains(TierPolicy.TOOL_SKILL_VIEW))
        assertTrue(tools.contains(TierPolicy.TOOL_MEMORY))
    }

    @Test
    fun `deep carries the file tool alongside web search`() {
        // The budget raise in issue #54: before it, six slots could not hold the
        // five local tools plus `files` plus web search.
        val tools = TierPolicy.toolNames(AgentTier.DEEP, webSearchConfigured = true)
        assertTrue(tools.contains(TierPolicy.TOOL_FILES))
        assertTrue(tools.contains(TierPolicy.TOOL_WEB_SEARCH))
        assertTrue(tools.contains(TierPolicy.TOOL_SKILL_MANAGE))
    }

    @Test
    fun `assisted does not carry the file tool`() {
        val tools = TierPolicy.toolNames(AgentTier.ASSISTED, webSearchConfigured = true)
        assertFalse(tools.contains(TierPolicy.TOOL_FILES))
        assertEquals(TierPolicy.ASSISTED_MAX_TOOLS, 3)
    }

    @Test
    fun `remote backends get a wider budget than on-device deep`() {
        assertTrue(TierPolicy.maxTools(AgentTier.DEEP, isRemoteBackend = true) >=
            TierPolicy.maxTools(AgentTier.DEEP, isRemoteBackend = false))
        val remote = TierPolicy.toolNames(AgentTier.DEEP, webSearchConfigured = true, isRemoteBackend = true)
        assertTrue(remote.contains(TierPolicy.TOOL_FILES))
    }

    @Test
    fun `memory always comes first so it survives any truncation`() {
        listOf(AgentTier.ASSISTED, AgentTier.DEEP).forEach { tier ->
            listOf(true, false).forEach { web ->
                assertEquals(TierPolicy.TOOL_MEMORY, TierPolicy.toolNames(tier, web).first())
            }
        }
    }
}
