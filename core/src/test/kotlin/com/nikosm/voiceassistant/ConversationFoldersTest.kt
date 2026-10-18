package com.nikosm.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString

/**
 * Conversations on disk, in the user's chosen shape (2026-10-18):
 * `~/.celeste-linux/<persona>/chat.json` and `~/.celeste-linux/rooms/<room>-<id>/chat.json`.
 *
 * These tests drive the real [SettingsManager] over a temp `homeDir`, so they cover the migration
 * from the old settings keys as well as the new writes — that migration is the part that touches
 * data a user already has.
 */
class ConversationFoldersTest {

    private val env = FakeAppEnv()
    private val store = InMemoryStore()
    private val manager = SettingsManager(env, store, NoImageCodec)

    private fun chatFile(name: String): Path =
        env.homeDir.resolve(".celeste-linux").resolve(name).resolve("chat.json")

    private fun messages(vararg texts: String) = texts.map { ChatMessage(role = "user", text = it) }

    @Test
    fun `a persona's chat is a file in its folder, not a settings key`() {
        manager.savePersonaMessages("Sylvia", messages("one", "two"))

        val file = chatFile("Sylvia")
        assertTrue("expected $file", Files.isRegularFile(file))
        assertTrue(Files.readString(file).contains("\"two\""))
        assertNull("the settings blob must not keep a second copy", store.getString("persona_messages_Sylvia"))
        assertEquals(listOf("one", "two"), manager.getPersonaMessages("Sylvia")!!.map { it.text })
    }

    @Test
    fun `an existing settings entry moves into the folder on first read`() {
        // What a pre-2026-10-18 installation looks like.
        store.edit().putString(
            "persona_messages_Old",
            SettingsJson.encodeToString(messages("from the blob"))
        ).commit()

        val loaded = manager.getPersonaMessages("Old")!!

        assertEquals(listOf("from the blob"), loaded.map { it.text })
        assertTrue(Files.isRegularFile(chatFile("Old")))
        assertNull("the legacy key is spent", store.getString("persona_messages_Old"))
    }

    @Test
    fun `a corrupted legacy entry stays a null read and is not migrated`() {
        store.edit().putString("persona_messages_Bad", "not json").commit()

        assertNull(manager.getPersonaMessages("Bad"))
        assertFalse("garbage must not become a chat file", Files.exists(chatFile("Bad")))
    }

    @Test
    fun `renaming a persona moves its whole folder`() {
        manager.savePersonaMessages("Before", messages("kept"))
        assertTrue(Files.isDirectory(chatFile("Before").parent))

        manager.migratePersonaHistory("Before", "After")

        assertFalse(Files.exists(chatFile("Before").parent))
        assertTrue(Files.isRegularFile(chatFile("After")))
        assertEquals(listOf("kept"), manager.getPersonaMessages("After")!!.map { it.text })
    }

    @Test
    fun `deleting a persona removes its folder`() {
        manager.savePersonaMessages("Gone", messages("bye"))
        assertTrue(Files.isDirectory(chatFile("Gone").parent))

        manager.deletePersonaHistory("Gone")

        assertFalse(Files.exists(chatFile("Gone").parent))
        assertNull(manager.getPersonaMessages("Gone"))
    }

    @Test
    fun `a room's transcript is a folder named for the room and its id`() {
        manager.saveRooms(listOf(Room(id = "room-1234", name = "The Den")))
        manager.saveRoomMessages(
            "room-1234",
            listOf(RoomMessage(speakerName = "user", text = "hello room")),
        )

        val dir = env.homeDir.resolve(".celeste-linux").resolve("rooms").resolve("The Den-room-1234")
        assertTrue("expected $dir", Files.isRegularFile(dir.resolve("chat.json")))
        assertNull(store.getString("room_messages_room-1234"))
        assertEquals(
            listOf("hello room"),
            manager.getRoomMessages("room-1234")!!.map { it.text },
        )
    }

    @Test
    fun `a legacy room transcript migrates on first read`() {
        manager.saveRooms(listOf(Room(id = "room-9", name = "Old Den")))
        store.edit().putString(
            "room_messages_room-9",
            SettingsJson.encodeToString(listOf(RoomMessage(speakerName = "user", text = "from the blob"))),
        ).commit()

        assertEquals(listOf("from the blob"), manager.getRoomMessages("room-9")!!.map { it.text })
        assertTrue(
            Files.isRegularFile(
                env.homeDir.resolve(".celeste-linux/rooms/Old Den-room-9/chat.json")
            )
        )
        assertNull(store.getString("room_messages_room-9"))
    }

    @Test
    fun `renaming a room follows its id, not its name`() {
        manager.saveRooms(listOf(Room(id = "room-55", name = "First")))
        manager.saveRoomMessages("room-55", listOf(RoomMessage(speakerName = "user", text = "kept")))

        manager.saveRooms(listOf(Room(id = "room-55", name = "Second")))
        manager.saveRoomMessages("room-55", listOf(RoomMessage(speakerName = "user", text = "kept")))

        val roomsRoot = env.homeDir.resolve(".celeste-linux/rooms")
        val folders = Files.list(roomsRoot).use { it.toList() }
        assertEquals("a rename must move the folder, not leave a second one", 1, folders.size)
        assertEquals("Second-room-55", folders.single().fileName.toString())
    }

    @Test
    fun `folder names are one-to-one even when names sanitise alike`() {
        val a = ConversationFolders.personaFolderName("a/b")
        val b = ConversationFolders.personaFolderName("a_b")

        assertTrue("$a vs $b", a != b)
        assertTrue(a.startsWith("a_b-"))
        // Ordinary names stay readable.
        assertEquals("Sylvia", ConversationFolders.personaFolderName("Sylvia"))
        assertEquals("Translator (inn)", ConversationFolders.personaFolderName("Translator (inn)"))
        // A persona called "rooms" cannot take the rooms directory.
        assertTrue(ConversationFolders.personaFolderName("rooms").startsWith("rooms-"))
    }
}
