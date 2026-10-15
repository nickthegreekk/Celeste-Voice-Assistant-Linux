package com.nikosm.voiceassistant.linux

import com.nikosm.voiceassistant.AppLogger
import com.nikosm.voiceassistant.AudioCapture
import com.nikosm.voiceassistant.AudioDevice
import com.nikosm.voiceassistant.CaptureStream
import com.nikosm.voiceassistant.StderrLogger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Seam 1, unimplemented — **M2 replaces this with the `pw-record` → `parec` → `arecord` runner**
 * (PLAN.md §5, §10). `grep -rn 'Stub' platform/src/main` is the whole M2 to-do list.
 *
 * [open] returning null is deliberately the *same* answer upstream gives when `AudioRecord` fails
 * to initialise: `VADAudioRecorder` logs once and stops, rather than retrying into a hot loop. So
 * M1's behaviour with no capture is upstream's own "no microphone" path rather than a new one, and
 * M2 makes [open] answer for real without touching the caller.
 *
 * Why this one is a stub while seam 9 was implemented in M1: `notify-send` and `gdbus` are two
 * commands with no state and no tuning, and M4 builds *on top* of them. Capture is not that — M2
 * owns the fallback chain across three helper binaries, `--target` device selection, and the
 * latency budget PLAN.md §6 sets, none of which should be guessed at before there is a device to
 * measure against.
 */
class StubAudioCapture(
    private val logger: AppLogger = StderrLogger,
) : AudioCapture {

    private val announced = AtomicBoolean(false)

    override fun open(sampleRate: Int, chunkFrames: Int, target: AudioDevice?): CaptureStream? {
        if (announced.compareAndSet(false, true)) {
            logger.i(
                TAG,
                "microphone capture is not implemented yet — this is StubAudioCapture, which M2 " +
                    "replaces with the pw-record/parec/arecord runner (PLAN.md §10). Hands-free " +
                    "listening will open no device until then; push-to-talk and text are unaffected."
            )
        }
        return null
    }

    private companion object {
        const val TAG = "StubAudioCapture"
    }
}
