package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Ollama reads `think` from the request, not from `options`, and the VALUE must follow the
 * model's actual capability.
 *
 * Measured on Ollama 0.40: `options.think=false` is ignored. A thinking-capable model such as
 * `ornith-1.5:9b` then stays in its default thinking mode, where hidden reasoning consumes
 * `num_predict` — with a 4096-token budget a normal turn can end with an empty `message.content`,
 * which the app stores as a blank assistant bubble.
 *
 * The first fix moved the flag to the top level but kept a DeepSeek-only name list, so Ornith
 * received an explicit `think: false` and stopped thinking — the regression these tests now
 * cover. The capability is decided by [AssistantService.isThinkingModel]: a known name, or the
 * live Ollama catalog's `thinking` capability, still gated by the persona's own toggle.
 *
 * The stub captures the exact request body, so this pins the wire shape rather than the code
 * path: `think` must be a top-level field, and `options` must not carry it at all.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ThinkingFlagPlacementTest {
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
    fun `think is top-level and options does not carry it`() = runTest {
        assertRequestFlag(model = "llama3", thinking = false, expectedTopLevel = false)
    }

    @Test
    fun `a thinking persona sends think true at the top level`() = runTest {
        assertRequestFlag(model = "deepseek-r1:8b", thinking = true, expectedTopLevel = true)
    }

    @Test
    fun `ornith is a thinking model by name`() = runTest {
        assertRequestFlag(model = "ornith-1.5:9b", thinking = true, expectedTopLevel = true)
    }

    @Test
    fun `a catalog thinking capability enables think for an unknown name`() = runTest {
        assertRequestFlag(
            model = "custom-thinker:7b",
            thinking = true,
            expectedTopLevel = true,
            catalogCapabilities = setOf(ModelCapability.COMPLETION, ModelCapability.THINKING),
        )
    }

    @Test
    fun `the persona toggle still wins over a catalog capability`() = runTest {
        assertRequestFlag(
            model = "custom-thinker:7b",
            thinking = false,
            expectedTopLevel = false,
            catalogCapabilities = setOf(ModelCapability.COMPLETION, ModelCapability.THINKING),
        )
    }

    private fun TestScope.assertRequestFlag(
        model: String,
        thinking: Boolean,
        expectedTopLevel: Boolean,
        catalogCapabilities: Set<ModelCapability>? = null,
    ) {
        SteppedNdjsonServer().use { server ->
            val service = newService()
            service._ollamaBaseUrls.value = listOf(ServerConfig(PROBE_SERVER, server.url))
            if (catalogCapabilities != null) {
                // The capability the models screen would have fetched from /api/show. Keyed by
                // the server URL, which is the same key isThinkingModel looks up.
                service._modelCatalog.value = mapOf(
                    server.url.trimEnd('/') to ModelCatalogState.Loaded(
                        entries = listOf(
                            ModelEntry(
                                name = model,
                                capabilities = catalogCapabilities,
                                parameterSize = null,
                                quantization = null,
                                contextLength = null,
                                sizeBytes = null,
                            )
                        ),
                        showStates = emptyMap(),
                        fetchedAtMillis = 0L,
                    )
                )
            }
            val persona = Persona(
                name = PROBE_PERSONA,
                themeColor = Color(0xFF4ADE80),
                model = "[$PROBE_SERVER] $model",
                systemPrompt = "You are terse.",
                numCtx = 4096,
                maxTokens = 256,
                enableThinking = thinking,
            )
            service.addPersona(persona)
            service.currentPersonaName = persona.name
            service.messagesOwnerName = persona.name

            val before = service._messages.value.size
            service.sendTextMessageToServer("hello", persona)
            runCurrent()
            val connection = server.connection(0)
            assertTrue(
                "the turn never reached the stub",
                connection.requestArrived.await(20, TimeUnit.SECONDS)
            )

            val body = Json.parseToJsonElement(connection.requestBody).jsonObject
            assertEquals(
                "think must be a top-level request field (Ollama ignores options.think)",
                expectedTopLevel,
                body["think"]?.jsonPrimitive?.booleanOrNull,
            )
            assertFalse(
                "options must not carry think: it is ignored there",
                body["options"]!!.jsonObject.containsKey("think"),
            )

            connection.sendChunk("ok")
            connection.finishStream()
            awaitAnswer(service, before)
        }
    }

    private fun TestScope.awaitAnswer(service: AssistantService, before: Int, timeoutMs: Long = 30_000) {
        fun settled() = service._messages.value
            .drop(before)
            .firstOrNull { it.role == "assistant" && (it.text.isNotBlank() || it.isError) }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            runCurrent()
            if (settled() != null) return
            Thread.sleep(25)
        }
        runCurrent()
        assertTrue("the turn never produced an answer", settled() != null)
    }

    private companion object {
        const val PROBE_SERVER = "ThinkingFlagProbeServer"
        const val PROBE_PERSONA = "ThinkingFlagProbePersona"
    }
}
