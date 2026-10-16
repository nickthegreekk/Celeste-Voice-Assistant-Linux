package com.nikosm.voiceassistant

/**
 * One input or output the user could choose.
 *
 * [id] is the handle a playback or capture process is pointed at — on Linux that is the
 * PipeWire/PulseAudio node name that `pw-play --target` and `pw-record --target` accept. It is
 * deliberately opaque here: `:core` never needs to know its shape, only to hand it back.
 */
data class AudioDevice(
    val id: String,
    val name: String,
    /**
     * True for the device a player or recorder will use **when the caller names no device**.
     *
     * That is a narrower claim than it sounds, and the narrowness is the point. It does **not** mean
     * "the one currently in use" — something may be playing through another one right now — and it
     * is **not** whatever `pactl get-default-sink` answers. On Linux there are two layers that both
     * keep a default, PulseAudio's database and the PipeWire graph's default node, and the players
     * this port spawns consult only the second. On the development machine the two disagree for
     * *sources*: `pactl get-default-source` names the analog input while WirePlumber marks no source
     * at all, so a label sourced from the first said "(default)" beside the one device `pw-record`
     * refuses to use without a `--target`.
     *
     * The seam therefore means the graph's default, and an implementation must ask the layer that
     * decides what actually happens. An unmarked device — `false` everywhere — is a real answer: it
     * is a machine where nothing will be chosen for you and the picker has to be used.
     */
    val isDefault: Boolean = false,
) {
    /**
     * A human-readable label. The desktop convention is `<name>` with the default marked, rather
     * than the phone's habit of naming the route ("Speaker", "Earpiece") — those are Android
     * routing concepts and the plan drops them (§9).
     */
    val label: String get() = if (isDefault) "$name (default)" else name
}

/**
 * Seam 3 (PLAN.md §5): which devices exist, and which one to play to.
 *
 * Android reached devices through `AudioManager` and expressed the choice as a *route* — earpiece
 * or speaker, `setCommunicationDevice`, `isSpeakerphoneOn`. PLAN.md §9 replaces that with sink
 * selection: the same intent, a different model, and one that is per-process rather than global.
 *
 * ## Why this is three methods
 *
 * Each one has a call site behind it, and nothing here is speculative:
 *
 *  - [outputs] and [inputs] are for the device pickers — the desktop equivalent of the runtime
 *    permission prompts and the earpiece toggle, and the place a user points the assistant at the
 *    headphones that D8 says they should be wearing.
 *  - [earpiece] is what the ported audio code actually calls, once per playback. Upstream
 *    recomputes the routing on every `playAudioFile`/`speakWithEspeak` from the live earpiece
 *    flag; here that becomes one lookup that may legitimately return null, and null means "there
 *    is no separate earpiece on this machine, play to the default" — which is the correct answer
 *    on a desktop far more often than it was on a phone.
 *
 * [outputs] returning a list rather than a single "current" device is deliberate: on PipeWire the
 * user may have several, and which one is current is `isDefault`'s job to say.
 *
 * Nothing here starts or stops anything. Enumerating is a read; pointing a playback at a device is
 * [AudioPlayback]'s job, through the id this seam hands out.
 */
interface AudioDevices {

    fun outputs(): List<AudioDevice>

    fun inputs(): List<AudioDevice>

    /**
     * The output to use when the user has asked for earpiece routing, or **null when this machine
     * has no such device** — in which case the caller plays to the default output.
     *
     * Null is a real answer, not a failure. Upstream's earpiece mode was a phone concept and this
     * is the seam where it stops being one.
     */
    fun earpiece(): AudioDevice?
}
