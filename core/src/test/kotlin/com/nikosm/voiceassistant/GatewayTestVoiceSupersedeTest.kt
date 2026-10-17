package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
 * H2 — a slow "Test Voice" fetch must not corrupt a newer turn that started meanwhile (ported
 * from upstream's instrumented test in M7).
 *
 * The bug: `testGatewayVoice`'s `finally` reset the assistant state to IDLE whenever it found
 * THINKING, with NO ownership check — unlike the two sibling finally blocks
 * (`AssistantServiceChat.kt` voice + cloud flows) which both guard on
 * `isChatRequestCurrent(generation)`. So a "Test Voice" whose `/synthesize` fetch was still in
 * flight when a real turn started would, on completion, force the NEWER turn out of THINKING and
 * repaint "Ready to help".
 *
 * How this proves it: both requests are held inside loopback stub servers, so "the voice test
 * completes while the real turn is still in flight" is ENGINEERED rather than assumed. The real
 * turn is a production `sendTextMessageToServer` cloud turn pointed at a second held stub.
 *
 * Upstream's second case asserted the AUDIO FOCUS consequence of the bug (the newer turn's
 * retained focus was never abandoned once its own IDLE guard had been stolen). That subject no
 * longer exists here — PLAN.md §9 deletes the focus stack, since there is no PipeWire
 * counterpart — so the focus case is recorded as dropped in docs/TEST-MAP.md and only the state
 * ownership it shared with this case is ported. The port change otherwise is the harness: a
 * fresh service over an in-memory store per test, and Main is a `StandardTestDispatcher` pumped
 * explicitly.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GatewayTestVoiceSupersedeTest {

    private val env = FakeAppEnv(notifier = RecordingNotifier())
    private val store = InMemoryStore()
    private val settings = SettingsManager(env, store, NoImageCodec)
    private val services = mutableListOf<AssistantService>()

    private lateinit var service: AssistantService

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        newService()
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
    ).also {
        services += it
        service = it
    }

    // ---- the repro ------------------------------------------------------------------------

    @Test
    fun `superseded voice test does not force the newer turn's state to idle`() = runTest {
        HeldHttpServer(holdResponse = true).use { gateway ->
            HeldHttpServer(
                holdResponse = true,
                statusCode = 500,
                responseBody = PROBE_ERROR_BODY.toByteArray()
            ).use { cloud ->
                registerGateway(gateway)
                registerCloudProvider(cloud)
                val persona = cloudPersona()
                activate(persona)

                beginVoiceTest(gateway)
                startRealTurn(cloud, persona)

                // The voice test finishes now — while the real turn is still parked at the
                // cloud stub, which cannot land IDLE by itself.
                gateway.releaseResponse()
                assertTrue(
                    "the held voice-test request never completed on the wire",
                    gateway.responseSent.await(20, TimeUnit.SECONDS)
                )

                awaitNeverIdleFor(
                    2_000,
                    "H2: the superseded voice test forced the newer turn out of THINKING"
                )
                assertEquals(
                    "H2: the newer turn must still own THINKING while its request is in flight",
                    AssistantState.THINKING,
                    service.assistantState.value
                )

                // Let the real turn fail so its own finally runs and nothing stays in flight.
                cloud.releaseResponse()
                awaitService(20_000, "the newer turn never finished") {
                    service.assistantState.value == AssistantState.IDLE
                }
            }
        }
    }

    /** Control: with nothing superseding it, the same flow must still land IDLE. */
    @Test
    fun `voice test still lands idle when nothing supersedes it`() = runTest {
        HeldHttpServer(holdResponse = true).use { gateway ->
            registerGateway(gateway)
            activate(cloudPersona())

            beginVoiceTest(gateway)
            gateway.releaseResponse()

            awaitService(30_000, "an unsuperseded voice test never landed IDLE") {
                service.assistantState.value == AssistantState.IDLE
            }
            assertEquals("no error may reach the transcript", 0, service.messages.value.count { it.isError })
        }
    }

    // ---- harness --------------------------------------------------------------------------

    private fun TestScope.registerGateway(stub: HeldHttpServer) {
        mainSync {
            service._serverBases.value = listOf(ServerConfig(name = PROBE_GATEWAY, url = stub.url))
        }
    }

    private fun TestScope.registerCloudProvider(stub: HeldHttpServer) {
        mainSync {
            service._customCloudApis.value = listOf(
                CloudApiSetting(
                    name = PROBE_PROVIDER,
                    baseUrl = stub.url,
                    // Icon "C" is the no-API-key probe provider shape.
                    icon = "C",
                    color = PROBE_COLOR
                )
            )
        }
    }

    private fun TestScope.activate(persona: Persona) {
        mainSync {
            service.addPersona(persona)
            service.currentPersonaName = persona.name
            // The history owner moves with the selection, exactly as switchPersona does — this
            // test's probe turns are saved under the probe persona's key.
            service.messagesOwnerName = persona.name
            service._messages.value = emptyList()
        }
    }

    /**
     * Starts the production "Test Voice" flow and proves it is genuinely in flight (THINKING
     * plus an arrived, held request) before returning — so the caller's "a newer turn starts
     * meanwhile" ordering is engineered, not hoped for.
     */
    private fun TestScope.beginVoiceTest(gateway: HeldHttpServer) {
        mainSync {
            service.testAudio(
                PROBE_VOICE_TEST_TEXT,
                VoiceMode.GATEWAY,
                gateway.url,
                "English",
                false,
                "kokoro",
                "af_heart"
            )
        }
        awaitService(20_000, "the voice test never entered THINKING") {
            service.assistantState.value == AssistantState.THINKING
        }
        assertTrue(
            "the voice test never reached the gateway",
            gateway.requestArrived.await(20, TimeUnit.SECONDS)
        )
    }

    /**
     * Starts the newer, real turn through the production text entry point (which bumps the
     * chat generation, superseding the voice test).
     */
    private fun TestScope.startRealTurn(cloud: HeldHttpServer, persona: Persona) {
        mainSync { service.sendTextMessageToServer(PROBE_REAL_TURN_TEXT, persona) }
        awaitService(20_000, "the real turn never entered THINKING") {
            service.assistantState.value == AssistantState.THINKING
        }
        assertTrue(
            "the real turn never reached the cloud provider",
            cloud.requestArrived.await(20, TimeUnit.SECONDS)
        )
    }

    /** Fails as soon as the state reaches IDLE anywhere inside [millis]. */
    private fun TestScope.awaitNeverIdleFor(millis: Long, failure: String) {
        val deadline = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < deadline) {
            runCurrent()
            if (service.assistantState.value == AssistantState.IDLE) {
                assertTrue("$failure (IDLE observed inside the ${millis}ms window)", false)
            }
            Thread.sleep(20)
        }
    }

    private fun TestScope.awaitService(timeoutMs: Long, failure: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            runCurrent()
            if (condition()) return
            Thread.sleep(20)
        }
        runCurrent()
        assertTrue("$failure (state=${service.assistantState.value})", condition())
    }

    private fun TestScope.mainSync(block: () -> Unit) {
        block()
        runCurrent()
    }

    private fun cloudPersona() = Persona(
        name = PROBE_PERSONA,
        themeColor = PROBE_COLOR,
        model = "[$PROBE_PROVIDER] probe-model",
        systemPrompt = "Supersede probe persona.",
        isCloud = true,
        providerIcon = "C",
        backendUrl = "",
        allowGatewayFailover = false,
        voiceMode = VoiceMode.NONE,
        enableThinking = false,
        webSearchEnabled = false,
        ragEnabled = false,
        maxTokens = 256,
        numCtx = 8192
    )

    private companion object {
        const val PROBE_PERSONA = "TestVoiceSupersedeProbePersona"
        const val PROBE_GATEWAY = "TestVoiceSupersedeProbeGateway"
        const val PROBE_PROVIDER = "TestVoiceSupersedeProbeCloud"
        const val PROBE_VOICE_TEST_TEXT = "voice test probe"
        const val PROBE_REAL_TURN_TEXT = "superseding real turn probe"
        const val PROBE_ERROR_BODY = "{\"error\":{\"message\":\"probe failure\"}}"

        val PROBE_COLOR = Color(0xFF4ADE80)
    }
}

