package com.nikosm.voiceassistant

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.Volatile

/**
 * The hands-free capture loop: read 32 ms chunks, ask the VAD about each one, and cut an utterance
 * when the speech has stopped. PLAN.md §7 marks this NEW rather than ported, and §6 lists what must
 * survive the rewrite — this is the file where "keep the behaviour, replace the device access" is
 * meant most literally.
 *
 * **Every constant and every invariant is preserved, and they are not tuning.** Each one was
 * arrived at against real speech, and §6 names the pre-roll as the clearest case: the comment
 * explaining it is upstream's, kept verbatim below, because "dropping it clipped short words ("Hi",
 * "Go") badly enough to confuse STT" is the sort of thing that is re-learned the hard way.
 *
 * Three things changed, and only three:
 *
 *  - **`AudioRecord` became [AudioCapture].** The open / read / close sequence is the same
 *    sequence; the device access is the seam's business now, which is what makes this class
 *    testable without a microphone and portable to a machine whose audio is a subprocess.
 *  - **`AcousticEchoCanceler` is gone, permanently.** §3/D8 decides there is no echo cancellation
 *    on this platform — headphones or push-to-talk — and §9 records it in the substitution table.
 *    It is not deferred and not stubbed; the ten-line attach block and its release counterpart are
 *    deleted, and `AudioCapture.open` has no session id for one to attach to. If it is ever wanted
 *    it is a PipeWire `module-echo-cancel` sink selected as the input device, which is no
 *    application code at all.
 *  - **`File` became `Path`**, and the WAV is written by [WavFile] rather than by a private header
 *    function here. Push-to-talk writes the same container from the same seam, and one layout with
 *    two writers is one layout too many.
 *
 * The loop also marks PLAN.md §6's two budget spans through [AudioLatency]. That is not a fourth
 * change: no branch, threshold or constant below is affected by the marks, and the class exists so
 * the numbers an acceptance run is judged on are measured rather than remembered.
 *
 * The two locks are upstream's, with their reasoning: [dataLock] guards the sample buffers and is
 * never held across file I/O or a suspension point — the comment below explains the
 * ConcurrentModificationException that made that rule — and the detector keeps its own lock so that
 * inference never runs under the data lock.
 *
 * Logging goes through [LogSink]: this runs on an IO coroutine launched from wherever the service
 * put it, and upstream logged through the global `android.util.Log` for the same reason.
 */
