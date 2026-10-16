package com.nikosm.voiceassistant

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

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
        // A dock draws a running window's icon by matching the window's `WM_CLASS` against the
        // entries that claim it. `Main.kt` sets AWT's `sun.awt.X11.XWMClassName` to this same
        // string; if these two ever drift, every entry loses its icon rather than the wrong one
        // being drawn — which is exactly what happened before either of them existed.
        assertEquals("StartupWMClass=$PERSONA_SHORTCUT_EXEC", entry.lineSequence().first { it.startsWith("StartupWMClass=") })
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
    fun `the Desktop copy is written when there is a Desktop, and it is executable`() {
        // The mode is the point of this one. `~/.local/share/applications` is read by menu machinery
        // that ignores it; a `.desktop` file sitting *on* a Desktop is a launcher the file manager
        // offers to run, and GNOME, KDE and XFCE all require the executable bit for that. Without it
        // the icon appears and double-clicking opens a text editor — worse than not writing it.
        // Asked for by the user on 2026-10-16, whose Desktop is where they keep their shortcuts.
        val desktop = root.resolve("Desktop").also { Files.createDirectories(it) }

        val written = writePersonaShortcut("Friend", null, applications, desktop)

        val onDesktop = personaShortcutFile("Friend", desktop)
        assertEquals(applications, written?.parent)
        assertTrue("the Desktop copy exists", Files.exists(onDesktop))
        assertEquals(
            "byte for byte the same entry, so the two copies cannot drift apart",
            Files.readString(written!!),
            Files.readString(onDesktop),
        )
        assertEquals(
            "rwxr-xr-x, or it is not a launcher",
            "rwxr-xr-x",
            PosixFilePermissions.toString(Files.getPosixFilePermissions(onDesktop)),
        )
    }

    @Test
    fun `a machine with no Desktop gets no Desktop copy, and no new directory`() {
        // Not creating `~/Desktop` is the decision, not an oversight: a desktop whose owner keeps
        // nothing there should not grow a folder to hold one file, and the caller is told the
        // shortcut exists (it does — in the menu) rather than that the press failed.
        val missing = root.resolve("Desktop")

        val written = writePersonaShortcut("Friend", null, applications, missing)

        assertNotNull("the menu entry is still written", written)
        assertFalse("and nothing invented a Desktop", Files.exists(missing))
    }

    @Test
    fun `removal takes the Desktop copy too`() {
        val desktop = root.resolve("Desktop").also { Files.createDirectories(it) }
        writePersonaShortcut("Friend", null, applications, desktop)

        assertTrue(removePersonaShortcut("Friend", applications, desktop))

        assertFalse(Files.exists(personaShortcutFile("Friend", applications)))
        assertFalse(
            "a shortcut that outlives its persona opens whichever one is selected",
            Files.exists(personaShortcutFile("Friend", desktop)),
        )
    }

    @Test
    fun `a directory that cannot be created is a null, not an exception`() {
        // The button is reachable when the data home is unwritable — a read-only mount, a full disk.
        // That is a snackbar, not a crash.
        val blocked = root.resolve("not-a-directory").also { Files.write(it, byteArrayOf(1)) }

        assertNull(writePersonaShortcut("Friend", null, blocked.resolve("applications")))
    }
}
