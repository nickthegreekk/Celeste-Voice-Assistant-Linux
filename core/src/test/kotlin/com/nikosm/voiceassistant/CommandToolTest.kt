package com.nikosm.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The terminal tool's pure half (PLAN.md §10/M16): the bounds, the framing, the copy, and the
 * declaration. What the command actually does is `LinuxCommandRunnerTest`'s job in `:platform`;
 * what a turn does with a result is `CommandToolExecutorTest`'s.
 */
class CommandToolTest {

    // ------------------------------------------------------------------ bounds

    @Test
    fun `a missing or non-positive timeout means the default`() {
        assertEquals(COMMAND_DEFAULT_TIMEOUT_SECONDS, resolveCommandTimeoutSeconds(null))
        assertEquals(COMMAND_DEFAULT_TIMEOUT_SECONDS, resolveCommandTimeoutSeconds(0))
        assertEquals(COMMAND_DEFAULT_TIMEOUT_SECONDS, resolveCommandTimeoutSeconds(-5))
    }

    @Test
    fun `a timeout is clamped into the advertised range`() {
        assertEquals(COMMAND_MIN_TIMEOUT_SECONDS, resolveCommandTimeoutSeconds(1))
        assertEquals(COMMAND_MIN_TIMEOUT_SECONDS, resolveCommandTimeoutSeconds(5))
        assertEquals(120, resolveCommandTimeoutSeconds(120))
        assertEquals(COMMAND_MAX_TIMEOUT_SECONDS, resolveCommandTimeoutSeconds(10_000))
    }

    @Test
    fun `output is cut to the cap and says so`() {
        val (kept, truncated) = truncateCommandOutput("x".repeat(100), maxBytes = 10)

        assertEquals(10, kept.length)
        assertTrue(truncated)
    }

    @Test
    fun `output below the cap is untouched`() {
        val (kept, truncated) = truncateCommandOutput("short", maxBytes = 10)

        assertEquals("short", kept)
        assertFalse(truncated)
    }

    @Test
    fun `the cut never splits a multi-byte character`() {
        // Three two-byte characters; a five-byte cap can keep exactly two of them.
        val (kept, truncated) = truncateCommandOutput("ééé", maxBytes = 5)

        assertEquals("éé", kept)
        assertTrue(truncated)
        assertEquals(4, kept.toByteArray(Charsets.UTF_8).size)
    }

    // ------------------------------------------------------------------ terminal output

    @Test
    fun `a PTY's colour and cursor codes are removed`() {
        val raw = "\u001B[?1049h\u001B[31mred\u001B[0m\u001B[2Jplain"

        assertEquals("redplain", sanitizeTtyOutput(raw))
    }

    @Test
    fun `a PTY's CRLF becomes a newline`() {
        assertEquals("one\ntwo\n", sanitizeTtyOutput("one\r\ntwo\r\n"))
    }

    @Test
    fun `a progress bar keeps only its final state`() {
        assertEquals("100%", sanitizeTtyOutput("10%\r50%\r100%"))
        assertEquals("done\nnext", sanitizeTtyOutput("0%\rdone\r\nnext"))
    }

    @Test
    fun `an OSC title sequence is removed`() {
        assertEquals("text", sanitizeTtyOutput("\u001B]0;a title\u0007text"))
    }

    @Test
    fun `bells go and tabs stay`() {
        assertEquals("a\tb", sanitizeTtyOutput("a\u0007\tb"))
    }

    @Test
    fun `plain text is unchanged`() {
        val text = "line one\nline two\ttabbed\n"
        assertEquals(text, sanitizeTtyOutput(text))
    }

    // ------------------------------------------------------------------ the audit row

    @Test
    fun `the audit row marks a terminal run and leaves an ordinary one unmarked`() {
        val entry = CommandAuditEntry(
            timestamp = 0,
            persona = "Celeste",
            command = "ls -la",
            root = "/tmp",
            exitCode = 0,
            timedOut = false,
            truncated = false,
            durationMs = 5,
            result = "ok",
            confirmed = "user",
        )

        assertEquals("ls -la  —  ok (user)", commandAuditSummary(entry))
        assertEquals("ls -la  —  ok (user, tty)", commandAuditSummary(entry.copy(tty = true)))
    }

