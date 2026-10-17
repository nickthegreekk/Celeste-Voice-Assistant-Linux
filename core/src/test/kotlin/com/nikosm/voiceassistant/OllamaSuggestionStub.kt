package com.nikosm.voiceassistant

import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/** One recorded request: the target path, lowercased header names, raw body. */
internal data class RecordedSuggestionRequest(
    val path: String,
    val headers: Map<String, String>,
    val body: String
)

/**
 * Minimal loopback Ollama stub: answers POST /api/chat with a 3-line non-streaming suggestion
 * payload (`message.content`), records every request's path/headers/body (ported from upstream's
 * instrumented fixture in M7).
 *
 * Anything else gets a 404, and an unauthorized `/api/chat` gets a 401 — if the application
 * routes here without the configured header, the failure is loud, not silent.
 */
internal class OllamaSuggestionStub(
    /**
     * The `message.content` answered for an authorized POST /api/chat. Plain ASCII only — the
     * value is spliced into a JSON string literal verbatim. Defaults to the multi-line payload
     * the suggested-replies test was written against; callers exercising other paths pass their
     * own.
     */
    val chatContent: String = "Yes, quiz me on Paris\\nWhat else is nearby?\\nThanks, that's clear",
    private val username: String = "probe-user",
    private val password: String = "probe-pass",
) : AutoCloseable {
    private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))

    val url: String = "http://127.0.0.1:${socket.localPort}"
    val requests = CopyOnWriteArrayList<RecordedSuggestionRequest>()

    init {
        val port = socket.localPort
        Thread({
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    break
                }
                Thread({ handle(client) }, "ollama-suggestion-stub-$port").apply { isDaemon = true }.start()
            }
        }, "ollama-suggestion-stub-accept-$port").apply { isDaemon = true }.start()
    }

    override fun close() {
        runCatching { socket.close() }
    }

    private fun handle(connection: Socket) {
        try {
            connection.use { client ->
                val input = BufferedInputStream(client.getInputStream())
                val requestLine = readStubLine(input) ?: return
                val path = requestLine.split(" ").getOrNull(1) ?: "/"
                val headers = mutableMapOf<String, String>()
                var contentLength = 0
                while (true) {
                    val line = readStubLine(input) ?: return
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        val name = line.substring(0, idx).trim().lowercase()
                        val value = line.substring(idx + 1).trim()
                        headers[name] = value
                        if (name == "content-length") contentLength = value.toIntOrNull() ?: 0
                    }
                }
                val bodyBytes = ByteArray(contentLength.coerceAtLeast(0))
                var read = 0
                while (read < bodyBytes.size) {
                    val n = input.read(bodyBytes, read, bodyBytes.size - read)
                    if (n <= 0) break
                    read += n
                }
                requests += RecordedSuggestionRequest(path, headers, String(bodyBytes, Charsets.UTF_8))

                val expected = "Basic " + Base64.getEncoder()
                    .encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
                val authorized = headers["authorization"] == expected
                val payload = if (path.startsWith("/api/chat")) {
                    if (authorized) {
                        200 to "{\"message\":{\"role\":\"assistant\",\"content\":" +
                            "\"$chatContent\"}}"
                    } else {
                        401 to "{\"error\":\"unauthorized\"}"
                    }
                } else {
                    null
                }
                val output = client.getOutputStream()
                if (payload == null) {
                    output.write(
                        "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            .toByteArray(Charsets.US_ASCII)
                    )
                } else {
                    val (status, body) = payload
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    val reason = if (status == 200) "OK" else "Unauthorized"
                    output.write(
                        (
                            "HTTP/1.1 $status $reason\r\n" +
                                "Content-Type: application/json\r\n" +
                                "Content-Length: ${bytes.size}\r\n" +
                                "Connection: close\r\n\r\n"
                            ).toByteArray(Charsets.US_ASCII)
                    )
                    output.write(bytes)
                }
                output.flush()
            }
        } catch (e: Exception) {
        }
    }

    private fun readStubLine(input: InputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val next = input.read()
            if (next == -1) return if (builder.isEmpty()) null else builder.toString()
            if (next == '\n'.code) return builder.toString().trimEnd('\r')
            builder.append(next.toChar())
        }
    }
}
