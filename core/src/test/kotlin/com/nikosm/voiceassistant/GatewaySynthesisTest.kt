package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The gateway's `/synthesize` leg over real HTTP (M7's fake gateway).
 *
 * Two shapes the gateway really returns, both measured on 2026-10-16 and both previously hard to
 * see: audio with word timestamps in a header, and a 200 `application/json` body that means "no
 * voice for this language" — a decision, not a failure, which used to be logged at DEBUG and
 * therefore never printed. The point of going through HTTP rather than a canned `GatewaySpeech` is
 * the content-type branch: a JSON note and a WAV body are told apart by the header, and a test that
 * constructed the result directly would not exercise the branch that decides it.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GatewaySynthesisTest {
    private val mainDispatcher = StandardTestDispatcher()


    private val env = FakeAppEnv(notifier = RecordingNotifier())
    private val settings = SettingsManager(env, InMemoryStore(), NoImageCodec)
    private var server: HttpServer? = null
    private var service: AssistantService? = null

    /** Swapped by the test to choose the gateway's answer. */
    @Volatile
    private var jsonRefusal = false
    private val requests = AtomicInteger()

    private val audioBytes = ByteArray(32) { (it * 3).toByte() }

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server = http
        http.createContext("/synthesize") { exchange ->
            requests.incrementAndGet()
            exchange.requestBody.use { it.readBytes() }
            if (jsonRefusal) {
                val body = """{"audio":null,"note":"Russian has no TTS voice available — text only."}"""
                val bytes = body.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else {
                exchange.responseHeaders.add("Content-Type", "audio/wav")
                exchange.responseHeaders.add(
                    "X-Word-Timestamps-B64",
                    Base64.getEncoder().encodeToString("""[[0,100]]""".toByteArray()),
                )
                exchange.sendResponseHeaders(200, audioBytes.size.toLong())
                exchange.responseBody.use { it.write(audioBytes) }
            }
        }
        http.start()
        val url = "http://127.0.0.1:${http.address.port}"
        settings.saveServerBases(listOf(ServerConfig(name = "GW", url = url)))
        settings.savePersonas(
            listOf(
                Persona(
                    name = "Echo",
                    themeColor = Color(0xFF4ADE80),
                    model = "bare-model",
                    systemPrompt = "You are a warm assistant.",
                    backendUrl = url,
                    voiceEngine = "kokoro",
                    kokoroVoice = "af_heart",
                )
            )
        )
        service = AssistantService(
            appEnv = env,
            audioPlayback = SilentAudioPlayback,
            audioDevices = NoAudioDevices,
            tts = NoTextToSpeech,
            audioCapture = NoAudioCapture,
            fileDialogs = NoFileDialogs,
            settingsManager = settings,
        ).also { it.start() }   // loads the gateway entry the synthesis resolves against
    }

    @After
    fun tearDown() {
        server?.stop(0)
        service?.let { runCatching { it.shutdown() } }
        drainServiceScopes(listOfNotNull(service), mainDispatcher.scheduler)
        Dispatchers.resetMain()
    }

    private fun persona() = settings.getPersonas()!!.first()

    @Test
    fun `audio and word timestamps come back from a real response`() = runTest {
        val speech = service!!.synthesizeWithGateway("Hello there, this is a reply.", persona())

        assertNotNull("audio is the body bytes: ${speech.noAudioReason}", speech.audio)
        assertArrayEquals(audioBytes, speech.audio)
        assertEquals("the header is base64-decoded", """[[0,100]]""", speech.timestamps)
        assertNull("no reason when audio arrived", speech.noAudioReason)
        assertEquals(1, requests.get())
    }

    @Test
    fun `a JSON refusal is reported with its note, engine and language, not as a failure`() = runTest {
        jsonRefusal = true

        val speech = service!!.synthesizeWithGateway("Hello there.", persona())

        assertNull(speech.audio)
        val reason = speech.noAudioReason!!
        assertTrue("the gateway's own note is kept: $reason", reason.contains("Russian has no TTS voice"))
        assertTrue("the route is named: $reason", reason.startsWith("gateway TTS produced no audio"))
        assertTrue("the engine is named: $reason", reason.contains("engine kokoro"))
        assertTrue("and the language the reply was classified as: $reason", reason.contains("language English"))
    }

    @Test
    fun `empty text never reaches the gateway`() = runTest {
        val speech = service!!.synthesizeWithGateway("   ", persona())

        assertNull(speech.audio)
        assertTrue(speech.noAudioReason!!.contains("the text was empty"))
        assertEquals("no request may be sent for nothing", 0, requests.get())
    }
}
