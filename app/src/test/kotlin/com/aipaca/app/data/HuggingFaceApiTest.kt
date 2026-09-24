package com.aipaca.app.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [HuggingFaceApi] against a [MockEngine] — no real network calls.
 * The sample JSON mirrors a real response from
 * `https://huggingface.co/api/models/{repoId}/tree/main` (non-model files, LFS-backed
 * GGUF files with a `lfs.size`, and a couple of files that must be filtered out).
 */
class HuggingFaceApiTest {

    private val repoId = "TheBloke/Llama-2-7B-Chat-GGUF"

    private val sampleTreeJson = """
        [
          {"type":"file","oid":"cf16200f21d2b7cdd4799a3b1b9cac8a928834cb","size":2277,"path":".gitattributes"},
          {"type":"file","oid":"7c2d7e73fa3b31f4937873fcc27159df7e508b73","size":27501,"path":"README.md"},
          {"type":"file","oid":"a4ba21b7cb475b3ebf33292c8eda7067b98f92a4","size":29,"path":"config.json"},
          {"type":"file","oid":"1a51dee18d79e82a14574214c505cd004fc010de","size":2825940672,"lfs":{"oid":"c0dd304d761e8e05d082cc2902d7624a7f87858fdfaa4ef098330ffe767ff0d3","size":2825940672,"pointerSize":135},"path":"llama-2-7b-chat.Q2_K.gguf"},
          {"type":"file","oid":"9e1fc06ff5d6ca3b37ba9a329d150debf2b6acd4","size":4081004224,"lfs":{"oid":"08a5566d61d7cb6b420c3e4387a39e0078e1f2fe5f055f3a03887385304d4bfa","size":4081004224,"pointerSize":135},"path":"llama-2-7b-chat.Q4_K_M.gguf"},
          {"type":"file","oid":"aabbccdd0011223344556677889900aabbccdd0","size":75000000,"path":"ggml-model-tiny.bin"},
          {"type":"file","oid":"ff11223344556677889900aabbccddee00112233","size":560000000,"lfs":{"oid":"ee00112233445566778899aabbccddee00112233","size":560000000,"pointerSize":135},"path":"mmproj-F16.gguf"},
          {"type":"file","oid":"ff22334455667788990011aabbccddee00223344","size":1120000000,"lfs":{"oid":"dd00112233445566778899aabbccddee00223344","size":1120000000,"pointerSize":135},"path":"mmproj-F32.gguf"},
          {"type":"directory","oid":"deadbeefdeadbeefdeadbeefdeadbeefdeadbeef","size":0,"path":"subfolder"}
        ]
    """.trimIndent()

