package com.nikosm.voiceassistant.linux

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watchdog's timing, without a shutdown.
 *
 * [ShutdownWatchdog.install] registers a real JVM shutdown hook, and a test that armed one would
 * fire it in whichever JVM exits next (the Gradle worker), so the test drives [ShutdownWatchdog.start]
 * — the same timer the hook starts — with a fake halt.
 */
class ShutdownWatchdogTest {

    @Test
    fun `the watchdog fires after its grace period`() {
        val fired = CountDownLatch(1)

        val watchdog = ShutdownWatchdog.start(50) { fired.countDown() }

        assertTrue("the watchdog did not fire within two seconds", fired.await(2, TimeUnit.SECONDS))
        watchdog.join(1_000)
    }

    @Test
    fun `the watchdog does not fire before the grace period`() {
        val fired = CountDownLatch(1)

        val watchdog = ShutdownWatchdog.start(10_000) { fired.countDown() }

        assertFalse(
            "the watchdog must never halt a process whose hooks are still working",
            fired.await(150, TimeUnit.MILLISECONDS),
        )
        watchdog.interrupt()
        watchdog.join(1_000)
    }
}
