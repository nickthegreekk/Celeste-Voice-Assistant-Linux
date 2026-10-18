package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Suggested-replies routing for the exact `Assistant (Voice)` failure: a direct-Ollama persona
 * (`[Name] model`, cloud = false, GATEWAY voice for TTS) whose Ollama entry requires basic auth
 * (ported from upstream's instrumented test in M7).
 *
 * The bug: the gateway branch was chosen on `ollamaBase.contains(":8880") ||
 * voiceMode == GATEWAY`, so a voice persona whose tag DID match an Ollama entry still fell into
 * the gateway branch — which then picked `_serverBases.firstOrNull()` with NO auth header and
 * 401'd before any generation. This test proves the routing and the credentials together: the
 * stub 401s anything without the entry's Basic header, so a request that reaches it at all and
 * comes back with suggestions was built with the credentials.
 *
 * The port change is only the harness: a fresh service over an in-memory store per test, and
 * `java.util.Base64` in place of Android's.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SuggestedRepliesDirectOllamaAuthTest {
    private val mainDispatcher = StandardTestDispatcher()


    private val env = FakeAppEnv(notifier = RecordingNotifier())
    private val store = InMemoryStore()
    private val settings = SettingsManager(env, store, NoImageCodec)
    private val services = mutableListOf<AssistantService>()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        services.forEach { runCatching { it.shutdown() } }
        drainServiceScopes(services, mainDispatcher.scheduler)
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

    @Test
    fun `direct ollama persona with basic auth reaches api chat with credentials`() {
        OllamaSuggestionStub().use { stub ->
            val service = newService()
            val ollamaEntry = ServerConfig(
                name = PROBE_OLLAMA,
                url = stub.url,
                username = PROBE_USER,
                password = PROBE_PASSWORD,
                authType = AuthType.BASIC
            )
            service._ollamaBaseUrls.value = listOf(ollamaEntry)
            service.currentPersonaName = PROBE_PERSONA
            service.messagesOwnerName = PROBE_PERSONA
            service._messages.value = listOf(
                ChatMessage("user", "What is the capital of France?"),
                ChatMessage("assistant", "Paris is the capital of France. Want a quick quiz on it?")
            )
            val persona = Persona(
                name = PROBE_PERSONA,
                themeColor = PROBE_COLOR,
                model = "[$PROBE_OLLAMA] ornith-1.5:9b",
                systemPrompt = "Routing probe persona.",
                isCloud = false,
                backendUrl = "",
                allowGatewayFailover = false,
                voiceMode = VoiceMode.GATEWAY,
                enableThinking = false,
                webSearchEnabled = false,
                ragEnabled = false,
                maxTokens = 256,
                numCtx = 8192
            )

            val suggestions = runBlocking { service.fetchSuggestedReplies(persona) }

            val chatHits = stub.requests.filter { it.path == "/api/chat" || it.path.startsWith("/api/chat?") }
            assertTrue(
                "no /api/chat request reached the stub (old code routes this persona to the gateway, " +
                    "which 401s before any generation)",
                chatHits.isNotEmpty()
            )
            assertEquals(
                "the stub must see the Ollama entry's basic-auth credentials",
                basicHeader(),
                chatHits.first().headers["authorization"]
            )
            assertEquals(
                listOf("Yes, quiz me on Paris", "What else is nearby?", "Thanks, that's clear"),
                suggestions
            )
        }
    }

    private fun basicHeader(): String =
        "Basic " + Base64.getEncoder()
            .encodeToString("$PROBE_USER:$PROBE_PASSWORD".toByteArray(Charsets.UTF_8))

    private companion object {
        const val PROBE_PERSONA = "SuggestedRepliesDirectOllamaProbePersona"
        const val PROBE_OLLAMA = "SuggestedRepliesProbeOllama"
        const val PROBE_USER = "probe-user"
        const val PROBE_PASSWORD = "probe-pass"
        val PROBE_COLOR = Color(0xFF4ADE80)
    }
}
