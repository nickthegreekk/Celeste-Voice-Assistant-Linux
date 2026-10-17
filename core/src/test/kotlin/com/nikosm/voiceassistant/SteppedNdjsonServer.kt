package com.nikosm.voiceassistant

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Loopback Direct-Ollama stub that serves MANY connections and lets the test drive each one
 * step by step (ported from upstream's instrumented test in M7).
 *
 * It answers `POST /api/chat` with an EOF-delimited (`Connection: close`, no Content-Length)
 * NDJSON body — the shape the production client streams — and then keeps the response open
 * until the test asks for the next chunk. That makes "turn A's next chunk arrives only after
 * turn B owns a placeholder" something the test SCHEDULES rather than something it hopes to
 * catch, which is what turns a timing race into deterministic evidence.
 */
internal class SteppedNdjsonServer : AutoCloseable {

    private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val connections = CopyOnWriteArrayList<SteppedConnection>()

    /** Base URL accepted by the Ollama client (`http://host:port`). */
    val url: String get() = "http://127.0.0.1:${socket.localPort}"

    init {
        thread(isDaemon = true, name = "h3-stepped-ndjson-accept") {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    // Socket closed by close() (or a failed accept): the loop exits on the
                    // isClosed check. A test stub has nothing to report here.
                    break
                }
                val connection = SteppedConnection(client)
                connections.add(connection)
                // One thread per connection: each response stays open (blocked reading for
                // the client's hangup) while the accept loop keeps listening for the next turn.
                thread(isDaemon = true, name = "h3-stepped-ndjson-conn") { connection.serve() }
            }
        }
    }

    /** The [index]-th (0-based) accepted connection, waiting up to [timeoutMs] for it. */
    fun connection(index: Int, timeoutMs: Long = 30_000): SteppedConnection {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            connections.getOrNull(index)?.let { return it }
            Thread.sleep(20)
        }
        throw AssertionError(
            "the stub never accepted connection #$index (it accepted ${connections.size})"
        )
    }

    override fun close() {
        connections.forEach { it.close() }
        runCatching { socket.close() }
    }
}

/**
 * One accepted connection. The test advances the response with [sendChunk]/[finishStream] and
 * learns that the client stopped reading from [clientDisconnected] — which is exactly how a
 * cancelled or a finished turn manifests on the wire.
 */
internal class SteppedConnection(private val client: Socket) {

    val requestArrived = CountDownLatch(1)
    val clientDisconnected = CountDownLatch(1)

    private val responseReady = CountDownLatch(1)

    @Volatile
    private var output: OutputStream? = null

    fun serve() {
        try {
            val input = BufferedInputStream(client.getInputStream())
            drainRequest(input)
            val out = client.getOutputStream()
            output = out
            out.write(
                (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/x-ndjson\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray()
            )
            out.flush()
            responseReady.countDown()
            requestArrived.countDown()
            // Block until the client hangs up. `read` returns -1 (or throws) once it closes
            // the connection, so this also serializes the test's "A's chunk was read" evidence.
            while (input.read() != -1) {
                // Body bytes are written by the test thread, never by the client: nothing to do.
            }
        } catch (_: Exception) {
            // A closed/reset socket is the normal end of a cancelled or finished turn.
        } finally {
            clientDisconnected.countDown()
            responseReady.countDown()
            requestArrived.countDown()
            runCatching { client.close() }
        }
    }

    /** Streams one content chunk, exactly like a real partial NDJSON line. */
    fun sendChunk(text: String) = writeLine(
        """{"model":"h3-stale-stream-model","created_at":"2026-01-01T00:00:00Z",""" +
            """"message":{"role":"assistant","content":"$text"},"done":false}"""
    )

    /** Ends the NDJSON stream the way the real server does (`done:true`). */
    fun finishStream() = writeLine(
        """{"model":"h3-stale-stream-model","created_at":"2026-01-01T00:00:01Z",""" +
            """"message":{"role":"assistant","content":""},"done":true,"done_reason":"stop"}"""
    )

    fun close() {
        runCatching { client.close() }
    }

    private fun writeLine(line: String) {
        if (!responseReady.await(20, TimeUnit.SECONDS)) {
            throw AssertionError("the stub's response headers were never written")
        }
        val out = output ?: throw AssertionError("the stub's response stream is not open")
        out.write((line + "\n").toByteArray())
        out.flush()
    }

    private fun drainRequest(input: InputStream) {
        val headers = StringBuilder()
        while (true) {
            val line = readAsciiLine(input) ?: return
            if (line.isEmpty()) break
            headers.append(line).append('\n')
        }
        val contentLength = headers.toString().lineSequence()
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        // Drain the request body: leaving it unread makes the client see a reset connection
        // instead of a response.
        var remaining = contentLength
        val buffer = ByteArray(8_192)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (read <= 0) break
            remaining -= read
        }
    }

    private fun readAsciiLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (sb.isEmpty()) null else sb.toString()
            if (byte == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(byte.toChar())
        }
    }
}
