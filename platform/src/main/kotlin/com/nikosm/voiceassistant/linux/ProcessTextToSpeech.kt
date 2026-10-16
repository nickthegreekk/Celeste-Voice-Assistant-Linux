package com.nikosm.voiceassistant.linux

import com.nikosm.voiceassistant.AppLogger
import com.nikosm.voiceassistant.PlaybackEnd
import com.nikosm.voiceassistant.SpeechSamples
import com.nikosm.voiceassistant.StderrLogger
import com.nikosm.voiceassistant.TextToSpeech
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Seam 6, implemented over two processes: `spd-say` for the system route, `espeak-ng` for the local
 * one.
 *
 * ## The gateway route is not here, and never was a seam
 *
 * PLAN.md §5's seam-6 row lists three routes and this class implements two of them. The third —
 * the gateway's `/synthesize` — is HTTP, it already worked on Linux, and it lives in
 * `AssistantServiceGateway.kt` untouched. It is also the route a persona is *configured* for, which
 * is why the order of the port matters: the gateway voice is the one the user chose, and the two
 * here are fallbacks that only run for a persona that asked for them.
 *
 * ## The two routes are shaped differently, and the seam says why
 *
 * The system route **hands text over and is told when it finished** — `spd-say -w` blocks until the
 * utterance is spoken, so the process exit is the completion, exactly as with seam 2. The local
 * route **returns samples**: `speakWithEspeak` computes `voiceDuration` from them and plays them
 * through `AudioPlayback` so the application keeps control of playback, which is why
 * [synthesizeLocal] is a function that returns a value and [speakSystem] is one that takes a
 * callback. Flattening the two would lose the duration the UI reveal is built on.
 *
 * ## Missing tools are `false` and `null`, never an exception
 *
 * This machine has neither `spd-say` nor `espeak-ng` installed — `speech-dispatcher` and
 * `espeak-ng` are Extra packages — so this class is, as of writing, exercised only on that path.
 * That is the contract's own requirement rather than an accident: "Both routes return null (local)
 * or false (system) when the engine is missing or refuses, because the caller's response is the
 * same either way: release the audio focus this turn retained and land in IDLE." An engine that
 * threw would turn a missing package into a failed turn.
 *
 * ## The once-only local engine, and where it went
 *
 * Upstream's `EspeakEngine` needed a double-checked lock because eSpeak keeps its state in
 * process-global natives and two concurrent `espeak_Initialize` calls aborted with
 * "pthread_mutex_lock called on a destroyed mutex". Running the *CLI* has no such state to share —
 * each synthesis is a process — so the lock and the `@Volatile` field go with it. What does not go
 * is the requirement's *purpose*: [releaseLocal] still exists, still is idempotent, and is still
 * called from the service's `shutdown()`, because a future JNA implementation would need it and a
 * caller that has to know which implementation it has is a caller that is already broken.
 */
