package com.nikosm.voiceassistant

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import java.nio.file.Files
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
 * What the Add/Edit dialog probes AFTER it saves (ported from upstream's instrumented test in
 * M7).
 *
 * This is the one place the probe endpoint is chosen by a CALL SITE rather than by the entry, so it
 * is the one place a test has to drive the real dialog: `imageGenProbePath` was already correct
 * while the save path passed `isImageGen = false`, which sent every image-gen save to the
 * Ollama/gateway health URL (`{base}/api/tags`). An image-gen server answers that with a 404 — one
 * of the false "this address doesn't seem right" snackbars, and a plausible source of the reported
 * 405. Both backends are asserted so neither can regress alone.
 *
 * The port change is the runner: upstream's `createComposeRule` becomes Compose Desktop's
 * `runComposeUiTest`, and the service is built over an in-memory store with a test Main dispatcher
 * that the wait loop pumps alongside the composition.
 */
@OptIn(ExperimentalTestApi::class)
class ImageGenSaveProbeTest {

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
        services.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `saving an A1111 entry probes only the model list`() = runComposeUiTest {
        ImageGenStub().use { stub ->
            addThroughTheDialog(stub.url, ImageGenBackend.A1111)

            assertTrue(
                "the post-save probe never reached $IMAGE_GEN_SD_MODELS_PATH",
                awaitProbe { stub.requests.any { it.path == IMAGE_GEN_SD_MODELS_PATH } }
            )
            val seen = stub.requests.map { "${it.method} ${it.path}" }
            assertFalse(
                "an image-gen entry must never be probed as an Ollama/gateway server — that is the " +
                    "bug this test exists for; saw: $seen",
                stub.requests.any { it.path.startsWith("/api/") }
            )
            val probe = stub.requests.first { it.path == IMAGE_GEN_SD_MODELS_PATH }
            assertEquals("the probe must be a GET", "GET", probe.method)
            assertEquals(
                "the probe must carry the saved entry's credentials",
                stub.basicHeader,
                probe.headers["authorization"]
            )
        }
    }

    @Test
    fun `saving a comfyui entry probes only system stats and stores its type`() = runComposeUiTest {
        ComfyUiStub().use { stub ->
            addThroughTheDialog(stub.url, ImageGenBackend.COMFYUI)

            assertTrue(
                "the post-save probe never reached $COMFY_SYSTEM_STATS_PATH",
                awaitProbe { stub.requests.any { it.path == COMFY_SYSTEM_STATS_PATH } }
            )
            val seen = stub.requests.map { "${it.method} ${it.path}" }
            assertFalse(
                "a ComfyUI entry must not be probed at the A1111 endpoint; saw: $seen",
                stub.requests.any { it.path.startsWith("/sdapi/") }
            )
            assertFalse(
                "nor as an Ollama/gateway server; saw: $seen",
                stub.requests.any { it.path.startsWith("/api/") }
            )
            assertEquals("GET", stub.requests.first { it.path == COMFY_SYSTEM_STATS_PATH }.method)
            assertTrue(
                "the entry must have been stored as ComfyUI, not reset to the default",
                service._imageGenBases.value.any { it.backendType == ImageGenBackend.COMFYUI }
            )
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Adds an entry the way a user does: tap + IN THE SECTION for [backend], type the name and URL,
     * choose Basic and its credentials, tap Add. The section owns the type now, so there is no chip
     * to click — and the "+" that is clicked is the one whose section it is.
     *
     * Credentials are always supplied so the probe is answered 200 — a 401 would be a different
     * test, and would leave a snackbar suspended.
     */
    private fun ComposeUiTest.addThroughTheDialog(url: String, backend: ImageGenBackend) {
        Files.createDirectories(env.cacheDir)
        setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    ServerListSection(
                        title = "${backend.displayName} servers",
                        servers = emptyList(),
                        status = emptyMap(),
                        onAdd = { name, entryUrl, user, pass, authType, apiKey, backendType, workflow, freeMemory ->
                            service.addImageGenBase(name, entryUrl, user, pass, authType, apiKey, backendType, workflow, freeMemory)
                        },
                        onRemove = { },
                        onEdit = { _, _, _, _, _, _, _, _, _, _ -> },
                        onRefreshHealth = { },
                        onRefreshModels = { },
                        onMoveUp = { },
                        onMoveDown = { },
                        isGateway = false,
                        isImageGen = true,
                        imageGenBackend = backend,
                        service = service,
                        snackbarHostState = remember { SnackbarHostState() },
                        currentThemeColor = Color(0xFF4ADE80)
                    )
                }
            }
        }
        waitForIdle()

        onNodeWithContentDescription("Add ${backend.displayName} servers").performClick()
        waitForIdle()

        // Field 0 is Name and 1 is URL. Selecting an auth type reveals Username/Password as fields
        // 2 and 3.
        onAllNodes(hasSetTextAction())[0].performTextInput("ProbeSave")
        onAllNodes(hasSetTextAction())[1].performTextInput(url)
        onNodeWithText("Basic").performClick()
        waitForIdle()
        onAllNodes(hasSetTextAction())[2].performTextInput(COMFY_PROBE_USER)
        onAllNodes(hasSetTextAction())[3].performTextInput(COMFY_PROBE_PASSWORD)
        onNodeWithText("Add").performClick()
    }

    /** Pumps both the composition and the service's Main while the post-save probe runs on IO. */
    private fun ComposeUiTest.awaitProbe(timeoutMs: Long = 20_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            mainDispatcher.scheduler.runCurrent()
            if (condition()) return true
            runCatching { waitForIdle() }
            Thread.sleep(25)
        }
        mainDispatcher.scheduler.runCurrent()
        return condition()
    }
}
