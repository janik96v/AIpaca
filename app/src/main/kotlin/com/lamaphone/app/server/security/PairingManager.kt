package com.lamaphone.app.server.security

import android.util.Log
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "PairingManager"
private const val PIN_VALIDITY_MS = 5 * 60 * 1000L   // 5 minutes

object PairingManager {

    data class PairingSession(
        val pin: String,
        val expiresAt: Long
    )

    private val current = AtomicReference<PairingSession?>(null)

    fun generatePin(): String {
        val pin = String.format("%06d", SecureRandom().nextInt(1_000_000))
        current.set(PairingSession(pin = pin, expiresAt = System.currentTimeMillis() + PIN_VALIDITY_MS))
        Log.i(TAG, "Generated pairing PIN (valid for 5 min)")
        return pin
    }

    fun validateAndConsume(pin: String): Boolean {
        val session = current.get() ?: return false
        if (System.currentTimeMillis() > session.expiresAt) {
            current.set(null)
            Log.w(TAG, "Pairing PIN expired")
            return false
        }
        // Constant-time comparison to resist timing attacks
        val match = pin.length == session.pin.length &&
            pin.toByteArray().zip(session.pin.toByteArray()).all { (a, b) -> a == b }
        if (match) {
            current.set(null)   // One-time use: consume on success
            Log.i(TAG, "Pairing PIN accepted")
        } else {
            Log.w(TAG, "Pairing PIN rejected")
        }
        return match
    }

    fun remainingMs(): Long {
        val session = current.get() ?: return 0L
        return maxOf(0L, session.expiresAt - System.currentTimeMillis())
    }

    fun isActive(): Boolean = current.get()?.let { System.currentTimeMillis() <= it.expiresAt } ?: false

    fun cancel() {
        current.set(null)
    }
}
