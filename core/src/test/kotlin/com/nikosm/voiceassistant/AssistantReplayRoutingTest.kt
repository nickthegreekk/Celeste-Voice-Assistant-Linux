package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins the voice-routing contract of REPLAYING an assistant message whose audio was never
 * persisted (ported from upstream's instrumented test in M7). The Stage-2 streaming Gateway
 * pipeline synthesizes per chunk and never writes a `.wav`, so `ChatMessage.audioFilePath` is
 * null for those turns by design.
 *
 * The bug this guards: that case used to fall through to System TTS, so replaying a Gateway
 * persona's message played it in the phone's own voice instead of the persona's configured
 * Kokoro voice — silently misrepresenting the persona. The fix re-synthesizes the stored text
 * through the persona's own Gateway voice (`synthesizeGatewayTextForPlayback` →
 * `playChunkedTtsGateway`).
 *
 * Why this assertion works here too: the discriminated observable is *which server was asked
 * for audio*. The persona is pointed at a loopback stub `/synthesize` endpoint, so "the Gateway
 * voice was used" is the arrival of that request, and "System TTS was used" is its absence.
 * Playing the returned bytes is then confirmed by finding the chunk WAV the pipeline wrote,
 * byte-for-byte, in the fake environment's cache — and no assertion depends on playback
 * callback timing, because [FakeAudioPlayback] never completes on its own.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AssistantReplayRoutingTest {

    private val env = FakeAppEnv(notifier = RecordingNotifier())
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
            audioPlayback = FakeAudioPlayback().apply { defaultDurationMs = 250 },
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

    @Test
    fun `gateway replay re-synthesizes with the persona's own voice instead of system tts`() {
        // Under the 80-char chunk minimum and free of sentence boundaries, so the pipeline
        // sends this text as ONE chunk — the request body must then carry it verbatim.
        val historyText = "Replay routing probe: gateway voice, not the phone's own engine"
        val stubAudio = silentWavBytes()
        StubTtsServer(stubAudio).use { stub ->
            val persona = Persona(
                name = "ReplayRoutingProbe",
                themeColor = Color(0xFF4ADE80),
                model = "probe-model",
                systemPrompt = "",
                backendUrl = stub.baseUrl,
                voiceMode = VoiceMode.GATEWAY,
                voiceEngine = "kokoro",
                kokoroVoice = "af_bella"
            )
            // A muted service bails before synthesis; that landing is covered elsewhere.
            if (service.silenced.value) service.toggleSilence()
            // The M1 resolver refuses to synthesize for a Backend URL that maps to no configured
            // Servers entry, so the stub must be registered as the persona's gateway first.
            service._serverBases.value = listOf(ServerConfig(name = PROBE_GATEWAY_SERVER, url = stub.baseUrl))

            val generationBefore = service.ttsGeneration
            val message = ChatMessage("assistant", historyText) // audioFilePath == null by design
            service.replayMessageAudio(message, persona)

            assertTrue(
                "replay never reached the persona's Gateway /synthesize endpoint within 30s — the " +
                    "turn was routed to a different voice engine (pre-fix: System TTS) instead of " +
                    "re-synthesizing with the persona's configured voice",
                waitFor(30_000) { stub.requestCount.get() > 0 }
            )
            assertEquals(
                "the persona's own Gateway endpoint must be the one used",
                "/synthesize",
                stub.lastPath
            )

            val body = stub.lastRequestBody ?: ""
            assertTrue("the replayed message text must be sent: $body", body.contains(historyText))
            assertTrue(
                "the persona's configured Kokoro voice must be sent: $body",
                body.contains("af_bella")
            )
            assertTrue("the persona's configured engine must be sent: $body", body.contains("kokoro"))

            // The pipeline's own artifact is the second half of the proof: the exact bytes the
            // stub returned were written as chunk 0 of a fresh generation, i.e. they were what
            // got played — not merely fetched.
            val written = waitUntilChunkFile(stubAudio)
            assertNotNull("no chunk WAV carrying the Gateway's bytes appeared in the cache", written)
            assertTrue(
                "the chunk file must belong to the generation this replay started " +
                    "(was $generationBefore, now ${service.ttsGeneration}): ${written!!.name}",
                service.ttsGeneration > generationBefore
            )
        }
    }

    /** Returns the chunk-0 file whose bytes match what the stub served. */
    private fun waitUntilChunkFile(expected: ByteArray, timeoutMs: Long = 20_000): java.io.File? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            mainDispatcher.scheduler.runCurrent()
            val match = env.cacheDir.toFile().listFiles { f ->
                f.name.startsWith("tts_chunk_") && f.name.endsWith("_0.wav") &&
                    f.length().toInt() == expected.size && f.readBytes().contentEquals(expected)
            }?.maxByOrNull { it.lastModified() }
            if (match != null) return match
            Thread.sleep(50)
        }
        return null
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            mainDispatcher.scheduler.runCurrent()
            if (condition()) return true
            Thread.sleep(25)
        }
        mainDispatcher.scheduler.runCurrent()
        return condition()
    }

    private companion object {
        /** Name the stub TTS gateway is registered under in Servers for this class. */
        const val PROBE_GATEWAY_SERVER = "ReplayRoutingProbeGateway"
    }
}

