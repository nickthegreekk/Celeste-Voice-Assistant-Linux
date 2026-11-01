package com.nikosm.voiceassistant.linux

import com.nikosm.voiceassistant.AppLogger
import com.nikosm.voiceassistant.COMMAND_TRUNCATION_MARKER
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
 *  - **stdin written once, then closed** — with no input an interactive prompt gets EOF instead of
 *    hanging; with input a single question can be answered;
 *  - **stderr merged into stdout**, so the model reads one ordered transcript of what happened;
 *  - **output capped while still draining** — the pipe must keep flowing or the child blocks on a
 *    full buffer. The cap keeps the beginning and the end; the middle is discarded and `truncated`
 *    says so, because a command's failure and its summary are usually the last thing it prints;
 *  - **the process tree killed on timeout** — `npm run dev` starts children, and killing only the
 *    shell leaves them running with the application's file descriptors and a port still bound;
 *  - **an optional pseudo-terminal** through util-linux's `script` (M16.2), which is in the base
 *    system on both target distributions. `-q` keeps its own header/footer out, `-e` returns the
 *    child's exit code, `-f` flushes. The output is a terminal stream — the tool layer strips its
 *    control codes before the model sees it.
 *
 * The command text is never logged: an argument can carry a secret, the audit file is the record
 * (`CommandAudit.kt`), and this line would be one more copy in journald.
 */
class LinuxCommandRunner(
    private val logger: AppLogger,
) : CommandRunner {

    override suspend fun run(request: CommandRunRequest): CommandRunResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val argv = if (request.tty) {
            listOf("script", "-q", "-e", "-f", "-c", request.command, "/dev/null")
        } else {
            listOf("/bin/sh", "-c", request.command)
        }
        val process = ProcessBuilder(argv)
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
     * Read at most [cap] bytes, keeping the beginning and the end and discarding the middle so the
     * pipe drains, and report whether anything was discarded. The middle is what goes: a command's
     * own failure and summary are usually the last thing it prints, and the previous head-only cap
     * gave the model the setup without the reason.
     *
     * An IOException here means the stream was closed under us (the grandchild case), which is a
     * normal end of output, not a failure.
     */
    private fun readCapped(process: Process, cap: Int): Pair<ByteArray, Boolean> {
        val marker = COMMAND_TRUNCATION_MARKER.toByteArray(Charsets.UTF_8)
        // A cap too small for the marker and a byte at each end keeps the beginning only, which is
        // what this did before the tail was kept.
        val budget = if (cap > marker.size + 2) cap - marker.size else 0
        val headBudget = if (budget > 0) budget / 2 else cap
        val tailBudget = if (budget > 0) budget - headBudget else 0
        val head = ByteArrayOutputStream(minOf(cap.coerceAtLeast(0), 64 * 1024))
        val tail = if (tailBudget > 0) ByteRing(tailBudget) else null
        var total = 0L
        val buffer = ByteArray(8 * 1024)
        try {
            process.inputStream.use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    var offset = 0
                    val room = cap - head.size()
                    if (room > 0) {
                        val take = minOf(room, read)
                        head.write(buffer, 0, take)
                        offset = take
                    }
                    if (offset < read) tail?.write(buffer, offset, read - offset)
                }
            }
        } catch (_: IOException) {
            // The stream was closed after the process tree was reaped; what we have is the output.
        }
        val kept = head.toByteArray()
        if (total <= cap) return kept to false
        if (tail == null || tail.size == 0) return kept to true
        val headCut = utf8CutBack(kept, headBudget)
        val tailBytes = tail.ordered()
        val tailStart = utf8StartForward(tailBytes)
        val out = ByteArray(headCut + marker.size + (tailBytes.size - tailStart))
        System.arraycopy(kept, 0, out, 0, headCut)
        System.arraycopy(marker, 0, out, headCut, marker.size)
        System.arraycopy(tailBytes, tailStart, out, headCut + marker.size, tailBytes.size - tailStart)
        return out to true
    }

    /**
     * The last [capacity] bytes written, in order, without keeping the bytes before them.
     *
     * `total` is a count, not an index: the write position is `total % capacity`, so once the ring
     * is full every write overwrites the oldest byte. [ordered] then reads from that position and
     * wraps, which is the order the bytes were received in.
     */
    private class ByteRing(private val capacity: Int) {
        private val buffer = ByteArray(capacity)
        private var total = 0L

        val size: Int get() = minOf(total, capacity.toLong()).toInt()

        fun write(source: ByteArray, offset: Int, length: Int) {
            var at = offset
            var remaining = length
            while (remaining > 0) {
                val writeAt = (total % capacity.toLong()).toInt()
                val contiguous = minOf(remaining, capacity - writeAt)
                System.arraycopy(source, at, buffer, writeAt, contiguous)
                total += contiguous
                at += contiguous
                remaining -= contiguous
            }
        }

        fun ordered(): ByteArray {
            val count = size
            if (count == 0) return ByteArray(0)
            val start = if (total <= capacity) 0 else (total % capacity.toLong()).toInt()
            val first = minOf(count, capacity - start)
            val out = ByteArray(count)
            System.arraycopy(buffer, start, out, 0, first)
            if (first < count) System.arraycopy(buffer, 0, out, first, count - first)
            return out
        }
    }

    /** The end of the first [cut] bytes, moved back over continuation bytes so no character is split. */
    private fun utf8CutBack(bytes: ByteArray, cut: Int): Int {
        var end = cut.coerceIn(0, bytes.size)
        while (end > 0 && end < bytes.size && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return end
    }

    /** The first character boundary in [bytes], so a tail never begins mid-character. */
    private fun utf8StartForward(bytes: ByteArray): Int {
        var at = 0
        while (at < bytes.size && (bytes[at].toInt() and 0xC0) == 0x80) at++
        return at
    }

    private fun killTree(process: Process) {
        // Descendants first: if the shell dies first, the children are reparented and the snapshot
        // is all a later walk would have.
        runCatching { process.descendants().forEach { it.destroyForcibly() } }
        runCatching { process.destroyForcibly() }
    }
}
