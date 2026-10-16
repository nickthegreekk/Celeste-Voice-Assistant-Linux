package com.nikosm.voiceassistant.linux

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.atomic.AtomicReference

/**
 * The lock as two processes see it, which is the only way the application ever sees it.
 *
 * `SingleInstanceTest` stays inside one JVM, and inside one JVM `FileChannel.tryLock` takes a
 * different path entirely: it throws `OverlappingFileLockException` because Java's own lock table
 * knows the file is locked. Two copies of the application are two JVMs, so the branch that runs in
 * production is `tryLock` returning null — and the second claim the guard makes, that a crashed
 * instance leaves nothing behind, is about what the *kernel* does when a process disappears without
 * unwinding. Neither is reachable from inside one JVM, so this test starts a real second one.
 *
 * It is slower than its sibling by a JVM start, which is the price of testing the real thing rather
 * than a same-process approximation of it. Nothing here touches the session's runtime directory, a
 * microphone or a network: the child gets a temporary directory and an explicitly empty
 * environment lookup.
 */
class SingleInstanceCrossProcessTest {

    private val log = RecordingLogger()
    private lateinit var temp: Path

    private val noRuntimeDir: (String) -> String? = { null }

    @Before
    fun setUp() {
        temp = Files.createTempDirectory("celeste-cross-process-test")
    }

    @After
    fun tearDown() {
        Files.walk(temp).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `another process holding the lock is refused, and a killed holder releases it`() {
        val child = startHolder()
        try {
            assertEquals("the child should have reported holding the lock", "HELD", awaitLine(child))

            assertNull(
                "a lock held by another process must refuse this one",
                SingleInstance.acquire(temp, log, noRuntimeDir),
            )
            // The message is the user-visible half of the guard: it is what explains a hotkey that
            // appeared to do nothing.
            assertTrue("expected 'already running' in ${log.warnings}", log.warnings.any { it.contains("already running") })
        } finally {
            // SIGKILL, deliberately, and it is the point rather than a shortcut: no shutdown path
            // runs in that process, so anything the guard leaves behind here is left by the kernel.
            child.destroyForcibly()
            child.waitFor()
        }

        val after = SingleInstance.acquire(temp, log, noRuntimeDir)
        assertNotNull("a holder that dies without unwinding must not leave a stale lock", after)
        after!!.close()
    }

    // ---------------------------------------------------------------- helpers

    private fun startHolder(): Process {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        // `java.class.path` of the test JVM, so the child resolves the same compiled classes this
        // one is testing against. On Linux Gradle passes the real classpath there rather than the
        // manifest-only jar it uses on Windows, which is the other reason this runs as a JVM test.
        val classpath = System.getProperty("java.class.path")
        return ProcessBuilder(
            java, "-cp", classpath,
            LockHolder::class.java.name.removeSuffix("\$"),
            temp.toString(),
        ).redirectErrorStream(true).start()
    }

    /** Reads one line from [process] on a thread of its own, because `readLine` has no timeout. */
    private fun awaitLine(process: Process, timeoutMs: Long = 30_000): String? {
        val line = AtomicReference<String?>()
        val reader = Thread({ line.set(process.inputStream.bufferedReader().readLine()) }, "lock-holder-output")
        reader.isDaemon = true
        reader.start()
        reader.join(timeoutMs)
        return line.get()
    }
}
