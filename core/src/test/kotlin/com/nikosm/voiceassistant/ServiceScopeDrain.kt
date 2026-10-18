package com.nikosm.voiceassistant

import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestCoroutineScheduler

/**
 * Waits, while the test Main dispatcher is still installed, for every service scope to finish
 * unwinding (2026-10-18).
 *
 * `AssistantService.shutdown()` cancels `serviceScope` and returns. A child still inside a
 * `Dispatchers.IO` block resumes its cancellation ON `Dispatchers.Main`; if the test resets Main
 * first, that resume throws "Dispatchers.Main was accessed when the platform dispatcher was
 * absent" on a worker thread, and kotlinx-coroutines-test reports it against the NEXT test — the
 * failure names a test that did nothing wrong. `FsToolTurnTest` and `SpontaneousDeliveryTest`
 * both produced it that way.
 *
 * Pumping the scheduler is what lets the queued continuation run to completion. Bounded, because
 * a scope that refuses to finish must not hang the build; in practice this returns in
 * milliseconds.
 */
internal fun drainServiceScopes(
    services: Collection<AssistantService>,
    scheduler: TestCoroutineScheduler,
    timeoutMs: Long = 3_000L,
) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        scheduler.runCurrent()
        if (services.all { it.serviceScope.coroutineContext[Job]?.isCompleted != false }) return
        Thread.sleep(2)
    }
}
