package com.nikosm.voiceassistant.linux

import com.nikosm.voiceassistant.AppLogger
import com.nikosm.voiceassistant.AudioCapture
import com.nikosm.voiceassistant.AudioDevice
import com.nikosm.voiceassistant.AudioDevices
import com.nikosm.voiceassistant.CaptureStream
import com.nikosm.voiceassistant.StderrLogger
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Seam 1, implemented as one `pw-record` process per capture session, writing raw PCM to its stdout.
 *
 * `parecord` and `arecord` are the fallbacks, for the same reason seam 2 has them: a machine can have
 * one of the three without the others, and which one it has is not something this class should ask
 * the user to find out.
 *
 * ## Raw PCM on stdout, not a WAV on disk
 *
 * The alternative — record to a file and read the file back — is what the *gateway* path does, and
 * it is right there: a whole utterance is produced, then sent. The VAD loop is the opposite shape. It
 * needs 32 ms every 32 ms, for as long as the microphone is on, and it needs the most recent one
 * *now*: a file it reads back would be a file it is also writing, and the VAD would be deciding
 * whether the last 32 ms contained speech based on however much of it had been flushed.
 *
 * `--raw` is what `pw-record`, `parecord` and `arecord` all spell the same way. The sample format is
 * `s16`, which is the `ShortArray` the seam hands over, and mono, because the VAD is mono and the
 * gateway's `/transcribe` is mono.
 *
 * ## `read` blocks, and that is the contract rather than a convenience
 *
 * The caller is `VADAudioRecorder`'s IO loop, which wants exactly `chunkSize` frames per iteration
 * and has nothing to do until they arrive. Returning what happened to be buffered would hand the VAD
 * a short frame, which is a different amount of audio than the model expects — 512 samples is the
 * Silero window, and 200 of them is not a smaller window, it is the wrong input.
 *
 * **A read of 0 means the stream has ended.** The process exited, the device was unplugged, or
 * PipeWire restarted; there will be no more samples, and the caller must stop rather than read again.
 * That is a change from the earlier shape of this contract, where 0 was not distinguished from a
 * short read — see [VADAudioRecorder], which now stops on it. Without that the loop would spin on a
 * dead microphone, logging a warning per iteration for as long as the application stayed open.
 *
 * ## Opening is where failure is reported
 *
 * [open] returns null when `pw-record` cannot be started or the device cannot be opened, and the
 * caller logs once and gives up. It does **not** return a stream that will fail on the first read:
 * the VAD loop's failure handling is a single check at the top, deliberately, and a stream that
 * opened and cannot produce samples would put a second failure path in the middle of the hot loop.
 */
class ProcessAudioCapture(
    /**
     * Used for one thing: picking a device when the caller named none and the sound server has no
     * default to fall back on — see [open].
     */
    private val devices: AudioDevices,
    private val logger: AppLogger = StderrLogger,
) : AudioCapture {

    @Volatile
    private var recorder: Command? = null

    override fun open(sampleRate: Int, chunkFrames: Int, target: AudioDevice?): CaptureStream? {
        val tool = recorder ?: detect().also { recorder = it }
        if (tool == null) {
            logger.e(TAG, "no capture tool found — tried ${CAPTURE_TOOLS.joinToString(", ") { it.command }}")
            return null
        }

        val process = try {
            ProcessBuilder(tool.args(sampleRate, target))
                // stderr is kept and drained on its own thread. `pw-record` reports the real reason a
                // stream will not open there — "no node found", "permission denied" — and merged into
                // stdout it would be read as samples, which is not a subtle failure: it is a stream of
                // garbage the VAD sees as speech.
                .redirectErrorStream(false)
                .start()
        } catch (e: IOException) {
            logger.e(TAG, "${tool.command} could not start: ${e.message}")
            return null
        }

        val stream = ProcessCaptureStream(process, tool.command, sampleRate, logger)
        // A `pw-record` that exits immediately could not open the device. Checked here, where the
        // answer is still "this microphone does not work" rather than in the first read, where it
        // would look like the microphone went quiet.
        if (stream.isAliveAfter(START_GRACE_MS)) {
            logger.i(TAG, "capture via ${tool.command} at ${sampleRate}Hz")
            return stream
        }

        val reason = stream.drainStderr()
        stream.close()

        // **A machine with no default source.** `pw-record` with no `--target` asks the session
        // manager for one, and on a machine where WirePlumber has never been told which input to
        // prefer it answers exactly this: "no target node available". Measured on the development
        // box — `wpctl status` prints the analog input with no `*` beside it, and `pw-record` fails
        // from a plain shell as well as from here, so it is the machine and not this code.
        //
        // The fallback is the first enumerated input, and it is taken only when the caller named
        // nothing. A caller that named a device and could not have it is told so: silently
        // substituting a different microphone for the one the user picked is how a persona ends up
        // listening to the wrong room.
        if (target == null) {
            val fallback = devices.inputs().firstOrNull()
            if (fallback != null) {
                logger.w(
                    TAG,
                    "no default capture device ($reason) — falling back to '${fallback.label}'. " +
                        "Set one with: wpctl set-default ${fallback.id}",
                )
                return open(sampleRate, chunkFrames, fallback)
            }
        }

        logger.e(TAG, "${tool.command} exited at once: $reason")
        return null
    }

    /**
     * The first tool that answers `--version`, run once.
     *
     * Asking a tool with no sound server behind it is not the same as asking whether the binary
     * exists; `--version` prints and exits without touching the graph, which is the question worth
     * asking here. Whether a *device* can be opened is a separate question and is asked per open.
     */
    private fun detect(): Command? {
        for (candidate in CAPTURE_TOOLS) {
            val ok = try {
                val p = ProcessBuilder(candidate.command, "--version")
                    .redirectErrorStream(true).start()
                p.inputStream.use { it.readBytes() }
                p.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS) && p.exitValue() == 0
            } catch (e: IOException) {
                false
            }
            if (ok) return candidate
        }
        return null
    }

    private companion object {
        const val TAG = "ProcessAudioCapture"
        const val PROBE_TIMEOUT_MS = 3_000L

        /** How long a recorder gets to prove it opened the device rather than fall over. */
        const val START_GRACE_MS = 400L

        val CAPTURE_TOOLS = listOf(
            Command("pw-record"),
            Command("parecord"),
            // `arecord` has no `--target`; `-D` names a card, which is the same information by a
            // different route and the best it can be given.
            Command("arecord", targetFlag = "-D"),
        )
    }

    private class Command(val command: String, val targetFlag: String? = "--target") {
        fun args(sampleRate: Int, target: AudioDevice?): List<String> = buildList {
            add(command)
            // `--raw` is `-t raw` in the PulseAudio and ALSA spellings; all three accept `--raw`.
            add("--raw")
            when (command) {
                "arecord" -> {
                    add("-f"); add("S16_LE")          // ALSA spells the format out
                    add("-r"); add(sampleRate.toString())
                    add("-c"); add("1")
                }
                "parecord" -> {
                    add("--format=s16le")
                    add("--rate=$sampleRate")
                    add("--channels=1")
                }
                else -> {
                    add("--format"); add("s16")
                    add("--rate"); add(sampleRate.toString())
                    add("--channels"); add("1")
                }
            }
            if (target != null && targetFlag != null) {
                add(targetFlag); add(target.id)
            }
            // `-` is stdout for the whole pw-cat family, and for `parecord` and `arecord` too.
            add("-")
        }
    }
}

