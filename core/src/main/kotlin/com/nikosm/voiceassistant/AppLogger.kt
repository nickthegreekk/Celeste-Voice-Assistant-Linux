package com.nikosm.voiceassistant

import java.util.logging.Level
import java.util.logging.Logger

/**
 * Android's six log levels, kept by name because the ported sources and the `android.util.Log`
 * shim both already speak them and a translation table would be one more thing to get wrong.
 */
enum class LogLevel { VERBOSE, DEBUG, INFO, WARN, ERROR, ASSERT }

/**
 * Where this application logs to — the replacement for `android.util.Log` (PLAN.md §3, D5).
 *
 * One method plus named conveniences, rather than six methods and no enum, because the shim has
 * to map Android's six levels onto something and an enum makes that mapping a value instead of a
 * switch. A logger that decides to drop VERBOSE has one place to do it.
 *
 * Deliberately not `android.util.Log`'s signature and deliberately not a log *framework*: M1
 * needs a sink, a level and a tag. Log rotation, journald integration and the settings UI's log
 * viewer arrive when something needs them.
 */
interface AppLogger {
    fun log(level: LogLevel, tag: String, message: String, error: Throwable? = null)

    fun v(tag: String, message: String, error: Throwable? = null) = log(LogLevel.VERBOSE, tag, message, error)
    fun d(tag: String, message: String, error: Throwable? = null) = log(LogLevel.DEBUG, tag, message, error)
    fun i(tag: String, message: String, error: Throwable? = null) = log(LogLevel.INFO, tag, message, error)
    fun w(tag: String, message: String, error: Throwable? = null) = log(LogLevel.WARN, tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = log(LogLevel.ERROR, tag, message, error)
    fun wtf(tag: String, message: String, error: Throwable? = null) = log(LogLevel.ASSERT, tag, message, error)
}

/**
 * The default sink: `java.util.logging` to stderr.
 *
 * From the JDK, so it costs no dependency, and stderr is what a systemd user unit hands to
 * journald — which is where README.md says logs go. VERBOSE and DEBUG sit below JUL's default
 * threshold on purpose, so they stay quiet until something raises the level, which is the
 * behaviour a release build of the phone has.
 *
 * M1 does not yet write a log *file* under `stateDir`; journald gets the output via the unit and
 * that is enough until the packaging milestone has a unit to point at.
 */
object StderrLogger : AppLogger {
    override fun log(level: LogLevel, tag: String, message: String, error: Throwable?) {
        // **A namespace, so a level can be raised for this application and nothing else.** JUL's
        // root logger is shared with the JDK's own logging, and raising *that* switches on `sun.awt`
        // and friends — measured on 2026-10-17 at 166 MB of chatter in under a minute, into a tmpfs.
        // The prefix is invisible in the output: JUL's default formatter prints the source class and
        // method, never the logger's name.
        val logger = Logger.getLogger("celeste.$tag")
        val jul = when (level) {
            LogLevel.VERBOSE -> Level.FINEST
            LogLevel.DEBUG -> Level.FINE
            LogLevel.INFO -> Level.INFO
            LogLevel.WARN -> Level.WARNING
            LogLevel.ERROR -> Level.SEVERE
            LogLevel.ASSERT -> Level.SEVERE
        }
        if (error == null) logger.log(jul, message) else logger.log(jul, message, error)
    }
}

/**
 * The bridge that lets a byte-identical COPY file log.
 *
 * `android.util.Log` is a static API, so a file that calls it cannot be handed anything: the only
 * way to route its output without editing it is a process-wide sink. That is what this is, and
 * it is the reason the shim exists at all (PLAN.md §7a) — the alternative was demoting three
 * COPY files to ADAPT so ten logging calls could name a logger.
 *
 * So there are deliberately two ways to reach the same logger, and they are not in competition:
 * ported code that can be injected uses `AppEnv.logger`, and a COPY file that cannot be touched
 * uses this. `LinuxAppEnv.install()` points both at the same instance before anything else runs,
 * so in a running process there is exactly one destination.
 *
 * Defaults to [StderrLogger] rather than to null, so `:core`'s own tests exercise the real path
 * and nothing has to remember to install a logger before the first log line.
 */
object LogSink {
    @Volatile
    var logger: AppLogger = StderrLogger
}
