package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
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
 * Phase 2 (multi-persona Rooms): the round turn loop, against a loopback stub (ported from
 * upstream's instrumented test in M7).
 *
 * `runRoomRound` is the first thing in the app that spends a user's tokens without the user
 * asking for each message, so the properties under test are the ones that make that safe to
 * ship:
 *  1. every member answers the user's message exactly ONCE, in the room's own order, and each
 *     reply lands in the ROOM transcript attributed to its speaker (never the single-chat one);
 *  2. autonomous continuation stops EXACTLY at `RoomReplyPolicy.capFor(room)` — the cap counts
 *     total autonomous messages and is checked before every turn, so it cannot be overshot
 *     by one;
 *  3. only LOCAL members take autonomous turns (Q1: no unsupervised paid turns), rotating so
 *     nobody speaks twice in a row, while cloud members still answer the user in the initial
 *     round and never consume autonomous budget;
 *  4. Stop ends the round immediately: no further member turn starts, the in-flight turn adds
 *     nothing to the transcript (not even a blank bubble), and the round state settles to null;
 *  5. a newer round SUPERSEDES one in flight: the abandoned round's remaining turns never run,
 *     its late unwind cannot blank the successor's state, and the transcript the user ends up
 *     with is the new round's.
 *
 * How it proves it: one loopback server answers BOTH backend shapes the loop actually uses —
 * Direct-Ollama NDJSON (`/api/chat`, EOF-delimited, the shape the client streams) and a
 * cloud-provider JSON reply (`/chat/completions`, icon "C") — and every request body is
 * captured. Each probe persona's system prompt carries a unique tag, so a captured body says
 * WHICH member spoke; each reply echoes the request index, so the transcript says which
 * request's answer landed where. Rounds are driven through the production entry points
 * (`startRoomRound`/`stopRoomRound`) on a room opened through the production `switchRoom`, so
 * routing, budget selection, streaming, the H3 placeholder bookkeeping and the settle path are
 * all the real ones.
 *
 * The port change is only the harness: a fresh service over an in-memory store per test instead
 * of the device's bound service, and Main is a `StandardTestDispatcher` pumped explicitly —
 * which is also what lets the round be driven deterministically instead of waiting on a real
 * Looper.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RoomRoundLoopTest {
    private val mainDispatcher = StandardTestDispatcher()


    private val env = FakeAppEnv(notifier = RecordingNotifier())
    private val store = InMemoryStore()
    private val settings = SettingsManager(env, store, NoImageCodec)
    private val services = mutableListOf<AssistantService>()

    private lateinit var service: AssistantService

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
    ).also {
        services += it
        service = it
    }

    // ---- the five properties ---------------------------------------------------------------

    /**
     * Property 1: the initial round is exactly one turn per member, in the room's own order,
     * each reply attributed to its speaker in the ROOM transcript.
     */
    @Test
    fun `initial round runs every member once in room order`() = runTest {
        RoomTurnServer().use { server ->
            enterRoom(server, probeRoom(cap = 0, members = listOf(LOCAL_A, LOCAL_B, CLOUD_ONE)))
            val room = service.activeRoom!!
            assertTrue("the probe round never started", startRound(room, USER_FIRST))
            awaitRoundSettled()

            assertEquals(
                "each member must answer the user exactly once, in room order",
                listOf(RoomMessage.USER_SPEAKER, LOCAL_A, LOCAL_B, CLOUD_ONE),
                speakers()
            )
            // The reply text echoes the request index, so this ties transcript order to the order
            // the turns were actually issued in AND to the member each request was built for.
            assertEquals(
                listOf(USER_FIRST, replyText(1, LOCAL_A), replyText(2, LOCAL_B), replyText(3, CLOUD_ONE)),
                texts()
            )
            assertEquals(
                "one request per member, in room order",
                listOf(LOCAL_A, LOCAL_B, CLOUD_ONE),
                server.requests.map { it.speakerTag }
            )
            assertNull(
                "a cap of 0 must leave the round state settled after the initial round",
                service.roomRound.value
            )
            assertEquals(
                "the finished round must still leave the room owning its transcript",
                room.id,
                service.roomMessagesOwnerId
            )
            assertNoFurtherRequests(server, expected = 3)
        }
    }

    /**
     * Property 2: with two local members and cap = 1 the round stops after exactly ONE
     * autonomous message — 4 messages total, not 5.
     */
    @Test
    fun `autonomous continuation stops exactly at the cap`() = runTest {
        RoomTurnServer().use { server ->
            enterRoom(server, probeRoom(cap = 1, members = listOf(LOCAL_A, LOCAL_B)))
            val room = service.activeRoom!!
            assertEquals("precondition: the probe room's cap", 1, RoomReplyPolicy.capFor(room))
            assertTrue("the probe round never started", startRound(room, USER_FIRST))
            awaitRoundSettled()

            assertEquals(
                "the cap counts TOTAL autonomous messages: cap=1 means one of them, not one more",
                listOf(RoomMessage.USER_SPEAKER, LOCAL_A, LOCAL_B, LOCAL_A),
                speakers()
            )
            assertEquals(listOf(LOCAL_A, LOCAL_B, LOCAL_A), server.requests.map { it.speakerTag })
            assertEquals(
                "the loop must not have used the room's members as an unbounded relay",
                4,
                service.roomMessages.value.size
            )
            assertNull(service.roomRound.value)
            // Three requests total: one per member in the initial round, plus the single
            // autonomous turn the cap of 1 allows — and nothing after it.
            assertNoFurtherRequests(server, expected = 3)
        }
    }

    /**
     * Property 3: autonomous turns are local-only and rotate. `cap = 4` in a room with two
     * locals and one cloud member yields 4 autonomous messages, local members alternating, with
     * the cloud member participating exactly once (the initial round).
     */
    @Test
    fun `autonomous turns rotate through locals and never spend on cloud`() = runTest {
        RoomTurnServer().use { server ->
            enterRoom(server, probeRoom(cap = 4, members = listOf(LOCAL_A, LOCAL_B, CLOUD_ONE)))
            val room = service.activeRoom!!
            assertTrue("the probe round never started", startRound(room, USER_FIRST))
            awaitRoundSettled()

            assertEquals(
                "cloud members answer the user but never take an autonomous turn (Q1), and " +
                    "local members must rotate rather than repeat",
                listOf(
                    RoomMessage.USER_SPEAKER,
                    LOCAL_A, LOCAL_B, CLOUD_ONE,
                    LOCAL_A, LOCAL_B, LOCAL_A, LOCAL_B
                ),
                speakers()
            )
            assertEquals(
                "the cloud member must be asked exactly once per user message",
                1,
                server.requests.count { it.speakerTag == CLOUD_ONE }
            )
            assertEquals(
                "each local member: one initial-round turn + two autonomous turns",
                3,
                server.requests.count { it.speakerTag == LOCAL_A }
            )
            assertNoFurtherRequests(server, expected = 7)
        }
    }

    /**
     * Property 4: Stop ends the round. The in-flight member's turn adds NOTHING to the
     * transcript (not even the blank bubble a cancelled stream would leave behind), no later
     * member turn runs, and the round state settles to null so the UI's Stop button clears.
     */
    @Test
    fun `stop ends the round without leaving a blank bubble`() = runTest {
        RoomTurnServer().use { server ->
            enterRoom(server, probeRoom(cap = 4, members = listOf(LOCAL_A, LOCAL_B)))
            val room = service.activeRoom!!
            // Park the FIRST member turn mid-stream, but only AFTER it streamed a whitespace
            // chunk: that chunk opens the turn's in-place placeholder, so the transcript really
            // does hold a row for this turn when Stop lands — Stop has to REMOVE that row, not
            // merely avoid appending one.
            server.preHoldChunk = " "
            server.holdConnections = true
            assertTrue("the probe round never started", startRound(room, USER_FIRST))
            server.awaitRequests(1)
            awaitTurnInFlight(0)
            awaitService(30_000, "the first member's streaming placeholder never landed") {
                service.roomMessages.value.size == 2
            }

            assertEquals(
                "precondition: the first member's turn is the one parked",
                LOCAL_A,
                server.requests.first().speakerTag
            )

            mainSync { service.stopRoomRound() }
            awaitRoundSettled()
            // Release the parked turn only now: it has to unwind, and its late output is exactly
            // what must never land. Asserting before this point would prove nothing.
            server.releaseHeld()
            assertNoFurtherRequests(server, expected = 1)

            assertEquals(
                "Stop must not append anything for the turn it cancelled",
                listOf(RoomMessage.USER_SPEAKER),
                speakers()
            )
            assertEquals(
                "Stop must leave exactly the user's own message in the transcript",
                1,
                service.roomMessages.value.size
            )
            assertNull(
                "Stop must clear the round state (the UI's Stop button keys off it)",
                service.roomRound.value
            )
            assertNull(
                "Stop must not leave a streaming overlay behind",
                service.streamingText.value
            )
        }
    }

    /**
     * Property 5: a new round SUPERSEDES one in flight. The abandoned round runs no further
     * turns, its late unwind cannot blank the successor's state, and the transcript ends up
     * being the new round's.
     */
    @Test
    fun `a newer round supersedes the round in flight`() = runTest {
        RoomTurnServer().use { server ->
            enterRoom(server, probeRoom(cap = 0, members = listOf(LOCAL_A, LOCAL_B, CLOUD_ONE)))
            val room = service.activeRoom!!
            // Round 1's first turn opens a placeholder (a whitespace chunk) and then parks, so
            // the abandoned round really has a row of its own to strand when it is superseded.
            server.preHoldChunk = " "
            server.holdConnections = true
            assertTrue("the first probe round never started", startRound(room, USER_FIRST))
            server.awaitRequests(1)
            awaitTurnInFlight(0)
            awaitService(30_000, "the first round's streaming placeholder never landed") {
                service.roomMessages.value.size == 2
            }
            assertEquals(LOCAL_A, server.requests.first().speakerTag)

            // Round 2 starts while round 1's first member is still parked mid-request. The hold
            // is lifted for round 2's own turns; round 1's parked connection stays parked, and
            // must be interrupted by the supersede (not merely released by the test).
            server.preHoldChunk = null
            server.holdConnections = false
            assertTrue(
                "a newer round must be accepted while an older one is still unwinding",
                startRound(room, USER_SECOND)
            )
            awaitRoundSettled()
            // Now release what is left of round 1 and give its output every chance to land.
            server.releaseHeld()
            assertNoFurtherRequests(server, expected = 4)

            assertEquals(
                "the superseded round must contribute nothing beyond the user message it " +
                    "already sent, and the new round must run every member once",
                listOf(
                    RoomMessage.USER_SPEAKER, RoomMessage.USER_SPEAKER,
                    LOCAL_A, LOCAL_B, CLOUD_ONE
                ),
                speakers()
            )
            assertEquals(
                listOf(USER_FIRST, USER_SECOND),
                texts().take(2)
            )
            // Request 1 is the abandoned round's turn; the surviving round is requests 2..4.
            assertEquals(
                listOf(LOCAL_A, LOCAL_A, LOCAL_B, CLOUD_ONE),
                server.requests.map { it.speakerTag }
            )
            assertEquals(
                "the superseded turn's reply must never reach the transcript",
                0,
                service.roomMessages.value.count { it.text == replyText(1, LOCAL_A) }
            )
            assertNull("the surviving round must settle too", service.roomRound.value)
        }
    }

    /**
     * Property 2a (regression): a cap of 0 set THROUGH THE UI PARSER gives exactly one reply
     * per persona and no autonomous continuation.
     *
     * Every other room test builds its room with a literal `autoReplyCap`, which bypasses the
     * layer that was actually broken: the editor's parser used to reject a typed 0 outright
     * and, before that, collapsed it into the blank/inherit sentinel so it persisted as
     * `null`. Driving the round from the TEXT the editor would have committed means this test
     * fails if the field can no longer express "initial round only" — the real user path —
     * and not merely if the loop mishandles a 0 handed to it directly.
     */
    @Test
    fun `a zero cap set through the editor parser runs the initial round only`() = runTest {
        RoomTurnServer().use { server ->
            enterRoom(server, probeRoom(cap = null, members = listOf(LOCAL_A, LOCAL_B)))
            val room = service.activeRoom!!

            // Precondition: the room starts on the global default, so a 0 that came back as
            // "inherit" could not be told apart from a 0 that was really stored.
            assertEquals(
                "precondition: an unset cap inherits the global default",
                RoomReplyPolicy.DEFAULT_AUTO_REPLY_CAP,
                RoomReplyPolicy.capFor(room)
            )

            // Exactly what the editor's Done button does with the text "0" in the field.
            val committed = autoReplyCapOverrideOrNull("0")
            assertEquals("the editor must accept a typed 0 as a real cap", 0, committed)
            mainSync { service.updateRoomCap(room.id, committed) }

            assertEquals(
                "the stored override must be 0, NOT null (null would silently inherit)",
                0,
                service.activeRoom!!.autoReplyCap
            )
            assertEquals(
                "capFor must resolve the explicit 0 rather than the default",
                0,
                RoomReplyPolicy.capFor(service.activeRoom!!)
            )

            assertTrue("the probe round never started", startRound(service.activeRoom!!, USER_FIRST))
            awaitRoundSettled()

            assertEquals(
                "with a cap of 0 every persona answers EXACTLY once and nobody continues",
                listOf(RoomMessage.USER_SPEAKER, LOCAL_A, LOCAL_B),
                speakers()
            )
            assertEquals(
                "only the two initial-round requests may reach the provider",
                listOf(LOCAL_A, LOCAL_B),
                server.requests.map { it.speakerTag }
            )
            assertNull("the round must settle", service.roomRound.value)
            assertNoFurtherRequests(server, expected = 2)
        }
    }

    /**
     * Property 2b: a cap of 1 still buys exactly ONE extra message beyond the initial round —
     * the behaviour verified before the cap-of-0 fix. Run alongside it so a fix for 0 that
     * quietly shifted the cap's baseline would fail here rather than pass silently.
     */
    @Test
    fun `a one cap set through the editor parser still adds exactly one autonomous message`() = runTest {
        RoomTurnServer().use { server ->
            enterRoom(server, probeRoom(cap = null, members = listOf(LOCAL_A, LOCAL_B)))
            val room = service.activeRoom!!

            assertEquals("the editor must accept a typed 1 as a real cap", 1, autoReplyCapOverrideOrNull("1"))
            mainSync { service.updateRoomCap(room.id, autoReplyCapOverrideOrNull("1")) }
            assertEquals(1, RoomReplyPolicy.capFor(service.activeRoom!!))

            assertTrue("the probe round never started", startRound(service.activeRoom!!, USER_FIRST))
            awaitRoundSettled()

            assertEquals(
                "cap 1 is one extra message beyond the initial round — unchanged by the 0 fix",
                listOf(RoomMessage.USER_SPEAKER, LOCAL_A, LOCAL_B, LOCAL_A),
                speakers()
            )
            assertNoFurtherRequests(server, expected = 3)
        }
    }

    /**
     * Property 2c: blanking the field goes back to inheriting the default, so fixing the 0
     * case did not steal the "clear the override" behaviour that shares the same text field.
     */
    @Test
    fun `blanking the cap field goes back to inheriting the default`() = runTest {
        RoomTurnServer().use { server ->
            enterRoom(server, probeRoom(cap = 0, members = listOf(LOCAL_A, LOCAL_B)))
            val room = service.activeRoom!!
            assertEquals("precondition: the room starts capped at 0", 0, RoomReplyPolicy.capFor(room))

            // The editor commits `null` for a blank field — that must clear the override,
            // not be swallowed, and must NOT be mistaken for the cap value 0.
            assertNull(autoReplyCapOverrideOrNull(""))
            mainSync { service.updateRoomCap(room.id, autoReplyCapOverrideOrNull("")) }

            assertNull(
                "blanking the field must clear the override",
                service.activeRoom!!.autoReplyCap
            )
            assertEquals(
                "a cleared override inherits the default again",
                RoomReplyPolicy.DEFAULT_AUTO_REPLY_CAP,
                RoomReplyPolicy.capFor(service.activeRoom!!)
            )
        }
    }

    // ---- harness ----------------------------------------------------------------------------

    /**
     * Open [room] through the production `switchRoom`, with the stub registered as both the
     * probe personas' Ollama server and their cloud provider.
     *
     * A fresh service owns a fresh store, so unlike the device harness there is no residue from
     * a previous test to remove first.
     */
    private fun TestScope.enterRoom(server: RoomTurnServer, room: Room) {
        newService()
        mainSync {
            service._ollamaBaseUrls.value = listOf(ServerConfig(PROBE_SERVER_NAME, server.url))
            service._customCloudApis.value = listOf(probeCloudApi(server))
            PROBE_PERSONAS.forEach { service.addPersona(it) }
            service.addRoom(room)
            service.switchRoom(room)
        }
        awaitService(30_000, "the probe room's transcript was never loaded") {
            service.activeRoomId.value == room.id && service.roomMessagesOwnerId == room.id
        }
        assertTrue(
            "the probe room must start from an empty transcript",
            service.roomMessages.value.isEmpty()
        )
    }

    /** Start a round through the production entry point the room composer's send button uses. */
    private fun TestScope.startRound(room: Room, text: String): Boolean {
        var started = false
        mainSync { started = service.startRoomRound(room, text) }
        return started
    }

    /**
     * Wait until no round is running. [AssistantService.startRoomRound] publishes the round
     * state synchronously, so "state is null" can only mean the round finished (or was stopped)
     * — including the superseded round, whose late settle must NOT have blanked the survivor.
     */
    private fun TestScope.awaitRoundSettled(timeoutMs: Long = 60_000) {
        awaitService(timeoutMs, "the room round never settled (round=${service.roomRound.value})") {
            service.roomRound.value == null
        }
    }

    /** Wait until the round has published a member turn — i.e. that turn is in flight. */
    private fun TestScope.awaitTurnInFlight(memberIndex: Int) {
        awaitService(30_000, "member #$memberIndex's turn never started") {
            val state = service.roomRound.value
            state != null && state.currentMemberIndex == memberIndex &&
                (state.phase == RoomRoundPhase.INITIAL_ROUND || state.phase == RoomRoundPhase.AUTONOMOUS)
        }
    }

    /**
     * Fail if the provider stub is asked for anything more than [expected] requests during a
     * short window. Asserting the count alone would let a stray extra turn through after the
     * assertions ran, which is exactly the failure mode (a loop that keeps going, or a
     * superseded round that resumes) this guards against.
     */
    private fun TestScope.assertNoFurtherRequests(
        server: RoomTurnServer,
        expected: Int,
        windowMs: Long = 600
    ) {
        val deadline = System.currentTimeMillis() + windowMs
        while (System.currentTimeMillis() < deadline) {
            runCurrent()
            assertEquals(
                "a member turn reached the provider stub after the round was over " +
                    "(captured turns: ${server.requests.map { it.speakerTag }})",
                expected,
                server.requests.size
            )
            Thread.sleep(20)
        }
    }

    private fun speakers(): List<String> = service.roomMessages.value.map { it.speakerName }

    private fun texts(): List<String> = service.roomMessages.value.map { it.text }

    private fun TestScope.awaitService(timeoutMs: Long, what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            runCurrent()
            if (condition()) return
            Thread.sleep(20)
        }
        runCurrent()
        assertTrue("timed out after ${timeoutMs}ms waiting until $what", condition())
    }

    /**
     * Run a production entry point the way the UI's main-thread call does, then pump the Main
     * dispatcher so whatever it launched is already in motion before the next assertion.
     */
    private fun TestScope.mainSync(block: () -> Unit) {
        block()
        runCurrent()
    }

    private companion object {

        /** The `[Name]` tag the probe members' models resolve against. */
        const val PROBE_SERVER_NAME = "RoomLoopProbeServer"

        /** The custom cloud provider the cloud probe member resolves against (icon "C"). */
        const val PROBE_CLOUD_PROVIDER = "RoomLoopProbeCloud"

        /** Not an `isKnownThinkingModel` match, so `think` is never requested. */
        const val PROBE_MODEL = "room-loop-probe-model"

        const val LOCAL_A = "RoomLoopLocalA"
        const val LOCAL_B = "RoomLoopLocalB"
        const val CLOUD_ONE = "RoomLoopCloud"

        const val PROBE_ROOM_ID = "room-loop-probe-room"

        /** Both avoid the word "news", so `isNewsRequest` cannot fire a search. */
        const val USER_FIRST = "kick off the round"
        const val USER_SECOND = "one more thought for the room"

        val PROBE_PERSONAS = listOf(
            localProbePersona(LOCAL_A),
            localProbePersona(LOCAL_B),
            cloudProbePersona(CLOUD_ONE)
        )

        fun probeRoom(cap: Int?, members: List<String>): Room =
            Room(
                id = PROBE_ROOM_ID,
                name = "Room Loop Probe",
                memberNames = members,
                autoReplyCap = cap
            )

        /**
         * A direct (non-cloud) probe member: `[PROBE_SERVER_NAME] model` routes it to the stub
         * through the same display-name lookup the real router performs. `voiceMode = NONE`
         * makes `playResponse` take its documented no-play bail, so no TTS engine is touched.
         */
        fun localProbePersona(name: String): Persona = Persona(
            name = name,
            themeColor = Color(0xFF4ADE80),
            model = "[$PROBE_SERVER_NAME] $PROBE_MODEL",
            systemPrompt = systemPromptTag(name),
            isCloud = false,
            backendUrl = "",
            allowGatewayFailover = false,
            voiceMode = VoiceMode.NONE,
            maxTokens = 256,
            numCtx = 8192,
            enableThinking = false,
            webSearchEnabled = false,
            ragEnabled = false
        )

        /** A cloud probe member, routed to the stub's `/chat/completions` endpoint. */
        fun cloudProbePersona(name: String): Persona = Persona(
            name = name,
            themeColor = Color(0xFF60A5FA),
            model = "[$PROBE_CLOUD_PROVIDER] $PROBE_MODEL",
            systemPrompt = systemPromptTag(name),
            isCloud = true,
            backendUrl = "",
            allowGatewayFailover = false,
            voiceMode = VoiceMode.NONE,
            maxTokens = 256,
            numCtx = 8192,
            enableThinking = false,
            webSearchEnabled = false,
            ragEnabled = false
        )

        /**
         * A custom cloud provider pointing at the stub. Icon "C" is the OpenAI-compatible
         * branch, and (unlike the other icons) it does not require an API key — which is what
         * lets a loopback server stand in for a paid provider here.
         */
        fun probeCloudApi(server: RoomTurnServer): CloudApiSetting = CloudApiSetting(
            name = PROBE_CLOUD_PROVIDER,
            baseUrl = server.url,
            apiKey = "",
            icon = "C",
            color = Color(0xFF6366F1),
            isEditableUrl = false
        )

        /**
         * The tag that identifies a member in a captured request body. The room preamble names
         * participants by persona NAME and the transcript labels lines with names too, so this
         * tag appears in exactly one place: the speaker's own system prompt.
         */
        fun systemPromptTag(name: String) = "ROOM-PROBE-$name"
    }
}
