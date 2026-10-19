package com.nikosm.voiceassistant

import java.util.Collections
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry: handshake, tool listing (with its pages), calls, failures and the list-changed
 * notification — all against a fake transport, so none of it touches a process or a socket.
 *
 * The transports are tested on their own (`McpStdioTransportTest`, `McpHttpTransportTest`); this
 * file is the layer that decides what a server contributes and what the model is told when it does
 * not answer.
 */
class McpRegistryTest {

    private fun config(id: String = "notes", name: String = "Notes") = McpServerConfig(
        id = id,
        name = name,
        transport = McpTransportKind.STDIO,
        command = "fixture",
    )

    private fun initResult(): JSONObject = JSONObject()
        .put("protocolVersion", McpProtocol.VERSION)
        .put("capabilities", JSONObject().put("tools", JSONObject()))
        .put("serverInfo", JSONObject().put("name", "fixture").put("version", "1.0"))

    private fun result(id: Long, body: JSONObject): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", body)

    private fun failure(id: Long, code: Int, message: String): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("id", id)
            .put("error", JSONObject().put("code", code).put("message", message))

    private fun toolsPage(names: List<String>, cursor: String?): JSONObject = JSONObject().put(
        "tools",
        JSONArray().apply {
            names.forEach { put(JSONObject().put("name", it).put("description", "about $it")) }
        },
    ).apply { if (cursor != null) put("nextCursor", cursor) }

    @Test
    fun `tools are namespaced and every page is collected`() = runTest {
        val transport = FakeTransport { request ->
            val id = request.getLong("id")
            when (request.getString("method")) {
                "initialize" -> result(id, initResult())
                "tools/list" -> if (request.getJSONObject("params").has("cursor")) {
                    result(id, toolsPage(listOf("slow"), null))
                } else {
                    result(id, toolsPage(listOf("list_notes", "read_note"), "page-2"))
                }
                else -> failure(id, -32601, "unknown")
            }
        }
        val registry = McpRegistry(FakeFactory { transport }, connectTimeoutMillis = 2_000, callTimeoutMillis = 2_000)
        try {
            val tools = registry.toolsFor(config())

            assertEquals(
                listOf("mcp_notes_list_notes", "mcp_notes_read_note", "mcp_notes_slow"),
                tools.map { it.declaredName },
            )
            assertEquals(2, transport.sent.count { it.optString("method") == "tools/list" })
            // The server's own name is kept for tools/call; only the declared name is decorated.
            assertEquals("list_notes", tools[0].toolName)
        } finally {
            registry.close()
        }
    }

    @Test
    fun `a call returns the content sentence`() = runTest {
        val transport = FakeTransport { request ->
            val id = request.getLong("id")
            when (request.getString("method")) {
                "initialize" -> result(id, initResult())
                "tools/list" -> result(id, toolsPage(listOf("echo"), null))
                "tools/call" -> {
                    assertEquals("echo", request.getJSONObject("params").getString("name"))
                    assertEquals("hi", request.getJSONObject("params").getJSONObject("arguments").getString("text"))
                    result(
                        id,
                        JSONObject().put(
                            "content",
                            JSONArray().put(JSONObject().put("type", "text").put("text", "echo: hi")),
                        ),
                    )
                }
                else -> failure(id, -32601, "unknown")
            }
        }
        val registry = McpRegistry(FakeFactory { transport }, connectTimeoutMillis = 2_000, callTimeoutMillis = 2_000)
        try {
            val tool = registry.toolsFor(config()).single()
            val outcome = registry.call(tool, JSONObject().put("text", "hi"))

            assertTrue(outcome.ok)
            assertEquals("echo: hi", outcome.text)
        } finally {
            registry.close()
        }
    }

    @Test
    fun `a JSON-RPC error is a failed call with the server's sentence`() = runTest {
        val transport = FakeTransport { request ->
            val id = request.getLong("id")
            when (request.getString("method")) {
                "initialize" -> result(id, initResult())
                "tools/list" -> result(id, toolsPage(listOf("echo"), null))
                "tools/call" -> failure(id, -32602, "bad arguments")
                else -> failure(id, -32601, "unknown")
            }
        }
        val registry = McpRegistry(FakeFactory { transport }, connectTimeoutMillis = 2_000, callTimeoutMillis = 2_000)
        try {
            val outcome = registry.call(registry.toolsFor(config()).single(), JSONObject())

            assertFalse(outcome.ok)
            assertTrue(outcome.text, outcome.text.contains("-32602"))
            assertTrue(outcome.text, outcome.text.contains("bad arguments"))
        } finally {
            registry.close()
        }
    }