/**
 * A loopback stand-in for one persona's Gateway TTS endpoint. It answers any request with a
 * canned WAV (plus optional word timestamps, mirroring the server's `X-Word-Timestamps-B64`
 * header) and records what was asked of it, so a test can tell *which* voice engine served a
 * playback. Ported from upstream's instrumented fixture; `java.util.Base64` replaces Android's.
 */
private class StubTtsServer(
    private val audioBytes: ByteArray,
    private val timestampsJson: String? = null
) : AutoCloseable {
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))

    /** e.g. `http://127.0.0.1:41234` — the app appends `/synthesize` itself. */
    val baseUrl: String = "http://127.0.0.1:${server.localPort}"

    val requestCount = AtomicInteger()

    @Volatile
    var lastPath: String? = null
        private set

    /** The raw multipart body, verbatim — the text/engine/voice fields are asserted on it. */
    @Volatile
    var lastRequestBody: String? = null
        private set

    init {
        val port = server.localPort
        Thread({
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (e: IOException) {
                    break // close() tore the listener down
                }
                Thread({ serve(client) }, "stub-tts-$port").apply { isDaemon = true }.start()
            }
        }, "stub-tts-accept-$port").apply { isDaemon = true }.start()
    }

    override fun close() {
        runCatching { server.close() }
    }

    private fun serve(client: Socket) {
        try {
            client.use { socket ->
                val head = readRequestHead(socket.getInputStream())
                lastPath = head.lineSequence().firstOrNull()?.split(' ')?.getOrNull(1)
                lastRequestBody = readBody(socket.getInputStream(), head)
                requestCount.incrementAndGet()
                writeResponse(socket.getOutputStream())
            }
        } catch (e: IOException) {
            // The test closed the socket; nothing to report.
        }
    }

    private fun readRequestHead(input: InputStream): String {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val next = input.read()
            if (next == -1) break
            head.append(next.toChar())
        }
        return head.toString()
    }

    private fun readBody(input: InputStream, head: String): String {
        val length = head.lineSequence()
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n == -1) break
            read += n
        }
        return String(body, 0, read, Charsets.UTF_8)
    }

    private fun writeResponse(output: OutputStream) {
        val tsHeader = timestampsJson?.let {
            "X-Word-Timestamps-B64: " +
                Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) + "\r\n"
        } ?: ""
        val head = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: audio/wav\r\n" +
            "Content-Length: ${audioBytes.size}\r\n" +
            tsHeader +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.US_ASCII))
        output.write(audioBytes)
        output.flush()
    }
}

/**
 * A real, playable WAV of silence (0.25s, 16-bit mono @ 24kHz). Its size is unique among the
 * app's chunk files, which is what lets the test recognise *its* bytes in the cache without
 * depending on generation numbering.
 */
private fun silentWavBytes(durationMs: Int = 250, sampleRate: Int = 24000): ByteArray {
    val samples = sampleRate * durationMs / 1000
    val dataSize = samples * 2
    fun le32(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
    )
    fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    val out = ByteArrayOutputStream(44 + dataSize)
    out.write("RIFF".toByteArray(Charsets.US_ASCII))
    out.write(le32(36 + dataSize))
    out.write("WAVEfmt ".toByteArray(Charsets.US_ASCII))
    out.write(le32(16))
    out.write(le16(1))              // PCM
    out.write(le16(1))              // mono
    out.write(le32(sampleRate))
    out.write(le32(sampleRate * 2)) // byte rate
    out.write(le16(2))              // block align
    out.write(le16(16))             // bits per sample
    out.write("data".toByteArray(Charsets.US_ASCII))
    out.write(le32(dataSize))
    repeat(dataSize) { out.write(0) }
    return out.toByteArray()
}
