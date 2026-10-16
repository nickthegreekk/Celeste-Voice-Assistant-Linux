package com.nikosm.voiceassistant

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.Executors

/**
 * A model provider on loopback, so a turn can be driven end to end: a real request out, a scripted
 * body back, and everything of the round trip that the app does in between actually happening.
 *
 * **Why this exists.** The M8 review found two defects that no test could see — the Ollama request
 * never declared its tools, and the cloud tool loop was gated on a field that is OFF at every real
 * call site — because the request builders are private and the milestone had no fake provider to
 * send one to. A unit test of the same code would have had to re-state the shape it was checking;
 * this answers with the bytes a provider really sends, so `fsToolCallsFrom` parses a body it did not
 * write, the executor runs against a real temporary folder, and the next request carries the result.
 *
 * **It answers nothing it was not asked to.** Replies come from a queue the test fills; an empty
 * queue is a 500 naming the path rather than a plausible-looking body, because a test that
 * under-scripts itself should fail with a sentence, not pass on invented data.
 *
 * `GET …/models` is the one exception, and it is not conversation: `updateCloudApi`/`fetchCloudModels`
 * are a different feature that fires on saving a provider, and it would be a poor fixture that made
 * every test script its response. It is served from a constant and consumes nothing.
 */
internal class FakeProviderServer : AutoCloseable {

    /** One request, as it arrived. [body] is empty for a GET. */
    internal data class Recorded(val method: String, val path: String, val body: String)

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val pool = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "fake-provider").apply { isDaemon = true }
    }

    private val queue = Collections.synchronizedList(mutableListOf<Pair<Int, String>>())

    /** Every request in arrival order. The assertions read this rather than the app's own logs. */
    val requests: MutableList<Recorded> = Collections.synchronizedList(mutableListOf())

    /**
     * Conversation requests held open by the test, keyed by their number (the first POST is 1).
     *
     * A request is **recorded before it is held**, so a test can assert on what was sent and on the
     * state of the app while the answer is still outstanding — which is the only way to look at a
     * turn in the middle: what happens *between* two rounds, or what Stop does to a round that has
     * already left the machine.
     */
    private val holds = Collections.synchronizedMap(mutableMapOf<Int, java.util.concurrent.CountDownLatch>())
    private val posts = java.util.concurrent.atomic.AtomicInteger(0)

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}/v1"

    /** The conversation requests only — the model-list probe is not a turn. */
    val turns: List<Recorded> get() = requests.filter { it.method == "POST" }

    init {
        server.executor = pool
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
    }

    /** The next conversation request is answered with this. Replies are consumed in order. */
    fun reply(status: Int = 200, body: String) {
        queue.add(status to body)
    }

    fun reply(body: String) = reply(200, body)

    /** Holds conversation request [number] (the first POST is 1) until [releaseRequest] is called. */
    fun holdRequest(number: Int) {
        holds[number] = java.util.concurrent.CountDownLatch(1)
    }

    fun releaseRequest(number: Int) {
        holds.remove(number)?.countDown()
    }

    private fun handle(exchange: HttpExchange) {
        val body = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
        requests.add(Recorded(exchange.requestMethod, exchange.requestURI.path, body))
        val number = if (exchange.requestMethod == "POST") posts.incrementAndGet() else 0
        // Held after recording, so the test sees the request while its answer is still outstanding.
        // The wait is bounded: a test that forgets to release gets a 500 it can read rather than a
        // fixture that hangs the build.
        val released = holds[number]?.await(30, java.util.concurrent.TimeUnit.SECONDS) ?: true
        val (status, response) = when {
            !released ->
                500 to """{"error":{"message":"FakeProviderServer: request $number was held and never released"}}"""
            exchange.requestMethod == "GET" && exchange.requestURI.path.endsWith("/models") ->
                200 to MODEL_LIST
            exchange.requestMethod == "POST" -> queue.removeFirstOrNull()
                ?: (500 to """{"error":{"message":"FakeProviderServer has no scripted reply for ${exchange.requestURI.path}"}}""")
            else -> 404 to """{"error":{"message":"FakeProviderServer does not serve ${exchange.requestMethod} ${exchange.requestURI.path}"}}"""
        }
        val bytes = response.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    override fun close() {
        server.stop(0)
        pool.shutdownNow()
    }

    private companion object {
        const val MODEL_LIST = """{"object":"list","data":[{"id":"fake-model","object":"model"}]}"""
    }
}

// ------------------------------------------------------------------ the OpenAI dialect, as a provider writes it

/** A reply with prose in it — what an ordinary turn ends with. */
internal fun openAiReply(text: String): String = JSONObject()
    .put("choices", JSONArray().put(JSONObject().put("message", JSONObject()
        .put("role", "assistant")
        .put("content", text))))
    .toString()

/**
 * A reply that is a tool call and nothing else — `content: null`, which is what a real provider
 * sends and what the parser would throw on if it were asked to read it.
 */
internal fun openAiToolCall(name: String, arguments: String, id: String = "call_1"): String = JSONObject()
    .put("choices", JSONArray().put(JSONObject().put("message", JSONObject()
        .put("role", "assistant")
        .put("content", JSONObject.NULL)
        .put("tool_calls", JSONArray().put(JSONObject()
            .put("id", id)
            .put("type", "function")
            .put("function", JSONObject().put("name", name).put("arguments", arguments)))))))
    .toString()

/** The messages array of a recorded request, for asserting on what the model was actually sent. */
internal fun FakeProviderServer.Recorded.messages(): JSONArray =
    JSONObject(body).getJSONArray("messages")
