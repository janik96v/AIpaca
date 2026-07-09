package com.aipaca.app.agent

import com.aipaca.app.data.ChatConversationStore
import com.aipaca.app.engine.ChatTurn
import com.aipaca.app.model.ChatMessage
import com.aipaca.app.model.Role
import com.aipaca.app.model.StoredConversation
import java.util.UUID

/**
 * Narrow view of [ChatConversationStore] that [AgentSession] depends on. Lets tests supply
 * a plain in-memory fake instead of the real EncryptedSharedPreferences-backed store, which
 * requires an Android [android.content.Context] and can't run in a JVM unit test.
 */
interface ChatConversationStoreLike {
    fun loadConversations(): List<StoredConversation>
    fun upsert(conversation: StoredConversation): List<StoredConversation>
}

/** Adapts the real [ChatConversationStore] to [ChatConversationStoreLike] for production use. */
fun ChatConversationStore.asSessionStore(): ChatConversationStoreLike = object : ChatConversationStoreLike {
    override fun loadConversations(): List<StoredConversation> = this@asSessionStore.loadConversations()
    override fun upsert(conversation: StoredConversation): List<StoredConversation> = this@asSessionStore.upsert(conversation)
}

/**
 * Session model for the agent, built on the same [ChatConversationStore] the chat UI
 * already uses (spec_issue_43_agent_mode.md §5/§6.5 — "Sessions" mapped onto
 * `ChatConversationStore`, mirroring OpenClaw's session list/history).
 *
 * Kept intentionally thin for the MVP: a session *is* a [StoredConversation]; this class
 * only adds the agent-specific view (turning stored messages into [ChatTurn]s for
 * [AgentLoop.run] and appending new turns back). The dedicated Agent UI/tab (Brick 3) is
 * explicitly left as a follow-up product decision (§8 "noch offen").
 */
class AgentSession(
    private val store: ChatConversationStoreLike,
    val id: String = UUID.randomUUID().toString()
) {

    /** Loads this session's prior turns as [ChatTurn]s (system prompt excluded — AgentConfig owns that). */
    fun loadHistory(): List<ChatTurn> =
        store.loadConversations()
            .firstOrNull { it.id == id }
            ?.messages
            ?.map { msg -> ChatTurn(role = msg.role.wireRole(), content = msg.content) }
            ?: emptyList()

    /**
     * Persists a completed agent turn (user goal + resulting steps) into the session's
     * stored conversation, creating it if this is the first turn.
     */
    fun appendTurn(userGoal: String, steps: List<AgentStep>) {
        val existing = store.loadConversations().firstOrNull { it.id == id }
        val priorMessages = existing?.messages ?: emptyList()

        val newMessages = buildList {
            add(ChatMessage(role = Role.USER, content = userGoal))
            steps.forEach { step ->
                when (step) {
                    is AgentStep.FinalAnswer -> add(ChatMessage(role = Role.ASSISTANT, content = step.text))
                    is AgentStep.Error -> add(ChatMessage(role = Role.ASSISTANT, content = "[error] ${step.message}"))
                    // Thinking / ToolCall / ToolObservation are intermediate agent telemetry,
                    // not conversational turns — not persisted as chat bubbles.
                    is AgentStep.Thinking, is AgentStep.ToolCall, is AgentStep.ToolObservation -> Unit
                }
            }
        }

        val updated = StoredConversation(
            id = id,
            title = existing?.title ?: userGoal.take(48),
            messages = priorMessages + newMessages,
            updatedAt = System.currentTimeMillis(),
            systemPrompt = existing?.systemPrompt ?: ""
        )
        store.upsert(updated)
    }

    private fun Role.wireRole(): String = when (this) {
        Role.USER -> "user"
        Role.ASSISTANT -> "assistant"
        Role.SYSTEM -> "system"
    }
}

