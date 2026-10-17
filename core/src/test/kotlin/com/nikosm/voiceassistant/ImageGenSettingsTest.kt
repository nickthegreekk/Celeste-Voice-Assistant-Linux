package com.nikosm.voiceassistant

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The Image Gen screen's two-section layout and its switch, composed on its own through
 * `ImageGenSettings` (ported from upstream's instrumented test in M7).
 *
 * Upstream's second case pushed the composition through a `StateRestorationTester` to emulate a
 * rotation, which has no desktop counterpart (a desktop window is not recreated); it is dropped
 * and recorded in docs/TEST-MAP.md. What survives is the property that case was mostly about
 * anyway: the active choice lives on the SERVICE, so it cannot be lost by recomposition — and the
 * switch reaching the persisted setting is asserted here directly.
 */
@OptIn(ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ImageGenSettingsTest {

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
        services.clear()
        Dispatchers.resetMain()
    }

    /** Two A1111 entries and one ComfyUI entry, with distinct names so each row is findable. */
    private fun entries() = listOf(
        ServerConfig("Local A1111", "http://192.0.2.50:7860"),
        ServerConfig("Backup A1111", "http://192.0.2.51:7860"),
        ServerConfig("Attic Comfy", "http://192.0.2.9:8188", backendType = ImageGenBackend.COMFYUI)
    )

    @Test
    fun `each section lists its own entries and the hint follows the switch`() = runComposeUiTest {
        // Explicit: the active backend is service state another test may have moved, and this
        // test asserts which entry the hint names.
        service.setActiveImageGenBackend(ImageGenBackend.A1111)
        mainDispatcher.scheduler.runCurrent()

        setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ImageGenSettings(
                        service = service,
                        imageGen = entries(),
                        status = emptyMap(),
                        currentThemeColor = Color(0xFF4ADE80),
                        snackbarHostState = remember { SnackbarHostState() }
                    )
                }
            }
        }
        waitForIdle()

        // Every entry is rendered, under the section for its own type, and each section has its OWN
        // "+" — named after the section — so an entry's type can never be picked wrong.
        onNodeWithText("Local A1111").assertIsDisplayed()
        onNodeWithText("Backup A1111").assertIsDisplayed()
        onNodeWithText("Attic Comfy").assertIsDisplayed()
        onNodeWithContentDescription("Add ${ImageGenBackend.A1111.displayName} servers").assertExists()
        onNodeWithContentDescription("Add ${ImageGenBackend.COMFYUI.displayName} servers").assertExists()

        // The hint names the FIRST entry of the active type — the one a request will actually use.
        onNode(hasText("Image requests use “Local A1111”", substring = true)).assertExists()

        onNodeWithText(ImageGenBackend.COMFYUI.displayName).performClick()
        waitForIdle()

        onNode(hasText("Image requests use “Attic Comfy”", substring = true)).assertExists()
        assertEquals(
            "the switch must reach the persisted setting, not just the UI",
            ImageGenBackend.COMFYUI,
            service.activeImageGenBackend.value
        )
    }
}
