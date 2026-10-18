package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
 * The doubled-stamp report, end to end over a loopback Ollama (ported from upstream's
 * instrumented test in M7).
 *
 * The stub replies with a reply that IMITATES the stamp format the app just showed it
 * (`[stamp][stamp] hello there`) — the shape a real local model produces when it copies the
 * transcript. Two things are then pinned:
 *
 *  1. what the app STORES and displays is the bare reply, and
 *  2. the next request carries EXACTLY ONE stamp for that message.
 *
 * The second is the one that matters to a user: before the fix the stored echo was stamped
 * again on injection, so the model saw `[real][echo][echo] hello` and could keep copying.
 *
 * The pure half (recognizer, stripper, display hold) is `ModelStampEchoTest` on the JVM. The
 * port change here is only the harness: `SteppedNdjsonServer` captures each request body, and
 * Main is a `StandardTestDispatcher` pumped explicitly.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MessageStampEchoTest {
    private val mainDispatcher = StandardTestDispatcher()


    private val env = FakeAppEnv(notifier = RecordingNotifier())
    private val store = InMemoryStore()
    private val settings = SettingsManager(env, store, NoImageCodec)
    private val services = mutableListOf<AssistantService>()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        services.forEach { runCatching { it.shutdown() } }
        drainServiceScopes(services, mainDispatcher.scheduler)
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
    ).also { services += it }

    @Test
    fun `a reply that echoes the stamp is stored clean and re-stamped exactly once`() = runTest {
        SteppedNdjsonServer().use { server ->
            val service = newService()
            service._ollamaBaseUrls.value = listOf(ServerConfig(PROBE_SERVER, server.url))
            service.addPersona(PROBE_PERSONA)
            service.currentPersonaName = PROBE_PERSONA.name
            service.messagesOwnerName = PROBE_PERSONA.name
            // The middle turn carries a stray stamp ALREADY IN ITS STORED TEXT — what an older
            // build, or an echo that reached the transcript some other way, leaves behind.
            // Nothing migrates it; the point is that injection must not stack on top of it either.
            service._messages.value = listOf(
                ChatMessage("user", "first question", timestamp = System.currentTimeMillis()),
                ChatMessage("assistant", STALE_STAMPED_REPLY, timestamp = System.currentTimeMillis())
            )

            // ---- turn 1: the model echoes the stamp ----
            val before = service._messages.value.size
            service.sendTextMessageToServer("first question", PROBE_PERSONA)
            runCurrent()
            val first = server.connection(0)
            assertTrue(
                "the first turn never reached the stub",
                first.requestArrived.await(20, TimeUnit.SECONDS)
            )
            first.sendChunk(ECHO)
            first.finishStream()
            awaitAnswer(service, before)

            val stored = service._messages.value.last { it.role == "assistant" }.text
            assertEquals(
                "the model's own stamps must never reach the transcript or the screen: [$stored]",
                REPLY, stored
            )

            // ---- turn 2: what the app actually SENDS ----
            val before2 = service._messages.value.size
            service.sendTextMessageToServer("second question", PROBE_PERSONA)
            runCurrent()
            val second = server.connection(1)
            assertTrue(
                "the second turn never reached the stub",
                second.requestArrived.await(20, TimeUnit.SECONDS)
            )
            second.sendChunk(ECHO)
            second.finishStream()
            awaitAnswer(service, before2)

            val messages = Json.parseToJsonElement(second.requestBody).jsonObject["messages"]!!.jsonArray
            val sent = (1 until messages.size - 1).map {
                messages[it].jsonObject["content"]!!.jsonPrimitive.content
            }
            // (a) the OLD stored reply: injection must add exactly one stamp to it, never two.
            val stale = sent.first { it.endsWith(STALE_REPLY) }
            assertEquals(
                "a stored reply that already carries a stray stamp must be re-stamped ONCE, " +
                    "not stacked: $stale",
                1, STAMP_AT_START.findAll(stale).count()
            )
            assertEquals(STALE_REPLY, stale.substringAfter("] "))

            // (b) the fresh echo: stored clean, and stamped once on the way out.
            val answer = sent.first { it.endsWith(REPLY) }
            val stamps = STAMP_AT_START.findAll(answer).count()
            assertEquals(
                "the model must be shown exactly one true stamp per message, got: $answer",
                1, stamps
            )
            assertEquals(
                "and the reply behind it must be the clean one, got: $answer",
                REPLY, answer.substringAfter("] ")
            )
            val systemPrompt = messages[0].jsonObject["content"]!!.jsonPrimitive.content
            assertTrue(
                "a stamped Direct-Ollama request must tell the model who owns the brackets",
                systemPrompt.contains("Never write a stamp")
            )
        }
    }

    /**
     * Waits for a FINISHED answer, not merely for the count of assistant messages to grow: the
     * streaming placeholder is appended empty on the first chunk, so counting returns instantly
     * and asserts against an empty bubble while the reply is still arriving.
     *
     * [before] is a message INDEX (the transcript size), not an assistant count.
     */
    private fun TestScope.awaitAnswer(service: AssistantService, before: Int, timeoutMs: Long = 30_000) {
        fun settled() = service._messages.value
            .drop(before)
            .firstOrNull { it.role == "assistant" && (it.text.isNotBlank() || it.isError) }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            runCurrent()
            if (settled() != null) return
            Thread.sleep(25)
        }
        runCurrent()
        assertTrue(
            "the turn never produced an answer (messages=" +
                "${service._messages.value.map { it.role + ":" + it.text.take(40) }})",
            settled() != null
        )
    }

    private companion object {
        const val PROBE_SERVER = "StampEchoServer"
        val PROBE_PERSONA = Persona(
            name = "StampEchoProbe",
            themeColor = Color(0xFF4ADE80),
            model = "[$PROBE_SERVER] llama3",
            systemPrompt = "You are terse.",
            numCtx = 4096,
            maxTokens = 256
        )

        const val REPLY = "hello there"
        const val STALE_REPLY = "old answer"
        const val STALE_STAMPED_REPLY = "[Oct 3, 2026 09:00] $STALE_REPLY"

        /** What a local model produces when it copies the stamp format it was shown. */
        const val ECHO = "[Oct 3, 2026 12:29][Oct 3, 2026 12:29] $REPLY"

        /** One model-facing stamp at the start of the content, shaped like the real thing. */
        val STAMP_AT_START = Regex("""\[[^\]]*\d{1,2}:\d{2}]""")
    }
}
