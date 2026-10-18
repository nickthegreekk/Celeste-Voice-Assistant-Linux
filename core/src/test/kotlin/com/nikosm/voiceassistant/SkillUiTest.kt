package com.nikosm.voiceassistant

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The Skills UI driven through the REAL composables against the real service (ported from
 * upstream's instrumented test in M7).
 *
 * The regression guard is the important half: `PersonaEditor` commits a WHOLE `Persona`
 * rebuilt from the snapshot it was opened with, on a 500ms debounce AND again on dispose.
 * `enabledSkillIds` was in neither copy, so any skill the user switched on was written straight
 * back to its pre-toggle value the moment they touched another field — or closed the editor.
 * This test performs exactly that sequence and reads the value back out of the service.
 *
 * Upstream's other half captured screenshots of each Skills surface; it is dropped with the
 * instrumentation host (no behavioural assertion) and recorded in docs/TEST-MAP.md.
 */
@OptIn(ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SkillUiTest {

    private val summarize = Skill(
        id = "shot-summarize",
        name = "Summarizer",
        triggerPhrases = listOf("summarize this", "tl;dr"),
        instructions = "Answer in at most three bullet points."
    )
    private val poemy = Skill(
        id = "shot-poemy",
        name = "Poemy",
        triggerPhrases = listOf("summarize this as a poem", "make this a poem"),
        instructions = "Rewrite the reply as a short rhyming poem."
    )

    /** Deliberately trigger-less and instruction-less: the "can never fire" warning surface. */
    private val draft = Skill(
        id = "shot-draft",
        name = "Unfinished Idea",
        triggerPhrases = emptyList(),
        instructions = ""
    )

    private val bob = Persona(
        name = "Bob", themeColor = Color(0xFF4ADE80), model = "[S] m",
        systemPrompt = "You are Bob.", enabledSkillIds = listOf(summarize.id)
    )
    private val cara = Persona(
        name = "Cara", themeColor = Color(0xFF60A5FA), model = "[S] m",
        systemPrompt = "You are Cara."
    )

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

    private fun mainSync(block: () -> Unit) {
        block()
        mainDispatcher.scheduler.runCurrent()
    }

    private fun installFixtures() {
        service._skills.value = listOf(summarize, poemy, draft)
        service.addPersona(bob)
        service.addPersona(cara)
        mainDispatcher.scheduler.runCurrent()
    }

    /**
     * Renders PersonaEditor over the fixtures, faithfully reproducing how PersonaSettings
     * wires it: the persona is captured ONCE and never re-read, because that is exactly what
     * `PersonaSettings` does. A harness that re-read the live `personas` flow would re-key
     * `PersonaEditor`'s `remember(persona)` state on every service write and quietly absorb
     * exactly the staleness this test exists to catch.
     */
    @Composable
    private fun PersonaEditorScreen(open: Boolean, snapshot: Persona) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                if (open) {
                    PersonaEditor(
                        persona = snapshot,
                        onSave = { updated ->
                            val index = service.personas.value.indexOfFirst { it.name == snapshot.name }
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
    fun `a skill toggle survives an edit and rename in the same session`() = runComposeUiTest {
        installFixtures()
        // Bob starts with the Summarizer and nothing else.
        mainSync { service.setSkillEnabledForPersona("Bob", poemy.id, true) }
        assertEquals(
            listOf(summarize.id, poemy.id),
            service.personas.value.first { it.name == "Bob" }.enabledSkillIds
        )

        var editorOpen by mutableStateOf(true)
        val snapshot = service.personas.value.first { it.name == "Bob" }
        setContent { PersonaEditorScreen(open = editorOpen, snapshot = snapshot) }
        waitForIdle()

        // Turn a skill on through the real UI.
        onNodeWithText("Add skill").performScrollTo().performClick()
        waitForIdle()
        onNodeWithText("Unfinished Idea").performClick()
        waitForIdle()
        onNodeWithText("Done").performClick()
        waitForIdle()

        // Now edit a DIFFERENT field in the same session — the rename. Field 0 is the
        // persona-name field.
        onAllNodes(hasSetTextAction())[0].performScrollTo().performTextClearance()
        onAllNodes(hasSetTextAction())[0].performTextInput("Bob Renamed")
        waitForIdle()

        // Let the 500ms debounce fire, then unmount the editor so the dispose flush runs too
        // — both commit paths must carry the new enabledSkillIds.
        mainClock.advanceTimeBy(1500)
        waitForIdle()
        editorOpen = false
        waitForIdle()
        mainClock.advanceTimeBy(500)
        waitForIdle()

        val after = service.personas.value.first { it.name == "Bob Renamed" }
        assertEquals(
            "the skill toggled on in this session was reverted by the editor's own commit — " +
                "the debounce or the dispose flush wrote a stale enabledSkillIds back",
            listOf(summarize.id, poemy.id, draft.id),
            after.enabledSkillIds
        )
    }
}
