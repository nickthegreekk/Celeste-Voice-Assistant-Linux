package com.nikosm.voiceassistant.linux

import com.nikosm.voiceassistant.McpServerConfig
import com.nikosm.voiceassistant.McpTransportException
import com.nikosm.voiceassistant.McpTransportKind
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * The Streamable HTTP transport against a loopback server — no network, no real MCP server.
 *
 * It covers the parts that only a wire can show: a JSON response, an SSE response, the session id
 * travelling on later requests, configured headers travelling on every request, and the GET event
 * stream delivering a server-initiated notification. Protocol semantics stay in `:core`'s registry
 * tests; this file is about the HTTP plumbing.
 */
class McpHttpTransportTest {

    private lateinit var http: HttpServer
    private lateinit var pool: ExecutorService
    private lateinit var transport: McpHttpTransport

    private val messages = Collections.synchronizedList(mutableListOf<String>())
    private val authHeaders = Collections.synchronizedList(mutableListOf<String?>())
    private val sessionHeaders = Collections.synchronizedList(mutableListOf<String?>())
    private val releaseStream = CountDownLatch(1)

    @Before
    fun setUp() {
        http = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        pool = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "mcp-http-test").apply { isDaemon = true }
        }
        http.executor = pool
        http.createContext("/mcp") { exchange -> handle(exchange) }
        http.start()

        val config = McpServerConfig(
            id = "fixture",
            name = "Fixture",
            transport = McpTransportKind.HTTP,
            url = "http://127.0.0.1:${http.address.port}/mcp",
            headers = mapOf("Authorization" to "Bearer test"),
        )
        transport = McpHttpTransport(config)
        transport.start({ line -> messages += line }, { })
    }

    @After
    fun tearDown() {
        transport.close()
        releaseStream.countDown()
        http.stop(0)
        pool.shutdownNow()
    }

    @Test
    fun `initialize, a tools list over SSE and a call round-trip with the session and configured headers`() {
        transport.send(request(1, "initialize"))
        assertEquals(
            "fixture",
            await(1)["result"]!!.jsonObject["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content,
        )

        transport.send(request(2, "tools/list"))
        assertEquals(
            "echo",
            await(2)["result"]!!.jsonObject["tools"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content,
        )

        transport.send(
            request(
                3,
                "tools/call",
                buildJsonObject {
                    put("name", "echo")
                    put("arguments", buildJsonObject { put("text", "hi") })
                },
            ),
        )
        assertEquals(
            "echo: hi",
            await(3)["result"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
        )

        assertTrue("the GET event stream should deliver list_changed", awaitListChanged())
        assertTrue(
            "every request should carry the configured header",
            authHeaders.isNotEmpty() && authHeaders.all { it == "Bearer test" },
        )
        assertEquals("only initialize goes out without a session", 1, sessionHeaders.count { it == null })
        assertTrue("later requests carry the session", sessionHeaders.count { it == "session-1" } >= 2)
    }

    @Test
    fun `a closed transport refuses further messages`() {
        transport.close()

        try {
            transport.send(request(4, "ping"))
            fail("a closed transport must not accept a message")
        } catch (expected: McpTransportException) {
            assertTrue(expected.message!!.isNotBlank())
        }
    }

    // ------------------------------------------------------------------ the scripted server

    private fun handle(exchange: HttpExchange) {
        try {
            authHeaders += exchange.requestHeaders.getFirst("Authorization")
            sessionHeaders += exchange.requestHeaders.getFirst("Mcp-Session-Id")

            if (exchange.requestMethod == "GET") {
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { out ->
                    out.write(
                        "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}\n\n"
                            .toByteArray(Charsets.UTF_8),
                    )
                    out.flush()
                    releaseStream.await(10, TimeUnit.SECONDS)
                }
                return
            }

            val request = Json.parseToJsonElement(
                exchange.requestBody.readBytes().toString(Charsets.UTF_8),
            ).jsonObject
            val id = request["id"]!!.jsonPrimitive.long
            when (request["method"]!!.jsonPrimitive.content) {
                "initialize" -> respondJson(
                    exchange,
                    response(
                        id,
                        buildJsonObject {
                            put("protocolVersion", "2025-06-18")
                            put("capabilities", buildJsonObject { put("tools", buildJsonObject {}) })
                            put("serverInfo", buildJsonObject { put("name", "fixture"); put("version", "1.0") })
                        },
                    ),
                    session = "session-1",
                )

                "tools/list" -> respondSse(
                    exchange,
                    response(
                        id,
                        buildJsonObject {
                            put(
                                "tools",
                                buildJsonArray { add(buildJsonObject { put("name", "echo") }) },
                            )
                        },
                    ),
                )

                "tools/call" -> respondJson(
                    exchange,
                    response(
                        id,
                        buildJsonObject {
                            put(
                                "content",
                                buildJsonArray {
                                    add(buildJsonObject { put("type", "text"); put("text", "echo: hi") })
                                },
                            )
                        },
                    ),
                )

                else -> respondJson(
                    exchange,
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", id)
                        put(
                            "error",
                            buildJsonObject { put("code", -32601); put("message", "Method not found") },
                        )
                    }.toString(),
                )
            }
        } finally {
            exchange.close()
        }
    }

    private fun response(id: Long, result: JsonObject): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }.toString()

    private fun respondJson(exchange: HttpExchange, body: String, session: String? = null) {
        exchange.responseHeaders.add("Content-Type", "application/json")
        if (session != null) exchange.responseHeaders.add("Mcp-Session-Id", session)
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    /** `sendResponseHeaders(200, 0)` is chunked, which is what an SSE stream needs. */
    private fun respondSse(exchange: HttpExchange, payload: String) {
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.sendResponseHeaders(200, 0)
        exchange.responseBody.use { out ->
            out.write("data: $payload\n\n".toByteArray(Charsets.UTF_8))
            out.flush()
        }
    }

    // ------------------------------------------------------------------ reading what arrived

    private fun request(id: Long, method: String, params: JsonObject = JsonObject(emptyMap())): String =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }.toString()

    /** The message carrying [id], skipping server notifications. */
    private fun await(id: Long): JsonObject {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            synchronized(messages) {
                for (line in messages) {
                    val message = Json.parseToJsonElement(line).jsonObject
                    if (message["id"]?.jsonPrimitive?.long == id) return message
                }
            }
            Thread.sleep(25)
        }
        error("no answer for id $id within 15 s")
    }

    private fun awaitListChanged(): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            synchronized(messages) {
                if (messages.any { line ->
                        Json.parseToJsonElement(line).jsonObject["method"]?.jsonPrimitive?.content ==
                            "notifications/tools/list_changed"
                    }
                ) {
                    return true
                }
            }
            Thread.sleep(25)
        }
        return false
    }
}
