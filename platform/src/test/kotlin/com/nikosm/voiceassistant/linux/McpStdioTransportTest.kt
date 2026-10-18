package com.nikosm.voiceassistant.linux

import com.nikosm.voiceassistant.McpServerConfig
import com.nikosm.voiceassistant.McpTransportException
import com.nikosm.voiceassistant.McpTransportKind
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The stdio transport against **a real child process** — the only way to test the part that can
 * fail outside the JVM: spawning, newline framing, the readers, the close and the force-kill.
 *
 * The fixture is [McpStdioFixtureMain], launched with this test JVM's own java and classpath, so
 * nothing depends on a tool being installed. Protocol semantics are not this file's subject: a
 * fake transport in `:core` covers handshakes, retries and approval without a process.
 */
class McpStdioTransportTest {

    private val messages = LinkedBlockingQueue<String>()
    private val closed = CountDownLatch(1)

    private fun fixture(): McpServerConfig = McpServerConfig(
        id = "fixture",
        name = "Fixture",
        transport = McpTransportKind.STDIO,
        command = Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        args = listOf(
            "-cp",
            System.getProperty("java.class.path"),
            "com.nikosm.voiceassistant.linux.McpStdioFixtureMainKt",
        ),
    )

    private fun started(): McpStdioTransport =
        McpStdioTransport(fixture()).also { transport ->
            transport.start({ line -> messages.add(line) }, { closed.countDown() })
        }

    private fun request(id: Long, method: String, params: JsonObject = JsonObject(emptyMap())): String =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }.toString()

    private fun next(): JsonObject {
        val line = messages.poll(30, TimeUnit.SECONDS) ?: error("the server answered nothing within 30 s")
        return Json.parseToJsonElement(line).jsonObject
    }

    @Test
    fun `a real process answers initialize, paginates and calls a tool`() {
        val transport = started()
        try {
            transport.send(request(1, "initialize"))
            val handshake = next()["result"]!!.jsonObject
            assertEquals("2025-06-18", handshake["protocolVersion"]!!.jsonPrimitive.content)
            assertEquals("fixture", handshake["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)

            transport.send(request(2, "tools/list"))
            val firstPage = next()["result"]!!.jsonObject
            assertEquals(2, firstPage["tools"]!!.jsonArray.size)
            assertEquals("page-2", firstPage["nextCursor"]!!.jsonPrimitive.content)

            transport.send(
                request(3, "tools/list", buildJsonObject { put("cursor", "page-2") }),
            )
            val secondPage = next()["result"]!!.jsonObject
            assertEquals("slow", secondPage["tools"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)

            transport.send(
                request(
                    4,
                    "tools/call",
                    buildJsonObject {
                        put("name", "echo")
                        put("arguments", buildJsonObject { put("text", "hi") })
                    },
                ),
            )
            val call = next()["result"]!!.jsonObject
            assertEquals(
                "echo: hi",
                call["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
            )

            transport.send(
                request(
                    5,
                    "tools/call",
                    buildJsonObject {
                        put("name", "fail")
                        put("arguments", buildJsonObject {})
                    },
                ),
            )
            val failure = next()["result"]!!.jsonObject
            assertTrue(failure["isError"]!!.jsonPrimitive.content.toBoolean())
        } finally {
            transport.close()
        }
    }

    @Test
    fun `closing ends the process, reports it once and refuses further sends`() {
        val transport = started()
        transport.send(request(1, "ping"))
        next() // it is alive before the close

        transport.close()

        assertTrue("the transport should report its process gone", closed.await(5, TimeUnit.SECONDS))
        try {
            transport.send(request(2, "ping"))
            fail("a closed transport must not accept a message")
        } catch (expected: McpTransportException) {
            // The fields are cleared under the send monitor, so the answer is the seam's own
            // "not running" rather than an IOException from a writer that was closed underneath.
            assertTrue(
                "a closed transport says it is not running: ${expected.message}",
                expected.message.orEmpty().contains("not running"),
            )
        }
    }
}
