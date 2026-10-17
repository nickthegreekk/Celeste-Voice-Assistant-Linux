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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** The reply text the stub answers the [index]-th captured request with. */
internal fun replyText(index: Int, tag: String): String = "reply-$index-for-$tag"

/** One captured provider request, as the stub saw it. */
internal data class RecordedRoomRequest(
    val index: Int,
    val path: String,
    val body: String,
    /** The probe member this request was built for, read back out of its own system prompt. */
    val speakerTag: String,
)

/**
 * Loopback stub that answers BOTH backend shapes the room turn loop uses, and captures every
 * request body (ported from upstream's instrumented test in M7).
 *
 *  - `POST /api/chat` → Direct-Ollama NDJSON, EOF-delimited (`Connection: close`, no
 *    Content-Length), exactly the shape the production client streams.
 *  - `POST /chat/completions` → OpenAI-compatible JSON, the shape an icon-"C" custom provider
 *    gets from `parseCloudResponse`.
 *
 * [holdConnections] parks a connection AFTER its response headers (so the client is provably
 * mid-request) and before any content, which is what turns "Stop/supersede while a turn is in
 * flight" from a race into a deterministic test. The hold is released by [close], i.e. once the
 * test is done with the parked turn.
 */
internal class RoomTurnServer : AutoCloseable {

    private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val requestIndex = AtomicInteger(0)
    private val heldConnections = CountDownLatch(1)
    private val clients = CopyOnWriteArrayList<Socket>()

    /** Captured requests, in arrival order. */
    val requests = CopyOnWriteArrayList<RecordedRoomRequest>()

    /** When true, a connection is parked mid-response until [releaseHeld] / [close]. */
    @Volatile
    var holdConnections = false

    /**
     * Content for a first chunk the stub streams BEFORE parking (only meaningful together with
     * [holdConnections]). It lets a test park a turn that has ALREADY opened its in-place
     * placeholder — the state in which Stop has to actively REMOVE that row rather than merely
     * avoid appending one. A whitespace chunk is what makes the row removable: the app treats a
     * blank partial as no utterance at all.
     */
    @Volatile
    var preHoldChunk: String? = null

    /** Base URL accepted by the Ollama client AND by the custom cloud provider. */
    val url: String get() = "http://127.0.0.1:${socket.localPort}"

    init {
        thread(isDaemon = true, name = "room-loop-stub-accept") {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    // Socket closed by close(): the accept loop is done.
                    break
                }
                clients.add(client)
                thread(isDaemon = true, name = "room-loop-stub-conn") { serve(client) }
            }
        }
    }

    /** Wait until at least [count] requests have been captured, or fail the test. */
    fun awaitRequests(count: Int, timeoutMs: Long = 30_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (requests.size >= count) return
            Thread.sleep(20)
        }
        throw AssertionError("the stub only ever saw ${requests.size} request(s), expected $count")
    }

    override fun close() {
        releaseHeld()
        clients.forEach { runCatching { it.close() } }
        runCatching { socket.close() }
    }

    /**
     * Let every parked connection finish, and stop parking new ones. Called at the end of a
     * `use` block; a test that means to prove a parked turn's output is DISCARDED calls it
     * itself before asserting, so the late write actually gets a chance to arrive.
     */
    fun releaseHeld() {
        holdConnections = false
        heldConnections.countDown()
    }

    private fun serve(client: Socket) {
        try {
            val input = BufferedInputStream(client.getInputStream())
            val request = readRequest(input) ?: return
            requests.add(request)
            val out = client.getOutputStream()
            if (request.path.contains("/chat/completions")) {
                respondCloud(out, request)
            } else {
                respondOllama(out, request)
            }
        } catch (_: Exception) {
            // A closed/reset socket is the normal end of a cancelled turn.
        } finally {
            runCatching { client.close() }
        }
    }

    /**
     * Headers FIRST, then the hold. Writing the headers is what lets the client's `execute()`
     * return, so a parked turn is one the app has already committed to reading — which is
     * exactly the state Stop and supersede have to interrupt.
     */
    private fun awaitHoldIfNeeded() {
        if (!holdConnections) return
        heldConnections.await(30, TimeUnit.SECONDS)
    }

    private fun respondOllama(out: OutputStream, request: RecordedRoomRequest) {
        writeHeaders(out, "application/x-ndjson")
        out.flush()
        preHoldChunk?.let { writeNdjsonChunk(out, it) }
        awaitHoldIfNeeded()
        writeNdjsonChunk(out, request.reply())
        writeLine(out, """{"message":{"role":"assistant","content":""},"done":true,"done_reason":"stop"}""")
    }

    private fun respondCloud(out: OutputStream, request: RecordedRoomRequest) {
        val body = """{"choices":[{"message":{"role":"assistant","content":"${request.reply()}"}}],""" +
            """"usage":{"prompt_tokens":3,"completion_tokens":2}}"""
        writeHeaders(out, "application/json", contentLength = body.toByteArray().size)
        out.flush()
        awaitHoldIfNeeded()
        out.write(body.toByteArray())
        out.flush()
    }

    private fun RecordedRoomRequest.reply(): String = replyText(index, speakerTag)

    private fun writeHeaders(out: OutputStream, contentType: String, contentLength: Int? = null) {
        // No Content-Length on the NDJSON response: that body is delimited by the connection
        // closing, which is what the production streaming reader expects.
        val headers = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: $contentType\r\n" +
            (contentLength?.let { "Content-Length: $it\r\n" } ?: "") +
            "Connection: close\r\n\r\n"
        out.write(headers.toByteArray())
    }

    private fun writeLine(out: OutputStream, line: String) {
        out.write((line + "\n").toByteArray())
        out.flush()
    }

    /** One NDJSON delta line, the shape the production streaming reader parses. */
    private fun writeNdjsonChunk(out: OutputStream, text: String) {
        writeLine(out, """{"message":{"role":"assistant","content":"$text"},"done":false}""")
    }

    private fun readRequest(input: InputStream): RecordedRoomRequest? {
        val requestLine = readAsciiLine(input) ?: return null
        val headers = StringBuilder()
        while (true) {
            val line = readAsciiLine(input) ?: return null
            if (line.isEmpty()) break
            headers.append(line).append('\n')
        }
        val contentLength = headers.lineSequence()
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        // Drain the request body: leaving it unread makes the client see a reset connection
        // instead of a response.
        val body = StringBuilder()
        var remaining = contentLength
        val buffer = ByteArray(8_192)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (read <= 0) break
            body.append(String(buffer, 0, read, Charsets.UTF_8))
            remaining -= read
        }
        return RecordedRoomRequest(
            index = requestIndex.incrementAndGet(),
            path = requestLine.split(' ').getOrElse(1) { "" },
            body = body.toString(),
            speakerTag = PROBE_TAG_REGEX.find(body)?.groupValues?.get(1) ?: "unknown"
        )
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

/**
 * Reads a probe member's name back out of a request body. The probe personas carry
 * `ROOM-PROBE-<name>` as their system prompt, and nothing else in a room request mentions that
 * tag (the room preamble and the transcript labels use plain names), so a match identifies
 * exactly which member the request was built for.
 */
private val PROBE_TAG_REGEX = Regex("ROOM-PROBE-([A-Za-z0-9_]+)")
