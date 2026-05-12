package com.aipaca.app.server.security

import android.util.Log
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "PairingManager"
private const val PIN_VALIDITY_MS = 5 * 60 * 1000L   // 5 minutes
private const val MAX_ATTEMPTS = 10   // lock out after 10 failed attempts per session

object PairingManager {

    data class PairingSession(
        val pin: String,
        val expiresAt: Long
    )

    private val current = AtomicReference<PairingSession?>(null)

    /** Remaining failed attempts before the current session is locked out. */
    private val attemptsLeft = AtomicInteger(MAX_ATTEMPTS)

    fun generatePin(): String {
        val pin = String.format("%06d", SecureRandom().nextInt(1_000_000))
        current.set(PairingSession(pin = pin, expiresAt = System.currentTimeMillis() + PIN_VALIDITY_MS))
        attemptsLeft.set(MAX_ATTEMPTS)
        Log.i(TAG, "Generated pairing PIN (valid for 5 min)")
        return pin
    }

    /**
     * Validates the supplied PIN using a constant-time comparison (MessageDigest.isEqual)
     * and enforces a per-session attempt limit to prevent brute-force.
     * Returns true only when the PIN matches and hasn't been consumed.
     */
    fun validateAndConsume(pin: String): Boolean {
        // Check attempt limit before any comparison to fail fast on lockout
        if (attemptsLeft.get() <= 0) {
            Log.w(TAG, "Pairing session locked out after $MAX_ATTEMPTS failed attempts")
            return false
        }

        val session = current.get() ?: return false
        if (System.currentTimeMillis() > session.expiresAt) {
            current.set(null)
            Log.w(TAG, "Pairing PIN expired")
            return false
        }

        // Constant-time comparison using MessageDigest.isEqual (not short-circuit)
        val match = MessageDigest.isEqual(
            pin.toByteArray(Charsets.UTF_8),
            session.pin.toByteArray(Charsets.UTF_8)
        )

        if (match) {
            current.set(null)   // One-time use: consume on success
            attemptsLeft.set(0)
            Log.i(TAG, "Pairing PIN accepted")
        } else {
            val remaining = attemptsLeft.decrementAndGet()
            Log.w(TAG, "Pairing PIN rejected ($remaining attempts remaining)")
            if (remaining <= 0) {
                current.set(null)   // Invalidate session on lockout
                Log.w(TAG, "Pairing session invalidated due to too many failed attempts")
            }
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
        attemptsLeft.set(0)
    }
}
