package com.nikosm.voiceassistant

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

/**
 * The chunked TTS sequencing, tested with no service, no audio hardware and no M2 implementation.
 *
 * PLAN.md §6 requires exactly that, and it is only possible because the sequencing was extracted
 * out of `AssistantServiceAudio.kt` into [TtsChunkSequencer]: upstream's version is unreachable
 * except through a live service and a real `MediaPlayer`, which is why upstream has no test for
 * any of this. [FakeAudioPlayback] is the other half — a double that enforces the one-playback
 * contract rather than tolerating violations.
 *
 * **`runCurrent`, never `advanceUntilIdle`.** The progress poller runs until its chunk ends, so
 * "advance until there is nothing left to run" would never return — it would spin the virtual
 * clock until `runTest`'s timeout. Every step below is therefore bounded explicitly, which has the
 * pleasant side effect of making each test's timing visible rather than implied.
 */
class TtsChunkSequencerTest {

    private fun chunk(name: String): Path = Path.of("/nonexistent", name)

    private fun files(): suspend (Int, String) -> Path? = { index, _ -> chunk("chunk$index.wav") }

    private val noProgress: (Float, Int) -> Unit = { _, _ -> }

    /**
     * Counts how many times the `onStarted` hook fires. That hook is the SPEAKING window, and its
     * whole contract is "once, on the first chunk" — so a count is what proves it. JUnit builds a
     * fresh instance per test, so a field is enough and no reset is needed.
     */
    private var startedCount = 0
    private val countStarts: () -> Unit = { startedCount++ }

    // ------------------------------------------------------------------------ the happy path

    @Test
    fun `chunks play one at a time, in order, and the utterance ends finished`() = runTest {
        val playback = FakeAudioPlayback()
        val ends = mutableListOf<PlaybackEnd>()

        TtsChunkSequencer(playback).start(
            chunks = listOf("one.", "two.", "three."),
            scope = this,
            synthesize = files(),
            onStarted = countStarts,
            onProgress = noProgress,
            onEnd = { ends += it },
        )
        runCurrent()
        assertEquals("only the first chunk should have started", listOf(chunk("chunk0.wav")), playback.played)

        playback.completeCurrent()
        runCurrent()
        assertEquals(listOf(chunk("chunk0.wav"), chunk("chunk1.wav")), playback.played)

        playback.completeCurrent()
        runCurrent()
        playback.completeCurrent()
        runCurrent()

        assertEquals(
            listOf(chunk("chunk0.wav"), chunk("chunk1.wav"), chunk("chunk2.wav")),
            playback.played,
        )
        assertEquals(listOf(PlaybackEnd.Finished), ends)
        assertTrue("nothing should still be playing", !playback.isPlaying)
    }

    // ------------------------------------------------ required: late completion after a cancel

    @Test
    fun `a completion arriving after a cancel does not advance the sequence`() = runTest {
        val playback = FakeAudioPlayback()
        val ends = mutableListOf<PlaybackEnd>()

        val sequence = TtsChunkSequencer(playback).start(
            chunks = listOf("one.", "two.", "three."),
            scope = this,
            synthesize = files(),
            onStarted = countStarts,
            onProgress = noProgress,
            onEnd = { ends += it },
        )
        runCurrent()
        assertEquals(listOf(chunk("chunk0.wav")), playback.played)

        sequence.cancel()

        // The chunk's process exits anyway. A real `pw-play` cannot know it was abandoned, so its
        // exit arrives after the sequence has moved on — which is precisely the case that used to
        // start the next chunk underneath whatever had replaced it.
        playback.deliverTo(0, PlaybackEnd.Finished)
        runCurrent()

        assertEquals(
            "a late completion must not start the next chunk",
            listOf(chunk("chunk0.wav")),
            playback.played,
        )
        assertEquals("a cancelled sequence reports no end of its own", emptyList<PlaybackEnd>(), ends)
    }

    // ------------------------------------------- required: a second utterance cancels the first

    @Test
    fun `a second utterance cancels the first`() = runTest {
        val playback = FakeAudioPlayback()
        val sequencer = TtsChunkSequencer(playback)
        val firstEnds = mutableListOf<PlaybackEnd>()

        sequencer.start(
            chunks = listOf("one.", "two."),
            scope = this,
            synthesize = { i, _ -> chunk("a$i.wav") },
            onStarted = countStarts,
            onProgress = noProgress,
            onEnd = { firstEnds += it },
        )
        runCurrent()
        assertEquals(listOf(chunk("a0.wav")), playback.played)

        // A second utterance arrives while the first is still speaking.
        val second = sequencer.start(
            chunks = listOf("uno.", "dos."),
            scope = this,
            synthesize = { i, _ -> chunk("b$i.wav") },
            onStarted = countStarts,
            onProgress = noProgress,
            onEnd = { },
        )
        runCurrent()

        assertEquals(
            "the first utterance stopped and the second took over",
            listOf(chunk("a0.wav"), chunk("b0.wav")),
            playback.played,
        )

        // The superseded utterance's exit arrives late, as a real one would.
        playback.deliverTo(0, PlaybackEnd.Finished)
        runCurrent()
        assertEquals(
            "the cancelled utterance must not resume alongside its replacement",
            listOf(chunk("a0.wav"), chunk("b0.wav")),
            playback.played,
        )

        second.cancel()
    }

