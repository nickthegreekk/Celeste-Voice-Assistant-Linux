package com.nikosm.voiceassistant.linux

import com.nikosm.voiceassistant.AppEnv
import com.nikosm.voiceassistant.AppLogger
import com.nikosm.voiceassistant.LogSink
import com.nikosm.voiceassistant.Notifier
import com.nikosm.voiceassistant.Scheduler
import com.nikosm.voiceassistant.SpontaneousTimer
import com.nikosm.voiceassistant.StderrLogger
import java.nio.file.Path

/**
 * The Linux implementation of [AppEnv] — the four XDG directories plus the logger.
 *
 * It is a thin composition over [XdgPaths] rather than a second copy of the resolution rules:
 * `XdgPaths` owns the environment lookup and the fallbacks, this owns the application's view of
 * them. That split is what lets `XdgPathsTest` cover the fallbacks without constructing an
 * application environment, and this class be tested with an environment that is already resolved.
 *
 * The constructor is public and [resolve] is a convenience, so a caller that wants a different
 * debug setting — a `--debug` flag, when M1 grows a real command line — passes it directly rather
 * than having this class guess.
 */
class LinuxAppEnv(
    private val paths: XdgPaths,
    override val debugLogging: Boolean,
    override val logger: AppLogger = StderrLogger,
    // Defaults to the real one, sharing the logger above so a notifier that cannot find
    // `notify-send` says so through the same channel as everything else. Tests pass their own.
    override val notifier: Notifier = NotifySendNotifier(logger),
    // The stub is the default and not a temporary placeholder for a better one: M4 chooses between
    // a coroutine and a systemd user timer, and until it does, this is the only implementation that
    // exists. It logs once, at INFO, naming the milestone that replaces it.
    // Seam 10's implementation is a :core class, not a :platform one: a coroutine timer has no
    // platform API in it, and PLAN.md §10 says "spontaneous scheduler in the core". This was the
    // last Stub* in this module (`grep -rn 'Stub' platform/src/main` now finds none).
    override val scheduler: Scheduler = SpontaneousTimer(logger),
) : AppEnv {

    override val homeDir: Path get() = paths.homeDir
    override val configDir: Path get() = paths.configDir
    override val dataDir: Path get() = paths.dataDir
    override val stateDir: Path get() = paths.stateDir
    override val cacheDir: Path get() = paths.cacheDir
    override val picturesDir: Path get() = paths.picturesDir
    override val applicationsDir: Path get() = paths.applicationsDir
    override val desktopDir: Path get() = paths.desktopDir

    /**
     * Points the process-wide [LogSink] at this environment's logger, so a COPY file calling
     * `android.util.Log` — which cannot be handed anything, being a static API — lands in the same
     * place as injected code.
     *
     * Explicit rather than a side effect of construction. A constructor that silently rewires
     * global state would make every test that builds one an order-dependent test, and the single
     * caller that should do this (`Main`) is easy to find by grepping for it.
     */
    fun installLogging(): LinuxAppEnv {
        LogSink.logger = logger
        return this
    }

    companion object {
        /**
         * The one switch that turns conversation-content logging on. Not a `--debug` flag yet:
         * the command line arrives with the milestone that needs one, and until then this is the
         * only way to set it, which also keeps it out of shell history by default.
         */
        const val DEBUG_ENV = "CELESTE_DEBUG"

        fun resolve(
            env: (String) -> String? = { System.getenv(it) },
            home: Path = Path.of(System.getProperty("user.home")),
            logger: AppLogger = StderrLogger,
        ): LinuxAppEnv = LinuxAppEnv(
            paths = XdgPaths.resolve(env, home),
            debugLogging = env(DEBUG_ENV).isDebugRequested(),
            logger = logger,
        )

        /**
         * Anything else — unset, empty, "0", "no", a typo — is off.
         *
         * Fail-closed on purpose: this flag gates logging of prompts, model output and message
         * history (see [AppEnv.debugLogging]), so the harmless direction to get wrong is the one
         * where a private conversation does *not* reach the log.
         */
        private fun String?.isDebugRequested(): Boolean {
            val v = this?.trim()?.lowercase() ?: return false
            return v == "1" || v == "true" || v == "yes" || v == "on"
        }
    }
}
