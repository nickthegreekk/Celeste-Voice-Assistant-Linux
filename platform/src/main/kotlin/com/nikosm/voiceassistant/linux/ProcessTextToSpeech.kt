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
 * The local engine has one behaviour worth recording because it looks like a silent failure and is
 * not one: `espeak-ng -v <unknown>` exits 0, writes nothing to stderr, and *speaks in its default
 * voice* — measured, 9,642 non-zero bytes of real audio for `-v no-such-voice`. So a language whose
 * code this build of eSpeak lacks is spoken with English phonetics rather than left unspoken, which
 * is the engine's call to make. It is written down so that the next person to see accented English
 * out of a Greek persona looks at the voice table instead of at this class.
 *
 * ## Missing tools are `false` and `null`, never an exception
 *
 * "Both routes return null (local) or false (system) when the engine is missing or refuses, because
 * the caller's response is the same either way: release the audio focus this turn retained and land
 * in IDLE." An engine that threw would turn a missing package into a failed turn.
 *
 * This paragraph was written when the development box had neither package installed and the missing
 * path was the only one that could be exercised. Both are now installed — speech-dispatcher 0.12.1
 * and espeak-ng 1.52.0 — and both happy paths have been run: the system route through a
 * socket-activated daemon, and the local route through the streaming WAV that [parseSpeechWav]
 * documents. The failure path is still the one that needs an argument rather than an anecdote, and
 * [probe]'s caching note is where it is made.
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
     * The probed tool, **remembered only when the probe succeeded**.
     *
     * `by lazy` was the first version and it was wrong in the direction that costs a session: it
     * caches a *failure* as faithfully as a success, so one bad probe — a daemon still starting, a
     * socket-activated service answering late, a transient `PATH` hiccup — would have judged the
     * voice unavailable until the process exited. There is no retry anywhere above this: the caller
     * checks [systemReady] once per turn and lands in IDLE when it is false.
     *
     * So a success is cached for the life of the process, because a tool that worked will keep
     * working and re-probing per utterance would be a process spawn per reply. A failure is **not**
     * cached: the next call probes again. The cost of that is one `--version` per attempt on a
     * machine with no engine installed, which is a few milliseconds against an HTTP request already
     * in flight — and the benefit is that installing the package mid-session starts working on the
     * next reply rather than on the next launch.
     */
    @Volatile
    private var systemPlayerCache: Command? = null

    @Volatile
    private var localSynthCache: Command? = null

    private fun systemPlayer(): Command? =
        systemPlayerCache ?: probe(SYSTEM_CANDIDATES)?.also { systemPlayerCache = it }

    private fun localSynth(): Command? =
        localSynthCache ?: probe(LOCAL_CANDIDATES)?.also { localSynthCache = it }

    override val systemReady: Boolean get() = systemPlayer() != null

    override fun speakSystem(text: String, utteranceId: String, onEnd: (PlaybackEnd) -> Unit): Boolean {
        val tool = systemPlayer()
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
        val tool = localSynth()
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
            // The deadline covers the read, not just the exit: a wedged engine that never closes
            // stdout used to block `readBytes()` forever, because `waitFor(timeout)` was only
            // reached after EOF. communicateWithin drains both pipes beside the wait and kills
            // the child on expiry.
            val output = process.communicateWithin(SYNTH_TIMEOUT_MS)
            if (output.timedOut) {
                logger.e(TAG, "espeak-ng did not finish within ${SYNTH_TIMEOUT_MS}ms")
                return null
            }
            if (output.exitCode != 0 || output.stdout.isEmpty()) {
                logger.e(TAG, "espeak-ng exited ${output.exitCode} with ${output.stdout.size} bytes")
                return null
            }
            parseSpeechWav(output.stdout, logger)
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
                // Drain beside the wait: `--version` normally closes stdout immediately, but a
                // wedged engine must not be able to hold the probe past its deadline.
                val output = p.communicateWithin(PROBE_TIMEOUT_MS)
                !output.timedOut && output.exitCode == 0
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

private const val WAV_TAG = "ProcessTextToSpeech"

/**
 * The WAV `espeak-ng --stdout` produces.
 *
 * Chunk-walking rather than assuming a 44-byte header, because that assumption holds only for the
 * files this project writes itself and this one is written by a program with its own idea of where
 * to put a `LIST` chunk. The scan is the ordinary RIFF walk: read the format from `fmt `, take the
 * payload from `data`, skip anything else by its declared size.
 *
 * Top-level and `internal` so a test can hand it synthetic bytes: every failure this function can
 * have is a *parsing* failure, and requiring a real `espeak-ng`, real audio and a real machine to
 * reach one would leave them all unexercised.
 *
 * ## A `data` size that runs past the end is a placeholder, not corruption
 *
 * `espeak-ng --stdout` writes to a pipe, and a pipe cannot be seeked back into, so the size it
 * stamps into the `data` header at the start is one it can never correct when the synthesis ends.
 * Measured on the development box: the header reads `RIFF$\xf0\xff\x7fWAVE`, with `data` declaring
 * 2,147,479,552 bytes in a 76,284-byte stream (1.729 s of 22050 Hz mono). The first version of this
 * parser applied the same bounds check to every chunk, declared the file corrupt and logged "no
 * usable data chunk" — leaving the local route dead on a machine where `espeak-ng` works perfectly.
 *
 * The streaming convention is the fix: **a `data` chunk says "the samples start here", and
 * everything to the end of the stream is payload.** So the declared size is honoured when it fits
 * and ignored when it does not; a declaration larger than the file can only mean the writer never
 * patched it. Nothing else is trusted that way — a `fmt ` chunk that runs past the end is still
 * corruption, because the walk advances by that number and no convention makes it mean anything
 * else.
 *
 * Reading to the end is safe because the caller has already reaped the process: the bytes it read
 * are the whole of what was written, so there is no in-flight truncation that could be mistaken for
 * a short `data` chunk.
 */
internal fun parseSpeechWav(wav: ByteArray, logger: AppLogger): SpeechSamples? {
    if (wav.size < 12 || String(wav, 0, 4) != "RIFF" || String(wav, 8, 4) != "WAVE") {
        logger.e(WAV_TAG, "espeak-ng did not produce a RIFF/WAVE stream")
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
        val body = pos + 8
        if (id == "data") {
            val available = wav.size - body
            payload = wav.copyOfRange(body, body + if (size in 0..available) size else available)
            break
        }
        if (size < 0 || body + size > wav.size) break
        if (id == "fmt " && size >= 16) {
            channels = ByteBuffer.wrap(wav, body + 2, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
            sampleRate = ByteBuffer.wrap(wav, body + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            bits = ByteBuffer.wrap(wav, body + 14, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        }
        // Chunks are word-aligned: an odd size is followed by a pad byte.
        pos = body + size + (size and 1)
    }

    val data = payload
    if (data == null || sampleRate <= 0 || data.isEmpty()) {
        logger.e(WAV_TAG, "espeak-ng's WAV had no usable data chunk")
        return null
    }
    if (channels != 1 || bits != 16) {
        // The seam's contract is mono signed 16-bit, and `AudioPlayback.playPcm` will play what it
        // is given. A stereo or 8-bit stream is a different contract, not a different platform, so
        // it is refused rather than silently misplayed as noise.
        logger.e(WAV_TAG, "espeak-ng produced ${channels}ch/${bits}bit, expected mono 16-bit")
        return null
    }
    return SpeechSamples(pcm = data, sampleRate = sampleRate)
}
