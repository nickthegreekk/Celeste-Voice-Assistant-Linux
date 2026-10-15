package com.nikosm.voiceassistant.linux

import com.nikosm.voiceassistant.AppLogger
import com.nikosm.voiceassistant.Scheduler
import com.nikosm.voiceassistant.StderrLogger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Seam 10, unimplemented — **M4 replaces this with a real timer** (PLAN.md §5, §10), either a
 * coroutine in the service core driven by the already-pure `SpontaneousScheduler`, or an optional
 * systemd user timer. That choice is M4's to make, because M4 is the first milestone that delivers
 * spontaneous messages and the trade — a timer that only runs while the application is open, versus
 * a unit the user has to install — is a product decision rather than a porting one.
 *
 * ## Every method is a no-op, and [isArmed] answering `false` is the honest answer
 *
 * `false` here means "nothing is armed", and on this implementation that is *true* rather than a
 * stand-in: if [arm] does nothing then nothing can be armed, so the guard and the state agree.
 * That is the property that makes this safe to land in M1, and it is what a stubbed *declaration*
 * could not have — a `BORROWED FROM M4` function with a hard-coded `false` body would look like
 * real code to every reader, where a `Stub*` class announces itself on first use and appears in
 * `grep -rn 'Stub' platform/src/main`.
 *
 * What the guard does with that answer is worth stating, since it is the visible consequence:
 * `refreshSpontaneousSchedule` asks [isArmed] before arming, so answering `false` makes it try to
 * arm every time. Arm does nothing, so the chain is still absent and the countdown never starts —
 * which is the correct outcome for a milestone that does not deliver spontaneous messages at all.
 */
class StubScheduler(
    private val logger: AppLogger = StderrLogger,
) : Scheduler {

    private val announced = AtomicBoolean(false)

    override fun arm(personaName: String, slot: Int, delayMillis: Long) {
        announce()
    }

    override fun disarm(personaName: String) {
        announce()
    }

    override fun isArmed(personaName: String): Boolean {
        announce()
        return false
    }

    private fun announce() {
        if (announced.compareAndSet(false, true)) {
            logger.i(
                TAG,
                "spontaneous scheduling is not implemented yet — this is StubScheduler, which M4 " +
                    "replaces with a timer over SpontaneousScheduler (PLAN.md §10). No spontaneous " +
                    "message will be delivered until then; nothing is armed, and nothing can be."
            )
        }
    }

    private companion object {
        const val TAG = "StubScheduler"
    }
}
