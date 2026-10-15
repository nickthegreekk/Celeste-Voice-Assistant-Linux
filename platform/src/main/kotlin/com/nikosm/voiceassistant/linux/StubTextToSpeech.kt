package com.nikosm.voiceassistant.linux

import com.nikosm.voiceassistant.AppLogger
import com.nikosm.voiceassistant.PlaybackEnd
import com.nikosm.voiceassistant.SpeechSamples
import com.nikosm.voiceassistant.StderrLogger
import com.nikosm.voiceassistant.TextToSpeech
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Seam 6, unimplemented — **M2 replaces this with speech-dispatcher for the system route and the
 * distro's `espeak-ng` for the local one** (PLAN.md §5, §10).
 *
 * Reporting "no engine" is not a placeholder that has to be excused: both of upstream's device
 * voices already have a bail-out for it, and they are the right bail-outs. `speakTextOnDevice`
 * checks `ttsReady` before doing anything and, when it is false, releases the audio focus the turn
 * retained and lands in IDLE; `speakWithEspeak` does the same when its engine getter returns null.
 * So M1's text-only behaviour is upstream's own "engine unavailable" path rather than a new one,
 * and M2 makes the engines answer without touching either call site.
 *
 * Nothing announces itself from [systemReady] or from the `stop`/`release` no-ops: the first is
 * read on every turn and the others are teardown, and a stub that logs from a teardown path is a
 * stub whose message arrives after the thing it describes. The announcement rides on the two calls
 * that actually try to make sound.
 */
class StubTextToSpeech(
    private val logger: AppLogger = StderrLogger,
) : TextToSpeech {

    private val announced = AtomicBoolean(false)

    override val systemReady: Boolean get() = false

    override fun speakSystem(text: String, utteranceId: String, onEnd: (PlaybackEnd) -> Unit): Boolean {
        announce()
        // False means "nothing was queued", so the caller must NOT wait for a callback — the
        // contract says onEnd is never called on this path, and it is not.
        return false
    }

    override fun stopSystem() = Unit

    override fun synthesizeLocal(text: String, voice: String): SpeechSamples? {
        announce()
        return null
    }

    override fun releaseLocal() = Unit

    private fun announce() {
        if (announced.compareAndSet(false, true)) {
            logger.i(
                TAG,
                "on-device speech is not implemented yet — this is StubTextToSpeech, which M2 " +
                    "replaces: speech-dispatcher for the system route, the distro espeak-ng for " +
                    "the local one (PLAN.md §10). Replies still reach the transcript."
            )
        }
    }

    private companion object {
        const val TAG = "StubTextToSpeech"
    }
}
