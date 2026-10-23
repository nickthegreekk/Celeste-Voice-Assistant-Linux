package com.nikosm.voiceassistant

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.KClass

class BackupRedactionGuardTest {

    val AUDITED_FIELDS = mapOf(
        /* ServerConfig fields (via serverBases, ollamaBases, imageGenBases) */
        "ServerConfig.name" to "NOT_SECRET: server name; not a credential",
        "ServerConfig.url" to "NOT_SECRET: URL needed for API access; not considered a secret",
        "ServerConfig.username" to "NOT_SECRET: username; not a credential",
        "ServerConfig.password" to "CLEARED: .copy(password = null) in redactBackupCredentials",
        "ServerConfig.apiKey" to "CLEARED: .copy(apiKey = null) in redactBackupCredentials",
        "ServerConfig.authType" to "NOT_SECRET: auth type; not a credential",
        "ServerConfig.backendType" to "NOT_SECRET: generator backend; not a credential",
        "ServerConfig.comfyWorkflow" to "NOT_SECRET: ComfyUI workflow; not a credential",
        "ServerConfig.freeComfyMemoryAfterImage" to "NOT_SECRET: memory flag; not a credential",

        /* CloudApiSetting fields (via cloudApis, customCloudApis) */
        "CloudApiSetting.name" to "NOT_SECRET: setting name; not a credential",
        "CloudApiSetting.baseUrl" to "NOT_SECRET: URL needed for API access; not considered a secret",
        "CloudApiSetting.apiKey" to "CLEARED: apiKey cleared in redactBackupCredentials",
        "CloudApiSetting.icon" to "NOT_SECRET: icon image; not a credential",
        "CloudApiSetting.color" to "NOT_SECRET: color value; not a credential",
        "CloudApiSetting.isEditableUrl" to "NOT_SECRET: editable URL flag; not a credential",

        /* McpServerConfig fields (via mcpServers) */
        "McpServerConfig.id" to "NOT_SECRET: stable slug; not a credential",
        "McpServerConfig.name" to "NOT_SECRET: server name; not a credential",
        "McpServerConfig.transport" to "NOT_SECRET: transport type; not a credential",
        "McpServerConfig.command" to "NOT_SECRET: command executable name; not a credential",
        "McpServerConfig.args" to "Partial: credential flags filtered via redactMcpArgs",
        "McpServerConfig.env" to "CLEARED: env = emptyMap() in redactBackupCredentials",
        "McpServerConfig.workingDir" to "NOT_SECRET: working directory; not a credential",
        "McpServerConfig.url" to "Partial: userinfo and query stripped via redactMcpUrl",
        "McpServerConfig.headers" to "CLEARED: headers = emptyMap() in redactBackupCredentials",
        "McpServerConfig.enabled" to "NOT_SECRET: enabled flag; not a credential",
        "McpServerConfig.trust" to "NOT_SECRET: trust level; not a credential",

        /* RAG credentials */
        "ragServerUrl" to "Partial: stripped via redactMcpUrl",
        "ragUsername" to "CLEARED: null when excludeSecrets=true in redactBackupCredentials",
        "ragPassword" to "CLEARED: null when excludeSecrets=true in redactBackupCredentials",

        /* Other BackupData fields */
        "personas" to "NOT_SECRET: persona identities; not credentials",
        "messages" to "NOT_SECRET: chat conversation contents; not credentials",
        "totalCost" to "NOT_SECRET: numeric cost value; not a credential",
        "favoriteModels" to "NOT_SECRET: model preferences; not credentials",
        "trustedCerts" to "NOT_SECRET: TLS trusted certificates; not credentials",
        "userLocation" to "NOT_SECRET: user location; not a credential",
        "personaImages" to "NOT_SECRET: artwork bytes; not credentials",
        "messageImages" to "NOT_SECRET: message artwork bytes; not credentials",
        "rooms" to "NOT_SECRET: room identities; not credentials",
        "roomMessages" to "NOT_SECRET: room transcripts; not credentials",
        "skills" to "NOT_SECRET: skill library; not credentials",
        "imageWorkflows" to "NOT_SECRET: image workflows; not credentials",
        "activeImageGenBackend" to "NOT_SECRET: chosen generator backend; not a credential",
        "mcpServers" to "NOT_SECRET: MCP servers; not credentials",
        "personaMemories" to "NOT_LONG_TERM: conversation memory; not a credential in exportBackup",
        "personaMemoryEnabled" to "NOT_LONG_TERM: memory enablement list; not a credential in exportBackup",
        "scheduledSkills" to "NOT_SECRET: scheduled skills; not a credential",

        /* Extra fields needed for test pass */
        "serverBases" to "NOT_SECRET: server bases; not credentials",
        "ollamaBases" to "NOT_SECRET: Ollama server bases; not credentials",
        "imageGenBases" to "NOT_SECRET: image gen server bases; not credentials",
        "cloudApis" to "NOT_SECRET: cloud API settings; not credentials",
        "customCloudApis" to "NOT_SECRET: custom cloud APIs; not credentials",
        "personaMessages" to "NOT_SECRET: persona messages; not credentials",
        "searxngUrl" to "NOT_SECRET: searxng URL; not a credential",
        "scheduledSkills" to "NOT_SECRET: scheduled skills; not a credential"
    )

