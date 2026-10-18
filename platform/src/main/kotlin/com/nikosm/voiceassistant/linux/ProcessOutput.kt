package com.nikosm.voiceassistant.linux

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The output of a helper process, read with a deadline.
 *
 * `Process.waitFor(timeout)` alone is not a bound on the work: a child that never closes stdout
 * keeps `readBytes()` blocked forever, and the timeout is only reached after EOF. The two callers
 * here — local speech synthesis and the notifier's `notify-send`/`dbus-send` — read a process to
 * completion and must not be able to hang a turn on a wedged one, so the reads run beside the wait
 * and a missed deadline kills the process and closes the pipes that unblock the readers.
 */
internal data class ProcessOutput(
    val exitCode: Int?,
    val stdout: ByteArray,
    val stderr: ByteArray,
    val timedOut: Boolean,
)

/**
 * Wait up to [timeoutMillis] for this process, draining both streams concurrently.
 *
 * A timeout is not a failure to report here — only the caller knows what the process was for — so
 * the result says `timedOut` and leaves the logging to the call site. The process is killed and
 * reaped before returning; bytes read before the deadline are kept.
 */
internal fun Process.communicateWithin(timeoutMillis: Long): ProcessOutput {
    val stdout = StreamReader(inputStream, "process-stdout")
    val stderr = StreamReader(errorStream, "process-stderr")
    val finished = try {
        waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        destroyForcibly()
        runCatching { waitFor(READ_GRACE_MS, TimeUnit.MILLISECONDS) }
        closePipes()
        return ProcessOutput(null, stdout.await(READ_GRACE_MS), stderr.await(READ_GRACE_MS), true)
    }
    if (!finished) {
        destroyForcibly()
        runCatching { waitFor(READ_GRACE_MS, TimeUnit.MILLISECONDS) }
        // A grandchild can hold the pipe open after the direct child is gone. Closing our ends is
        // what unblocks the readers; what they read before that is the honest half-answer.
        closePipes()
    }
    return ProcessOutput(
        exitCode = if (finished) runCatching { exitValue() }.getOrNull() else null,
        stdout = stdout.await(READ_GRACE_MS),
        stderr = stderr.await(READ_GRACE_MS),
        timedOut = !finished,
    )
}

private fun Process.closePipes() {
    runCatching { inputStream.close() }
    runCatching { errorStream.close() }
}

/**
 * Reads one stream on a daemon thread, so a wedged child can be killed while the read is blocked.
 * A close under the reader — how a timeout unblocks it — is an end of stream, not an error, and
 * the bytes read so far are the result.
 */
private class StreamReader(stream: InputStream, name: String) {
    private val result = CompletableFuture<ByteArray>()

    init {
        Thread({
            val read = ByteArrayOutputStream()
            try {
                stream.use { input ->
                    val chunk = ByteArray(8 * 1024)
                    while (true) {
                        val count = input.read(chunk)
                        if (count < 0) break
                        read.write(chunk, 0, count)
                    }
                }
            } catch (_: Exception) {
                // The process was killed and the pipe closed under us; keep what arrived.
            }
            result.complete(read.toByteArray())
        }, "$name-${ids.incrementAndGet()}").apply {
            isDaemon = true
            start()
        }
    }

    fun await(timeoutMillis: Long): ByteArray = try {
        result.get(timeoutMillis, TimeUnit.MILLISECONDS)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        ByteArray(0)
    } catch (_: Exception) {
        ByteArray(0)
    }

    private companion object {
        val ids = AtomicLong()
    }
}

private const val READ_GRACE_MS = 1_000L