    // ---------------------------------------------------------- required: a chunk that fails

    @Test
    fun `a chunk that fails to play aborts the utterance rather than skipping ahead to the next one, because upstream's abort is a decision while its synthesis-failure path is only a quirk`() =
        runTest {
            // Upstream has two failure paths and they disagree. Its playback-error listener
            // aborts the utterance — and a reply that cannot be spoken part-way through is not
            // improved by speaking the rest of it, so that one is kept. Its *synthesis*-failure
            // path does `continue`, which is a quirk rather than a decision: the branch that
            // starts playback is keyed on `index == 0`, so a failed first chunk leaves the
            // sequence with a non-empty file list, no playback, no cleanup and nothing to end it.
            // Both are treated here as "abort", and this name is where that is recorded.
            val playback = FakeAudioPlayback()
            val ends = mutableListOf<PlaybackEnd>()

            TtsChunkSequencer(playback).start(
                chunks = listOf("one.", "two.", "three."),
                scope = this,
                synthesize = files(),
            onStarted = countStarts,
                onProgress = noProgress,
                onEnd = { ends += it },
            )
            runCurrent()

            playback.failCurrent("pipe closed")
            runCurrent()

            assertEquals(
                "the utterance must stop at the failed chunk, not carry on",
                listOf(chunk("chunk0.wav")),
                playback.played,
            )
            assertEquals(listOf(PlaybackEnd.Failed("pipe closed")), ends)
        }

    @Test
    fun `a chunk that cannot be synthesised aborts the utterance too`() = runTest {
        val playback = FakeAudioPlayback()
        val ends = mutableListOf<PlaybackEnd>()

        TtsChunkSequencer(playback).start(
            chunks = listOf("one.", "two."),
            scope = this,
            synthesize = { index, _ -> if (index == 0) null else chunk("chunk$index.wav") },
            onStarted = countStarts,
            onProgress = noProgress,
            onEnd = { ends += it },
        )
        runCurrent()

        assertTrue("nothing should have played", playback.played.isEmpty())
        assertEquals(1, ends.size)
        assertTrue("the sequence must end, not hang", ends.single() is PlaybackEnd.Failed)
    }

    // -------------------------------------------------------- the progress arithmetic

    @Test
    fun `the estimate extrapolates from the measured chunks to the whole reply`() = runTest {
        val playback = FakeAudioPlayback()
        playback.durations[chunk("chunk0.wav")] = 1_000
        val progress = mutableListOf<Pair<Float, Int>>()

        // Three chunks of four cleaned characters each — 12 characters in total — so one measured
        // chunk of 1000 ms extrapolates to 3000 ms for the whole reply. That extrapolation is what
        // makes the highlight cover the full response rather than the first few seconds of it.
        val sequence = TtsChunkSequencer(playback).start(
            chunks = listOf("aaa.", "bbb.", "ccc."),
            scope = this,
            synthesize = files(),
            onStarted = countStarts,
            onProgress = { fraction, total -> progress += fraction to total },
            onEnd = { },
        )
        runCurrent()

        assertEquals(
            "one measured chunk of three extrapolates to the whole reply",
            3_000,
            progress.last().second,
        )

        playback.currentHandle!!.positionMs = 500
        advanceTimeBy(150)

        assertEquals(
            "the fraction is elapsed over the estimate",
            500f / 3_000f,
            progress.last().first,
            0.001f,
        )

        sequence.cancel()
    }

    @Test
    fun `a chunk whose duration is not yet known reports no total rather than a wrong one`() = runTest {
        // The real implementation reports durationMs 0 until it has measured the audio. A caller
        // that divides by it has to see "unknown", and the sequencer must not invent a total from
        // a zero — which would make the fraction a division by nothing.
        val playback = FakeAudioPlayback()
        val progress = mutableListOf<Pair<Float, Int>>()

        val sequence = TtsChunkSequencer(playback).start(
            chunks = listOf("aaa.", "bbb."),
            scope = this,
            synthesize = files(),
            onStarted = countStarts,
            onProgress = { fraction, total -> progress += fraction to total },
            onEnd = { },
        )
        runCurrent()
        advanceTimeBy(250)

        assertTrue("no estimate should be published without a measurement", progress.all { it.second == 0 })
        assertTrue("and no fraction either", progress.all { it.first == 0f })

        sequence.cancel()
    }

