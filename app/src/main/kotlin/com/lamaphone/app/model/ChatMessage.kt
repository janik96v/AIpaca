package com.lamaphone.app.model

enum class Role { USER, ASSISTANT, SYSTEM }

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: Role,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)
