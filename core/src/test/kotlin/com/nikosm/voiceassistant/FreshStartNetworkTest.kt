package com.nikosm.voiceassistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.nio.file.Path

/**
 * **A first run must not reach the network by itself.** AGENTS.md §5: "No network call that the
 * user did not configure, except the build's own dependency resolution."
 *
 * This exists because the rule was violated once and nothing caught it. `syncOpenRouterPricing()`
 * fired from `AssistantService.start()`, throttled to once a day by a stored timestamp — and on a
 * fresh install that timestamp is zero, so **the first launch always made the call**. It went to
 * `https://openrouter.ai/api/v1/models`, a host the user never named, and fetched 465 model prices
 * into a 42 KB settings file. The only thing that noticed was a human reading the file's size.
 *
 * The plan did not catch it, and could not have: §8's list of dropped features named the *update
 * check* as an unconfigured call and this one was named nowhere. So the guard is a test rather than
 * a reading.
 *
 * ## Two independent assertions, because either alone is defeatable
 *
 * **The store assertion** — no pricing key appears — is what the first launch would have failed in
 * a networked environment, and it is the state a person can observe.
 *
 * **The request assertion** is what makes it hermetic. A JVM-wide [ProxySelector] that records
 * every host OkHttp asks about means the test fails on a machine with no network and a machine with
 * one, and it names the host. The store assertion alone would pass on an airgapped CI box even if
 * the call had been restored — because the call would fail and write nothing — which is exactly the
 * kind of test that reads as a guard and is not one.
 *
 * The proxy is not installed as a proxy: it throws before any connection is opened, so nothing ever
 * leaves the process. It is installed as a *witness*.
 */
class FreshStartNetworkTest {

    private lateinit var env: FakeAppEnv
    private lateinit var store: InMemoryStore
    private lateinit var service: AssistantService
    private lateinit var witnesses: WitnessingProxySelector

    @Before
    fun setUp() {
        // `serviceScope` is `Dispatchers.Main`, which a JVM test has to supply. `StandardTestDispatcher`
        // rather than `UnconfinedTestDispatcher`: the point is to run what `start()` launched
        // *deliberately*, so a test that forgot to would fail rather than pass on a technicality.
        Dispatchers.setMain(StandardTestDispatcher())

        env = FakeAppEnv()
        store = InMemoryStore()
        witnesses = WitnessingProxySelector()
        ProxySelector.setDefault(witnesses)

        val manager = SettingsManager(env, store, NoImageCodec)
        service = AssistantService(
            appEnv = env,
            audioPlayback = SilentAudioPlayback,
            audioDevices = NoAudioDevices,
            tts = NoTextToSpeech,
            audioCapture = NoAudioCapture,
            fileDialogs = NoFileDialogs,
            settingsManager = manager,
        )
    }

    @After
    fun tearDown() {
        ProxySelector.setDefault(null)
        Dispatchers.resetMain()
    }

    @Test
    fun `a first run writes no pricing data and asks no host about prices`() = runTest {
        service.start()
        // Let everything `start()` launched run to its first suspension or its end.
        runCurrent()

        // **And then wait, because proving a negative needs a window rather than a signal.**
        //
        // `start()` launches its work on `Dispatchers.IO`, which the test scheduler does not drive,
        // so `runCurrent()` returns before a startup fetch would have made its request. The first
        // version of this test asserted straight after `runCurrent()` — and passed with the
        // openrouter call deliberately put back, which is the failure mode its own comment above
        // warns about. A sleep is the right tool here and the only one: there is no event to await
        // when the expected outcome is that nothing happens. The call it is guarding against
        // reaches the proxy in microseconds, so 750 ms is two orders of magnitude of headroom.
        Thread.sleep(750)

        val priceKeys = store.allKeys().filter { it.contains("price", ignoreCase = true) }
        assertTrue(
            "a fresh start wrote pricing keys: $priceKeys — something fetched a price list at boot",
            priceKeys.isEmpty(),
        )

        assertTrue(
            "a fresh start reached the network: ${witnesses.hosts}. AGENTS.md §5 allows no call " +
                "the user did not configure, and a first run has configured nothing.",
            witnesses.hosts.isEmpty(),
        )
    }

