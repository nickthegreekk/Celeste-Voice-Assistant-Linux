package com.nikosm.voiceassistant.linux

import com.nikosm.voiceassistant.AppLogger
import com.nikosm.voiceassistant.CommandRunRequest
import com.nikosm.voiceassistant.CommandRunResult
import com.nikosm.voiceassistant.CommandRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Terminal commands on Linux (PLAN.md §10/M16).
 *
 * `ProcessBuilder` lives here because AGENTS.md §4 says it may live nowhere else. One command, run
 * by `/bin/sh -c` in the working directory the approval dialog showed, with:
 *
 *  - **stdin closed** — an interactive prompt must fail or time out rather than wait forever;
 *  - **stderr merged into stdout**, so the model reads one ordered transcript of what happened;
 *  - **output capped while still draining** — the pipe must keep flowing or the child blocks on a
 *    full buffer, so bytes past the cap are read and discarded; `truncated` says they existed;
 *  - **the process tree killed on timeout** — `npm run dev` starts children, and killing only the
 *    shell leaves them running with the application's file descriptors and a port still bound.
 *
 * The command text is never logged: an argument can carry a secret, the audit file is the record
 * (`CommandAudit.kt`), and this line would be one more copy in journald.
 */
class LinuxCommandRunner(
    private val logger: AppLogger,
) : CommandRunner {

    override suspend fun run(request: CommandRunRequest): CommandRunResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val process = ProcessBuilder("/bin/sh", "-c", request.command)
            .directory(request.workingDirectory.toFile())
            .redirectErrorStream(true)
            .start()
        try {
            // Feed the optional input, then close the pipe. A command given no input sees EOF
            // immediately instead of hanging, and one given input can answer a single prompt.
            // The input is capped by the tool layer well below the pipe buffer, so this write
            // cannot block before the reader starts.
            val stdin = request.stdin
            runCatching {
                if (stdin.isNullOrEmpty()) {
                    process.outputStream.close()
                } else {
                    process.outputStream.use { input ->
                        input.write(stdin.toByteArray(Charsets.UTF_8))
                        input.flush()
                    }
                }
            }

            // The reader runs beside waitFor: a single-threaded "wait then read" deadlocks the
            // moment the child fills the pipe buffer.
            val reader = async(Dispatchers.IO) { readCapped(process, request.maxOutputBytes) }

            val finished = process.waitFor(request.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            if (!finished) {
                logger.i("CommandRunner", "command timed out after ${request.timeoutSeconds}s — killing it")
                killTree(process)
            }
            // Reap the process so the exit code is available and the child is fully gone.
            process.waitFor()

            // A background grandchild (`sleep 100 &`) inherits stdout and can hold the pipe open
            // past the shell's exit. Give the reader a moment to see EOF; if it does not, close the
            // stream — the reader treats the resulting IOException as the end of the output.
            val drained = withTimeoutOrNull(500) { reader.await() }
            val (bytes, truncated) = drained ?: run {
                runCatching { process.inputStream.close() }
                reader.await()
            }
            val duration = System.currentTimeMillis() - started
            logger.d(
                "CommandRunner",
                "command finished in ${duration}ms (exit=${if (finished) process.exitValue() else null}, " +
                    "timedOut=${!finished}, ${bytes.size} bytes, truncated=$truncated)"
            )
            CommandRunResult(
                exitCode = if (finished) process.exitValue() else null,
                output = String(bytes, Charsets.UTF_8),
                timedOut = !finished,
                truncated = truncated,
            )
        } finally {
            // Cancellation (Stop, a superseded turn) throws out of waitFor/await; the command must
            // not outlive the turn that started it.
            if (process.isAlive) killTree(process)
        }
    }

    /**
     * Read at most [cap] bytes, discarding the rest so the pipe drains, and report whether anything
     * was discarded. An IOException here means the stream was closed under us (the grandchild
     * case), which is a normal end of output, not a failure.
     */
    private fun readCapped(process: Process, cap: Int): Pair<ByteArray, Boolean> {
        val kept = ByteArrayOutputStream(minOf(cap, 64 * 1024))
        var stored = 0
        var truncated = false
        val buffer = ByteArray(8 * 1024)
        try {
            process.inputStream.use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    val room = cap - stored
                    if (room > 0) {
                        val take = minOf(room, read)
                        kept.write(buffer, 0, take)
                        stored += take
                        if (take < read) truncated = true
                    } else {
                        truncated = true
                    }
                }
            }
        } catch (_: IOException) {
            // The stream was closed after the process tree was reaped; what we have is the output.
        }
        return kept.toByteArray() to truncated
    }

    private fun killTree(process: Process) {
        // Descendants first: if the shell dies first, the children are reparented and the snapshot
        // is all a later walk would have.
        runCatching { process.descendants().forEach { it.destroyForcibly() } }
        runCatching { process.destroyForcibly() }
    }
}
