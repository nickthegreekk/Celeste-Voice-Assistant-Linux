package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * **A tool turn, driven end to end on both routes** — the half of M8 that only a run can prove, made
 * runnable: a real request out to a loopback provider, a real tool call parsed from the body it
 * answered with, the executor against a real temporary folder, and the result carried into the next
 * request.
 *
 * It exists because the milestone's review found three things that no test beside it could see: the
 * cloud loop could not run at all (its gate read a field that is `OFF` at every real call site), a
 * tool-call round wrote an empty bubble into the transcript, and a tool round was carried out even
 * after the turn it belonged to was over. All three lived in wiring — a request that no test ever
 * sent, an apply block no test ever reached — and PLAN.md §10 said so in its own words: *"the two
 * loops … have no unit test between them, because this repository has no fake provider server."*
 * [FakeProviderServer] is that server.
 *
 * **Both dialects, because they are two code paths and the user's route is the local one.** The
 * Ollama turn is a streamed NDJSON conversation and the cloud turn is a single JSON body; they share
 * the executor, the protocol and the guards, and they do *not* share the code that decides when to
 * run them — which is exactly where the defects were.
 *
 * The narrowing is tested too, and is not decoration: a fork that stopped narrowing would offer
 * tools to every persona, and only an assertion that an ordinary turn is *unchanged* — one request,
 * no `tools` — catches that.
 */
class FsToolTurnTest {

    /** The two routes that can reach a tool. They differ in the entry point and the wire dialect. */
    private enum class Dialect { OLLAMA, CLOUD }

    private lateinit var env: FakeAppEnv
    private lateinit var notifier: RecordingNotifier
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
        // A recording notifier rather than the throwing one: this suite has a case where the app is
        // *supposed* to announce something (a write confirmation nobody answers), and asserting what
        // was announced is stronger than asserting that nothing was.
        notifier = RecordingNotifier()
        env = FakeAppEnv(notifier = notifier)
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
    fun `an Ollama turn with a workspace calls a tool and leaves no bubble for the call`() = runTest {
        assertToolTurn(Dialect.OLLAMA)
    }

    @Test
    fun `a cloud turn with a workspace calls a tool and leaves no bubble for the call`() = runTest {
        assertToolTurn(Dialect.CLOUD)
    }

    @Test
    fun `an Ollama tool round is not carried out once the turn is superseded`() = runTest {
        assertSupersededRoundIsNotRun(Dialect.OLLAMA)
    }

    @Test
    fun `a cloud tool round is not carried out once the turn is superseded`() = runTest {
        assertSupersededRoundIsNotRun(Dialect.CLOUD)
    }

    @Test
    fun `an Ollama turn reads at most one turn's worth, not one round's worth`() = runTest {
        assertTurnByteCapHolds(Dialect.OLLAMA)
    }

    @Test
    fun `a cloud turn reads at most one turn's worth, not one round's worth`() = runTest {
        assertTurnByteCapHolds(Dialect.CLOUD)
    }

    @Test
    fun `an Ollama turn that would overflow the window is refused, not truncated`() = runTest {
        assertOversizedTurnIsRefused(Dialect.OLLAMA)
    }

    @Test
    fun `a cloud turn that would overflow the window is refused, not truncated`() = runTest {
        assertOversizedTurnIsRefused(Dialect.CLOUD)
    }

    @Test
    fun `a confirmation nobody answers becomes a timeout, and it is announced`() = runTest {
        val service = serviceWith(Dialect.CLOUD, workspace = workspace, mode = FsToolMode.READ_WRITE)
        runCurrent()
        val request = FsWriteRequest(
            personaName = PERSONA,
            relativePath = "notes.md",
            resolved = workspace.resolve("notes.md"),
            root = workspace,
            existed = false,
            oldBytes = 0,
            newBytes = 3,
            preview = "abc",
        )

        val decision = async { service.requestFsWriteApproval(request, FsWorkspace(workspace, FsToolMode.READ_WRITE)) }
        runCurrent()
        assertNotNull("the question is up before anybody answers it", service.fsWriteGate.pending.value)

        // Virtual time, so this is the *real* five-minute wait expiring rather than a shortened one
        // passing for it.
        advanceTimeBy(FS_WRITE_APPROVAL_TIMEOUT_MS + 1)

        assertEquals("nobody answering is not somebody refusing", FsWriteDecision.TIMEOUT, decision.await())
        assertNull("and the question is withdrawn, not left on screen", service.fsWriteGate.pending.value)
        assertEquals(
            "the window-closed case is exactly what the desktop channel is for: " +
                notifier.posted,
            listOf("Celeste wanted to write notes.md — open the window to decide."),
            notifier.posted.map { it.body },
        )
    }

