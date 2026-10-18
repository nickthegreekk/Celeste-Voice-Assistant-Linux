package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
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
 * Skills — the INJECTION layer, proven through the production entry points (ported from
 * upstream's instrumented test in M7).
 *
 * The pure matching half is covered on the JVM (`SkillMatchingTest`). What only the running
 * service can show is the wiring: that the resolved skill text actually reaches the model's
 * system prompt, on every flow, and — just as importantly — that it does NOT leak anywhere it
 * must not go.
 *
 * Every turn below is driven through `sendTextMessageToServer` (and `sendAudioToServer` for the
 * voice pair) on the real service, against a loopback stub that CAPTURES the request body. The
 * stub is the only stand-in; the budget math, request assembly, routing and refusal are all
 * production code. The captured system prompt is the assertion surface, because that is exactly
 * where injected skill text is supposed to be visible.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SkillInjectionTest {

    private companion object {
        /** Must match the `[Name] model` tag on the probe persona — that is how it resolves. */
        const val PROBE_SERVER_NAME = "SkillInjectionProbeServer"

        /** The gateway the voice flow transcribes through. */
        const val PROBE_GATEWAY_NAME = "SkillInjectionProbeGateway"

        /** Not an `isKnownThinkingModel` match, so `think` is never requested. */
        const val PROBE_MODEL = "skill-injection-probe-model"

        const val PROBE_PERSONA = "SkillInjectionProbePersona"

        val PROBE_COLOR = Color(0xFF4ADE80)

        const val ANSWER = "SKILL-PROBE-ANSWER"

        /** A short prompt, so any overflow has to come from the injected skill text. */
        const val SHORT_SYSTEM_PROMPT = "Skill injection probe persona."

        const val SUMMARIZE_ID = "skill-probe-summarize"
        const val POEMY_ID = "skill-probe-poemy"

        const val SUMMARIZE_INSTRUCTIONS = "SKILL-SUMMARIZE-INSTRUCTIONS-MARKER"
        const val POEMY_INSTRUCTIONS = "SKILL-POEMY-INSTRUCTIONS-MARKER"

        /** Starts with the summarize skill's trigger, and also fires the poemy one. */
        const val TRIGGERED_MESSAGE = "summarize this, and make it a poem"

        const val FOLLOW_UP_MESSAGE = "what did you just say?"

        /** What the stub gateway "hears" — starts with the summarize trigger. */
        const val VOICE_TRANSCRIPT = "summarize this out loud please"

        val SUMMARIZE_SKILL = Skill(
            id = SUMMARIZE_ID,
            name = "Summarizer",
            triggerPhrases = listOf("summarize this"),
            instructions = SUMMARIZE_INSTRUCTIONS
        )

        val POEMY_SKILL = Skill(
            id = POEMY_ID,
            name = "Poemy",
            triggerPhrases = listOf("summarize this, and"),
            instructions = POEMY_INSTRUCTIONS
        )

        /**
         * ~2,400 tokens on its own: with 1024 reserved for the reply inside a 2048-token
         * window, injecting this overflows the request on its own.
         */
        val HUGE_INSTRUCTIONS: String = "Skill probe filler instruction line. ".repeat(240)

        const val SMALL_NUM_CTX = 2048
        const val RESERVED_OUTPUT_TOKENS = 1024

        fun probePersona(enabledSkillIds: List<String>, gatewayUrl: String = ""): Persona = Persona(
            name = PROBE_PERSONA,
            themeColor = PROBE_COLOR,
            model = "[$PROBE_SERVER_NAME] $PROBE_MODEL",
            systemPrompt = SHORT_SYSTEM_PROMPT,
            isCloud = false,
            backendUrl = gatewayUrl,
            allowGatewayFailover = false,
            voiceMode = VoiceMode.NONE,
            enableThinking = false,
            webSearchEnabled = false,
            ragEnabled = false,
            maxTokens = RESERVED_OUTPUT_TOKENS,
            numCtx = SMALL_NUM_CTX,
            enabledSkillIds = enabledSkillIds
        )
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
        Files.createDirectories(env.cacheDir)
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

    // ---- driving the production entry points -----------------------------------------------

    private fun mainSync(block: () -> Unit) {
        block()
        mainDispatcher.scheduler.runCurrent()
    }

    private fun registerProbeServer(stub: StubProbeServer) {
        service._ollamaBaseUrls.value = listOf(ServerConfig(name = PROBE_SERVER_NAME, url = stub.url))
    }

    /** The voice flow's OTHER half: the gateway the audio is transcribed through. */
    private fun registerProbeGateway(stub: StubProbeServer) {
        service._serverBases.value = listOf(ServerConfig(name = PROBE_GATEWAY_NAME, url = stub.url))
    }

    /** A real recording file in the fake environment's cache, sent via the production API. */
    private fun sendVoiceTurn(persona: Persona) {
        val file = env.cacheDir.resolve("skill_probe_recording_${System.currentTimeMillis()}.wav")
        Files.write(file, ByteArray(64))
        mainSync { service.sendAudioToServer(file, persona) }
    }

    /**
     * Installs the probe library and persona through the SAME public API the settings UI uses
     * (`addSkill` / `addPersona`), so the test exercises the real persistence path too.
     */
    private fun installLibrary(skills: List<Skill>, persona: Persona) {
        service._skills.value = emptyList()
        skills.forEach { service.addSkill(it) }
        service.addPersona(persona)
        service.currentPersonaName = persona.name
        service.messagesOwnerName = persona.name
        service._messages.value = emptyList()
    }

    /** The send button's own call: the production text entry point, no shortcuts. */
    private fun sendTurn(text: String) {
        val persona = service.personas.value.first { it.name == service.currentPersonaName }
        mainSync { service.sendTextMessageToServer(text, persona) }
    }

    private fun awaitService(timeoutMs: Long, failure: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            mainDispatcher.scheduler.runCurrent()
            if (condition()) return
            Thread.sleep(25)
        }
        mainDispatcher.scheduler.runCurrent()
        throw AssertionError(failure)
    }

    private fun awaitRequests(stub: StubProbeServer, count: Int, failure: String) {
        awaitService(20_000, "$failure (only ${stub.chatRequestCount.get()} arrived)") {
            stub.chatRequestCount.get() >= count
        }
        awaitService(20_000, "the turn never returned to IDLE") {
            service.assistantState.value == AssistantState.IDLE
        }
    }

    /** The system prompt of the [index]-th captured chat request. */
    private fun systemPromptAt(stub: StubProbeServer, index: Int): String {
        val body = Json.parseToJsonElement(
            stub.bodies.getOrNull(index) ?: error(
                "no request #$index reached the stub (only ${stub.chatRequestCount.get()})"
            )
        ).jsonObject
        val messages = body["messages"]!!.jsonArray
        for (element in messages) {
            val message = element.jsonObject
            if (message["role"]!!.jsonPrimitive.content == "system") {
                return message["content"]!!.jsonPrimitive.content
            }
        }
        error("request #$index carried no system message")
    }

    /** Every non-system message content of the [index]-th request, in order. */
    private fun userMessagesAt(stub: StubProbeServer, index: Int): List<String> {
        val body = Json.parseToJsonElement(stub.bodies.getOrNull(index) ?: error("no request #$index")).jsonObject
        return body["messages"]!!.jsonArray
            .map { it.jsonObject }
            .filter { it["role"]!!.jsonPrimitive.content != "system" }
            .map { it["content"]!!.jsonPrimitive.content }
    }

    // ---- the tests --------------------------------------------------------------------------

    @Test
    fun `matching skill reaches the system prompt`() {
        val persona = probePersona(enabledSkillIds = listOf(SUMMARIZE_ID))
        StubProbeServer().use { stub ->
            registerProbeServer(stub)
            installLibrary(listOf(SUMMARIZE_SKILL), persona)

            sendTurn(TRIGGERED_MESSAGE)
            awaitRequests(stub, 1, "the skill turn never reached the server")

            val systemPrompt = systemPromptAt(stub, 0)
            assertTrue(
                "the skill's instructions were missing from the system prompt, which was:\n$systemPrompt",
                systemPrompt.contains(SUMMARIZE_INSTRUCTIONS)
            )
            // The persona's own prompt still governs — the skill is ADDED, not substituted.
            assertTrue(
                "the persona's own system prompt was replaced instead of extended",
                systemPrompt.contains(SHORT_SYSTEM_PROMPT)
            )
            assertEquals(
                "the user's message must reach the model exactly as typed",
                listOf(TRIGGERED_MESSAGE),
                userMessagesAt(stub, 0)
            )
        }
    }

    @Test
    fun `multi match skills are injected in library order`() {
        // The persona lists the ids in the OPPOSITE order to the library, so this fails if
        // anything iterates `enabledSkillIds` instead.
        val persona = probePersona(enabledSkillIds = listOf(POEMY_ID, SUMMARIZE_ID))
        StubProbeServer().use { stub ->
            registerProbeServer(stub)
            installLibrary(listOf(SUMMARIZE_SKILL, POEMY_SKILL), persona)

            sendTurn(TRIGGERED_MESSAGE)
            awaitRequests(stub, 1, "the multi-match turn never reached the server")

            val systemPrompt = systemPromptAt(stub, 0)
            assertTrue("the first skill was not injected:\n$systemPrompt", systemPrompt.contains(SUMMARIZE_INSTRUCTIONS))
            assertTrue("the second skill was dropped instead of also being injected:\n$systemPrompt", systemPrompt.contains(POEMY_INSTRUCTIONS))
            assertTrue(
                "skills must be injected in library order, but here first=" +
                    "${systemPrompt.indexOf(SUMMARIZE_INSTRUCTIONS)} and second=" +
                    "${systemPrompt.indexOf(POEMY_INSTRUCTIONS)}",
                systemPrompt.indexOf(SUMMARIZE_INSTRUCTIONS) < systemPrompt.indexOf(POEMY_INSTRUCTIONS)
            )
        }
    }

    @Test
    fun `skill text is absent from the transcript and the next turn`() {
        val persona = probePersona(enabledSkillIds = listOf(SUMMARIZE_ID))
        StubProbeServer().use { stub ->
            registerProbeServer(stub)
            installLibrary(listOf(SUMMARIZE_SKILL), persona)

            sendTurn(TRIGGERED_MESSAGE)
            awaitRequests(stub, 1, "the skill turn never reached the server")
            assertTrue(
                "the skill text should have been injected on the first turn",
                systemPromptAt(stub, 0).contains(SUMMARIZE_INSTRUCTIONS)
            )

            // A second, NON-triggering turn in the same conversation.
            sendTurn(FOLLOW_UP_MESSAGE)
            awaitRequests(stub, 2, "the follow-up turn never reached the server")

            // 1. The transcript the user sees (and the app persists) holds no skill text.
            val messages = service.messages.value
            assertTrue(
                "the skill's instructions leaked into the transcript: " + messages.map { it.text.take(40) },
                messages.none { it.text.contains(SUMMARIZE_INSTRUCTIONS) }
            )
            assertTrue(
                "the user's own message must still be in the transcript",
                messages.any { it.role == "user" && it.text == TRIGGERED_MESSAGE }
            )
            // 2. The next turn's system prompt must not re-inject the previous turn's skill.
            assertFalse(
                "the previous turn's skill text was replayed into the next turn's system prompt:\n" +
                    systemPromptAt(stub, 1),
                systemPromptAt(stub, 1).contains(SUMMARIZE_INSTRUCTIONS)
            )
            // 3. Nor into the replayed message history.
            assertTrue(
                "the skill text was replayed as a message: ${userMessagesAt(stub, 1)}",
                userMessagesAt(stub, 1).none { it.contains(SUMMARIZE_INSTRUCTIONS) }
            )
            // The prior exchange IS still there — the turn history itself must be intact.
            assertTrue(
                "the first turn should still be in the follow-up's history: ${userMessagesAt(stub, 1)}",
                userMessagesAt(stub, 1).any { it.contains(TRIGGERED_MESSAGE) }
            )
        }
    }

    @Test
    fun `oversized skill text is counted by the budget refusal`() {
        val persona = probePersona(enabledSkillIds = listOf(SUMMARIZE_ID))
        val hugeSkill = SUMMARIZE_SKILL.copy(instructions = HUGE_INSTRUCTIONS)

        StubProbeServer().use { stub ->
            registerProbeServer(stub)
            installLibrary(listOf(hugeSkill), persona)

            // A NON-matching turn on this same tiny-window persona still fits: proves the
            // refusal below is caused by the injected skill text, not the window size.
            sendTurn(FOLLOW_UP_MESSAGE)
            awaitRequests(stub, 1, "the non-triggering control turn never reached the server")

            sendTurn(TRIGGERED_MESSAGE)
            awaitService(20_000, "the oversized skill turn never refused") {
                service.messages.value.any { it.isError }
            }
            val error = service.messages.value.last { it.isError }
            assertTrue(
                "expected the actionable context-window refusal, got: ${error.text}",
                error.text.contains("exceed this persona's Context Window Size")
            )
            // The refusal happens pre-send, so only the control turn is on the wire.
            assertEquals(
                "the over-budget request must never be sent, but the stub saw " +
                    "${stub.chatRequestCount.get()} chat request(s)",
                1,
                stub.chatRequestCount.get()
            )
        }
    }

    @Test
    fun `a non matching turn injects nothing`() {
        val persona = probePersona(enabledSkillIds = listOf(SUMMARIZE_ID, POEMY_ID))
        StubProbeServer().use { stub ->
            registerProbeServer(stub)
            installLibrary(listOf(SUMMARIZE_SKILL, POEMY_SKILL), persona)

            sendTurn(FOLLOW_UP_MESSAGE)
            awaitRequests(stub, 1, "the non-matching turn never reached the server")

            val systemPrompt = systemPromptAt(stub, 0)
            assertFalse(
                "a non-matching message must not inject a skill:\n$systemPrompt",
                systemPrompt.contains(SUMMARIZE_INSTRUCTIONS) || systemPrompt.contains(POEMY_INSTRUCTIONS)
            )
        }
    }

    @Test
    fun `voice turn injects skills from the transcription`() {
        StubProbeServer(transcript = VOICE_TRANSCRIPT).use { gateway ->
            StubProbeServer().use { ollama ->
                registerProbeGateway(gateway)
                registerProbeServer(ollama)
                val persona = probePersona(listOf(SUMMARIZE_ID), gatewayUrl = gateway.url)
                installLibrary(listOf(SUMMARIZE_SKILL), persona)

                sendVoiceTurn(persona)
                awaitRequests(ollama, 1, "the voice turn never reached the model")

                val systemPrompt = systemPromptAt(ollama, 0)
                assertTrue(
                    "a spoken turn matched by a skill must inject it, as a typed turn does. " +
                        "The voice path used to pass no turnContext at all, so skills never " +
                        "reached it. System prompt was:\n$systemPrompt",
                    systemPrompt.contains(SUMMARIZE_INSTRUCTIONS)
                )
                // And the injected text still never lands in the transcript (Q2, voice too).
                assertTrue(
                    "the skill's instructions leaked into the voice transcript: " +
                        service.messages.value.map { it.text.take(40) },
                    service.messages.value.none { it.text.contains(SUMMARIZE_INSTRUCTIONS) }
                )
            }
        }
    }

    @Test
    fun `non matching voice turn injects nothing`() {
        StubProbeServer(transcript = FOLLOW_UP_MESSAGE).use { gateway ->
            StubProbeServer().use { ollama ->
                registerProbeGateway(gateway)
                registerProbeServer(ollama)
                val persona = probePersona(listOf(SUMMARIZE_ID), gatewayUrl = gateway.url)
                installLibrary(listOf(SUMMARIZE_SKILL), persona)

                sendVoiceTurn(persona)
                awaitRequests(ollama, 1, "the voice turn never reached the model")

                assertFalse(
                    "a spoken turn that matched no skill must inject nothing:\n" +
                        systemPromptAt(ollama, 0),
                    systemPromptAt(ollama, 0).contains(SUMMARIZE_INSTRUCTIONS)
                )
            }
        }
    }
}

/**
 * Loopback HTTP/1.1 stub that CAPTURES each request body and answers `POST /api/chat` with the
 * Direct-Ollama streaming NDJSON contract `performDirectOllamaChat` reads. It also answers
 * `/transcribe` when a [transcript] is supplied, so one class serves both halves of a voice turn.
 *
 * Capturing the body is the point: injected skill text is only observable in the request the
 * production builder actually produced. Ported from upstream's instrumented fixture.
 */
private class StubProbeServer(
    private val answer: String = "SKILL-PROBE-ANSWER",
    private val transcript: String? = null
) : AutoCloseable {

    private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val worker: Thread

    val url: String get() = "http://127.0.0.1:${socket.localPort}"
    val chatRequestCount = AtomicInteger()

    /** Every captured `POST /api/chat` body, in arrival order. */
    val bodies = CopyOnWriteArrayList<String>()

    init {
        worker = thread(isDaemon = true, name = "stub-skill-injection") {
            while (!socket.isClosed) {
                try {
                    socket.accept().use { handle(it) }
                } catch (_: Exception) {
                    // Socket closed by close() (or a failed accept).
                }
            }
        }
    }

    private fun handle(connection: Socket) {
        val input = BufferedInputStream(connection.inputStream)
        val headers = StringBuilder()
        while (true) {
            val line = readAsciiLine(input) ?: return
            if (line.isEmpty()) break
            headers.append(line).append('\n')
        }
        val headerText = headers.toString()
        val contentLength = headerText.lineSequence()
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        // Drain the body: leaving it unread makes the client see a reset connection.
        val body = StringBuilder()
        var remaining = contentLength.toLong()
        val buffer = ByteArray(8_192)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) break
            body.append(String(buffer, 0, read, Charsets.UTF_8))
            remaining -= read
        }

        val output = connection.getOutputStream()
        // A voice turn transcribes through the gateway's /transcribe first; the returned
        // text is then the user's message, and therefore the text skills match against.
        if (headerText.startsWith("POST") && headerText.contains("/transcribe")) {
            val reply = "{\"text\":\"${transcript ?: ""}\"}".toByteArray()
            output.write(
                (
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${reply.size}\r\nConnection: close\r\n\r\n"
                    ).toByteArray()
            )
            output.write(reply)
            output.flush()
            return
        }
        if (!(headerText.startsWith("POST") && headerText.contains("/api/chat"))) {
            output.write(
                "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    .toByteArray()
            )
            output.flush()
            return
        }

        chatRequestCount.incrementAndGet()
        bodies += body.toString()
        val response = (
            "{\"model\":\"skill-injection-probe-model\",\"created_at\":\"2026-01-01T00:00:00Z\"," +
                "\"message\":{\"role\":\"assistant\",\"content\":\"$answer\"},\"done\":false}\n" +
                "{\"model\":\"skill-injection-probe-model\",\"created_at\":\"2026-01-01T00:00:01Z\"," +
                "\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true," +
                "\"done_reason\":\"stop\",\"prompt_eval_count\":9,\"eval_count\":4}\n"
            ).toByteArray()
        output.write(
            (
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/x-ndjson\r\n" +
                    "Content-Length: ${response.size}\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray()
        )
        output.write(response)
        output.flush()
    }

    private fun readAsciiLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }

    override fun close() {
        runCatching { socket.close() }
        runCatching { worker.interrupt() }
    }
}
