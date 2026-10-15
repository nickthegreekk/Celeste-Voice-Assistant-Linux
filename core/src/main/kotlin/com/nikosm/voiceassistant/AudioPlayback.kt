package com.nikosm.voiceassistant

import java.nio.file.Path

/**
 * How a playback ended.
 *
 * Three outcomes, not two, and the third is the one that carries information. Upstream's
 * `MediaPlayer` callbacks gave it `onCompletion` and `onError`, and a stop was indistinguishable
 * from either — which is why every teardown path in `AssistantServiceAudio.kt` has to re-derive
 * "was this stopped by a newer turn, or did it really finish?" from a generation counter before
 * it dares touch the state machine. Naming `Stopped` lets that question be answered by the thing
 * that knows, and it is what makes [AudioPlayback.stopAll] safe to call from inside a callback.
 */
sealed interface PlaybackEnd {
    /** The audio played to the end. */
    data object Finished : PlaybackEnd

    /** Playback failed. [reason] is for the log — never shown to the user. */
    data class Failed(val reason: String) : PlaybackEnd

    /** Something stopped it early: a newer utterance, or the Stop button. Not a failure. */
    data object Stopped : PlaybackEnd
}

/**
 * Seam 2 (PLAN.md §5): playback, without the Android audio stack.
 *
 * The Linux implementation is a `pw-play` process per utterance (`paplay`, then `aplay` as
 * fallbacks), which PLAN.md §5 gives one consequence worth stating here because it is a contract
 * rather than an implementation detail: **"finished" is process exit.**
 *
 * ## The four contracts
 *
 * 1. **At most one playback at a time, and starting one stops the previous.** That is PLAN.md §6's
 *    "current playback" guard, and it lives here rather than in the caller because every caller
 *    would otherwise have to remember it. The stopped playback's callback receives
 *    [PlaybackEnd.Stopped] — not `Failed`, because a superseded utterance is not an error, and not
 *    silence, because a caller that never learns why its audio ended is a caller that cannot
 *    clean up.
 *
 * 2. **[PlaybackHandle.positionMs] is elapsed time, capped at [PlaybackHandle.durationMs].** The UI
 *    divides one by the other for the karaoke highlight and for the duration-synced reveal, so a
 *    position that overshot the duration would paint past the end of the text. Upstream reads this
 *    from `MediaPlayer.currentPosition`; a subprocess reports it a different way, and capping is
 *    what keeps the arithmetic identical.
 *
 * 3. **[playPcm] takes a temporary WAV.** Upstream hands raw samples to an `AudioTrack`; a
 *    subprocess wants a file. The seam hands the samples over and takes the file-writing on
 *    itself, because the alternative — every caller wrapping its own PCM in a WAV header — would
 *    put the same forty-four bytes in three places.
 *
 * 4. **Callbacks are delivered on one defined dispatcher, and [PlaybackHandle.stop] is safe to
 *    call from inside one.** The caller that reacts to `Finished` by starting the next chunk must
 *    be able to stop the thing that just called it without deadlocking, and callers that mutate
 *    service state from a callback need to know which thread they are on. Upstream's callbacks
 *    arrive on `MediaPlayer`'s handler thread and every one of them hops via `serviceScope.launch`;
 *    here the dispatcher is stated instead of inherited.
 *
 * Implementations must also treat [PlaybackHandle.stop] as idempotent and safe after the end has
 * already been reported — every teardown path in the ported audio code calls it defensively.
 */
interface AudioPlayback {

    /**
     * Plays [file] to the end or until stopped. The file is not modified or deleted: the caller
     * owns it, and the chunked TTS pipeline deliberately keeps its chunk files for a later sweep.
     *
     * [target] is the sink to play into, from seam 3; `null` means "whatever the system default
     * is", which is the ordinary case. It is a parameter rather than a property of the
     * implementation because the choice is per-utterance and is made by the caller — the earpiece
     * toggle is a user setting, and `playbackTarget()` is where it is read.
     */
    fun play(file: Path, target: AudioDevice? = null, onEnd: (PlaybackEnd) -> Unit): PlaybackHandle

    /**
     * Plays [samples] — signed 16-bit mono PCM — at [sampleRate]. See contract 3: the
     * implementation materialises them as a temporary WAV and deletes it afterwards.
     *
     * [target] as on [play].
     */
    fun playPcm(
        samples: ByteArray,
        sampleRate: Int,
        target: AudioDevice? = null,
        onEnd: (PlaybackEnd) -> Unit
    ): PlaybackHandle

    /** Stops whatever is playing, if anything. A no-op when nothing is. */
    fun stopAll()
}

/**
 * One playback in flight.
 *
 * A handle outlives its audio: after the end callback has fired, [positionMs] and [durationMs]
 * still answer, and [stop] is a no-op. The ported code keeps handles around for identity
 * comparisons — `currentPlayer === mp` upstream — and a handle that vanished at the end would
 * turn those comparisons into null checks with different semantics.
 */
interface PlaybackHandle {

    /** Elapsed time, never greater than [durationMs]. See contract 2. */
    val positionMs: Long

    /**
     * Total length, or 0 when it is not known yet.
     *
     * Callers must treat 0 as "unknown" rather than "empty": the chunked pipeline publishes this
     * as `voiceDuration`, and a UI that divides by it needs to know the difference between "no
     * audio" and "not measured yet".
     */
    val durationMs: Long

    /** Stops this playback. Idempotent, and safe from inside the end callback. */
    fun stop()
}
