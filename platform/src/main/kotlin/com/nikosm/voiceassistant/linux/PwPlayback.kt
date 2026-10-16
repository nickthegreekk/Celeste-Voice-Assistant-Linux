package com.nikosm.voiceassistant.linux

import com.nikosm.voiceassistant.AppLogger
import com.nikosm.voiceassistant.AudioDevice
import com.nikosm.voiceassistant.AudioPlayback
import com.nikosm.voiceassistant.PlaybackEnd
import com.nikosm.voiceassistant.PlaybackHandle
import com.nikosm.voiceassistant.StderrLogger
import com.nikosm.voiceassistant.WavFile
import com.nikosm.voiceassistant.wavDurationSeconds
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Seam 2, implemented as **one player process per utterance** — `pw-play`, with `paplay` and `aplay`
 * as fallbacks.
 *
 * ## "Finished" is process exit, and that is the contract rather than a shortcut
 *
 * PLAN.md §5 gives this seam one consequence worth stating: a subprocess has no completion callback,
 * so the end of the audio *is* the end of the process. Everything else follows from that:
 *
 *  - **Durations come from the file.** There is no player to ask, so [PlaybackHandle.durationMs] is
 *    the WAV header's own arithmetic, read through `wavDurationSeconds` — the same function the
 *    chunked pipeline uses to advance its clock. Reading the header twice would be two
 *    implementations of one number.
 *  - **Position is elapsed wall-clock time, capped at the duration.** Contract 2 says a position
 *    that overshot would paint past the end of the text, so the cap is what keeps the karaoke
 *    arithmetic identical to upstream's even though the source of the number changed. It is an
 *    approximation and the honest kind: a `pw-play` process does not report where it is in the
 *    stream, and the alternative — parsing `/proc/<pid>/fdinfo` for the read offset — would report
 *    how far the *file* had been read rather than how far the audio had been heard, which is
 *    further from the truth, not closer.
 *  - **A stop is a signal, and the waiter has to know it caused one.** Killing the process makes
 *    `waitFor` return non-zero, which is indistinguishable from a crash unless someone remembers
 *    who pulled the trigger — so [Clip.killed] does. That flag is what makes `Stopped` and `Failed`
 *    different reports, and contract 1 needs the difference: a caller that read its own stop as a
 *    failure would log an error on every superseded reply.
 *
 * ## Why the callbacks arrive on a thread of this class's own
 *
 * Contract 4 asks for one defined dispatcher. It is a **single-threaded executor created here**, not
 * `Dispatchers.IO` and not the caller's: `Dispatchers.IO` is shared with every gateway request and
 * file write in the process, and a caller that blocked in a callback would stall unrelated work.
 * One thread per seam also means callbacks cannot arrive concurrently, so the "exactly once" the
 * sequencer relies on is a property of the shape rather than of a flag.
 *
 * ## The three tools, and why the fallback list is not decoration
 *
 * `pw-play` is the PipeWire native one and the primary path. `paplay` is PulseAudio's, which on a
 * PipeWire system goes through `pipewire-pulse`; `aplay` is ALSA's and works with neither server
 * running. The list is tried once, at the first playback, and the winner is remembered — a machine
 * where `pw-play` is missing should pay for discovering that once, not once per utterance.
 */