    @Test
    fun `Stop withdraws a pending write question rather than leaving it to be answered late`() = runTest {
        Files.writeString(workspace.resolve("notes.md"), "before")
        val service = serviceWith(Dialect.CLOUD, workspace = workspace, mode = FsToolMode.READ_WRITE)
        runCurrent()

        scriptToolRound(Dialect.CLOUD, TOOL_WRITE, """{"path":"notes.md","content":"after"}""", "never asked for")
        service.sendTextMessageToServer("rewrite my notes", service.personas.value.first())
        settle(service, "the confirmation to be asked") { service.fsWriteGate.pending.value != null }

        service.stopEverything()
        runCurrent()

        assertNull("the question goes with the turn that asked it", service.fsWriteGate.pending.value)
        settle(service, "the refusal to be recorded") { readFsToolAudit(env.stateDir, 10).isNotEmpty() }
        assertEquals(
            "an answer that arrives after the turn is over must not approve anything",
            "denied",
            readFsToolAudit(env.stateDir, 10).first().result,
        )
        assertEquals("and the file is untouched", "before", Files.readString(workspace.resolve("notes.md")))
    }

    @Test
    fun `a persona with no workspace is one request and declares no tools`() = runTest {
        val service = serviceWith(Dialect.CLOUD, workspace = null)
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

    // ------------------------------------------------------------------ the two turns worth asserting

    /**
     * Ask, call, answer — and assert every step of it: the tools were declared, the call was carried
     * out against the real folder, its result went back with the next request, and the round that
     * called it left nothing in the transcript.
     */
    private suspend fun TestScope.assertToolTurn(dialect: Dialect) {
        Files.writeString(workspace.resolve("notes.md"), "milk, and the shopping list\n")
        val service = serviceWith(dialect, workspace = workspace)
        runCurrent()

        scriptToolRound(dialect, TOOL_LIST, """{"path":"."}""", "Your workspace holds notes.md.")
        if (dialect == Dialect.CLOUD) {
            // The cloud loop owns the THINKING span (`ownsThinkingState`), and holding round two open
            // at the fake is the only way to look at the state *between* rounds — the moment an IDLE
            // would be visible, and the microphone with it.
            provider.holdRequest(2)
        }

        service.sendTextMessageToServer("list my files", service.personas.value.first())
        settle(service, "the second request to leave the app") { provider.turns.size >= 2 }

        if (dialect == Dialect.CLOUD) {
            assertEquals(
                "the turn is still THINKING between its rounds — an IDLE here resumes the VAD",
                AssistantState.THINKING,
                service._state.value,
            )
            provider.releaseRequest(2)
        }
        settle(service, "the turn to finish") {
            service._messages.value.lastOrNull()?.text == "Your workspace holds notes.md."
        }

        // 1. The route exists at all: two round trips, the first declaring the tools. Without that
        //    declaration a conforming model answers in prose and the loop never sees a call — which
        //    is what the Ollama request did before the review, and what the cloud fork did by never
        //    being reached at all.
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

        // 2. The call was carried out — against the real folder, and recorded, which is the half a
        //    transcript cannot show.
        val audit = readFsToolAudit(env.stateDir, 10)
        assertEquals("one call, one audit record: $audit", 1, audit.size)
        assertEquals(TOOL_LIST, audit.single().operation)
        assertEquals("ok", audit.single().result)
        assertEquals(PERSONA, audit.single().persona)

        // 3. And the model was told what it found: the second request carries the assistant's call
        //    and one result message with the real listing in it.
        val messages = provider.turns[1].messages()
        val result = (0 until messages.length())
            .map { messages.getJSONObject(it) }
            .firstOrNull { it.optString("role") == "tool" }
        assertNotNull("the second request must carry the tool's result back", result)
        assertTrue(
            "the result is the real listing of the real folder, not a placeholder: ${result?.optString("content")}",
            result!!.optString("content").contains("notes.md"),
        )

        // 4. Nothing was appended for the call itself. The empty triple the round used to return fell
        //    through to the apply block, which wrote an empty assistant bubble and persisted it.
        val assistant = service._messages.value.filter { it.role == "assistant" }
        assertEquals("one reply and no bubble for the call: $assistant", 1, assistant.size)
        assertEquals("Your workspace holds notes.md.", assistant.single().text)
        // Waited for rather than sampled: the bubble is written before the turn's own terminal state
        // write, so reading `_state` at the instant the transcript looks complete is a race — and it
        // is the kind that passes on a quiet machine and fails on a busy one.
        settle(service, "the turn to land in IDLE") { service._state.value == AssistantState.IDLE }
    }

    /**
     * The turn is over before its answer is read: Stop, a persona switch, a newer message. The calls
     * that answer carries must not be carried out — for `read_file` that is privacy, and for
     * `write_file` it is a file the user stopped.
     *
     * **The generation is bumped on the server thread, as the request arrives**, which is the only
     * way to place the bump inside the window this is about: between a request leaving and its answer
     * being read. That window is not a race the test invented — it is the one the content path's own
     * guards were written for, and it is the only window a tool round acts in.
     */
    private suspend fun TestScope.assertSupersededRoundIsNotRun(dialect: Dialect) {
        Files.writeString(workspace.resolve("notes.md"), "milk, and the shopping list\n")
        val service = serviceWith(dialect, workspace = workspace)
        runCurrent()

        provider.onRequest(1) { service.nextChatRequestSeq() }
        scriptToolRound(dialect, TOOL_LIST, """{"path":"."}""", "never asked for")

        service.sendTextMessageToServer("list my files", service.personas.value.first())
        settle(service, "the first request to arrive") { provider.turns.size >= 1 }
        // Nothing should follow, so this is a window rather than a signal — the shape
        // FreshStartNetworkTest established for proving a negative. Generous by orders of magnitude:
        // a second request, if one were coming, would be on loopback within milliseconds.
        settleWindow(SETTLE_WINDOW_MS)

        assertEquals(
            "a superseded turn must not ask again: ${provider.turns.map { it.path }}",
            1,
            provider.turns.size,
        )
        assertTrue(
            "a superseded turn must not carry the call out — the audit is empty of it: " +
                readFsToolAudit(env.stateDir, 10),
            readFsToolAudit(env.stateDir, 10).isEmpty(),
        )
        assertTrue(
            "and nothing was appended for it: ${service._messages.value.map { "${it.role}:${it.text.take(30)}" }}",
            service._messages.value.none { it.role == "assistant" },
        )
    }

    // ------------------------------------------------------------------ the harness

    /**
     * A service over the settings a turn needs: a provider entry pointing at the loopback fake, one
     * persona whose model label routes to it, and optionally the global filesystem workspace and mode.
     *
     * Seeded through the store rather than through `updateCloudApi`, deliberately: that call is one of
     * the two user-initiated triggers that reach `fetchCloudModels`, and a test about a chat turn
     * should not depend on a model-list fetch having happened first.
     */
    private suspend fun TestScope.serviceWith(
        dialect: Dialect,
        workspace: Path?,
        mode: FsToolMode = FsToolMode.READ_ONLY,
        numCtx: Int = 8192,
        maxTokens: Int = 2048,
    ): AssistantService {
        when (dialect) {
            Dialect.OLLAMA -> settings.saveOllamaBases(listOf(ServerConfig(name = OLLAMA_SERVER, url = provider.baseUrl)))
            Dialect.CLOUD -> settings.saveCloudApis(
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
        }
        if (workspace != null) {
            settings.saveFsMode(mode)
            settings.saveFsWorkspace(workspace.toString())
        }
        settings.savePersonas(listOf(persona(dialect, numCtx, maxTokens)))
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

    private fun persona(dialect: Dialect, numCtx: Int = 8192, maxTokens: Int = 2048): Persona = Persona(
        name = PERSONA,
        numCtx = numCtx,
        maxTokens = maxTokens,
        themeColor = Color(0xFF4ADE80),
        model = when (dialect) {
            Dialect.OLLAMA -> "[$OLLAMA_SERVER] fake-model"
            Dialect.CLOUD -> "[$PROVIDER] fake-model"
        },
        systemPrompt = "You are a test fixture.",
        isCloud = dialect == Dialect.CLOUD,
        // No voice engine and no thinking: this test is about the tool round trip, and a reply that
        // went anywhere near a synthesiser or a reasoning field would be measuring something else.
        voiceMode = VoiceMode.NONE,
        enableThinking = false,
    )

    /** One tool round and then a prose answer, in [dialect]'s own wire shape. */
    private fun scriptToolRound(dialect: Dialect, tool: String, arguments: String, answer: String) =
        scriptToolRound(dialect, listOf(tool to arguments), answer)

    /** [calls] in the order the model asks for them, then a prose answer. */
    private fun scriptToolRound(dialect: Dialect, calls: List<Pair<String, String>>, answer: String) {
        when (dialect) {
            Dialect.OLLAMA -> {
                calls.forEach { (tool, arguments) ->
                    provider.replyNdjson(
                        ollamaChunk(toolCalls = JSONArray().put(ollamaToolCall(tool, arguments))),
                        ollamaDone(),
                    )
                }
                provider.replyNdjson(ollamaChunk(answer), ollamaDone())
            }
            Dialect.CLOUD -> {
                calls.forEach { (tool, arguments) -> provider.reply(openAiToolCall(tool, arguments)) }
                provider.reply(openAiReply(answer))
            }
        }
    }

    /**
     * **The executor's byte cap is a turn's, not a round's.** It was enforced by an executor built
     * inside each round, so the bound reset every time the model asked again: eight rounds could read
     * eight times the 64 KiB the cap promises, and every byte of it travels in the *next request*.
     *
     * The fixture file is one long line under the per-read cap, so the first round's result is
     * returned in full and pushes the turn over the bound on its own.
     */
    private suspend fun TestScope.assertTurnByteCapHolds(dialect: Dialect) {
        Files.writeString(workspace.resolve("big.txt"), "x".repeat(80_000))
        Files.writeString(workspace.resolve("small.txt"), "the small file's contents")
        // A window wide enough to *hold* the 80 KB the first round read (~20k tokens), deliberately:
        // the byte cap being tested here is a different bound from the history budget, and on the
        // default 8k window the budget would refuse the turn first and this would prove nothing.
        val service = serviceWith(dialect, workspace = workspace, numCtx = 32_768)
        runCurrent()

        scriptToolRound(
            dialect,
            listOf(TOOL_READ to """{"path":"big.txt"}""", TOOL_READ to """{"path":"small.txt"}"""),
            "I read what I could.",
        )
        service.sendTextMessageToServer("read both files", service.personas.value.first())
        settle(service, "the turn to finish") {
            service._messages.value.lastOrNull()?.text == "I read what I could."
        }

        // The second call still reaches the executor — a refusal is a *result the model reads*, not a
        // stopped turn — and comes back as the sentence the cap has always promised.
        val results = provider.turns.last().messages().let { messages ->
            (0 until messages.length()).map { messages.getJSONObject(it) }.filter { it.optString("role") == "tool" }
        }
        assertTrue(
            "the second read must be refused by the turn's byte cap: ${results.map { it.optString("content").take(60) }}",
            results.any { it.optString("content").contains("already read as much as it may") },
        )
        assertTrue(
            "and the file it asked for must not have been read at all",
            provider.turns.none { it.body.contains("the small file's contents") },
        )
        assertEquals("both calls are audited, one of them as a refusal", 2, readFsToolAudit(env.stateDir, 10).size)
        assertEquals("denied", readFsToolAudit(env.stateDir, 10).first().result)
    }

    /**
     * **The tool output is part of what has to fit.** A turn that has read 64 KiB has ~16k tokens
     * riding in the next request, and nothing counted them: the budget saw a system prompt and a
     * short message, said the request fitted, and the provider truncated it — silently, which is the
     * failure `resolveHistoryBudget` exists to refuse out loud.
     */
    private suspend fun TestScope.assertOversizedTurnIsRefused(dialect: Dialect) {
        Files.writeString(workspace.resolve("big.txt"), "x".repeat(80_000))
        val service = serviceWith(dialect, workspace = workspace, numCtx = 2_000, maxTokens = 1_024)
        runCurrent()

        scriptToolRound(dialect, TOOL_READ, """{"path":"big.txt"}""", "this answer must never be asked for")
        service.sendTextMessageToServer("read the big file", service.personas.value.first())
        settle(service, "the refusal") {
            service._messages.value.any { it.isError }
        }

        val error = service._messages.value.single { it.isError }
        assertTrue(
            "the refusal names the window and what filled it: ${error.text}",
            error.text.contains("Context Window Size") && error.text.contains("this turn's file contents"),
        )
        assertEquals(
            "and the request that would have carried the tool output was never sent",
            1,
            provider.turns.size,
        )
        assertEquals("the read itself did happen", 1, readFsToolAudit(env.stateDir, 10).size)
    }

    /**
     * Waits for [ready], alternating real time with the test scheduler — because a turn's work happens
     * on threads this test does not own. The request goes out on `Dispatchers.IO`, comes back to
     * `Dispatchers.Main` (the test dispatcher, which runs only when told to), and the loop starts the
     * next round. So the wait has to drain Main, sleep, and drain it again.
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

    /** Drains the scheduler for [ms] of real time: the wait for an outcome that is *nothing*. */
    private fun TestScope.settleWindow(ms: Long) {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) {
            runCurrent()
            Thread.sleep(5)
        }
        runCurrent()
    }

    private companion object {
        const val OLLAMA_SERVER = "Local"
        const val PROVIDER = "Fake"
        const val PERSONA = "Fixture"

        /** Generous: a real round trip to loopback is milliseconds, and this is a hang guard. */
        const val TURN_TIMEOUT_MS = 20_000L

        /** Long enough that a second request would have arrived; short enough to stay a unit test. */
        const val SETTLE_WINDOW_MS = 1_500L
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
