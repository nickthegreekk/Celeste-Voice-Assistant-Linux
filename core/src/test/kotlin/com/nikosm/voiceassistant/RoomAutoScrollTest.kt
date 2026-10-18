package com.nikosm.voiceassistant

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The room transcript's scroll behaviour, and the message identity it depends on (ported from
 * upstream's instrumented test in M7).
 *
 * Two defects, fixed together because one caused the other:
 *
 *  1. `RoomChatArea` had no scroll handling at all, so opening a room that already had a
 *     conversation started at the top, and a live round grew downward off-screen.
 *  2. `RoomTurnSink` minted a FRESH `RoomMessage.id` on every streaming chunk. The
 *     transcript's LazyColumn keys on that id, so each token read as remove+insert: the
 *     bubble was destroyed and rebuilt mid-stream, discarding its scroll anchoring.
 *
 * The UI assertions are deliberately about POSITION (`firstVisibleItemIndex` /
 * `canScrollForward`), not pixels: "is the newest message on screen" is the user-visible
 * property, and a pixel assertion would be brittle across devices and font scales.
 *
 * Upstream's screenshot half is dropped with the instrumentation host and recorded in
 * docs/TEST-MAP.md.
 */
@OptIn(ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RoomAutoScrollTest {

    private companion object {
        const val ROOM_ID = "room-autoscroll-probe"
    }

    private val env = FakeAppEnv(notifier = RecordingNotifier(), scheduler = RecordingScheduler())
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
            audioPlayback = SilentAudioPlayback,
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

    // ---- message identity: the precondition for the scroll to hold still ---------------

    /**
     * A streaming update is an edit of an EXISTING message, so it must keep that message's
     * id and timestamp. It did not: each chunk minted a new UUID, which the transcript's
     * LazyColumn keys on — so every token destroyed and rebuilt the bubble.
     */
    @Test
    fun `streaming updates preserve the message's identity`() {
        service.roomMessagesOwnerId = ROOM_ID
        service._roomMessages.value = emptyList()
        val sink = RoomTurnSink(service, ROOM_ID, "Bob")

        val appended = sink.append(ChatMessage("assistant", "Hel"))
        assertTrue("the sink refused the append", appended >= 0)
        val original = service._roomMessages.value.single()

        // More chunks, as a streaming reply would deliver.
        sink.updateAt(0) { it.copy(text = it.text + "lo, wor") }
        sink.updateAt(0) { it.copy(text = it.text + "ld!") }
        val afterChunks = service._roomMessages.value.single()

        assertEquals(
            "a streaming update re-minted the message id, so the LazyColumn treats every " +
                "chunk as remove+insert",
            original.id,
            afterChunks.id
        )
        assertEquals(
            "a streaming update re-stamped the message's creation time",
            original.timestamp,
            afterChunks.timestamp
        )
        // The text must of course have advanced — the identity is what must not.
        assertEquals("Hello, world!", afterChunks.text)
    }

    @Test
    fun `an appended message still gets a fresh identity`() {
        service.roomMessagesOwnerId = ROOM_ID
        service._roomMessages.value = emptyList()
        val sink = RoomTurnSink(service, ROOM_ID, "Bob")
        sink.append(ChatMessage("assistant", "one"))
        sink.append(ChatMessage("assistant", "two"))
        val ids = service._roomMessages.value.map { it.id }
        assertEquals(2, ids.size)
        assertNotEquals(
            "two distinct messages must not share an id, or the LazyColumn drops one",
            ids[0], ids[1]
        )
    }

    // ---- the scroll behaviour itself ------------------------------------------------------

    private val room = Room(id = ROOM_ID, name = "Scroll Room", memberNames = listOf("Bob"))

    /** Tall bubbles, so "at the top" and "at the bottom" are unambiguously different. */
    private fun transcript(count: Int) = (0 until count).map { i ->
        RoomMessage(
            speakerName = if (i % 2 == 0) RoomMessage.USER_SPEAKER else "Bob",
            text = "Message $i — " + "lorem ipsum dolor sit amet ".repeat(6)
        )
    }

    /**
     * Whether [listState] is showing the newest message.
     *
     * Deliberately NOT "firstVisibleItemIndex is near the end": the final bubble is tall, so
     * a correctly-bottomed list legitimately reports a small index. The last ITEM being
     * visible is the property the user actually sees.
     */
    private fun LazyListState.isShowingNewest(): Boolean {
        val total = layoutInfo.totalItemsCount
        return total > 0 && layoutInfo.visibleItemsInfo.any { it.index == total - 1 }
    }

    /**
     * Asserts the transcript is long enough that reaching the newest message requires an
     * actual scroll. Checked as "more items than fit on screen" rather than
     * `canScrollForward`: once the auto-scroll has done its job the list legitimately sits at
     * the BOTTOM, where `canScrollForward` is false — using it here would be circular.
     */
    private fun requireScrollable(listState: LazyListState, count: Int) {
        val info = listState.layoutInfo
        assertTrue(
            "precondition: $count messages must overflow the viewport, or scrolling is " +
                "meaningless here (total=${info.totalItemsCount}, visible=" +
                "${info.visibleItemsInfo.size})",
            info.totalItemsCount > info.visibleItemsInfo.size && info.visibleItemsInfo.size > 0
        )
    }

    @Composable
    private fun RoomTranscript(listState: LazyListState, messages: List<RoomMessage>) {
        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                RoomChatArea(
                    room = room,
                    messages = messages,
                    personas = listOf(Persona("Bob", Color(0xFF4ADE80), "[S] m", "sys")),
                    roundState = null,
                    chatFontSize = 16f,
                    textInput = "",
                    onTextInputChange = {},
                    onSend = {},
                    onStop = {},
                    focusRequester = remember { FocusRequester() },
                    listState = listState
                )
            }
        }
    }

    @Test
    fun `opening a room shows the newest message`() = runComposeUiTest {
        val messages = transcript(24)
        val listState = LazyListState() // starts at the top, as an unscrolled list does
        setContent { RoomTranscript(listState, messages) }
        waitForIdle()

        requireScrollable(listState, messages.size)
        assertTrue(
            "a room with a history opened without reaching the newest message; visible=" +
                "${listState.layoutInfo.visibleItemsInfo.map { it.index }} of " +
                "${listState.layoutInfo.totalItemsCount}",
            listState.isShowingNewest()
        )
        assertTrue(
            "the transcript should be scrolled to the very end",
            !listState.canScrollForward
        )
    }

    @Test
    fun `a new message during a round pulls the view down`() = runComposeUiTest {
        val messages = mutableStateOf(transcript(24))
        val listState = LazyListState()
        setContent { RoomTranscript(listState, messages.value) }
        waitForIdle()
        requireScrollable(listState, messages.value.size)
        val before = listState.firstVisibleItemIndex

        messages.value = messages.value + RoomMessage(
            speakerName = "Bob",
            text = "Newest reply — " + "streaming content ".repeat(6)
        )
        waitForIdle()

        assertTrue(
            "a new message arrived but the view stayed at index $before",
            listState.firstVisibleItemIndex > before
        )
        assertTrue(
            "the view should be following the newest message",
            listState.isShowingNewest() && !listState.canScrollForward
        )
    }

    @Test
    fun `a streaming reply keeps pulling the view down as it grows`() = runComposeUiTest {
        val last = RoomMessage(speakerName = "Bob", text = "Short")
        val messages = mutableStateOf(transcript(24) + last)
        val countBeforeGrowth = messages.value.size
        val listState = LazyListState()
        setContent { RoomTranscript(listState, messages.value) }
        waitForIdle()
        requireScrollable(listState, messages.value.size)
        assertTrue(
            "precondition: the view must start at the newest message, so there is " +
                "somewhere BELOW the fold for the grown reply to hide in",
            listState.isShowingNewest()
        )

        // Same SIZE, much longer text — exactly what a streaming chunk looks like.
        messages.value = messages.value.dropLast(1) + last.copy(
            text = "Short, and now considerably longer: " + "more output ".repeat(20)
        )
        waitForIdle()

        assertEquals(
            "this test only means something if the item count did NOT change — an append " +
                "would be followed by any size-keyed effect, an in-place edit by none",
            countBeforeGrowth,
            messages.value.size
        )
        assertTrue(
            "the reply grew in place but the view did not follow it " +
                "(canScrollForward=${listState.canScrollForward}, visible=" +
                "${listState.layoutInfo.visibleItemsInfo.map { it.index }})",
            listState.isShowingNewest() && !listState.canScrollForward
        )
    }
}
