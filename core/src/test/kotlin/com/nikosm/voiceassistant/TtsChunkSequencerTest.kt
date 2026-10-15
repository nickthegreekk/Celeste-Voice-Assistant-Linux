package com.nikosm.voiceassistant

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

    // ------------------------------------------------------------------------ the happy path

    @Test
    fun `chunks play one at a time, in order, and the utterance ends finished`() = runTest {
        val playback = FakeAudioPlayback()
        val ends = mutableListOf<PlaybackEnd>()

        TtsChunkSequencer(playback).start(
            chunks = listOf("one.", "two.", "three."),
            scope = this,
            synthesize = files(),
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
            onProgress = { fraction, total -> progress += fraction to total },
            onEnd = { },
        )
        runCurrent()
        advanceTimeBy(250)

        assertTrue("no estimate should be published without a measurement", progress.all { it.second == 0 })
        assertTrue("and no fraction either", progress.all { it.first == 0f })

        sequence.cancel()
    }
}
