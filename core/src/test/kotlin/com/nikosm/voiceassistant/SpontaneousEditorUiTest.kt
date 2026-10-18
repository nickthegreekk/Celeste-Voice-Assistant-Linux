package com.nikosm.voiceassistant

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Spontaneous messages — the editor's RENDER GATE, proven through the real editor (ported from
 * upstream's instrumented test in M7).
 *
 * What this pins is the part no JVM test can reach: the gate reads the SERVICE's live server
 * lists, not the editor's asynchronously-collected copies, so it is correct on the FIRST
 * composition — before the `LaunchedEffect` collectors have delivered and the local snapshots
 * still read `emptyList()`.
 *
 * Why that moment matters: feeding an empty Ollama set to `spontaneousEligibility` makes a
 * perfectly local persona resolve as gateway-only. A gate that read the collected copies would
 * hide the section on the first frame (and, worse, the editor's commit would compute the same
 * verdict and strip a valid opt-in on the first auto-save).
 *
 * The frozen-clock technique travels unchanged: `mainClock.autoAdvance = false` holds the first
 * frame so NO `LaunchedEffect` collector has delivered yet, while the service's own lists are
 * already populated. The port change is the runner (Compose Desktop's `runComposeUiTest`).
 */
@OptIn(ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SpontaneousEditorUiTest {

    private companion object {
        const val LOCAL_SERVER = "ProbeOllamaSpontaneousUI"
        const val CLOUD_PROVIDER = "ProbeCloudSpontaneousUI"
        const val LOCAL_MODEL = "[$LOCAL_SERVER] llama3"
        const val CLOUD_MODEL = "[$CLOUD_PROVIDER] probe-model"
        const val EDITOR_TAG = "spontaneousEditorProbeRoot"

        /** As `SettingsSectionHeader` renders it — `title.uppercase()`. */
        const val SECTION_HEADING = "SPONTANEOUS MESSAGES"

        /** The gate's own label — present ONLY when the section is offered at all. */
        const val TOGGLE_LABEL = "Let this persona message you on its own"
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
    }

    @After
    fun tearDown() {
        services.forEach { runCatching { it.shutdown() } }
        drainServiceScopes(services, mainDispatcher.scheduler)
        services.clear()
        Dispatchers.resetMain()
    }

    private fun localPersona(name: String, enabled: Boolean) = Persona(
        name = name, themeColor = Color(0xFF4ADE80), model = LOCAL_MODEL,
        systemPrompt = "You are $name.", isCloud = false, spontaneousEnabled = enabled
    )

    private fun cloudPersona(name: String, enabled: Boolean) = Persona(
        name = name, themeColor = Color(0xFF4ADE80), model = CLOUD_MODEL,
        systemPrompt = "You are $name.", isCloud = true, spontaneousEnabled = enabled
    )

    /**
     * Opens the REAL editor for a persona already in the service's list, exactly as
     * `PersonaSettings` does — so `remember(persona)` states, the debounce, and the dispose
     * flush all run for real.
     */
    @Composable
    private fun PersonaEditorScreen(personaName: String) {
        val live = service.personas.value.firstOrNull { it.name == personaName } ?: return
        Surface(modifier = Modifier.fillMaxSize().testTag(EDITOR_TAG)) {
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                PersonaEditor(
                    persona = live,
                    onSave = { updated ->
                        val index = service.personas.value.indexOfFirst { it.name == live.name }
                        if (index != -1) service.updatePersona(index, updated)
                    },
                    onCancel = {},
                    service = service
                )
            }
        }
    }

    private fun installFixtures() {
        service._ollamaBaseUrls.value = listOf(ServerConfig(LOCAL_SERVER, "http://192.0.2.9:11434"))
        service._customCloudApis.value = listOf(
            CloudApiSetting(
                name = CLOUD_PROVIDER, baseUrl = "https://$CLOUD_PROVIDER.test",
                apiKey = "probe", icon = "C", color = Color(0xFF4ADE80)
            )
        )
        service.addPersona(localPersona("ProbeLocalGate", enabled = false))
        service.addPersona(cloudPersona("ProbeCloudGate", enabled = false))
        mainDispatcher.scheduler.runCurrent()
    }

    // ------------------------------------------------------------------ the gate

    /**
     * Brings the node itself into the editor's viewport, then asserts it. The editor is one
     * long scrollable Column and this section sits below the prompt, Skills and voice — so a
     * node can exist in the tree yet fail `assertIsDisplayed` on viewport bounds.
     */
    private fun androidx.compose.ui.test.ComposeUiTest.scrollAndAssert(
        node: androidx.compose.ui.test.SemanticsNodeInteraction
    ): androidx.compose.ui.test.SemanticsNodeInteraction {
        node.performScrollTo()
        waitForIdle()
        return node.assertIsDisplayed()
    }

    @Test
    fun `a local persona is shown the controls`() = runComposeUiTest {
        installFixtures()
        setContent { PersonaEditorScreen("ProbeLocalGate") }
        waitForIdle()
        scrollAndAssert(onNodeWithText(SECTION_HEADING))
        scrollAndAssert(onNodeWithText(TOGGLE_LABEL))
        scrollAndAssert(onNodeWithTag("spontaneousMasterSwitch"))
        scrollAndAssert(onNodeWithTag("spontaneousSendNow"))
    }

    @Test
    fun `cloud persona is told why rather than shown the controls`() = runComposeUiTest {
        installFixtures()
        setContent { PersonaEditorScreen("ProbeCloudGate") }
        waitForIdle()
        scrollAndAssert(onNodeWithText(SECTION_HEADING))
        // The controls are absent entirely — the gate renders the refusal in their place.
        onNodeWithText(TOGGLE_LABEL).assertDoesNotExist()
        onNodeWithTag("spontaneousMasterSwitch").assertDoesNotExist()
        onNodeWithTag("spontaneousSendNow").assertDoesNotExist()
        scrollAndAssert(onNodeWithText(SpontaneousPolicy.CLOUD_BLOCK_REASON))
    }

    // ------------------------------------------------------------------ the first frame

    @Test
    fun `gate is correct on the first frame before collectors deliver`() = runComposeUiTest {
        installFixtures()
        // HOLD the first frame: no LaunchedEffect collector — including the three that
        // populate this editor's server lists — has delivered yet, so those local snapshots
        // still read `emptyList()`. The service itself is already populated.
        mainClock.autoAdvance = false
        setContent { PersonaEditorScreen("ProbeLocalGate") }
        waitForIdle()
        try {
            // What is asserted is PRESENCE, not display: a gate that read the
            // not-yet-delivered copies would decide Blocked (gateway-only) at this instant
            // and render the refusal instead — so the controls' node would not be in the
            // tree AT ALL. Display is a scroll question, deliberately not coupled to this.
            assertTrue(LOCAL_SERVER in service.ollamaServerNames())
            onNodeWithText(SECTION_HEADING).assertExists()
            onNodeWithText(TOGGLE_LABEL).assertExists()
            onNodeWithTag("spontaneousMasterSwitch").assertExists()
        } finally {
            mainClock.autoAdvance = true
        }
    }

    // ------------------------------------------------------------------ the toggle persists

    @Test
    fun `master switch flip reaches the service through the debounce`() = runComposeUiTest {
        installFixtures()
        setContent { PersonaEditorScreen("ProbeLocalGate") }
        waitForIdle()
        assertFalse(service.personas.value.first { it.name == "ProbeLocalGate" }.spontaneousEnabled)

        // Scroll the SWITCH itself into view first: a click on an off-screen node lands at
        // coordinates outside the visible window, so it silently hits whatever is there
        // instead — no exception, no state change, and a test that reports a false negative.
        val toggle = onNodeWithTag("spontaneousMasterSwitch")
        toggle.performScrollTo()
        waitForIdle()
        toggle.performClick()
        // The debounce must fire on the compose clock — Thread.sleep would not advance it.
        mainClock.advanceTimeBy(1500)
        waitForIdle()

        assertTrue(
            "the toggle must survive the debounce with a live gate — a commit that re-decided " +
                "from not-yet-delivered copies would write `false` straight back over the flip",
            service.personas.value.first { it.name == "ProbeLocalGate" }.spontaneousEnabled
        )
    }
}
