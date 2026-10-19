package com.nikosm.voiceassistant.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

/**
 * The deadline around a helper process, against a real child — the only way to test the failure
 * the old code could not survive: a child that never closes stdout leaves `readBytes()` blocked
 * forever, so a timeout checked after EOF is never reached.
 *
 * The fixture is [ProcessIoFixtureMain], launched with this JVM's own java and classpath, so the
 * tests depend on no installed tool. The two production callers (local TTS and the notifier) both
 * go through [communicateWithin]; this is the bound they share.
 */
class ProcessCommunicationTest {

    private fun fixture(mode: String): Process = ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-cp",
        System.getProperty("java.class.path"),
        "com.nikosm.voiceassistant.linux.ProcessIoFixtureMainKt",
        mode,
    ).start()

    @Test
    fun `both streams are returned and the exit code is the process's`() {
        val output = fixture("echo").communicateWithin(10_000)

        assertFalse(output.timedOut)
        assertEquals(0, output.exitCode)
        assertEquals("out", String(output.stdout))
        assertEquals("err", String(output.stderr))
    }

    @Test(timeout = 20_000)
    fun `a stderr flood does not deadlock before stdout closes`() {
        // 256 KiB is past the pipe buffer: reading stdout to EOF first would block on this child,
        // which is exactly what the notifier's sequential drain did.
        val output = fixture("flood").communicateWithin(10_000)

        assertFalse("the concurrent drain must let the child finish", output.timedOut)
        assertEquals(0, output.exitCode)
        assertEquals("done", String(output.stdout))
        assertEquals(256 * 1024, output.stderr.size)
    }

    @Test(timeout = 10_000)
    fun `a child that never closes stdout is killed at the deadline`() {
        val process = fixture("hang")
        val started = System.nanoTime()

        val output = process.communicateWithin(500)

        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("the read must return on the deadline, took ${elapsedMs}ms", elapsedMs < 5_000)
        assertTrue(output.timedOut)
        assertNull(output.exitCode)
        assertFalse("the child is killed, not left running", process.isAlive)
        assertTrue(
            "bytes written before the deadline are kept: '${String(output.stdout)}'",
            String(output.stdout).startsWith("partial"),
        )
    }

    @Test(timeout = 10_000)
    fun `a clean exit with a grandchild holding the pipe keeps what was read`() {
        // The direct child exits 0, but a grandchild inherited stdout and keeps it open. The reader
        // never sees EOF, so the grace expires — and the bytes already collected must survive,
        // rather than being replaced by the empty array a timed-out await used to return.
        val output = fixture("orphan").communicateWithin(10_000)

        assertFalse("the direct child exited, so this is not a timeout", output.timedOut)
        assertEquals(0, output.exitCode)
        assertEquals("before-orphan", String(output.stdout))
    }
}
