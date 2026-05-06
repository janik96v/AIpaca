package com.lamaphone.app.model

import kotlinx.serialization.Serializable

@Serializable
enum class Role { USER, ASSISTANT, SYSTEM }

@Serializable
data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: Role,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)
