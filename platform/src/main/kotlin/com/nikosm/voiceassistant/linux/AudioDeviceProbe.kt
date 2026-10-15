package com.nikosm.voiceassistant.linux

/**
 * Seam 3, `AudioDevices` (PLAN.md §5) — **a stub, and it says so.**
 *
 * The real implementation enumerates input and output devices by parsing `wpctl status` and
 * falling back to `pactl list`, and feeds the `--target` argument that capture and playback pass
 * to `pw-record`/`pw-play` (seams 1 and 2). It lands in M2, with the rest of the audio path.
 *
 * It exists as a named object this early for one reason: `--selftest` has to report on the audio
 * device layer, and printing nothing would leave a reader unable to tell "no devices were found"
 * from "nothing was asked". A stub that states it is a stub is the honest version of that, and it
 * marks the place M2 fills in.
 *
 * Note what it deliberately is *not*: it runs no process and reads no device. `ProcessBuilder` is
 * confined to `:platform` (AGENTS.md §4), and this is `:platform`, so nothing here would break
 * that rule — but M0 has no business touching PipeWire, and a selftest that reports on a machine's
 * audio state before the audio code exists would be reporting on the stub, not the machine.
 */
object AudioDeviceProbe {

    /** True once seam 3 is implemented. `--selftest` prints this rather than a device list. */
    const val IMPLEMENTED: Boolean = false

    /** One line for `--selftest`. Kept free of a device list, which would be a lie at M0. */
    fun describe(): String =
        "STUB - not probed. Seam 3 (AudioDevices) lands in M2: wpctl/pactl enumeration, --target on capture and playback."
}
