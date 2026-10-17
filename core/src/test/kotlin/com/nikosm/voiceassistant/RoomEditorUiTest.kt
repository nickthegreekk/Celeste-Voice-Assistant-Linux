package com.nikosm.voiceassistant

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * The room EDITOR itself, driven through the real composable against the real service (ported
 * from upstream's instrumented test in M7).
 *
 * `RoomPersistenceTest` proves the service layer and `rooms_v1` are sound, so this suite exists
 * to pin down WHERE the edit is lost: between the editor's local field state and the service
 * call. It renders `RoomEditor` exactly as RoomSettings does and drives the same controls, then
 * reads the value back out of `SettingsManager.getRooms`.
 *
 * The regression: RoomEditor's name/cap fields are committed by a debounced auto-save AND a
 * dispose flush (mirroring PersonaEditor), so anything that leaves composition another way —
 * system back, the category's own back arrow, dismissing Settings — must still save.
 */
@OptIn(ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RoomEditorUiTest {

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
        services.clear()
        Dispatchers.resetMain()
    }

    private fun mainSync(block: () -> Unit) {
        block()
        mainDispatcher.scheduler.runCurrent()
    }

    private fun awaitPersist(
        what: String,
        timeoutMs: Long = 10_000,
        matches: (List<Room>) -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            // Both kinds of time: the virtual clock drives the debounce, the real one lets the
            // IO write land.
            mainDispatcher.scheduler.advanceTimeBy(1_000)
            mainDispatcher.scheduler.runCurrent()
            val onDisk = runCatching { settings.getRooms() }.getOrNull()
            if (onDisk != null && matches(onDisk)) return
            Thread.sleep(25)
        }
        val actual = runCatching {
            settings.getRooms()?.joinToString { "${it.name}[${it.memberNames}]" }
        }.getOrNull()
        fail("timed out after ${timeoutMs}ms waiting until $what; disk holds: $actual")
    }

    private fun seedRoom(): Room {
        val room = Room(name = "Editor Probe", memberNames = listOf("EP1", "EP2", "EP3"))
        service.addRoom(room)
        return room
    }

    private fun persisted(id: String): Room? =
        settings.getRooms()?.firstOrNull { it.id == id }

    private fun probePersona(name: String) = Persona(
        name = name,
        themeColor = Color(0xFF4ADE80),
        model = "[EditorProbeServer] some-model",
        systemPrompt = "probe",
        isCloud = false,
        backendUrl = "",
        allowGatewayFailover = false,
        voiceMode = VoiceMode.NONE
    )

    /**
     * Renders `RoomEditor` inside a scrollable parent — what the category page does in the
     * real app. The editor is deliberately a plain Column, so a test that mounts it on its
     * own must supply the scrolling parent itself; `performScrollTo()` relies on it.
     */
    @Composable
    private fun RoomEditorInPage(room: Room, personas: List<Persona>, onClose: () -> Unit) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            RoomEditor(room = room, personas = personas, onClose = onClose, service = service)
        }
    }

    @Test
    fun `editing the name and closing without done still persists`() = runComposeUiTest {
        val room = seedRoom()
        val personas = listOf(probePersona("EP1"), probePersona("EP2"), probePersona("EP3"))

        var editorOpen by mutableStateOf(true)
        setContent {
            MaterialTheme {
                if (editorOpen) {
                    RoomEditorInPage(room, personas, onClose = { editorOpen = false })
                }
            }
        }
        waitForIdle()

        // Type a new room name (field 0 is "Room name", field 1 is the cap).
        onAllNodes(hasSetTextAction())[0].performScrollTo().performTextClearance()
        onAllNodes(hasSetTextAction())[0].performTextInput("Renamed In UI")
        waitForIdle()

        // Close the way the back arrow / system back / Settings dismissal all do — NOT Done.
        editorOpen = false
        waitForIdle()
        mainDispatcher.scheduler.runCurrent()

        awaitPersist("a name typed and closed without Done never reached disk") {
            it.any { r -> r.id == room.id && r.name == "Renamed In UI" }
        }
        assertEquals("Renamed In UI", persisted(room.id)?.name)
    }

    /**
     * The editor's back chevron is reachable BY LABEL and actually exits, committing the edit
     * it was holding — so both the label and the commit it performs are covered at once.
     */
    @Test
    fun `the back chevron is labelled and exits the editor committing the edit`() = runComposeUiTest {
        val room = seedRoom()
        val personas = listOf(probePersona("EP1"), probePersona("EP2"), probePersona("EP3"))

        var editorOpen by mutableStateOf(true)
        setContent {
            MaterialTheme {
                if (editorOpen) {
                    RoomEditorInPage(room, personas, onClose = { editorOpen = false })
                }
            }
        }
        waitForIdle()

        // Selectable by its label, and a real control rather than decoration.
        val back = onNodeWithContentDescription("Back to rooms list")
        back.assertHasClickAction()

        onAllNodes(hasSetTextAction())[0].performScrollTo().performTextClearance()
        onAllNodes(hasSetTextAction())[0].performTextInput("Closed By Chevron")
        waitForIdle()

        back.performClick()
        waitForIdle()

        // The editor is gone...
        onAllNodesWithText("Edit room").assertCountEquals(0)
        // ...and the edit it was holding reached disk anyway.
        awaitPersist("an edit closed with the back chevron never reached disk") {
            it.any { r -> r.id == room.id && r.name == "Closed By Chevron" }
        }
    }

    @Test
    fun `editing the name and pressing done persists`() = runComposeUiTest {
        val room = seedRoom()
        val personas = listOf(probePersona("EP1"), probePersona("EP2"), probePersona("EP3"))

        setContent { MaterialTheme { RoomEditorInPage(room, personas, onClose = {}) } }
        waitForIdle()

        onAllNodes(hasSetTextAction())[0].performScrollTo().performTextClearance()
        onAllNodes(hasSetTextAction())[0].performTextInput("Committed With Done")
        waitForIdle()

        onNodeWithText("Done").performScrollTo().performClick()
        waitForIdle()

        awaitPersist("pressing Done did not reach disk") {
            it.any { r -> r.id == room.id && r.name == "Committed With Done" }
        }
        assertEquals("Committed With Done", persisted(room.id)?.name)
    }

    @Test
    fun `editing the cap and closing without done still persists`() = runComposeUiTest {
        val room = seedRoom()
        val personas = listOf(probePersona("EP1"), probePersona("EP2"), probePersona("EP3"))

        var editorOpen by mutableStateOf(true)
        setContent {
            MaterialTheme {
                if (editorOpen) {
                    RoomEditorInPage(room, personas, onClose = { editorOpen = false })
                }
            }
        }
        waitForIdle()

        // Field 1 is the autonomous-cap field.
        onAllNodes(hasSetTextAction())[1].performScrollTo().performTextClearance()
        onAllNodes(hasSetTextAction())[1].performTextInput("2")
        waitForIdle()

        editorOpen = false
        waitForIdle()
        mainDispatcher.scheduler.runCurrent()

        awaitPersist("a cap typed and closed without Done never reached disk") {
            it.any { r -> r.id == room.id && r.autoReplyCap == 2 }
        }
        assertEquals(2, persisted(room.id)?.autoReplyCap)
    }

    @Test
    fun `reordering through the editor control persists`() = runComposeUiTest {
        val room = seedRoom()
        val personas = listOf(probePersona("EP1"), probePersona("EP2"), probePersona("EP3"))

        setContent { MaterialTheme { RoomEditorInPage(room, personas, onClose = {}) } }
        waitForIdle()

        // The down arrow on the first member row (index 0 -> 1).
        onAllNodes(hasContentDescription("Move EP1 down"))[0].performScrollTo().performClick()
        waitForIdle()

        awaitPersist("a reorder through the editor never reached disk") {
            it.any { r -> r.id == room.id && r.memberNames == listOf("EP2", "EP1", "EP3") }
        }
        assertEquals(listOf("EP2", "EP1", "EP3"), persisted(room.id)?.memberNames)
    }
}
