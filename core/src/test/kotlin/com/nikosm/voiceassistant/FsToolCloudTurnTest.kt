package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * **A cloud turn with a workspace, driven end to end** — the half of M8 that only a run can prove,
 * made runnable: a real request out to a loopback provider, a real tool call parsed from the body it
 * answered with, the executor against a real temporary folder, and the result carried into the next
 * request.
 *
 * It exists because the milestone's review found that **the cloud loop could not run at all** and
 * that a tool-call round wrote an empty bubble into the transcript — two defects in the same five
 * lines, both invisible to the tests beside them, because the request builders are private and there
 * was no provider to send one to. PLAN.md §10's own note said as much: "the two loops and the two
 * screens have no unit test between them, because this repository has no fake provider server."
 * [FakeProviderServer] is that server.
 *
 * The negative case is beside the positive one and is not decoration: a fork that stopped narrowing
 * would offer tools to every cloud persona, and only an assertion that an ordinary turn is
 * *unchanged* — one request, no `tools` — catches that.
 */
class FsToolCloudTurnTest {

    private lateinit var env: FakeAppEnv
    private lateinit var store: InMemoryStore
    private lateinit var settings: SettingsManager
    private lateinit var provider: FakeProviderServer
    private lateinit var workspace: Path
    private val started = mutableListOf<AssistantService>()
    private var proxyBefore: ProxySelector? = null

    @Before
    fun setUp() {
        // `serviceScope` is `Dispatchers.Main`, and a JVM test supplies one. Standard rather than
        // Unconfined: the turn should advance when this test says so, not whenever a thread happens
        // to resume it.
        Dispatchers.setMain(StandardTestDispatcher())
        proxyBefore = ProxySelector.getDefault()
        ProxySelector.setDefault(DirectOnly)
        env = FakeAppEnv()
        store = InMemoryStore()
        settings = SettingsManager(env, store, NoImageCodec)
        provider = FakeProviderServer()
        workspace = Files.createTempDirectory("celeste-fs-turn")
    }

    @After
    fun tearDown() {
        // Every service this test built is shut down before the dispatcher goes away, for the reason
        // AGENTS.md §6 records: the state collector and the health check outlive a `runTest` and
        // then fail whichever test runs next, with nothing in the report pointing here.
        started.forEach { runCatching { it.shutdown() } }
        started.clear()
        provider.close()
        ProxySelector.setDefault(proxyBefore)
        Dispatchers.resetMain()
    }

    @Test
    fun `a cloud turn with a workspace calls a tool and leaves no bubble for the call`() = runTest {
        Files.writeString(workspace.resolve("notes.md"), "milk, and the shopping list\n")
        val service = serviceWith(workspace = workspace)
        runCurrent()

        provider.reply(openAiToolCall(TOOL_LIST, """{"path":"."}"""))
        provider.reply(openAiReply("Your workspace holds notes.md."))
        // The second round is held at the fake, so the state can be read in the one place this test
        // is about: between the rounds, while round two is outstanding.
        provider.holdRequest(2)

        service.sendTextMessageToServer("list my files", service.personas.value.first())
        settle(service, "the second round to leave the app") { provider.turns.size >= 2 }

        // What the loop must hold across its rounds. An IDLE here is not a flicker: the state
        // collector resumes the VAD on it, so the microphone would come back on mid-turn.
        assertEquals(
            "the turn is still THINKING between its rounds — an IDLE here resumes the VAD",
            AssistantState.THINKING,
            service._state.value,
        )
        provider.releaseRequest(2)

        settle(service, "the turn to finish") {
            service._messages.value.lastOrNull()?.text == "Your workspace holds notes.md."
        }

        // 1. The route exists at all: two round trips, and the first one declared the tools. Before
        //    the fix this was one request with no `tools` key — the fork tested `turnContext.fsToolMode`,
        //    which is OFF at every real call site, so `performCloudChatWithTools` was dead code.
        assertEquals("a tool turn is two requests to the provider", 2, provider.turns.size)
        val tools = JSONObject(provider.turns[0].body).optJSONArray("tools")
        assertNotNull(
            "the first request must declare the tools — a conforming model answers in prose without them",
            tools,
        )
        assertEquals(
            "a read-only workspace offers exactly the two read tools",
            listOf(TOOL_LIST, TOOL_READ),
            (0 until tools!!.length()).map { tools.getJSONObject(it).getJSONObject("function").getString("name") },
        )

        // 2. The call was carried out against the real folder, and the model was told what it found:
        //    the second request carries the assistant's call and one `tool` message with the listing.
        val messages = provider.turns[1].messages()
        val toolMessage = (0 until messages.length())
            .map { messages.getJSONObject(it) }
            .firstOrNull { it.optString("role") == "tool" }
        assertNotNull("the second request must carry the tool's result back", toolMessage)
        assertTrue(
            "the result is the real listing of the real folder, not a placeholder: ${toolMessage?.optString("content")}",
            toolMessage!!.optString("content").contains("notes.md"),
        )

        // 3. Nothing was appended for the call itself. The empty triple the round used to return
        //    fell through to the apply block, which wrote an empty assistant bubble and persisted it.
        val assistant = service._messages.value.filter { it.role == "assistant" }
        assertEquals("one reply and no bubble for the call: $assistant", 1, assistant.size)
        assertEquals("Your workspace holds notes.md.", assistant.single().text)
        assertEquals(AssistantState.IDLE, service._state.value)
    }

