package com.nikosm.voiceassistant

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a restored backup may and may not do: it never restores certificate pins, and an imported
 * MCP server comes back disabled and untrusted, so nothing it names can start. The registry is
 * driven with a counting factory, so no process is ever launched.
 */
class ImportGuardTest {

    private fun server(enabled: Boolean, trust: McpTrust) = McpServerConfig(
        id = "import-guard",
        name = "Import guard",
        transport = McpTransportKind.STDIO,
        command = "touch",
        args = listOf("/tmp/never-run"),
        enabled = enabled,
        trust = trust,
    )

    private fun backupWith(mcp: List<McpServerConfig>) = BackupData(
        serverBases = emptyList(),
        ollamaBases = emptyList(),
        imageGenBases = emptyList(),
        cloudApis = emptyList(),
        customCloudApis = emptyList(),
        personas = emptyList(),
        messages = emptyList(),
        totalCost = 0.0,
        favoriteModels = emptyList(),
        trustedCerts = emptyMap(),
        searxngUrl = null,
        userLocation = null,
        ragServerUrl = null,
        ragUsername = null,
        ragPassword = null,
        rooms = emptyList(),
        roomMessages = emptyMap(),
        skills = emptyList(),
        imageWorkflows = emptyList(),
        activeImageGenBackend = ImageGenBackend.A1111,
        mcpServers = mcp,
        personaMemories = emptyMap(),
        personaMemoryEnabled = emptyList(),
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

    private fun toolsPage(names: List<String>): JSONObject = JSONObject().put(
        "tools",
        JSONArray().apply {
            names.forEach { put(JSONObject().put("name", it).put("description", "about $it")) }
        },
    )

    /** A transport that answers the handshake and one tool, in memory. */
    private class FakeTransport(
        private val handler: (JSONObject) -> JSONObject?,
    ) : McpTransport {
        private var onMessage: ((String) -> Unit)? = null

        override fun start(onMessage: (String) -> Unit, onClosed: (String) -> Unit) {
            this.onMessage = onMessage
        }

        override fun send(message: String) {
            val request = JSONObject(message)
            if (!request.has("id")) return
            handler(request)?.let { onMessage?.invoke(it.toString()) }
        }

        override fun close() {}
    }

    /** Counts how often the registry asks for a transport. Nothing is ever launched. */
    private inner class CountingFactory : McpTransportFactory {
        var opens = 0
        override fun open(server: McpServerConfig): McpTransport {
            opens++
            return FakeTransport { request ->
                val id = request.getLong("id")
                when (request.getString("method")) {
                    "initialize" -> result(id, initResult())
                    "tools/list" -> result(id, toolsPage(listOf("echo")))
                    else -> failure(id, -32601, "unknown")
                }
            }
        }
    }

    @Test fun `import does not restore trusted cert pins`() {
        val manager = SettingsManager(FakeAppEnv(), InMemoryStore(), NoImageCodec)
        val backup = backupWith(emptyList()).copy(trustedCerts = mapOf("cert1" to "MY_TRUSTED_CERT"))
        assertTrue(manager.importBackup(Json.encodeToString(BackupData.serializer(), backup)))
        assertTrue("pins must be re-approved on first use, never restored", manager.getTrustedCertificates().isEmpty())
    }

    @Test fun `control - an enabled stdio server reaches the transport factory`() = runTest {
        val factory = CountingFactory()
        val registry = McpRegistry(factory, connectTimeoutMillis = 2_000, callTimeoutMillis = 2_000)
        try {
            registry.toolsFor(server(enabled = true, trust = McpTrust.ASK))
            assertEquals("an enabled server must open a transport", 1, factory.opens)
        } finally {
            registry.close()
        }
    }

    @Test fun `an imported enabled and trusted server is restored disabled and never reaches the transport`() = runTest {
        val manager = SettingsManager(FakeAppEnv(), InMemoryStore(), NoImageCodec)
        val hostile = backupWith(listOf(server(enabled = true, trust = McpTrust.TRUSTED)))
        assertTrue(manager.importBackup(Json.encodeToString(BackupData.serializer(), hostile)))

        val stored = manager.getMcpServers().single()
        assertFalse("import must restore the server disabled", stored.enabled)
        assertEquals("import must restore the server untrusted", McpTrust.ASK, stored.trust)

        val factory = CountingFactory()
        val registry = McpRegistry(factory, connectTimeoutMillis = 2_000, callTimeoutMillis = 2_000)
        try {
            registry.toolsFor(stored)
            assertEquals("a restored server must not reach the transport", 0, factory.opens)
        } finally {
            registry.close()
        }
    }
}
