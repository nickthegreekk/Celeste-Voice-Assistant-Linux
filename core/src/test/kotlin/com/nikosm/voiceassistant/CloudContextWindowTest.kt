package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The cloud context budget (2026-10-18).
 *
 * The live finding: a DeepSeek turn of ~9,787 fixed tokens was refused because the seeded
 * `Persona.numCtx` is 8,192, while the provider accepts 64k+. A cloud persona that never had its
 * window chosen now uses a provider floor; any value the user set is honoured, including an
 * explicit 8192, which is why the service records per-persona changes to that value.
 */
class CloudContextWindowTest {

    private val env = FakeAppEnv()
    private val store = InMemoryStore()
    private val settings = SettingsManager(env, store, NoImageCodec)
    private val dispatcher = StandardTestDispatcher()
    private val services = mutableListOf<AssistantService>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        services.forEach { runCatching { it.shutdown() } }
        drainServiceScopes(services, dispatcher.scheduler)
        services.clear()
        Dispatchers.resetMain()
    }

    private fun service(): AssistantService = AssistantService(
        appEnv = env,
        audioPlayback = SilentAudioPlayback,
        audioDevices = NoAudioDevices,
        tts = NoTextToSpeech,
        audioCapture = NoAudioCapture,
        fileDialogs = NoFileDialogs,
        settingsManager = settings,
    ).also { services += it }

    private fun persona(name: String = "CloudProbe", numCtx: Int = DEFAULT_PERSONA_CONTEXT_WINDOW) = Persona(
        name = name,
        themeColor = Color(0xFF4ADE80),
        model = "[ProbeCloud] model",
        systemPrompt = "You are $name.",
        isCloud = true,
        numCtx = numCtx,
    )

    @Test
    fun `the untouched seeded default is lifted to the provider floor`() {
        assertEquals(CLOUD_CONTEXT_WINDOW_FLOOR, cloudContextWindow(8192, explicitlySet = false))
        assertEquals(65536, cloudContextWindow(65536, explicitlySet = false))
    }

    @Test
    fun `a value the user set is honoured, even at the default`() {
        assertEquals(16384, cloudContextWindow(16384, explicitlySet = false))
        assertEquals(4096, cloudContextWindow(4096, explicitlySet = false))
        assertEquals(8192, cloudContextWindow(8192, explicitlySet = true))
    }

    @Test
    fun `a new cloud persona on the default is not marked as chosen`() {
        val service = service()
        service.addPersona(persona())

        assertFalse(settings.isPersonaContextWindowExplicit("CloudProbe"))
    }

    @Test
    fun `a new persona with a non-default window is marked`() {
        val service = service()
        service.addPersona(persona(numCtx = 65536))

        assertTrue(settings.isPersonaContextWindowExplicit("CloudProbe"))
    }

    @Test
    fun `moving the window marks the choice, and moving it back to 8192 keeps it`() {
        val service = service()
        service.addPersona(persona())
        val index = service.personas.value.indexOfFirst { it.name == "CloudProbe" }

        service.updatePersona(index, persona(numCtx = 16384))
        assertTrue(settings.isPersonaContextWindowExplicit("CloudProbe"))

        service.updatePersona(index, persona(numCtx = DEFAULT_PERSONA_CONTEXT_WINDOW))
        assertTrue(
            "an 8192 the user chose is theirs; the floor must not override it",
            settings.isPersonaContextWindowExplicit("CloudProbe"),
        )
    }

    @Test
    fun `the mark follows a rename and dies with the persona`() {
        settings.markPersonaContextWindowExplicit("Old")
        settings.migratePersonaHistory("Old", "New")

        assertFalse(settings.isPersonaContextWindowExplicit("Old"))
        assertTrue(settings.isPersonaContextWindowExplicit("New"))

        settings.deletePersonaHistory("New")
        assertFalse(settings.isPersonaContextWindowExplicit("New"))
    }
}
