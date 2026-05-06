package com.lamaphone.app.server.security

import android.util.Log
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlin.math.abs

private const val TAG = "Ed25519Verifier"

// Maximum allowed clock skew between client and server (seconds)
private const val TIMESTAMP_WINDOW_SEC = 30L

// DER prefix for a raw 32-byte Ed25519 public key (SubjectPublicKeyInfo wrapper)
// OID 1.3.101.112 (id-EdDSA / Ed25519)
private val ED25519_DER_PREFIX = byteArrayOf(
    0x30, 0x2A, 0x30, 0x05, 0x06, 0x03, 0x2B, 0x65, 0x70, 0x05, 0x00, 0x03, 0x21, 0x00
)

/**
 * Verifies LamaPhone-Ed25519 Authorization headers.
 *
 * Header format:
 *   Authorization: LamaPhone-Ed25519 <base64-raw-pubkey> <base64-signature> <unix-timestamp>
 *
 * Signed message: "LamaPhone-Ed25519:<unixTimestampSeconds>"
 */
object Ed25519Verifier {

    sealed class Result {
        data class Valid(val publicKeyBase64: String) : Result()
        data class Invalid(val reason: String) : Result()
    }

    fun verify(authHeader: String): Result {
        val parts = authHeader.trim().split(" ")
        if (parts.size != 4 || parts[0] != "LamaPhone-Ed25519") {
            return Result.Invalid("Malformed Authorization header; expected: LamaPhone-Ed25519 <pubkey> <sig> <timestamp>")
        }

        val pubKeyB64 = parts[1]
        val sigB64    = parts[2]
        val tsStr     = parts[3]

        // Validate timestamp is recent
        val timestamp = tsStr.toLongOrNull()
            ?: return Result.Invalid("Non-numeric timestamp")
        val now = System.currentTimeMillis() / 1000L
        if (abs(now - timestamp) > TIMESTAMP_WINDOW_SEC) {
            return Result.Invalid("Timestamp outside ±${TIMESTAMP_WINDOW_SEC}s window (skew: ${now - timestamp}s)")
        }

        // Decode public key: raw 32 bytes → prepend DER header for X509EncodedKeySpec
        val rawPubKeyBytes = try {
            Base64.getDecoder().decode(pubKeyB64)
        } catch (e: Exception) {
            return Result.Invalid("Invalid base64 public key")
        }
        if (rawPubKeyBytes.size != 32) {
            return Result.Invalid("Ed25519 public key must be 32 bytes, got ${rawPubKeyBytes.size}")
        }
        val derEncodedKey = ED25519_DER_PREFIX + rawPubKeyBytes

        val publicKey = try {
            KeyFactory.getInstance("Ed25519")
                .generatePublic(X509EncodedKeySpec(derEncodedKey))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode public key: ${e.message}")
            return Result.Invalid("Failed to decode public key: ${e.message}")
        }

        // Decode signature
        val sigBytes = try {
            Base64.getDecoder().decode(sigB64)
        } catch (e: Exception) {
            return Result.Invalid("Invalid base64 signature")
        }

        // Verify signature over "LamaPhone-Ed25519:<timestamp>"
        val message = "LamaPhone-Ed25519:$tsStr".toByteArray(Charsets.UTF_8)
        val valid = try {
            val sig = Signature.getInstance("Ed25519")
            sig.initVerify(publicKey)
            sig.update(message)
            sig.verify(sigBytes)
        } catch (e: Exception) {
            Log.w(TAG, "Signature verification exception: ${e.message}")
            return Result.Invalid("Signature verification failed: ${e.message}")
        }

        return if (valid) Result.Valid(pubKeyB64) else Result.Invalid("Invalid signature")
    }
}
