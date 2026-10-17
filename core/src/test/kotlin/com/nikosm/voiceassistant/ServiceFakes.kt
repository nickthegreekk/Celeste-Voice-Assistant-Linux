package com.nikosm.voiceassistant

import java.nio.file.Files
import java.nio.file.Path

/**
 * The service's seams as fakes, so a test can build one and run a real turn against it.
 *
 * These began inside `FreshStartNetworkTest`, which needed them to prove that a first run makes no
 * request. A second test needs the same harness — the cloud tool loop is a turn that only a
 * *running service* can exercise — so they live here rather than being copied, and the two tests
 * now drive the same fakes rather than two drifting sets of them.
 *
 * **Every fake here defaults to refusing rather than to answering.** A call that a test did not
 * expect is then a failure with a sentence in it, not a silent success: `FreshStartNetworkTest`'s
 * whole point is that a first run does *nothing* to the network or to the desktop, and a fake that
 * quietly served the call would hide exactly the behaviour it is watching for.
 */

/**
 * Paths under one temporary root, none of which are created.
 *
 * Nothing here is a real XDG directory, and that is the rule rather than a convenience: a test that
 * reached the user's own `~/.config` would be a test that could damage their settings (AGENTS.md
 * §4). [homeDir] deliberately points at a directory that does not exist — the workspace rules that
 * read it (`~/.ssh` above all) are tested against real temporary directories of their own.
 */
internal class FakeAppEnv(
    override val notifier: Notifier = ThrowingNotifier,
    override val scheduler: Scheduler = ThrowingScheduler,
) : AppEnv {
    // Removed when the test JVM exits — see [TempRoots] for why `deleteOnExit` was not enough (the
    // audit writer creates `state/` inside it, so the tree is not empty). Before this, every test
    // that built a service left one behind: 561 had accumulated in /tmp by 2026-10-17.
    private val root: Path = TempRoots.newRoot("celeste-test")
    override val configDir: Path get() = root.resolve("config")
    override val dataDir: Path get() = root.resolve("data")
    override val stateDir: Path get() = root.resolve("state")
    override val cacheDir: Path get() = root.resolve("cache")
    override val picturesDir: Path get() = cacheDir.resolve("Pictures")
    override val applicationsDir: Path get() = cacheDir.resolve("applications")
    // No Desktop in a test: `null` is not possible on the interface, so this points at a directory
    // that does not exist — which is exactly the case the writer has to skip (it never creates it).
    override val desktopDir: Path get() = cacheDir.resolve("no-such-desktop")
    override val homeDir: Path get() = cacheDir.resolve("home")
    override val debugLogging: Boolean get() = false
    override val logger: AppLogger get() = StderrLogger
}

/**
 * Throws on **every** member, `enabled` included — which is the whole point and was the original
 * fake's behaviour: a test that only means to prove silence should fail loudly if the code under it
 * reaches for the desktop at all, not quietly take "no" for an answer.
 */
internal object ThrowingNotifier : Notifier {
    override val enabled: Boolean
        get() = throw UnsupportedOperationException("this test posts no notification")

    override fun post(id: Int, title: String, body: String): Unit =
        throw UnsupportedOperationException("this test posts no notification")

    override fun cancel(id: Int) = Unit
}

/**
 * Records what would have gone to the desktop, so a test can assert that it did — or that it did
 * not, which for a notification is the assertion that usually matters.
 *
 * `enabled` is true rather than throwing: unlike [ThrowingNotifier], the point here is to be a
 * working notifier, and a test that wants the seam to be unreachable should use the one that throws.
 */
internal class RecordingNotifier : Notifier {
    internal data class Posted(val id: Int, val title: String, val body: String)

    private val messages = java.util.Collections.synchronizedList(mutableListOf<Posted>())

    val posted: List<Posted> get() = messages.toList()

    override val enabled: Boolean = true

    override fun post(id: Int, title: String, body: String) {
        messages += Posted(id, title, body)
    }

    override fun cancel(id: Int) = Unit
}

/**
 * The guard is on `arm`, not on touching the seam at all: `AssistantService.start()` attaches its
 * scope to an in-process scheduler (seam 10) so a persona enabled later can be armed — attaching
 * arms nothing, sends nothing and schedules nothing. What must stay impossible in a test that did
 * not ask for it is a *timer*, and that is what throws.
 */
internal object ThrowingScheduler : Scheduler {
    override fun arm(personaName: String, slot: Int, delayMillis: Long): Unit =
        throw UnsupportedOperationException("this test arms no timer")