    @Test
    fun `a cloud persona without a workspace is one request and declares nothing`() = runTest {
        val service = serviceWith(workspace = null)
        runCurrent()

        provider.reply(openAiReply("No files for me."))
        service.sendTextMessageToServer("list my files", service.personas.value.first())
        settle(service, "the reply") { service._messages.value.lastOrNull()?.text == "No files for me." }

        assertEquals("a persona with no workspace is one round trip", 1, provider.turns.size)
        assertFalse(
            "an ordinary cloud turn must not grow a tools field — this is the half that keeps the " +
                "fork above narrow rather than merely on",
            JSONObject(provider.turns[0].body).has("tools"),
        )
        assertTrue(
            "and it still answers normally",
            service._messages.value.any { it.role == "assistant" && it.text == "No files for me." },
        )
    }

    // ------------------------------------------------------------------ the harness

    /**
     * A service over the settings a turn needs: a provider entry pointing at the loopback fake (with
     * `icon = "C"`, the OpenAI-compatible dialect, and a blank key — the one dialect that may have
     * none), one cloud persona, and optionally the global filesystem workspace and mode.
     *
     * Seeded through the store rather than through `updateCloudApi`, deliberately: that call is one
     * of the two user-initiated triggers that reach `fetchCloudModels`, and a test about a chat turn
     * should not depend on a model-list fetch having happened first.
     */
    private suspend fun TestScope.serviceWith(workspace: Path?, mode: FsToolMode = FsToolMode.READ_ONLY): AssistantService {
        settings.saveCloudApis(
            listOf(
                CloudApiSetting(
                    name = PROVIDER,
                    baseUrl = provider.baseUrl,
                    apiKey = "",
                    icon = "C",
                    color = Color(0xFF808080),
                    isEditableUrl = true,
                )
            )
        )
        if (workspace != null) {
            settings.saveFsMode(mode)
            settings.saveFsWorkspace(workspace.toString())
        }
        settings.savePersonas(listOf(persona()))
        val service = AssistantService(
            appEnv = env,
            audioPlayback = SilentAudioPlayback,
            audioDevices = NoAudioDevices,
            tts = NoTextToSpeech,
            audioCapture = NoAudioCapture,
            fileDialogs = NoFileDialogs,
            settingsManager = settings,
        )
        started += service
        service.start()
        runCurrent()
        // L5: the persona's history loads on another thread, and a turn that began before it landed
        // could have its own transcript write replaced by the load. The service offers exactly this
        // join for tests, so the turn below starts from a settled transcript.
        service.awaitPersonaHistoryIo()
        return service
    }

    private fun persona(): Persona = Persona(
        name = PERSONA,
        themeColor = Color(0xFF4ADE80),
        model = "[$PROVIDER] fake-model",
        systemPrompt = "You are a test fixture.",
        isCloud = true,
        // No voice engine and no thinking: this test is about the tool round trip, and a reply that
        // went anywhere near a synthesiser or a reasoning field would be measuring something else.
        voiceMode = VoiceMode.NONE,
        enableThinking = false,
    )

    /**
     * Waits for [ready], alternating real time with the test scheduler — because a turn's work
     * happens on threads this test does not own. The request goes out on `Dispatchers.IO`, comes
     * back to `Dispatchers.Main` (the test dispatcher, which runs only when told to), and the loop
     * starts the next round. So the wait has to drain Main, sleep, and drain it again.
     *
     * A deadline rather than a signal, and the failure names the state instead of the assertion that
     * happens to run next — the lesson `FreshStartNetworkTest`'s first version recorded.
     */
    private fun TestScope.settle(service: AssistantService, what: String, ready: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TURN_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            runCurrent()
            if (ready()) return
            Thread.sleep(5)
        }
        runCurrent()
        throw AssertionError(
            "$what did not happen within ${TURN_TIMEOUT_MS}ms. " +
                "requests=${provider.turns.size} (${provider.turns.map { it.path }}), transcript=" +
                service._messages.value.joinToString(",") { "${it.role}:${it.text.take(40)}" }
        )
    }

    private companion object {
        const val PROVIDER = "Fake"
        const val PERSONA = "Fixture"

        /** Generous: a real round trip to loopback is milliseconds, and this is a hang guard. */
        const val TURN_TIMEOUT_MS = 20_000L
    }

    /**
     * Loopback only, and nothing else: a test whose provider is on 127.0.0.1 has no business
     * resolving a proxy, and if a request ever went somewhere else it would fail here rather than
     * leave the machine.
     */
    private object DirectOnly : ProxySelector() {
        override fun select(uri: URI): List<Proxy> = listOf(Proxy.NO_PROXY)
        override fun connectFailed(uri: URI, sa: SocketAddress, ioe: java.io.IOException) = Unit
    }
}
