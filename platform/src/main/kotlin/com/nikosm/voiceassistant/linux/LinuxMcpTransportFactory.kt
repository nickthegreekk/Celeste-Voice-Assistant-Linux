package com.nikosm.voiceassistant.linux

import com.nikosm.voiceassistant.AppLogger
import com.nikosm.voiceassistant.McpServerConfig
import com.nikosm.voiceassistant.McpTransport
import com.nikosm.voiceassistant.McpTransportFactory
import com.nikosm.voiceassistant.McpTransportKind
import com.nikosm.voiceassistant.StderrLogger

/**
 * The Linux transports for MCP servers: a child process for stdio, OkHttp for Streamable HTTP.
 *
 * It is a factory rather than two constructors at the call site so that `:core` can hold the
 * choice in one field and a test can substitute a fake without a process or a socket.
 */
class LinuxMcpTransportFactory(
    private val logger: AppLogger = StderrLogger,
) : McpTransportFactory {

    override fun open(server: McpServerConfig): McpTransport = when (server.transport) {
        McpTransportKind.STDIO -> McpStdioTransport(server, logger)
        McpTransportKind.HTTP -> McpHttpTransport(server, logger)
    }
}
