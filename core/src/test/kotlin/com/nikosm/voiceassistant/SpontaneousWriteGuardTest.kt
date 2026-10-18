package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Spontaneous messages — the cloud guard on the IN-MEMORY write path, proven through the real
 * service (ported from upstream's instrumented test in M7).
 *
 * The pure decision (`spontaneousEligibility` / `SpontaneousPolicy.sanitize`) and the persistence
 * chokepoint (`SettingsManager.savePersonas`, including `importBackup`) are covered on the JVM by
 * `SpontaneousGuardTest`. What only the service can show is that `AssistantService.addPersona` /
 * `updatePersona` actually APPLY the guard: the service holds the live `personas` flow the UI
 * renders from, so a persona that reached it enabled would be both visible and schedulable even
 * if storage later corrected it.
 *
 * The realistic regression these pin is the one the plan calls out: `isCloud` is DERIVED from the
 * model string by the persona editor, so a user can enable spontaneous messages on a local
 * persona and then switch its model to a cloud provider without ever revisiting the feature.
 * `updatePersona` is the only thing standing between that sequence and an enabled cloud persona.
 *
 * The second half proves the flag reaches the SCHEDULER when it is set — the gap `addPersona`
 * had: it armed from `settingsManager.getPersonas()` while `saveSettings()` had only SCHEDULED its
 * debounced write, so a brand-new spontaneous-enabled persona read back as null and was actively
 * DISARMED instead of armed. The port's `Scheduler` seam replaces WorkManager; the observable is
 * the same `isSpontaneouslyArmed` question.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SpontaneousWriteGuardTest {

    private companion object {
        const val LOCAL_SERVER = "ProbeOllamaSpontaneous"
        const val CLOUD_PROVIDER = "ProbeCloudSpontaneous"
        const val LOCAL_MODEL = "[$LOCAL_SERVER] llama3"
        const val CLOUD_MODEL = "[$CLOUD_PROVIDER] probe-model"
    }

    private val scheduler = RecordingScheduler()
    private val env = FakeAppEnv(notifier = RecordingNotifier(), scheduler = scheduler)
    private val store = InMemoryStore()
    private val settings = SettingsManager(env, store, NoImageCodec)
    private val mainDispatcher = StandardTestDispatcher()
    private val services = mutableListOf<AssistantService>()

    private lateinit var service: AssistantService

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        service = AssistantService(
            appEnv = env,
            audioPlayback = SilentAudioPlayback,
            audioDevices = NoAudioDevices,
            tts = NoTextToSpeech,
            audioCapture = NoAudioCapture,
            fileDialogs = NoFileDialogs,
            settingsManager = settings,
        ).also { services += it }

        // Register the probe server/provider, so the guard has the roles it checks against.
        service._ollamaBaseUrls.value = listOf(ServerConfig(LOCAL_SERVER, "http://192.0.2.9:11434"))
        service._customCloudApis.value = listOf(
            CloudApiSetting(
                name = CLOUD_PROVIDER, baseUrl = "https://$CLOUD_PROVIDER.test",
                apiKey = "probe", icon = "C", color = Color(0xFF4ADE80)
            )
        )
    }

    @After
    fun tearDown() {
        services.forEach { runCatching { it.shutdown() } }
        drainServiceScopes(services, mainDispatcher.scheduler)
        services.clear()
        Dispatchers.resetMain()
    }

    private fun mainSync(block: () -> Unit) {
        block()
        mainDispatcher.scheduler.runCurrent()
    }

    private fun localPersona(name: String, enabled: Boolean) = Persona(
        name = name, themeColor = Color(0xFF4ADE80), model = LOCAL_MODEL,
        systemPrompt = "You are $name.", isCloud = false, spontaneousEnabled = enabled
    )

    private fun cloudPersona(name: String, enabled: Boolean) = Persona(
        name = name, themeColor = Color(0xFF4ADE80), model = CLOUD_MODEL,
        systemPrompt = "You are $name.", isCloud = true, spontaneousEnabled = enabled
    )

    private fun stored(name: String): Persona =
        service.personas.value.firstOrNull { it.name == name }
            ?: error("persona '$name' is not in the service's list")

    private fun indexOf(name: String): Int =
        service.personas.value.indexOfFirst { it.name == name }
            .also { require(it >= 0) { "persona '$name' is not in the service's list" } }

    // ---- non-vacuity: the probe really is the routing shape each test assumes ------------

    @Test
    fun `the probe servers are registered for the roles the tests assume`() {
        val ollama = service.ollamaServerNames()
        val cloud = service.cloudProviderNames()
        assertTrue("$LOCAL_SERVER must be a configured Ollama server", LOCAL_SERVER in ollama)
        assertTrue("$CLOUD_PROVIDER must be a configured cloud provider", CLOUD_PROVIDER in cloud)
        assertTrue(
            "the probe cloud persona must really be CLOUD-eligible, or the cloud tests below " +
                "would pass for the wrong reason (blocked as gateway-only, not as cloud)",
            cloudPersona("Probe", false).roomEligibility(ollama, cloud) == RoomEligibility.Eligible(cloud = true)
        )
    }

    // ---- addPersona ---------------------------------------------------------------------

    @Test
    fun `addPersona strips spontaneousEnabled from a cloud persona`() {
        mainSync { service.addPersona(cloudPersona("ProbeCloudAdd", enabled = true)) }
        assertFalse(
            "the service must not hold an enabled cloud persona, even if a caller asks for one",
            stored("ProbeCloudAdd").spontaneousEnabled
        )
    }

    @Test
    fun `addPersona keeps spontaneousEnabled on a local persona`() {
        // The inverse control: a guard that stripped everything would pass the test above.
        mainSync { service.addPersona(localPersona("ProbeLocalAdd", enabled = true)) }
        assertTrue(stored("ProbeLocalAdd").spontaneousEnabled)
    }

    // ---- updatePersona ------------------------------------------------------------------

    @Test
    fun `updatePersona strips the flag when a local persona is switched to a cloud model`() {
        // THE realistic sequence: enable the feature on a local persona, then change its
        // model to a cloud provider. isCloud is derived from the model string, so nothing
        // else in the app would notice.
        mainSync { service.addPersona(localPersona("ProbeSwitch", enabled = true)) }
        assertTrue("precondition: the local persona is enabled", stored("ProbeSwitch").spontaneousEnabled)

        mainSync {
            service.updatePersona(
                indexOf("ProbeSwitch"),
                stored("ProbeSwitch").copy(model = CLOUD_MODEL, isCloud = true)
            )
        }
        assertFalse(
            "switching the model to a cloud provider must turn the feature off",
            stored("ProbeSwitch").spontaneousEnabled
        )
    }

    @Test
    fun `updatePersona keeps the flag while the model stays local`() {
        mainSync { service.addPersona(localPersona("ProbeStay", enabled = true)) }

        mainSync {
            service.updatePersona(
                indexOf("ProbeStay"),
                stored("ProbeStay").copy(systemPrompt = "You are ProbeStay, now chattier.")
            )
        }
        assertTrue(
            "an unrelated edit must not silently switch the feature off",
            stored("ProbeStay").spontaneousEnabled
        )
    }

    @Test
    fun `a persona whose Ollama server is not configured cannot keep the flag`() {
        mainSync {
            service.addPersona(
                localPersona("ProbeGateway", enabled = true).copy(model = "[NoSuchServer] llama3")
            )
        }
        assertFalse(
            "a gateway-only persona is history-blind and must not be enabled",
            stored("ProbeGateway").spontaneousEnabled
        )
    }

    // ---- arming, not just flagging -----------------------------------------------------

    @Test
    fun `a spontaneous-enabled persona is armed as soon as it is added`() {
        val name = "ProbeArmedOnAdd"
        mainSync { service.addPersona(armedPersona(name)) }

        assertNotPersistedYet(name)
        assertTrue(
            "addPersona must arm the persona it just created, without waiting for a settings " +
                "load — nothing else re-arms it, and the debounced save never calls this",
            waitUntilArmed(name)
        )
    }

    @Test
    fun `switching spontaneous messages on re-arms the persona immediately`() {
        // The same staleness reached updatePersona: the re-arm read the OLD, still-disabled
        // persona off disk and disarmed it.
        val name = "ProbeArmedOnToggle"
        mainSync { service.addPersona(armedPersona(name).copy(spontaneousEnabled = false)) }
        mainSync {
            service.updatePersona(indexOf(name), stored(name).copy(spontaneousEnabled = true))
        }

        assertNotPersistedYet(name)
        assertTrue(
            "turning the feature on must arm the chain immediately",
            waitUntilArmed(name)
        )
    }

    /**
     * The debounced write cannot have landed yet: `saveSettings()` sleeps `SAVE_DEBOUNCE_MS` on
     * IO, and these assertions still run inside the main-thread block that did the add. If this
     * ever starts failing, the save became synchronous — which would make the tests above pass
     * for the wrong reason (arming off a completed write).
     */
    private fun assertNotPersistedYet(name: String) {
        val storedOnDisk = runCatching {
            settings.getPersonas()?.firstOrNull { it.name == name }
        }.getOrNull()
        assertNull(
            "precondition: storage must not know about '$name' yet, or this test would prove " +
                "nothing about arming from memory",
            storedOnDisk
        )
    }

    private fun waitUntilArmed(name: String, timeoutMs: Long = 30_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            mainDispatcher.scheduler.runCurrent()
            if (isSpontaneouslyArmed(env, name)) return true
            Thread.sleep(100)
        }
        mainDispatcher.scheduler.runCurrent()
        return isSpontaneouslyArmed(env, name)
    }

    /**
     * A local, ELIGIBLE persona with the feature on. The window spans the whole day so the
     * test does not depend on what time it is — the chain is armed either way, but an
     * out-of-window clamp would make the assertion depend on the hour.
     */
    private fun armedPersona(name: String) = localPersona(name, enabled = true).copy(
        spontaneousWindowStartMin = 0,
        spontaneousWindowEndMin = 24 * 60 - 1
    )
}