    // ------------------------------------------------- the awaitable end

    @Test
    fun `awaitEnd settles when the last chunk finishes, and only then`() = runTest {
        // The live turn awaits this. Without it a caller reads `played` the instant start()
        // returns — always empty — and reports every reply as "nothing was spoken".
        val playback = FakeAudioPlayback()
        val sequence = TtsChunkSequencer(playback).start(
            chunks = listOf("one.", "two."),
            scope = this,
            synthesize = files(),
            onStarted = countStarts,
            onProgress = noProgress,
            onEnd = { },
        )
        var awaited: PlaybackEnd? = null
        val waiter = launch { awaited = sequence.awaitEnd() }
        runCurrent()

        assertFalse("the reply is still being spoken", waiter.isCompleted)
        assertNull("and so it has no verdict yet", awaited)

        playback.completeCurrent()
        runCurrent()
        assertFalse("one chunk in, still speaking", waiter.isCompleted)

        playback.completeCurrent()
        runCurrent()
        assertTrue(waiter.isCompleted)
        assertEquals(PlaybackEnd.Finished, awaited)
    }

    @Test
    fun `a cancelled sequence releases whoever is awaiting its end, and says stopped`() = runTest {
        // `finish` deliberately reports nothing for a cancelled sequence — a caller that was
        // replaced must not hear about it — so if cancel() did not release the awaiter itself, a
        // caller parked in awaitEnd() would never wake. That is every replay and every deferred
        // Gateway turn: a Stop pressed mid-reply would strand the turn's coroutine for the life of
        // the process.
        //
        // `Stopped`, not `Finished`: the reply was cut off, and a caller that read that as
        // "finished" would treat a superseded utterance as a delivered one.
        val playback = FakeAudioPlayback()
        val sequence = TtsChunkSequencer(playback).start(
            chunks = listOf("one.", "two."),
            scope = this,
            synthesize = files(),
            onStarted = countStarts,
            onProgress = noProgress,
            onEnd = { },
        )
        var awaited: PlaybackEnd? = null
        val waiter = launch { awaited = sequence.awaitEnd() }
        runCurrent()

        sequence.cancel()
        runCurrent()

        assertTrue("cancelling must not park the caller forever", waiter.isCompleted)
        assertEquals(PlaybackEnd.Stopped, awaited)
    }

    // ------------------------------------------------- the SPEAKING window's hook

    @Test
    fun `the started hook fires once, as the first chunk begins, and never again`() = runTest {
        // Upstream enters SPEAKING at exactly this moment and holds it across the whole sequence —
        // one continuous window from the first word to the last. A hook that fired per chunk would
        // flicker listening back on between every paragraph, and one that fired at sequence
        // creation would claim to be speaking before any audio existed. Both are why the contract
        // is written down on the hook rather than left to whoever edits it next.
        val playback = FakeAudioPlayback()

        TtsChunkSequencer(playback).start(
            chunks = listOf("one.", "two.", "three."),
            scope = this,
            synthesize = files(),
            onStarted = countStarts,
            onProgress = noProgress,
            onEnd = { },
        )
        runCurrent()

        assertEquals("fires once the first chunk is about to play", 1, startedCount)
        assertEquals("and that chunk is the one playing", listOf(chunk("chunk0.wav")), playback.played)

        // All three chunks are completed — not two. A sequence left mid-utterance has a live
        // progress poller, and `runTest` reports that as an unfinished coroutine, which is exactly
        // how this test failed the first time it ran.
        playback.completeCurrent()
        runCurrent()
        assertEquals("still one, halfway through", 1, startedCount)
        playback.completeCurrent()
        runCurrent()
        playback.completeCurrent()
        runCurrent()

        assertEquals("three chunks, one SPEAKING window", 1, startedCount)
        assertTrue("and the utterance finished", !playback.isPlaying)
    }

    @Test
    fun `the started hook does not fire when the first chunk cannot be synthesised`() = runTest {
        // Nothing was ever played, so nothing may announce that speaking began. The caller uses
        // the hook to enter SPEAKING and pause the microphone; announcing it for an utterance that
        // produced no audio would leave the microphone paused for silence.
        val playback = FakeAudioPlayback()

        TtsChunkSequencer(playback).start(
            chunks = listOf("one.", "two."),
            scope = this,
            synthesize = { index, _ -> if (index == 0) null else chunk("chunk$index.wav") },
            onStarted = countStarts,
            onProgress = noProgress,
            onEnd = { },
        )
        runCurrent()

        assertEquals(0, startedCount)
        assertTrue("and nothing played", playback.played.isEmpty())
    }
}
