package com.nikosm.voiceassistant

import kotlinx.coroutines.CoroutineScope

/**
 * Seam 10 (PLAN.md §5): the timing side of spontaneous delivery.
 *
 * ## What upstream does, and what is being replaced
 *
 * Upstream arms a self-rescheduling `OneTimeWorkRequest` per persona — one run, which computes its
 * successor's instant and enqueues it — and that is what makes the feature survive process death
 * and reboot without the application being resident. WorkManager is Android's, and the three things
 * this seam has to provide are exactly the three the rest of the code asks of it: arm a chain for a
 * persona at a given instant, drop every chain for a persona, and answer whether one is pending.
 *
 * §5 leaves the Linux implementation open between a coroutine timer inside the service core and an
 * optional systemd user timer, and that choice is M4's — it is the first milestone that delivers
 * spontaneous messages, and the trade (a timer that only runs while the app is open, versus a unit
 * the user has to install) is a product decision, not a porting one.
 *
 * ## Why it lands in M1 with a stub
 *
 * `AssistantServiceSpontaneous.kt` is a byte-identical COPY file, and it calls
 * [isSpontaneouslyArmed], [armSpontaneousWork] and [disarmSpontaneousWork] three times each —
 * so `:core` cannot compile the m1 set without them. That is the seam rule doing its job rather
 * than an exception to it: **a seam is introduced with its first caller**, and here the first
 * caller is a file this port may not edit. PLAN.md §5's seam-10 row and §10's corrected claim both
 * record it; the worker *file* stays out of M1, the capability does not.
 *
 * The alternative — borrowing the three declarations with stub bodies — was rejected, and the
 * reason generalises past this seam: a borrowed stub is called like real code and returns a
 * plausible wrong value. `isSpontaneouslyArmed` answering `false` forever is not an inert stub; it
 * is precisely the condition that function exists to detect, and answering it wrongly makes
 * `refreshSpontaneousSchedule` re-arm a healthy chain on every service start, restarting the
 * countdown each time. A `Stub*` class announces itself in the log and in the grep; a stubbed
 * *declaration* looks like the real thing.
 *
 * ## The slot is the caller's, and it is passed through
 *
 * [arm] takes a `slot` because the ported call site passes one and the interface should not pretend
 * otherwise. It is upstream's own scheme — alternate between two work names so that a run's re-arm
 * can never be `REPLACE`d by its own enqueue — and whether a given implementation needs it is that
 * implementation's business. [StubScheduler] ignores it; a coroutine timer would too, since it has
 * no unique-work constraint to route around.
 *
 * Implementations must be **total**, and that is upstream's contract rather than a preference:
 * `armSpontaneousWork` and `disarmSpontaneousWork` wrap their WorkManager calls in `runCatching`
 * because neither of their callers can afford a throw. `disarm` is called from
 * `serviceScope.launch` on `Dispatchers.Main` with no exception handler, where a propagated throw
 * takes the process down — a failed toggle or persona delete would crash the application rather
 * than merely losing a timer. A failure is logged and degrades to "the previous chain stands",
 * which the worker's own eligibility guards then resolve on its next run.
 */
interface Scheduler {

    /**
     * Arms a run for [personaName] to fire [delayMillis] from now, in [slot].
     *
     * Must not throw. A blank [personaName] is a no-op rather than an error — upstream returns
     * early on it, because a blank name can only come from a persona that was already deleted.
     */
    fun arm(personaName: String, slot: Int, delayMillis: Long)

    /**
     * Drops every chain for [personaName].
     *
     * Must not throw, and must cancel a run already in flight: the three callers are toggle-off,
     * persona deleted and persona renamed, and in all three the user changed their mind about
     * something the run was started for.
     */
    fun disarm(personaName: String)

    /**
     * Whether a chain is pending for [personaName] — enqueued or running.
     *
     * False on any failure, which is upstream's `getOrDefault(false)`: the caller uses this to
     * decide whether to arm, so the failure mode is one redundant arm rather than a silent
     * permanent loss of the chain.
     */
    fun isArmed(personaName: String): Boolean
}

// ─── The three functions the byte-identical COPY file calls ────────────────
//
// `AssistantServiceSpontaneous.kt` calls these by their upstream names, with `applicationContext`
// as the first argument. They are top-level rather than members of `AssistantService` for exactly
// that reason: an extension on the service would not accept a call of the form
// `isSpontaneouslyArmed(applicationContext, personaName)`. Their signatures are upstream's with
// one substitution — `Context` became [AppEnv], because that is what `applicationContext` is here
// (PLAN.md §3/D5) and what the call site hands over.

