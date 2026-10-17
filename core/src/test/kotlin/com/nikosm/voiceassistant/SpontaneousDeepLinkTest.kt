package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Spontaneous messages — the notification-tap deep link, proven at the SERVICE layer (ported
 * from upstream's instrumented test in M7).
 *
 * `applySpontaneousDeepLink` owns everything stateful about a tap: which persona is selected
 * (O14 — the persona, never a message), and that room mode steps aside so the promised
 * conversation is what the user lands on. It lives on the service precisely so this coverage
 * needs no Compose host.
 *
 * The port change is only the harness: a fresh service per test and Main pumped while the
 * persona swap runs.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SpontaneousDeepLinkTest {

    private companion object {
        const val PROBE = "ProbeDeepLinkPersona"
        const val OTHER = "ProbeDeepLinkOther"
        const val GHOST = "ProbeDeepLinkGhost"
    }

    private val env = FakeAppEnv(notifier = RecordingNotifier(), scheduler = RecordingScheduler())
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

        // Two eligible personas, so a switch has somewhere to go and a wrong target is
        // distinguishable from "already there".
        service.addPersona(probe(OTHER))
        service.addPersona(probe(PROBE))
        service.switchPersona(probe(OTHER))
        assertTrue("probe setup must leave OTHER active", waitFor { service.currentPersonaName == OTHER })
    }

    @After
    fun tearDown() {
        services.forEach { runCatching { it.shutdown() } }
        services.clear()
        Dispatchers.resetMain()
    }

    private fun probe(name: String) = Persona(
        name = name,
        themeColor = Color(0xFF4ADE80),
        model = "[ProbeDeepLinkOllama] llama3",
        systemPrompt = "You are $name.",
        isCloud = false,
        spontaneousEnabled = true,
        spontaneousGapMinutes = 15
    )

    private fun waitFor(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            mainDispatcher.scheduler.runCurrent()
            if (condition()) return true
            Thread.sleep(25)
        }
        mainDispatcher.scheduler.runCurrent()
        return condition()
    }

    // ---- the deep link itself ------------------------------------------------------------

    @Test
    fun `a tap selects the named persona`() {
        assertEquals("precondition: the tap must be a real switch", OTHER, service.currentPersonaName)
        val selected = service.applySpontaneousDeepLink(PROBE)

        assertEquals("the returned persona is what the UI mirrors", PROBE, selected?.name)
        assertTrue("the service must end up on the tapped persona",
            waitFor { service.currentPersonaName == PROBE })
    }

    @Test
    fun `a tap for a persona that no longer exists is consumed without switching`() {
        val selected = service.applySpontaneousDeepLink(GHOST)

        assertNull("nothing to open, so nothing is returned to mirror", selected)
        assertEquals("and nothing may move", OTHER, service.currentPersonaName)
    }

    @Test
    fun `a tap leaves room mode`() {
        // Room mode renders in place of the persona chat, so a notification tap that did not
        // leave the room would land the user on the room's transcript instead of the
        // conversation the card promised.
        service._activeRoomId.value = "probe-deep-link-room"
        assertTrue("precondition: room mode must actually be on",
            waitFor { service.activeRoomId.value != null })

        service.applySpontaneousDeepLink(PROBE)

        assertTrue("the tap must leave room mode", waitFor { service.activeRoomId.value == null })
        assertTrue("and select the tapped persona", waitFor { service.currentPersonaName == PROBE })
    }
}