class ProcessTextToSpeech(
    private val logger: AppLogger = StderrLogger,
) : TextToSpeech {

    /** The callbacks' thread, for the same reason seam 2 has one. Daemon, for the same reason. */
    private val callbacks = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tts-callbacks").apply { isDaemon = true }
    }

    /** The utterance in flight. At most one, which is the obligation the seam's KDoc states. */
    private val speaking = AtomicReference<Utterance?>()

    /**
     * Resolved once. `spd-say` is probed rather than assumed, because a binary on `PATH` with no
     * speech-dispatcher daemon running is a binary that fails at every utterance.
     */
    private val systemPlayer: Command? by lazy { probe(SYSTEM_CANDIDATES) }

    private val localSynth: Command? by lazy { probe(LOCAL_CANDIDATES) }

    override val systemReady: Boolean get() = systemPlayer != null

    override fun speakSystem(text: String, utteranceId: String, onEnd: (PlaybackEnd) -> Unit): Boolean {
        val tool = systemPlayer
        if (tool == null) {
            logger.w(TAG, "no system speech engine — install speech-dispatcher (spd-say)")
            return false
        }
        // At most one utterance, enforced here rather than by the caller: the seam's KDoc makes it
        // the implementation's obligation, and this is the line that keeps it.
        speaking.getAndSet(null)?.stop()

        val process = try {
            ProcessBuilder(tool.command, *tool.args(text, null)).redirectErrorStream(true).start()
        } catch (e: IOException) {
            logger.e(TAG, "${tool.command} could not start: ${e.message}")
            callbacks.execute { onEnd(PlaybackEnd.Failed("${tool.command} could not start")) }
            return true
        }

        val utterance = Utterance(process, tool.command)
        speaking.set(utterance)
        utterance.reap(callbacks) { end ->
            speaking.compareAndSet(utterance, null)
            if (end !is PlaybackEnd.Finished) {
                logger.d(TAG, "system speech ended: $end")
            }
            onEnd(end)
        }
        return true
    }

    override fun stopSystem() {
        speaking.get()?.stop()
    }

    override fun synthesizeLocal(text: String, voice: String): SpeechSamples? {
        val tool = localSynth
        if (tool == null) {
            logger.w(TAG, "no local speech engine — install espeak-ng")
            return null
        }
        return try {
            // Run on the caller's thread: `speakWithEspeak` already reaches this from
            // `Dispatchers.IO`, and adding a second thread to wait for a process that
            // exists to produce a return value would be a thread for nothing.
            val process = ProcessBuilder(tool.command, *tool.args(text, voice))
                .redirectErrorStream(false)
                .start()
            // stdout is the WAV; stderr is espeak's own diagnostics and is drained on another
            // thread so a chatty engine cannot fill the pipe and block the child.
            val stderr = process.errorStream.readBytesAsync()
            val wav = process.inputStream.use { it.readBytes() }
            if (!process.waitFor(SYNTH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                logger.e(TAG, "espeak-ng did not finish within ${SYNTH_TIMEOUT_MS}ms")
                process.destroyForcibly()
                return null
            }
            stderr.join()
            if (process.exitValue() != 0 || wav.isEmpty()) {
                logger.e(TAG, "espeak-ng exited ${process.exitValue()} with ${wav.size} bytes")
                return null
            }
            parseWav(wav)
        } catch (e: Exception) {
            logger.e(TAG, "local synthesis failed: ${e.message}")
            null
        }
    }

    /**
     * Idempotent and a no-op here: each synthesis is a process, so there is no engine to release.
     *
     * Kept because the seam promises it and the service calls it, and because the alternative — a
     * future JNA implementation noticing that nobody calls its release — is a leak waiting for the
     * day the implementation changes.
     */
    override fun releaseLocal() = Unit

    // ---------------------------------------------------------------- helpers

    /**
     * The first command that answers a harmless question, run once.
     *
     * `--version` for both: it prints and exits without touching the audio graph or the speech
     * daemon, which is exactly the probe a first utterance wants. A `spd-say` present with no
     * daemon behind it fails here rather than at every utterance.
     */
    private fun probe(candidates: List<Command>): Command? {
        for (candidate in candidates) {
            val ok = try {
                val p = ProcessBuilder(candidate.command, *candidate.probeArgs)
                    .redirectErrorStream(true).start()
                p.inputStream.use { it.readBytes() }
                p.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS) && p.exitValue() == 0
            } catch (e: IOException) {
                false
            }
            if (ok) {
                logger.i(TAG, "speech via ${candidate.command}")
                return candidate
            }
        }
        return null
    }

    /**
     * The WAV `espeak-ng --stdout` produces.
     *
     * Chunk-walking rather than assuming a 44-byte header, because that assumption holds only for
     * the files this project writes itself and this one is written by a program with its own idea
     * of where to put a `LIST` chunk. The scan is the ordinary RIFF walk: read the format from
     * `fmt `, take the payload from `data`, skip anything else by its declared size. A chunk size
     * that would run past the end is treated as corruption rather than trusted, since the loop
     * advances by that number.
     */
    private fun parseWav(wav: ByteArray): SpeechSamples? {
        if (wav.size < 12 || String(wav, 0, 4) != "RIFF" || String(wav, 8, 4) != "WAVE") {
            logger.e(TAG, "espeak-ng did not produce a RIFF/WAVE stream")
            return null
        }
        var pos = 12
        var sampleRate = 0
        var bits = 0
        var channels = 0
        var payload: ByteArray? = null
        while (pos + 8 <= wav.size) {
            val id = String(wav, pos, 4)
            val size = ByteBuffer.wrap(wav, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (size < 0 || pos + 8 + size > wav.size) break
            val body = pos + 8
            when (id) {
                "fmt " -> if (size >= 16) {
                    channels = ByteBuffer.wrap(wav, body + 2, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                    sampleRate = ByteBuffer.wrap(wav, body + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    bits = ByteBuffer.wrap(wav, body + 14, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                }
                "data" -> payload = wav.copyOfRange(body, body + size)
            }
            // Chunks are word-aligned: an odd size is followed by a pad byte.
            pos = body + size + (size and 1)
        }

        val data = payload
        if (data == null || sampleRate <= 0 || data.isEmpty()) {
            logger.e(TAG, "espeak-ng's WAV had no usable data chunk")
            return null
        }
        if (channels != 1 || bits != 16) {
            // The seam's contract is mono signed 16-bit, and `AudioPlayback.playPcm` will play what
            // it is given. A stereo or 8-bit stream is a different contract, not a different
            // platform, so it is refused rather than silently misplayed as noise.
            logger.e(TAG, "espeak-ng produced ${channels}ch/${bits}bit, expected mono 16-bit")
            return null
        }
        return SpeechSamples(pcm = data, sampleRate = sampleRate)
    }

    private companion object {
        const val TAG = "ProcessTextToSpeech"
        const val PROBE_TIMEOUT_MS = 3_000L
        const val SYNTH_TIMEOUT_MS = 30_000L

        /** How long a politely-killed utterance gets before the daemon is told again. */
        const val PLAYER_EXIT_GRACE_MS = 500L

        /**
         * `spd-say -w` waits for the utterance; `-l` sets the language, which the caller's
         * `voice` argument carries for this route as an eSpeak language code.
         *
         * `-w` is what makes "finished is process exit" true for a route whose engine owns its own
         * playback, and it is the reason this route needs no handle to query.
         */
        val SYSTEM_CANDIDATES = listOf(
            // No `-l`. Upstream set `tts.language = Locale.US` once at construction, and the
            // seam this replaced has no way to carry that — `speakSystem` takes text, an id and a
            // callback. So the daemon's own configured language is used, which on a machine whose
            // locale is the user's is a better answer than the hard-coded US English upstream
            // picked. If a call site ever needs to say otherwise, the seam grows a property and
            // this line grows a flag; inventing a parameter now would be guessing at the caller.
            Command("spd-say", arrayOf("--version")) { text, _ -> arrayOf("-w", text) },
        )

        /**
         * `--stdout` writes the WAV to stdout, which is what makes this route able to return
         * samples rather than play them. `-v` selects the voice.
         */
        val LOCAL_CANDIDATES = listOf(
            Command("espeak-ng", arrayOf("--version")) { text, voice -> arrayOf("-v", voice ?: "en", "--stdout", text) },
            Command("espeak", arrayOf("--version")) { text, voice -> arrayOf("-v", voice ?: "en", "--stdout", text) },
        )
    }

    /** A speech tool, how to ask whether it works, and how to make it say something. */
    private class Command(
        val command: String,
        val probeArgs: Array<String>,
        val args: (text: String, voice: String?) -> Array<String>,
    )

    /** One utterance in flight: the process, and whether this class killed it. */
    private class Utterance(private val process: Process, private val command: String) {
        @Volatile private var killed = false
        @Volatile private var reaped = false

        fun stop() {
            if (!process.isAlive) return
            killed = true
            process.destroy()
            process.waitFor(PLAYER_EXIT_GRACE_MS, TimeUnit.MILLISECONDS)
        }

        fun reap(executor: java.util.concurrent.Executor, onEnd: (PlaybackEnd) -> Unit) {
            val thread = Thread({
                val exit = try {
                    process.waitFor()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    -1
                }
                if (reaped) return@Thread
                reaped = true
                val end = when {
                    killed -> PlaybackEnd.Stopped
                    exit == 0 -> PlaybackEnd.Finished
                    else -> PlaybackEnd.Failed("$command exited with $exit")
                }
                executor.execute { onEnd(end) }
            }, "tts-wait")
            thread.isDaemon = true
            thread.start()
        }
    }
}

/** Drains a stream on its own thread, so a child that writes more than a pipe buffer cannot block. */
private fun java.io.InputStream.readBytesAsync(): Thread {
    val thread = Thread({ runCatching { readBytes() } }, "tts-stderr")
    thread.isDaemon = true
    thread.start()
    return thread
}
