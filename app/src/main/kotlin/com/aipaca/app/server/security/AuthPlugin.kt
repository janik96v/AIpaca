package com.aipaca.app.server.security

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.util.*
import kotlinx.serialization.Serializable

// Routes that do NOT require authentication
private val PUBLIC_PATHS = setOf("/health", "/v1/pair")

val AIpacaAuth = createApplicationPlugin("AIpacaAuth") {
    onCall { call ->
        val path = call.request.path()
        if (path in PUBLIC_PATHS) return@onCall

        val authorizedKeys = call.application.attributes[AuthorizedKeysAttrKey]

        val authHeader = call.request.header(HttpHeaders.Authorization)
        if (authHeader == null) {
            call.respond(HttpStatusCode.Unauthorized, AuthErrorResponse("Missing Authorization header"))
            return@onCall
        }

        when (val result = Ed25519Verifier.verify(authHeader)) {
            is Ed25519Verifier.Result.Invalid -> {
                call.respond(HttpStatusCode.Unauthorized, AuthErrorResponse(result.reason))
                return@onCall
            }
            is Ed25519Verifier.Result.Valid -> {
                val key = authorizedKeys.findByPublicKey(result.publicKeyBase64)
                if (key == null) {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        AuthErrorResponse("Public key not registered. Pair this device first via /v1/pair.")
                    )
                    return@onCall
                }
                // Authorized — continue to route handler
            }
        }
    }
}

val AuthorizedKeysAttrKey = AttributeKey<AuthorizedKeysStore>("AuthorizedKeysStore")

@Serializable
private data class AuthErrorResponse(val error: String, val type: String = "auth_error")
