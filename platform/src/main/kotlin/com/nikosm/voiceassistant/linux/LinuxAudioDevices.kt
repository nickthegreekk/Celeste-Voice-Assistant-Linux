package com.nikosm.voiceassistant.linux

import com.nikosm.voiceassistant.AppLogger
import com.nikosm.voiceassistant.AudioDevice
import com.nikosm.voiceassistant.AudioDevices
import com.nikosm.voiceassistant.StderrLogger
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Seam 3, implemented by asking `pactl`, with `wpctl` as the fallback.
 *
 * ## Why `pactl` leads, when PLAN.md §5 writes it as "wpctl/pactl"
 *
 * Because the **id has to be usable by whichever player ends up running**, and only one identifier
 * satisfies both. `PwPlayback` tries `pw-play` and falls back to `paplay`; `pw-play --target`
 * accepts "a node target serial or name" and `paplay --device` accepts a sink *name* only. The
 * PulseAudio node name — `alsa_output.pci-0000_2f_00.4.analog-stereo` — is the one spelling both
 * take, and `pactl list sinks` hands it over directly.
 *
 * `wpctl status` does not: it prints a numeric serial and a human description, so a name costs one
 * `wpctl inspect <serial>` per node. Two sinks should not be three processes. So `wpctl` is the
 * fallback and uses the serial as the id — which `pw-play` accepts, and which `paplay` does not,
 * though a machine missing `pactl` is a machine missing `paplay` by the same token.
 *
 * ## The two things the output does that a naive parse gets wrong
 *
 * **`wpctl status` has a `Video` section with `Sinks:` and `Sources:` of its own**, both empty on
 * most machines. A parse that looks for "the Sinks: section" finds the video one and reports that
 * there are no audio devices. The section boundary is what the parse tracks, not the key.
 *
 * **`pactl list sinks` has a `Name:` inside every `Monitor Source:` and inside its `Ports:` block.**
 * A parse that greps for `Name:` collects monitor names as if they were sinks. The entries are
 * tab-indented, the block starts at column zero with `Sink #`, and exactly one tab's worth of
 * indent is the block's own fields — so the parse takes *the first* one-tab `Name:` after a `Sink #`
 * line and stops looking for that block.
 *
 * ## What it never does
 *
 * It does not start, stop or reconfigure anything: enumerating is a read, and pointing a playback
 * at a device is [com.nikosm.voiceassistant.AudioPlayback]'s job through the id this hands out.
 * Every process is bounded by [PROBE_TIMEOUT_MS], because this runs on the UI thread behind a
 * picker and a wedged `pactl` must not be able to hang the window.
 */
