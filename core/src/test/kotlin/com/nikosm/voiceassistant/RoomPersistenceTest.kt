package com.nikosm.voiceassistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Room EDIT PERSISTENCE (ported from upstream's instrumented test in M7).
 *
 * The bug this pins down is a *silent* one: the editor updated what the user saw, and the list
 * row even reflected it in memory, but nothing reached `rooms_v1`. Every assertion therefore
 * reads back OUT OF `SettingsManager.getRooms` — what a relaunch would actually load — rather
 * than the in-memory `service.rooms` flow, which is exactly what made the broken build look
 * correct while it was open.
 *
 * Persistence is debounced and re-reads the flows at write time, so each assertion waits for
 * the write to land. The port change is only the harness: one service over a fresh in-memory
 * store per test, instead of binding the device's and restoring its rooms afterwards.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RoomPersistenceTest {
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

    /** Poll the PERSISTED list until [matches], so we assert what a relaunch would read. */
    private fun awaitPersist(
        what: String,
        timeoutMs: Long = 10_000,
        matches: (List<Room>) -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val onDisk = runCatching { settings.getRooms() }.getOrNull()
            if (onDisk != null && matches(onDisk)) return
            Thread.sleep(20)
        }
        val actual = runCatching {
            settings.getRooms()?.joinToString { "${it.name}[${it.memberNames}](${it.autoReplyCap})" }
        }.getOrNull()
        assertTrue("timed out after ${timeoutMs}ms waiting until $what; disk holds: $actual", false)
    }

    /** Create a probe room through the production path and confirm it landed. */
    private fun AssistantService.seedRoom(): Room {
        val room = Room(name = "Persistence Probe", memberNames = listOf("PM1", "PM2", "PM3"))
        addRoom(room)
        awaitPersist("the probe room was never written to disk") { it.any { r -> r.id == room.id } }
        return room
    }

    /** The live copy of [room], so each edit starts from current state. */
    private fun AssistantService.live(room: Room): Room = rooms.value.first { it.id == room.id }

    private fun persisted(id: String): Room? = settings.getRooms()?.firstOrNull { it.id == id }

    @Test
    fun `renaming a room survives a write-read cycle`() {
        val service = newService()
        val room = service.seedRoom()

        // Exactly what RoomEditor's Done button does for the name field.
        service.updateRoom(service.live(room).copy(name = "Renamed Probe"))

        awaitPersist("the renamed room never reached disk") {
            it.any { r -> r.id == room.id && r.name == "Renamed Probe" }
        }
        assertEquals("Renamed Probe", persisted(room.id)?.name)
    }

    @Test
    fun `reordering members survives a write-read cycle`() {
        val service = newService()
        val room = service.seedRoom()

        // Exactly what the editor's up/down arrows do.
        service.moveRoomMember(room.id, 2, 0)

        awaitPersist("the reordered members never reached disk") {
            it.any { r -> r.id == room.id && r.memberNames == listOf("PM3", "PM1", "PM2") }
        }
        assertEquals(listOf("PM3", "PM1", "PM2"), persisted(room.id)?.memberNames)
    }

    @Test
    fun `adding and removing a member survives a write-read cycle`() {
        val service = newService()
        val room = service.seedRoom()

        service.updateRoom(service.live(room).copy(memberNames = service.live(room).memberNames + "PM4"))
        awaitPersist("the added member never reached disk") {
            it.any { r -> r.id == room.id && r.memberNames.contains("PM4") }
        }

        service.updateRoom(
            service.live(room).copy(memberNames = service.live(room).memberNames.filterNot { it == "PM1" })
        )
        awaitPersist("the removed member never reached disk") {
            it.any { r -> r.id == room.id && !r.memberNames.contains("PM1") }
        }
        assertEquals(listOf("PM2", "PM3", "PM4"), persisted(room.id)?.memberNames)
    }

    @Test
    fun `a per-room cap override survives a write-read cycle`() {
        val service = newService()
        val room = service.seedRoom()

        // Driven through the editor's own parser, so a "0" cannot silently collapse back to
        // the inherit sentinel on its way to disk.
        val committed = autoReplyCapOverrideOrNull("0")
        assertEquals("the editor must accept a typed 0 as a real cap", 0, committed)
        service.updateRoomCap(room.id, committed)

        awaitPersist("the cap override never reached disk") {
            it.any { r -> r.id == room.id && r.autoReplyCap == 0 }
        }
        assertEquals(0, persisted(room.id)?.autoReplyCap)
    }

    @Test
    fun `name, reorder and cap from one editing session all reach disk together`() {
        // The user-visible bug was "none of it sticks". This asserts the whole editor's output
        // lands in one debounced write, so a partial future fix fails here too.
        val service = newService()
        val room = service.seedRoom()

        service.moveRoomMember(room.id, 0, 1)
        service.updateRoomCap(room.id, 2)
        service.updateRoom(service.live(room).copy(name = "All Three"))

        awaitPersist("not every edit reached disk together") {
            it.any { r ->
                r.id == room.id &&
                    r.name == "All Three" &&
                    r.memberNames == listOf("PM2", "PM1", "PM3") &&
                    r.autoReplyCap == 2
            }
        }
        val stored = persisted(room.id)
        assertNotNull("the probe room vanished from disk", stored)
        assertEquals("All Three", stored?.name)
        assertEquals(listOf("PM2", "PM1", "PM3"), stored?.memberNames)
        assertEquals(2, stored?.autoReplyCap)
    }
}
