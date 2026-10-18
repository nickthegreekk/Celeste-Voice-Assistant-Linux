package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * M2 — toggling hands-free off must not force IDLE underneath a live turn (ported from
 * upstream's instrumented test in M7).
 *
 * The bug: `stopVadListening()` ended with an UNCONDITIONAL `_state.value = IDLE`. Toggling
 * hands-free off while a VAD-initiated turn was in flight therefore reset THINKING even though
 * the turn was still running, and the turn's own terminal path — guarded by
 * `_state == THINKING && isChatRequestCurrent(generation)` — never landed its state.
 *
 * The fix gates the reset on OWNERSHIP: the reset happens only when the THINKING found there is
 * not owned by a chat turn that also recorded itself as the state's owner
 * (`recordThinkingOwner(generation)`) and is still current.
 *
 * Upstream's two cases also asserted the AUDIO FOCUS consequence (the retained
 * `AudioFocusRequest` surviving the toggle, and being released by the turn). That subject no
 * longer exists — PLAN.md §9 deletes the focus stack — so those assertions are dropped and
 * recorded in docs/TEST-MAP.md; what remains is the state-ownership contract they shared:
 * the toggle leaves the live turn THINKING, and that turn's own terminal path still lands IDLE
 * and releases the ownership. The control case pins the other direction — a THINKING that NO
 * chat turn owns must still be normalized to IDLE — so the fix cannot pass by turning the
 * toggle into a no-op.
 *
 * The harness is a fresh service, `FakeAppEnv`, a scratch WAV, and two loopback stubs that park
 * their responses until the test releases them, so "a turn is in flight when hands-free goes
 * off" is engineered rather than raced.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HandsFreeToggleTest {

    private val env = FakeAppEnv(notifier = RecordingNotifier())
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
            audioPlayback = FakeAudioPlayback(),
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

    /**
     * A live VAD-path turn: the exact production `sendAudioToServer` call the recorder's
     * `onSpeechEnd` makes, parked inside the gateway's `/transcribe`. The toggle must leave it
     * THINKING, and its own failure path must land IDLE and release the ownership.
     */
    @Test
    fun `hands free toggle leaves the in flight turn's state to that turn`() {
        val question = "what is the weather"
        val gatewayReply = "{\"text\":\"$question\"}"
        ParkedServer(holdResponse = true, statusCode = 200, responseBody = gatewayReply.toByteArray())
            .use { gateway ->
                ParkedServer(
                    holdResponse = true,
                    statusCode = 500,
                    // An OpenAI-shaped error body: the app surfaces `Error 500: <message>`.
                    responseBody = """{"error":{"message":"probe upstream failure"}}""".toByteArray()
                ).use { cloud ->
                    registerGateway(gateway)
                    registerCloudProvider(cloud, PROBE_PROVIDER)
                    val persona = gatewayVoicePersona(backendUrl = gateway.url)
                    activate(persona)

                    // The VAD-initiated turn, parked inside the gateway's /transcribe.
                    val file = env.cacheDir.resolve("probe_voice_${System.currentTimeMillis()}.wav")
                    Files.write(file, ByteArray(64))
                    mainSync { service.sendAudioToServer(file, persona) }
                    awaitService(20_000, "the voice turn never reached the gateway's /transcribe") {
                        gateway.requestCount.get() >= 1
                    }
                    assertEquals(
                        "the voice turn must own THINKING while it is parked in transcription",
                        AssistantState.THINKING,
                        service.assistantState.value
                    )
                    assertEquals(
                        "the turn must not have moved past transcription yet",
                        0,
                        cloud.requestCount.get()
                    )

                    // Hands-free is toggled off while that turn is in flight.
                    mainSync { service.stopVadListening() }

                    // M2: the live turn keeps the state it owns.
                    assertStateHolds(
                        AssistantState.THINKING,
                        1_500,
                        "M2: stopVadListening() forced IDLE underneath a live turn"
                    )

                    // The turn is let through: it reaches the cloud provider, still THINKING.
                    gateway.releaseResponse()
                    awaitService(20_000, "the turn never reached the cloud provider") {
                        cloud.requestCount.get() >= 1
                    }
                    assertEquals(
                        "the turn must still be THINKING while its cloud call is in flight",
                        AssistantState.THINKING,
                        service.assistantState.value
                    )

                    // The cloud call fails, so the turn terminates through its own failure path.
                    cloud.releaseResponse()
                    awaitService(20_000, "the failed turn never returned to IDLE") {
                        service.assistantState.value == AssistantState.IDLE
                    }
                    assertTrue(
                        "the turn's failure never surfaced in the transcript",
                        service.messages.value.any { it.isError }
                    )
                    assertNull(
                        "M2: the terminal path left a stale THINKING owner behind",
                        service.thinkingOwnerGeneration
                    )
                }
            }
    }

    /**
     * The other direction of the same rule, so the fix cannot pass by turning the toggle into a
     * no-op: a THINKING that NO chat turn owns must still be normalized to IDLE. The state shape
     * comes from a real production path — replaying a Gateway persona's message whose audio was
     * never persisted re-synthesizes it, and `synthesizeGatewayTextForPlayback` sets THINKING for
     * the synthesis latency WITHOUT recording an owner.
     */
    @Test
    fun `hands free toggle still normalizes a thinking that no turn owns`() {
        // An empty `audio/wav` answer: the pipeline's own no-audio guard handles it, so this
        // case can only land IDLE after its assertions.
        ParkedServer(
            holdResponse = true,
            statusCode = 200,
            responseBody = ByteArray(0),
            contentType = "audio/wav"
        ).use { gateway ->
            registerGateway(gateway)
            val persona = gatewayVoicePersona(backendUrl = gateway.url)
            activate(persona)
            assertNull(
                "precondition: this case asserts against a baseline of 'no owner recorded'",
                service.thinkingOwnerGeneration
            )

            mainSync { service.replayMessageAudio(ChatMessage("assistant", REPLAY_TEXT), persona) }
            awaitService(20_000, "the replay never entered its THINKING synthesis feedback") {
                service.assistantState.value == AssistantState.THINKING
            }
            // THINKING is published BEFORE the chunk is synthesised, so wait for the parked
            // request itself rather than assuming the IO leg has already run.
            awaitService(20_000, "the replay never reached the gateway") {
                gateway.requestCount.get() >= 1
            }
            assertEquals(
                "the synthesis feedback must be waiting on the gateway, not finished",
                1,
                gateway.requestCount.get()
            )
            assertNull(
                "precondition: this THINKING must have NO owner recorded — that is the case under test",
                service.thinkingOwnerGeneration
            )

            // Toggling hands-free off here must still land IDLE, exactly as the unconditional
            // write always did: nothing is in flight that could own the state.
            mainSync { service.stopVadListening() }
            assertEquals(
                "M2: the toggle no longer normalizes a THINKING that no live turn owns — the fix " +
                    "must not have disabled the reset",
                AssistantState.IDLE,
                service.assistantState.value
            )
            assertNull(service.thinkingOwnerGeneration)

            // Let the parked synthesis finish so nothing outlives this case (empty body -> no
            // audio -> the pipeline's own no-play guard lands IDLE).
            gateway.releaseResponse()
            awaitService(20_000, "the parked synthesis never completed") {
                gateway.responseSent.count == 0L
            }
            assertEquals(
                "the settled no-audio path must end on IDLE",
                AssistantState.IDLE,
                service.assistantState.value
            )
        }
    }

    // ---- harness --------------------------------------------------------------------------

    /** Points the service at the loopback stub gateway (the entry the persona resolves through). */
    private fun registerGateway(stub: ParkedServer) {
        service._serverBases.value = listOf(ServerConfig(name = PROBE_GATEWAY, url = stub.url))
    }

    /** Icon "C" needs no API key. */
    private fun registerCloudProvider(stub: ParkedServer, provider: String) {
        service._customCloudApis.value = listOf(
            CloudApiSetting(
                name = provider,
                baseUrl = stub.url,
                apiKey = "",
                icon = "C",
                color = PROBE_COLOR
            )
        )
    }

    /** Makes [persona] the active persona with an empty transcript, as the UI would. */
    private fun activate(persona: Persona) {
        service.currentPersonaName = persona.name
        service.messagesOwnerName = persona.name
        service._messages.value = emptyList()
    }

    /** Fails as soon as the state leaves [expected] anywhere inside [millis]. */
    private fun assertStateHolds(expected: AssistantState, millis: Long, failure: String) {
        val deadline = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < deadline) {
            mainDispatcher.scheduler.runCurrent()
            assertEquals(
                "$failure (state=${service.assistantState.value})",
                expected,
                service.assistantState.value
            )
            Thread.sleep(20)
        }
    }

    private fun mainSync(block: () -> Unit) {
        block()
        mainDispatcher.scheduler.runCurrent()
    }

    private fun awaitService(timeoutMs: Long, failure: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            mainDispatcher.scheduler.runCurrent()
            if (condition()) return
            Thread.sleep(20)
        }
        mainDispatcher.scheduler.runCurrent()
        assertTrue("$failure (state=${service.assistantState.value})", condition())
    }

    private fun gatewayVoicePersona(backendUrl: String) = Persona(
        name = PROBE_PERSONA,
        themeColor = PROBE_COLOR,
        model = "[$PROBE_PROVIDER] probe-model",
        systemPrompt = "Hands-free probe persona.",
        isCloud = true,
        providerIcon = "C",
        backendUrl = backendUrl,
        allowGatewayFailover = false,
        voiceMode = VoiceMode.GATEWAY,
        enableThinking = false,
        webSearchEnabled = false,
        ragEnabled = false,
        maxTokens = 256,
        numCtx = 8192
    )

    private companion object {
        const val PROBE_PERSONA = "HandsFreeProbePersona"
        const val PROBE_GATEWAY = "HandsFreeProbeGateway"
        const val PROBE_PROVIDER = "HandsFreeProbeCloud"
        const val REPLAY_TEXT = "Gateway replay probe: thinking without an owner"
        val PROBE_COLOR = Color(0xFF4ADE80)
    }
}