class VADAudioRecorder(
    private val capture: AudioCapture,
    private val detector: SpeechDetector,
    private val cacheDir: Path,
    private val scope: CoroutineScope,
    /**
     * The microphone to listen on, or null for the sound server's default — the user's choice
     * from Settings, resolved once when the session starts.
     *
     * Read once rather than per open, because there is one open per session: the service builds a
     * new recorder each time hands-free is switched on, which is also when a changed choice is
     * picked up.
     */
    private val inputDevice: AudioDevice?,
    private val onSpeechStart: () -> Unit,
    private val onSpeechEnd: (Path) -> Unit,
    private val onAmplitudeUpdate: (Float) -> Unit = {},
    /**
     * The microphone is gone — reported once, on Main, with a user-facing reason.
     *
     * The loop is the only thing that can notice: the capture stream ends when `pw-record` exits,
     * the device is unplugged or the sound server restarts, and a service that is not told keeps
     * `handsFreeMode` on, leaves the tray claiming to listen, and never stops a recorder that has
     * already stopped itself. That is the 2026-10-19 review's silent-failure gap.
     *
     * Deliberately **not** reported when [stop] ends the loop: a user switching hands-free off and
     * a microphone disappearing both take the same `break`/`finally`, and only `isActive` tells
     * them apart. [resume] cannot revive a dead recorder — the service releases it and rebuilds
     * one when the user starts listening again.
     */
    private val onDied: (String) -> Unit = {}
) {
    private var job: Job? = null

    /** Exactly one death report per session; only the loop's own coroutine touches it. */
    private var deathReported = false

    /**
     * PLAN.md §6's two budget spans, measured where the loop can mark them.
     *
     * Built rather than injected: the loop that drives it cannot be constructed in a test either
     * way (see [AudioLatency]'s own note), so an injection point here would be a seam with one
     * implementation and no second caller — while the arithmetic the numbers come from is already
     * pinned by `AudioLatencyTest` against a fake clock.
     */
    private val latency = AudioLatency()

    private val sampleRate = 16000
    private val chunkSize = 512

    private var isMonitoring = false
    private var isRecording = false
    private var speechFrames = 0
    private var silenceFrames = 0

    private val speechThreshold = 3 // ~100ms
    private val silenceThreshold = 60 // ~2.0s
    private val probThreshold = 0.5f

    private val recordedData = mutableListOf<ShortArray>()

    // Guards recordedData: the recording loop mutates it on Dispatchers.IO while stop()
    // (Main thread) can clear it concurrently. Unsynchronized add/clear interleaving can
    // tear the list state, and stop() landing mid-saveToWav() made its iterator throw
    // ConcurrentModificationException — silently caught, killing the recording coroutine,
    // dropping the finished utterance and leaving hands-free VAD dead until re-toggled.
    // Held only for microsecond-scale in-memory ops (add/clear/snapshot) — never file
    // I/O, never across a suspension — so Main's stop() never blocks for long and no
    // deadlock cycle exists with VADDetector's own internal state lock.
    private val dataLock = Any()

    // M-pre-roll: ring buffer of the most recent raw chunks, captured continuously in
    // the idle/pre-detection phase. The trigger needs ~3 chunks (~100ms) of consistent
    // speech before isRecording flips — without this, the first two of those chunks
    // (the actual onset of a short word: "Hi", "Go") were physically discarded,
    // clipping short utterances enough to confuse STT. Capacity 3 (~96ms) covers the
    // trigger latency plus one jitter frame. On trigger the buffered chunks are
    // PREPENDED to recordedData (the capture happens after the live-append, so the
    // trigger chunk itself is never doubled), then the ring clears and live appending
    // continues. Shares dataLock with recordedData rather than a separate lock: both
    // buffers are "audio of the current utterance", they are mutated at exactly the
    // same points (trigger-prepend, speech-end, stop, mute/pause reset), and one lock
    // keeps a single consistent snapshot boundary — with the same rules as before
    // (tiny in-memory ops only, never held across suspension or file I/O).
    private val preRollChunks = ArrayDeque<ShortArray>()
    private val preRollCapacity = 3

    // M4-adjacent: written from Main (toggleMicMute / the state watcher's pause()),
    // read on the IO recording loop — @Volatile removes the visibility lag.
    @Volatile
    var muted = false

    @Volatile
    var isPaused = false
        private set

    /**
     * Set by [resume] and acted on by the loop, because the drain is a read and reads belong to the
     * thread that owns the stream — a `resume()` that drained from Main would be file I/O on the UI
     * thread, and a race with the loop's own read into the bargain.
     */
    @Volatile
    private var drainPending = false

    fun start() {
        if (isMonitoring) return
        isMonitoring = true
        isPaused = false
        deathReported = false
        job = scope.launch(Dispatchers.IO) {
            // §6's first span starts here: this call is where the capture process is spawned, and
            // the next chunk to come out of it is what the 50 ms budget is about.
            latency.captureStarted()
            // Null means no microphone could be opened — no sound server, no input device, or a
            // helper binary that is not installed. Upstream has the same shape for the same
            // reason: it logs once and stops monitoring rather than retrying into a hot loop.
            val stream = capture.open(sampleRate, chunkSize, inputDevice)
            latency.captureOpened()
            if (stream == null) {
                isMonitoring = false
                val reason = if (inputDevice == null) "no capture stream could be opened"
                else "the selected microphone could not be opened (${inputDevice.label})"
                LogSink.logger.e("VADAudioRecorder", reason)
                // Monitoring never began, but the service has already been told listening is on
                // (startVadListening sets `handsFreeMode` immediately after this call returns).
                // Reporting the same event is what makes the toggle honest instead of leaving it
                // on over a recorder that never opened anything.
                reportDeath(reason)
                return@launch
            }

            try {
                val buffer = ShortArray(chunkSize)

                LogSink.logger.d("VADAudioRecorder", "VAD monitoring started")

                while (isActive && isMonitoring) {
                    if (isPaused || muted) {
                        if (isRecording) {
                            isRecording = false
                            synchronized(dataLock) {
                                recordedData.clear()
                                preRollChunks.clear()
                            }
                            detector.reset()
                        }
                        onAmplitudeUpdate(0f)
                        delay(200)
                        continue
                    }

                    // The reply is over: throw away what the pipe collected while nobody was
                    // reading (§6's resume span, and the reason its first chunk means anything).
                    if (drainPending) {
                        drainPending = false
                        stream.drain()
                        latency.drainDone()
                    }

                    val read = stream.read(buffer, chunkSize)
                    // A read of 0 is the stream *ending*, not a short frame: the recorder process
                    // exited, the microphone was unplugged, or the sound server restarted. There
                    // will be no more samples, so this stops rather than reading again. A negative
                    // return means the same thing in the seam's contract, and treating only 0 as
                    // the end would make any implementation that uses the negative form spin.
                    //
                    // Without this the loop spun: it only consumed a full chunk, so a stream that
                    // had ended produced a warning per iteration for as long as the application
                    // stayed open — a dead microphone as a hot loop, which is the worst shape a
                    // failure can take. Upstream could not hit this, because `AudioRecord.read`
                    // blocks on a device that is still open and an unplugged phone microphone is
                    // not a thing; a subprocess that can exit is.
                    if (read <= 0) {
                        LogSink.logger.w("VADAudioRecorder", "Capture stream ended — stopping VAD monitoring")
                        reportDeath("the capture stream ended")
                        break
                    }
                    if (read != chunkSize) {
                        LogSink.logger.w("VADAudioRecorder", "Unexpected read size: $read (expected $chunkSize)")
                    }
                    if (read == chunkSize) {
                        // §6's first span ends the moment the samples are in hand — before the
                        // amplitude scan and the inference below, which are downstream work rather
                        // than capture latency. The second call is the resume span, and is silent
                        // unless a resume is pending.
                        latency.firstChunkArrived()
                        latency.firstChunkAfterResume()

                        // Max absolute amplitude for the visualizer
                        var maxAmp = 0
                        for (i in 0 until chunkSize) {
                            val abs = Math.abs(buffer[i].toInt())
                            if (abs > maxAmp) maxAmp = abs
                        }
                        onAmplitudeUpdate(maxAmp / 32767f)

                        val floatData = FloatArray(chunkSize) { buffer[it] / 32768.0f }
                        val prob = detector.isSpeech(floatData)

                        if (prob > probThreshold) {
                            speechFrames++
                            silenceFrames = 0
                        } else {
                            silenceFrames++
                            speechFrames = 0
                        }

                        if (!isRecording && speechFrames >= speechThreshold) {
                            isRecording = true
                            withContext(Dispatchers.Main) { onSpeechStart() }
                            synchronized(dataLock) {
                                // Prepend the pre-trigger chunks — they include the
                                // onset that actually caused the detection — ahead of
                                // the live append below, so nothing before isRecording
                                // is lost. Clearing the ring afterwards lets it refill
                                // with fresh audio for the next utterance.
                                recordedData.addAll(0, preRollChunks)
                                preRollChunks.clear()
                            }
                        }

                        if (isRecording) {
                            synchronized(dataLock) { recordedData.add(buffer.copyOf()) }
                            if (silenceFrames >= silenceThreshold) {
                                isRecording = false
                                // §6's second span: this line is the verdict that the utterance
                                // has ended, and `saveToWav` returning is it being on disk.
                                latency.utteranceEnded()
                                val file = saveToWav()
                                // The size is what tells a whole utterance from a clipped one in
                                // the log. Unreadable is not worth failing an utterance over, so
                                // it degrades to null rather than throwing out of the loop.
                                latency.wavWritten(runCatching { Files.size(file) }.getOrNull())
                                isPaused = true // Pause until resumed by service (e.g. after response)
                                withContext(Dispatchers.Main) { onSpeechEnd(file) }
                                synchronized(dataLock) {
                                    recordedData.clear()
                                    preRollChunks.clear()
                                }
                                detector.reset()
                                speechFrames = 0
                                silenceFrames = 0
                            }
                        }

                        // M-pre-roll capture: while not recording, continuously keep the
                        // most recent chunks (oldest evicted at capacity). Captured AFTER
                        // the append block above so the chunk that trips the threshold is
                        // appended live exactly once and never duplicated in the ring.
                        if (!isRecording) {
                            synchronized(dataLock) {
                                if (preRollChunks.size >= preRollCapacity) preRollChunks.removeFirst()
                                preRollChunks.addLast(buffer.copyOf())
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                // `stop()` cancels this job, so a cancellation here is how monitoring is *ended*,
                // not a fault in it. It arrives at a suspension point — the paused branch's
                // `delay(200)` is the usual one, since that is where the loop sits between an
                // utterance and the reply — and reported as an error it read as a crash on every
                // stop: "Recording error: StandaloneCoroutine was cancelled" at SEVERE, which is
                // what the 2026-10-16 run shows. Rethrown rather than swallowed: the coroutine is
                // cancelled and must stay cancelled, and this is the value `launch` expects.
                throw e
            } catch (e: Exception) {
                LogSink.logger.e("VADAudioRecorder", "Recording error: ${e.message}")
                // A detector or file failure ends this recorder just as surely as an unplugged
                // microphone does, and leaving the service believing it is listening is the same
                // silent failure. `reportDeath` filters the deliberate-stop case (the job is
                // cancelled by then, so `isActive` is false).
                reportDeath("recording error: ${e.message}")
            } finally {
                // Released with the stream it belongs to. Upstream released its
                // AcousticEchoCanceler here too, before the record it was attached to went away;
                // there is no such effect on this platform (§3/D8), so this is the whole teardown.
                stream.close()
                isMonitoring = false
            }
        }
    }

    /**
     * Reports a microphone loss once, and only while the loop is still running.
     *
     * `isActive` is the discriminator between "the stream died on us" and "the user switched
     * hands-free off": [stop] cancels [job] before the loop reaches its failure branches, so a
     * deliberate stop can never be announced as a fault. The flag is set before the Main hop, so
     * an exception thrown by the callback itself cannot re-enter through `catch (Exception)` and
     * report twice.
     */
    private suspend fun reportDeath(reason: String) {
        if (deathReported || !currentCoroutineContext().isActive) return
        deathReported = true
        withContext(Dispatchers.Main) { onDied(reason) }
    }

    fun stop() {
        isMonitoring = false
        job?.cancel()
        job = null
        isRecording = false
        onAmplitudeUpdate(0f)
        // Runs on Main while the IO loop may be mid-iteration; the lock makes this clear
        // atomic against add() and saveToWav()'s snapshot (the CME source). The loop can
        // still append one orphan chunk if it already passed its flag check — harmless,
        // the next start()/speech-start clears it.
        synchronized(dataLock) {
            recordedData.clear()
            preRollChunks.clear()
        }
        detector.reset()
        LogSink.logger.d("VADAudioRecorder", "VAD monitoring stopped")
    }

    fun pause() {
        isPaused = true
        LogSink.logger.d("VADAudioRecorder", "VAD Monitoring Paused")
    }

    fun resume() {
        isPaused = false
        // Marked here, acted on by the loop: this is the instant the user's "can it hear me now?"
        // starts from, and it is the service's state collector that decides it (IDLE after a
        // reply), not the loop.
        drainPending = true
        latency.resumed()
        detector.reset()
        speechFrames = 0
        silenceFrames = 0
        LogSink.logger.d("VADAudioRecorder", "VAD Monitoring Resumed")
    }

    private fun saveToWav(): Path {
        // Snapshot the chunk list under the lock (a cheap reference copy), then release
        // the lock BEFORE the file I/O: stop() must never block for the write duration,
        // and iterating the live list let stop()'s clear() throw
        // ConcurrentModificationException mid-save.
        val chunks = synchronized(dataLock) { ArrayList(recordedData) }
        val file = cacheDir.resolve("vad_recording_${System.currentTimeMillis()}.wav")
        // The header is [WavFile]'s now, and it is shared with push-to-talk — two writers of one
        // layout is how a header and the reader that parses it (`wavDurationSeconds`) drift apart
        // without anything throwing.
        WavFile.write(file, chunks, sampleRate)
        return file
    }
}
