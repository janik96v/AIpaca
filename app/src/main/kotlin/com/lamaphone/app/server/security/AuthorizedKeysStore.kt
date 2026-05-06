package com.lamaphone.app.server.security

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.Base64

private const val TAG = "AuthorizedKeysStore"
private const val PREFS_NAME = "lamaphone_authorized_keys"

class AuthorizedKeysStore(context: Context) {

    @Serializable
    data class AuthorizedKey(
        val fingerprint: String,       // SHA-256 of raw public key bytes, hex (no colons)
        val displayName: String,
        val publicKeyBase64: String    // Base64 of raw 32-byte Ed25519 public key
    )

    private val prefs = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.e(TAG, "Failed to create EncryptedSharedPreferences, falling back to plain", e)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun add(key: AuthorizedKey) {
        prefs.edit().putString(key.fingerprint, json.encodeToString(key)).apply()
        Log.i(TAG, "Registered client key: ${key.displayName} (${key.fingerprint.take(16)}...)")
    }

    fun remove(fingerprint: String) {
        prefs.edit().remove(fingerprint).apply()
        Log.i(TAG, "Removed client key: ${fingerprint.take(16)}...")
    }

    fun getAll(): List<AuthorizedKey> {
        return prefs.all.values.mapNotNull { value ->
            try {
                json.decodeFromString<AuthorizedKey>(value as String)
            } catch (e: Exception) {
                null
            }
        }.sortedBy { it.displayName }
    }

    fun findByPublicKey(publicKeyBase64: String): AuthorizedKey? {
        val fingerprint = fingerprintOf(publicKeyBase64) ?: return null
        val stored = prefs.getString(fingerprint, null) ?: return null
        return try {
            json.decodeFromString<AuthorizedKey>(stored)
        } catch (e: Exception) {
            null
        }
    }

    fun isEmpty(): Boolean = prefs.all.isEmpty()

    companion object {
        fun fingerprintOf(publicKeyBase64: String): String? {
            return try {
                val bytes = Base64.getDecoder().decode(publicKeyBase64)
                MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it) }
            } catch (e: Exception) {
                null
            }
        }
    }
}
