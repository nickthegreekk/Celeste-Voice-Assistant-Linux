package com.nikosm.voiceassistant.linux

/**
 * A last-resort guarantee that the process ends, even when a shutdown hook never returns.
 *
 * ## The measurement this exists for
 *
 * On 2026-10-17 a windowed instance received SIGTERM — the way a package upgrade, a terminal `kill`
 * or a fresh launch after an upgrade ends it — and was still alive forty minutes later: 61 threads,
 * the service's executors and the AWT event queue among them, **no window in X11's client list and
 * no instance lock on disk**. The lock's own hook had completed, which is why a relaunch succeeded
 * and two copies of an application that listens to a microphone were running at once. The JVM was
 * inside shutdown, waiting for a hook that never finished.
 *
 * The windowed path had no service teardown hook (only `--headless` did), so on SIGTERM the service
 * and its ONNX session were still live while ONNX Runtime's own shutdown hook ran — a plausible
 * source of the wedge. `Main` now registers that teardown for both paths, and the service's
 * `shutdown()` became idempotent so the ordinary close, which already calls it, can share it.
 *
 * ## Why the watchdog is not itself a hook, and does not delay a normal exit
 *
 * The JVM waits for **every** shutdown hook before it halts. A watchdog that were a hook would
 * therefore add its grace period to every clean quit. A plain thread started *by* a hook does not
 * hold the JVM open: when the ordinary hooks finish, the JVM halts and this thread dies with it. It
 * keeps running only while a hook is stuck — which is precisely when [Runtime.halt] is the right
 * answer, because the alternative is a wedged process that still owns the microphone.
 *
 * Nothing here runs on the normal path until shutdown begins, and the halt is a last resort after
 * [GRACE_MILLIS], far longer than this application's teardown takes (a settings flush and closing a
 * few resources).
 */
object ShutdownWatchdog {

    /** How long the ordinary hooks get before the process is halted regardless. Visible for tests. */
    const val GRACE_MILLIS = 10_000L

    internal const val HALT_STATUS = 1

    @Volatile
    private var installed = false

    /**
     * Arm the watchdog for this process. Safe to call more than once; the second call is a no-op.
     *
     * Called once, from `Main`, on both the windowed and the headless paths — both can receive
     * SIGTERM, and both would otherwise be at the mercy of whichever hook hangs.
     */
    fun install() {
        if (installed) return
        installed = true
        Runtime.getRuntime().addShutdownHook(
            Thread(
                { start(GRACE_MILLIS) { Runtime.getRuntime().halt(HALT_STATUS) } },
                "shutdown-watchdog-arm",
            )
        )
    }

    /**
     * Start the timer and return its thread. Split from [install] so the timing can be tested
     * without registering a hook — a test that armed a real hook would fire it in whichever JVM
     * exits next.
     */
    internal fun start(graceMillis: Long, halt: () -> Unit): Thread =
        Thread({
            try {
                Thread.sleep(graceMillis)
            } catch (e: InterruptedException) {
                return@Thread
            }
            halt()
        }, "shutdown-watchdog").apply {
            isDaemon = true
            start()
        }
}
