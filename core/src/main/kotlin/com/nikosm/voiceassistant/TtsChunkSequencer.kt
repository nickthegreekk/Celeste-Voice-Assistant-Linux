package com.nikosm.voiceassistant

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.file.Path
import kotlin.coroutines.resume

/**
 * A running chunk sequence. [cancel] is the whole public surface beyond reading back what played.
 */
interface ChunkSequence {
    /**
     * Ends this sequence. Idempotent, and safe to call from anywhere — including from a playback
     * callback that the sequence itself is not waiting on any more.
     *
     * After this returns, no further chunk will start and no callback from a late playback can
     * advance anything. That guarantee is the reason this interface exists rather than the caller
     * comparing a generation counter it also has to remember to compare.
     */
    fun cancel()

    /** The chunk files actually played, in order. Fills in as the sequence runs. */
    val played: List<Path>

    /**
     * Suspends until this sequence ends, and reports how it ended.
     *
     * A sequence is started, not awaited: [TtsChunkSequencer.start] hands it to a scope and returns.
     * A caller that is itself a `suspend` function and has to act *after* the audio — deciding
     * whether anything was spoken, reporting a failure at the point of use — needs this rather than
     * reading [played] the instant [TtsChunkSequencer.start] returns, which is always empty.
     *
     * A cancelled sequence ends as [PlaybackEnd.Stopped] — never [PlaybackEnd.Finished]. That is the
     * difference between "the reply was heard" and "something replaced it", and a caller that
     * conflated the two would report a superseded utterance as a completed one.
     */
    suspend fun awaitEnd(): PlaybackEnd
}

/**
 * What one chunk's synthesis produced: the file to play, or the reason there is none.
 *
 * This was a nullable `Path`, and the reason went with the null. The sequence could then report
 * only "chunk 0 could not be synthesised" — which names neither the cause nor the route that
 * produced it. The user's run on 2026-10-16 has three of those lines and nothing that tells them
 * apart or acts on them, while the actual answer ("Russian has no TTS voice available — text only",
 * because the reply quoted a Russian phrase and the gateway was asked for a Russian voice) was
 * being logged at DEBUG and never printed.
 *
 * So the failure travels with the chunk, in the caller's words: only the caller knows which engine
 * it asked, what it asked for, and what came back.
 */
sealed interface Synthesis {

    /** This chunk is ready to play. */
    data class Ready(val file: Path) : Synthesis

    /**
     * There is nothing to play for this chunk, and [reason] says why — a refusal, an empty chunk,
     * a superseded turn, a file that could not be written. It reaches [PlaybackEnd.Failed] verbatim.
     */
    data class Unavailable(val reason: String) : Synthesis
}

/**
 * Plays a synthesised reply one chunk at a time, in order.
 *
 * ## Why this is a class and not code in the service
 *
 * Upstream keeps this logic inside `AssistantServiceAudio.kt`, fused to a live service and a real
 * `MediaPlayer`: three-deep `postDelayed` polling, `currentPlayer === mp` identity comparisons in
 * every callback, and state flips interleaved with the sequencing. **It has no test**, and it
 * cannot have one — reaching any of these branches needs a device, a player and a service.
 *
 * So the sequencing is extracted. This class owns chunk order, the one-at-a-time rule, the
 * cancel-previous guard and the progress arithmetic, and it is driven entirely through
 * [AudioPlayback] — which means `FakeAudioPlayback` can drive it, and all four behaviours are
 * pinned by tests with no audio hardware, no service, and no M2 implementation. What stays in the
 * service is what genuinely belongs to it: state transitions, publishing `voiceDuration`, and the
 * TTS generation counter.
 *
 * ## The two things upstream does that are easy to lose
 *
 * **Synthesis runs alongside playback.** Chunk 0 is synthesised, playback starts, and chunk 1 is
 * being synthesised while chunk 0 plays — that interleaving is the entire point of the chunked
 * pipeline, and it is the first-audio latency PLAN.md §6 cares about. Here the synthesis loop and
 * the playback loop are separate coroutines and the playback loop awaits each chunk's
 * [CompletableDeferred]; a naive sequential implementation would synthesise everything first and
 * lose the overlap without any test noticing.
 *
 * **The duration estimate is refined as chunks arrive.** Upstream measures chunk 0, estimates the
 * whole reply from its text-to-audio ratio, and re-estimates on every subsequent chunk, so the
 * karaoke highlight covers the full response rather than chunk 0's few seconds. The arithmetic is
 * upstream's, including the 64-bit product: `cumulativeDurationMs * fullTextLen` overflows 32-bit
 * around 5.6k characters, and the wrapped negative value froze the fraction at 0 *and* published a
 * negative duration, killing the highlight and the reveal together. That is not a hypothetical —
 * it is a bug someone found in a long reply — so the Long arithmetic and the clamp are kept
 * exactly, and the test that covers it uses a reply long enough to have triggered it.
 */
