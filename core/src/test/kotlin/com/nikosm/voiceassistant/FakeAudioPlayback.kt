package com.nikosm.voiceassistant

import java.nio.file.Path

/**
 * A test double for seam 2.
 *
 * It exists because choosing a handle-based `AudioPlayback` over a suspending loop was only
 * defensible if the sequencing it enables can be tested without audio hardware — PLAN.md §6
 * requires that, and this is what makes it possible. Nothing here shells out, touches a device or
 * waits on real time.
 *
 * **It enforces the seam's one-playback-at-a-time contract rather than tolerating violations.**
 * A double that quietly allowed two live playbacks would let the sequencer pass its tests while
 * breaking the guarantee the real implementation makes, which is the failure mode a fake is
 * supposed to prevent. So a second `play` while one is live throws.
 *
 * **It never completes a playback on its own.** Completions arrive only when the test asks for
 * them, which is what makes "a completion arriving after a cancel" expressible at all: the test
 * cancels first and delivers the callback afterwards, exactly as a real `pw-play` exit would
 * arrive after the sequence had moved on.
 */
class FakeAudioPlayback : AudioPlayback {

    /** Every file the sequencer asked to play, in order — including ones later stopped. */
    val played = mutableListOf<Path>()

    /**
     * The duration each chunk reports. Anything absent reports 0, which is also what the real
     * implementation reports before it has measured anything — and the sequencer has to cope with
     * that rather than treating it as a zero-length chunk.
     */
    val durations = mutableMapOf<Path, Long>()

    private class Recording(val file: Path, val handle: Handle, val onEnd: (PlaybackEnd) -> Unit)

    class Handle internal constructor() : PlaybackHandle {
        override var positionMs: Long = 0
        override var durationMs: Long = 0
        internal var ended = false
        internal var onStop: (() -> Unit)? = null

        override fun stop() {
            if (ended) return
            ended = true
            onStop?.invoke()
        }
    }

    private val recordings = mutableListOf<Recording>()
    private var current: Recording? = null

    /** The playback in flight, or null when nothing is playing. */
    val currentHandle: Handle? get() = current?.handle

    val isPlaying: Boolean get() = current != null

    override fun play(file: Path, target: AudioDevice?, onEnd: (PlaybackEnd) -> Unit): PlaybackHandle {
        check(current == null) {
            "seam 2 allows one playback at a time, but a second was started while one was live"
        }
        val handle = Handle().apply { durationMs = durations[file] ?: 0L }
        val recording = Recording(file, handle, onEnd)
        handle.onStop = { if (current === recording) current = null }
        played.add(file)
        recordings.add(recording)
        current = recording
        return handle
    }

    override fun playPcm(
        samples: ByteArray,
        sampleRate: Int,
        target: AudioDevice?,
        onEnd: (PlaybackEnd) -> Unit
    ): PlaybackHandle =
        throw UnsupportedOperationException("the chunk sequencer plays files, never raw PCM")

    override fun stopAll() {
        current?.handle?.stop()
        current = null
    }

    // ------------------------------------------------------------------ test controls

    /** The current playback finished on its own, as a `pw-play` exit would. */
    fun completeCurrent() = endCurrent(PlaybackEnd.Finished)

    /** The current playback failed — a broken pipe, a missing file, a dead process. */
    fun failCurrent(reason: String) = endCurrent(PlaybackEnd.Failed(reason))

    /**
     * Delivers [end] to an EARLIER call's callback, as a real process exit would if it arrived
     * after the sequence had already moved on. This is the shape of the late-completion bug the
     * sequencer's guard exists to prevent, so the test has to be able to produce it.
     */
    fun deliverTo(callIndex: Int, end: PlaybackEnd) {
        val recording = recordings[callIndex]
        recording.handle.ended = true
        if (current === recording) current = null
        recording.onEnd(end)
    }

    private fun endCurrent(end: PlaybackEnd) {
        val recording = current ?: error("nothing is playing")
        current = null
        recording.handle.ended = true
        recording.onEnd(end)
    }
}
