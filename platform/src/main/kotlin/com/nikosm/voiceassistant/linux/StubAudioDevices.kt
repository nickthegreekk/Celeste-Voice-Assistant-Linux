package com.nikosm.voiceassistant.linux

import com.nikosm.voiceassistant.AppLogger
import com.nikosm.voiceassistant.AudioDevice
import com.nikosm.voiceassistant.AudioDevices
import com.nikosm.voiceassistant.StderrLogger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Seam 3, unimplemented — **M2 replaces this with a `wpctl status` / `pactl list` reader**
 * (PLAN.md §5, §10).
 *
 * The empty device list is the part that is genuinely missing. [earpiece] returning null is not:
 * null is the interface's documented answer for "this machine has no separate earpiece", which is
 * the true answer on a desktop — and PLAN.md §9 replaces the phone's route toggle with sink
 * selection anyway, so the real M2 answer may well stay null here while `outputs()` grows.
 *
 * `AudioDeviceProbe` (M0.5) is the sibling stub `--selftest` prints through. The two collapse in
 * M2, when there is a real device list for the selftest to describe; until then each is doing a
 * different job and neither is pretending to be the other.
 */
class StubAudioDevices(
    private val logger: AppLogger = StderrLogger,
) : AudioDevices {

    private val announced = AtomicBoolean(false)

    override fun outputs(): List<AudioDevice> {
        announce()
        return emptyList()
    }

    override fun inputs(): List<AudioDevice> {
        announce()
        return emptyList()
    }

    override fun earpiece(): AudioDevice? {
        announce()
        return null
    }

    private fun announce() {
        if (announced.compareAndSet(false, true)) {
            logger.i(
                TAG,
                "audio device enumeration is not implemented yet — this is StubAudioDevices, " +
                    "which M2 replaces with a wpctl/pactl reader (PLAN.md §10). No device picker " +
                    "list is available until then."
            )
        }
    }

    private companion object {
        const val TAG = "StubAudioDevices"
    }
}
