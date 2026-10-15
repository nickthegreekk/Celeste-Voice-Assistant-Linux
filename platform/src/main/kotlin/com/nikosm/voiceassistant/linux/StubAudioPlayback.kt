package com.nikosm.voiceassistant.linux

import com.nikosm.voiceassistant.AppLogger
import com.nikosm.voiceassistant.AudioDevice
import com.nikosm.voiceassistant.AudioPlayback
import com.nikosm.voiceassistant.PlaybackEnd
import com.nikosm.voiceassistant.PlaybackHandle
import com.nikosm.voiceassistant.StderrLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Seam 2, unimplemented — **M2 replaces this with the `pw-play`/`paplay`/`aplay` process runner**
 * (PLAN.md §5, §10). `grep -rn 'Stub' platform/src/main` is the whole M2 to-do list.
 *
 * It reports [PlaybackEnd.Failed] rather than pretending to succeed, and that is the honest
 * answer rather than a pessimistic one: M1's acceptance is text-only, so a voice persona's reply
 * landing in the transcript with a "could not speak it" outcome is what actually happens. The two
 * device-voice routes never reach here at all — [StubTextToSpeech] reports both engines
 * unavailable, so they take the "engine unavailable" bail-out upstream already has.
 *
 * **The failure is delivered asynchronously, deliberately.** [AudioPlayback]'s contract says
 * callbacks arrive on a dispatcher rather than from inside `play`, and the ported chunked player
 * depends on it: it assigns the handle returned by `play()` to a field and compares against that
 * field inside the callback to decide whether a chunk is still current. A stub that called back
 * synchronously would compare against a field that had not been assigned yet — which would look
 * like the stub being "simpler" and would in fact be a stub that cannot be swapped for the real
 * one without the caller changing.
 */
class StubAudioPlayback(
    private val logger: AppLogger = StderrLogger,
) : AudioPlayback {

    private val announced = AtomicBoolean(false)

    // Shared, and Dispatchers.Default rather than a thread of its own: this exists only to make the
    // failure callback asynchronous, and a per-call thread would be a leak in a class nobody is
    // supposed to ship.
    private val scope = CoroutineScope(Dispatchers.Default)

    override fun play(file: Path, target: AudioDevice?, onEnd: (PlaybackEnd) -> Unit): PlaybackHandle = refuse(onEnd)

    override fun playPcm(
        samples: ByteArray,
        sampleRate: Int,
        target: AudioDevice?,
        onEnd: (PlaybackEnd) -> Unit
    ): PlaybackHandle = refuse(onEnd)

    override fun stopAll() = Unit

    private fun refuse(onEnd: (PlaybackEnd) -> Unit): PlaybackHandle {
        if (announced.compareAndSet(false, true)) {
            logger.i(
                TAG,
                "audio playback is not implemented yet — this is StubAudioPlayback, which M2 " +
                    "replaces with the pw-play/paplay/aplay runner (PLAN.md §10). Nothing will " +
                    "be heard until then; the transcript is unaffected."
            )
        }
        scope.launch { onEnd(PlaybackEnd.Failed("audio playback is not implemented until M2")) }
        return InertHandle
    }

    /** Answers the questions a real handle would, with nothing behind them. */
    private object InertHandle : PlaybackHandle {
        override val positionMs: Long get() = 0
        override val durationMs: Long get() = 0
        override fun stop() = Unit
    }

    private companion object {
        const val TAG = "StubAudioPlayback"
    }
}
