package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The output-side amplitude the Live HUD reads, driven through the two routes that can measure it.
 *
 * The measurement itself is [AudioEnvelopeTest]'s job. This test is about the wiring: a local
 * turn's samples and a replayed WAV must publish a level while the player is mid-file, and the
 * level must be cleared when the playback ends rather than left as the last frame. The system TTS
 * route is deliberately absent — it owns its playback and has nothing to measure — and the HUD's
 * fallback for that case is a UI concern, not a service state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpeechAmplitudeTest {

    private val mainDispatcher = StandardTestDispatcher()
    private val env = FakeAppEnv(notifier = RecordingNotifier())
    private val settings = SettingsManager(env, InMemoryStore(), NoImageCodec)
    private val services = mutableListOf<AssistantService>()

    private lateinit var playback: ProbePlayback
    private lateinit var service: AssistantService

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        playback = ProbePlayback()
        service = AssistantService(
            appEnv = env,
            audioPlayback = playback,
            audioDevices = NoAudioDevices,
            tts = ToneSpeech(),
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
    fun `a local turn publishes its envelope and clears it at the end`() = runTest {
        val persona = persona(VoiceMode.BUNDLED_ESPEAK)
        service.currentPersonaName = persona.name
        service.messagesOwnerName = persona.name

        service.speakWithEspeak("hello there", persona)
        await("the local speech never reached playback") { playback.playing }

        playback.handle.positionMs = 1_000
        mainDispatcher.scheduler.advanceTimeBy(150)
        mainDispatcher.scheduler.runCurrent()

        // The fake's samples are a half-scale square: RMS 0.5 of full scale, and the envelope's
        // square-root mapping puts it at ~0.707. A value near zero would mean the poller never
        // reached the envelope; the exact number is AudioEnvelopeTest's contract.
        val level = service.speechAmplitude.value
        assertTrue("expected a measured level, was $level", level > 0.5f)

        playback.finish(PlaybackEnd.Finished)
        mainDispatcher.scheduler.advanceTimeBy(200)
        mainDispatcher.scheduler.runCurrent()
        assertEquals("the end of a playback must not leave the last frame standing", 0f, service.speechAmplitude.value, 0.0f)
    }

    @Test
    fun `a replayed wav publishes its envelope and clearing is owned by the end`() = runTest {
        val persona = persona(VoiceMode.GATEWAY)
        service.currentPersonaName = persona.name
        service.messagesOwnerName = persona.name

        val pcm = squareWave(samples = 32_000, sampleRate = 16_000)
        val file = Path.of(env.cacheDir.toString(), "live-hud-replay.wav")
        Files.createDirectories(file.parent)
        Files.write(file, WavFile.header(pcm.size, 16_000) + pcm)

        service.playAudioFile(file)
        // The read and the parser run on IO; this test's dispatcher only drives the state writes.
        await("the replay never measured its file") { service.speechAmplitude.value > 0.5f }

        playback.handle.positionMs = 1_500
        mainDispatcher.scheduler.advanceTimeBy(150)
        mainDispatcher.scheduler.runCurrent()
        assertTrue("the replay's level should follow its playback", service.speechAmplitude.value > 0.5f)

        playback.finish(PlaybackEnd.Stopped)
        mainDispatcher.scheduler.advanceTimeBy(200)
        mainDispatcher.scheduler.runCurrent()
        assertEquals(0f, service.speechAmplitude.value, 0.0f)
    }

    @Test
    fun `stopAudio clears a level that is still being published`() = runTest {
        val persona = persona(VoiceMode.BUNDLED_ESPEAK)
        service.currentPersonaName = persona.name
        service.messagesOwnerName = persona.name
        service.speakWithEspeak("hello there", persona)
        await("the local speech never reached playback") { playback.playing }

        playback.handle.positionMs = 1_000
        mainDispatcher.scheduler.advanceTimeBy(150)
        mainDispatcher.scheduler.runCurrent()
        assertTrue(service.speechAmplitude.value > 0.5f)

        service.stopAudio()
        assertEquals(
            "a stopped turn must not leave its last frame driving the HUD",
            0f,
            service.speechAmplitude.value,
            0.0f,
        )
    }

    private fun persona(mode: VoiceMode) = Persona(
        name = "SpeechAmplitudeProbe",
        themeColor = Color(0xFF4ADE80),
        model = "",
        systemPrompt = "",
        voiceMode = mode,
        targetLanguage = "English",
    )

    private fun await(failure: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            mainDispatcher.scheduler.runCurrent()
            Thread.sleep(10)
        }
        assertTrue(failure, condition())
    }

    /** Signed 16-bit little-endian at half full scale — loud enough to clear the HUD's floor. */
    private fun squareWave(samples: Int, sampleRate: Int): ByteArray {
        val bytes = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val value = if (i % 2 == 0) 16_384 else -16_384
            bytes[i * 2] = (value and 0xFF).toByte()
            bytes[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    private class ToneSpeech : TextToSpeech {
        override val systemReady: Boolean = false
        override fun speakSystem(text: String, utteranceId: String, onEnd: (PlaybackEnd) -> Unit): Boolean = false
        override fun stopSystem() = Unit
        override fun synthesizeLocal(text: String, voice: String): SpeechSamples {
            // 16-bit LE, alternating ±16384: a half-scale square, 2 seconds at 16 kHz.
            val pattern = byteArrayOf(0x00, 0x40, 0x00, 0xC0.toByte())
            return SpeechSamples(ByteArray(64_000) { pattern[it % 4] }, 16_000)
        }
        override fun releaseLocal() = Unit
    }

    private class ProbePlayback : AudioPlayback {
        class Handle : PlaybackHandle {
            override var positionMs: Long = 0
            override var durationMs: Long = 2_000
            override fun stop() = Unit
        }

        val handle = Handle()
        var playing = false
        private var onEndCallback: ((PlaybackEnd) -> Unit)? = null

        override fun play(file: Path, target: AudioDevice?, onEnd: (PlaybackEnd) -> Unit): PlaybackHandle {
            playing = true
            onEndCallback = onEnd
            return handle
        }

        override fun playPcm(
            samples: ByteArray,
            sampleRate: Int,
            target: AudioDevice?,
            onEnd: (PlaybackEnd) -> Unit,
        ): PlaybackHandle {
            playing = true
            onEndCallback = onEnd
            return handle
        }

        fun finish(end: PlaybackEnd) {
            playing = false
            onEndCallback?.invoke(end)
        }

        override fun stopAll() {
            playing = false
        }
    }
}
