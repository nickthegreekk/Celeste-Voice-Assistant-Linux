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

    private companion object {
        /**
         * How long to wait before concluding that nothing happened. There is no event to await
         * when the expected outcome is silence, so proving it takes a window; the call being
         * guarded against reaches the proxy in microseconds, so this is generous by orders of
         * magnitude and costs under a second per assertion.
         */
        const val SETTLE_MS = 600L
    }


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

        service = newService().also { started += it }
    }

    private val started = mutableListOf<AssistantService>()

    /**
     * A service over the *same* store — which is what "reopen the app" means for this test.
     *
     * Every service built here is registered so [tearDown] can shut it down. `start()` launches two
     * loops that are meant to live for the life of the process — the state collector and the
     * periodic health check — and a test that walks away from them leaves a coroutine that resumes
     * on a dispatcher `runTest` has already torn down. Kotlin reports that as an uncaught exception
     * *in whichever test runs next*, which is how it presented: four tests passing and a fifth
     * failing with `UncaughtExceptionsBeforeTest` and no clue which coroutine it came from.
     */
    private fun newService(): AssistantService = AssistantService(
        appEnv = env,
        audioPlayback = SilentAudioPlayback,
        audioDevices = NoAudioDevices,
        tts = NoTextToSpeech,
        audioCapture = NoAudioCapture,
        fileDialogs = NoFileDialogs,
        settingsManager = SettingsManager(env, store, NoImageCodec),
    )

    @After
    fun tearDown() {
        // Shut every service down before the dispatcher goes away. `runCatching` because a service
        // whose teardown throws would otherwise turn into a failure blamed on the next test — the
        // same misattribution this teardown exists to remove.
        started.forEach { runCatching { it.shutdown() } }
        started.clear()
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
        Thread.sleep(SETTLE_MS)

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
    fun `the second run is silent too, which is where the seeded provider template lives`() = runTest {
        // The gap the first test leaves, and it is the one the bug actually lived in.
        //
        // A first run is silent because it has *nothing* to fetch — no provider is configured and
        // none is stored. The seeded `DEFAULT_CLOUD_APIS` are written to the store during that
        // first run, so it is the SECOND launch that reads them back and walks them, and the
        // `"OpenAI-Compatible"` template with its empty key and `icon == "C"` is what got through
        // the old guard. Testing only the first run tests the half of this that was never wrong.
        //
        // The sequence is the one a person performs: launch, let it seed itself, close, open again.
        service.start()
        runCurrent()
        Thread.sleep(SETTLE_MS)
        assertTrue("the first run reached the network: ${witnesses.hosts}", witnesses.hosts.isEmpty())

        // What the UI does on a first run — `MainScreen` fills an empty provider list with the
        // templates. Through `updateCloudApi`, because that is the call it makes.
        DEFAULT_CLOUD_APIS.forEach { service.updateCloudApi(-1, it) }
        runCurrent()
        Thread.sleep(SETTLE_MS)
        assertTrue(
            "seeding the provider templates reached the network: ${witnesses.hosts}. A template is " +
                "not a configuration; the seeding path must not be able to start a fetch.",
            witnesses.hosts.isEmpty(),
        )

        // Close and reopen, over the same store.
        val second = newService().also { started += it }
        second.start()
        runCurrent()
        Thread.sleep(SETTLE_MS)

        assertTrue(
            "the second run reached the network: ${witnesses.hosts}. This is the bug — the seeded " +
                "templates are in the store by now, and an automatic fetch for an unconfigured one " +
                "is a call the user did not make (AGENTS.md §5).",
            witnesses.hosts.isEmpty(),
        )
    }

    @Test
    fun `configuring a provider does fetch, which is what makes the silence above a choice`() = runTest {
        // The other side of the rule, and the reason the two tests above are not just asserting
        // that the feature is dead. Saving a provider is one of PLAN.md §5's two user-initiated
        // triggers: pointing an OpenAI-compatible entry at a real server is exactly when its model
        // list becomes both fetchable and wanted.
        service.start()
        runCurrent()

        val configured = CloudApiSetting(
            name = "OpenAI-Compatible",
            baseUrl = "http://192.0.2.50:8080/v1",
            apiKey = "",
            icon = "C",
            color = androidx.compose.ui.graphics.Color(0xFF808080),
            isEditableUrl = true,
        )
        service.updateCloudApi(-1, configured)

        val deadline = System.currentTimeMillis() + 5_000
        while (witnesses.hosts.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)

        assertEquals(
            "saving a provider with a URL of the user's own must fetch its models — this is the " +
                "trigger that replaces the startup fetch",
            listOf("192.0.2.50"),
            witnesses.hosts,
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
        // Both throw rather than no-op: a first run posts nothing and arms nothing, so a fake that
        // answered would be hiding a call rather than serving one.
        override val notifier: Notifier
            get() = throw UnsupportedOperationException("a first run posts no notification")
        // The guard is on `arm`, not on touching the seam at all: `AssistantService.start()` attaches
        // its scope to an in-process scheduler (seam 10) so a persona enabled later can be armed —
        // attaching arms nothing, sends nothing and schedules nothing, and M4 is when that hook had
        // to exist. What must stay impossible on a first run is a *timer*, and that is what throws.
        override val scheduler: Scheduler = object : Scheduler {
            override fun arm(personaName: String, slot: Int, delayMillis: Long): Unit =
                throw UnsupportedOperationException("a first run arms no timer")

            override fun disarm(personaName: String) = Unit

            override fun isArmed(personaName: String): Boolean = false
        }
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
        override fun openDirectory(title: String): Path? = null
    }
}