class LinuxAudioDevices(
    private val logger: AppLogger = StderrLogger,
) : AudioDevices {

    override fun outputs(): List<AudioDevice> = enumerate("sinks")

    /**
     * The microphones, **without the monitor sources**.
     *
     * PulseAudio exposes every sink's monitor as a source — "Monitor of Starship/Matisse HD Audio
     * Controller Analog Stereo" — and `pactl list sources` lists them beside the real inputs. They
     * are genuine capture devices and genuinely useful for recording the machine's own output. They
     * are not microphones, and every caller of this method is a microphone path: the VAD capture,
     * push-to-talk, and the `--target` that goes to `pw-record`. A user who picks one gets the
     * system's own playback fed into a speech recogniser, which fails as an incomprehensible
     * transcript rather than as an error.
     *
     * Filtered by the `.monitor` suffix, which is the name `pactl` guarantees a monitor source
     * carries. A machine that wants system-audio capture is a machine that will ask for it.
     */
    override fun inputs(): List<AudioDevice> = enumerate("sources").filterNot { it.id.endsWith(MONITOR_SUFFIX) }

    /**
     * **Always null, and the stub predicted it.** `AudioDeviceProbe`'s note said "PLAN.md §9
     * replaces the phone's route toggle with sink selection anyway, so the real M2 answer may well
     * stay null here while `outputs()` grows" — and it has.
     *
     * Null is a real answer here rather than a gap: null means "play to the default", which is what
     * the callers do with it. The phone concept has no desktop counterpart — a tower has no
     * earpiece — and the *user-visible* need it served, which D8 says is private listening with
     * headphones, is served by picking a sink in the picker that [outputs] feeds. Guessing which of
     * a machine's sinks is "the headphones" would be wrong more often than right: the analog stereo
     * sink on this box is the motherboard's line-out, and on a laptop it is the built-in speakers.
     */
    override fun earpiece(): AudioDevice? = null

    // ---------------------------------------------------------------- enumeration

    private fun enumerate(kind: String): List<AudioDevice> {
        val blocks = run(listOf("pactl", "list", kind))
        if (blocks != null) {
            val devices = parsePactl(blocks)
            if (devices.isNotEmpty()) {
                val defaultName = wpctlDefaultName(kind)
                return devices.map { it.copy(isDefault = it.id == defaultName) }
            }
        }
        val status = run(listOf("wpctl", "status"))
        if (status != null) return parseWpctl(status, kind)
        logger.w(TAG, "no audio device list: neither pactl nor wpctl answered")
        return emptyList()
    }

    /**
     * `pactl list` prints one block per entry, each opening at column zero with `Sink #12` or
     * `Source #12` and carrying its fields at one tab. Parsing is per block for the reason in the
     * class note: the fields of a different kind of entry sit inside these ones.
     */
    private fun parsePactl(lines: List<String>): List<AudioDevice> {
        val devices = mutableListOf<AudioDevice>()
        var name: String? = null
        var description: String? = null

        fun flush() {
            val n = name
            if (n != null) devices.add(AudioDevice(id = n, name = description ?: n))
            name = null
            description = null
        }

        for (line in lines) {
            // A new block: the previous one is complete.
            if (line.startsWith("Sink #") || line.startsWith("Source #")) {
                flush()
                continue
            }
            // Exactly one tab, and only the first of each key: a second `Name:` inside this block
            // belongs to its `Ports:` list.
            if (!line.startsWith("\t") || line.startsWith("\t\t")) continue
            val field = line.removePrefix("\t")
            when {
                field.startsWith("Name:") && name == null -> name = field.removePrefix("Name:").trim()
                field.startsWith("Description:") && description == null ->
                    description = field.removePrefix("Description:").trim()
            }
        }
        flush()
        return devices
    }

    /**
     * `wpctl status` — the fallback, and the ids it yields are node serials.
     *
     * The section tracking is the whole trick. Under `Audio` there are `Devices:`, `Sinks:`,
     * `Sources:`, `Filters:` and `Streams:`; under `Video` the same headings appear again, which is
     * why "find Sinks:" is not a question whose answer means anything. The parse follows the
     * top-level section, then the sub-heading inside it, and reads the `* `<serial>. <description>`
     * entries between one sub-heading and the next.
     */
    private data class WpctlEntry(val serial: Int, val isDefault: Boolean, val description: String)

    private fun parseWpctl(lines: List<String>, kind: String): List<AudioDevice> =
        wpctlEntries(lines, kind).map { entry ->
            AudioDevice(
                // The serial, because `pw-play --target` accepts it and this is the fallback path —
                // reached when `pactl` is absent, which is when the PulseAudio spelling would not
                // have helped anyway.
                id = entry.serial.toString(),
                name = entry.description.ifBlank { "node ${entry.serial}" },
                isDefault = entry.isDefault,
            )
        }

    /** The one walk over `wpctl status`, shared by the enumeration and the default lookup. */
    private fun wpctlEntries(lines: List<String>, kind: String): List<WpctlEntry> {
        val wanted = if (kind == "sinks") "Sinks:" else "Sources:"
        val entries = mutableListOf<WpctlEntry>()
        var inAudio = false
        var inList = false

        for (line in lines) {
            // Top-level sections have no leading box-drawing character.
            if (line.isNotBlank() && !line.startsWith(" ") && !line.startsWith("\t")) {
                inAudio = line.trim() == "Audio"
                inList = false
                continue
            }
            if (!inAudio) continue
            val stripped = line.trim().trimStart('│', '├', '└', '─', ' ')
            if (stripped.endsWith(":")) {
                inList = stripped == wanted
                continue
            }
            if (!inList || stripped.isEmpty()) continue
            // `*  34. Name` marks the default; `   59. Name` is the rest.
            val isDefault = stripped.startsWith("*")
            val body = stripped.removePrefix("*").trim()
            val serial = body.substringBefore('.').trim().toIntOrNull() ?: continue
            val description = body.substringAfter('.', "").trim()
                .substringBefore("[")   // drop the `[vol: 0.25]` suffix
                .trim()
            entries.add(WpctlEntry(serial, isDefault, description))
        }
        return entries
    }

    /**
     * Which node is the default, asked of the layer that decides it — and this is deliberately not
     * `pactl get-default-sink`.
     *
     * `pactl get-default-*` answers **PulseAudio's** question. `pw-play` and `pw-record` do not
     * consult that database; they ask the PipeWire graph, whose default is the node WirePlumber
     * marks with `*` in `wpctl status`. On the development machine those two answers **differ for
     * sources**: `pactl get-default-source` names the analog input, `wpctl status` marks no source
     * at all, and `pw-record` with no `--target` fails with "no target node available". So the
     * picker labelled the one input the recorder would refuse to use as "(default)".
     *
     * It is a two-call answer and worth both. `wpctl status` gives the marked node's *serial*;
     * `wpctl inspect <serial>` gives its `node.name`, which is the same spelling `pactl list`
     * produced and therefore the thing that joins the two lists. Not the serial: those are PipeWire
     * global object ids and they shift between runs, so the same sink was 34 in one command and 60
     * in another a minute later — which is what a join by id would have silently got wrong.
     *
     * Null when there is no default, which is a real answer and printed as one: an unmarked device
     * is a device the players will not choose on their own.
     */
    private fun wpctlDefaultName(kind: String): String? {
        val status = run(listOf("wpctl", "status")) ?: return null
        val serial = wpctlEntries(status, kind).firstOrNull { it.isDefault }?.serial ?: return null
        return run(listOf("wpctl", "inspect", serial.toString()))
            ?.firstOrNull { it.contains("node.name") }
            ?.substringAfter("node.name", "")
            ?.substringAfter("=", "")
            ?.trim()
            ?.trim('"')
            ?.takeIf { it.isNotEmpty() }
    }

    /**
     * Runs [command] and returns its output, or null if it cannot be run or does not answer in time.
     *
     * `redirectErrorStream` because `pactl` reports "Connection failure" on stderr and exits
     * non-zero; merging means a caller that only wanted to know whether it worked still sees why.
     * The bounded wait is the point of the helper: a `pactl` blocked on a sound server that is
     * starting must not block the picker that asked.
     */
    private fun run(command: List<String>): List<String>? {
        val process = try {
            ProcessBuilder(command).redirectErrorStream(true).start()
        } catch (e: IOException) {
            logger.d(TAG, "${command.first()} is not available: ${e.message}")
            return null
        }
        return try {
            val output = process.inputStream.bufferedReader().use { it.readLines() }
            if (!process.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                logger.w(TAG, "${command.first()} did not answer within ${PROBE_TIMEOUT_MS}ms")
                process.destroyForcibly()
                null
            } else if (process.exitValue() != 0) {
                logger.w(TAG, "${command.first()} exited ${process.exitValue()}")
                null
            } else {
                output
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroyForcibly()
            null
        }
    }

    private companion object {
        const val TAG = "LinuxAudioDevices"

        /**
         * Generous for a tool that answers in milliseconds, and short enough that a wedged sound
         * server is a picker that stays empty rather than a window that stops repainting.
         */
        const val PROBE_TIMEOUT_MS = 3_000L

        /** What `pactl` names a sink monitor exposed as a source. */
        const val MONITOR_SUFFIX = ".monitor"
    }
}
