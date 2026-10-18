package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Spontaneous messages — the TURN, proven against the production entry point (ported from
 * upstream's instrumented test in M7).
 *
 * The pure half (framing, suffix, prompt composition, silence parsing) is covered on the JVM
 * (`SpontaneousTurnTest`). What only the running service can show is the execution:
 *  - a `runSpontaneousTurn` for a persona produces a completion through the REAL loopback
 *    Ollama and lands it in THAT persona's history — never the single-chat author's, never
 *    through the streaming pipeline;
 *  - silence writes nothing at all;
 *  - the wire request honours the isolation contract (non-streaming, framing instruction
 *    last, persona prompt first, persona's own instruction riding along);
 *  - an unreachable server writes nothing and throws nothing beyond the transport failure the
 *    caller classifies (a background miss must never become an error bubble).
 *
 * Probe personas are NEVER activated, so the detached sink and its synchronous persist are what
 * make the persistence assertions race-free without sleeping for the service debounce. The port
 * change is the harness: a fresh service, `RecordingScheduler` instead of WorkManager, and
 * kotlinx.serialization instead of `org.json`.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SpontaneousTurnServiceTest {

    private companion object {
        const val LOCAL_SERVER = "ProbeOllamaTurn"
        const val MODEL = "probe-model"
        const val LOCAL_MODEL = "[$LOCAL_SERVER] $MODEL"
        const val MAKE_MESSAGE = "Hello from the other side."
        /** A reply that echoes the `[date time]` stamp the app showed it — must be stored clean. */
        const val STAMPED_ECHO = "[Oct 3, 2026 12:29] " + MAKE_MESSAGE
        const val PROBE_USER = "probe-user"
        const val PROBE_PASSWORD = "probe-pass"
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

    private fun mainSync(block: () -> Unit) {
        block()
        mainDispatcher.scheduler.runCurrent()
    }

    private fun localPersona(name: String, enabled: Boolean) = Persona(
        name = name, themeColor = Color(0xFF4ADE80), model = LOCAL_MODEL,
        systemPrompt = "You are a warm friend.", isCloud = false, spontaneousEnabled = enabled
    )

    private fun stored(name: String): Persona =
        service.personas.value.firstOrNull { it.name == name }
            ?: error("persona '$name' is not in the service's list")

    private fun roster(name: String): List<ChatMessage> =
        settings.getPersonaMessages(name) ?: emptyList()

    private fun registerProbeServer(url: String) {
        service._ollamaBaseUrls.value = listOf(
            ServerConfig(LOCAL_SERVER, url, PROBE_USER, PROBE_PASSWORD, AuthType.BASIC)
        )
    }

    // ------------------------------------------------------------------ the turn

    @Test
    fun `turn lands in that personas history only`() {
        OllamaSuggestionStub(chatContent = STAMPED_ECHO).use { stub ->
            registerProbeServer(stub.url)
            mainSync { service.addPersona(localPersona("ProbeTurn", enabled = true)) }
            val historyBefore = roster("ProbeTurn")
            val sharedBefore = service._messages.value

            val outcome = runBlocking { service.runSpontaneousTurn(stored("ProbeTurn")) }
            assertTrue("the turn must report that it sent", outcome is SpontaneousTurnOutcome.Sent)

            // The probe persona was never activated, so the detached sink persisted
            // synchronously — no debounce to sleep for.
            val historyAfter = roster("ProbeTurn")
            assertEquals(
                "exactly one spontaneous message must land",
                historyBefore.size + 1, historyAfter.size
            )
            val landed = historyAfter.last()
            assertEquals("assistant", landed.role)
            // The stub echoed a `[date time]` stamp; the stored text must be the bare reply — the
            // same strip the streaming chat path applies before storing.
            assertEquals(
                "the model's echoed stamp must not reach the user's chat: [${landed.text}]",
                MAKE_MESSAGE, landed.text
            )
            assertFalse(landed.isError)
            // Nothing touched the single-chat author's transcript from underneath it.
            assertEquals(sharedBefore, service._messages.value)
        }
    }

    @Test
    fun `a silence sentinel writes nothing`() {
        OllamaSuggestionStub(chatContent = SPONTANEOUS_SILENCE_SENTINEL).use { stub ->
            registerProbeServer(stub.url)
            mainSync { service.addPersona(localPersona("ProbeSilence", enabled = true)) }
            val historyBefore = roster("ProbeSilence")

            val outcome = runBlocking { service.runSpontaneousTurn(stored("ProbeSilence")) }
            assertEquals(SpontaneousTurnOutcome.Silent, outcome)

            // The request still went out (one turn was attempted), but no message may land.
            assertEquals(1, stub.requests.size)
            assertEquals(historyBefore, roster("ProbeSilence"))
        }
    }

    @Test
    fun `an unreachable server raises a transport error and writes nothing`() {
        mainSync {
            service._ollamaBaseUrls.value = listOf(
                ServerConfig(LOCAL_SERVER, "http://127.0.0.1:1", PROBE_USER, PROBE_PASSWORD, AuthType.BASIC)
            )
            service.addPersona(localPersona("ProbeDead", enabled = true))
        }
        val historyBefore = roster("ProbeDead")

        // Port 1 refuses instantly on every platform, so this is quick. The turn itself
        // lets the transport error propagate — the CALLER is what classifies and silently
        // skips. Swallowing it here would conflate "server down" with "chose silence" for
        // every future caller. What is asserted is that it fails CLEANLY: nothing is persisted.
        val outcome = runCatching { runBlocking { service.runSpontaneousTurn(stored("ProbeDead")) } }
        assertTrue(
            "expected a transport failure, got: ${outcome.getOrNull() ?: outcome.exceptionOrNull()}",
            outcome.exceptionOrNull() is java.io.IOException
        )
        assertEquals(historyBefore, roster("ProbeDead"))
    }

    @Test
    fun `wire request is non-streaming and carries the persona framing`() {
        OllamaSuggestionStub(chatContent = MAKE_MESSAGE).use { stub ->
            registerProbeServer(stub.url)
            mainSync {
                service.addPersona(
                    localPersona("ProbeFramed", enabled = true)
                        .copy(spontaneousInstruction = "only ever ask about the garden")
                )
                // Seed one already-stamped turn, so the wire payload can be checked for the
                // per-message `[date time]` prefixes a Direct-Ollama persona gets.
                settings.savePersonaMessages(
                    "ProbeFramed",
                    listOf(ChatMessage("user", "we talked about the trip", timestamp = 1_700_000_000_000L))
                )
            }

            runBlocking { service.runSpontaneousTurn(stored("ProbeFramed")) }

            val body = Json.parseToJsonElement(stub.requests.single().body).jsonObject
            assertFalse("there is no bubble to stream into", body["stream"]!!.jsonPrimitive.boolean)
            val messages = body["messages"]!!.jsonArray
            // History rides as an ordinary message, stamped exactly like the streaming path.
            val historyTurn = messages[1].jsonObject
            assertEquals("user", historyTurn["role"]!!.jsonPrimitive.content)
            assertTrue(
                "spontaneous history must carry a [date time] prefix: " +
                    historyTurn["content"]!!.jsonPrimitive.content,
                historyTurn["content"]!!.jsonPrimitive.content.endsWith("] we talked about the trip")
            )
            // First is the system prompt — the persona's own governs, framing rides after.
            val system = messages[0].jsonObject
            assertEquals("system", system["role"]!!.jsonPrimitive.content)
            val prompt = system["content"]!!.jsonPrimitive.content
            assertTrue(prompt.contains("You are a warm friend."))
            assertTrue("the persona's own instruction must reach the model", prompt.contains("only ever ask about the garden"))
            assertTrue(prompt.contains(SPONTANEOUS_SILENCE_SENTINEL))
            assertTrue("the whole framing is budgeted as one piece", prompt.indexOf("own initiative") < prompt.length)
            // Last is the instruction turn that ends the alternation.
            val last = messages[messages.size - 1].jsonObject
            assertEquals("user", last["role"]!!.jsonPrimitive.content)
            assertTrue(last["content"]!!.jsonPrimitive.content.contains(SPONTANEOUS_SILENCE_SENTINEL))
            // Current date/time is part of every budgeted request.
            assertTrue(prompt.contains("Current date and time:"))
        }
    }
}