/**
 * One open capture session, reading raw `s16` mono frames from a process's stdout.
 *
 * The conversion is little-endian by definition of the format `s16` names, and the buffer is reused
 * per call: [read] is called every 32 ms for the life of a session, and allocating a `ByteBuffer` per
 * call would be a megabyte a minute of garbage for no benefit.
 */
private class ProcessCaptureStream(
    private val process: Process,
    private val command: String,
    private val sampleRate: Int,
    private val logger: AppLogger,
) : CaptureStream {

    private val source: InputStream = process.inputStream

    /**
     * Reused across [read] calls, and sized on the first one. A VAD session calls this every
     * 32 ms for as long as the microphone is on, so a buffer per call would be a megabyte a
     *     * minute of garbage for no benefit — and the seam does not promise a frame count, so
     * the size cannot be fixed at construction either.
     */
    private var scratch = ByteArray(0)

    @Volatile
    private var closed = false

    /** stderr, collected so an immediate exit can be explained. Drained by a thread of its own. */
    private val stderr = StringBuilder()
    private val stderrThread = Thread({
        runCatching {
            process.errorStream.bufferedReader().forEachLine { line ->
                synchronized(stderr) {
                    // Bounded: a recorder that fails in a loop could otherwise grow this forever.
                    if (stderr.length < 4096) stderr.appendLine(line)
                }
            }
        }
    }, "capture-stderr").apply { isDaemon = true; start() }

    fun isAliveAfter(millis: Long): Boolean {
        val deadline = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) return false
            Thread.sleep(10)
        }
        return true
    }

    fun drainStderr(): String {
        stderrThread.join(200)
        return synchronized(stderr) { stderr.toString().trim() }.ifEmpty { "no output" }
    }

    override fun read(buffer: ShortArray, frames: Int): Int {
        if (closed) return 0
        val wanted = frames * 2
        if (scratch.size < wanted) scratch = ByteArray(wanted)
        var filled = 0
        // A single `read` returns what the pipe has, which is rarely a whole chunk. Filling the
        // request is the difference between a VAD window and a partial one.
        while (filled < wanted) {
            val n = try {
                source.read(scratch, filled, wanted - filled)
            } catch (e: IOException) {
                logger.w(TAG, "$command read failed: ${e.message}")
                return 0
            }
            if (n < 0) return 0          // end of stream — see the class note on `read` returning 0
            filled += n
        }
        val bb = ByteBuffer.wrap(scratch, 0, wanted).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) buffer[i] = bb.short
        return frames
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { source.close() }
        if (process.isAlive) {
            process.destroy()
            // The grace is short on purpose: this is called from the UI's mute/stop path, and a
            // recorder that has not released the device by now is one that will not.
            if (!process.waitFor(EXIT_GRACE_MS, TimeUnit.MILLISECONDS)) process.destroyForcibly()
        }
        stderrThread.interrupt()
    }

    private companion object {
        const val TAG = "ProcessAudioCapture"

        /**
         * Short on purpose: [close] is reached from the UI's mute and stop paths, and a recorder
         * that has not let go of the device by now is one that will not.
         */
        const val EXIT_GRACE_MS = 300L
    }
}
