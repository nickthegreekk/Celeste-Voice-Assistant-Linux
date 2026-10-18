package com.nikosm.voiceassistant

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
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
 * Closing the editor on its FIRST frame — before the cloud/server lists it collects have
 * delivered — must not rewrite the persona's cloud identity (ported from upstream's instrumented
 * test in M7).
 *
 * The editor's `cloudApis`/`customCloudApis`/`ollamaBases` are collected INSIDE it by a
 * `LaunchedEffect`, so on the first composition they still read `emptyList()`. A builder that
 * derived `isCloud`/`providerIcon` from THOSE (empty) copies would, on a close inside that
 * window, store `isCloud=false` and the local badge for a persona whose model is a cloud
 * provider. The shared builder therefore takes its cloud inputs from the SERVICE's
 * already-loaded state (the same source `spontaneousSectionEligibility` reads).
 *
 * The frozen-clock technique travels unchanged: `autoAdvance = false` holds the first frame so
 * no `LaunchedEffect` collector has delivered, while the service's own lists are already loaded.
 * The editor is then unmounted — its dispose flush is the only commit — and the stored persona is
 * read back.
 */
@OptIn(ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PersonaEditorEmptyListsTest {

    private companion object {
        const val CLOUD_PROVIDER = "ProbeCloudEmptyLists"
        const val CLOUD_MODEL = "[$CLOUD_PROVIDER] probe-model"
        const val PERSONA = "ProbeCloudPersona"
    }

    private val env = FakeAppEnv(notifier = RecordingNotifier())
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
    }

    @After
    fun tearDown() {
        services.forEach { runCatching { it.shutdown() } }
        drainServiceScopes(services, mainDispatcher.scheduler)
        services.clear()
        Dispatchers.resetMain()
    }

    private fun installFixtures() {
        // The provider is configured in the SERVICE — exactly the state the editor's collected
        // copies will not have delivered on the first frame.
        service._customCloudApis.value = listOf(
            CloudApiSetting(
                name = CLOUD_PROVIDER, baseUrl = "https://$CLOUD_PROVIDER.test",
                apiKey = "probe", icon = "C", color = Color(0xFF4ADE80)
            )
        )
        // The persona already carries the identity the loaded provider yields, so
        // "unchanged" is the same thing as "correct".
        service.addPersona(
            Persona(
                name = PERSONA,
                themeColor = Color(0xFF4ADE80),
                model = CLOUD_MODEL,
                systemPrompt = "You are $PERSONA.",
                isCloud = true,
                providerIcon = "C",
                spontaneousEnabled = false
            )
        )
    }

    /** The persona is captured OUTSIDE composition (no StateFlow read in the composable). */
    @Composable
    private fun Editor(snapshot: Persona, open: Boolean) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                if (open) {
                    PersonaEditor(
                        persona = snapshot,
                        onSave = { updated ->
                            val index = service.personas.value.indexOfFirst { it.name == PERSONA }
                            if (index != -1) service.updatePersona(index, updated)
                        },
                        onCancel = {},
                        service = service
                    )
                }
            }
        }
    }

    @Test
    fun `closing on the first frame leaves the cloud identity unchanged`() = runComposeUiTest {
        installFixtures()
        val opened = service.personas.value.first { it.name == PERSONA }
        assertTrue("fixture must be a cloud persona", opened.isCloud)
        assertEquals("fixture must carry the cloud badge", "C", opened.providerIcon)
        assertFalse("fixture must not hold a cloud-inedible opt-in", opened.spontaneousEnabled)

        var open by mutableStateOf(true)
        // Hold the first frame: no LaunchedEffect collector — including the three that populate
        // this editor's `cloudApis`/`customCloudApis`/`ollamaBases` — has delivered, so those
        // local snapshots still read emptyList().
        mainClock.autoAdvance = false
        setContent { Editor(opened, open) }
        try {
            waitForIdle()
            // Close IMMEDIATELY, still frozen, then advance exactly ONE frame so the unmount —
            // and therefore the dispose flush — runs while the collected lists are still empty.
            open = false
            mainClock.advanceTimeByFrame()
            waitForIdle()
        } finally {
            mainClock.autoAdvance = true
        }
        waitForIdle()

        // Proof the unmount (and its flush) actually ran: the editor's model field is gone.
        onNodeWithTag("personaModelField").assertDoesNotExist()

        val stored = service.personas.value.firstOrNull { it.name == PERSONA }
        assertTrue("the persona must still be in the service's stored list; stored=$stored", stored != null)
        assertTrue(
            "isCloud must be UNCHANGED — the builder must read the SERVICE's loaded providers, " +
                "not the editor's not-yet-delivered empty copies; stored=$stored",
            stored!!.isCloud
        )
        assertEquals("providerIcon must be UNCHANGED; stored=$stored", "C", stored.providerIcon)
        assertFalse("spontaneousEnabled must be UNCHANGED; stored=$stored", stored.spontaneousEnabled)
    }
}
