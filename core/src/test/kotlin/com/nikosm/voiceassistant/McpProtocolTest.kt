package com.nikosm.voiceassistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The protocol layer, against fabricated bodies — no server, no process, no clock.
 *
 * The wire shapes here are the [Model Context Protocol's](https://modelcontextprotocol.io) JSON-RPC
 * ones for the tool subset this client implements (`2025-06-18`). The registry tests drive the
 * handshake and the transport; this file is what decides whether the bytes are the right bytes.
 */
class McpProtocolTest {

    @Test
    fun `initialize sends the version, an empty capability set and the client name`() {
        val request = McpProtocol.initializeRequest(id = 7, clientVersion = "0.1.0")

        assertEquals("2.0", request.getString("jsonrpc"))
        assertEquals(7L, request.getLong("id"))
        assertEquals("initialize", request.getString("method"))
        val params = request.getJSONObject("params")
        assertEquals(McpProtocol.VERSION, params.getString("protocolVersion"))
        assertEquals(0, params.getJSONObject("capabilities").length())
        assertEquals(McpProtocol.CLIENT_NAME, params.getJSONObject("clientInfo").getString("name"))
        assertEquals("0.1.0", params.getJSONObject("clientInfo").getString("version"))
    }

    @Test
    fun `the initialized notification carries no id`() {
        val notification = McpProtocol.initializedNotification()

        assertEquals("notifications/initialized", notification.getString("method"))
        assertFalse("a notification must not have an id", notification.has("id"))
    }

    @Test
    fun `tools-list sends a cursor only when there is a next page`() {
        assertFalse(McpProtocol.listToolsRequest(1).getJSONObject("params").has("cursor"))

        val paged = McpProtocol.listToolsRequest(2, cursor = "page-2")
        assertEquals("page-2", paged.getJSONObject("params").getString("cursor"))
    }

    @Test
    fun `tools-call sends the namespaced name and an arguments object`() {
        val arguments = JSONObject().put("path", "/tmp").put("limit", 3)

        val request = McpProtocol.callToolRequest(9, "read_file", arguments)

        val params = request.getJSONObject("params")
        assertEquals("read_file", params.getString("name"))
        assertEquals("/tmp", params.getJSONObject("arguments").getString("path"))
        assertEquals(3, params.getJSONObject("arguments").getInt("limit"))
    }

    @Test
    fun `a tool list carries names, descriptions, schemas and annotations`() {
        val result = JSONObject().put(
            "tools",
            JSONArray()
                .put(
                    JSONObject()
                        .put("name", "list_notes")
                        .put("description", "List the notes.")
                        .put("inputSchema", JSONObject().put("type", "object"))
                        .put("annotations", JSONObject().put("readOnlyHint", true)),
                )
                .put(JSONObject().put("name", " ") ) // nameless: dropped, not declared
                .put(JSONObject().put("name", "create_note")),
        ).put("nextCursor", "next")

        val (tools, cursor) = McpProtocol.toolList(result)

        assertEquals(listOf("list_notes", "create_note"), tools.map { it.name })
        assertEquals("List the notes.", tools[0].description)
        assertTrue(tools[0].readOnly)
        assertFalse(tools[1].readOnly)
        assertEquals("object", tools[1].inputSchema.getString("type")) // the default schema
        assertEquals("next", cursor)
    }

    @Test
    fun `the last page has no cursor`() {
        val (_, cursor) = McpProtocol.toolList(JSONObject().put("tools", JSONArray()))

        assertNull(cursor)
    }

    @Test
    fun `text parts are joined, an image is a placeholder and a resource keeps its text`() {
        val result = JSONObject().put(
            "content",
            JSONArray()
                .put(JSONObject().put("type", "text").put("text", "first"))
                .put(JSONObject().put("type", "text").put("text", "second"))
                .put(JSONObject().put("type", "image").put("mimeType", "image/png").put("data", "AAAA"))
                .put(
                    JSONObject().put("type", "resource").put(
                        "resource",
                        JSONObject().put("uri", "file:///notes.md").put("text", "embedded"),
                    ),
                ),
        )

        val outcome = McpProtocol.callResult(result)

        assertTrue(outcome.ok)
        assertEquals("first\nsecond\n[image: image/png, about 3 bytes]\nembedded", outcome.text)
    }

    @Test
    fun `a resource without embedded text reports its uri`() {
        val result = JSONObject().put(
            "content",
            JSONArray().put(
                JSONObject().put("type", "resource").put(
                    "resource",
                    JSONObject().put("uri", "file:///x.bin"),
                ),
            ),
        )

        assertEquals("[resource: file:///x.bin]", McpProtocol.callResult(result).text)
    }

    @Test
    fun `isError is a refusal, not a sentence that reads like success`() {
        val result = JSONObject()
            .put("isError", true)
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "permission denied")))

        val outcome = McpProtocol.callResult(result)

        assertFalse(outcome.ok)
        assertEquals("permission denied", outcome.text)
    }

    @Test
    fun `an empty result still says something`() {
        assertEquals("(the tool returned no content)", McpProtocol.callResult(JSONObject()).text)
        assertEquals(
            "the tool reported an error with no details.",
            McpProtocol.callResult(JSONObject().put("isError", true)).text,
        )
    }

    @Test
    fun `structured content is the fallback when the content array is empty`() {
        val result = JSONObject().put("structuredContent", JSONObject().put("count", 3))

        assertEquals("{\"count\":3}", McpProtocol.callResult(result).text)
    }

    @Test
    fun `a JSON-RPC error keeps its code`() {
        val message = JSONObject().put(
            "error",
            JSONObject().put("code", -32601).put("message", "Method not found"),
        )

        assertEquals("JSON-RPC error -32601: Method not found", McpProtocol.errorOf(message))
        assertNull(McpProtocol.errorOf(JSONObject().put("result", JSONObject())))
    }

    @Test
    fun `only the list-changed notification is recognised`() {
        assertTrue(
            McpProtocol.isToolListChanged(
                JSONObject().put("jsonrpc", "2.0").put("method", "notifications/tools/list_changed"),
            ),
        )
        assertFalse(
            "a response that happens to mention the method is not a notification",
            McpProtocol.isToolListChanged(
                JSONObject().put("id", 1).put("method", "notifications/tools/list_changed"),
            ),
        )
        assertFalse(McpProtocol.isToolListChanged(JSONObject().put("method", "notifications/other")))
    }

    @Test
    fun `the handshake reads the server and whether it offers tools`() {
        val result = JSONObject()
            .put("protocolVersion", "2025-06-18")
            .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", true)))
            .put("serverInfo", JSONObject().put("name", "notes-server").put("version", "1.2.3"))

        val handshake = McpHandshake.from(result)

        assertNotNull(handshake)
        assertEquals("2025-06-18", handshake!!.protocolVersion)
        assertEquals("notes-server", handshake.serverName)
        assertEquals("1.2.3", handshake.serverVersion)
        assertTrue(handshake.tools)
    }

    @Test
    fun `a handshake without a protocol version is not a server answer`() {
        assertNull(McpHandshake.from(JSONObject().put("capabilities", JSONObject())))
    }
}
