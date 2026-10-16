package com.nikosm.voiceassistant

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The containment rules, which are the part of M8 that has to be right: a workspace is a promise the
 * user made to themselves about which files the assistant can see, and every escape from it is
 * silent — the content goes into a prompt, possibly a cloud one, and nothing on screen says so.
 *
 * The tests are the escape *shapes* rather than the happy path, because the happy path is one line of
 * code and these are the ones that need to be true: `..`, absolute paths, symlinks out, symlinked
 * `~/.ssh`, and a workspace that happens to contain the excluded paths (a workspace of `$HOME`, the
 * obvious mistake). Every refusal is asserted on its *reason*, because that sentence is what the
 * model is told and what a reader of the audit line will see.
 */
class FsWorkspaceTest {

    private val root: Path = Files.createTempDirectory("fs-root").also { it.toFile().deleteOnExit() }
    private val outside: Path = Files.createTempDirectory("fs-outside").also { it.toFile().deleteOnExit() }
    private val home: Path = Files.createTempDirectory("fs-home").also { it.toFile().deleteOnExit() }

    // Inside the fake home, so the "workspace of $HOME" shape below is the real one: the excluded
    // paths sit *within* the workspace and must be refused individually rather than wholesale.
    private val ssh: Path = home.resolve(".ssh").also { Files.createDirectories(it) }
    private val config: Path = home.resolve(".config/celeste-linux").also { Files.createDirectories(it) }
    private val state: Path = home.resolve(".local/state/celeste-linux").also { Files.createDirectories(it) }
    private val excluded: List<Path> = alwaysExcludedPaths(config, home, state)