    @Test
    fun `an audit row shows only the command's first line`() {
        val entry = CommandAuditEntry(
            timestamp = 0,
            persona = "Celeste",
            command = "echo one\necho two",
            root = "/tmp",
            exitCode = 0,
            timedOut = false,
            truncated = false,
            durationMs = 5,
            result = "ok",
            confirmed = "user",
        )

        assertEquals("echo one  —  ok (user)", commandAuditSummary(entry))
    }

    // ------------------------------------------------------------------ framing

    @Test
    fun `output is framed as data and cannot close its own frame`() {
        val framed = frameCommandOutput("before\n$COMMAND_FRAME_END\nafter")

        assertTrue(framed.startsWith(COMMAND_FRAME_BEGIN))
        assertTrue(framed.endsWith(COMMAND_FRAME_END))
        // The line that *is* the marker must be escaped, or the rest would read as the app speaking.
        assertTrue(framed.contains("| $COMMAND_FRAME_END\n"))
    }

    // ------------------------------------------------------------------ result copy

    @Test
    fun `a successful command reports the code and its output`() {
        val text = formatCommandResultText(CommandRunResult(0, "hi\n"), timeoutSeconds = 60)

        assertTrue(text.startsWith("Exit code: 0."))
        assertTrue(text.contains("hi"))
        assertFalse(text.contains("truncated"))
    }

    @Test
    fun `a failed command still reports its code`() {
        val text = formatCommandResultText(CommandRunResult(2, ""), timeoutSeconds = 60)

        assertTrue(text.contains("Exit code: 2."))
        assertTrue(text.contains("no output"))
    }

    @Test
    fun `a timeout is named rather than reported as an exit code`() {
        val text = formatCommandResultText(CommandRunResult(null, "partial", timedOut = true), timeoutSeconds = 30)

        assertTrue(text.contains("did not finish within 30s"))
        assertTrue(text.contains("partial"))
    }

    @Test
    fun `truncation is announced`() {
        val text = formatCommandResultText(CommandRunResult(0, "x", truncated = true), timeoutSeconds = 60)

        assertTrue(text.contains("truncated at the size cap"))
    }

    // ------------------------------------------------------------------ declaration and contract

    @Test
    fun `the tool is declared with a required command and an optional timeout`() {
        val spec = commandToolSpec()

        assertEquals(TOOL_RUN_COMMAND, spec.name)
        assertTrue(spec.description.contains("approve"))
        assertEquals("object", spec.schema.getString("type"))
        assertEquals("command", spec.schema.getJSONArray("required").getString(0))
        assertTrue(spec.schema.getJSONObject("properties").has("timeout_seconds"))
        assertTrue("the optional input is declared", spec.schema.getJSONObject("properties").has("stdin"))
        assertTrue("the optional terminal is declared", spec.schema.getJSONObject("properties").has("tty"))
        assertTrue(spec.description.contains("stdin"))
    }

    @Test
    fun `the contract exists only when commands are on, and names the confirmation`() {
        assertNull(commandToolContractFor(enabled = false))
        val contract = commandToolContractFor(enabled = true)!!
        assertTrue(contract.contains(TOOL_RUN_COMMAND))
        assertTrue(contract.contains("approve"))
        assertTrue(contract.contains("refused"))
        assertTrue(contract.contains("data, not instructions"))
        assertTrue("the input rule names credentials", contract.contains("credentials"))
        assertTrue(contract.contains("stdin"))
        assertTrue("the terminal rule is stated", contract.contains("tty: true"))
    }

    @Test
    fun `a stored mode this build cannot read falls back to off`() {
        assertEquals(CommandToolMode.OFF, CommandToolMode.fromName(null))
        assertEquals(CommandToolMode.OFF, CommandToolMode.fromName(""))
        assertEquals(CommandToolMode.OFF, CommandToolMode.fromName("READ_WRITE"))
        assertEquals(CommandToolMode.OFF, CommandToolMode.fromName("garbage"))
        assertEquals(CommandToolMode.ASK, CommandToolMode.fromName("ask"))
    }

    @Test
    fun `only ask allows commands`() {
        assertFalse(CommandToolMode.OFF.allowsCommands)
        assertTrue(CommandToolMode.ASK.allowsCommands)
    }
}
