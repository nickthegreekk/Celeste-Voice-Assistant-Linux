package com.nikosm.voiceassistant.linux

import com.nikosm.voiceassistant.StderrLogger
import java.nio.file.Path

/**
 * A second JVM that takes an instance lock on a directory and holds it until it is killed.
 *
 * This exists because the branch that matters is the *cross-process* one: `tryLock` returns null
 * for another process, and throws `OverlappingFileLockException` for the same process, and the
 * application only ever reaches the first. A test that stayed inside one JVM would cover the branch
 * production never takes and miss the one it does — so `SingleInstanceCrossProcessTest` starts
 * this, and this reports what it did on stdout in one word.
 */
object LockHolder {

    @JvmStatic
    fun main(args: Array<String>) {
        // `{ null }` for the environment for the same reason the test passes one: an explicitly
        // absent `XDG_RUNTIME_DIR` is what keeps the lock in the directory handed to it, rather than
        // in the running session's real runtime directory.
        val lock = SingleInstance.acquire(Path.of(args[0]), StderrLogger) { null }
        println(if (lock == null) "REFUSED" else "HELD")
        System.out.flush()
        if (lock == null) return
        try {
            Thread.sleep(60_000)
            lock.close()
        } catch (e: InterruptedException) {
            lock.close()
        }
    }
}