    @After
    fun cleanUp() {
        listOf(root, outside, home).forEach { dir ->
            if (Files.exists(dir)) {
                Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } }
            }
        }
    }

    /** Runs [block] and returns the refusal's reason, failing if it did not refuse at all. */
    private fun refusal(block: () -> Unit): String =
        try {
            block()
            fail("expected a refusal, and the call was allowed")
            ""
        } catch (e: FsToolRefusal) {
            e.reason
        }

    // ------------------------------------------------------------------ the ordinary path

    @Test
    fun `a relative path inside the workspace resolves, and an absolute one that lands inside is allowed`() {
        val file = root.resolve("notes/todo.md").also { Files.createDirectories(it.parent); Files.writeString(it, "x") }

        assertEquals(file.toRealPath(), resolveInside(root, "notes/todo.md", forWrite = false, excluded))
        assertEquals(
            "models produce absolute paths often; refusing them outright would waste a round trip",
            file.toRealPath(),
            resolveInside(root, file.toString(), forWrite = false, excluded),
        )
    }

    @Test
    fun `a symlink that stays inside the workspace is followed`() {
        val real = root.resolve("real").also { Files.createDirectories(it); Files.writeString(it.resolve("a.txt"), "x") }
        val link = root.resolve("link").also { Files.createSymbolicLink(it, real) }

        assertEquals(
            real.resolve("a.txt").toRealPath(),
            resolveInside(root, "link/a.txt", forWrite = false, excluded),
        )
    }

    // ------------------------------------------------------------------ the escapes

    @Test
    fun `dot dot out of the workspace is refused`() {
        Files.writeString(outside.resolve("secret.txt"), "x")

        assertTrue(refusal { resolveInside(root, "../${outside.fileName}/secret.txt", false, excluded) }.contains("outside the workspace"))
    }

    @Test
    fun `an absolute path outside the workspace is refused`() {
        val target = outside.resolve("secret.txt").also { Files.writeString(it, "x") }

        assertTrue(refusal { resolveInside(root, target.toString(), false, excluded) }.contains("outside the workspace"))
    }

    @Test
    fun `a symlink pointing out of the workspace is refused for reading`() {
        // The escape that a string check cannot see: an entirely ordinary-looking name inside the
        // workspace, resolving somewhere else entirely.
        val target = outside.resolve("secret.txt").also { Files.writeString(it, "x") }
        root.resolve("innocent.txt").also { Files.createSymbolicLink(it, target) }

        assertTrue(refusal { resolveInside(root, "innocent.txt", false, excluded) }.contains("resolves outside the workspace"))
    }

    @Test
    fun `a write through a symlink out of the workspace is refused`() {
        val target = outside.resolve("victim.txt").also { Files.writeString(it, "original") }
        root.resolve("handy.txt").also { Files.createSymbolicLink(it, target) }

        assertTrue(refusal { resolveInside(root, "handy.txt", forWrite = true, excluded) }.contains("symlink out of the workspace"))
        assertEquals("and the file outside is untouched", "original", Files.readString(target))
    }

    @Test
    fun `a write whose parent folder is a symlink out of the workspace is refused`() {
        val link = root.resolve("elsewhere").also { Files.createSymbolicLink(it, outside) }

        assertTrue(refusal { resolveInside(root, "elsewhere/new.txt", forWrite = true, excluded) }.contains("resolves outside the workspace"))
        assertFalse("nothing was created out there", Files.exists(outside.resolve("new.txt")))
    }

    @Test
    fun `writing into a folder that does not exist is refused rather than invented`() {
        assertTrue(refusal { resolveInside(root, "no/such/folder/file.txt", forWrite = true, excluded) }.contains("does not exist"))
    }

    @Test
    fun `a write whose name is not a plain file name is refused`() {
        // `..` and `.` are the two names that would otherwise hand the resolver's own parent logic
        // back to the caller as an escape.
        assertTrue(refusal { resolveInside(root, "..", forWrite = true, excluded) }.isNotEmpty())
        assertTrue(refusal { resolveInside(root, ".", forWrite = true, excluded) }.isNotEmpty())
    }

    // ------------------------------------------------------------------ the excluded paths

    @Test
    fun `keys and credentials are refused even when the workspace is the home folder`() {
        // The shape that matters: a workspace of `$HOME` is the obvious user mistake, and it must
        // refuse these two paths *specifically* rather than refusing the whole folder — the notes
        // beside them are exactly what the user wanted reachable.
        val key = ssh.resolve("id_rsa").also { Files.writeString(it, "PRIVATE") }
        val settings = config.resolve("settings.json").also { Files.writeString(it, "{}") }

        assertTrue(refusal { resolveInside(home, ".ssh/id_rsa", false, excluded) }.contains("always excluded"))
        assertTrue(refusal { resolveInside(home, ".config/celeste-linux/settings.json", false, excluded) }.contains("always excluded"))
        assertTrue(refusal { resolveInside(home, key.toString(), false, excluded) }.contains("always excluded"))
        assertTrue(refusal { resolveInside(home, settings.toString(), false, excluded) }.contains("always excluded"))
    }

    @Test
    fun `a symlink to the keys is refused too, because the refusal is about where it lands`() {
        ssh.resolve("id_rsa").also { Files.writeString(it, "PRIVATE") }
        root.resolve("my-notes.txt").also { Files.createSymbolicLink(it, ssh.resolve("id_rsa")) }

        assertTrue(refusal { resolveInside(root, "my-notes.txt", false, excluded) }.contains("always excluded"))
    }

    @Test
    fun `a write through a link aimed at the keys is refused, though it lands inside the workspace`() {
        // The hole the adversarial review found. The excluded check saw the *spelling*
        // ("notes/settings.json") and the containment check saw a path inside the root — both said
        // yes — while the file actually written was the credentials file the link pointed at. A
        // dotfile manager creating that link is the ordinary way it happens.
        val notes = home.resolve("notes").also { Files.createDirectories(it) }
        val settings = config.resolve("settings.json").also { Files.writeString(it, "{\"apiKey\":\"real\"}") }
        notes.resolve("settings.json").also { Files.createSymbolicLink(it, settings) }

        val reason = refusal { resolveInside(home, "notes/settings.json", forWrite = true, excluded) }

        assertTrue("it says why: $reason", reason.contains("always excluded"))
        assertEquals("and the credentials file is untouched", "{\"apiKey\":\"real\"}", Files.readString(settings))
    }

    @Test
    fun `the audit log is excluded too, so a model cannot erase its own trail`() {
        // The review's finding: with a workspace of `$HOME`, a write to the audit file replaced it
        // and the next call appended a clean line to it — the safety feature's own evidence,
        // destroyable by the thing it records.
        Files.writeString(state.resolve("fs-tool-audit.jsonl"), "old record")

        val reason = refusal {
            resolveInside(home, ".local/state/celeste-linux/fs-tool-audit.jsonl", forWrite = true, excluded)
        }

        assertTrue(reason.contains("always excluded"))
    }

    @Test
    fun `a sibling folder whose name merely starts the same is not excluded`() {
        // `startsWith` on a `Path` compares elements, so this is true by construction — and it is
        // asserted because the version of this code that compared *strings* would be a bug that
        // quietly hides a user's ordinary folder.
        val sibling = home.resolve(".ssh-backup").also { Files.createDirectories(it); Files.writeString(it.resolve("notes.txt"), "x") }

        assertEquals(
            sibling.resolve("notes.txt").toRealPath(),
            resolveInside(home, ".ssh-backup/notes.txt", false, excluded),
        )
    }

    @Test
    fun `an excluded folder cannot be chosen as the workspace, and a file cannot either`() {
        assertNotNull("picking ~/.ssh as a workspace is refused where the user can see why", workspaceRootRefusal(ssh, excluded))
        assertNotNull("and so is the credentials folder", workspaceRootRefusal(config, excluded))
        assertNotNull(
            "a file is not a workspace",
            workspaceRootRefusal(Files.createTempFile("not-a-dir", ".txt"), excluded),
        )
        assertNull("an ordinary folder is fine", workspaceRootRefusal(root, excluded))
    }

    // ------------------------------------------------------------------ the mode

    @Test
    fun `the mode gates reads and writes, and off is what an unreadable value means`() {
        assertFalse(FsToolMode.OFF.allowsReads)
        assertFalse(FsToolMode.OFF.allowsWrites)
        assertTrue(FsToolMode.READ_ONLY.allowsReads)
        assertFalse("read-only means read-only", FsToolMode.READ_ONLY.allowsWrites)
        assertTrue(FsToolMode.READ_WRITE.allowsWrites)

        assertEquals(FsToolMode.READ_WRITE, FsToolMode.fromName("read_write"))
        assertEquals(FsToolMode.READ_ONLY, FsToolMode.fromName("Read-Only"))
        assertEquals("an absent value is off, not the permissive default", FsToolMode.OFF, FsToolMode.fromName(null))
        assertEquals("and so is one this build cannot read", FsToolMode.OFF, FsToolMode.fromName("READ_WRITE_MAYBE"))
    }
}
