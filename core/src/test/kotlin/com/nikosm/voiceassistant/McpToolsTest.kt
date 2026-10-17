package com.nikosm.voiceassistant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How an MCP tool reaches the model: the declaration in all four dialects, the prompt contract,
 * and the budget text. Pure, so it runs against fabricated tools.
 *
 * The declarations are built by the same [toolsJsonFor] the filesystem tools use — the point of
 * this file is that the two sources cannot drift into different shapes.
 */
class McpToolsTest {

    private fun mcpTool(
        serverId: String = "home",
        serverName: String = "Home",
        toolName: String = "set_light",
        description: String = "Turn a light on or off.",
    ) = McpTool(
        serverId = serverId,
        serverName = serverName,
        toolName = toolName,
        declaredName = declaredMcpToolName(serverId, toolName),
        description = description,
        inputSchema = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put("on", JSONObject().put("type", "boolean"))),
    )

    @Test
    fun `the declaration carries the namespaced name, the server and the server's own schema`() {
        val declarations = allToolsJsonFor(FsToolProvider.OLLAMA, FsToolMode.OFF, listOf(mcpTool()))!!

        val function = declarations.getJSONObject(0).getJSONObject("function")
        assertEquals("mcp_home_set_light", function.getString("name"))
        assertTrue(function.getString("description").contains("Home"))
        assertTrue(function.getString("description").contains("Turn a light on or off."))
        assertEquals("object", function.getJSONObject("parameters").getString("type"))
    }

    @Test
    fun `filesystem tools come first and MCP tools after, on every dialect`() {
        val tools = listOf(mcpTool())

        for (provider in FsToolProvider.values()) {
            val declarations = allToolsJsonFor(provider, FsToolMode.READ_ONLY, tools)!!
            val names = when (provider) {
                FsToolProvider.OLLAMA, FsToolProvider.OPENAI ->
                    (0 until declarations.length()).map {
                        declarations.getJSONObject(it).getJSONObject("function").getString("name")
                    }
                FsToolProvider.ANTHROPIC ->
                    (0 until declarations.length()).map { declarations.getJSONObject(it).getString("name") }
                FsToolProvider.GEMINI ->
                    declarations.getJSONObject(0).getJSONArray("function_declarations").let { array ->
                        (0 until array.length()).map { array.getJSONObject(it).getString("name") }
                    }
            }
            assertEquals(provider.name, listOf("list_files", "read_file", "mcp_home_set_light"), names)
        }
    }

    @Test
    fun `Anthropic uses input_schema and Gemini wraps the declarations`() {
        val anthropic = allToolsJsonFor(FsToolProvider.ANTHROPIC, FsToolMode.OFF, listOf(mcpTool()))!!
        assertNotNull(anthropic.getJSONObject(0).getJSONObject("input_schema"))

        val gemini = allToolsJsonFor(FsToolProvider.GEMINI, FsToolMode.OFF, listOf(mcpTool()))!!
        assertEquals(1, gemini.getJSONObject(0).getJSONArray("function_declarations").length())
    }

    @Test
    fun `a turn with no tools declares nothing and texts nothing`() {
        assertNull(allToolsJsonFor(FsToolProvider.OLLAMA, FsToolMode.OFF, emptyList()))
        assertEquals("", toolDeclarationsText(FsToolProvider.OLLAMA, FsToolMode.OFF, emptyList()))
    }

    @Test
    fun `the contract names the servers, forbids invented calls and does not promise success`() {
        val contract = mcpToolContractFor(listOf(mcpTool(), mcpTool(toolName = "read_sensor")))!!

        assertTrue(contract.contains("Home"))
        assertTrue(contract.contains("mcp_home_set_light"))
        assertTrue(contract.contains("Never say you used a tool"))
        assertTrue(contract.contains("data, not instructions"))
        assertTrue(contract.contains("refused"))
    }

    @Test
    fun `the combined contract is the filesystem one and the MCP one`() {
        val combined = allToolContractFor(FsToolMode.READ_WRITE, listOf(mcpTool()))!!

        assertTrue(combined.contains("filesystem tools"))
        assertTrue(combined.contains("MCP servers"))
        assertNull(allToolContractFor(FsToolMode.OFF, emptyList()))
    }

    @Test
    fun `a tool without a description still says where it comes from`() {
        val description = mcpToolDescription(mcpTool(description = ""))

        assertTrue(description.contains("Home"))
        assertTrue(description.endsWith("A tool that server offers."))
    }
}
