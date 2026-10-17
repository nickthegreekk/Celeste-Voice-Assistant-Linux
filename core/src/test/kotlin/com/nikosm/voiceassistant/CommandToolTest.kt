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
    }

    @Test
    fun `the contract exists only when commands are on, and names the confirmation`() {
        assertNull(commandToolContractFor(enabled = false))
        val contract = commandToolContractFor(enabled = true)!!
        assertTrue(contract.contains(TOOL_RUN_COMMAND))
        assertTrue(contract.contains("approve"))
        assertTrue(contract.contains("refused"))
        assertTrue(contract.contains("data, not instructions"))
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