class TtsChunkSequencer(private val playback: AudioPlayback) {

    /**
     * The sequence currently running, if any. Held so a second utterance can end the first —
     * PLAN.md §6's guard, and the reason [start] does not simply add another player.
     */
    private var active: Sequence? = null

    /**
     * Begins playing [chunks] in order.
     *
     * [synthesize] is called once per chunk, in order, on [scope], and may run concurrently with
     * playback of an earlier chunk. [Synthesis.Unavailable] means that chunk could not be produced,
     * and its reason is carried into [PlaybackEnd.Failed] — the message a caller reports and a user
     * reads, so the caller's words are what belongs there.
     *
     * [onStarted] fires **once, when the first chunk's playback begins** — and the "once, first
     * chunk" is the whole contract, not an implementation detail. Upstream enters its SPEAKING
     * state at exactly that moment and holds it across the entire sequence: one continuous window
     * from the first word to the last, never SPEAKING→IDLE→SPEAKING between chunks. The state is
     * what pauses the VAD and what the UI reads, so a version that fired per chunk would flicker
     * listening back on between every 80-character paragraph, and a version that fired on
     * sequence creation would claim to be speaking before any audio existed. Do not "fix" it to
     * fire more often.
     *
     * It fires *after* chunk 0 has been synthesised and *before* [AudioPlayback.play] is called, so
     * a caller reacting to it is setting up state for audio that is about to start rather than
     * audio that already has.
     *
     * Starting a sequence cancels whatever was running. Cancellation is not an error: the previous
     * sequence's [onEnd] receives [PlaybackEnd.Stopped].
     */
    fun start(
        chunks: List<String>,
        scope: CoroutineScope,
        synthesize: suspend (index: Int, text: String) -> Synthesis,
        onStarted: () -> Unit,
        onProgress: (fraction: Float, estimatedTotalMs: Int) -> Unit,
        onEnd: (PlaybackEnd) -> Unit,
        target: AudioDevice? = null,
    ): ChunkSequence {
        // Cancel-previous happens BEFORE the new sequence exists, so the outgoing sequence's
        // teardown cannot observe or disturb its replacement.
        active?.cancel()

        val sequence = Sequence(chunks, scope, synthesize, onStarted, onProgress, onEnd, target)
        active = sequence
        sequence.begin()
        return sequence
    }