/**
 * Is a chain already pending for [personaName]?
 *
 * The guard that keeps `refreshSpontaneousSchedule` idempotent. Upstream's implementation persists
 * the request across process death and reboot on its own, so re-arming on every service start would
 * be actively harmful: each `REPLACE` restarts the countdown, and a user who opens the app hourly
 * would never see a four-hour gap elapse. Asking first turns the safety net into a no-op whenever
 * the timer is healthy.
 *
 * Nothing else may read that property on the caller's behalf: this is the question and the answer,
 * and a caller that assumed instead of asking is the bug this is here to prevent.
 */
internal fun isSpontaneouslyArmed(context: AppEnv, personaName: String): Boolean =
    context.scheduler.isArmed(personaName)

/**
 * Arm the next run for [personaName], in [slot], to fire [delayMillis] from now.
 *
 * A pure function of its arguments — no ordering requirement, no interaction with any other run —
 * and the implementations must keep it that way. That is the entire point of the slot scheme, and
 * it is why this carries no contract note about where a caller may put it.
 *
 * **Total by construction.** WorkManager is infrastructure and its calls can throw for reasons
 * outside the application's control — the singleton not being initialised, a shutdown race, an OEM
 * quirk — and neither caller can afford a propagated throw. A failure is logged, not propagated,
 * and costs only "the previous chain, if any, stands": the worker's own precondition guards are the
 * backstop, so a stale survivor ends itself rather than ever sending anything.
 */
internal fun armSpontaneousWork(context: AppEnv, personaName: String, slot: Int, delayMillis: Long) {
    if (personaName.isBlank()) return
    runCatching { context.scheduler.arm(personaName, slot, delayMillis) }
        .onFailure { context.logger.w("Spontaneous", "Failed to arm spontaneous work for '$personaName'", it) }
}

/**
 * Drop every chain for [personaName].
 *
 * Cancels both slots, because "which slot is live" is deliberately not knowable from the outside —
 * that is the cost of the scheme, paid once here rather than at every call site.
 *
 * **Total by construction**, and the two call sites are what make a propagated throw unacceptable
 * — they fail in opposite ways. `refreshSpontaneousSchedule` calls this (via
 * `removeSpontaneousSchedule`) inside `serviceScope.launch` on `Dispatchers.Main` with no
 * `CoroutineExceptionHandler`, where an uncaught throw takes the whole process down: a failed
 * toggle or persona delete would crash the application, not merely lose a timer. And the worker's
 * own `rearm()` calls [armSpontaneousWork] from inside its run, where an uncaught throw fails that
 * run and silently ends the chain — the one outcome the guard-and-log design exists to avoid, since
 * the persona then never speaks again and nothing anywhere says why.
 */
internal fun disarmSpontaneousWork(context: AppEnv, personaName: String) {
    if (personaName.isBlank()) return
    runCatching { context.scheduler.disarm(personaName) }
        .onFailure { context.logger.w("Spontaneous", "Failed to disarm spontaneous work for '$personaName'", it) }
}

/**
 * A [Scheduler] that runs the delivery **in this process**, and therefore has to be told what to run.
 *
 * Upstream's WorkManager did not need this: it woke a separate process, which bound the service and
 * called the turn itself. A coroutine timer is the process it delivers into, so the loop closes the
 * other way — the service attaches its scope and its delivery, and PLAN.md §10's "spontaneous
 * scheduler in the core" is what that buys: no IPC, no binder, no second executable (D1).
 *
 * The seam itself is unchanged, which is the point of keeping this separate: the three functions the
 * COPY file calls ([arm], [disarm], [isArmed]) never see it.
 */
internal interface InProcessScheduler : Scheduler {
    /**
     * Hand over the scope the timer lives in and the run to start when it fires.
     *
     * [deliver] is `suspend` so the timer's job stays alive for the length of a run: upstream's
     * guard counts a *running* chain as armed, and a timer that reported "not armed" the moment it
     * fired would let a settings refresh arm a second chain beside the one already delivering.
     */
    fun attach(scope: CoroutineScope, deliver: suspend (personaName: String, slot: Int) -> Unit)
}

