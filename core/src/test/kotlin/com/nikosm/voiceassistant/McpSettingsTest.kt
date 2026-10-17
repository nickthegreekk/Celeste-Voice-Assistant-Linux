package com.nikosm.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP configuration in the settings file (PLAN.md §10/M9): the server list, per-persona
 * enablement, the trust rule, and what a backup may and may not carry.
 *
 * The trust rules are the security-relevant half: a grant survives a rename and an on/off switch,
 * **dies when the command, arguments, environment, working directory or URL changes** — that is a
 * different server — and **never travels in a backup**, because a restore must not grant the right
 * to run tools on this machine.
 */
class McpSettingsTest {

    private val store = InMemoryStore()
    private val manager = SettingsManager(FakeAppEnv(), store, NoImageCodec)

    private fun server(id: String = "home", trust: McpTrust = McpTrust.ASK) = McpServerConfig(
        id = id,
        name = "Home",
        transport = McpTransportKind.STDIO,
        command = "npx",
        args = listOf("server"),
        env = mapOf("TOKEN" to "secret"),
        trust = trust,
    )

    @Test
    fun `a fresh installation has no servers and no enablement`() {
        assertTrue(manager.getMcpServers().isEmpty())
        assertTrue(manager.getMcpServersForPersona("Celeste").isEmpty())
    }

    @Test
    fun `servers round-trip with their transport details`() {
        manager.saveMcpServers(listOf(server()))

        val restored = manager.getMcpServers().single()
        assertEquals("home", restored.id)
        assertEquals(McpTransportKind.STDIO, restored.transport)
        assertEquals(listOf("server"), restored.args)
        assertEquals(mapOf("TOKEN" to "secret"), restored.env)
    }

    @Test
    fun `unreadable JSON reads as no servers rather than a failed start`() {
        store.edit().putString("mcp_servers_v1", "{ not json").commit()
        store.edit().putString("mcp_persona_Celeste", "also not json").commit()

        assertTrue(manager.getMcpServers().isEmpty())
        assertTrue(manager.getMcpServersForPersona("Celeste").isEmpty())
    }

    @Test
    fun `trust survives an unrelated edit and dies when the connection changes`() {
        manager.saveMcpServers(listOf(server(trust = McpTrust.TRUSTED)))

        // A rename and an enabled flip are not a different server.
        manager.saveMcpServers(manager.getMcpServers().map { it.copy(name = "Home 2", enabled = false) })
        assertEquals(McpTrust.TRUSTED, manager.getMcpServers().single().trust)

        manager.saveMcpServers(manager.getMcpServers().map { it.copy(enabled = true) })
        assertEquals(McpTrust.TRUSTED, manager.getMcpServers().single().trust)

        // The command is: the grant was for the program the user approved.
        manager.saveMcpServers(manager.getMcpServers().map { it.copy(command = "something-else") })
        assertEquals(McpTrust.ASK, manager.getMcpServers().single().trust)
    }

    @Test
    fun `a changed URL also revokes an HTTP server's trust`() {
        val http = server(trust = McpTrust.TRUSTED).copy(
            transport = McpTransportKind.HTTP,
            command = "",
            args = emptyList(),
            env = emptyMap(),
            url = "http://127.0.0.1:9000/mcp",
        )
        manager.saveMcpServers(listOf(http))
        assertEquals(McpTrust.TRUSTED, manager.getMcpServers().single().trust)

        manager.saveMcpServers(manager.getMcpServers().map { it.copy(url = "http://192.0.2.2:9000/mcp") })

        assertEquals(McpTrust.ASK, manager.getMcpServers().single().trust)
    }

    @Test
    fun `one server's trust can be granted without touching the others`() {
        manager.saveMcpServers(listOf(server("home"), server("notes")))

        manager.saveMcpTrust("notes", McpTrust.TRUSTED)

        assertEquals(McpTrust.ASK, manager.getMcpServers().single { it.id == "home" }.trust)
        assertEquals(McpTrust.TRUSTED, manager.getMcpServers().single { it.id == "notes" }.trust)
    }

    @Test
    fun `per-persona enablement round-trips, migrates on rename and dies with the persona`() {
        manager.saveMcpServersForPersona("A", listOf("home", "home", "notes"))
        assertEquals(listOf("home", "notes"), manager.getMcpServersForPersona("A"))

        manager.migratePersonaMcp("A", "B")
        assertTrue(manager.getMcpServersForPersona("A").isEmpty())
        assertEquals(listOf("home", "notes"), manager.getMcpServersForPersona("B"))

        manager.removePersonaMcp("B")
        assertTrue(manager.getMcpServersForPersona("B").isEmpty())
    }

    @Test
    fun `a backup carries the definitions but never the trust`() {
        manager.saveMcpServers(listOf(server(trust = McpTrust.TRUSTED)))

        val exported = manager.exportBackup()
        val restoredManager = SettingsManager(FakeAppEnv(), InMemoryStore(), NoImageCodec)
        assertTrue(restoredManager.importBackup(exported))

        val restored = restoredManager.getMcpServers().single()
        assertEquals("npx", restored.command)
        assertEquals(McpTrust.ASK, restored.trust)
    }
}