    private inner class Sequence(
        private val chunks: List<String>,
        private val scope: CoroutineScope,
        private val synthesize: suspend (Int, String) -> Synthesis,
        private val onStarted: () -> Unit,
        private val onProgress: (Float, Int) -> Unit,
        private val onEnd: (PlaybackEnd) -> Unit,
        /**
         * The sink every chunk of this sequence plays into, from seam 3. Upstream re-derived it
         * per chunk from `earpieceMode.value` when it re-set the `AudioAttributes` on the recycled
         * player; here it is decided once by the caller that owns the setting and held for the
         * sequence, which is the same answer without the mid-sequence reread.
         */
        private val target: AudioDevice?,
    ) : ChunkSequence {

        /** One slot per chunk; the synthesis loop fills it, the playback loop awaits it. */
        private val ready: List<CompletableDeferred<Synthesis>> = List(chunks.size) { CompletableDeferred() }

        private val chunkTextLen: List<Int> = chunks.map { cleanTextForTts(it).length.coerceAtLeast(1) }
        private val fullTextLen: Int = chunkTextLen.sum().coerceAtLeast(1)

        private val playedFiles = mutableListOf<Path>()
        private var job: Job? = null
        private var currentHandle: PlaybackHandle? = null

        @Volatile
        private var cancelled = false

        // Upstream's estimate state. Long for the product: see the class comment.
        private var cumulativeDurationMs = 0L

        /**
         * Characters of audio measured so far — **characters, not chunks.**
         *
         * The distinction is the whole estimate: `cumulativeDurationMs * fullTextLen / preparedTextLen`
         * only lands on the reply's total length if both sides count the same thing. Accumulating a
         * chunk *count* here estimates a reply by dividing its character length by its chunk count,
         * which is meaningless and overshoots by roughly the characters-per-chunk — it read 12000 ms
         * for a three-chunk reply whose first chunk measured 1000 ms over four characters.
         */
        private var preparedTextLen = 0
        private var measuredChunks = 0
        private var estimatedTotalMs = 0

        override val played: List<Path> get() = synchronized(playedFiles) { playedFiles.toList() }

        /** Completed exactly once by whichever of [finish] and [cancel] gets there first. */
        private val endSignal = CompletableDeferred<PlaybackEnd>()

        override suspend fun awaitEnd(): PlaybackEnd = endSignal.await()

        fun begin() {
            job = scope.launch { run() }
        }

        override fun cancel() {
            if (cancelled) return
            cancelled = true
            // Release anything still waiting on a chunk that will never arrive, so no coroutine
            // is left parked on a deferred nobody will complete. The flag is what does the real
            // work; this is what stops a cancelled sequence leaving a suspended coroutine behind.
            // (The playback loop checks the flag before it reads a slot, so what it is released
            // *with* is never reported — the reason is here to be honest, not to be read.)
            ready.forEach { it.complete(Synthesis.Unavailable("the sequence was stopped")) }
            currentHandle?.stop()
            job?.cancel()
            // A cancelled sequence has still ended: a caller suspended in awaitEnd() must be
            // released rather than parked forever on a report that is deliberately not coming.
            // `finish` reports nothing for a cancelled sequence, so this is where that caller is
            // told — and `Stopped`, not `Finished`, because no reply was heard through.
            endSignal.complete(PlaybackEnd.Stopped)
        }

        private suspend fun run() {
            // `coroutineScope`, not `scope`, so everything this sequence starts — the synthesis
            // loop and each chunk's progress poller — is a child of the sequence's own job. A
            // poller launched on the caller's scope would outlive the sequence it belongs to:
            // a leak in production, and in a test a run that never settles.
            coroutineScope {
                // The synthesis loop runs CONCURRENTLY with playback — see the class comment. It
                // is deliberately not awaited before the playback loop starts; awaiting it here
                // would synthesise the whole reply before a single word was heard.
                val synthesis = launch {
                    for (i in chunks.indices) {
                        if (cancelled) {
                            ready[i].complete(Synthesis.Unavailable("the sequence was stopped"))
                            continue
                        }
                        val produced = try {
                            synthesize(i, chunks[i])
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // A failed synthesis is not fatal to the loop the way a failed
                            // playback is; this chunk reports nothing and playback decides. The
                            // throwable's own words go with it — a caller that threw rather than
                            // returned still said why.
                            Synthesis.Unavailable("synthesis failed: ${e.message ?: e::class.simpleName}")
                        }
                        ready[i].complete(produced)
                    }
                }

                try {
                    playInOrder(this)
                } finally {
                    synthesis.cancel()
                }
            }
        }

        private suspend fun playInOrder(seqScope: CoroutineScope) {
            for (index in chunks.indices) {
                if (cancelled) return

                val path = when (val produced = ready[index].await()) {
                    is Synthesis.Ready -> produced.file
                    is Synthesis.Unavailable -> {
                        // Covers both a cancelled sequence resuming on a released slot and a chunk
                        // that genuinely could not be produced — the `cancelled` check above has
                        // already returned for the first of those.
                        //
                        // Upstream's synthesis-failure path is a quirk rather than a decision: it
                        // `continue`s past a chunk, but its "start playback" branch is keyed on
                        // `index == 0`, so a failed FIRST chunk leaves the sequence with a
                        // non-empty file list and no playback, no cleanup and nothing to end it.
                        // Aborting the whole utterance is the behaviour a failure should have
                        // anyway, and the test name records that this is a choice rather than a
                        // copy. The reason is the caller's, and it is carried rather than dropped:
                        // "chunk 0 could not be synthesised" alone is a message that cannot be
                        // acted on, which is what the 2026-10-16 run showed three times.
                        finish(PlaybackEnd.Failed("chunk $index could not be synthesised: ${produced.reason}"))
                        return
                    }
                }

                // Once, for the first chunk only, and immediately before its playback starts.
                // See the contract on `start`: upstream holds one SPEAKING window across the
                // whole sequence, so firing per chunk would flicker the VAD and the UI.
                if (index == 0) onStarted()

                when (val end = playOne(seqScope, path, index)) {
                    is PlaybackEnd.Finished -> Unit            // fall through to the next chunk
                    is PlaybackEnd.Failed -> {
                        // Upstream's onErrorListener aborts the utterance; it does not skip to
                        // the next chunk. A reply that cannot be spoken part-way through is not
                        // improved by speaking the rest of it.
                        finish(end)
                        return
                    }
                    is PlaybackEnd.Stopped -> {
                        // Something stopped this playback — the Stop button, a newer sequence,
                        // or a newer utterance taking the guard. Either way this sequence is
                        // over and must not advance. Not reported as a failure: a replaced
                        // utterance is not one.
                        finish(PlaybackEnd.Stopped)
                        return
                    }
                }
            }
            finish(PlaybackEnd.Finished)
        }

        /** Plays one chunk and suspends until it ends, polling the progress while it does. */
        private suspend fun playOne(seqScope: CoroutineScope, path: Path, index: Int): PlaybackEnd {
            val cumulativeBefore = cumulativeDurationMs
            synchronized(playedFiles) { playedFiles.add(path) }

            return suspendCancellableCoroutine { continuation ->
                var poll: Job? = null

                val handle = playback.play(path, target) { end ->
                    poll?.cancel()
                    if (continuation.isActive) continuation.resume(end)
                }
                currentHandle = handle

                // The playback may have ended before the poller existed (a fake delivering
                // synchronously, or a failure on the very first frame), in which case nothing
                // would ever cancel it.
                if (!continuation.isActive) handle.stop()

                poll = seqScope.launch {
                    while (isActive) {
                        val duration = handle.durationMs
                        if (duration > 0 && measuredChunks <= index) {
                            // First time this chunk has reported a duration: upstream refines the
                            // whole-reply estimate here, on every chunk prepare, so the reveal
                            // covers the full response rather than chunk 0's few seconds.
                            measuredChunks += 1
                            cumulativeDurationMs += duration
                            preparedTextLen += chunkTextLen[index]
                            estimatedTotalMs = (cumulativeDurationMs * fullTextLen / preparedTextLen)
                                .coerceIn(0L, Int.MAX_VALUE.toLong())
                                .toInt()
                        }
                        val elapsed = cumulativeBefore + handle.positionMs
                        val fraction = if (estimatedTotalMs > 0) {
                            (elapsed.toFloat() / estimatedTotalMs).coerceIn(0f, 1f)
                        } else 0f
                        onProgress(fraction, estimatedTotalMs)
                        delay(PROGRESS_POLL_MS)
                    }
                }

                continuation.invokeOnCancellation {
                    poll.cancel()
                    handle.stop()
                    if (currentHandle === handle) currentHandle = null
                }
            }
        }

        private fun finish(end: PlaybackEnd) {
            // Release an awaiter first, unconditionally: it is waiting on *this sequence*, not on
            // the status of the guard below, and no path through here may leave it suspended.
            // `complete` on the already-completed signal is a no-op, so the cancel path is safe.
            endSignal.complete(end)
            // A cancelled sequence has already ended as far as its caller is concerned; a second
            // report would be the duplicate the guard exists to prevent.
            if (cancelled) return
            if (active !== this) return
            active = null
            onEnd(end)
        }
    }

    private companion object {
        /** Upstream polls at 100 ms; the karaoke highlight is driven from this cadence. */
        const val PROGRESS_POLL_MS = 100L
    }
}
