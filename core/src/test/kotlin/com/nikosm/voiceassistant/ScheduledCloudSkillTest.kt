package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A scheduled skill on a cloud persona (PLAN.md §10/M15.1).
 *
 * The point of this test is the REUSE: the request goes through `buildCloudRequest`, the same
 * builder a manual cloud turn uses, so the scheduled framing and the skill's instructions must
 * arrive in the body and the reply must land in the persona's history. A fake provider on loopback
 * answers with the bytes a provider really sends, so this catches a drift between the two routes
 * that a unit test of the runner alone could not.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ScheduledCloudSkillTest {

    private val env = FakeAppEnv(notifier = RecordingNotifier())
    private val store = InMemoryStore()
    private val settings = SettingsManager(env, store, NoImageCodec)
    private lateinit var provider: FakeProviderServer
    private val started = mutableListOf<AssistantService>()

    private fun newService(): AssistantService = AssistantService(
        appEnv = env,
        audioPlayback = SilentAudioPlayback,
        audioDevices = NoAudioDevices,
        tts = NoTextToSpeech,
        audioCapture = NoAudioCapture,
        fileDialogs = NoFileDialogs,
        settingsManager = settings,
    ).also { started += it }

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        provider = FakeProviderServer()
        settings.saveCloudApis(
            listOf(
                CloudApiSetting(
                    name = "Prov",
                    baseUrl = provider.baseUrl,
                    apiKey = "",
                    icon = "C",
                    color = Color(0xFF808080),
                    isEditableUrl = true,
                )
            )
        )
        settings.savePersonas(
            listOf(
                Persona(
                    name = "Cloudie",
                    themeColor = Color(0xFF4ADE80),
                    model = "[Prov] fake-model",
                    systemPrompt = "You are a warm assistant.",
                    isCloud = true,
                )
            )
        )
        settings.saveSkills(
            listOf(Skill(id = "skill-1", name = "Morning briefing", instructions = "Say good morning in one line."))
        )
    }

    @After
    fun tearDown() {
        started.forEach { runCatching { it.shutdown() } }
        started.clear()
        provider.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `a scheduled cloud run goes through the cloud builder and lands in the persona history`() = runTest {
        provider.reply(
            """{"choices":[{"message":{"content":"Good morning!"}}],
               "usage":{"prompt_tokens":10,"completion_tokens":3}}"""
        )
        val service = newService()
        service.start()
        runCurrent()
        service.awaitPersonaHistoryIo()

        service.runScheduledSkill(ScheduledSkill(skillId = "skill-1", personaName = "Cloudie"))

        val request = provider.turns.single()
        assertTrue("the OpenAI-compatible route: ${request.path}", request.path.endsWith("/chat/completions"))
        assertTrue("the scheduled framing reaches the provider", request.body.contains("scheduled skill"))
        assertTrue("the skill's instructions reach the provider", request.body.contains("Say good morning"))

        // The persona is the active one, so the sink wrote to the live transcript.
        assertEquals("Good morning!", service._messages.value.last().text)
        assertEquals("assistant", service._messages.value.last().role)

        assertTrue(
            "a scheduled cloud turn is a real paid turn and must land on the card",
            (env.notifier as RecordingNotifier).posted.any { it.title == "Cloudie" },
        )
    }

    @Test
    fun `a gateway-only persona is refused without a request`() = runTest {
        settings.savePersonas(
            listOf(
                Persona(
                    name = "Cloudie",
                    themeColor = Color(0xFF4ADE80),
                    model = "bare-model",   // neither a configured Ollama server nor a provider
                    systemPrompt = "You are a warm assistant.",
                )
            )
        )
        val service = newService()
        service.start()
        runCurrent()
        service.awaitPersonaHistoryIo()

        service.runScheduledSkill(ScheduledSkill(skillId = "skill-1", personaName = "Cloudie"))

        assertTrue("no request may leave for a route that cannot carry the run", provider.turns.isEmpty())
    }
}
