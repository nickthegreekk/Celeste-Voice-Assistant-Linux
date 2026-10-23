package com.nikosm.voiceassistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json

class ExportRedactionTest {

    private val SENTINEL = "CREDENTIAL_LEAK"

    @Test fun `exportBackup(excludeSecrets = true) strips credentials`() {
        // Create manager and add an MCP server with sentinel values
        val manager = SettingsManager(FakeAppEnv(), InMemoryStore(), NoImageCodec)
        manager.saveMcpServers(listOf(McpServerConfig(
            id = "test-mcp",
            name = "Test MCP",
            transport = McpTransportKind.STDIO,
            command = "touch",
            args = listOf("--api-key", SENTINEL, "--token", SENTINEL),
            env = mapOf("API_KEY" to SENTINEL, "SECRET" to SENTINEL),
            headers = mapOf("X-API-Key" to SENTINEL),
            url = "http://user:pass@mcp.local/api",
            enabled = true,
            trust = McpTrust.TRUSTED
        )))

        val jsonString = manager.exportBackup(excludeSecrets = true)

        // When excludeSecrets=true, credentials should be stripped
        // The key assertion: no SENTINEL should appear
        assertFalse("exportBackup(excludeSecrets=true) must not contain sentinel", jsonString.contains(SENTINEL))
        
        // MCP trust should be ASK after redaction
        assertTrue("exportBackup(excludeSecrets=true) should have trust=ASK",
            jsonString.contains("\"trust\":\"ASK\""))
    }

    @Test fun `exportBackup() with flag false contains credentials`() {
        // Create manager and add an MCP server with sentinel values
        val manager = SettingsManager(FakeAppEnv(), InMemoryStore(), NoImageCodec)
        manager.saveMcpServers(listOf(McpServerConfig(
            id = "test-mcp",
            name = "Test MCP",
            transport = McpTransportKind.STDIO,
            command = "touch",
            args = listOf("--api-key", SENTINEL, "--token", SENTINEL),
            env = mapOf("API_KEY" to SENTINEL, "SECRET" to SENTINEL),
            headers = mapOf("X-API-Key" to SENTINEL),
            url = "http://user:pass@mcp.local/api",
            enabled = true,
            trust = McpTrust.TRUSTED
        )))

        val jsonString = manager.exportBackup()  // default: excludeSecrets = false

        // When flag false, sentinels should be present (no redaction)
        assertTrue("exportBackup() with flag false must contain sentinel", jsonString.contains(SENTINEL))
        
        // MCP trust should be ASK (always mapped during export)
        assertTrue("exportBackup(false) should have trust=ASK",
            jsonString.contains("\"trust\":\"ASK\""))
    }

    @Test fun `exportBackup preserves MCP trust info`() {
        // Create manager and add an MCP server with TRUSTED status
        val manager = SettingsManager(FakeAppEnv(), InMemoryStore(), NoImageCodec)
        manager.saveMcpServers(listOf(McpServerConfig(
            id = "test-mcp",
            name = "Test MCP",
            transport = McpTransportKind.STDIO,
            command = "touch",
            args = emptyList(),
            env = emptyMap(),
            headers = emptyMap(),
            url = "",
            enabled = true,
            trust = McpTrust.TRUSTED
        )))

        val jsonString = manager.exportBackup(excludeSecrets = false)

        // The export should contain MCP trust information
        // Note: exportBackup always maps MCP trust to ASK regardless of the flag
        assertTrue("exportBackup should contain trust field", jsonString.contains("\"trust\":"))
    }
}
