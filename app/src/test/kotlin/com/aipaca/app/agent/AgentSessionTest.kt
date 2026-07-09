package com.aipaca.app.agent

import com.aipaca.app.data.ChatConversationStore
import com.aipaca.app.engine.ChatTurn
import com.aipaca.app.model.StoredConversation
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fake in-memory stand-in for [ChatConversationStore] — the real class requires an Android
 * [android.content.Context] / EncryptedSharedPreferences and can't run in a plain JVM unit test.
 * [AgentSession] only calls [loadConversations]/[upsert], so a minimal fake covering that
 * surface is sufficient and keeps this a true unit test.
 */
private class FakeConversationStore : ChatConversationStoreLike {
    private val conversations = mutableListOf<StoredConversation>()

    override fun loadConversations(): List<StoredConversation> = conversations.toList()

    override fun upsert(conversation: StoredConversation): List<StoredConversation> {
        conversations.removeAll { it.id == conversation.id }
        conversations += conversation
        return conversations.toList()
    }
}

class AgentSessionTest {

    @Test
    fun `appendTurn creates a new conversation on first turn`() {
        val store = FakeConversationStore()
        val session = AgentSession(store, id = "session-1")

        session.appendTurn(
            userGoal = "What's new in AIpaca?",
            steps = listOf(AgentStep.FinalAnswer("AIpaca 0.3.0 shipped agent mode."))
        )

        val stored = store.loadConversations().single()
        assertEquals("session-1", stored.id)
        assertEquals(2, stored.messages.size)
        assertEquals("What's new in AIpaca?", stored.messages[0].content)
        assertEquals("AIpaca 0.3.0 shipped agent mode.", stored.messages[1].content)
    }

    @Test
    fun `intermediate agent steps are not persisted as chat bubbles`() {
        val store = FakeConversationStore()
        val session = AgentSession(store, id = "session-2")

        session.appendTurn(
            userGoal = "Search something",
            steps = listOf(
                AgentStep.Thinking("thinking..."),
                AgentStep.ToolCall("tavily_search", JsonObject(emptyMap())),
                AgentStep.ToolObservation("tavily_search", com.aipaca.app.agent.mcp.ToolResult("found it")),
                AgentStep.FinalAnswer("Here's what I found.")
            )
        )

        val stored = store.loadConversations().single()
        // Only the user goal + final answer become chat messages.
        assertEquals(2, stored.messages.size)
    }

    @Test
    fun `appendTurn on existing session appends without losing prior history`() {
        val store = FakeConversationStore()
        val session = AgentSession(store, id = "session-3")

        session.appendTurn("First question", listOf(AgentStep.FinalAnswer("First answer")))
        session.appendTurn("Second question", listOf(AgentStep.FinalAnswer("Second answer")))

        val stored = store.loadConversations().single()
        assertEquals(4, stored.messages.size)
        assertEquals("First question", stored.messages[0].content)
        assertEquals("Second answer", stored.messages[3].content)
    }

    @Test
    fun `loadHistory maps stored messages to ChatTurns`() {
        val store = FakeConversationStore()
        val session = AgentSession(store, id = "session-4")
        session.appendTurn("Hi", listOf(AgentStep.FinalAnswer("Hello!")))

        val history: List<ChatTurn> = session.loadHistory()

        assertEquals(2, history.size)
        assertEquals("user", history[0].role)
        assertEquals("Hi", history[0].content)
        assertEquals("assistant", history[1].role)
        assertEquals("Hello!", history[1].content)
    }

    @Test
    fun `loadHistory on unknown session id returns empty list`() {
        val store = FakeConversationStore()
        val session = AgentSession(store, id = "does-not-exist")

        assertTrue(session.loadHistory().isEmpty())
    }
}
