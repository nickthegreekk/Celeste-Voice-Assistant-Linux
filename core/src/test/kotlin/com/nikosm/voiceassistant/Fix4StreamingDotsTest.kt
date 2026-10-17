package com.nikosm.voiceassistant

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
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
 * Fix #4 — the "Thinking" placeholder dots must yield to real streamed text (ported from
 * upstream's instrumented test in M7).
 *
 * The bug: a Direct-Ollama turn keeps `assistantState == THINKING` for the ENTIRE NDJSON stream.
 * The accumulated answer, meanwhile, is already on screen from the first content chunk. So the
 * dots rendered *underneath* the growing response for the whole generation, in both the classic
 * voice mini-box (`ControlBar`, `Text("...")`) and text mode (`ChatList`, `Text("Thinking...")`).
 * The fix gates the dots on `streamingText.isNullOrBlank()`.
 *
 * This is the end-to-end proof of both halves: before any content chunk the dots ARE rendered
 * (the fix must not have removed the progress affordance), and after the first content chunk —
 * while the stream is deliberately held open, so the state is still THINKING — the dots are
 * GONE and the answer text is on screen. "Still THINKING" is what makes this a regression test
 * rather than a test of a state transition.
 *
 * The harness renders the REAL `ControlBar` and reads the real service flows; the only stand-in
 * is the loopback NDJSON stub, whose holds are the phases the assertions run in. The port change
 * is the runner (Compose Desktop's `runComposeUiTest`) and a shorter hold.
 */
@OptIn(ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class Fix4StreamingDotsTest {

    private companion object {
        /** Must match the `[Name] model` tag on [PROBE_PERSONA] — that is how the flow resolves it. */
        const val SERVER_NAME = "Fix4ProbeStreamServer"

        /** Not an `isKnownThinkingModel` match, so `think` is never requested. */
        const val PROBE_MODEL = "fix4-probe-stream-model"

        /**
         * The whole answer, delivered as ONE NDJSON chunk. One chunk makes the published
         * `streamingText` and the placeholder's `text` equal this exact string, so the semantics
         * queries below can use exact (non-substring) matches.
         */
        const val PROBE_ANSWER = "PROBE-ANSWER-VISIBLE"

        /**
         * Deliberately avoids the word "news", so `isNewsRequest` cannot fire a search.
         */
        const val PROBE_PROMPT = "describe the probe answer"

        val PROBE_PERSONA = Persona(
            name = "Fix4ProbeStreamPersona",
            themeColor = Color(0xFF4ADE80),
            model = "[$SERVER_NAME] $PROBE_MODEL",
            systemPrompt = "Instrumentation probe persona",
            isCloud = false,
            backendUrl = "",
            allowGatewayFailover = false,
            voiceMode = VoiceMode.NONE,
            enableThinking = false,
            webSearchEnabled = false,
            ragEnabled = false
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

    /** Classic voice mini-box: the dots are the transcription box's literal `"..."`. */
    @Test
    fun `classic mini box hides dots once streamed text is visible`() =
        assertDotsYieldToStreamedText(textModeOpen = false, dotsText = "...")

    /** Text mode: the same placeholder sits at the tail of the chat list as `"Thinking..."`. */
    @Test
    fun `text mode list hides dots once streamed text is visible`() =
        assertDotsYieldToStreamedText(textModeOpen = true, dotsText = "Thinking...")

    private fun assertDotsYieldToStreamedText(textModeOpen: Boolean, dotsText: String) =
        runComposeUiTest {
            val persona = PROBE_PERSONA
            StubDirectOllamaServer().use { stub ->
                service._ollamaBaseUrls.value = listOf(ServerConfig(name = SERVER_NAME, url = stub.url))
                service.currentPersonaName = persona.name
                service.messagesOwnerName = persona.name
                service._messages.value = emptyList()
                mainDispatcher.scheduler.runCurrent()

                setContent {
                    val state by service.assistantState.collectAsState()
                    val messages by service.messages.collectAsState()
                    val streaming by service.streamingText.collectAsState()
                    val fraction by service.ttsPlaybackFraction.collectAsState()
                    val timestamps by service.ttsWordTimestamps.collectAsState()
                    val duration by service.voiceDuration.collectAsState()
                    MaterialTheme {
                        ControlBar(
                            textModeOpen = textModeOpen,
                            textInput = "",
                            onTextInputChange = { },
                            attachedFiles = emptyList(),
                            attachedImage = null,
                            onAttachClick = { },
                            onImageAttachClick = { },
                            onRemoveAttachment = { },
                            onRemoveImage = { },
                            onSendClick = { },
                            onMicClick = { },
                            onStopClick = { },
                            state = state,
                            personaColor = persona.themeColor,
                            personaName = persona.name,
                            onTextModeToggle = { },
                            focusRequester = remember { FocusRequester() },
                            muted = false,
                            silenced = false,
                            onMuteToggle = { },
                            onSilenceToggle = { },
                            handsFreeMode = false,
                            onHandsFreeToggle = { },
                            messages = messages,
                            // Exactly what MainVoiceScreen pins while a Direct-Ollama stream is
                            // in flight: no fake reveal, all text shown.
                            revealedChars = Int.MAX_VALUE,
                            streamingText = streaming,
                            ttsPlaybackFraction = fraction,
                            ttsWordTimestamps = timestamps,
                            voiceDuration = duration,
                            miniScrollState = rememberScrollState(),
                            listState = rememberLazyListState(),
                            onEditMessage = { _, _ -> },
                            onDeleteMessage = { },
                            onReplayAudio = { },
                            currentPersona = persona
                        )
                    }
                }

                // Drive a real turn through the production entry point the send button calls.
                service.sendTextMessageToServer(PROBE_PROMPT, PROBE_PERSONA, emptyList())
                pump()

                // ---- Phase A: the turn is in flight and the model has produced NOTHING yet. ----
                awaitService(20_000, "the turn never entered THINKING") {
                    service.assistantState.value == AssistantState.THINKING
                }
                awaitService(20_000, "the stub's /api/chat endpoint was never called") {
                    stub.requestCount.get() > 0
                }
                awaitNodes(
                    PROBE_ANSWER,
                    present = false,
                    timeoutMs = 20_000,
                    failure = "precondition failed: the answer was already on screen before the first " +
                        "content chunk — the stub's pre-chunk hold is not being honored"
                )
                awaitNodes(
                    dotsText,
                    present = true,
                    timeoutMs = 20_000,
                    failure = "the \"$dotsText\" dots were not rendered while the model was still " +
                        "generating — the fix must not remove the progress affordance"
                )

                // ---- Phase B: the first content chunk landed; the turn is STILL streaming. ----
                awaitNodes(
                    PROBE_ANSWER,
                    present = true,
                    timeoutMs = 20_000,
                    failure = "the streamed answer never reached the UI"
                )
                assertEquals(
                    "precondition failed: the turn must still be streaming for this to be a Fix #4 " +
                        "regression test — once the stream ends the state is IDLE and the dots are " +
                        "hidden by the state check instead of by streamingText",
                    AssistantState.THINKING,
                    service.assistantState.value
                )
                assertFalse(
                    "streamingText was not published alongside the visible answer, so the render state " +
                        "the fix keys off was never reached " +
                        "(streamingText=${service.streamingText.value})",
                    service.streamingText.value.isNullOrBlank()
                )
                assertFalse(
                    "Fix #4 regression: the \"$dotsText\" dots are still rendered UNDER the visible " +
                        "streamed answer — the user sees the response and the thinking dots at the " +
                        "same time",
                    hasExactText(dotsText)
                )
                assertTrue(
                    "the answer must remain visible while the dots are suppressed",
                    hasExactText(PROBE_ANSWER)
                )

                // ---- Stream ends: the placeholder is finalized in place and lands IDLE. ----
                awaitService(30_000, "the streamed turn never completed") {
                    service.assistantState.value == AssistantState.IDLE
                }
                assertEquals(
                    "the streamed placeholder was not finalized in place (expected exactly one " +
                        "assistant message carrying the streamed text, got " +
                        "${service.messages.value.count { it.text == PROBE_ANSWER }})",
                    1,
                    service.messages.value.count { it.text == PROBE_ANSWER }
                )
                assertFalse(
                    "\"$dotsText\" dots survived the end of the turn",
                    hasExactText(dotsText)
                )
            }
        }

    // ---- the harness -----------------------------------------------------------------------

    /** One pump of both clocks: the service's Main and the composition. */
    private fun ComposeUiTest.pump() {
        mainDispatcher.scheduler.runCurrent()
        runCatching { waitForIdle() }
    }

    /**
     * Semantics presence check that deliberately does NOT wait for Compose idleness: the
     * rendered screen runs an infinite animation in voice mode, so this polls the semantics
     * tree directly.
     */
    private fun ComposeUiTest.hasExactText(text: String): Boolean = try {
        onAllNodesWithText(text, substring = false).fetchSemanticsNodes().isNotEmpty()
    } catch (_: Throwable) {
        // No semantics root yet (before the very first frame) — treat as "not there".
        false
    }

    private fun ComposeUiTest.awaitNodes(text: String, present: Boolean, timeoutMs: Long, failure: String) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            pump()
            if (hasExactText(text) == present) return
            Thread.sleep(25)
        }
        pump()
        assertTrue(
            "$failure (queried \"$text\" present=$present, actual=${hasExactText(text)}, " +
                "state=${service.assistantState.value}, " +
                "streamingText=${service.streamingText.value}, " +
                "messages=${service.messages.value.map { it.role + ":" + it.text.take(32) }})",
            hasExactText(text) == present
        )
    }

    /** Polls SERVICE state (not UI), pumping Main so the turn's continuations run. */
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
                "streamingText=${service.streamingText.value}, " +
                "messages=${service.messages.value.map { it.role + ":" + it.text.take(32) }})",
            condition()
        )
    }

    /**
     * Minimal HTTP/1.1 stub answering `POST /api/chat` with the Direct-Ollama streaming NDJSON
     * contract: headers flush immediately; a hold passes with NO body data (Phase A); one
     * `done:false` line carries the WHOLE answer; a second hold keeps the stream open with that
     * text published (Phase B); then `done:true` ends the turn. The body is EOF-delimited.
     */
    private class StubDirectOllamaServer(
        private val holdBeforeFirstChunkMs: Long = 2_500,
        private val holdAfterFirstChunkMs: Long = 2_500
    ) : AutoCloseable {

        private val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        private val worker: Thread

        val url: String get() = "http://127.0.0.1:${socket.localPort}"
        val requestCount = AtomicInteger()

        init {
            worker = thread(isDaemon = true, name = "stub-direct-ollama") {
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

            val output = connection.getOutputStream()
            val isChatPost = headerText.startsWith("POST") && headerText.contains("/api/chat")
            if (!isChatPost) {
                output.write(
                    "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .toByteArray()
                )
                output.flush()
                return
            }

            requestCount.incrementAndGet()
            output.write(
                (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/x-ndjson\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray()
            )
            output.flush()

            // Window A: the turn has been accepted, nothing has been produced yet.
            Thread.sleep(holdBeforeFirstChunkMs)
            output.write(
                (
                    "{\"model\":\"$PROBE_MODEL\",\"created_at\":\"2026-01-01T00:00:00Z\"," +
                        "\"message\":{\"role\":\"assistant\",\"content\":\"$PROBE_ANSWER\"}," +
                        "\"done\":false}\n"
                    ).toByteArray()
            )
            output.flush()

            // Window B: the full answer is published, the turn is still streaming.
            Thread.sleep(holdAfterFirstChunkMs)
            output.write(
                (
                    "{\"model\":\"$PROBE_MODEL\",\"created_at\":\"2026-01-01T00:00:01Z\"," +
                        "\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true," +
                        "\"done_reason\":\"stop\",\"prompt_eval_count\":3,\"eval_count\":2}\n"
                    ).toByteArray()
            )
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
}