    override fun disarm(personaName: String) = Unit

    override fun isArmed(personaName: String): Boolean = false
}

/**
 * Records what was armed and disarmed, without starting any timer.
 *
 * The spontaneous tests need to observe SCHEDULING decisions — "addPersona armed the persona it
 * just created" — which WorkManager used to answer through `isSpontaneouslyArmed`. This keeps the
 * same observable (`isArmed`) and adds the history, so a test can also assert the slot and the
 * delay when that is the point.
 */
internal class RecordingScheduler : Scheduler {
    data class Armed(val personaName: String, val slot: Int, val delayMillis: Long)

    private val lock = Any()
    private val armHistory = mutableListOf<Armed>()
    private val disarmHistory = mutableListOf<String>()
    private val currentlyArmed = mutableSetOf<String>()

    val arms: List<Armed> get() = synchronized(lock) { armHistory.toList() }
    val disarms: List<String> get() = synchronized(lock) { disarmHistory.toList() }

    override fun arm(personaName: String, slot: Int, delayMillis: Long) {
        synchronized(lock) {
            armHistory += Armed(personaName, slot, delayMillis)
            currentlyArmed += personaName
        }
    }

    override fun disarm(personaName: String) {
        synchronized(lock) {
            disarmHistory += personaName
            currentlyArmed -= personaName
        }
    }

    override fun isArmed(personaName: String): Boolean =
        synchronized(lock) { personaName in currentlyArmed }
}

/** The whole store, in a map. No file, no `~/.config`, nothing to restore afterwards. */
internal class InMemoryStore : SettingsStore {
    private val values = mutableMapOf<String, Any>()

    override fun getString(key: String, default: String?): String? = values[key] as? String ?: default
    override fun getLong(key: String, default: Long): Long = values[key] as? Long ?: default
    override fun getFloat(key: String, default: Float): Float = values[key] as? Float ?: default
    override fun getBoolean(key: String, default: Boolean): Boolean = values[key] as? Boolean ?: default
    override fun allKeys(): Set<String> = values.keys.toSet()
    override fun edit(): SettingsEdit = object : SettingsEdit {
        private val staged = mutableMapOf<String, Any?>()
        override fun putString(key: String, value: String) = apply { staged[key] = value }
        override fun putLong(key: String, value: Long) = apply { staged[key] = value }
        override fun putFloat(key: String, value: Float) = apply { staged[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { staged[key] = value }
        override fun remove(key: String) = apply { staged[key] = null }
        override fun commit() {
            staged.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
        }
    }
}

/** No image codec: nothing in these tests downscales, and a fake that did would be a fake decoder. */
internal object NoImageCodec : ImageCodec {
    override fun downscaleToJpeg(source: Path, target: Path, maxDim: Int, quality: Int): Boolean = false
}

internal object SilentAudioPlayback : AudioPlayback {
    override fun play(file: Path, target: AudioDevice?, onEnd: (PlaybackEnd) -> Unit): PlaybackHandle =
        throw UnsupportedOperationException("this test plays nothing")

    override fun playPcm(samples: ByteArray, sampleRate: Int, target: AudioDevice?, onEnd: (PlaybackEnd) -> Unit): PlaybackHandle =
        throw UnsupportedOperationException("this test plays nothing")

    override fun stopAll() = Unit
}

internal object NoAudioDevices : AudioDevices {
    override fun outputs(): List<AudioDevice> = emptyList()
    override fun inputs(): List<AudioDevice> = emptyList()
    override fun earpiece(): AudioDevice? = null
}

internal object NoTextToSpeech : TextToSpeech {
    override val systemReady: Boolean get() = false
    override fun speakSystem(text: String, utteranceId: String, onEnd: (PlaybackEnd) -> Unit): Boolean = false
    override fun stopSystem() = Unit
    override fun synthesizeLocal(text: String, voice: String): SpeechSamples? = null
    override fun releaseLocal() = Unit
}

internal object NoAudioCapture : AudioCapture {
    override fun open(sampleRate: Int, chunkFrames: Int, target: AudioDevice?): CaptureStream? = null
}

internal object NoFileDialogs : FileDialogs {
    override fun openImage(title: String): Path? = null
    override fun openTextFile(title: String): Path? = null
    override fun openFiles(title: String): List<Path> = emptyList()
    override fun save(defaultName: String, title: String): Path? = null
    override fun openDirectory(title: String): Path? = null
}
