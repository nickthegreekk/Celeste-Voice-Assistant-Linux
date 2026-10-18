package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * H1 — a cloud voice turn must record the user's spoken turn, not only the reply (ported from
 * upstream's instrumented test in M7).
 *
 * The bug: `sendAudioToServer`'s cloud branch transcribed the audio, threw the text away (it
 * only ever reached the model as the current turn) and let `performCloudChat` append the
 * ASSISTANT message. So a cloud voice conversation left the question out of `_messages` — a
 * visible transcript gap — and out of every subsequent request: an Anthropic persona runs
 * `history.dropWhile { it.role != "user" }` over an assistant-only history and discarded
 * EVERYTHING, so it had no memory at all between turns.
 *
 * The fix appends the transcribed user turn before `performCloudChat` and passes
 * `currentTurnInHistory = true`. How this proves it: the whole turn runs through the
 * production entry point against loopback stubs — one for `/transcribe`, one for the cloud
 * provider — and the SECOND turn's request body is what is asserted: the previous question
 * and answer must be present, in alternating roles, on all three provider dialects.
 *
 * The port change is the harness: a fresh service, a `Path` recording, main pumped
 * explicitly, and kotlinx serialization for the captured bodies.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CloudVoiceHistoryTest {

    private companion object {
        const val PROBE_PERSONA = "CloudVoiceHistoryProbePersona"
        const val PROBE_GATEWAY = "CloudVoiceHistoryProbeGateway"
        const val PROBE_PROVIDER = "CloudVoiceHistoryProbeCloud"
        const val PROBE_MODEL = "probe-model"
        const val PROBE_SYSTEM_PROMPT = "Cloud voice history probe persona."

        const val QUESTION_ONE = "what is the capital of France"
        const val QUESTION_TWO = "and how many people live there"
        const val PROBE_ANSWER = "probe assistant reply"

        val PROBE_COLOR = Color(0xFF4ADE80)

        fun openAiReply(path: String): String? = when {
            path.endsWith("/chat/completions") ->
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"$PROBE_ANSWER\"}}]," +
                    "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":3}}"
            else -> null
        }

        fun anthropicReply(path: String): String? = when {
            path.endsWith("/v1/messages") ->
                "{\"content\":[{\"type\":\"text\",\"text\":\"$PROBE_ANSWER\"}]," +
                    "\"usage\":{\"input_tokens\":5,\"output_tokens\":3}}"
            else -> null
        }

        fun geminiReply(path: String): String? = when {
            path.contains(":generateContent") ->
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"$PROBE_ANSWER\"}]}}]," +
                    "\"usageMetadata\":{\"promptTokenCount\":5,\"candidatesTokenCount\":3}}"
            else -> null
        }
    }

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
        drainServiceScopes(services, mainDispatcher.scheduler)
        services.clear()
        scratchFiles.forEach { runCatching { Files.deleteIfExists(it) } }
        scratchFiles.clear()
        Dispatchers.resetMain()
    }

    // ---- the three provider routes ---------------------------------------------------------

    @Test
    fun `cloud voice turn is recorded in the transcript and carried into the next request`() {
        ProbeServer(transcripts = listOf(QUESTION_ONE, QUESTION_TWO)).use { gateway ->
            ProbeServer(responder = ::openAiReply).use { cloud ->
                registerGateway(gateway)
                registerCloudProvider(cloud, PROBE_PROVIDER, icon = "C", apiKey = "")
                val persona = cloudPersona(PROBE_PROVIDER, icon = "C", backendUrl = gateway.url)

                activate(persona)
                sendVoiceTurn(persona)

                awaitService(20_000, "the first cloud voice turn never produced a user+assistant pair") {
                    service.messages.value.size >= 2
                }
                val firstTurn = service.messages.value
                assertEquals(
                    "H1: the spoken question never reached the transcript — only the reply was appended",
                    "user",
                    firstTurn[0].role
                )
                assertEquals(QUESTION_ONE, firstTurn[0].text)
                assertEquals("assistant", firstTurn[1].role)
                assertEquals(PROBE_ANSWER, firstTurn[1].text)
                awaitIdle("the first cloud voice turn never returned to IDLE")

                sendVoiceTurn(persona)
                awaitService(20_000, "the second cloud voice turn never completed") {
                    service.messages.value.size >= 4
                }

                val secondRequest = cloud.requests.last { it.path.endsWith("/chat/completions") }
                val messages = Json.parseToJsonElement(secondRequest.body).jsonObject["messages"]!!.jsonArray
                val turns = messages.map {
                    it.jsonObject["role"]!!.jsonPrimitive.content to
                        it.jsonObject["content"]!!.jsonPrimitive.content
                }
                assertEquals(
                    "H1: turn 2's request must carry turn 1's question and answer as history " +
                        "(the transcript now feeds the next prompt)",
                    listOf("user" to QUESTION_ONE, "assistant" to PROBE_ANSWER, "user" to QUESTION_TWO),
                    turns.filter { it.first != "system" }
                )
            }
        }
    }

    @Test
    fun `anthropic cloud voice conversation keeps its history instead of being wiped`() {
        ProbeServer(transcripts = listOf(QUESTION_ONE, QUESTION_TWO)).use { gateway ->
            ProbeServer(responder = ::anthropicReply).use { cloud ->
                registerGateway(gateway)
                registerCloudProvider(cloud, PROBE_PROVIDER, icon = "A", apiKey = "probe-key")
                val persona = cloudPersona(PROBE_PROVIDER, icon = "A", backendUrl = gateway.url)

                activate(persona)
                driveTwoVoiceTurns(persona, cloud)

                val secondRequest = cloud.requests.last { it.path.endsWith("/v1/messages") }
                val messages = Json.parseToJsonElement(secondRequest.body).jsonObject["messages"]!!.jsonArray
                val turns = messages.map {
                    it.jsonObject["role"]!!.jsonPrimitive.content to
                        it.jsonObject["content"]!!.jsonPrimitive.content
                }
                // The pre-fix history was assistant-only, and `dropWhile { it.role != "user" }`
                // threw all of it away — the request then held the current question alone.
                assertEquals(
                    "H1/Anthropic: the dropWhile world must still see a previous user turn",
                    listOf("user" to QUESTION_ONE, "assistant" to PROBE_ANSWER, "user" to QUESTION_TWO),
                    turns
                )
            }
        }
    }

    @Test
    fun `gemini cloud voice conversation keeps alternating turns`() {
        ProbeServer(transcripts = listOf(QUESTION_ONE, QUESTION_TWO)).use { gateway ->
            ProbeServer(responder = ::geminiReply).use { cloud ->
                registerGateway(gateway)
                registerCloudProvider(cloud, PROBE_PROVIDER, icon = "G", apiKey = "probe-key")
                val persona = cloudPersona(PROBE_PROVIDER, icon = "G", backendUrl = gateway.url)

                activate(persona)
                driveTwoVoiceTurns(persona, cloud)

                val secondRequest = cloud.requests.last { it.path.contains(":generateContent") }
                val contents = Json.parseToJsonElement(secondRequest.body).jsonObject["contents"]!!.jsonArray
                val turns = contents.map {
                    it.jsonObject["role"]!!.jsonPrimitive.content to
                        it.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
                }
                assertEquals(
                    "H1/Gemini: user/model turns must alternate with the previous turn intact",
                    listOf("user" to QUESTION_ONE, "model" to PROBE_ANSWER, "user" to QUESTION_TWO),
                    turns
                )
            }
        }
    }

    // ---- harness --------------------------------------------------------------------------

    private fun registerGateway(stub: ProbeServer) {
        service._serverBases.value = listOf(ServerConfig(name = PROBE_GATEWAY, url = stub.url))
    }

    /** Icon "C" needs no API key; "A"/"G" require a non-blank one to be accepted at all. */
    private fun registerCloudProvider(stub: ProbeServer, provider: String, icon: String, apiKey: String) {
        service._customCloudApis.value = listOf(
            CloudApiSetting(
                name = provider,
                baseUrl = stub.url,
                apiKey = apiKey,
                icon = icon,
                color = PROBE_COLOR
            )
        )
    }

    private fun activate(persona: Persona) {
        service.currentPersonaName = persona.name
        service.messagesOwnerName = persona.name
        service._messages.value = emptyList()
        mainDispatcher.scheduler.runCurrent()
    }

    /** A fresh, real recording file in the fake environment's cache, sent via the production API. */
    private fun sendVoiceTurn(persona: Persona) {
        val file = env.cacheDir.resolve("probe_recording_${System.currentTimeMillis()}.wav")
        Files.write(file, ByteArray(64) { 0 })
        scratchFiles.add(file)
        service.sendAudioToServer(file, persona)
        mainDispatcher.scheduler.runCurrent()
    }

    private fun driveTwoVoiceTurns(persona: Persona, cloud: ProbeServer) {
        sendVoiceTurn(persona)
        awaitService(20_000, "the first voice turn never completed") { service.messages.value.size >= 2 }
        awaitIdle("the first voice turn never returned to IDLE")
        sendVoiceTurn(persona)
        awaitService(20_000, "the second voice turn never completed") {
            service.messages.value.size >= 4 && cloud.requests.size >= 2
        }
    }

    private fun awaitIdle(failure: String) =
        awaitService(20_000, failure) { service.assistantState.value == AssistantState.IDLE }

    private fun awaitService(timeoutMs: Long, failure: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            mainDispatcher.scheduler.runCurrent()
            if (condition()) return
            Thread.sleep(25)
        }
        mainDispatcher.scheduler.runCurrent()
        assertTrue(
            "$failure (state=${service.assistantState.value}, messages=${service.messages.value.size})",
            condition()
        )
    }

    private fun cloudPersona(provider: String, icon: String, backendUrl: String) = Persona(
        name = PROBE_PERSONA,
        themeColor = PROBE_COLOR,
        model = "[$provider] $PROBE_MODEL",
        systemPrompt = PROBE_SYSTEM_PROMPT,
        isCloud = true,
        providerIcon = icon,
        backendUrl = backendUrl,
        allowGatewayFailover = false,
        // NONE: playResponse's NONE branch lands in IDLE without touching TTS.
        voiceMode = VoiceMode.NONE,
        enableThinking = false,
        webSearchEnabled = false,
        ragEnabled = false,
        maxTokens = 256,
        numCtx = 8192
    )
}

