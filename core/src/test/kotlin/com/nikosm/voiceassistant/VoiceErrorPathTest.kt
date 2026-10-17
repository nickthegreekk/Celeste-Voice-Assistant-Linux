package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * M7's error paths, starting with the one a user meets first: **the gateway is down when they
 * speak.**
 *
 * The requirement is not a particular sentence — it is that a failed transcription produces an
 * error bubble in the transcript and leaves the application alive, rather than a silent turn, a
 * spinner that never ends, or a crash on an IO thread. The URL points at a closed loopback port,
 * so the refusal is immediate and no test depends on a real service.
 *
 * The other M7 paths are recorded rather than faked here: "no microphone" lives in the VAD loop's
 * capture-open failure (which needs the audio-device fakes extended), "ComfyUI restarted mid-job"
 * needs a scriptable history endpoint, and "no Secret Service" is moot — seam 8 is not in v1
 * (PLAN.md §5a), so credentials are the 0600 settings file and there is no daemon to be missing.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VoiceErrorPathTest {

    private val env = FakeAppEnv(notifier = RecordingNotifier())
    private val settings = SettingsManager(env, InMemoryStore(), NoImageCodec)
    private val services = mutableListOf<AssistantService>()

    private val gatewayUrl = "http://127.0.0.1:1"

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        settings.saveServerBases(listOf(ServerConfig(name = "GW", url = gatewayUrl)))
        settings.savePersonas(
            listOf(
                Persona(
                    name = "Echo",
                    themeColor = Color(0xFF4ADE80),
                    // No bracket prefix: the gateway route, which transcribes before the model.
                    model = "bare-model",
                    systemPrompt = "You are a warm assistant.",
                    backendUrl = gatewayUrl,
                )
            )
        )
    }

    @After
    fun tearDown() {
        services.forEach { runCatching { it.shutdown() } }
        services.clear()
        Dispatchers.resetMain()
    }

    private fun newService(): AssistantService = AssistantService(
        appEnv = env,
        audioPlayback = SilentAudioPlayback,
        audioDevices = NoAudioDevices,
        tts = NoTextToSpeech,
        audioCapture = NoAudioCapture,
        fileDialogs = NoFileDialogs,
        settingsManager = settings,
    ).also { services += it }

    private fun TestScope.settle(service: AssistantService, what: String, ready: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            runCurrent()
            if (ready()) return
            Thread.sleep(5)
        }
        runCurrent()
        throw AssertionError(
            "$what did not happen within ${TIMEOUT_MS}ms. transcript=" +
                service._messages.value.joinToString(",") { "${it.role}:${it.text.take(60)}" }
        )
    }

    @Test
    fun `a gateway that is down yields an error bubble, not a silent turn or a crash`() = runTest {
        val service = newService()
        service.start()
        runCurrent()
        service.awaitPersonaHistoryIo()
        val audio = Files.createTempFile("utterance", ".wav").also { Files.write(it, ByteArray(2048)) }

        service.sendAudioToServer(audio, service.personas.value.first())
        settle(service, "the failure to surface") {
            service._messages.value.lastOrNull()?.let { it.role == "assistant" && it.isError } == true
        }

        val bubble = service._messages.value.last()
        assertTrue("the bubble explains itself: ${bubble.text}", bubble.text.startsWith("Error:"))
        // The exact sentence depends on which layer saw the refusal first (the transcription call
        // itself or the turn's own null check); the requirement is that the user is told it is a
        // connection problem rather than handed a silent turn.
        assertTrue(
            "and names the connection, which is what the user has to fix: ${bubble.text}",
            bubble.text.contains("Failed to connect", ignoreCase = true) ||
                bubble.text.contains("Could not transcribe audio", ignoreCase = true) ||
                bubble.text.contains("Check Gateway connection", ignoreCase = true),
        )
        Files.deleteIfExists(audio)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