    @Test
    fun `the price sync still exists and still asks openrouter when it is called`() = runTest {
        // The other half of the guard, and the half that keeps the first one honest: a test that
        // only asserts "no request happens" passes just as well if the feature was deleted by
        // accident, or if the proxy witness stopped observing anything. This one proves the
        // witness works, by making the call on purpose and watching it arrive.
        service.syncOpenRouterPricing(force = true)

        // Waited for rather than advanced: the function launches on `Dispatchers.IO`, which the
        // test scheduler does not drive, so `runCurrent()` would return before the call had been
        // made and the assertion below would fail for a reason that has nothing to do with the
        // behaviour it is checking. The deadline is generous because the call fails at the proxy
        // in microseconds; what it is really guarding is a hang.
        val deadline = System.currentTimeMillis() + 5_000
        while (witnesses.hosts.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)

        assertEquals(
            "the manual price sync must still be reachable and must still be the thing that " +
                "talks to openrouter",
            listOf("openrouter.ai"),
            witnesses.hosts,
        )
    }

    // ------------------------------------------------------------------ the seams, as fakes

    /**
     * Records every host OkHttp resolves a proxy for, and refuses to give it one. Refusing is what
     * keeps the test hermetic — the call fails before a socket is opened — and the record is what
     * makes the failure legible.
     */
    private class WitnessingProxySelector : ProxySelector() {
        val hosts = mutableListOf<String>()

        override fun select(uri: URI): List<Proxy> {
            synchronized(hosts) { hosts.add(uri.host ?: uri.toString()) }
            throw IllegalStateException("this test refuses to open a connection to ${uri.host}")
        }

        override fun connectFailed(uri: URI, sa: SocketAddress, ioe: java.io.IOException) = Unit
    }

    private class FakeAppEnv : AppEnv {
        private val root: Path = java.nio.file.Files.createTempDirectory("celeste-fresh-start")
        override val configDir: Path get() = root.resolve("config")
        override val dataDir: Path get() = root.resolve("data")
        override val stateDir: Path get() = root.resolve("state")
        override val cacheDir: Path get() = root.resolve("cache")
        override val debugLogging: Boolean get() = false
        override val logger: AppLogger get() = StderrLogger
        // Both throw rather than no-op: a first run posts nothing and arms nothing, so a fake that
        // answered would be hiding a call rather than serving one.
        override val notifier: Notifier
            get() = throw UnsupportedOperationException("a first run posts no notification")
        override val scheduler: Scheduler
            get() = throw UnsupportedOperationException("a first run arms no timer")
    }

    /** The whole store, in a map. No file, no `~/.config`, nothing to restore afterwards. */
    private class InMemoryStore : SettingsStore {
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

    private object NoImageCodec : ImageCodec {
        override fun downscaleToJpeg(source: Path, target: Path, maxDim: Int, quality: Int): Boolean = false
    }

    private object SilentAudioPlayback : AudioPlayback {
        override fun play(file: Path, target: AudioDevice?, onEnd: (PlaybackEnd) -> Unit): PlaybackHandle =
            throw UnsupportedOperationException("a first run plays nothing")
        override fun playPcm(samples: ByteArray, sampleRate: Int, target: AudioDevice?, onEnd: (PlaybackEnd) -> Unit): PlaybackHandle =
            throw UnsupportedOperationException("a first run plays nothing")
        override fun stopAll() = Unit
    }

    private object NoAudioDevices : AudioDevices {
        override fun outputs(): List<AudioDevice> = emptyList()
        override fun inputs(): List<AudioDevice> = emptyList()
        override fun earpiece(): AudioDevice? = null
    }

    private object NoTextToSpeech : TextToSpeech {
        override val systemReady: Boolean get() = false
        override fun speakSystem(text: String, utteranceId: String, onEnd: (PlaybackEnd) -> Unit): Boolean = false
        override fun stopSystem() = Unit
        override fun synthesizeLocal(text: String, voice: String): SpeechSamples? = null
        override fun releaseLocal() = Unit
    }

    private object NoAudioCapture : AudioCapture {
        override fun open(sampleRate: Int, chunkFrames: Int, target: AudioDevice?): CaptureStream? = null
    }

    private object NoFileDialogs : FileDialogs {
        override fun openImage(title: String): Path? = null
        override fun openTextFile(title: String): Path? = null
        override fun openFiles(title: String): List<Path> = emptyList()
        override fun save(defaultName: String, title: String): Path? = null
    }
}