    @Test
    fun `a server without the tools capability contributes nothing and is closed`() = runTest {
        val transport = FakeTransport { request ->
            val id = request.getLong("id")
            if (request.getString("method") == "initialize") {
                result(
                    id,
                    JSONObject()
                        .put("protocolVersion", McpProtocol.VERSION)
                        .put("capabilities", JSONObject())
                        .put("serverInfo", JSONObject().put("name", "no-tools")),
                )
            } else {
                failure(id, -32601, "unknown")
            }
        }
        val registry = McpRegistry(FakeFactory { transport }, connectTimeoutMillis = 2_000, callTimeoutMillis = 2_000)
        try {
            assertTrue(registry.toolsFor(config()).isEmpty())
            assertTrue("a server that offers no tools should not stay open", transport.closed)
        } finally {
            registry.close()
        }
    }

    @Test
    fun `a server that never answers yields no tools instead of hanging the turn`() = runTest {
        val transport = FakeTransport { null }
        val registry = McpRegistry(FakeFactory { transport }, connectTimeoutMillis = 100, callTimeoutMillis = 100)
        try {
            assertTrue(registry.toolsFor(config()).isEmpty())
        } finally {
            registry.close()
        }
    }

    @Test
    fun `list_changed makes the next turn re-list the tools`() = runTest {
        val transport = FakeTransport { request ->
            val id = request.getLong("id")
            when (request.getString("method")) {
                "initialize" -> result(id, initResult())
                "tools/list" -> result(id, toolsPage(listOf("echo"), null))
                else -> failure(id, -32601, "unknown")
            }
        }
        val registry = McpRegistry(FakeFactory { transport }, connectTimeoutMillis = 2_000, callTimeoutMillis = 2_000)
        try {
            registry.toolsFor(config())
            val before = transport.sent.count { it.optString("method") == "tools/list" }

            transport.deliver("""{"jsonrpc":"2.0","method":"notifications/tools/list_changed"}""")
            registry.toolsFor(config())

            assertEquals(
                "the cache should have been dropped",
                before + 1,
                transport.sent.count { it.optString("method") == "tools/list" },
            )
        } finally {
            registry.close()
        }
    }

    @Test
    fun `a dead transport is reported to the model, and the next turn reconnects`() = runTest {
        var connections = 0
        var serverUp = true
        val transports = mutableListOf<FakeTransport>()
        val realFactory = object : McpTransportFactory {
            override fun open(server: McpServerConfig): McpTransport {
                connections++
                if (!serverUp) throw McpTransportException("the server is down")
                return FakeTransport { request ->
                    val id = request.getLong("id")
                    when (request.getString("method")) {
                        "initialize" -> result(id, initResult())
                        "tools/list" -> result(id, toolsPage(listOf("echo"), null))
                        "tools/call" -> result(
                            id,
                            JSONObject().put(
                                "content",
                                JSONArray().put(JSONObject().put("type", "text").put("text", "ran")),
                            ),
                        )
                        else -> failure(id, -32601, "unknown")
                    }
                }.also { transports += it }
            }
        }
        val registry = McpRegistry(realFactory, connectTimeoutMillis = 2_000, callTimeoutMillis = 2_000)
        try {
            val tool = registry.toolsFor(config()).single()
            transports.single().die()
            serverUp = false

            // The connection is gone and the server will not reopen: the model gets a sentence.
            val outcome = registry.call(tool, JSONObject())
            assertFalse(outcome.ok)
            assertTrue(outcome.text, outcome.text.contains("could not run"))

            // When it comes back, the next turn reconnects and lists again.
            serverUp = true
            assertEquals("mcp_notes_echo", registry.toolsFor(config()).single().declaredName)
            assertEquals("one open, one refused, one reconnect", 3, connections)
        } finally {
            registry.close()
        }
    }

