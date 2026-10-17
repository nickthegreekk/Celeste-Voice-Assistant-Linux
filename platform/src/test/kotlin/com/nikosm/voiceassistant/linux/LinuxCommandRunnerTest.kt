package com.nikosm.voiceassistant.linux

import com.nikosm.voiceassistant.CommandRunRequest
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The real command runner (PLAN.md §10/M16), against `/bin/sh` and a temporary directory.
 *
 * Everything here is a local process the test wrote itself — no network, no user files, no
 * services. The four promises the tool layer relies on are the four tested: output and exit code
 * come back, stderr is merged, stdin is closed rather than waited on, and the process is killed
 * when its timeout expires.
 */
class LinuxCommandRunnerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val runner = LinuxCommandRunner(RecordingLogger())

    private fun directory(): Path = temp.newFolder().toPath()

    @Test
    fun `a command's output and exit code come back`() = runTest {
        val result = runner.run(CommandRunRequest("printf 'hello'", directory(), timeoutSeconds = 10))

        assertEquals(0, result.exitCode)
        assertEquals("hello", result.output)
        assertFalse(result.timedOut)
        assertFalse(result.truncated)
    }

    @Test
    fun `a non-zero exit is reported, not thrown`() = runTest {
        val result = runner.run(CommandRunRequest("exit 3", directory(), timeoutSeconds = 10))

        assertEquals(3, result.exitCode)
        assertFalse(result.timedOut)
    }

    @Test
    fun `stderr is merged into the same output`() = runTest {
        val result = runner.run(
            CommandRunRequest("printf out; printf err >&2", directory(), timeoutSeconds = 10)
        )

        assertTrue(result.output.contains("out"))
        assertTrue(result.output.contains("err"))
    }

    @Test
    fun `the command runs in the working directory it was given`() = runTest {
        val dir = directory()

        val result = runner.run(CommandRunRequest("pwd", dir, timeoutSeconds = 10))

        assertEquals(
            dir.toRealPath(),
            Path.of(result.output.trim()).toRealPath(),
        )
    }

    @Test
    fun `stdin is closed, so a command that reads does not hang`() = runTest {
        // `cat` with a terminal would wait forever. With stdin closed it sees EOF and exits.
        val result = runner.run(CommandRunRequest("cat", directory(), timeoutSeconds = 10))

        assertEquals(0, result.exitCode)
        assertEquals("", result.output)
        assertFalse(result.timedOut)
    }

    @Test
    fun `the optional input is fed to the command and then closed`() = runTest {
        // `read` is the shape this exists for: one line of input, then the pipe closes, so the
        // command cannot sit waiting for a second answer that will never come.
        val result = runner.run(
            CommandRunRequest(
                "read name; echo \"hello \$name\"",
                directory(),
                timeoutSeconds = 10,
                stdin = "Nikos\n",
            )
        )

        assertEquals(0, result.exitCode)
        assertEquals("hello Nikos\n", result.output)
        assertFalse(result.timedOut)
    }

    @Test
    fun `input is passed through byte-for-byte`() = runTest {
        val result = runner.run(
            CommandRunRequest("cat", directory(), timeoutSeconds = 10, stdin = "line one\nline two\n")
        )

        assertEquals("line one\nline two\n", result.output)
    }

    @Test
    fun `a command asked for a terminal has one, and one without does not`() = runTest {
        val withTty = runner.run(
            CommandRunRequest("test -t 1 && echo tty || echo notty", directory(), 10, tty = true)
        )
        val without = runner.run(
            CommandRunRequest("test -t 1 && echo tty || echo notty", directory(), 10, tty = false)
        )

        assertTrue("script -c must give the child a PTY: ${withTty.output}", withTty.output.contains("tty"))
        assertFalse(withTty.output.contains("notty"))
        assertTrue("no PTY without the flag: ${without.output}", without.output.contains("notty"))
    }

    @Test
    fun `a terminal run still returns the child's exit code`() = runTest {
        val result = runner.run(CommandRunRequest("exit 3", directory(), 10, tty = true))

        assertEquals(3, result.exitCode)
        assertFalse(result.timedOut)
    }

    @Test
    fun `input is fed through the terminal`() = runTest {
        val result = runner.run(
            CommandRunRequest(
                "read name; echo \"hello \$name\"",
                directory(),
                timeoutSeconds = 10,
                stdin = "Nikos\n",
                tty = true,
            )
        )

        assertTrue("the child must have read its input: ${result.output}", result.output.contains("hello Nikos"))
    }

    @Test
    fun `a command past its timeout is killed and says so`() = runTest {
        val started = System.currentTimeMillis()

        val result = runner.run(CommandRunRequest("sleep 30", directory(), timeoutSeconds = 1))

        val elapsed = System.currentTimeMillis() - started
        assertTrue("timed out", result.timedOut)
        assertTrue("the kill must return promptly, not after the sleep: ${elapsed}ms", elapsed < 10_000)
    }

    @Test
    fun `output past the cap is discarded but the command still finishes`() = runTest {
        val result = runner.run(
            CommandRunRequest("seq 1 100000", directory(), timeoutSeconds = 10, maxOutputBytes = 1024)
        )

        assertTrue("the cap was reached", result.truncated)
        assertTrue("at most the cap is kept: ${result.output.length}", result.output.length <= 1024)
        assertEquals("the pipe is still drained, so the command exits normally", 0, result.exitCode)
    }
}
