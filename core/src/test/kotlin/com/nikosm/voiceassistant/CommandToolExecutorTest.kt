package com.nikosm.voiceassistant

import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a turn does with a command (PLAN.md §10/M16): ask, run, frame, audit — and refuse.
 *
 * The runner is a fake and the approval an injected answer, so this covers the executor's own
 * decisions without spawning anything or waiting for a person. The real process is
 * `LinuxCommandRunnerTest`'s; the real dialog is judged in a live run.
 */
class CommandToolExecutorTest {

    private val root = Path.of("/tmp/celia-workspace")

    private class FakeRunner(
        private val produce: (CommandRunRequest) -> CommandRunResult,
    ) : CommandRunner {
        val requests = mutableListOf<CommandRunRequest>()

        override suspend fun run(request: CommandRunRequest): CommandRunResult {
            requests += request
            return produce(request)
        }
    }

    private fun executor(
        runner: CommandRunner,
        decision: CommandDecision = CommandDecision.ONCE,
        audits: MutableList<CommandAuditEntry>,
        onApprove: (String, String?, Boolean, Path) -> Unit = { _, _, _, _ -> },
    ) = CommandToolExecutor(
        runner = runner,
        workingDirectory = root,
        personaName = "Celeste",
        approve = { command, stdin, tty, dir ->
            onApprove(command, stdin, tty, dir)
            decision
        },
        audit = { audits += it },
    )

    private fun call(
        command: String = "echo hi",
        stdin: String? = null,
        timeoutSeconds: Int? = null,
        tty: Boolean = false,
        id: String = "call-1",
    ): FsToolCall {
        val args = JSONObject().put("command", command)
        if (stdin != null) args.put("stdin", stdin)
        if (timeoutSeconds != null) args.put("timeout_seconds", timeoutSeconds)
        if (tty) args.put("tty", true)
        return FsToolCall(id, TOOL_RUN_COMMAND, args.toString())
    }

    @Test
    fun `a successful command is framed for the model and audited`() = runTest {
        val runner = FakeRunner { CommandRunResult(0, "hi\n") }
        val audits = mutableListOf<CommandAuditEntry>()

        val result = executor(runner, audits = audits).execute(call())

        assertTrue(result.ok)
        assertTrue(result.text.contains("Exit code: 0."))
        assertTrue(result.text.contains(COMMAND_FRAME_BEGIN))
        assertTrue(result.text.contains("hi"))
        assertEquals(1, runner.requests.size)
        assertEquals("echo hi", runner.requests.single().command)
        assertEquals("ok", audits.single().result)
        assertEquals("user", audits.single().confirmed)
        assertEquals("echo hi", audits.single().command)
    }

    @Test
    fun `a non-zero exit is a failed tool result but still an executed command`() = runTest {
        val runner = FakeRunner { CommandRunResult(2, "") }
        val audits = mutableListOf<CommandAuditEntry>()

        val result = executor(runner, audits = audits).execute(call())

        assertFalse(result.ok)
        assertTrue(result.text.contains("Exit code: 2."))
        assertEquals("error", audits.single().result)
        assertEquals(2, audits.single().exitCode)
    }

    @Test
    fun `a refusal runs nothing and says so`() = runTest {
        val runner = FakeRunner { CommandRunResult(0, "should not happen") }
        val audits = mutableListOf<CommandAuditEntry>()

        val result = executor(runner, decision = CommandDecision.DENIED, audits = audits).execute(call())

        assertFalse(result.ok)
        assertTrue(result.text.contains("refused"))
        assertTrue("a denied command never reaches the runner", runner.requests.isEmpty())
        assertEquals("denied", audits.single().result)
        assertEquals("denied", audits.single().confirmed)
    }

    @Test
    fun `a timeout is a failed tool result that says it was stopped`() = runTest {
        val runner = FakeRunner { CommandRunResult(null, "partial", timedOut = true) }
        val audits = mutableListOf<CommandAuditEntry>()

        val result = executor(runner, audits = audits).execute(call(timeoutSeconds = 10))

        assertFalse(result.ok)
        assertTrue(result.text.contains("did not finish within 10s"))
        assertEquals("timeout", audits.single().result)
        assertTrue(audits.single().timedOut)
        assertEquals(10, runner.requests.single().timeoutSeconds)
    }

    @Test
    fun `a missing timeout is sent as the default`() = runTest {
        val runner = FakeRunner { CommandRunResult(0, "") }

        executor(runner, audits = mutableListOf()).execute(call())

        assertEquals(COMMAND_DEFAULT_TIMEOUT_SECONDS, runner.requests.single().timeoutSeconds)
    }

    @Test
    fun `a session grant is recorded as such`() = runTest {
        val runner = FakeRunner { CommandRunResult(0, "") }
        val audits = mutableListOf<CommandAuditEntry>()

        executor(runner, decision = CommandDecision.SESSION, audits = audits).execute(call())

        assertEquals("session-trust", audits.single().confirmed)
    }

