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
     * playback of an earlier chunk. Returning null means that chunk could not be produced.
     *
     * Starting a sequence cancels whatever was running. Cancellation is not an error: the previous
     * sequence's [onEnd] receives [PlaybackEnd.Stopped].
     */
    fun start(
        chunks: List<String>,
        scope: CoroutineScope,
        synthesize: suspend (index: Int, text: String) -> Path?,
        onProgress: (fraction: Float, estimatedTotalMs: Int) -> Unit,
        onEnd: (PlaybackEnd) -> Unit,
    ): ChunkSequence {
        // Cancel-previous happens BEFORE the new sequence exists, so the outgoing sequence's
        // teardown cannot observe or disturb its replacement.
        active?.cancel()

        val sequence = Sequence(chunks, scope, synthesize, onProgress, onEnd)
        active = sequence
        sequence.begin()
        return sequence
    }

    private inner class Sequence(
        private val chunks: List<String>,
        private val scope: CoroutineScope,
        private val synthesize: suspend (Int, String) -> Path?,
        private val onProgress: (Float, Int) -> Unit,
        private val onEnd: (PlaybackEnd) -> Unit,
    ) : ChunkSequence {

        /** One slot per chunk; the synthesis loop fills it, the playback loop awaits it. */
        private val ready: List<CompletableDeferred<Path?>> = List(chunks.size) { CompletableDeferred() }

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

        fun begin() {
            job = scope.launch { run() }
        }

        override fun cancel() {
            if (cancelled) return
            cancelled = true
            // Release anything still waiting on a chunk that will never arrive, so no coroutine
            // is left parked on a deferred nobody will complete. The flag is what does the real
            // work; this is what stops a cancelled sequence leaving a suspended coroutine behind.
            ready.forEach { it.complete(null) }
            currentHandle?.stop()
            job?.cancel()
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
                            ready[i].complete(null)
                            continue
                        }
                        val path = try {
                            synthesize(i, chunks[i])
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // A failed synthesis is not fatal to the loop the way a failed
                            // playback is; this chunk reports null and playback decides.
                            null
                        }
                        ready[i].complete(path)
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

                val path = ready[index].await()
                // Covers both a cancelled sequence resuming on a completed-with-null slot and a
                // chunk that genuinely could not be produced.
                if (cancelled) return
                if (path == null) {
                    // Upstream's synthesis-failure path is a quirk rather than a decision: it
                    // `continue`s past a chunk, but its "start playback" branch is keyed on
                    // `index == 0`, so a failed FIRST chunk leaves the sequence with a non-empty
                    // file list and no playback, no cleanup and nothing to end it. Aborting the
                    // whole utterance is the behaviour a failure should have anyway, and the test
                    // name records that this is a choice rather than a copy.
                    finish(PlaybackEnd.Failed("chunk $index could not be synthesised"))
                    return
                }

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

                val handle = playback.play(path) { end ->
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