/**
 * A loopback stub that holds its response until [releaseResponse] is called, so a request can
 * be parked mid-flight for as long as a test needs. [requestCount] fires once the head + body
 * have been read; [responseSent] once the scripted reply has been written and the socket
 * closed.
 */
private class ParkedServer(
    private val statusCode: Int = 200,
    private val responseBody: ByteArray = "{}".toByteArray(),
    private val contentType: String = "application/json",
    private val holdResponse: Boolean = false,
) : AutoCloseable {

    private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val released = CountDownLatch(1)

    val url: String = "http://127.0.0.1:${socket.localPort}"
    val requestCount = AtomicInteger()
    val responseSent = CountDownLatch(1)

    init {
        val port = socket.localPort
        Thread({
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    break
                }
                Thread({ handle(client) }, "parked-server-$port").apply { isDaemon = true }.start()
            }
        }, "parked-server-accept-$port").apply { isDaemon = true }.start()
    }

    fun releaseResponse() = released.countDown()

    override fun close() {
        released.countDown() // unblock any held worker so the test cannot leak a thread
        runCatching { socket.close() }
    }

    private fun handle(connection: Socket) {
        try {
            connection.use { client ->
                val input = BufferedInputStream(client.getInputStream())
                val head = StringBuilder()
                while (true) {
                    val line = readAsciiLine(input) ?: return
                    if (line.isEmpty()) break
                    head.append(line).append('\n')
                }
                val contentLength = head.toString().lineSequence()
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

                if (holdResponse) released.await(60, TimeUnit.SECONDS)

                val reason = when (statusCode) {
                    200 -> "OK"
                    500 -> "Internal Server Error"
                    else -> "Error"
                }
                val output = client.getOutputStream()
                output.write(
                    (
                        "HTTP/1.1 $statusCode $reason\r\n" +
                            "Content-Type: $contentType\r\n" +
                            "Content-Length: ${responseBody.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray(Charsets.US_ASCII)
                )
                output.write(responseBody)
                output.flush()
            }
        } catch (e: Exception) {
            // The test closed the socket mid-flight; nothing to report.
        } finally {
            responseSent.countDown()
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
