package com.aipaca.app.data

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.aipaca.app.model.StoredConversation
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private const val TAG = "ChatConversationStore"
private const val PREFS_NAME = "chat_conversations"
private const val KEY_CONVERSATIONS = "conversations_json"

class ChatConversationStore(context: Context) {
    // Conversation history may contain sensitive content — store encrypted.
    private val prefs = run {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val serializer = ListSerializer(StoredConversation.serializer())

    fun loadConversations(): List<StoredConversation> {
        val rawJson = prefs.getString(KEY_CONVERSATIONS, null) ?: return emptyList()
        return try {
            json.decodeFromString(serializer, rawJson)
                .sortedByDescending { it.updatedAt }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read stored conversations", e)
            emptyList()
        }
    }

    fun upsert(conversation: StoredConversation): List<StoredConversation> {
        val conversations = loadConversations()
            .filterNot { it.id == conversation.id } + conversation
        return save(conversations)
    }

    fun delete(id: String): List<StoredConversation> {
        return save(loadConversations().filterNot { it.id == id })
    }

    private fun save(conversations: List<StoredConversation>): List<StoredConversation> {
        val sortedConversations = conversations.sortedByDescending { it.updatedAt }
        prefs.edit()
            .putString(KEY_CONVERSATIONS, json.encodeToString(serializer, sortedConversations))
            .apply()
        return sortedConversations
    }
}
