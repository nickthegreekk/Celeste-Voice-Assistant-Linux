package android.util

import java.util.logging.Level
import java.util.logging.Logger

/**
 * The `android.util.Log` shim — **the only file in this project allowed to live under an
 * `android.*` package** (PLAN.md §7a, AGENTS.md §3).
 *
 * It exists because three COPY files call `android.util.Log` **fully qualified**, with no
 * import line, so they passed a check for `import android.*` and were recorded as pure:
 * `SpontaneousScheduler.kt:410` (the file PLAN.md §7 calls "Pure — this is why scheduling is
 * nearly free"), and six call sites each in `AssistantServiceRoomTurns.kt` and
 * `AssistantServiceSpontaneous.kt`. A file that touches `android.*` cannot compile on the
 * JVM, so "pure, copy verbatim" was wrong for those three.
 *
 * The choice was this shim or demoting three files to ADAPT. Demotion would put a permanent
 * diff on files we would otherwise take verbatim forever — for the sake of ten logging calls
 * whose behaviour nobody can observe. Logging is invisible; byte-identity is not.
 *
 * **So: never edit a fully-qualified `android.*` call inside a COPY file.** Extend this file,
 * or stop and ask (AGENTS.md §3). Nothing else is ever added here — this is not an Android
 * compatibility layer and must not grow into one. It is one logging facade over seven levels.
 *
 * The sink is `java.util.logging`, which ships with the JDK, so this costs no dependency.
 * M1 rewires it to the project logger that arrives with `AppEnv` (PLAN.md §3, D5) — a change
 * to *this file alone*, which is precisely why the shim is a NEW file rather than scattered
 * edits through the COPY set.
 */
object Log {

    // Android's tag becomes the JUL logger name, so it survives into the output the way
    // Android's logcat tag does, without any formatting work here.
    private fun logger(tag: String): Logger = Logger.getLogger(tag)

    // Level mapping, Android -> JUL. VERBOSE and DEBUG deliberately land below JUL's default
    // INFO threshold: that is the behaviour a release build of the phone has, and the
    // behaviour a test run wants. ASSERT maps to SEVERE, as Android ranks it above ERROR.
    fun v(tag: String, msg: String): Int = log(Level.FINEST, tag, msg, null)
    fun v(tag: String, msg: String, tr: Throwable): Int = log(Level.FINEST, tag, msg, tr)

    fun d(tag: String, msg: String): Int = log(Level.FINE, tag, msg, null)
    fun d(tag: String, msg: String, tr: Throwable): Int = log(Level.FINE, tag, msg, tr)

    fun i(tag: String, msg: String): Int = log(Level.INFO, tag, msg, null)
    fun i(tag: String, msg: String, tr: Throwable): Int = log(Level.INFO, tag, msg, tr)

    fun w(tag: String, msg: String): Int = log(Level.WARNING, tag, msg, null)
    fun w(tag: String, msg: String, tr: Throwable): Int = log(Level.WARNING, tag, msg, tr)

    fun e(tag: String, msg: String): Int = log(Level.SEVERE, tag, msg, null)
    fun e(tag: String, msg: String, tr: Throwable): Int = log(Level.SEVERE, tag, msg, tr)

    fun wtf(tag: String, msg: String): Int = log(Level.SEVERE, tag, msg, null)
    fun wtf(tag: String, msg: String, tr: Throwable): Int = log(Level.SEVERE, tag, msg, tr)

    /**
     * Android's `Log` returns the number of bytes written, and callers are free to ignore it,
     * which every call site in this project does. The return type is kept so that a future
     * verbatim file which *does* use the result still compiles; nothing is counted.
     */
    private fun log(level: Level, tag: String, msg: String, tr: Throwable?): Int {
        val log = logger(tag)
        if (tr == null) log.log(level, msg) else log.log(level, msg, tr)
        return 0
    }
}