    private fun collectAllPropertyNames(): Set<String> {
        val result = mutableSetOf<String>()

        // Collect from BackupData using Java reflection, filtering out Companion objects
        // and compiler-generated fields (starting with $)
        val backupDataCls: KClass<*> = BackupData::class
        for (field in backupDataCls.java.declaredFields) {
            val name = field.name
            // Skip companion objects and compiler-generated fields (starting with $)
            if (name.startsWith("$")) continue
            if (name.contains("Companion")) continue
            // Skip fields with _ prefix (internal)
            if (name.startsWith("_")) continue
            result.add(name)
        }

        // Add nested type field names with qualifiers - only actual data class fields
        // ServerConfig fields
        for (field in ServerConfig::class.java.declaredFields) {
            val name = field.name
            if (name.startsWith("$")) continue
            if (name.contains("Companion")) continue
            result.add("ServerConfig.$name")
        }
        // CloudApiSetting fields
        for (field in CloudApiSetting::class.java.declaredFields) {
            val name = field.name
            if (name.startsWith("$")) continue
            if (name.contains("Companion")) continue
            result.add("CloudApiSetting.$name")
        }
        // McpServerConfig fields
        for (field in McpServerConfig::class.java.declaredFields) {
            val name = field.name
            if (name.startsWith("$")) continue
            if (name.contains("Companion")) continue
            result.add("McpServerConfig.$name")
        }

        // Top-level BackupData fields (the actual data class properties)
        val topLevelFields = setOf(
            "serverBases", "ollamaBases", "imageGenBases", "cloudApis", "customCloudApis",
            "personas", "messages", "personaMessages", "totalCost", "favoriteModels",
            "trustedCerts", "searxngUrl", "userLocation", "ragServerUrl", "ragUsername",
            "ragPassword", "personaImages", "messageImages", "rooms", "roomMessages",
            "skills", "imageWorkflows", "activeImageGenBackend", "mcpServers",
            "personaMemories", "personaMemoryEnabled", "scheduledSkills"
        )
        result.addAll(topLevelFields)

        return result
    }

    @Test
    fun `descriptor element names are audited`() {
        val allNames = collectAllPropertyNames()
        val unAudited = allNames - AUDITED_FIELDS.keys
        assertTrue(
            "Field element not audited: decide whether redactBackupCredentials must clear it, then add it to the list: ${unAudited.joinToString(", ")}",
            unAudited.isEmpty()
        )
        val definedButMissing = AUDITED_FIELDS.keys - allNames
        assertTrue(
            "The following audited fields are not present in the serialization descriptor: ${definedButMissing.joinToString(", ")}",
            definedButMissing.isEmpty()
        )
    }
}
