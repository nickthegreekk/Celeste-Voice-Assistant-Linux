package com.nikosm.voiceassistant

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Fix #6 — a request whose FIXED parts already cannot fit the persona's Context Window Size
 * must be refused before it is sent, with a clear, actionable explanation (ported from
 * upstream's instrumented test in M7).
 *
 * The bug: both builders trim history against `persona.numCtx`, but history trimming cannot
 * help when the reserved output, the system prompt and the current message (with attachment
 * text inlined) are ALONE bigger than the window. The trimmed history floor was 0, so the
 * request went out and the server silently truncated whatever it wanted.
 *
 * The fix: both builders go through `resolveHistoryBudget(...)`, which refuses to send when the
 * budget is negative, throwing the user-facing message that the callers' error handling
 * surfaces as an `isError` bubble.
 *
 * Four cases: a long system prompt (route a), a large text attachment (route b), the same
 * overflow through the cloud builder, and the inverse control that an under-budget request is
 * still sent. The port change is the harness: attachments are `Path`s (the port has no
 * `Uri`/`contentResolver`), Compose Desktop's runner, and the service's Main pumped explicitly.
 */
@OptIn(ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class Fix6OverflowTest {

    private val env = FakeAppEnv(notifier = RecordingNotifier(), scheduler = RecordingScheduler())
    private val store = InMemoryStore()
    private val settings = SettingsManager(env, store, NoImageCodec)
    private val mainDispatcher = StandardTestDispatcher()
    private val services = mutableListOf<AssistantService>()
    private val scratchFiles = mutableListOf<Path>()

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
        services.clear()
        scratchFiles.forEach { runCatching { Files.deleteIfExists(it) } }
        scratchFiles.clear()
        Dispatchers.resetMain()
    }

    // ---- budget arithmetic, recomputed independently of the production helper ----------------

    /** Mirrors the production `estimateTokens` approximation (chars / 4, floored, min 1). */
    private fun estimateTokens(text: String): Int = (text.length / 4.0).toInt().coerceAtLeast(1)

    /**
     * The system prompt the Direct-Ollama path really sends, including the stamp instruction:
     * this probe persona is a stamped direct-Ollama one, so that suffix IS part of the fixed
     * prompt the pre-flight check measures.
     */
    private fun expectedSystemPrompt(persona: Persona, stampedDirectOllama: Boolean = false): String =
        "Current date and time: " +
            SimpleDateFormat("EEEE, MMMM d, yyyy, HH:mm", Locale.getDefault()).format(Date()) +
            "\n\n${persona.systemPrompt}" +
            if (stampedDirectOllama) "\n\n$MODEL_STAMP_INSTRUCTION" else ""

    /** The whole fixed (never-trimmed) prompt the pre-flight check measures against the window. */
    private fun expectedFixedTokens(
        persona: Persona,
        modelText: String,
        stampedDirectOllama: Boolean = false
    ): Int =
        RESERVED_OUTPUT_TOKENS + estimateTokens(expectedSystemPrompt(persona, stampedDirectOllama)) +
            estimateTokens(modelText)

    /**
     * `buildModelPrompt` with one attachment: the question, then the "--- Attached file: X ---"
     * section carrying the file's text verbatim.
     */
    private fun expectedAttachmentModelText(text: String, fileName: String, content: String): String =
        "$text\n\n--- Attached file: $fileName ---\n$content\n--- End of $fileName ---\n\n"

    /** A real text attachment in the fake environment's cache; the port passes a `Path`. */
    private fun createTextAttachment(fileName: String, content: String): Path {
        val file = env.cacheDir.resolve(fileName)
        Files.writeString(file, content)
        scratchFiles.add(file)
        return file
    }

    // ---- driving a turn through the production entry point -----------------------------------

    private fun registerProbeServer(stub: StubProbeServer) {
        service._ollamaBaseUrls.value = listOf(ServerConfig(name = PROBE_SERVER_NAME, url = stub.url))
    }

    /** Registers the stub as a cloud provider — icon "C" ⇒ no API key required. */
    private fun registerProbeCloudProvider(stub: StubProbeServer) {
        service._customCloudApis.value = listOf(
            CloudApiSetting(
                name = PROBE_CLOUD_PROVIDER,
                baseUrl = stub.url,
                icon = PROBE_CLOUD_ICON,
                color = PROBE_COLOR
            )
        )
    }

    private fun activate(persona: Persona) {
        service.currentPersonaName = persona.name
        service.messagesOwnerName = persona.name
        service._messages.value = emptyList()
    }

    /** Exactly what the send button does — the production text entry point, no shortcuts. */
    private fun sendTurn(persona: Persona, text: String, attachments: List<Path> = emptyList()) {
        service.sendTextMessageToServer(text, persona, attachments)
        mainDispatcher.scheduler.runCurrent()
    }

    /**
     * The full Fix #6 contract for one refused turn: the actionable message (with its token
     * count recomputed here from the same inputs), the Context Window Size actually in effect,
     * no generic failure, nothing at all sent to the server, and no answer produced.
     */
    private fun ComposeUiTest.assertRefusedBeforeSending(
        route: String,
        persona: Persona,
        stub: StubProbeServer,
        expectedFixedTokens: Int,
        expectedUserMessageText: String
    ): ChatMessage {
        awaitService(20_000, "[$route] no error bubble ever appeared — the turn did not refuse") {
            service.messages.value.any { it.isError }
        }
        awaitService(20_000, "[$route] the refused turn never returned to IDLE") {
            service.assistantState.value == AssistantState.IDLE
        }
        val messages = service.messages.value
        val error = messages.last { it.isError }

        assertTrue(
            "[$route] expected the Fix #6 overflow explanation, got: ${error.text}",
            error.text.startsWith("Error: Your message and system prompt (~")
        )
        assertTrue(
            "[$route] the message must name the persona's Context Window Size (${persona.numCtx}), " +
                "got: ${error.text}",
            error.text.contains("exceed this persona's Context Window Size (${persona.numCtx})")
        )
        assertTrue(
            "[$route] the message must offer the concrete remedies, got: ${error.text}",
            error.text.contains(
                "Try a shorter message, a smaller attachment, or increase Context Window Size " +
                    "in this persona's settings."
            )
        )
        assertFalse(
            "[$route] a generic failure was surfaced instead of the actionable overflow message: " +
                error.text,
            error.text.contains("Unknown error") || error.text.contains("Ollama error") ||
                error.text.contains("Error 4") || error.text.contains("Error 5")
        )

        val reported = Regex("\\(~(\\d+) tokens\\)").find(error.text)?.groupValues?.get(1)?.toIntOrNull()
        assertNotNull("[$route] the message must report the overflowing token count: ${error.text}", reported)
        assertTrue(
            "[$route] reported ~$reported tokens, but the same inputs measure ~$expectedFixedTokens " +
                "(reserved $RESERVED_OUTPUT_TOKENS + system prompt + current message w/ attachments). " +
                "A difference of up to $DATE_PREFIX_SLACK_TOKENS is allowed because the request's " +
                "date/time prefix is rebuilt at assertion time",
            abs(reported!! - expectedFixedTokens) <= DATE_PREFIX_SLACK_TOKENS
        )

        // The user's own turn stays in the transcript (the refusal is per-turn, pre-send)...
        assertTrue(
            "[$route] the user's own message must still be in the transcript, got: " +
                messages.map { it.role + ":" + it.text.take(40) },
            messages.any { it.role == "user" && it.text == expectedUserMessageText }
        )
        // ...and nothing else was produced: no answer, no streamed placeholder.
        assertTrue(
            "[$route] a non-error assistant bubble was added despite the refusal: " +
                messages.filter { it.role == "assistant" && !it.isError }.map { it.text.take(60) },
            messages.none { it.role == "assistant" && !it.isError }
        )

        // THE decisive check: no request was built or sent at all.
        assertEquals(
            "[$route] the oversized request must NOT reach the server, but the stub saw " +
                "${stub.requestCount.get()} connection(s) (${stub.chatRequestCount.get()} to /api/chat)",
            0,
            stub.requestCount.get()
        )
        return error
    }

    // ---- the two overflow routes, the second builder, and the inverse control ----------------

    @Test
    fun `long system prompt is refused before sending`() = runComposeUiTest {
        val persona = directPersona(LONG_PROMPT_PERSONA_NAME, LONG_SYSTEM_PROMPT)
        val fixed = expectedFixedTokens(persona, PROBE_SHORT_MESSAGE, stampedDirectOllama = true)
        assertTrue(
            "precondition: the long system prompt alone must exceed the $SMALL_NUM_CTX-token " +
                "window (measured ~$fixed tokens including $RESERVED_OUTPUT_TOKENS reserved)",
            fixed > SMALL_NUM_CTX
        )

        StubProbeServer().use { stub ->
            registerProbeServer(stub)
            activate(persona)
            renderChatList()

            sendTurn(persona, PROBE_SHORT_MESSAGE)

            val error = assertRefusedBeforeSending(
                route = "long system prompt + short message",
                persona = persona,
                stub = stub,
                expectedFixedTokens = fixed,
                expectedUserMessageText = PROBE_SHORT_MESSAGE
            )
            awaitRenderedText(
                "exceed this persona's Context Window Size (${persona.numCtx})",
                "the refusal was never rendered in the chat list (bubble text: ${error.text})"
            )
        }
    }

    @Test
    fun `large text attachment is refused before sending`() = runComposeUiTest {
        val persona = directPersona(SHORT_PROMPT_PERSONA_NAME, SHORT_SYSTEM_PROMPT)
        val withoutAttachment = expectedFixedTokens(persona, PROBE_SHORT_MESSAGE, stampedDirectOllama = true)
        assertTrue(
            "precondition: this persona must be UNDER budget without the attachment " +
                "(measured ~$withoutAttachment tokens against a $SMALL_NUM_CTX-token window)",
            withoutAttachment < SMALL_NUM_CTX
        )
        val modelText = expectedAttachmentModelText(
            PROBE_SHORT_MESSAGE,
            ATTACHMENT_FILE_NAME,
            LARGE_ATTACHMENT_FILLER
        )
        val withAttachment = expectedFixedTokens(persona, modelText, stampedDirectOllama = true)
        assertTrue(
            "precondition: the ${LARGE_ATTACHMENT_FILLER.length}-char attachment must push the " +
                "request over the $SMALL_NUM_CTX-token window (measured ~$withAttachment tokens)",
            withAttachment > SMALL_NUM_CTX
        )

        StubProbeServer().use { stub ->
            registerProbeServer(stub)
            activate(persona)
            renderChatList()
            val attachment = createTextAttachment(ATTACHMENT_FILE_NAME, LARGE_ATTACHMENT_FILLER)

            sendTurn(persona, PROBE_SHORT_MESSAGE, listOf(attachment))

            val error = assertRefusedBeforeSending(
                route = "short system prompt + large text attachment",
                persona = persona,
                stub = stub,
                expectedFixedTokens = withAttachment,
                expectedUserMessageText = "$PROBE_SHORT_MESSAGE\n\n(Attached: $ATTACHMENT_FILE_NAME)"
            )
            awaitRenderedText(
                "exceed this persona's Context Window Size (${persona.numCtx})",
                "the refusal was never rendered in the chat list (bubble text: ${error.text})"
            )
        }
    }

    @Test
    fun `cloud request with long system prompt is refused before sending`() = runComposeUiTest {
        val persona = cloudPersona(CLOUD_PROMPT_PERSONA_NAME, LONG_SYSTEM_PROMPT)
        val fixed = expectedFixedTokens(persona, PROBE_SHORT_MESSAGE)
        assertTrue(
            "precondition: the cloud persona's long system prompt alone must exceed the " +
                "$SMALL_NUM_CTX-token window (measured ~$fixed tokens)",
            fixed > SMALL_NUM_CTX
        )

        StubProbeServer().use { stub ->
            registerProbeCloudProvider(stub)
            activate(persona)
            renderChatList()

            sendTurn(persona, PROBE_SHORT_MESSAGE)

            val error = assertRefusedBeforeSending(
                route = "cloud provider + long system prompt",
                persona = persona,
                stub = stub,
                expectedFixedTokens = fixed,
                expectedUserMessageText = PROBE_SHORT_MESSAGE
            )
            awaitRenderedText(
                "exceed this persona's Context Window Size (${persona.numCtx})",
                "the cloud refusal was never rendered in the chat list (bubble text: ${error.text})"
            )
        }
    }

    @Test
    fun `request that fits is still sent normally`() = runComposeUiTest {
        val persona = directPersona(CONTROL_PERSONA_NAME, SHORT_SYSTEM_PROMPT)
        val fixed = expectedFixedTokens(persona, PROBE_CONTROL_MESSAGE, stampedDirectOllama = true)
        assertTrue(
            "precondition: the control request must FIT the $SMALL_NUM_CTX-token window " +
                "(measured ~$fixed tokens including $RESERVED_OUTPUT_TOKENS reserved)",
            fixed < SMALL_NUM_CTX
        )

        StubProbeServer(answer = CONTROL_ANSWER).use { stub ->
            registerProbeServer(stub)
            activate(persona)
            renderChatList()

            sendTurn(persona, PROBE_CONTROL_MESSAGE)

            awaitService(20_000, "the under-budget request was never sent to the server") {
                stub.chatRequestCount.get() > 0
            }
            awaitRenderedText(CONTROL_ANSWER, "the under-budget answer never reached the chat list")
            awaitService(20_000, "the under-budget turn never returned to IDLE") {
                service.assistantState.value == AssistantState.IDLE
            }

            assertEquals("exactly one chat request should have been sent", 1, stub.chatRequestCount.get())
            assertEquals(
                "the answer should be the only assistant bubble",
                1,
                service.messages.value.count { it.role == "assistant" && it.text == CONTROL_ANSWER }
            )
            assertFalse(
                "the pre-flight check refused a request that fits: " +
                    service.messages.value.filter { it.isError }.map { it.text },
                service.messages.value.any { it.isError }
            )
            assertFalse(
                "an in-budget turn must not show the overflow explanation",
                service.messages.value.any {
                    it.text.contains("exceed this persona's Context Window Size")
                }
            )
        }
    }

    // ---- harness ---------------------------------------------------------------------------

    private fun ComposeUiTest.pump() {
        mainDispatcher.scheduler.runCurrent()
        runCatching { waitForIdle() }
    }

    private fun ComposeUiTest.renderChatList() {
        setContent {
            val state by service.assistantState.collectAsState()
            val messages by service.messages.collectAsState()
            val streaming by service.streamingText.collectAsState()
            val fraction by service.ttsPlaybackFraction.collectAsState()
            val timestamps by service.ttsWordTimestamps.collectAsState()
            val duration by service.voiceDuration.collectAsState()
            MaterialTheme {
                ChatList(
                    messages = messages,
                    listState = rememberLazyListState(),
                    state = state,
                    personaColor = PROBE_COLOR,
                    revealedChars = Int.MAX_VALUE,
                    streamingText = streaming,
                    ttsPlaybackFraction = fraction,
                    ttsWordTimestamps = timestamps,
                    voiceDuration = duration,
                    onEditMessage = { _, _ -> },
                    onDeleteMessage = { },
                    onReplayAudio = { }
                )
            }
        }
        pump()
    }

    private fun ComposeUiTest.hasTextSubstring(substring: String): Boolean = try {
        onAllNodesWithText(substring, substring = true).fetchSemanticsNodes().isNotEmpty()
    } catch (_: Throwable) {
        false
    }

    /** Asserts the given text is actually RENDERED (not merely present in service state). */
    private fun ComposeUiTest.awaitRenderedText(substring: String, failure: String) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            pump()
            if (hasTextSubstring(substring)) return
            Thread.sleep(25)
        }
        pump()
        assertTrue(
            "$failure (visited the semantics tree for \"$substring\"; " +
                "rendered messages=${service.messages.value.map { it.role + ":" + it.text.take(48) }})",
            hasTextSubstring(substring)
        )
    }

    private fun ComposeUiTest.awaitService(timeoutMs: Long, failure: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            pump()
            if (condition()) return
            Thread.sleep(25)
        }
        pump()
        assertTrue(
            "$failure (state=${service.assistantState.value}, " +
                "messages=${service.messages.value.map { it.role + ":" + it.text.take(48) }})",
            condition()
        )
    }

    /**
     * Minimal HTTP/1.1 server on loopback that counts what reaches it. Every fully read request
     * increments [requestCount]; a `POST /api/chat` additionally increments [chatRequestCount] and
     * is answered with the Direct-Ollama streaming NDJSON contract.
     */
    private class StubProbeServer(private val answer: String = CONTROL_ANSWER) : AutoCloseable {

        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val worker: Thread

        val url: String get() = "http://127.0.0.1:${socket.localPort}"
        val requestCount = AtomicInteger()
        val chatRequestCount = AtomicInteger()

        init {
            worker = thread(isDaemon = true, name = "stub-fix6-overflow") {
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
            var remaining = contentLength.toLong()
            val buffer = ByteArray(8_192)
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read <= 0) break
                remaining -= read
            }

            requestCount.incrementAndGet()
            val output = connection.getOutputStream()
            if (!(headerText.startsWith("POST") && headerText.contains("/api/chat"))) {
                output.write(
                    "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .toByteArray()
                )
                output.flush()
                return
            }

            chatRequestCount.incrementAndGet()
            val body = (
                "{\"model\":\"$PROBE_MODEL\",\"created_at\":\"2026-01-01T00:00:00Z\"," +
                    "\"message\":{\"role\":\"assistant\",\"content\":\"$answer\"},\"done\":false}\n" +
                    "{\"model\":\"$PROBE_MODEL\",\"created_at\":\"2026-01-01T00:00:01Z\"," +
                    "\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true," +
                    "\"done_reason\":\"stop\",\"prompt_eval_count\":9,\"eval_count\":4}\n"
                ).toByteArray()
            output.write(
                (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/x-ndjson\r\n" +
                        "Content-Length: ${body.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray()
            )
            output.write(body)
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

    private companion object {
        /** Must match the `[Name] model` tag on the direct probe personas — that is how it resolves. */
        const val PROBE_SERVER_NAME = "Fix6ProbeOverflowServer"

        /** Registered in `_customCloudApis`; the cloud probe persona's tag points at it. */
        const val PROBE_CLOUD_PROVIDER = "Fix6ProbeCloud"

        /** Icon "C" is the generic cloud/api-key-optional provider, so no key is needed. */
        const val PROBE_CLOUD_ICON = "C"

        /** Not an `isKnownThinkingModel` match, so `think` is never requested. */
        const val PROBE_MODEL = "fix6-probe-overflow-model"

        /** The control turn's whole answer, delivered as ONE NDJSON chunk. */
        const val CONTROL_ANSWER = "FIX6-CONTROL-ANSWER"

        /** The persona Context Window Size under test — small, so the fixed parts overflow it. */
        const val SMALL_NUM_CTX = 2048

        /** `persona.maxTokens`: what both builders reserve for the reply (floor 1024). */
        const val RESERVED_OUTPUT_TOKENS = 1024

        /**
         * Slack allowed when comparing the message's "~X tokens" against this test's independent
         * recomputation: the builders embed the request-time date/time string, which this test
         * rebuilds a moment later.
         */
        const val DATE_PREFIX_SLACK_TOKENS = 2

        /** `.txt` ⇒ accepted by `isPlainTextAttachment`; the port passes a real file path. */
        const val ATTACHMENT_FILE_NAME = "fix6_large_probe.txt"

        /**
         * ~2,460 tokens on its own: with 1024 tokens reserved for the reply inside a 2048-token
         * window, no amount of history trimming can make this persona's request fit.
         */
        val LONG_SYSTEM_PROMPT: String = "You are the Fix6 overflow probe persona. ".repeat(240)

        /** ~8 tokens — comfortably inside the window on its own (proved by the control test). */
        const val SHORT_SYSTEM_PROMPT = "Fix6 short-prompt probe persona."

        /** Deliberately short: the overflow must come from the prompt or the attachment. */
        const val PROBE_SHORT_MESSAGE = "probe question"

        /** Also avoids the word "news", so `isNewsRequest` cannot fire a search. */
        const val PROBE_CONTROL_MESSAGE = "describe the control answer"

        /**
         * ~35,000 chars (~8,750 tokens) of plain text: inlined into the current message by
         * `buildModelPrompt`, it alone blows the 2048-token window.
         */
        val LARGE_ATTACHMENT_FILLER: String =
            "Fix6 attachment filler line for the overflow route.\n".repeat(700)

        val PROBE_COLOR = Color(0xFF4ADE80)

        const val LONG_PROMPT_PERSONA_NAME = "Fix6ProbeLongPromptPersona"
        const val SHORT_PROMPT_PERSONA_NAME = "Fix6ProbeAttachmentPersona"
        const val CLOUD_PROMPT_PERSONA_NAME = "Fix6ProbeCloudPromptPersona"
        const val CONTROL_PERSONA_NAME = "Fix6ProbeControlPersona"

        fun directPersona(name: String, systemPrompt: String): Persona = Persona(
            name = name,
            themeColor = PROBE_COLOR,
            model = "[$PROBE_SERVER_NAME] $PROBE_MODEL",
            systemPrompt = systemPrompt,
            isCloud = false,
            backendUrl = "",
            allowGatewayFailover = false,
            voiceMode = VoiceMode.NONE,
            enableThinking = false,
            webSearchEnabled = false,
            ragEnabled = false,
            maxTokens = RESERVED_OUTPUT_TOKENS,
            numCtx = SMALL_NUM_CTX
        )

        fun cloudPersona(name: String, systemPrompt: String): Persona = Persona(
            name = name,
            themeColor = PROBE_COLOR,
            model = "[$PROBE_CLOUD_PROVIDER] $PROBE_MODEL",
            systemPrompt = systemPrompt,
            isCloud = true,
            providerIcon = PROBE_CLOUD_ICON,
            backendUrl = "",
            allowGatewayFailover = false,
            voiceMode = VoiceMode.NONE,
            enableThinking = false,
            webSearchEnabled = false,
            ragEnabled = false,
            maxTokens = RESERVED_OUTPUT_TOKENS,
            numCtx = SMALL_NUM_CTX
        )
    }
}
