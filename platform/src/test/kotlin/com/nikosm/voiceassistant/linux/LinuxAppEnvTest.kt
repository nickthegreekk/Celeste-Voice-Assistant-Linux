package com.nikosm.voiceassistant.linux

import com.nikosm.voiceassistant.AppLogger
import com.nikosm.voiceassistant.LogLevel
import com.nikosm.voiceassistant.LogSink
import com.nikosm.voiceassistant.StderrLogger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

/**
 * `LinuxAppEnv` is thin by design — it composes `XdgPaths` with a logger and a flag — so what is
 * worth testing is the part that is not `XdgPaths`' job and not obvious: that the four directories
 * are the *application* ones rather than the bare XDG bases, and that the debug flag fails closed.
 *
 * The flag matters more than its size suggests. It gates logging of prompts, model output and
 * message history (AGENTS.md §5), so the direction that must be pinned is the one where an
 * unrecognised value leaves it OFF. A test that only checked "1" would not notice a resolver that
 * treated a typo as consent.
 *
 * Every path is synthetic and none exist on disk; resolution does not stat anything.
 */
class LinuxAppEnvTest {

    private val temp = Path.of(System.getProperty("java.io.tmpdir"), "linux-app-env-test")
    private val home = temp.resolve("home")

    private val originalSink = LogSink.logger

    @After
    fun restoreSink() {
        LogSink.logger = originalSink
    }

    private fun env(vararg pairs: Pair<String, String>): (String) -> String? {
        val map = pairs.toMap()
        return { map[it] }
    }

    @Test
    fun `the four directories are the application's, not the bare XDG bases`() {
        val appEnv = LinuxAppEnv.resolve(env(), home)

        assertEquals(home.resolve(".config").resolve(XdgPaths.APP_DIR), appEnv.configDir)
        assertEquals(home.resolve(".local/share").resolve(XdgPaths.APP_DIR), appEnv.dataDir)
        assertEquals(home.resolve(".local/state").resolve(XdgPaths.APP_DIR), appEnv.stateDir)
        assertEquals(home.resolve(".cache").resolve(XdgPaths.APP_DIR), appEnv.cacheDir)
    }

    @Test
    fun `data and cache stay distinct`() {
        // The port must not collapse these: on the phone one is filesDir and the other is
        // cacheDir, and treating the cache as durable turns an eviction into data loss
        // (persona artwork and message images live in the former).
        val appEnv = LinuxAppEnv.resolve(env(), home)

        assertFalse(appEnv.dataDir == appEnv.cacheDir)
    }

    @Test
    fun `debug logging is off when the variable is unset`() {
        assertFalse(LinuxAppEnv.resolve(env(), home).debugLogging)
    }

    @Test
    fun `debug logging accepts the obvious ways of saying yes`() {
        for (value in listOf("1", "true", "TRUE", "yes", "on", " 1 ")) {
            val appEnv = LinuxAppEnv.resolve(env(LinuxAppEnv.DEBUG_ENV to value), home)
            assertTrue("expected '$value' to enable debug logging", appEnv.debugLogging)
        }
    }

    @Test
    fun `debug logging fails closed on anything else, including a typo`() {
        for (value in listOf("", "0", "false", "no", "off", "ture", "2")) {
            val appEnv = LinuxAppEnv.resolve(env(LinuxAppEnv.DEBUG_ENV to value), home)
            assertFalse("'$value' must not enable logging of conversation content", appEnv.debugLogging)
        }
    }

    @Test
    fun `installLogging points the process sink at this environment's logger`() {
        // This is what makes a COPY file's `android.util.Log` call land in the same place as
        // injected `AppEnv.logger` calls — the whole reason LogSink exists.
        val mine = object : AppLogger {
            override fun log(level: LogLevel, tag: String, message: String, error: Throwable?) = Unit
        }

        val appEnv = LinuxAppEnv.resolve(env(), home, logger = mine)
        assertSame(mine, appEnv.installLogging().logger)
        assertSame(mine, LogSink.logger)
    }

    @Test
    fun `the logger defaults to stderr rather than to nothing`() {
        assertSame(StderrLogger, LinuxAppEnv.resolve(env(), home).logger)
    }

    /**
     * **The switch has to reach the framework, not just the shim.** `StderrLogger` maps DEBUG to
     * `java.util.logging.Level.FINE`, and a JUL logger's default level is INFO — so every `Log.d` in
     * the port, including the prompt and payload dumps this switch exists for, was discarded by the
     * framework rather than by a decision. A diagnosis on 2026-10-17 ran for an hour with the
     * variable set and nothing to show for it.
     *
     * Both directions are asserted: a resolver that turned FINE on unconditionally would leak
     * conversation content into a log on a machine where nobody asked for it (AGENTS.md §5), which is
     * the failure this flag's fail-closed resolver exists to prevent, one layer down.
     */
    @Test
    fun `the debug switch reaches the logging framework, and only when it is on`() {
        val root = java.util.logging.Logger.getLogger("")
        val levelBefore = root.level
        val handlerLevels = root.handlers.map { it.level }
        try {
            LinuxAppEnv.resolve(env(DEBUG to "1"), home).installLogging()
            assertTrue(
                "with the switch on, a DEBUG line must be loggable — otherwise the flag is inert",
                root.isLoggable(java.util.logging.Level.FINE),
            )

            root.level = java.util.logging.Level.INFO
            root.handlers.forEach { it.level = java.util.logging.Level.INFO }
            LinuxAppEnv.resolve(env(), home).installLogging()
            assertFalse(
                "with it off, FINE stays discarded — the fail-closed direction",
                root.isLoggable(java.util.logging.Level.FINE),
            )
        } finally {
            root.level = levelBefore
            root.handlers.forEachIndexed { i, h -> h.level = handlerLevels[i] }
        }
    }

    private companion object {
        const val DEBUG = "CELESTE_DEBUG"
    }
}
