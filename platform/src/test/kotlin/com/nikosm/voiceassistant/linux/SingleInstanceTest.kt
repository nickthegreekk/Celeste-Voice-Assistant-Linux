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

/**
 * The guard that keeps `celeste-linux --listen` from starting a second microphone.
 *
 * Every path here is a temporary directory and every environment lookup is injected, because the
 * alternative is a test that writes a lock into the *session's* real `$XDG_RUNTIME_DIR` — which on
 * a machine where the application is actually running would collide with it. That is the same
 * reason [XdgPaths.resolve] takes its environment as a parameter, applied to the same problem.
 *
 * The same-JVM case is the one this test can reach, and it is reached through a genuinely different
 * branch: `FileChannel.tryLock` throws `OverlappingFileLockException` for a lock this process
 * already holds instead of returning null, so a guard that only handled the null would let a second
 * `main` in one JVM through. The cross-process case cannot be exercised from inside one JVM without
 * spawning another, and it is the same `tryLock` returning null — the branch below it.
 */
class SingleInstanceTest {

    private val log = RecordingLogger()
    private lateinit var temp: Path

    /** No `XDG_RUNTIME_DIR`, so the caller's fallback directory is what gets used. */
    private val noRuntimeDir: (String) -> String? = { null }

    @Before
    fun setUp() {
        temp = Files.createTempDirectory("celeste-instance-test")
    }

    @After
    fun tearDown() {
        Files.walk(temp).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `a second acquisition is refused while the first is held`() {
        val first = SingleInstance.acquire(temp, log, noRuntimeDir)
        assertNotNull("the first acquisition must succeed", first)

        first!!.use {
            assertNull("a second one must be refused", SingleInstance.acquire(temp, log, noRuntimeDir))
            assertTrue(
                "and it must say why: ${log.warnings}",
                log.warnings.any { it.contains("already running") },
            )
        }
    }

    @Test
    fun `the lock is released when the holder closes it`() {
        val first = SingleInstance.acquire(temp, log, noRuntimeDir)!!

        first.close()
        // Idempotent, because the caller's shutdown path may close it after an explicit close.
        first.close()

        val second = SingleInstance.acquire(temp, log, noRuntimeDir)
        assertNotNull("a released lock can be taken again", second)
        second!!.close()
    }

    @Test
    fun `the lock lives in the session's runtime directory when there is one`() {
        val runtime = Files.createTempDirectory("celeste-runtime-test")
        try {
            val held = SingleInstance.acquire(temp, log) { name ->
                if (name == "XDG_RUNTIME_DIR") runtime.toString() else null
            }!!

            held.use {
                assertEquals(runtime.resolve(XdgPaths.APP_DIR).resolve(SingleInstance.LOCK_NAME), held.path)
                assertTrue("the app's subdirectory is created, not just assumed", Files.isDirectory(held.path!!.parent))
                // Nothing is written into the fallback directory when the session has a runtime dir.
                assertTrue(Files.notExists(temp.resolve(SingleInstance.LOCK_NAME)))
            }
        } finally {
            Files.walk(runtime).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test
    fun `a relative XDG_RUNTIME_DIR is ignored in favour of the fallback`() {
        // The specification says a relative value is invalid, and `XdgPaths` already refuses one for
        // the four base directories. A relative runtime dir would put the lock in whatever the
        // process's working directory happens to be, which is not a place two instances agree on.
        val held = SingleInstance.acquire(temp, log) { name ->
            if (name == "XDG_RUNTIME_DIR") "relative/runtime" else null
        }!!

        held.use { assertEquals(temp.resolve(SingleInstance.LOCK_NAME), held.path) }
    }

    @Test
    fun `the file records the pid of the holder`() {
        val held = SingleInstance.acquire(temp, log, noRuntimeDir)!!
        val path = held.path!!

        // Read *after* the close, and that ordering is deliberate rather than incidental: POSIX
        // record locks are released by closing any descriptor for the file in this process, so a
        // `readString` while the lock is held would drop the guard in every other process while
        // this one still believed it held it. The test must not model the mistake the KDoc warns
        // about, and the file outlives the lock by design — it is never deleted.
        held.close()

        // The only thing that reads this is a human looking at why a second launch exited, which is
        // exactly the case that is hard to debug from a keyboard shortcut.
        assertEquals(ProcessHandle.current().pid().toString(), Files.readString(path).trim())
    }

    @Test
    fun `an unusable directory is logged and does not stop the program`() {
        // The guard is best effort by design: failing to create *it* must not become a failure to
        // start. This path cannot be made to fail with a temp directory that is writable, so it
        // asserts the shape of the answer instead — an unguarded handle, which is not null and so
        // is not mistaken by the caller for "another instance".
        val missing = temp.resolve("a-file-not-a-directory")
        Files.writeString(missing, "not a directory")

        val held = SingleInstance.acquire(missing.resolve("underneath"), log, noRuntimeDir)

        assertNotNull("a guard that cannot be created is not 'already running'", held)
        assertNull("and it carries no lock", held!!.path)
        assertTrue(log.warnings.any { it.contains("no instance lock") })
        held.close()
    }
}
