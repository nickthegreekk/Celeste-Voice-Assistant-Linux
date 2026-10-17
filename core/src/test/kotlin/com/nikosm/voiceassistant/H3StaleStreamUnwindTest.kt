package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * H3 — a STALE streaming turn unwinding must not touch the NEWER turn's placeholder (ported
 * from upstream's instrumented test in M7).
 *
 * The bug: `streamedPlaceholderIndex` and `_streamingText` are service-global. Turn A streams
 * its first chunk (so it adopts the trailing assistant placeholder and publishes its partial
 * text), turn B starts and adopts a placeholder of its own, and then A's NEXT chunk arrives,
 * hits the per-chunk `isChatRequestCurrent(generation)` guard, and throws
 * `StreamCancelledByUserException`. The unwind that follows ran UNCONDITIONALLY pre-fix:
 * `streamedPlaceholderIndex = null` (killing the index that had since become B's) and
 * `_streamingText.value = null` (blanking B's live overlay). B's remaining chunks then found
 * `streamedPlaceholderIndex == null`, so `updatePlaceholder()` stopped rewriting B's bubble and
 * the apply block fell back to a plain APPEND — leaving B's streamed placeholder stranded next
 * to a second, freshly appended bubble for the same reply.
 *
 * The port change is only the harness: no Android service binding. The service is constructed
 * without `start()`, and the probe persona, base URL and empty transcript are assigned directly
 * — which also keeps the periodic health sweep from racing the stepped stub with an extra
 * connection.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class H3StaleStreamUnwindTest {

    private val env = FakeAppEnv(notifier = RecordingNotifier())
    private val store = InMemoryStore()
    private val settings = SettingsManager(env, store, NoImageCodec)
    private val services = mutableListOf<AssistantService>()

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
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

    /** Points the probe persona at [server] and starts from an empty transcript. */
    private fun AssistantService.activateWithFreshTranscript(server: SteppedNdjsonServer) {
        // `_personas` is private; the production add path is what the editor uses anyway, and
        // the probe persona is not spontaneous, so its schedule refresh is a no-op.
        addPersona(PROBE_PERSONA)
        currentPersonaName = PROBE_PERSONA.name
        // The history owner moves with the selection, exactly as switchPersona does — this
        // test's probe turns are saved under the probe persona's key.
        messagesOwnerName = PROBE_PERSONA.name
        _ollamaBaseUrls.value = listOf(ServerConfig(PROBE_SERVER_NAME, server.url))
        _messages.value = emptyList()
        _streamingText.value = null
        streamedPlaceholderIndex = null
        streamedPlaceholderGeneration = null
        streamingTextGeneration = null
    }

    /** Starts a turn through the production entry point the composer's send button uses. */
    private fun TestScope.startTurn(service: AssistantService, text: String) {
        service.sendTextMessageToServer(text, PROBE_PERSONA)
        runCurrent()
    }

    private fun TestScope.awaitService(timeoutMs: Long, what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            runCurrent()
            if (condition()) return
            Thread.sleep(20)
        }
        runCurrent()
        assertTrue("timed out after ${timeoutMs}ms waiting until $what", condition())
    }

    /**
     * Fails if the published streaming text stops being [expected] at any point during
     * [windowMs]. Sampling the window (rather than checking once) is what catches a
     * clear-then-republish: while the newer turn is parked mid-stream, the stale turn's unwind
     * is the only writer that can touch the service-global overlay at all.
     */
    private fun TestScope.awaitStreamingTextHolds(service: AssistantService, windowMs: Long, expected: String) {
        val deadline = System.currentTimeMillis() + windowMs
        while (System.currentTimeMillis() < deadline) {
            runCurrent()
            assertEquals(
                "the published streaming text was blanked/changed while the turn that owns it " +
                    "was still streaming — only that turn's own terminal path may clear it " +
                    "(streamedPlaceholderIndex=${service.streamedPlaceholderIndex})",
                expected,
                service.streamingText.value,
            )
            Thread.sleep(20)
        }
    }

    @Test
    fun `stale unwind does not strand or duplicate the newer turn's bubble`() = runTest {
        SteppedNdjsonServer().use { server ->
            val service = newService()
            service.activateWithFreshTranscript(server)

            // ---- Turn A: streams its first chunk, then parks mid-stream. ----
            startTurn(service, PROMPT_A)
            val stale = server.connection(0)
            assertTrue("turn A never reached the stub", stale.requestArrived.await(20, java.util.concurrent.TimeUnit.SECONDS))
            stale.sendChunk(CHUNK_A1)
            awaitService(20_000, "turn A's first chunk never reached its placeholder") {
                service._messages.value.any { it.role == "assistant" && it.text == PARTIAL_A }
            }
            awaitService(20_000, "turn A never published its streaming text") {
                service.streamingText.value == PARTIAL_A
            }

            // ---- Turn B: supersedes A (new chat generation) and streams its own chunk. ----
            startTurn(service, PROMPT_B)
            val newer = server.connection(1)
            assertTrue("turn B never reached the stub", newer.requestArrived.await(20, java.util.concurrent.TimeUnit.SECONDS))
            newer.sendChunk(CHUNK_B1)
            awaitService(20_000, "turn B never adopted its own placeholder") {
                service._messages.value.any { it.role == "assistant" && it.text == PARTIAL_B }
            }
            awaitService(20_000, "turn B never published its streaming text") {
                service.streamingText.value == PARTIAL_B
            }

            // ---- A's next chunk arrives now: A is stale, so its per-chunk guard cancels it
            // and throws StreamCancelledByUserException, i.e. A unwinds while B is parked
            // mid-stream with a live placeholder + published text. ----
            stale.sendChunk(CHUNK_A2)
            assertTrue(
                "turn A's stale chunk never cancelled it (its connection stayed open, so the " +
                    "per-chunk generation guard never fired and this test proved nothing)",
                stale.clientDisconnected.await(30, java.util.concurrent.TimeUnit.SECONDS),
            )

            // Evidence #1 (the `_streamingText` half): B is parked, so nothing can republish
            // its overlay. A stale unwind that cleared the published text unconditionally
            // leaves it null for this whole window.
            awaitStreamingTextHolds(service, 2_000, PARTIAL_B)
            assertEquals(
                "H3: the stale turn's unwind forced the newer turn out of THINKING",
                AssistantState.THINKING,
                service.assistantState.value,
            )

            // ---- B finishes and finalizes its own placeholder. ----
            newer.sendChunk(CHUNK_B2)
            newer.finishStream()
            awaitService(30_000, "turn B never finished") {
                service.assistantState.value == AssistantState.IDLE
            }

            // Evidence #2 (the placeholder-index half): pre-fix, A's unwind nulled the index B
            // had since adopted, so B's apply appended a SECOND bubble and B's placeholder was
            // left behind still carrying the partial text.
            assertEquals(
                "H3: the stale turn's unwind cleared the newer turn's placeholder index — its " +
                    "reply was appended as a second bubble and its streamed placeholder was " +
                    "stranded with the partial text (assistant bubbles: " +
                    service._messages.value.filter { it.role == "assistant" }.map { it.text } + ")",
                0,
                service._messages.value.count { it.text == PARTIAL_B },
            )
            assertEquals(
                "H3: turn B's reply must be finalized in its own streamed bubble",
                1,
                service._messages.value.count { it.text == FULL_B },
            )
            // Composition check: A's partial (deliberately kept as history, D2) + B's single
            // finalized bubble. The duplicated-bubble outcome leaves three.
            assertEquals(
                "H3: expected one assistant bubble per turn's content (A's kept partial + B's " +
                    "finalized reply), got " +
                    service._messages.value.filter { it.role == "assistant" }.map { it.text },
                2,
                service._messages.value.count { it.role == "assistant" },
            )
            assertNull(
                "H3: the streamed overlay text must be cleared once the newer turn finished",
                service.streamingText.value,
            )
        }
    }

    /**
     * Control: with nothing superseding it, the same streamed turn must still finalize its
     * placeholder IN PLACE — no stranded partial bubble, no second append. Guards the fix
     * against "solving" H3 by never adopting a placeholder at all.
     */
    @Test
    fun `a lone streamed turn still finalizes its own placeholder in place`() = runTest {
        SteppedNdjsonServer().use { server ->
            val service = newService()
            service.activateWithFreshTranscript(server)

            startTurn(service, PROMPT_B)
            val only = server.connection(0)
            assertTrue("the turn never reached the stub", only.requestArrived.await(20, java.util.concurrent.TimeUnit.SECONDS))
            only.sendChunk(CHUNK_B1)
            awaitService(20_000, "the turn never adopted its placeholder") {
                service._messages.value.any { it.role == "assistant" && it.text == PARTIAL_B }
            }
            awaitStreamingTextHolds(service, 500, PARTIAL_B)

            only.sendChunk(CHUNK_B2)
            only.finishStream()
            awaitService(30_000, "the turn never finished") {
                service.assistantState.value == AssistantState.IDLE
            }

            assertEquals(
                "the streamed reply was not finalized in place (assistant bubbles: " +
                    service._messages.value.filter { it.role == "assistant" }.map { it.text } + ")",
                1,
                service._messages.value.count { it.text == FULL_B },
            )
            assertEquals(
                "the streamed placeholder was stranded with the partial text",
                0,
                service._messages.value.count { it.text == PARTIAL_B },
            )
            assertNull(service.streamingText.value)
        }
    }

    private companion object {
        /** Must match the `[Name] model` tag on [PROBE_PERSONA] — that is how the flow resolves it. */
        const val PROBE_SERVER_NAME = "H3StaleStreamUnwindServer"

        /** Not an `isKnownThinkingModel` match, so `think` is never requested. */
        const val PROBE_MODEL = "h3-stale-stream-model"

        /** Avoids the word "news", so `isNewsRequest` cannot fire a search. */
        const val PROMPT_A = "explain rate limiting in one sentence"
        const val PROMPT_B = "explain how rate limiting differs from throttling"

        const val CHUNK_A1 = "A-ONE "
        const val PARTIAL_A = "A-ONE "
        const val CHUNK_A2 = "A-TWO"

        const val CHUNK_B1 = "B-ONE "
        const val PARTIAL_B = "B-ONE "
        const val CHUNK_B2 = "B-TWO"
        const val FULL_B = "B-ONE B-TWO"

        val PROBE_PERSONA = Persona(
            name = "H3StaleStreamUnwindPersona",
            themeColor = Color(0xFF4ADE80),
            model = "[$PROBE_SERVER_NAME] $PROBE_MODEL",
            systemPrompt = "Instrumentation probe persona",
            isCloud = false,
            backendUrl = "",
            allowGatewayFailover = false,
            voiceMode = VoiceMode.NONE,
            maxTokens = 256,
            numCtx = 8192,
            enableThinking = false,
            webSearchEnabled = false,
            ragEnabled = false,
        )
    }
}