/**
 * A loopback-only stand-in for one HTTP endpoint, scripted per request path.
 *
 * `/transcribe` is served internally from [transcripts] (one text per call, in order); every
 * other path goes through [responder], which returns the raw JSON body for that path (null →
 * 404). Every request's line, headers and body is recorded so a test can assert what the app
 * actually SENT. Ported from upstream's instrumented fixture.
 */
private class ProbeServer(
    private val transcripts: List<String> = emptyList(),
    private val responder: (path: String) -> String? = { null }
) : AutoCloseable {

    private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val transcriptIndex = AtomicInteger()

    val url: String = "http://127.0.0.1:${socket.localPort}"
    val requests = CopyOnWriteArrayList<RecordedRequest>()

    init {
        val port = socket.localPort
        Thread({
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    break // close() tore the listener down
                }
                Thread({ handle(client) }, "probe-server-$port").apply { isDaemon = true }.start()
            }
        }, "probe-server-accept-$port").apply { isDaemon = true }.start()
    }

    override fun close() {
        runCatching { socket.close() }
    }

    private fun handle(connection: Socket) {
        connection.use { client ->
            val input = BufferedInputStream(client.inputStream)
            val requestLine = readAsciiLine(input) ?: return
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readAsciiLine(input) ?: return
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator > 0) {
                    headers[line.substring(0, separator).trim().lowercase()] =
                        line.substring(separator + 1).trim()
                }
            }
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val body = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val count = input.read(body, read, contentLength - read)
                if (count <= 0) break
                read += count
            }
            val path = requestLine.split(' ').getOrNull(1) ?: "/"
            requests += RecordedRequest(path, headers, String(body, 0, read, Charsets.UTF_8))

            val reply = when {
                path.startsWith("/transcribe") -> {
                    val index = transcriptIndex.getAndIncrement()
                    val text = transcripts.getOrElse(index) { transcripts.lastOrNull() ?: "" }
                    "{\"text\":\"$text\"}"
                }
                else -> responder(path)
            }

            val output = client.getOutputStream()
            if (reply == null) {
                output.write(
                    "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .toByteArray(Charsets.US_ASCII)
                )
            } else {
                val bytes = reply.toByteArray(Charsets.UTF_8)
                output.write(
                    (
                        "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/json\r\n" +
                            "Content-Length: ${bytes.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray(Charsets.US_ASCII)
                )
                output.write(bytes)
            }
            output.flush()
        }
    }

    private fun readAsciiLine(input: InputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val next = input.read()
            if (next == -1) return if (builder.isEmpty()) null else builder.toString()
            if (next == '\n'.code) return builder.toString().trimEnd('\r')
            builder.append(next.toChar())
        }
    }
}

/** One recorded request: the target path, lowercased header names, and the raw body. */
private data class RecordedRequest(
    val path: String,
    val headers: Map<String, String>,
    val body: String
)
