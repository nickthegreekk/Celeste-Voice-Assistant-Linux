package com.nikosm.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Path

/**
 * The stored side of source/sink selection — PLAN.md §9's substitution for the phone's
 * earpiece/Bluetooth route toggle.
 *
 * Three things are pinned here, and they are the three that would fail silently:
 *
 *  - **Matching is exact.** The stored string is the id a player is pointed at, so a
 *    near-match must not be handed over; a device that is gone has to read as *gone*.
 *  - **"No preference" and "the device is gone" both answer null**, which is what the callers fall
 *    back on — and the callers are where the two are told apart in words (`sinkForPersona`,
 *    `inputDeviceForCapture`).
 *  - **The per-persona choice has a lifecycle.** It is name-keyed in settings rather than a
 *    `Persona` field, because `Models.kt` is a COPY file — so it has to follow the persona through
 *    a rename and die with it on deletion, exactly as the message history and the spontaneous
 *    delivery state beside it do. Both halves are asserted through the same shared surface.
 */
class AudioDeviceSelectionTest {

    private val outputs = listOf(
        AudioDevice(id = "alsa_output.hdmi", name = "HDMI", isDefault = true),
        AudioDevice(id = "alsa_output.analog", name = "Analog Stereo"),
    )

    @Test
    fun `a stored id resolves to its device`() {
        assertEquals("alsa_output.analog", pickDevice(outputs, "alsa_output.analog")?.id)
    }

    @Test
    fun `an unknown or absent id resolves to nothing`() {
        assertNull("a device that is gone", pickDevice(outputs, "alsa_output.usb_that_left"))
        assertNull("a prefix is not a match", pickDevice(outputs, "alsa_output"))
        assertNull("nothing stored", pickDevice(outputs, null))
        assertNull("blank stored", pickDevice(outputs, "  "))
    }

    @Test
    fun `the input device round-trips, and clearing it means the default`() {
        val manager = manager()

        assertNull("nothing stored yet", manager.getAudioInputDevice())
        manager.saveAudioInputDevice("alsa_input.usb_headset")
        assertEquals("alsa_input.usb_headset", manager.getAudioInputDevice())

        // Clearing stores an empty string rather than removing the key — the store has no
        // "remove" on this path, and blank has to read as "the default" rather than as a device
        // with an empty name.
        manager.saveAudioInputDevice(null)
        assertNull(manager.getAudioInputDevice())
        manager.saveAudioInputDevice("   ")
        assertNull(manager.getAudioInputDevice())
    }

    @Test
    fun `a persona's chosen output is stored under its name, and clearing it removes the entry`() {
        val manager = manager()

        assertNull("nothing chosen yet", manager.getPersonaOutputDevice("Celeste"))
        manager.savePersonaOutputDevice("Celeste", "alsa_output.analog")
        assertEquals("alsa_output.analog", manager.getPersonaOutputDevice("Celeste"))
        assertNull("and nothing else is touched", manager.getPersonaOutputDevice("Friend"))

        manager.savePersonaOutputDevice("Celeste", null)
        assertNull("choosing the default stores nothing", manager.getPersonaOutputDevice("Celeste"))
    }

    @Test
    fun `a rename carries the choice, and a deletion drops it`() {
        // The reason this state is name-keyed at all: it has a lifecycle to keep in step with the
        // persona, and both halves of it are shared with the message history and the spontaneous
        // delivery state.
        val manager = manager()
        manager.savePersonaOutputDevice("Old name", "alsa_output.hdmi")

        manager.migratePersonaHistory("Old name", "New name")

        assertNull("the old key is gone", manager.getPersonaOutputDevice("Old name"))
        assertEquals("alsa_output.hdmi", manager.getPersonaOutputDevice("New name"))

        manager.deletePersonaHistory("New name")
        assertNull("deletion takes it with the history", manager.getPersonaOutputDevice("New name"))
    }

    // ---------------------------------------------------------------- harness

    /**
     * A manager over an in-memory store. Deliberately not the `:platform` `JsonSettingsStore`:
     * `:core` may not import from `:platform` (AGENTS.md §2), and this test is about the two
     * accessors' contract, not about the file format — which `JsonSettingsStoreTest` pins.
     */
    private fun manager(): SettingsManager = SettingsManager(
        appEnv = FakeAppEnv(),
        store = InMemoryStore(),
        imageCodec = object : ImageCodec {
            // Reports failure rather than throwing: the honest answer for "nothing here touches
            // artwork", and the one the manager is written to tolerate.
            override fun downscaleToJpeg(source: Path, target: Path, maxDim: Int, quality: Int): Boolean = false
        },
    )

    private class InMemoryStore : SettingsStore {
        private val map = HashMap<String, Any>()
        override fun getString(key: String, default: String?): String? = map[key] as? String ?: default
        override fun getLong(key: String, default: Long): Long = map[key] as? Long ?: default
        override fun getFloat(key: String, default: Float): Float = map[key] as? Float ?: default
        override fun getBoolean(key: String, default: Boolean): Boolean = map[key] as? Boolean ?: default
        override fun allKeys(): Set<String> = map.keys.toSet()
        override fun edit(): SettingsEdit = object : SettingsEdit {
            private val changes = HashMap<String, Any?>()
            override fun putString(key: String, value: String): SettingsEdit = also { changes[key] = value }
            override fun putLong(key: String, value: Long): SettingsEdit = also { changes[key] = value }
            override fun putFloat(key: String, value: Float): SettingsEdit = also { changes[key] = value }
            override fun putBoolean(key: String, value: Boolean): SettingsEdit = also { changes[key] = value }
            override fun remove(key: String): SettingsEdit = also { changes[key] = null }
            override fun commit() {
                changes.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
            }
        }
    }

    private class FakeAppEnv : AppEnv {
        override val configDir: Path get() = Path.of("/nonexistent/config")
        override val dataDir: Path get() = Path.of("/nonexistent/data")
        override val stateDir: Path get() = Path.of("/nonexistent/state")
        override val cacheDir: Path get() = Path.of("/nonexistent/cache")
        override val picturesDir: Path get() = cacheDir.resolve("Pictures")
    override val applicationsDir: Path get() = cacheDir.resolve("applications")
    // No Desktop in a test: `null` is not possible on the interface, so this points at a directory
    // that does not exist — which is exactly the case the writer has to skip (it never creates it).
    override val desktopDir: Path get() = cacheDir.resolve("no-such-desktop")
    // A home that does not exist, like the Desktop above: nothing in these tests reaches into it,
    // and the workspace rules that will are tested against real temp directories of their own.
    override val homeDir: Path get() = cacheDir.resolve("home")
        override val debugLogging: Boolean get() = false
        override val logger: AppLogger get() = StderrLogger
        override val notifier: Notifier get() = throw UnsupportedOperationException("no selection test notifies")
        override val scheduler: Scheduler get() = throw UnsupportedOperationException("no selection test schedules")
    }
}
