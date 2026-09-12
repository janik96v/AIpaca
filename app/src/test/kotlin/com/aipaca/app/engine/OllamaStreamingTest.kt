package com.aipaca.app.engine

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Streaming behaviour of [OllamaEngine] against a deliberately slow SSE server.
 *
 * The regression this guards: `httpClient.post(...)` only returns once Ktor has
 * buffered the entire response body, so `bodyAsChannel()` replayed an already
 * finished stream and every token surfaced in one burst at the end. The fix is
 * `preparePost { execute { … } }`, and the only way to tell the two apart is to
 * time when each chunk arrives relative to the server writing it.
 */
class OllamaStreamingTest {

    private lateinit var server: ServerSocket
    private lateinit var acceptor: Thread
    private lateinit var engine: OllamaEngine

    /** Milliseconds the fake server waits between reasoning deltas. */
    private val gapMs = 300L
    private val deltas = 5

    /**
     * Minimal chunked SSE server. Written on raw sockets because
     * `com.sun.net.httpserver` is not on the Android unit-test classpath.
     */
    private fun serve(socket: Socket) = socket.use { client ->
        val input = client.getInputStream().bufferedReader()
        // The request body must be drained, not just the head: closing a socket
        // with unread bytes still buffered makes the peer see a TCP reset rather
        // than a clean close, which surfaced as a flaky `Connection reset` on the
        // agent test, whose tool schemas make for a much larger request.
        var contentLength = 0
        while (true) {
            val line = input.readLine() ?: return@use
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }
        if (contentLength > 0) {
            val body = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(body, read, contentLength - read)
                if (n < 0) break
                read += n
            }
        }
        val out = client.getOutputStream()
        out.write(
            (
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/event-stream\r\n" +
                    "Transfer-Encoding: chunked\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray()
        )
        out.flush()

        fun chunk(payload: String) {
            val bytes = payload.toByteArray()
            out.write(bytes.size.toString(16).toByteArray())
            out.write("\r\n".toByteArray())
            out.write(bytes)
            out.write("\r\n".toByteArray())
            out.flush()
        }

        repeat(deltas) { i ->
            chunk("""data: {"choices":[{"delta":{"reasoning":"r$i "}}]}""" + "\n\n")
            Thread.sleep(gapMs)
        }
        chunk("""data: {"choices":[{"delta":{"content":"answer"},"finish_reason":"stop"}]}""" + "\n\n")
        chunk("data: [DONE]\n\n")
        out.write("0\r\n\r\n".toByteArray())
        out.flush()
    }

    @BeforeTest
    fun setUp() {
        server = ServerSocket(0)
        acceptor = thread(isDaemon = true) {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (e: Exception) { return@thread }
                thread(isDaemon = true) { runCatching { serve(client) } }
            }
        }
        engine = OllamaEngine().apply {
            serverUrl = "http://127.0.0.1:${server.localPort}"
            modelName = "test-model"
        }
    }

    @AfterTest
    fun tearDown() {
        engine.shutdown()
        server.close()
    }

    @Test
    fun `chat chunks arrive incrementally, not batched at the end`() = runBlocking {
        val start = System.currentTimeMillis()
        val arrivals = mutableListOf<Long>()
        val thinking = StringBuilder()
        val content = StringBuilder()

        withTimeout(30_000) {
            engine.generateChat(
                listOf(ChatTurn("user", "hi")),
                GenerateParams(thinkingEnabled = true)
            ).collect { chunk ->
                arrivals += System.currentTimeMillis() - start
                thinking.append(chunk.thinking)
                content.append(chunk.content)
            }
        }

        assertEquals("r0 r1 r2 r3 r4 ", thinking.toString())
        assertEquals("answer", content.toString())

        // The decisive assertion: the first chunk must land before the server has
        // finished writing. A buffered body delivers everything only after all
        // `deltas * gapMs` of server-side sleeping, so the gap between the two
        // behaviours is the full sleep budget — the threshold below leaves room
        // for JIT and HTTP-client startup without weakening that distinction.
        val firstArrival = arrivals.first()
        assertTrue(
            firstArrival < gapMs * (deltas - 1),
            "first chunk arrived after ${firstArrival}ms — response was buffered, not streamed"
        )
        // And chunks must be spread out rather than all landing together.
        val span = arrivals.last() - arrivals.first()
        assertTrue(span > gapMs, "all chunks arrived within ${span}ms — not incremental")
    }

    @Test
    fun `agent chunks arrive incrementally too`() = runBlocking {
        val start = System.currentTimeMillis()
        var firstThinkingAt = -1L
        val thinking = StringBuilder()

        withTimeout(30_000) {
            engine.generateAgent(
                emptyList(),
                emptyList(),
                GenerateParams(thinkingEnabled = true)
            ).collect { chunk ->
                if (chunk.thinking.isNotEmpty()) {
                    if (firstThinkingAt < 0) firstThinkingAt = System.currentTimeMillis() - start
                    thinking.append(chunk.thinking)
                }
            }
        }

        assertEquals("r0 r1 r2 r3 r4 ", thinking.toString())
        assertTrue(
            firstThinkingAt in 0 until gapMs * (deltas - 1),
            "first thinking chunk arrived after ${firstThinkingAt}ms — response was buffered"
        )
    }
}