    @Test
    fun `long output is capped before it reaches the model`() = runTest {
        val runner = FakeRunner { CommandRunResult(0, "x".repeat(COMMAND_OUTPUT_CAP_BYTES * 2)) }
        val audits = mutableListOf<CommandAuditEntry>()

        val result = executor(runner, audits = audits).execute(call())

        assertTrue(result.text.contains("truncated at the size cap"))
        // The framed text is the cap plus the frame and the sentences around it, never the 128 KiB
        // the fake produced.
        assertTrue(result.text.length < COMMAND_OUTPUT_CAP_BYTES + 500)
        assertTrue(audits.single().truncated)
    }

    @Test
    fun `one turn may not keep running commands past its byte budget`() = runTest {
        val runner = FakeRunner { CommandRunResult(0, "x".repeat(COMMAND_OUTPUT_CAP_BYTES / 2)) }
        val audits = mutableListOf<CommandAuditEntry>()
        val tool = executor(runner, audits = audits)

        tool.execute(call(id = "1"))
        tool.execute(call(id = "2"))
        val third = tool.execute(call(id = "3"))

        assertFalse(third.ok)
        assertTrue(third.text.contains("narrower"))
        assertEquals("the third call must not reach the runner", 2, runner.requests.size)
    }

    @Test
    fun `a blank stdin is no input at all`() = runTest {
        val runner = FakeRunner { CommandRunResult(0, "") }
        val seen = mutableListOf<String?>()

        executor(runner, audits = mutableListOf(), onApprove = { _, stdin, _, _ -> seen += stdin })
            .execute(call(stdin = ""))

        assertEquals(listOf<String?>(null), seen)
        assertEquals(null, runner.requests.single().stdin)
    }

    @Test
    fun `stdin is shown to the approver and reaches the runner`() = runTest {
        val runner = FakeRunner { CommandRunResult(0, "ok") }
        val audits = mutableListOf<CommandAuditEntry>()
        val seen = mutableListOf<Pair<String, String?>>()

        executor(
            runner,
            audits = audits,
            onApprove = { command, stdin, _, _ -> seen += command to stdin },
        ).execute(call(command = "read name; echo \"hello \$name\"", stdin = "Nikos\n"))

        assertEquals(listOf("read name; echo \"hello \$name\"" to "Nikos\n"), seen)
        assertEquals("Nikos\n", runner.requests.single().stdin)
        assertEquals(6, audits.single().stdinChars)
    }

    @Test
    fun `input too long to show for approval is refused before asking`() = runTest {
        val runner = FakeRunner { CommandRunResult(0, "") }
        var asked = false

        val result = executor(
            runner,
            audits = mutableListOf(),
            onApprove = { _, _, _, _ -> asked = true },
        ).execute(call(stdin = "x".repeat(COMMAND_MAX_STDIN_CHARS + 1)))

        assertFalse(result.ok)
        assertTrue(result.text.contains("limit"))
        assertFalse("nothing the user cannot read should be approved silently", asked)
        assertTrue(runner.requests.isEmpty())
    }

    @Test
    fun `tty is shown to the approver and reaches the runner`() = runTest {
        val runner = FakeRunner { CommandRunResult(0, "tty") }
        val audits = mutableListOf<CommandAuditEntry>()
        val seen = mutableListOf<Pair<String, Boolean>>()

        executor(
            runner,
            audits = audits,
            onApprove = { command, _, tty, _ -> seen += command to tty },
        ).execute(call(command = "test -t 1 && echo tty", tty = true))

        assertEquals(listOf("test -t 1 && echo tty" to true), seen)
        assertTrue(runner.requests.single().tty)
        assertTrue(audits.single().tty)
    }

    @Test
    fun `tty output is stripped of terminal control codes before the model sees it`() = runTest {
        val runner = FakeRunner { CommandRunResult(0, "\u001B[31mred\u001B[0m\r\n") }

        val result = executor(runner, audits = mutableListOf()).execute(call(tty = true))

        assertTrue(result.text.contains("red"))
        assertFalse(result.text.contains("\u001B["))
        assertFalse("the PTY's CRLF is a newline, not a stray CR", result.text.contains("\r"))
    }

    @Test
    fun `non-tty output is left exactly as the command printed it`() = runTest {
        // The cleaning is for a terminal's stream, not a general filter: a program writing an
        // escape sequence to a pipe meant it.
        val runner = FakeRunner { CommandRunResult(0, "raw \u001B[31mred\u001B[0m") }

        val result = executor(runner, audits = mutableListOf()).execute(call(tty = false))

        assertTrue(result.text.contains("\u001B[31m"))
    }

    @Test
    fun `an empty command never reaches the runner`() = runTest {
        val runner = FakeRunner { CommandRunResult(0, "") }
        val audits = mutableListOf<CommandAuditEntry>()

        val result = executor(runner, audits = audits).execute(call(command = "   "))

        assertFalse(result.ok)
        assertTrue(runner.requests.isEmpty())
        assertTrue(audits.isEmpty())
    }

    @Test
    fun `a runner failure becomes a sentence, not a thrown turn`() = runTest {
        val runner = FakeRunner { throw java.io.IOException("boom") }
        val audits = mutableListOf<CommandAuditEntry>()

        val result = executor(runner, audits = audits).execute(call())

        assertFalse(result.ok)
        assertTrue(result.text.contains("could not be run"))
        assertEquals("error", audits.single().result)
    }
}
