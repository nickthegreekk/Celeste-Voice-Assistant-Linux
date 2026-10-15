package android.util

import com.nikosm.voiceassistant.LogSink

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
 * The body is now a pure translation layer: each call forwards to [LogSink], which holds the
 * application's real logger. That is D5's "`Log` becomes the project logger" satisfied without
 * editing a single upstream line — the COPY file keeps calling `android.util.Log`, and the output
 * lands wherever the application decided it should. The default sink is stderr, so this file is
 * also correct before anything installs a logger (in `:core`'s own tests, for instance).
 */
object Log {

    // Level mapping, Android -> the port's LogLevel. VERBOSE and DEBUG deliberately land below the
    // default sink's threshold: that is the behaviour a release build of the phone has, and the
    // behaviour a test run wants. ASSERT maps to ERROR's level, as Android ranks it above ERROR.
    fun v(tag: String, msg: String): Int = emit { it.v(tag, msg) }
    fun v(tag: String, msg: String, tr: Throwable): Int = emit { it.v(tag, msg, tr) }

    fun d(tag: String, msg: String): Int = emit { it.d(tag, msg) }
    fun d(tag: String, msg: String, tr: Throwable): Int = emit { it.d(tag, msg, tr) }

    fun i(tag: String, msg: String): Int = emit { it.i(tag, msg) }
    fun i(tag: String, msg: String, tr: Throwable): Int = emit { it.i(tag, msg, tr) }

    fun w(tag: String, msg: String): Int = emit { it.w(tag, msg) }
    fun w(tag: String, msg: String, tr: Throwable): Int = emit { it.w(tag, msg, tr) }

    fun e(tag: String, msg: String): Int = emit { it.e(tag, msg) }
    fun e(tag: String, msg: String, tr: Throwable): Int = emit { it.e(tag, msg, tr) }

    fun wtf(tag: String, msg: String): Int = emit { it.wtf(tag, msg) }
    fun wtf(tag: String, msg: String, tr: Throwable): Int = emit { it.wtf(tag, msg, tr) }

    /**
     * Android's `Log` returns the number of bytes written, and callers are free to ignore it,
     * which every call site in this project does. The return type is kept so that a future
     * verbatim file which *does* use the result still compiles; nothing is counted.
     *
     * The sink is read once per call rather than captured, so installing a logger after a COPY
     * file's object has been initialised still takes effect.
     */
    private inline fun emit(write: (com.nikosm.voiceassistant.AppLogger) -> Unit): Int {
        write(LogSink.logger)
        return 0
    }
}
