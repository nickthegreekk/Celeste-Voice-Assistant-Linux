package com.nikosm.voiceassistant.linux

import com.nikosm.voiceassistant.AppLogger
import com.nikosm.voiceassistant.McpServerConfig
import com.nikosm.voiceassistant.McpTransport
import com.nikosm.voiceassistant.McpTransportException
import com.nikosm.voiceassistant.StderrLogger
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * stdio transport: the MCP server is a child process, and the protocol is newline-delimited JSON
 * on its stdin and stdout (PLAN.md §10/M9). `ProcessBuilder` lives here because AGENTS.md §4 says
 * the machine's process API belongs to `:platform`.
 *
 * ## What this class does not do
 *
 * It does not parse anything. A line is forwarded as text to [start]'s callback and the registry
 * in `:core` decides whether it is a JSON-RPC message — the seam's contract is a string per
 * message, which is also what keeps `org.json` out of this module. The only line this class
 * interprets is a **blank** one, which the framing gives no meaning to and is dropped.
 *
 * ## stderr is the debug log, and only that
 *
 * An MCP server's stderr is its own logging, and it can carry the contents it was asked about. It
 * is written at debug level, so `CELESTE_DEBUG=1` shows it and an ordinary run discards it —
 * which is the same fail-closed choice the application makes for its own payload dumps.
 *
 * ## Lifecycle
 *
 * [start] spawns the process and two daemon readers. [close] closes the child's stdin, asks it to
 * end, and force-kills it after two seconds; a server that ignores both does not outlive us.
 * [onClosed] fires exactly once, whether the server died, the pipe broke, or we closed it.
 */
class McpStdioTransport(
    private val server: McpServerConfig,
    private val logger: AppLogger = StderrLogger,
) : McpTransport {

    @Volatile
    private var process: Process? = null

    @Volatile
    private var writer: BufferedWriter? = null

    @Volatile
    private var onClosed: ((String) -> Unit)? = null

    /**
     * Serializes writers, and **only** writers.
     *
     * [close] deliberately does not take this monitor: a send can block forever inside
     * `writer.flush()` when the server stops reading its stdin, and a close that waited for the
     * monitor would never reach `process.destroy()` — which is what closes the other end of the
     * pipe and breaks the blocked write.
     */
    private val sendLock = Any()

    /** One report per lifetime, whichever side ends it first. */
    private val reported = AtomicBoolean(false)

    override fun start(onMessage: (String) -> Unit, onClosed: (String) -> Unit) {
        this.onClosed = onClosed
        val commandLine = listOf(server.command) + server.args
        val builder = ProcessBuilder(commandLine)
        server.workingDir?.takeIf { it.isNotBlank() }?.let { builder.directory(File(it)) }
        if (server.env.isNotEmpty()) builder.environment().putAll(server.env)

        val started = try {
            builder.start()
        } catch (e: IOException) {
            throw McpTransportException("could not start '${commandLine.first()}': ${e.message}", e)
        }
        process = started
        writer = BufferedWriter(OutputStreamWriter(started.outputStream, Charsets.UTF_8))

        Thread({ readOutput(started, onMessage) }, "mcp-stdio-${server.id}").apply {
            isDaemon = true
            start()
        }
        Thread({ readErrors(started) }, "mcp-stderr-${server.id}").apply {
            isDaemon = true
            start()
        }
    }

    private fun readOutput(process: Process, onMessage: (String) -> Unit) {
        var why = "the server closed its output"
        try {
            process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank()) onMessage(line)
                }
            }
        } catch (e: IOException) {
            why = "reading from the server failed: ${e.message}"
        } finally {
            report(why)
        }
    }

    private fun readErrors(process: Process) {
        try {
            process.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                if (line.isNotBlank()) logger.d(TAG, "[${server.id}] $line")
            }
        } catch (_: IOException) {
            // The process ended; there is nothing left to read.
        }
    }

    override fun send(message: String) {
        // Fail fast when the transport is already gone: a wedged send may still hold [sendLock].
        writer ?: throw McpTransportException("the server '${server.name}' is not running")
        try {
            synchronized(sendLock) {
                // Re-read under the lock: close clears the field to make a queued send answer
                // "not running" instead of writing into a writer it already closed.
                val target = writer ?: throw McpTransportException("the server '${server.name}' is not running")
                target.write(message)
                target.newLine()
                target.flush()
            }
        } catch (e: IOException) {
            throw McpTransportException("could not write to '${server.name}': ${e.message}", e)
        }
    }

    /**
     * Closes the child and refuses further sends.
     *
     * **The process is destroyed before anything touches the writer**, and the writer is never
     * closed here. A send can block forever inside `writer.flush()` when the server stops reading
     * its stdin; `BufferedWriter` holds its own monitor for that call, so a `writer.close()` from
     * this thread would wait behind it. Destroying the process closes the other end of the pipe,
     * which makes the blocked flush fail and releases the writer — and the connection can then be
     * recycled. The wait stays outside every lock and is bounded at two seconds.
     */
    override fun close() {
        val process = this.process
        this.writer = null
        this.process = null
        if (process != null) {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        report("the transport was closed")
    }

    private fun report(why: String) {
        if (reported.compareAndSet(false, true)) onClosed?.invoke(why)
    }

    private companion object {
        const val TAG = "McpStdio"
    }
}
