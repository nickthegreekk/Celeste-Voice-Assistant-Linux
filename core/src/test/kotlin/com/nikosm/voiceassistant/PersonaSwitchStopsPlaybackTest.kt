package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
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
 * Pins the "a persona switch / clear-chat must stop the outgoing turn" contract (ported from
 * upstream's instrumented test in M7): [AssistantService.switchPersona] and
 * [AssistantService.clearMessages] both call `stopEverything()` before doing anything else, for
 * two reasons verified separately here:
 *
 *  1. **Audio.** An in-flight chunked Gateway TTS sequence only ever halts when `ttsGeneration`
 *     advances (every chunk transition re-checks it), and nothing in a persona switch or a
 *     clear-chat advanced it — so the previous persona's response kept SPEAKING underneath the
 *     new persona's screen.
 *  2. **State republishing.** The chunked sequencer's progress poller republishes
 *     `_ttsPlaybackFraction` every ~100ms while its playback is live. It knows nothing about
 *     personas, so with the sequence still alive it kept writing that fraction — measured against
 *     a response that is no longer on screen — while the UI had already swapped to the new
 *     persona's message list. The regression assertion is therefore not just "the player went
 *     null" but "nothing republishes afterwards": the virtual clock is advanced past many poll
 *     ticks after the switch, so a surviving poller would write into the window.
 *
 * The port change is the harness: the real `MediaPlayer` becomes [FakeAudioPlayback] with a real
 * chunk duration, and the stub gateway is a loopback server. The fake now honours seam 2's
 * contract that a stopped playback REPORTS `Stopped` — without which the sequencer's playback
 * would park forever instead of unwinding.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PersonaSwitchStopsPlaybackTest {

    private val env = FakeAppEnv(notifier = RecordingNotifier())
    private val store = InMemoryStore()
    private val settings = SettingsManager(env, store, NoImageCodec)
    private val mainDispatcher = StandardTestDispatcher()
    private lateinit var playback: FakeAudioPlayback
    private val services = mutableListOf<AssistantService>()

    private lateinit var service: AssistantService

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        playback = FakeAudioPlayback().apply { defaultDurationMs = 12_000 }
        service = AssistantService(
            appEnv = env,
            audioPlayback = playback,
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
     * The core fix: a Gateway (chunked) sequence is genuinely playing, then the user switches
     * personas. Asserts (a) the playback objects are released and the state lands IDLE, (b) the
     * per-sequence Stage-2 state is cleared, (c) the chat generation advanced (Stop semantics, so
     * the discarded turn cannot append), (d) the outgoing persona's history was still saved and
     * the incoming persona's list loaded, and (e) nothing republishes playback state afterwards.
     */
    @Test
    fun `persona switch mid gateway playback stops audio and halts state republishing`() {
        val outgoingMessages = listOf(
            ChatMessage("user", "outgoing persona turn"),
            ChatMessage("assistant", "outgoing persona reply")
        )
        StubGatewayTtsServer().use { stub ->
            setActiveConversation(OUTGOING_PROBE_PERSONA, outgoingMessages)
            startGatewaySequenceAndAwaitPlayback(stub, OUTGOING_PROBE_PERSONA.copy(backendUrl = stub.url))

            // Preconditions: a genuinely live chunked sequence — exactly the state that used to
            // survive the switch.
            assertEquals(
                "precondition: the chunked pipeline must be SPEAKING",
                AssistantState.SPEAKING,
                service.assistantState.value
            )
            assertTrue("precondition: a live playback must exist", playback.isPlaying)
            assertTrue("precondition: the mode field must exist", service.ttsPlaybackFraction.value != null)
            assertTrue("precondition: a live duration must exist", service.voiceDuration.value > 0)

            val generationBefore = service.ttsGeneration
            val chatSeqBefore = service.currentChatRequestSeq()

            service.switchPersona(INCOMING_PROBE_PERSONA)

            // (a) audio actually stopped: stopAudio() releases the playback and stopEverything()
            // lands the state.
            assertTrue("the chunked sequence survived the persona switch", !playback.isPlaying)
            assertEquals(
                "the persona switch left the service SPEAKING",
                AssistantState.IDLE,
                service.assistantState.value
            )
            // (b)
            assertNull("a stale playback fraction survived the persona switch", service.ttsPlaybackFraction.value)
            assertNull("stale word timestamps survived the persona switch", service.ttsWordTimestamps.value)
            assertEquals(
                "a stale voice duration survived the persona switch",
                0L,
                service.voiceDuration.value.toLong()
            )
            // (c) Stop semantics, not just stopAudio
            assertTrue(
                "the switch did not bump ttsGeneration ($generationBefore -> " +
                    "${service.ttsGeneration}) — the sequence's own generation checks are what halt it",
                service.ttsGeneration > generationBefore
            )
            assertTrue(
                "the switch did not bump the chat sequence — a discarded turn could still append " +
                    "its reply into the outgoing persona's history",
                service.currentChatRequestSeq() > chatSeqBefore
            )
            // (d)
            assertTrue(
                "the outgoing persona's history was NOT saved by the switch",
                waitFor { settings.getPersonaMessages(OUTGOING_PROBE_PERSONA.name) == outgoingMessages }
            )
            assertEquals(
                "the switch did not re-point the active persona",
                INCOMING_PROBE_PERSONA.name,
                service.currentPersonaName
            )
            assertTrue(
                "the incoming persona's message list was not loaded",
                waitFor { service._messages.value.isEmpty() }
            )

            // (e) The regression that motivated the fix: the now-dead sequence's poll coroutine
            // must not keep republishing playback state. Advance the virtual clock past many poll
            // ticks — with the sequence still alive this window would fill with fractions measured
            // against a response the UI no longer shows.
            mainDispatcher.scheduler.advanceTimeBy(1_500)
            mainDispatcher.scheduler.runCurrent()
            assertNull(
                "playback state was REPUBLISHED after the persona switch — the dead sequence's " +
                    "poll coroutine is still writing against the new persona's list",
                service.ttsPlaybackFraction.value
            )
            assertNull("playback state was republished after the persona switch", service.ttsWordTimestamps.value)
            assertEquals(
                "the service did not stay IDLE after the persona switch",
                AssistantState.IDLE,
                service.assistantState.value
            )
            assertTrue("the playback came back after the persona switch", !playback.isPlaying)
        }
    }

    /**
     * Same contract through the other "move on" gesture. Clear-chat empties the list the
     * sequence was speaking for, so an in-flight sequence must stop for exactly the same two
     * reasons plus one more: a discarded turn must not append its reply into the history the
     * user just cleared.
     */
    @Test
    fun `clear messages mid gateway playback stops audio and republishing`() {
        val outgoingMessages = listOf(
            ChatMessage("user", "turn that is about to be cleared"),
            ChatMessage("assistant", "response that must stop speaking")
        )
        StubGatewayTtsServer().use { stub ->
            setActiveConversation(OUTGOING_PROBE_PERSONA, outgoingMessages)
            // Fixture guard: with a real stored history the "persisted history is empty"
            // assertion below is an observation rather than the trivially-null entry of a
            // never-written persona.
            settings.savePersonaMessages(OUTGOING_PROBE_PERSONA.name, outgoingMessages)
            startGatewaySequenceAndAwaitPlayback(stub, OUTGOING_PROBE_PERSONA.copy(backendUrl = stub.url))
            val generationBefore = service.ttsGeneration

            service.clearMessages()

            assertTrue("clear-chat left the chunked sequence playing", !playback.isPlaying)
            assertEquals(
                "clear-chat left the service SPEAKING",
                AssistantState.IDLE,
                service.assistantState.value
            )
            assertNull("clear-chat left a stale fraction", service.ttsPlaybackFraction.value)
            assertNull("clear-chat left stale timestamps", service.ttsWordTimestamps.value)
            assertTrue(
                "clear-chat did not bump ttsGeneration ($generationBefore -> ${service.ttsGeneration})",
                service.ttsGeneration > generationBefore
            )
            assertEquals("clear-chat did not empty the message list", 0, service._messages.value.size)
            assertTrue(
                "clear-chat did not empty the persona's persisted history",
                waitFor {
                    settings.getPersonaMessages(OUTGOING_PROBE_PERSONA.name).orEmpty().isEmpty()
                }
            )

            mainDispatcher.scheduler.advanceTimeBy(1_000)
            mainDispatcher.scheduler.runCurrent()
            assertNull(
                "the cleared sequence republished playback state",
                service.ttsPlaybackFraction.value
            )
            assertEquals(AssistantState.IDLE, service.assistantState.value)
            assertTrue("clear-chat restarted a playback", !playback.isPlaying)
        }
    }

    /**
     * The common case must be untouched: switching with nothing playing keeps doing exactly what
     * it did before — save the outgoing history, load the incoming one, re-point the active
     * persona — and leaves the service IDLE with no playback.
     */
    @Test
    fun `persona switch with nothing playing keeps the normal save and load behaviour`() {
        val outgoingMessages = listOf(ChatMessage("user", "idle switch turn"))
        val incomingStored = listOf(
            ChatMessage("user", "incoming stored turn"),
            ChatMessage("assistant", "incoming stored reply")
        )
        setActiveConversation(OUTGOING_PROBE_PERSONA, outgoingMessages)
        // Fixture guard: the incoming persona must have real stored history for "loaded" to
        // mean anything.
        settings.savePersonaMessages(INCOMING_PROBE_PERSONA.name, incomingStored)

        val stateBefore = service.assistantState.value
        val generationBefore = service.ttsGeneration

        service.switchPersona(INCOMING_PROBE_PERSONA)

        assertTrue(
            "the outgoing persona's history was not saved by the switch",
            waitFor { settings.getPersonaMessages(OUTGOING_PROBE_PERSONA.name) == outgoingMessages }
        )
        assertTrue(
            "the incoming persona's stored history was not loaded",
            waitFor { service._messages.value == incomingStored }
        )
        assertEquals(
            "an idle persona switch changed the assistant state",
            stateBefore,
            service.assistantState.value
        )
        assertTrue("an idle persona switch created a playback", playback.played.isEmpty())
        assertNull("an idle persona switch published a fraction", service.ttsPlaybackFraction.value)
        assertEquals(
            "the switch did not re-point the active persona",
            INCOMING_PROBE_PERSONA.name,
            service.currentPersonaName
        )
        // Documented, intended: the stop-everything pass runs unconditionally, so its counter
        // bump is observable even with nothing playing.
        assertEquals(
            "an idle switch should still perform exactly one stop-everything pass",
            generationBefore + 1,
            service.ttsGeneration
        )
    }

    /** Points the service at [persona] with [messages] in memory — the pre-switch world. */
    private fun setActiveConversation(persona: Persona, messages: List<ChatMessage>) {
        service.currentPersonaName = persona.name
        service.messagesOwnerName = persona.name
        service._messages.value = messages
    }

    /**
     * Starts the real chunked Gateway pipeline against [stub] and waits for genuinely live
     * playback (SPEAKING + a playing handle + a published fraction), so the tests below interrupt
     * a sequence that is actually running rather than an assumed one.
     */
    private fun startGatewaySequenceAndAwaitPlayback(stub: StubGatewayTtsServer, persona: Persona) {
        // M1's resolver refuses to speak for a Backend URL that maps to no configured Servers
        // entry, so the stub must be registered as the persona's gateway.
        service._serverBases.value = listOf(ServerConfig(name = PROBE_GATEWAY_SERVER, url = stub.url))
        service.synthesizeGatewayTextForPlayback(SINGLE_CHUNK_TEXT, persona) { /* unused */ }
        awaitCondition(20_000, "the stub-backed chunked Gateway sequence never reached SPEAKING") {
            service.assistantState.value == AssistantState.SPEAKING && playback.isPlaying
        }
        awaitCondition(5_000, "the chunked player never published a live playback fraction") {
            service.ttsPlaybackFraction.value != null
        }
        assertTrue(
            "the stub's /synthesize endpoint was never called — the sequence never really started",
            stub.requestCount.get() > 0
        )
    }

    private fun waitFor(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            mainDispatcher.scheduler.runCurrent()
            if (condition()) return true
            Thread.sleep(20)
        }
        mainDispatcher.scheduler.runCurrent()
        return condition()
    }

    private fun awaitCondition(timeoutMs: Long, failure: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            mainDispatcher.scheduler.runCurrent()
            if (condition()) return
            Thread.sleep(25)
        }
        mainDispatcher.scheduler.runCurrent()
        assertTrue(
            "$failure (state=${service.assistantState.value}, playing=${playback.isPlaying}, " +
                "fraction=${service.ttsPlaybackFraction.value})",
            condition()
        )
    }

    /**
     * Minimal HTTP/1.1 stub answering `POST /synthesize` with a silent PCM WAV plus the Stage-2
     * header, mirroring the real gateway's contract as `synthesizeWithGateway` reads it.
     * Ported from upstream's instrumented fixture; `java.util.Base64` replaces Android's.
     */
    private class StubGatewayTtsServer(
        private val seconds: Int = 12,
        private val sampleRate: Int = 22_050
    ) : AutoCloseable {

        private val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        private val wavBytes = buildSilentPcmWav(seconds, sampleRate)
        private val worker: Thread

        val url: String get() = "http://127.0.0.1:${socket.localPort}"
        val requestCount = AtomicInteger()

        init {
            worker = thread(isDaemon = true, name = "stub-gateway-tts") {
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
            // Drain the multipart body: leaving it unread makes the client see a reset
            // connection instead of a response.
            var remaining = contentLength.toLong()
            val buffer = ByteArray(8_192)
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read <= 0) break
                remaining -= read
            }

            val output = connection.getOutputStream()
            val isSynthesizePost = headerText.startsWith("POST") && headerText.contains("/synthesize")
            if (!isSynthesizePost) {
                output.write(
                    "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .toByteArray()
                )
                output.flush()
                return
            }

            requestCount.incrementAndGet()
            val timestampsJson = """[{"word":"probe","start":0.0,"end":1.0}]"""
            val timestampsB64 = Base64.getEncoder()
                .encodeToString(timestampsJson.toByteArray(Charsets.UTF_8))
            output.write(
                (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: audio/wav\r\n" +
                        "Content-Length: ${wavBytes.size}\r\n" +
                        "X-Word-Timestamps-B64: $timestampsB64\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray()
            )
            output.write(wavBytes)
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

        companion object {
            /**
             * Canonical 44-byte RIFF/WAVE header for 16-bit PCM mono. The data section is
             * allocated zero-filled, and zero-valued 16-bit PCM samples ARE digital silence.
             */
            private fun buildSilentPcmWav(seconds: Int, sampleRate: Int): ByteArray {
                val channels = 1
                val bitsPerSample = 16
                val blockAlign = channels * bitsPerSample / 8
                val dataSize = seconds * sampleRate * blockAlign
                val wav = ByteArray(44 + dataSize)

                fun ascii(offset: Int, text: String) {
                    text.forEachIndexed { i, c -> wav[offset + i] = c.code.toByte() }
                }

                fun intLe(offset: Int, value: Int) {
                    wav[offset] = (value and 0xFF).toByte()
                    wav[offset + 1] = ((value shr 8) and 0xFF).toByte()
                    wav[offset + 2] = ((value shr 16) and 0xFF).toByte()
                    wav[offset + 3] = ((value shr 24) and 0xFF).toByte()
                }

                fun shortLe(offset: Int, value: Int) {
                    wav[offset] = (value and 0xFF).toByte()
                    wav[offset + 1] = ((value shr 8) and 0xFF).toByte()
                }

                ascii(0, "RIFF")
                intLe(4, 36 + dataSize)
                ascii(8, "WAVE")
                ascii(12, "fmt ")
                intLe(16, 16)
                shortLe(20, 1)
                shortLe(22, channels)
                intLe(24, sampleRate)
                intLe(28, sampleRate * blockAlign)
                shortLe(32, blockAlign)
                shortLe(34, bitsPerSample)
                ascii(36, "data")
                intLe(40, dataSize)
                return wav
            }
        }
    }

    private companion object {
        /**
         * One long sentence, comfortably over `splitIntoTtsChunks`' default 80-char minimum
         * and with no internal sentence boundary, so the pipeline produces exactly ONE chunk.
         */
        const val SINGLE_CHUNK_TEXT =
            "Probe voice response for the persona switch instrumentation test long enough to " +
                "exceed the minimum chunk length so the pipeline keeps a single chunk playing " +
                "without any sentence boundary to split it"

        fun probePersona(name: String, backendUrl: String = "") = Persona(
            name = name,
            themeColor = Color(0xFF123456),
            model = "",
            systemPrompt = "Instrumentation probe persona",
            backendUrl = backendUrl,
            voiceMode = VoiceMode.GATEWAY,
            voiceEngine = "kokoro",
            kokoroVoice = "af_heart",
            targetLanguage = "English"
        )

        /** Name the stub TTS gateway is registered under in Servers for this class. */
        const val PROBE_GATEWAY_SERVER = "StopOnSwitchProbeGateway"

        /** The persona whose turn is in flight when the user switches away. */
        val OUTGOING_PROBE_PERSONA = probePersona("StopOnSwitchProbeOutgoing")

        /** The persona the user switches to mid-playback. */
        val INCOMING_PROBE_PERSONA = probePersona("StopOnSwitchProbeIncoming")
    }
}
