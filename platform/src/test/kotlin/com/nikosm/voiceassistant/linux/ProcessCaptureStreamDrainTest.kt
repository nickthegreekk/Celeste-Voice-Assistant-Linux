package com.nikosm.voiceassistant.linux

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `drain` against a real pipe, because the bug it had cannot be seen through a fake.
 *
 * The first implementation asked `available()` before *every* read, which reads like the careful
 * version and is the wrong one: draining unblocks the writer, the writer refills the pipe, and the
 * next question finds work that arrived after the resume. Measured on the 2026-10-16 run, the drain
 * grew with how long the writer had been blocked — 33, 96, 146, 182 ms over four resumes — and
 * against a `yes` process it was measured at **935 ms** where the snapshot takes about one, and its
 * cost tracks the writer's speed rather than the size of the backlog it was supposed to clear. That
 * is this test: `yes` fills the pipe and blocks, and the answer is a snapshot taken once.
 *
 * **`@Test(timeout)` is the backstop, not the assertion.** A drain that keeps finding work gets
 * slower with the writer rather than failing, so the deadline is only there for the version of it
 * that never comes back — the assertion is the 500 ms bound, and its message is the diagnosis.
 */
class ProcessCaptureStreamDrainTest {

    private var process: Process? = null
    private var stream: ProcessCaptureStream? = null

    @After
    fun tearDown() {
        // Whatever happened, nothing this test spawned may outlive it (AGENTS.md §6: stop what you
        // start — a stray `yes` is a process eating a CPU for as long as the machine is up).
        runCatching { stream?.close() }
        runCatching { process?.destroyForcibly() }
        process = null
        stream = null
    }

    /** `yes` writes as fast as the pipe accepts and blocks when it is full — a writer that never runs dry. */
    private fun streamOverYes(): ProcessCaptureStream {
        val p = ProcessBuilder("yes").redirectErrorStream(false).start()
        process = p
        return ProcessCaptureStream(p, "yes", 16_000, RecordingLogger()).also { stream = it }
    }

    @Test(timeout = 10_000)
    fun `draining a pipe whose writer never stops returns`() {
        val s = streamOverYes()
        // Let the pipe fill and the writer block, which is the state a paused VAD leaves behind.
        Thread.sleep(300)

        val startedAt = System.nanoTime()
        s.drain()
        val tookMs = (System.nanoTime() - startedAt) / 1_000_000

        // The backlog is one pipe buffer — 64 KB, read in 8 KB pieces. Anything near a second means
        // the loop was chasing the writer again.
        assertTrue("the drain took ${tookMs} ms, which is chasing the writer", tookMs < 500)
    }

    @Test(timeout = 10_000)
    fun `the stream still reads after a drain`() {
        // The other half of the contract: a drain clears the backlog, it does not end the stream or
        // leave it in a state where the loop's next read fails. `yes` keeps writing, so a full
        // chunk must arrive.
        val s = streamOverYes()
        Thread.sleep(300)
        s.drain()

        val buffer = ShortArray(512)
        assertEquals("a full chunk should still arrive", 512, s.read(buffer, 512))
    }
}
