package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins the Stage-2 (playback fraction / word timestamps / reveal-restart) state contract that
 * the three fixes in this change set rest on (ported from upstream's instrumented test in M7):
 *
 *  1. **Fraction/timestamp leak.** `_ttsPlaybackFraction` and `_ttsWordTimestamps` are
 *     per-sequence state, not per-session state: [stopAudio] must clear them. Otherwise a
 *     value left over from an earlier Gateway (chunked) turn satisfies the UI's karaoke gate
 *     (`state == SPEAKING && ttsPlaybackFraction != null && voiceDuration > 0`) during an
 *     unrelated later device-voice (eSpeak/System TTS) playback — suppressing that response's
 *     typewriter reveal and/or painting a nonsense highlight computed from a fraction that
 *     belongs to a response which finished playing long ago.
 *  2. **Replay reveal (device voice).** Replaying a device-voice message must re-arm the
 *     classic timed reveal ([RevealRestartRequest]), and the replay entry point must itself
 *     clear the per-sequence state — System TTS never routes through [stopAudio]
 *     (`speakTextOnDevice` requests focus directly), so relying on that teardown alone would
 *     leave a previous turn's fraction/duration readable as this replay's own. The same entry
 *     point clears the state for a LIVE System-TTS turn (the one playback path with no
 *     stopAudio() in front of it).
 *  3. **Scope guard.** A chunked Gateway replay must NOT arm the classic reveal: that path
 *     owns the response with its own karaoke highlight (and the UI deliberately pins the text
 *     in full while a fraction is live).
 *
 * The port change is only the harness: a fresh service over an in-memory store per test, and
 * Main pumped explicitly where the replay hops onto the service scope.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class Stage2RevealStateTest {

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
        services.clear()
        Dispatchers.resetMain()
    }

    /**
     * Fix 1: the teardown every playback entry point funnels through must clear the
     * per-sequence Stage-2 state. Mutation: removing the two clears from stopAudio() fails
     * this — which is precisely the leak that let an earlier Gateway turn's fraction bleed
     * into a later device-voice response in the same session.
     */
    @Test
    fun `stop audio clears the stage2 fraction and timestamps`() {
        primeChunkedStage2State()

        service.stopAudio()

        assertStage2StateCleared("stopAudio() teardown")
    }

    /**
     * Fix 1 + Fix 3 at the entry point that bypasses stopAudio(): a device-voice replay must
     * arm the reveal for the replayed (last) message AND clear the per-sequence state, so the
     * replay cannot inherit the previous response's fraction (stale highlight / suppressed
     * reveal) or its duration.
     */
    @Test
    fun `device voice replay arms the reveal and clears stale stage2 state`() {
        val message = ChatMessage("assistant", "Replay reveal probe: device voice, fresh reveal")
        service._messages.value = listOf(ChatMessage("user", "probe"), message)
        primeChunkedStage2State()
        // Muted service: speakTextOnDevice() bails before synthesizing (its own landing is
        // covered elsewhere), so the test asserts routing/state without speaking aloud.
        if (!service.silenced.value) service.toggleSilence()
        val tokenBefore = service._revealRestartRequest.value?.token ?: 0L

        service.replayMessageAudio(message, SYSTEM_TTS_PROBE_PERSONA)

        val request = waitForRevealRestartAbove(tokenBefore)
        assertNotNull(
            "no RevealRestartRequest was published for a device-voice replay — the UI would " +
                "show the message in full with no synced reveal (the reported behavior)",
            request
        )
        assertEquals(
            "the request must address the replayed message (it is the last one here)",
            1L,
            request!!.messageIndex.toLong()
        )
        assertTrue(
            "each request needs a fresh token, otherwise a second replay of the same message " +
                "could not re-arm the reveal: ${request.token} vs $tokenBefore",
            request.token > tokenBefore
        )
        assertStage2StateCleared("device-voice replay entry")
    }

    /**
     * Fix 1b: System TTS is the one playback entry point with no stopAudio() in front of it
     * (`speakTextOnDevice` requests focus itself), so it must clear the per-sequence state on
     * its own. Mutation: drop the three clears at the top of speakTextOnDevice() and this
     * fails — leaving an earlier Gateway turn's fraction plus an earlier eSpeak turn's duration
     * to satisfy the UI karaoke gate during this playback.
     */
    @Test
    fun `system tts entry clears stale stage2 state`() {
        primeChunkedStage2State()
        if (!service.silenced.value) service.toggleSilence()

        // Muted engine: it bails right after the clears, which is the point — the landing must
        // be clean whether or not anything is actually spoken.
        service.speakTextOnDevice("System TTS entry probe")

        assertStage2StateCleared("System TTS entry (speakTextOnDevice)")
    }

    /**
     * Fix 3 scope guard: the chunked Gateway replay re-synthesizes through its own karaoke
     * pipeline, so it must not publish a reveal-restart request — the UI would run a
     * typewriter over a response that is being karaoke-highlighted instead.
     */
    @Test
    fun `gateway replay does not arm the classic reveal`() {
        val message = ChatMessage("assistant", "Gateway replay probe: karaoke owns this one")
        service._messages.value = listOf(ChatMessage("user", "probe"), message)
        val tokenBefore = service._revealRestartRequest.value?.token ?: 0L

        // Closed loopback port: synthesis fails fast (allowGatewayFailover is off by
        // default), so nothing is played — the assertions below are about state, not audio.
        service.replayMessageAudio(message, GATEWAY_PROBE_PERSONA)
        val deadline = System.currentTimeMillis() + 1_500
        while (System.currentTimeMillis() < deadline) {
            mainDispatcher.scheduler.runCurrent()
            Thread.sleep(20)
        }

        assertEquals(
            "a chunked Gateway replay must not publish a reveal-restart request (its karaoke " +
                "highlight is the dynamic element)",
            tokenBefore,
            service._revealRestartRequest.value?.token ?: 0L
        )
        assertStage2StateCleared("gateway replay entry (its own stopAudio() teardown)")
    }

    /** Live fraction/timestamps exactly as the chunked player leaves them mid-response. */
    private fun primeChunkedStage2State() {
        service._ttsPlaybackFraction.value = 0.42f
        service._ttsWordTimestamps.value = """[{"word":"probe","start":0.0,"end":0.5}]"""
        service._voiceDuration.value = 12_345
        assertNotNull(
            "precondition: a live fraction must be observable",
            service.ttsPlaybackFraction.value
        )
        assertNotNull(
            "precondition: timestamps must be observable",
            service.ttsWordTimestamps.value
        )
    }

    private fun assertStage2StateCleared(what: String) {
        assertNull(
            "$what — a fraction from an earlier Gateway turn survived: it satisfies the UI " +
                "karaoke gate (SPEAKING && fraction != null && voiceDuration > 0) during an " +
                "unrelated device-voice playback, suppressing the reveal / painting a nonsense " +
                "highlight",
            service.ttsPlaybackFraction.value
        )
        assertNull("$what — stale word timestamps survived", service.ttsWordTimestamps.value)
        assertEquals("$what — stale duration survived", 0L, service.voiceDuration.value.toLong())
    }

    /** Polls for the replay's request — the emission happens on the service's Main dispatcher. */
    private fun waitForRevealRestartAbove(
        tokenBefore: Long,
        timeoutMs: Long = 5_000
    ): RevealRestartRequest? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            mainDispatcher.scheduler.runCurrent()
            val current = service._revealRestartRequest.value
            if (current != null && current.token > tokenBefore) return current
            Thread.sleep(25)
        }
        mainDispatcher.scheduler.runCurrent()
        return null
    }

    private companion object {
        // Voice settings only: the muted service never reaches an engine, so nothing here is
        // audible or network-dependent.
        val SYSTEM_TTS_PROBE_PERSONA = Persona(
            name = "RevealRestartProbe",
            themeColor = Color(0xFF4ADE80),
            model = "probe-model",
            systemPrompt = "",
            voiceMode = VoiceMode.SYSTEM_TTS
        )

        val GATEWAY_PROBE_PERSONA = Persona(
            name = "GatewayRevealProbe",
            themeColor = Color(0xFF60A5FA),
            model = "probe-model",
            systemPrompt = "",
            backendUrl = "http://127.0.0.1:9",
            voiceMode = VoiceMode.GATEWAY
        )
    }
}
