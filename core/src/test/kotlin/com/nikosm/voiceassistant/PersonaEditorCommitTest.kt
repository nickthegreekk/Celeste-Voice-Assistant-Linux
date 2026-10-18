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
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The persona editor's COMMIT, driven through the real composable against the real service
 * (ported from upstream's instrumented test in M7).
 *
 * The debounced auto-save and the dispose-time flush used to build the Persona separately: the
 * debounce derived `isCloud`/`providerIcon` from the model, the flush preserved the values of the
 * persona as it was OPENED. So switching a persona from a local model to a cloud provider and
 * closing the editor inside the 500ms debounce window stored the STALE pair. Both paths now go
 * through `Persona.withEditorFields`, so this can no longer happen.
 *
 * The test does exactly that sequence: type a cloud model, close IMMEDIATELY, then read the
 * STORED persona back. The port change is the runner — Compose Desktop's `runComposeUiTest` and
 * an in-memory store — so the assertion is still made against what a relaunch would read.
 */
@OptIn(ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PersonaEditorCommitTest {

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

    /** Reads the persona from DISK, which is where the commit (debounce or flush) lands. */
    private fun storedPersona(name: String): Persona? =
        settings.getPersonas().orEmpty().firstOrNull { it.name == name }

    private fun ComposeUiTest.awaitStoredPersona(
        name: String,
        predicate: (Persona) -> Boolean
    ): Persona? {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            mainDispatcher.scheduler.runCurrent()
            val p = storedPersona(name)
            if (p != null && predicate(p)) return p
            runCatching { waitForIdle() }
            Thread.sleep(25)
        }
        mainDispatcher.scheduler.runCurrent()
        return storedPersona(name)
    }

    /** Renders the real editor the way PersonaSettings does, with [open] driven from outside. */
    @Composable
    private fun Editor(snapshot: Persona, open: Boolean) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
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
    fun `closing within the debounce window still commits the cloud identity`() = runComposeUiTest {
        // A cloud provider the editor resolves against, added BEFORE it mounts so the editor's
        // collectors have delivered by the first settled frame.
        service.addCustomCloudApi("ProbeCloud", "http://127.0.0.1:1/probe-cloud", "k")
        service.addPersona(
            Persona(
                name = "CommitProbe",
                themeColor = Color(0xFF4ADE80),
                model = "[HomeBox] llama3",
                systemPrompt = "You are CommitProbe.",
                isCloud = false,
                providerIcon = "O"
            )
        )
        val snapshot = service.personas.value.first { it.name == "CommitProbe" }

        var open by mutableStateOf(true)
        setContent { Editor(snapshot, open) }
        waitForIdle()

        // Switch the model local → cloud through the REAL model field.
        onNodeWithTag("personaModelField").performScrollTo().performTextClearance()
        onNodeWithTag("personaModelField").performTextInput("[ProbeCloud] some-model")
        waitForIdle()

        // Close IMMEDIATELY: the 500ms debounce has not fired, so only the dispose flush commits.
        open = false
        waitForIdle()

        val stored = awaitStoredPersona("CommitProbe") { it.model == "[ProbeCloud] some-model" }
        assertTrue("the model edit must reach disk; stored=$stored", stored != null)
        assertTrue(
            "isCloud must be DERIVED from the cloud model, not the opened persona's false; stored=$stored",
            stored!!.isCloud
        )
        assertEquals(
            "providerIcon must be the derived cloud badge, not the opened persona's local 'O'; stored=$stored",
            "C",
            stored.providerIcon
        )
    }
}
