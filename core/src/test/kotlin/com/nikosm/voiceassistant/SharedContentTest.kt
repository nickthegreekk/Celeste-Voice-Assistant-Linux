package com.nikosm.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

/**
 * The share target, on this platform's terms.
 *
 * Upstream's test of the same name had sixteen cases, eleven of which drive `parseSharedIntent` with
 * an `ACTION_SEND` action, a MIME type and an `EXTRA_SUBJECT` — none of which a command line has.
 * Those became the CLI cases below, which ask the same questions in the vocabulary that exists here:
 * what is a share, what is not, and what wins when two are given. The five append cases came across
 * unchanged in substance, because `appendSharedTextToDraft` did; `docs/TEST-MAP.md` records the
 * adaptation, since a test that needs retargeting never arrives byte-identical.
 */
class SharedContentTest {

    /** Stands in for `user.home`; the parser is handed it rather than reading it (:core resolves
     * nothing — AGENTS.md §4). */
    private val home: Path = Path.of("/home/tester")

    // ------------------------------------------------------------------ the append and its cap

    @Test
    fun `text lands in an empty draft as-is`() {
        assertEquals("hello", appendSharedTextToDraft("", "hello").draft)
    }

    @Test
    fun `text is appended to an existing draft after a newline`() {
        // The user's own half-typed turn is never overwritten by something that arrived from
        // outside; it is added above.
        assertEquals("mine\nshared", appendSharedTextToDraft("mine", "shared").draft)
    }

    @Test
    fun `the cap is thirty thousand characters, and exactly the cap is not truncated`() {
        val exactly = "x".repeat(MAX_SHARED_TEXT_CHARS)
        val landed = appendSharedTextToDraft("", exactly)

        assertEquals(MAX_SHARED_TEXT_CHARS, landed.draft.length)
        assertFalse(landed.truncated)
    }

    @Test
    fun `one character over the cap is truncated to it, and the user is told`() {
        val over = "x".repeat(MAX_SHARED_TEXT_CHARS + 1)
        val landed = appendSharedTextToDraft("", over)

        assertEquals(MAX_SHARED_TEXT_CHARS, landed.draft.length)
        assertTrue("the caller has to be able to say the text was trimmed", landed.truncated)
    }

    @Test
    fun `truncation keeps the head of the shared text and the draft separator`() {
        // The head, not the tail: a shared article is useful from its beginning. And the draft's
        // own text must survive at the front, however long the payload was.
        val landed = appendSharedTextToDraft("mine", "a".repeat(MAX_SHARED_TEXT_CHARS + 10))

        assertTrue(landed.draft.startsWith("mine\n"))
        assertEquals("mine".length + 1 + MAX_SHARED_TEXT_CHARS, landed.draft.length)
    }

    // ------------------------------------------------------------------ the command line

    @Test
    fun `a text payload is a text request`() {
        assertEquals(
            SharedRequest.Text("hello there"),
            parseShareArgs(listOf("--send-text", "hello there"), home),
        )
    }

    @Test
    fun `an image path is an image request`() {
        // Upstream carried a `Uri` because a share grant dies with the intent; a path from a command
        // line outlives it, so nothing has to be copied before the attachment is set.
        assertEquals(
            SharedRequest.Image(Path.of("/tmp/photo.PNG")),
            parseShareArgs(listOf("--send-file", "/tmp/photo.PNG"), home),
        )
    }

    @Test
    fun `a flag with no value is nothing`() {
        assertNull(parseShareArgs(listOf("--send-text"), home))
        assertNull(parseShareArgs(listOf("--send-file"), home))
    }

    @Test
    fun `an empty value is nothing`() {
        assertNull(parseShareArgs(listOf("--send-text", ""), home))
        assertNull(parseShareArgs(listOf("--send-file", "   "), home))
    }

    @Test
    fun `a file that is not an image is not an attachment`() {
        // The composer renders and sends its attachment as an image, so a PDF would be a broken
        // thumbnail and a refused turn. Upstream refused a non-image MIME type at this exact point;
        // on a command line the extension is what a MIME type becomes.
        assertNull(parseShareArgs(listOf("--send-file", "/tmp/report.pdf"), home))
        assertNull(parseShareArgs(listOf("--send-file", "/tmp/no-extension"), home))
    }

    @Test
    fun `an unrelated flag is ignored and a share later in the list still counts`() {
        // This is what a `.desktop` entry calls: `celeste-linux --listen --send-text "…"`. An
        // unrecognised argument must not stop the payload being read.
        assertEquals(
            SharedRequest.Text("note"),
            parseShareArgs(listOf("--listen", "--unknown", "--send-text", "note"), home),
        )
        assertNull(parseShareArgs(listOf("--listen", "--headless"), home))
    }

    @Test
    fun `the first payload wins, because two shared items are two turns nobody composed`() {
        assertEquals(
            SharedRequest.Text("first"),
            parseShareArgs(listOf("--send-text", "first", "--send-text", "second"), home),
        )
        assertEquals(
            SharedRequest.Image(Path.of("/tmp/a.png")),
            parseShareArgs(listOf("--send-file", "/tmp/a.png", "--send-text", "second"), home),
        )
    }

    @Test
    fun `a leading tilde is expanded, because nothing else expands it`() {
        // Measured on 2026-10-16: `--send-file ~/Pictures/VoiceAssistant/Gen_….png` reached the
        // application with the tilde intact — neither `gradle --args` nor a `.desktop` entry's
        // `Exec` line is a shell — so the attachment was a file that does not exist and the chat
        // bubble drew a black rectangle with nothing to explain it. The shell habit has to be
        // honoured here, or the path has to be refused; silently attaching nothing is the one
        // option that was wrong.
        assertEquals(
            SharedRequest.Image(Path.of("/home/tester/Pictures/a.png")),
            parseShareArgs(listOf("--send-file", "~/Pictures/a.png"), home),
        )
    }

    @Test
    fun `a bare tilde expands to the home directory, and is then not an image`() {
        // The expansion and the image rule are separate, and this is where they meet: `~` becomes a
        // directory path, and a directory has no image extension, so nothing is attached.
        assertNull(parseShareArgs(listOf("--send-file", "~"), home))
    }

    @Test
    fun `a tilde naming someone else is left alone rather than guessed at`() {
        // `~bob/x.png` is another user's home. Inventing an absolute path for it would be this
        // process making a path up; left as it is, the landing's existence check names it.
        assertEquals(
            SharedRequest.Image(Path.of("~bob/x.png")),
            parseShareArgs(listOf("--send-file", "~bob/x.png"), home),
        )
    }
}
