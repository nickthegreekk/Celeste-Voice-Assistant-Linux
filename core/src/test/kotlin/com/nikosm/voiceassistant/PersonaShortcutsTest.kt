package com.nikosm.voiceassistant

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Per-persona `.desktop` entries: the half that can be pinned without a desktop menu.
 *
 * Three things here are worth a test rather than a hope. **The file name is the identity** — the name
 * is the id, as it was upstream, so two personas must never share a file, and the case that makes
 * that sharp is a pair whose names differ only in something a file name cannot hold. **The `Exec`
 * line is escaped to the desktop-entry specification, not to the shell** — a name with a quote in it
 * must still open the right persona. And **the write is idempotent and the removal is total**: the
 * button can be pressed twice, and a renamed or deleted persona must not leave an entry behind that
 * opens somebody else.
 */
class PersonaShortcutsTest {

    private val root: Path = Files.createTempDirectory("celeste-shortcuts").also { it.toFile().deleteOnExit() }
    private val applications: Path = root.resolve("applications")

    @After
    fun cleanUp() {
        if (Files.exists(root)) {
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } }
        }
    }

    @Test
    fun `two names that slug the same still get two files`() {
        // "Vicky" and "Vicky " both slug to `vicky`, and a file name cannot hold the trailing space
        // that tells them apart — so the slug alone would let the second entry silently replace the
        // first, losing a shortcut the user asked for.
        assertNotEquals(personaShortcutSlug("Vicky"), personaShortcutSlug("Vicky "))
        assertNotEquals(
            personaShortcutFile("Vicky", applications),
            personaShortcutFile("Vicky ", applications),
        )
    }

    @Test
    fun `the file name is derived from the persona and lives in the menu's directory`() {
        val file = personaShortcutFile("Friend", applications)

        assertEquals(applications, file.parent)
        assertTrue("a menu only reads files ending in .desktop", file.fileName.toString().endsWith(".desktop"))
        assertTrue(file.fileName.toString().startsWith(PERSONA_SHORTCUT_PREFIX))
        assertTrue("readable rather than a hash alone", file.fileName.toString().contains("friend"))
    }

    @Test
    fun `the entry opens the persona through the deep link the notifications use`() {
        val entry = personaDesktopEntry("Friend", artworkPath = null)

        assertTrue(entry.startsWith("[Desktop Entry]"))
        assertTrue(entry.contains("Type=Application"))
        assertTrue(entry.contains("Name=Friend"))
        assertTrue(
            "the same one-shot handoff --persona that MainScreen already honours",
            entry.contains("Exec=$PERSONA_SHORTCUT_EXEC --persona \"Friend\""),
        )
        assertFalse("no artwork, no Icon line rather than an empty one", entry.contains("Icon="))
    }

    @Test
    fun `a name with quoting characters is escaped the way the specification requires`() {
        // A quote, a backslash, a dollar and a backtick are the four characters the desktop-entry
        // spec requires to be backslash-escaped inside a quoted Exec argument. Getting this wrong is
        // not a crash: it is an entry that opens the wrong persona, or none.
        assertEquals("""a\"b""", escapeForDesktopExec("""a"b"""))
        assertEquals("""a\\b""", escapeForDesktopExec("""a\b"""))
        assertEquals("a\\\$b", escapeForDesktopExec("a\$b"))
        assertEquals("a\\`b", escapeForDesktopExec("a`b"))
        assertTrue(
            personaDesktopEntry("""He said "hi"""", null).contains("""--persona "He said \"hi\"""""),
        )
    }

    @Test
    fun `writing twice rewrites the entry rather than adding a second one`() {
        val first = writePersonaShortcut("Friend", null, applications)
        val second = writePersonaShortcut("Friend", "/tmp/art.png", applications)

        assertEquals("the same file both times", first, second)
        assertEquals(1, Files.list(applications).use { it.count() })
        assertTrue("and the artwork arrived with the second write", Files.readString(second!!).contains("Icon=/tmp/art.png"))
    }

    @Test
    fun `removal takes the entry away, and says whether there was one`() {
        assertFalse("nothing to remove yet", removePersonaShortcut("Friend", applications))
        writePersonaShortcut("Friend", null, applications)

        assertTrue("the entry existed and went", removePersonaShortcut("Friend", applications))
        assertFalse("and a second removal is not a failure", removePersonaShortcut("Friend", applications))
    }

    @Test
    fun `a directory that cannot be created is a null, not an exception`() {
        // The button is reachable when the data home is unwritable — a read-only mount, a full disk.
        // That is a snackbar, not a crash.
        val blocked = root.resolve("not-a-directory").also { Files.write(it, byteArrayOf(1)) }

        assertNull(writePersonaShortcut("Friend", null, blocked.resolve("applications")))
    }
}