/**
 * A loopback stub that holds its response until [releaseResponse] is called, so a request can be
 * parked mid-flight for as long as a test needs. [requestArrived] fires once the request head +
 * body have been read (the request is genuinely in flight); [responseSent] once the scripted
 * reply has been written and the socket closed.
 *
 * A `Content-Type: application/json` reply is what the production "Test Voice" flow treats as
 * "no audio" — the point here is that the FETCH completes, not that anything is played.
 */
private class HeldHttpServer(
    private val statusCode: Int = 200,
    private val responseBody: ByteArray = "{\"note\":\"probe\"}".toByteArray(),
    private val contentType: String = "application/json",
    private val holdResponse: Boolean = false
) : AutoCloseable {

    private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val released = CountDownLatch(1)

    val url: String = "http://127.0.0.1:${socket.localPort}"
    val requestArrived = CountDownLatch(1)
    val responseSent = CountDownLatch(1)

    init {
        val port = socket.localPort
        Thread({
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    break // close() tore the listener down
                }
                Thread({ handle(client) }, "held-server-$port").apply { isDaemon = true }.start()
            }
        }, "held-server-accept-$port").apply { isDaemon = true }.start()
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
                // Drain the body: an unread body makes the client see a reset connection.
                var remaining = contentLength.toLong()
                val buffer = ByteArray(8_192)
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read <= 0) break
                    remaining -= read
                }
                requestArrived.countDown()

                if (holdResponse) released.await(60, TimeUnit.SECONDS)

                val reason = if (statusCode == 200) "OK" else "Stub Error"
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