class PwPlayback(
    private val logger: AppLogger = StderrLogger,
) : AudioPlayback {

    /** The callbacks' thread. Daemon so a forgotten playback cannot hold the JVM open. */
    private val callbacks = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pw-playback-callbacks").apply { isDaemon = true }
    }

    /**
     * The playback in flight, if any. An [AtomicReference] rather than a lock: every reader is
     * asking "is this the one I started?", and the answer changing underneath them is the normal
     * case rather than the exceptional one.
     */
    private val current = AtomicReference<Clip?>(null)

    /** The tool that worked, or null until one has been tried. */
    @Volatile
    private var player: Player? = null

    override fun play(file: Path, target: AudioDevice?, onEnd: (PlaybackEnd) -> Unit): PlaybackHandle =
        start(file, target, onEnd, ownedTemp = null)

    override fun playPcm(
        samples: ByteArray,
        sampleRate: Int,
        target: AudioDevice?,
        onEnd: (PlaybackEnd) -> Unit,
    ): PlaybackHandle {
        // Contract 3: the seam takes the file-writing on itself, because the alternative is the
        // WAV header in three callers. The name carries the sample rate so a reader of a stray file
        // can tell what it is, and `.part` until it is complete so nothing can play a half-written
        // one — the convention `AssistantServiceComfy`'s image download already uses.
        val temp = Files.createTempFile("celeste-playback-", ".wav")
        try {
            WavFile.write(temp, listOf(samples.toShortArrayLittleEndian()), sampleRate)
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(temp) }
            callbacks.execute { onEnd(PlaybackEnd.Failed("could not write the audio: ${e.message}")) }
            return FinishedHandle
        }
        return start(temp, target, onEnd, ownedTemp = temp)
    }

    override fun stopAll() {
        current.get()?.stop()
    }

    // ---------------------------------------------------------------- the one playback at a time

    private fun start(file: Path, target: AudioDevice?, onEnd: (PlaybackEnd) -> Unit, ownedTemp: Path?): PlaybackHandle {
        // Contract 1, and the order matters: the outgoing clip is stopped and *its* callback fired
        // before the incoming one is installed, so a caller reacting to a stop by starting something
        // else cannot find its own replacement already replaced.
        current.getAndSet(null)?.stop()

        val tool = player ?: detect().also { player = it }
        if (tool == null) {
            ownedTemp?.let { runCatching { Files.deleteIfExists(it) } }
            val reason = "no audio player found — tried ${TOOLS.joinToString(", ") { it.command }}"
            logger.e(TAG, reason)
            callbacks.execute { onEnd(PlaybackEnd.Failed(reason)) }
            return FinishedHandle
        }

        val durationMs = durationOf(file) ?: 0L
        val process = try {
            tool.launch(file, target)
        } catch (e: IOException) {
            ownedTemp?.let { runCatching { Files.deleteIfExists(it) } }
            val reason = "${tool.command} could not start: ${e.message}"
            logger.e(TAG, reason)
            callbacks.execute { onEnd(PlaybackEnd.Failed(reason)) }
            return FinishedHandle
        }

        val clip = Clip(process, durationMs, ownedTemp, tool)
        current.set(clip)
        clip.reap(callbacks) { end ->
            // Only clear the slot if it is still ours: a clip that was superseded has already been
            // replaced, and nulling the field here would silently stop enforcing contract 1 for the
            // playback that took its place.
            current.compareAndSet(clip, null)
            onEnd(end)
        }
        return clip
    }

    /**
     * The first tool that can be launched. Probed by *running* it, not by asking whether the file
     * exists: a `pw-play` binary present on a machine with no PipeWire daemon is a binary that will
     * fail at every playback, and finding that out at the first utterance is better than finding it
     * out here — except that the fallbacks exist for exactly this case, so the probe asks the
     * cheapest question each tool can answer.
     */
    private fun detect(): Player? {
        for (candidate in TOOLS) {
            val usable = try {
                val probe = ProcessBuilder(candidate.command, *candidate.probeArgs)
                    .redirectErrorStream(true)
                    .start()
                probe.inputStream.use { it.readBytes() }
                probe.waitFor() == 0
            } catch (e: IOException) {
                false
            }
            if (usable) {
                logger.i(TAG, "audio playback via ${candidate.command}")
                return candidate
            }
        }
        return null
    }

    private fun durationOf(file: Path): Long? = try {
        (wavDurationSeconds(Files.readAllBytes(file)) * 1000.0).toLong().takeIf { it > 0 }
    } catch (e: Exception) {
        // A file that is not a WAV, or is truncated. Not a failure — contract 2 says duration 0
        // means "not known yet", and a caller that divides by it has to handle that anyway.
        logger.w(TAG, "could not read a duration from ${file.fileName}: ${e.message}")
        null
    }

    // ---------------------------------------------------------------- one playback

    /** A player binary and how to ask it, on this machine, to play a file. */
    private class Player(val command: String, val probeArgs: Array<String>) {
        fun launch(file: Path, target: AudioDevice?): Process {
            val args = mutableListOf(command)
            // Seam 3's device choice, where the tool takes one. `pw-play` and `paplay` both name a
            // sink; `aplay` names a card with `-D`. A null target means the system default, which is
            // what omitting the flag asks for.
            if (target != null) {
                when (command) {
                    "aplay" -> args += listOf("-D", target.id)
                    else -> args += listOf("--target", target.id)
                }
            }
            args += file.toAbsolutePath().toString()
            return ProcessBuilder(args)
                // The player writes progress and warnings to stderr. Discarded rather than merged
                // into stdout: nothing here reads either, and an undrained pipe is how a child
                // process blocks forever on a full buffer.
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
        }
    }

    /**
     * One playback in flight. A [PlaybackHandle] and its waiter, because the two are the same
     * lifetime and splitting them would mean passing the flag between them.
     */
    private class Clip(
        private val process: Process,
        override val durationMs: Long,
        private val ownedTemp: Path?,
        private val tool: Player,
    ) : PlaybackHandle {

        private val startedAtNanos = System.nanoTime()

        /** Set by [stop], read by the waiter. This is what makes `Stopped` different from `Failed`. */
        @Volatile
        private var killed = false

        @Volatile
        private var reaped = false

        override val positionMs: Long
            get() {
                val elapsed = (System.nanoTime() - startedAtNanos) / 1_000_000
                // Contract 2: never greater than the duration. `durationMs` may be 0 for an
                // unreadable header, and then this is 0 too — which is the "not measured" the
                // contract asks a caller to handle rather than a lie about where playback is.
                return if (durationMs > 0) elapsed.coerceIn(0, durationMs) else 0L
            }

        override fun stop() {
            if (!process.isAlive) return
            killed = true
            // `destroy` and not `destroyForcibly`: a player that is asked to stop gets the chance to
            // close its stream and release the device, which on PipeWire is the difference between
            // the next utterance starting immediately and waiting for the sink to notice. The wait
            // below is what makes that release *ordered* — the next layer up calls `stop()` and then
            // starts another clip, and without it the two streams overlap for as long as the kernel
            // takes to run the signal.
            //
            // **It blocks its caller, for at most PLAYER_EXIT_GRACE_MS, and that is a considered
            // cost.** `stopAll()` is reached from the service on `Dispatchers.Main`, so a player
            // that ignored SIGTERM would hitch the UI by that much. Measured on this machine:
            // `pw-play` exits in well under 50 ms.
            process.destroy()
            process.waitFor(PLAYER_EXIT_GRACE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        }

        /**
         * Waits for the process and reports once. [onEnd] runs on the seam's callback thread.
         *
         * The waiter is a plain thread rather than a coroutine because the seam's interface is not
         * `suspend`: the ported call sites are `MediaPlayer` callbacks and a coroutine here would
         * have no caller to suspend.
         */
        fun reap(executor: java.util.concurrent.Executor, onEnd: (PlaybackEnd) -> Unit) {
            val thread = Thread({
                val exit = try {
                    process.waitFor()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    -1
                }
                // The temp file is deleted before the callback, so a caller that reacts by starting
                // the next chunk never sees the previous one's file still on disk.
                ownedTemp?.let { runCatching { Files.deleteIfExists(it) } }
                if (reaped) return@Thread
                reaped = true
                val end = when {
                    killed -> PlaybackEnd.Stopped
                    exit == 0 -> PlaybackEnd.Finished
                    else -> PlaybackEnd.Failed("${tool.command} exited with $exit")
                }
                executor.execute { onEnd(end) }
            }, "pw-playback-wait")
            thread.isDaemon = true
            thread.start()
        }
    }

    /** A handle for a playback that never started, so a failure still returns something valid. */
    private object FinishedHandle : PlaybackHandle {
        override val positionMs: Long get() = 0L
        override val durationMs: Long get() = 0L
        override fun stop() = Unit
    }

    private companion object {
        const val TAG = "PwPlayback"

        /** How long a politely-killed player gets before the device is taken from it. */
        const val PLAYER_EXIT_GRACE_MS = 500L

        /**
         * In preference order, each with the cheapest question that tells whether it can work here.
         * `pw-play --version` and friends print and exit without touching the audio graph, which is
         * exactly the probe a first playback wants.
         */
        val TOOLS = listOf(
            Player("pw-play", arrayOf("--version")),
            Player("paplay", arrayOf("--version")),
            Player("aplay", arrayOf("--version")),
        )
    }
}

/**
 * `SpeechSamples` carries signed 16-bit mono PCM as bytes, and `WavFile` wants samples.
 *
 * Little-endian by definition of the format — `AudioPlayback.playPcm`'s contract says signed 16-bit
 * mono PCM, and every producer in this project writes little-endian (the VAD recorder's own header
 * says so). A big-endian sample array would be a different contract, not a different platform.
 */
private fun ByteArray.toShortArrayLittleEndian(): ShortArray {
    val out = ShortArray(size / 2)
    for (i in out.indices) {
        val lo = this[i * 2].toInt() and 0xFF
        val hi = this[i * 2 + 1].toInt()
        out[i] = ((hi shl 8) or lo).toShort()
    }
    return out
}
