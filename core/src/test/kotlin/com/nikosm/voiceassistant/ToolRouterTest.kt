package com.nikosm.voiceassistant

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where one call goes, without a loop, a provider or a server.
 *
 * The router is the seam M9 adds to M8's loop: the declared name decides, the MCP path is a
 * suspend call the service supplies, and a name neither source knows is answered with a sentence —
 * the same contract as every other tool failure, because an invented tool name must not end a turn.
 */
class ToolRouterTest {

    private val mcpTool = McpTool(
        serverId = "home",
        serverName = "Home",
        toolName = "set_light",
        declaredName = "mcp_home_set_light",
        description = "",
        inputSchema = JSONObject(),
    )

    private fun router(
        fs: ToolCallExecutor? = null,
        callMcp: suspend (McpTool, JSONObject) -> McpCallOutcome = { _, _ ->
            McpCallOutcome("ran", ok = true)
        },
    ) = ToolRouter(fs, mapOf(mcpTool.declaredName to mcpTool), callMcp)

    @Test
    fun `an MCP name routes to the registry with the model's arguments`() = runTest {
        var seen: JSONObject? = null
        val router = router(callMcp = { tool, arguments ->
            assertEquals("set_light", tool.toolName)
            seen = arguments
            McpCallOutcome("the light is on", ok = true)
        })

        val result = router.execute(FsToolCall("1", "mcp_home_set_light", """{"on":true}"""))

        assertTrue(result.ok)
        assertEquals("the light is on", result.text)
        assertEquals(true, seen!!.getBoolean("on"))
    }

    @Test
    fun `an unusable arguments string is an empty object, not a thrown exception`() = runTest {
        var seen: JSONObject? = null
        val router = router(callMcp = { _, arguments -> seen = arguments; McpCallOutcome("ok", true) })

        router.execute(FsToolCall("1", "mcp_home_set_light", "not json"))

        assertEquals(0, seen!!.length())
    }

    @Test
    fun `a filesystem name goes to the filesystem executor`() = runTest {
        val fs = object : ToolCallExecutor {
            override suspend fun execute(call: FsToolCall) =
                FsToolResult(call.id, call.name, "from the filesystem", ok = true)
        }

        val result = router(fs).execute(FsToolCall("1", "read_file", """{"path":"a.txt"}"""))

        assertEquals("from the filesystem", result.text)
    }

    @Test
    fun `an unknown name is a sentence, and a turn with no filesystem still answers`() = runTest {
        val result = router().execute(FsToolCall("1", "invented_tool", "{}"))

        assertFalse(result.ok)
        assertTrue(result.text.contains("invented_tool"))
    }

    @Test
    fun `the memory tool routes to the memory executor when the persona has it enabled`() = runTest {
        var called = false
        val router = ToolRouter(
            fs = null,
            mcpTools = emptyMap(),
            callMcp = { _, _ -> error("the MCP path must not be used") },
            remember = { call ->
                called = true
                FsToolResult(call.id, call.name, "Saved to memory.", ok = true)
            },
        )

        val result = router.execute(FsToolCall("1", MEMORY_TOOL, """{"text":"hi"}"""))

        assertTrue(called)
        assertTrue(result.ok)
        assertEquals("Saved to memory.", result.text)
    }

    @Test
    fun `remember without memory enabled is an unknown tool`() = runTest {
        val router = ToolRouter(null, emptyMap(), { _, _ -> error("the MCP path must not be used") })

        val result = router.execute(FsToolCall("1", MEMORY_TOOL, "{}"))

        assertFalse(result.ok)
        assertTrue(result.text.contains(MEMORY_TOOL))
    }

    @Test
    fun `a refused call keeps the server's sentence and its failure`() = runTest {
        val router = router(callMcp = { _, _ -> McpCallOutcome("The user did not approve this call, so it was not run.", false) })

        val result = router.execute(FsToolCall("1", "mcp_home_set_light", "{}"))

        assertFalse(result.ok)
        assertTrue(result.text.contains("did not approve"))
    }
}
