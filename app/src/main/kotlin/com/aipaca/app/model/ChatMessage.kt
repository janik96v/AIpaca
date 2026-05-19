package com.aipaca.app.model

import kotlinx.serialization.Serializable

@Serializable
enum class Role { USER, ASSISTANT, SYSTEM }

@Serializable
data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: Role,
    val content: String,
    val thinkingContent: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val attachedImageUri: String? = null,
    val attachedDocumentName: String? = null,
    // Shown in the bubble; null means fall back to content (normal case).
    // Used to separate the user's typed text from document text injected for the model.
    val displayText: String? = null
)
