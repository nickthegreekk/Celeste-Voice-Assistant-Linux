package com.nikosm.voiceassistant.linux

import com.nikosm.voiceassistant.AppLogger
import com.nikosm.voiceassistant.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * The settings store is where every setting the user has ever changed ends up, and the port's
 * promise is that the Android blob survives the move. So these tests are mostly about the two
 * ways that promise breaks: a value that does not round-trip, and a file that is destroyed rather
 * than set aside when it will not parse.
 *
 * `TemporaryFolder` rather than the real `~/.config` — AGENTS.md §4 requires it, and this store
 * would otherwise overwrite the author's settings on every test run.
 */
class JsonSettingsStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun storeFile(): Path = temp.root.toPath().resolve(JsonSettingsStore.FILE_NAME)

    private fun newStore(): JsonSettingsStore = JsonSettingsStore(storeFile())

    @Test
    fun `every supported type round-trips through the file`() {
        newStore().edit()
            .putString("personas_v2", """[{"name":"Nikos"}]""")
            .putLong("last_price_sync_ts", 1_765_000_000_000L)
            .putFloat("chat_font_size", 14.5f)
            .putBoolean("is_first_run", false)
            .commit()

        // A second store on the same file, so this is the file talking and not the first store's
        // in-memory state.
        val reopened = newStore()
        assertEquals("""[{"name":"Nikos"}]""", reopened.getString("personas_v2"))
        assertEquals(1_765_000_000_000L, reopened.getLong("last_price_sync_ts"))
        assertEquals(14.5f, reopened.getFloat("chat_font_size"), 0.0f)
        assertFalse(reopened.getBoolean("is_first_run", true))
    }

    @Test
    fun `a float put in this session reads back as a float`() {
        // The in-memory map holds the Float that putFloat stored; only a value read back from JSON
        // is a Double. Casting to Double here made every same-session read return the default and
        // log a spurious type mismatch.
        val store = newStore()
        store.edit().putFloat("chat_font_size", 14.5f).commit()

        assertEquals(14.5f, store.getFloat("chat_font_size", 0f), 0.0f)
    }

    @Test
    fun `a full-precision Long survives exactly`() {
        // `total_cost_bits` is Double.doubleToRawLongBits, so it is an arbitrary 64-bit pattern and
        // not a tidy number — a round trip through a JSON *double* would silently change it.
        val bits = java.lang.Double.doubleToRawLongBits(0.1)
        newStore().edit().putLong("total_cost_bits", bits).commit()

        assertEquals(bits, newStore().getLong("total_cost_bits"))
    }

    @Test
    fun `absent keys return the caller's default`() {
        val store = newStore()
        assertNull(store.getString("never_set"))
        assertEquals("fallback", store.getString("never_set", "fallback"))
        assertEquals(7L, store.getLong("never_set", 7L))
        assertEquals(2.5f, store.getFloat("never_set", 2.5f), 0.0f)
        assertTrue(store.getBoolean("never_set", true))
    }

    @Test
    fun `remove drops the key rather than storing a null`() {
        val store = newStore()
        store.edit().putString("chat_history", "legacy").putString("chat_history_v2", "current").commit()
        store.edit().remove("chat_history").commit()

        assertFalse(store.allKeys().contains("chat_history"))
        assertNull(newStore().getString("chat_history"))
        assertEquals("current", newStore().getString("chat_history_v2"))
    }

    @Test
    fun `allKeys is a snapshot, so a sweep can delete while it walks`() {
        // importBackup enumerates persona_messages_* and deletes entries as it goes. A live view
        // would be a ConcurrentModificationException on the first orphan.
        val store = newStore()
        store.edit()
            .putString("persona_messages_A", "[]")
            .putString("persona_messages_B", "[]")
            .putString("room_messages_r1", "[]")
            .commit()

        val snapshot = store.allKeys()
        store.edit().remove("persona_messages_A").commit()

        assertEquals(setOf("persona_messages_A", "persona_messages_B", "room_messages_r1"), snapshot)
        assertEquals(setOf("persona_messages_B", "room_messages_r1"), store.allKeys())
        assertEquals(
            setOf("persona_messages_B"),
            store.allKeys().filter { it.startsWith("persona_messages_") }.toSet(),
        )
    }

    @Test
    fun `one edit is one commit and lands together`() {
        // persistSettings writes a dozen keys in a row; migratePersonaHistory moves one key to
        // another name. Both rely on the batch being all-or-nothing.
        val store = newStore()
        store.edit()
            .putString("persona_messages_New", "[]")
            .remove("persona_messages_Old")
            .commit()

        val reopened = newStore()
        assertEquals("[]", reopened.getString("persona_messages_New"))
        assertNull(reopened.getString("persona_messages_Old"))
    }

    @Test
    fun `an unparseable file is moved aside and the store starts empty`() {
        val original = """{"personas_v2": "truncated"""
        Files.createDirectories(storeFile().parent)
        Files.writeString(storeFile(), original)

        val store = newStore()

        assertEquals(emptySet<String>(), store.allKeys())
        val quarantined = Files.list(temp.root.toPath()).use { it.toList() }
            .filter { it.fileName.toString().startsWith("${JsonSettingsStore.FILE_NAME}.corrupt-") }
        assertEquals("the damaged file must be kept for inspection", 1, quarantined.size)
        assertEquals(original, Files.readString(quarantined.single()))
    }

    @Test
    fun `a file that cannot be quarantined is not echoed into the log`() {
        // The blob holds provider keys and the RAG password. The failure path used to interpolate
        // the whole file into an ERROR line, which journald keeps — the one place the credentials
        // must never go. The move is made to fail by taking write permission off the folder; root
        // ignores that, so the case is skipped when the suite runs as root.
        Assume.assumeFalse("root can write to a read-only folder", System.getProperty("user.name") == "root")
        val dir = temp.root.toPath()
        Files.writeString(storeFile(), """{"rag_password":"hunter2","truncated":""")
        val original = Files.getPosixFilePermissions(dir)
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-x------"))
        try {
            val logger = RecordingLogger()
            val store = JsonSettingsStore(storeFile(), logger)

            assertEquals(emptySet<String>(), store.allKeys())
            assertTrue(
                "the failure to move the file aside is still reported: ${logger.lines}",
                logger.lines.any { it.contains("could not be moved aside") },
            )
            assertFalse(
                "the settings contents must never reach the log: ${logger.lines}",
                logger.lines.any { it.contains("hunter2") },
            )
        } finally {
            Files.setPosixFilePermissions(dir, original)
        }
    }

    @Test
    fun `an empty file is simply empty settings`() {
        Files.createDirectories(storeFile().parent)
        Files.writeString(storeFile(), "")

        assertEquals(emptySet<String>(), newStore().allKeys())
    }

    @Test
    fun `the file is created 0600 and stays that way across writes`() {
        // It holds server credentials and the RAG password; see the class comment.
        val store = newStore()
        store.edit().putString("rag_password", "hunter2").commit()
        assertEquals(PRIVATE, Files.getPosixFilePermissions(storeFile()))

        store.edit().putString("rag_password", "hunter3").commit()
        assertEquals(PRIVATE, Files.getPosixFilePermissions(storeFile()))
    }

    @Test
    fun `no temporary file is left behind`() {
        // The atomic write goes through settings.json.tmp; a leftover would be a second copy of
        // every credential sitting next to the first.
        newStore().edit().putString("a", "b").commit()

        val entries = Files.list(temp.root.toPath()).use { it.map { p -> p.fileName.toString() }.toList() }
        assertEquals(listOf(JsonSettingsStore.FILE_NAME), entries.sorted())
    }

    @Test
    fun `the config directory is created when it does not exist`() {
        val nested = temp.root.toPath().resolve("celeste-linux").resolve(JsonSettingsStore.FILE_NAME)
        JsonSettingsStore(nested).edit().putString("k", "v").commit()

        assertTrue(Files.exists(nested))
        assertEquals("v", JsonSettingsStore(nested).getString("k"))
    }

    @Test
    fun `a value of the wrong type reads as the default instead of throwing`() {
        // Android throws ClassCastException here. This application's habit with damage it cannot
        // interpret is to degrade one entry, not to fail startup, and a settings read happens
        // during service construction.
        val store = newStore()
        store.edit().putString("chat_font_size", "not a number").commit()

        assertEquals(14f, store.getFloat("chat_font_size", 14f), 0.0f)
    }

    private class RecordingLogger : AppLogger {
        val lines = mutableListOf<String>()

        override fun log(level: LogLevel, tag: String, message: String, error: Throwable?) {
            lines += message
        }
    }

    private companion object {
        val PRIVATE = PosixFilePermissions.fromString("rw-------")
    }
}
