package com.nikosm.voiceassistant.linux

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Path

/**
 * The XDG fallbacks are the part of path resolution that breaks on somebody else's machine, and
 * the only reason `XdgPaths.resolve` takes the environment and the home directory as parameters is
 * so that this test can exist without reading the real environment or touching `~/.config`.
 *
 * Every path below is synthetic and none of them exist on disk. That is the point rather than a
 * shortcut: resolution is pure and must not stat anything, so a test that required real
 * directories would be testing something the production code does not do. The paths sit under the
 * JVM's temp root only so that they are unmistakably absolute on any machine.
 */
class XdgPathsTest {

    private val temp = Path.of(System.getProperty("java.io.tmpdir"), "xdg-paths-test")
    private val home = temp.resolve("home")

    private fun env(vararg pairs: Pair<String, String>): (String) -> String? {
        val map = pairs.toMap()
        return { map[it] }
    }

    @Test
    fun `unset variables fall back to the XDG defaults under HOME`() {
        val paths = XdgPaths.resolve(env(), home)

        assertEquals(home.resolve(".config"), paths.configHome)
        assertEquals(home.resolve(".local/share"), paths.dataHome)
        assertEquals(home.resolve(".local/state"), paths.stateHome)
        assertEquals(home.resolve(".cache"), paths.cacheHome)
    }

    @Test
    fun `the pictures folder, set or unset, keeps upstream's VoiceAssistant name`() {
        // XDG_PICTURES_DIR is a *user* dir rather than an XDG base dir, so it is the one path here
        // with no home-then-app structure above it — and the app directory under it is upstream's
        // ("VoiceAssistant", from the RELATIVE_PATH it asked MediaStore for), not this port's own
        // storage name. An image saved on either platform must land in the same folder.
        assertEquals(home.resolve("Pictures/VoiceAssistant"), XdgPaths.resolve(env(), home).picturesDir)
        assertEquals(
            home.resolve("Pictures/VoiceAssistant"),
            XdgPaths.resolve(env("XDG_PICTURES_DIR" to ""), home).picturesDir,
        )
        assertEquals(
            temp.resolve("my-pics/VoiceAssistant"),
            XdgPaths.resolve(env("XDG_PICTURES_DIR" to temp.resolve("my-pics").toString()), home).picturesDir,
        )
    }

    @Test
    fun `absolute values are honoured`() {
        val paths = XdgPaths.resolve(
            env(
                "XDG_CONFIG_HOME" to temp.resolve("config").toString(),
                "XDG_DATA_HOME" to temp.resolve("data").toString(),
                "XDG_STATE_HOME" to temp.resolve("state").toString(),
                "XDG_CACHE_HOME" to temp.resolve("cache").toString(),
            ),
            home,
        )

        assertEquals(temp.resolve("config"), paths.configHome)
        assertEquals(temp.resolve("data"), paths.dataHome)
        assertEquals(temp.resolve("state"), paths.stateHome)
        assertEquals(temp.resolve("cache"), paths.cacheHome)
    }

    @Test
    fun `an empty value is treated as unset`() {
        // The specification says "unset or empty", and an empty XDG_CONFIG_HOME is exactly what a
        // shell exports from an unset variable in some configurations.
        val paths = XdgPaths.resolve(
            env("XDG_CONFIG_HOME" to "", "XDG_CACHE_HOME" to ""),
            home,
        )

        assertEquals(home.resolve(".config"), paths.configHome)
        assertEquals(home.resolve(".cache"), paths.cacheHome)
    }

    @Test
    fun `a relative value is ignored rather than resolved against the working directory`() {
        // The specification requires absolute paths and says a relative one is invalid. Honouring
        // it would write a config directory wherever the process happened to be started, which is
        // a far worse outcome than falling back.
        val paths = XdgPaths.resolve(
            env("XDG_CONFIG_HOME" to "relative/config", "XDG_DATA_HOME" to "./data"),
            home,
        )

        assertEquals(home.resolve(".config"), paths.configHome)
        assertEquals(home.resolve(".local/share"), paths.dataHome)
    }

    @Test
    fun `a trailing slash does not change the resolved path`() {
        val paths = XdgPaths.resolve(
            env("XDG_CONFIG_HOME" to "${temp.resolve("config")}/"),
            home,
        )

        assertEquals(temp.resolve("config"), paths.configHome)
    }

    @Test
    fun `the app directories nest under the resolved base`() {
        val paths = XdgPaths.resolve(env("XDG_CONFIG_HOME" to temp.resolve("config").toString()), home)

        assertEquals(temp.resolve("config").resolve(XdgPaths.APP_DIR), paths.configDir)
        assertEquals(home.resolve(".local/share").resolve(XdgPaths.APP_DIR), paths.dataDir)
        assertEquals(home.resolve(".local/state").resolve(XdgPaths.APP_DIR), paths.stateDir)
        assertEquals(home.resolve(".cache").resolve(XdgPaths.APP_DIR), paths.cacheDir)
    }
}
