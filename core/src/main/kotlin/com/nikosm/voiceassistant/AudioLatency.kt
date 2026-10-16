package com.nikosm.voiceassistant

/**
 * PLAN.md §6's latency budget, measured where it can be and reported in the plan's own terms.
 *
 * §6 states two spans as M2's to verify. Both are on the capture path rather than around the round
 * trip ("WAV to `/transcribe` to reply to first audio out is dominated by the gateway and the
 * model", so that leg is deliberately not measured here), and the first acceptance run named a
 * third that §6 never did:
 *
 *  - **capture start → first chunk, < 50 ms.** [VADAudioRecorder] marks the moment before it asks
 *    seam 1 to open a stream — the `pw-record`/`parecord`/`arecord` spawn is inside that call — and
 *    reports when the first full 512-frame chunk is in hand. The mark is taken *before* the
 *    amplitude scan and the VAD inference that follow the read: those are downstream work, and
 *    charging them to the capture path would measure the wrong thing.
 *  - **utterance end → WAV on disk, < 100 ms.** From the VAD's verdict that speech has stopped to
 *    the return of [WavFile.write], which closes its stream on every path — so "returned" is "the
 *    file is whole".
 *  - **resume after a reply → the first live chunk** ([resumed], [drainDone],
 *    [firstChunkAfterResume]). §6 never named this one, and it is the span a user actually feels:
 *    after the reply stops, how long before the microphone is listening to the room again. It is
 *    measured from the resume to the first chunk that is *new* — the backlog the paused pipe
 *    accumulated is drained first (`CaptureStream.drain`), because a first chunk that is two
 *    seconds old arrives in zero milliseconds and means nothing. **It has no budget yet, on
 *    purpose:** the first measurement decides the number, and §6 is the file that states
 *    acceptance, so a figure invented here would be a guess wearing a budget's clothes.
 *
 * The span begins inside the recorder's coroutine, not at `start()`. The dispatch hop from whichever
 * thread called `start()` to `Dispatchers.IO` is real, but it is not the capture path, and a budget
 * that quietly absorbed it would blame `pw-record` for the scheduler.
 *
 * ## Why a class rather than two `System.nanoTime()` pairs in the loop
 *
 * Because a measurement nobody can test is a measurement nobody should trust, and the loop cannot be
 * driven in a test: its detector is a concrete [VADDetector] wrapping ONNX. PLAN.md §11 gives the
 * voice loop a fake gateway and VAD fixtures in M7. Extracting the arithmetic leaves these numbers —
 * and the wording the acceptance run is read from — pinned by a fake clock, with the call sites left
 * as the five lines of wiring they should be.
 *
 * ## The lines log at INFO, and that is not an accident
 *
 * [StderrLogger] is `java.util.logging` at its default level, where INFO is the lowest thing that
 * reaches the terminal and DEBUG is FINE and therefore invisible — "VAD monitoring started" is a
 * DEBUG line and stays quiet. These are the evidence M2's acceptance run is judged on, so they must
 * arrive. It is a handful of lines per session, not a stream.
 *
 * ## The capture span is reported split, because the two halves have different repairs
 *
 * `open` and the first `read` are logged separately alongside the total. A budget missed inside
 * `open` — the subprocess spawn, or the process-liveness grace `ProcessAudioCapture` waits out — is
 * a different change from one missed because the first chunk was slow to come out of the pipe, and a
 * single total would not say which one happened.
 */
internal class AudioLatency(
    /** Monotonic: a wall clock can step backwards mid-span and report a negative latency. */
    private val nanoTime: () -> Long = System::nanoTime,
    private val logger: AppLogger = LogSink.logger,
) {

    private var captureStartNanos = 0L
    private var openDoneNanos = 0L
    private var firstChunkReported = false

    private var utteranceEndNanos = 0L

    private var resumeNanos = 0L
    private var drainDoneNanos = 0L
    private var resumePending = false

    /** A capture session begins: seam 1 is about to be asked to open a stream. */
    fun captureStarted() {
        captureStartNanos = nanoTime()
        openDoneNanos = 0L
        firstChunkReported = false
    }

    /** `open` returned — a stream or a null, either way the wait is over and it is measurable. */
    fun captureOpened() {
        openDoneNanos = nanoTime()
    }

    /**
     * The first full chunk of this session is in hand.
     *
     * Idempotent by design rather than by accident: this is called from the hot loop, where every
     * chunk passes, and only the first one is the span. A session that reopens (the recorder is
     * rebuilt when hands-free is toggled) starts a new span through [captureStarted].
     */
    fun firstChunkArrived() {
        if (firstChunkReported) return
        firstChunkReported = true
        val now = nanoTime()
        val total = millis(captureStartNanos, now)
        val open = millis(captureStartNanos, openDoneNanos)
        val read = millis(openDoneNanos, now)
        logger.i(
            TAG,
            "capture start → first chunk: $total ms (${verdict(total, CAPTURE_BUDGET_MS)}; " +
                "open $open ms, first read $read ms)",
        )
    }

    /** Listening resumes: the reply is over and the microphone is about to be read again. */
    fun resumed() {
        resumeNanos = nanoTime()
        drainDoneNanos = 0L
        resumePending = true
    }

    /** The backlog is discarded. Half of the resume span, and the half that is a design cost. */
    fun drainDone() {
        drainDoneNanos = nanoTime()
    }

    /**
     * The first *live* chunk after a resume is in hand.
     *
     * Silent unless a resume is pending — which is what keeps the first chunk of an ordinary
     * session from being reported as a resume, and what makes a second resume a second line rather
     * than a correction of the first.
     */
    fun firstChunkAfterResume() {
        if (!resumePending) return
        resumePending = false
        val now = nanoTime()
        val total = millis(resumeNanos, now)
        val drain = millis(resumeNanos, drainDoneNanos)
        val read = millis(drainDoneNanos, now)
        logger.i(TAG, "resume → first live chunk: $total ms (drain $drain ms, first read $read ms)")
    }

    /** The VAD has decided the utterance is over; the WAV is about to be written. */
    fun utteranceEnded() {
        utteranceEndNanos = nanoTime()
    }

    /**
     * The WAV is on disk. [bytes] is its size, which is what says the utterance was not clipped —
     * null when the size could not be read back, which is not worth failing an utterance over.
     */
    fun wavWritten(bytes: Long?) {
        val total = millis(utteranceEndNanos, nanoTime())
        val size = if (bytes == null) "size unreadable" else "$bytes bytes"
        logger.i(TAG, "utterance end → WAV: $total ms (${verdict(total, WAV_BUDGET_MS)}; $size)")
    }

    private fun millis(from: Long, to: Long): Long = (to - from) / 1_000_000

    /**
     * §6 states both numbers as strict upper bounds ("< 50 ms"), so a span exactly equal to its
     * budget is reported as over — by zero, which is what the plan's `<` says and is the honest
     * reading at millisecond resolution.
     */
    private fun verdict(measuredMs: Long, budgetMs: Long): String =
        if (measuredMs < budgetMs) "${budgetMs - measuredMs} ms under the $budgetMs ms budget"
        else "${measuredMs - budgetMs} ms over the $budgetMs ms budget"

    private companion object {
        const val TAG = "AudioLatency"

        /** PLAN.md §6: "capture start to first chunk < 50 ms". */
        const val CAPTURE_BUDGET_MS = 50L

        /** PLAN.md §6: "utterance end to WAV on disk < 100 ms". */
        const val WAV_BUDGET_MS = 100L
    }
}
