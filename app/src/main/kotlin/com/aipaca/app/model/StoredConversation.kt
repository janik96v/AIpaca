package com.aipaca.app.model

import kotlinx.serialization.Serializable

@Serializable
data class StoredConversation(
    val id: String,
    val title: String,
    val messages: List<ChatMessage>,
    val updatedAt: Long,
    val systemPrompt: String = ""
)
