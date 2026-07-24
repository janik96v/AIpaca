package com.aipaca.app.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Thrown for Hugging Face Hub API failures (transport errors, non-2xx responses). */
class HuggingFaceApiException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * A single downloadable file resolved from a Hugging Face repo's model tree —
 * filtered to the quantised model formats AIpaca can actually load (GGUF for
 * llama.cpp, BIN for whisper.cpp).
 *
 * @param name         File name as it appears in the repo, e.g. `model.Q4_K_M.gguf`.
 * @param sizeBytes    File size in bytes (from the tree listing / LFS pointer).
 * @param downloadUrl  Direct resolve URL AIpaca can stream the bytes from.
 */
data class HfFile(
    val name: String,
    val sizeBytes: Long,
    val downloadUrl: String
)

/** One raw entry as returned by the Hugging Face `tree/main` endpoint. */
@Serializable
private data class HfTreeEntry(
    val type: String,
    val path: String,
    val size: Long = 0,
    val lfs: HfLfsInfo? = null
)

/** LFS metadata attached to large tracked files — the authoritative size when present. */
@Serializable
private data class HfLfsInfo(
    @SerialName("size") val size: Long = 0
)

/**
 * Client for the Hugging Face Hub's repo-tree API (Kotlin/Ktor CIO client, mirrors the
 * hand-rolled `HttpMcpClient` pattern used elsewhere in this repo for outbound HTTP).
 *
 * Hand-rolled on the existing Ktor-2.3.12 CIO client + kotlinx.serialization stack, no new
 * dependencies. Only the two things AIpaca's model-download flow needs are modelled: listing
 * the GGUF/BIN files of a repo (with size) and building the resolve/download URL for one.
 *
 * @param httpClient Injectable Ktor client — production code relies on the CIO default;
 *                    tests inject a `MockEngine`-backed client to simulate server responses.
 */
class HuggingFaceApi(
    private val httpClient: HttpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
        }
    }
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Fetches the file tree for [repoId] (e.g. `TheBloke/Llama-2-7B-Chat-GGUF`) and returns
     * only the GGUF and BIN files, each with its size and ready-to-download resolve URL.
     *
     * @throws HuggingFaceApiException on transport errors or a non-2xx HTTP response.
     */
    suspend fun listModelFiles(repoId: String): List<HfFile> {
        val url = treeUrl(repoId)
        val response: HttpResponse = try {
            httpClient.get(url)
        } catch (e: Exception) {
            throw HuggingFaceApiException("Failed to reach Hugging Face for repo=$repoId", e)
        }
        if (!response.status.isSuccess()) {
            throw HuggingFaceApiException("Hugging Face returned HTTP ${response.status} for repo=$repoId")
        }
        val raw = response.bodyAsText()
        val entries = try {
            json.decodeFromString(ListSerializer(HfTreeEntry.serializer()), raw)
        } catch (e: Exception) {
            throw HuggingFaceApiException("Failed to parse Hugging Face tree response for repo=$repoId", e)
        }
        return entries
            .asSequence()
            .filter { it.type == "file" && isModelFile(it.path) }
            .map { entry ->
                HfFile(
                    name = entry.path,
                    sizeBytes = entry.lfs?.size?.takeIf { it > 0 } ?: entry.size,
                    downloadUrl = resolveUrl(repoId, entry.path)
                )
            }
            .toList()
    }

    /** Releases the underlying HTTP client resources (connection pool). */
    fun close() {
        httpClient.close()
    }

    companion object {
        private val MODEL_EXTENSIONS = setOf("gguf", "bin")

        private fun isModelFile(path: String): Boolean =
            MODEL_EXTENSIONS.contains(path.substringAfterLast('.', "").lowercase())

        private fun treeUrl(repoId: String): String =
            "https://huggingface.co/api/models/$repoId/tree/main"

        /** Builds the direct download URL for [fileName] inside [repoId]'s `main` branch. */
        fun resolveUrl(repoId: String, fileName: String): String =
            "https://huggingface.co/$repoId/resolve/main/$fileName"
    }
}
