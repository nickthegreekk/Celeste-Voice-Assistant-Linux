package com.nikosm.voiceassistant

import kotlinx.serialization.Serializable

/**
 * The process and network half of MCP, split the way §5's seams are split — `:core` must not know
 * what a child process or a socket is. It is not an entry in §5's table, which is about places
 * *Android* provided a platform (PLAN.md §10/M9); the rule it follows is the same one. The bytes
 * are the contract and they are **strings**: one JSON-RPC message per string, in both directions.
 *
 * ## Why strings, and not `JSONObject`
 *
 * `org.json` is an `implementation` dependency of `:core`, so :platform cannot see it — and that
 * boundary is the point of the rule in `platform/build.gradle.kts`: a module that uses a library
 * says so. A transport does not need to understand a message to carry it, so the seam carries the
 * message's text and [McpRegistry] (in `:core`) is the only place that parses.
 */

/** How a configured MCP server is reached. */
@Serializable
enum class McpTransportKind {
    /** A child process: `command` + `args`, newline-delimited JSON-RPC on stdio. */
    STDIO,

    /** Streamable HTTP: JSON-RPC POSTed to `url`, answers read as JSON or SSE. */
    HTTP,
}

/**
 * How much a server is trusted, remembered between restarts.
 *
 * [ASK] is the default and the only safe first answer: the first tool call raises a confirmation.
 * [TRUSTED] is granted by that confirmation's "always allow" and is revoked from the same screen.
 * There is no [DENIED] value on purpose — denying is an answer to one call, and a server the user
 * never wants is a server they remove.
 */
@Serializable
enum class McpTrust { ASK, TRUSTED }

/**
 * One configured MCP server, as the settings file stores it.
 *
 * It is a plain data class with defaults so a settings blob written by an earlier build decodes
 * without every field; kotlinx.serialization gives [McpTrust.ASK] and [McpTransportKind.STDIO] to
 * anything absent, which is the fail-closed direction for a capability that runs programs.
 *
 * **Secrets live in `env` and `headers`**, and therefore in the settings file at mode 0600 — the
 * same place the provider API keys live, and the same reason they travel in a backup. [trust] is
 * not among them: it is a permission to run tools on this machine, and a restored backup must not
 * confer one (PLAN.md §10/M9).
 */
@Serializable
data class McpServerConfig(
    /** Stable slug, also half of every tool name this server contributes. */
    val id: String,
    val name: String,
    val transport: McpTransportKind = McpTransportKind.STDIO,
    /** stdio: the executable. */
    val command: String = "",
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val workingDir: String? = null,
    /** HTTP: the endpoint. */
    val url: String = "",
    val headers: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val trust: McpTrust = McpTrust.ASK,
) {
    /** Whether this entry has what its transport needs to start at all. */
    val isRunnable: Boolean
        get() = when (transport) {
            McpTransportKind.STDIO -> command.isNotBlank()
            McpTransportKind.HTTP -> url.isNotBlank()
        }
}

/** The transport could not start, or is gone. The message is written for the log, not the model. */
class McpTransportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * One live connection to an MCP server. Messages are complete JSON-RPC objects, as text.
 *
 * [start] is called exactly once. [onMessage] may be called from any thread the transport owns —
 * the registry marshals — and [onClosed] is called once when the transport dies, with a sentence
 * for the log. [send] must be safe to call from another thread, and must fail with
 * [McpTransportException] rather than throwing an `IOException` from under the caller.
 */
interface McpTransport : AutoCloseable {

    fun start(onMessage: (String) -> Unit, onClosed: (String) -> Unit)

    fun send(message: String)

    override fun close()
}

/** Opens transports for configured servers. The implementation lives in `:platform`. */
interface McpTransportFactory {

    /** @throws McpTransportException when the transport cannot be created or started. */
    fun open(server: McpServerConfig): McpTransport

    companion object {
        /**
         * What a build with no platform transport gets: every open fails in words. Used as the
         * default in `AssistantService`, so a test that never configures MCP cannot reach a
         * process or a socket by accident.
         */
        val NONE: McpTransportFactory = object : McpTransportFactory {
            override fun open(server: McpServerConfig): McpTransport =
                throw McpTransportException("no MCP transport is installed in this build")
        }
    }
}
