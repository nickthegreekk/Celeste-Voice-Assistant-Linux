package com.nikosm.voiceassistant

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The slot a run in [slot] must arm next: an involution, and the same value the run itself did not
 * occupy — which is precisely what makes self-cancellation unrepresentable.
 *
 * Upstream defined this, its name scheme and its two-slot reasoning in `SpontaneousDeliveryWorker.kt`,
 * which this port does not take: the file is WorkManager's, and the only part of the scheme a
 * coroutine timer still needs is this one line. It is kept with its reasoning because the *caller's*
 * contract depends on it — see [SpontaneousDelivery]'s re-arm — and because the alternative (a
 * single chain name with replace semantics) is the landmine upstream documented at length: the run
 * doing the re-arming would be cancelling itself, and whether that is survivable would depend on
 * statement order inside the run.
 */
internal fun alternateSpontaneousSlot(slot: Int): Int = (slot and 1) xor 1

/**
 * Seam 10 as a coroutine timer in the core — PLAN.md §10's "spontaneous scheduler in the core", and
 * the implementation that retires `StubScheduler`, which was the last `Stub*` in `:platform`.
 *
 * ## Why a timer here rather than a systemd timer
 *
 * §5 left the choice open and called it a product decision: a timer that runs only while the
 * application is up, versus a unit the user has to install. The acceptance criterion decides it —
 * "a spontaneous message arrives as a desktop notification **while only the tray process runs**" —
 * because that sentence requires the process to be up either way, and once it is, a coroutine is the
 * whole mechanism. A systemd timer would add a second scheduling authority that has to agree with
 * `SpontaneousPolicy`'s windows, gaps and daily caps, and would fire into a process that is not
 * running unless the unit it belongs to keeps it running. The unit is M4's too, and it is what a
 * *login* is missing, not what a delay is.
 *
 * ## The slot scheme is upstream's, and it survives in the caller's contract
 *
 * `arm(persona, slot, delay)` replaces whatever chain that persona had. Upstream needed two names
 * per persona because `REPLACE` would otherwise cancel the run doing the re-arming; here the same
 * alternation is carried by the slot the delivery receives and hands back, and the timer separately
 * remembers which job is *inside* the delivery ([delivering]) so the run's own re-arm cannot cancel
 * its tail. The caller is a byte-identical COPY file and the delivery code ports upstream's
 * reasoning line for line, so its contract — "the run arms the other slot" — is kept exactly.
 * [SpontaneousDelivery.kt] documents what the slot means where it is used.
 *
 * ## Armed means "a run is pending or in flight"
 *
 * The guard in `refreshSpontaneousSchedule` exists so that opening the application does not restart
 * a four-hour countdown; it asks [isArmed] and arms only if nothing is waiting. So the answer has to
 * be true for the whole life of a run — which is why [InProcessScheduler.attach] takes a `suspend`
 * delivery and the job is not finished until that returns.
 */
class SpontaneousTimer(
    private val logger: AppLogger = LogSink.logger,
) : InProcessScheduler {

    /** Guards [jobs], [delivering] and the attached pair: arming arrives from the UI thread, delivery from IO. */
    private val lock = Any()
    private val jobs = mutableMapOf<String, Job>()

    /**
     * The job currently inside [InProcessScheduler]'s delivery, if any.
     *
     * The delivery re-arms the next chain **from inside itself** (`SpontaneousDelivery` ends every
     * path with `armSpontaneousWork`). Without this map, [arm] would cancel the job doing the
     * arming — via the `jobs` entry it is about to replace — and any suspension after the re-arm
     * would then abort the tail of the delivery. [alternateSpontaneousSlot] makes the caller's
     * intent unambiguous; this map is what makes the timer honour it.
     */
    private val delivering = mutableMapOf<String, Job>()
    private var scope: CoroutineScope? = null
    private var deliver: (suspend (String, Int) -> Unit)? = null

    override fun attach(scope: CoroutineScope, deliver: suspend (personaName: String, slot: Int) -> Unit) {
        synchronized(lock) {
            this.scope = scope
            this.deliver = deliver
        }
    }

    override fun arm(personaName: String, slot: Int, delayMillis: Long) {
        if (personaName.isBlank()) return
        val (targetScope, targetDeliver) = synchronized(lock) { scope to deliver }
        if (targetScope == null || targetDeliver == null) {
            // Honest rather than silent: nothing can fire before the service has attached, and a
            // chain that reported itself armed with no timer behind it is the failure mode this
            // project has already paid for once (see Scheduler.kt's note on stubbed declarations).
            logger.w(TAG, "asked to arm '$personaName' before delivery was attached — nothing armed")
            return
        }
        synchronized(lock) {
            // One chain per persona: this *is* upstream's REPLACE, minus the self-cancellation
            // problem the slot scheme existed to dodge. A call that arrives from inside the
            // delivery is not a replacement — it is the run arming its successor — so the job
            // doing the delivering is left alone. Cancelling it here would set its cancellation
            // flag and make the next suspension in the delivery throw.
            val previous = jobs[personaName]
            if (previous != null && previous !== delivering[personaName]) previous.cancel()
            jobs[personaName] = targetScope.launch {
                val self = coroutineContext[Job] ?: return@launch
                try {
                    delay(delayMillis.coerceAtLeast(0L))
                    synchronized(lock) { delivering[personaName] = self }
                    logger.d(TAG, "delivering for '$personaName' (slot ${slot and 1})")
                    targetDeliver(personaName, slot)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    // A disarm, or the service going away. Not a failure, and not ours to swallow.
                    throw cancelled
                } catch (error: Throwable) {
                    // The delivery catches its own transport failures, so anything reaching here is
                    // a bug — and the one thing it must not do is end the chain silently. The
                    // person who configured this persona would simply never hear from it again.
                    logger.e(TAG, "delivery for '$personaName' threw — the chain continues with " +
                        "whatever it armed: ${error.message ?: error::class.simpleName}")
                } finally {
                    // The run is over, whatever it decided: whoever it armed holds the chain now.
                    // The identity checks are what keep a run that armed its successor from
                    // deleting that successor on the way out.
                    synchronized(lock) {
                        if (delivering[personaName] === self) delivering.remove(personaName)
                        if (jobs[personaName] === self) jobs.remove(personaName)
                    }
                }
            }
        }
    }

    override fun disarm(personaName: String) {
        if (personaName.isBlank()) return
        val cancelled = synchronized(lock) { jobs.remove(personaName)?.also { it.cancel() } }
        if (cancelled != null) logger.d(TAG, "disarmed '$personaName'")
    }

    override fun isArmed(personaName: String): Boolean =
        synchronized(lock) { jobs[personaName]?.isActive == true }

    private companion object {
        const val TAG = "SpontaneousTimer"
    }
}
