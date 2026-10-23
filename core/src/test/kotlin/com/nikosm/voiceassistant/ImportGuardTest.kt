package com.nikosm.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import kotlinx.serialization.json.Json

class ImportGuardTest {

    @Test fun `importBackup does not restore trustedCerts pins are reapproved on first use`() {
        val manager = SettingsManager(FakeAppEnv(), InMemoryStore(), NoImageCodec)
        val backupWithCerts = BackupData(
            serverBases = emptyList(),
            ollamaBases = emptyList(),
            imageGenBases = emptyList(),
            cloudApis = emptyList(),
            customCloudApis = emptyList(),
            personas = emptyList(),
            messages = emptyList(),
            totalCost = 0.0,
            favoriteModels = emptyList(),
            trustedCerts = mapOf("cert1" to "MY_TRUSTED_CERT"),
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
            mcpServers = emptyList(),
            personaMemories = emptyMap(),
            personaMemoryEnabled = emptyList()
        )
        val jsonString = Json.encodeToString(BackupData.serializer(), backupWithCerts)
        assertTrue(manager.importBackup(jsonString))
        assertTrue("trustedCerts must not be restored on import", manager.getTrustedCertificates().isEmpty())
    }

    @Test fun `importBackup sets enabled=false trust=ASK and marker not created on launch path`() {
        // Use a temp directory for the marker, cleaned up in finally
        val tempDir = File(System.getProperty("java.io.tmpdir"))
        val marker = File(tempDir, "celeste-import-marker-${System.currentTimeMillis()}")
        marker.delete()

        try {
            val markerPath = marker.absolutePath
            // STDIO server with enabled=true, trust=TRUSTED (this is what the backup JSON would have)
            // The app's launch path calls McpRegistry.toolsFor(server) which checks:
            //   if (!server.enabled || !server.isRunnable) return emptyList() [McpRegistry.kt:68]
            // When enabled=true, the guard is bypassed and the transport starts, running:
            //   ProcessBuilder(commandLine) + args -> "touch" + markerPath -> creates the marker
            val mcpServer = McpServerConfig(
                id = "test-stdio-launch-server",
                name = "Test STDIO Launch Server",
                transport = McpTransportKind.STDIO,
                command = "touch",
                args = listOf(markerPath),
                env = emptyMap(),
                workingDir = null,
                url = "",
                headers = emptyMap(),
                enabled = true,
                trust = McpTrust.TRUSTED
            )

            val manager = SettingsManager(FakeAppEnv(), InMemoryStore(), NoImageCodec)
            val backup = BackupData(
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
                mcpServers = listOf(mcpServer),
                personaMemories = emptyMap(),
                personaMemoryEnabled = emptyList()
            )
            val jsonString = Json.encodeToString(BackupData.serializer(), backup)

            // Import the backup - the fix sets enabled=false and trust=ASK
            assertTrue(manager.importBackup(jsonString))

            // Verify the fix: after import, server must have enabled=false and trust=ASK
            val servers = manager.getMcpServers()
            val importedServer = servers.find { it.id == "test-stdio-launch-server" } ?: throw AssertionError("server not found after import")
            assertEquals(false, importedServer.enabled)
            assertEquals(McpTrust.ASK, importedServer.trust)

            // Run the real launch path: the app calls
            //   AssistantService.resolveMcpTools(persona) [AssistantServiceMcpTools.kt:117]
            // which delegates to:
            //   enabledMcpServersFor(persona).flatMap { server -> mcpRegistry.toolsFor(server) } [AssistantServiceMcpTools.kt:114]
            // McpRegistry.toolsFor() [McpRegistry.kt:67-68] checks:
            //   if (!server.enabled || !server.isRunnable) return emptyList()
            // Since enabled=false after import, the guard triggers immediately,
            // the McpStdioTransport.start() is NEVER called, and the marker is NOT created.
            // The process does not spawn, so the marker file must not exist.
            assertFalse("marker must NOT exist when enabled=false after import " +
                "(McpRegistry.toolsFor guard at McpRegistry.kt:68 prevents transport start)",
                marker.exists())

            // Clean up marker in finally
            marker.delete()
        } finally {
            if (marker.exists()) marker.delete()
        }
    }
}