    private fun mockClient(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpClient {
        val engine = MockEngine { _ ->
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        return HttpClient(engine)
    }

    @Test
    fun `listModelFiles returns only gguf and bin entries with sizes and resolve urls`() = runTest {
        val api = HuggingFaceApi(httpClient = mockClient(sampleTreeJson))

        val files = api.listModelFiles(repoId)

        assertEquals(3, files.size)

        val q2k = files.single { it.name == "llama-2-7b-chat.Q2_K.gguf" }
        assertEquals(2825940672L, q2k.sizeBytes)
        assertEquals(
            "https://huggingface.co/TheBloke/Llama-2-7B-Chat-GGUF/resolve/main/llama-2-7b-chat.Q2_K.gguf",
            q2k.downloadUrl
        )

        val q4km = files.single { it.name == "llama-2-7b-chat.Q4_K_M.gguf" }
        assertEquals(4081004224L, q4km.sizeBytes)
        assertEquals(
            "https://huggingface.co/TheBloke/Llama-2-7B-Chat-GGUF/resolve/main/llama-2-7b-chat.Q4_K_M.gguf",
            q4km.downloadUrl
        )

        val binFile = files.single { it.name == "ggml-model-tiny.bin" }
        assertEquals(75_000_000L, binFile.sizeBytes)
        assertEquals(
            "https://huggingface.co/TheBloke/Llama-2-7B-Chat-GGUF/resolve/main/ggml-model-tiny.bin",
            binFile.downloadUrl
        )
    }

    @Test
    fun `listModelFiles excludes non-model files and directories`() = runTest {
        val api = HuggingFaceApi(httpClient = mockClient(sampleTreeJson))

        val files = api.listModelFiles(repoId)

        assertTrue(files.none { it.name == "README.md" })
        assertTrue(files.none { it.name == "config.json" })
        assertTrue(files.none { it.name == ".gitattributes" })
        assertTrue(files.none { it.name == "subfolder" })
    }

    @Test
    fun `listModelFiles excludes mmproj files`() = runTest {
        val api = HuggingFaceApi(httpClient = mockClient(sampleTreeJson))

        val files = api.listModelFiles(repoId)

        assertTrue(files.none { it.name.contains("mmproj") })
    }

    @Test
    fun `listMmprojFiles returns only mmproj gguf entries`() = runTest {
        val api = HuggingFaceApi(httpClient = mockClient(sampleTreeJson))

        val files = api.listMmprojFiles(repoId)

        assertEquals(2, files.size)

        val f16 = files.single { it.name == "mmproj-F16.gguf" }
        assertEquals(560_000_000L, f16.sizeBytes)
        assertEquals(
            "https://huggingface.co/TheBloke/Llama-2-7B-Chat-GGUF/resolve/main/mmproj-F16.gguf",
            f16.downloadUrl
        )

        val f32 = files.single { it.name == "mmproj-F32.gguf" }
        assertEquals(1_120_000_000L, f32.sizeBytes)
    }

    @Test
    fun `listMmprojFiles returns empty list when no mmproj files exist`() = runTest {
        val json = """[
            {"type":"file","oid":"x","size":100000,"path":"model-Q4_0.gguf"}
        ]"""
        val api = HuggingFaceApi(httpClient = mockClient(json))

        val files = api.listMmprojFiles(repoId)

        assertTrue(files.isEmpty())
    }

    @Test
    fun `isMmprojFile matches mmproj gguf files case-insensitively`() {
        assertTrue(HuggingFaceApi.isMmprojFile("mmproj-F16.gguf"))
        assertTrue(HuggingFaceApi.isMmprojFile("mmproj-BF16.gguf"))
        assertTrue(HuggingFaceApi.isMmprojFile("MMPROJ-F32.gguf"))
        assertTrue(HuggingFaceApi.isMmprojFile("model-mmproj-f16.gguf"))
        assertFalse(HuggingFaceApi.isMmprojFile("mmproj-F16.bin"))
        assertFalse(HuggingFaceApi.isMmprojFile("model-Q4_K_M.gguf"))
        assertFalse(HuggingFaceApi.isMmprojFile("README.md"))
    }

    @Test
    fun `listModelFiles returns empty list for repo with no model files`() = runTest {
        val json = """[{"type":"file","oid":"x","size":10,"path":"README.md"}]"""
        val api = HuggingFaceApi(httpClient = mockClient(json))

        val files = api.listModelFiles(repoId)

        assertTrue(files.isEmpty())
    }

    @Test
    fun `non-2xx http response raises HuggingFaceApiException`() = runTest {
        val api = HuggingFaceApi(httpClient = mockClient("Not Found", HttpStatusCode.NotFound))

        assertFailsWith<HuggingFaceApiException> {
            api.listModelFiles(repoId)
        }
    }

    @Test
    fun `malformed json raises HuggingFaceApiException`() = runTest {
        val api = HuggingFaceApi(httpClient = mockClient("not json"))

        assertFailsWith<HuggingFaceApiException> {
            api.listModelFiles(repoId)
        }
    }

    @Test
    fun `resolveUrl builds the direct download url`() {
        val url = HuggingFaceApi.resolveUrl("org/repo", "model.Q4_K_M.gguf")

        assertEquals("https://huggingface.co/org/repo/resolve/main/model.Q4_K_M.gguf", url)
    }

    @Test
    fun `searchModels asks for gguf repos by downloads and parses the listing`() = runTest {
        var requested: io.ktor.http.Url? = null
        val engine = MockEngine { request ->
            requested = request.url
            respond(
                content = """
                    [
                      {"_id":"x","id":"unsloth/Qwen3.5-4B-GGUF","downloads":81234,"likes":120,"pipeline_tag":"image-text-to-text","tags":["gguf"]},
                      {"_id":"y","id":"ggerganov/whisper.cpp","downloads":5000,"likes":900}
                    ]
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val api = HuggingFaceApi(httpClient = HttpClient(engine))

        val models = api.searchModels(" qwen ")

        assertEquals(listOf("unsloth/Qwen3.5-4B-GGUF", "ggerganov/whisper.cpp"), models.map { it.id })
        assertEquals(81234L, models[0].downloads)
        assertEquals("image-text-to-text", models[0].pipelineTag)
        assertEquals(null, models[1].pipelineTag)
        val url = requested!!
        assertEquals("/api/models", url.encodedPath)
        assertEquals("qwen", url.parameters["search"])
        assertEquals("gguf", url.parameters["filter"])
        assertEquals("downloads", url.parameters["sort"])
    }

    @Test
    fun `searchModels surfaces http errors`() = runTest {
        val api = HuggingFaceApi(httpClient = mockClient("oops", HttpStatusCode.InternalServerError))
        assertFailsWith<HuggingFaceApiException> { api.searchModels("qwen") }
    }
}