    @Test
    fun `a changed command reconnects instead of reusing the old connection`() = runTest {
        var opens = 0
        val transports = mutableListOf<FakeTransport>()
        val factory = object : McpTransportFactory {
            override fun open(server: McpServerConfig): McpTransport {
                opens++
                return FakeTransport { request ->
                    val id = request.getLong("id")
                    when (request.getString("method")) {
                        "initialize" -> result(id, initResult())
                        "tools/list" -> result(id, toolsPage(listOf("echo"), null))
                        else -> failure(id, -32601, "unknown")
                    }
                }.also { transports += it }
            }
        }
        val registry = McpRegistry(factory, connectTimeoutMillis = 2_000, callTimeoutMillis = 2_000)
        try {
            registry.toolsFor(config())
            assertEquals(1, opens)

            registry.toolsFor(config().copy(command = "a-different-command"))

            assertEquals("a changed connection must not reuse the old process", 2, opens)
            assertTrue("the old connection should have been closed", transports[0].closed)
        } finally {
            registry.close()
        }
    }

    @Test
    fun `names are sanitized, shortened to the provider limit and deterministic`() {
        assertEquals("mcp_my_server_read_file", declaredMcpToolName("My Server!", "Read File"))
        assertEquals("mcp_notes_echo", declaredMcpToolName("notes", "echo"))

        val long = declaredMcpToolName("s".repeat(80), "t".repeat(120))
        assertTrue("provider names are at most 64 characters", long.length <= 64)
        assertTrue(long.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        assertEquals(long, declaredMcpToolName("s".repeat(80), "t".repeat(120)))
    }

    // ------------------------------------------------------------------ fakes

    @Test
    fun `cancelling a tools request cancels it rather than answering with no tools`() = runTest {
        // A stopped turn must unwind. The old catch (e: Exception) turned the CancellationException
        // into an empty list, so the turn continued as if the server had said "no tools".
        val transport = FakeTransport { request ->
            if (request.getString("method") == "initialize") result(request.getLong("id"), initResult()) else null
        }
        val registry = McpRegistry(FakeFactory { transport }, connectTimeoutMillis = 60_000, callTimeoutMillis = 60_000)
        try {
            var reachedAfter = false
            val job = launch {
                registry.toolsFor(config())
                reachedAfter = true
            }
            runCurrent()
            job.cancel()
            job.join()

            assertTrue("the cancelled request stays cancelled", job.isCancelled)
            assertFalse("a cancelled request must not return normally", reachedAfter)
        } finally {
            registry.close()
        }
    }

    @Test
    fun `cancelling a tool call cancels it rather than reporting a failed call`() = runTest {
        val transport = FakeTransport { request ->
            val id = request.getLong("id")
            when (request.getString("method")) {
                "initialize" -> result(id, initResult())
                "tools/list" -> result(id, toolsPage(listOf("slow"), null))
                else -> null // tools/call never answers
            }
        }
        val registry = McpRegistry(FakeFactory { transport }, connectTimeoutMillis = 2_000, callTimeoutMillis = 60_000)
        try {
            val tool = registry.toolsFor(config()).single()
            var reachedAfter = false
            val job = launch {
                registry.call(tool, JSONObject())
                reachedAfter = true
            }
            runCurrent()
            job.cancel()
            job.join()

            assertTrue("the cancelled call stays cancelled", job.isCancelled)
            assertFalse("a cancelled call must not return normally", reachedAfter)
        } finally {
            registry.close()
        }
    }

    private class FakeTransport(
        private val handler: (JSONObject) -> JSONObject?,
    ) : McpTransport {
        private var onMessage: ((String) -> Unit)? = null
        private var onClosed: ((String) -> Unit)? = null
        val sent: MutableList<JSONObject> = Collections.synchronizedList(mutableListOf())

        @Volatile
        var closed = false

        override fun start(onMessage: (String) -> Unit, onClosed: (String) -> Unit) {
            this.onMessage = onMessage
            this.onClosed = onClosed
        }

        override fun send(message: String) {
            if (closed) throw McpTransportException("the fake is closed")
            val request = JSONObject(message)
            sent += request
            // A notification (no id) is not answered by a server; only requests are.
            if (!request.has("id")) return
            handler(request)?.let { onMessage?.invoke(it.toString()) }
        }

        override fun close() {
            closed = true
            onClosed?.invoke("closed")
        }

        fun die() {
            onClosed?.invoke("the server died")
        }

        fun deliver(raw: String) {
            onMessage?.invoke(raw)
        }
    }

    private class FakeFactory(private val create: () -> McpTransport) : McpTransportFactory {
        override fun open(server: McpServerConfig): McpTransport = create()
    }
}
