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
    fun `home is the home it was given, and no environment variable can move it`() {
        // The one path here with no XDG variable behind it — `$HOME` is not `$XDG_*`. It exists so
        // `:core` can name `~/.ssh` as always-excluded for filesystem tools without resolving
        // `$HOME` for itself (AGENTS.md §4), which is why the second assertion is the one that
        // matters: a `HOME` in the environment is not consulted, because consulting it would be
        // `:core`'s rule broken one layer down where nobody would look for it.
        assertEquals(home, XdgPaths.resolve(env(), home).homeDir)
        assertEquals(
            home,
            XdgPaths.resolve(env("HOME" to temp.resolve("elsewhere").toString()), home).homeDir,
        )
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
    fun `the Desktop is the user's own folder, with no app directory under it`() {
        // A user dir like the pictures folder, and resolved the same way — but this one is written
        // to directly: `celeste-linux` under the Desktop would be a shortcut inside a folder nobody
        // opens, which is what the applications directory already was for this user (2026-10-16).
        // The `~/Desktop` fallback is the spec's, and the case that matters is the unset one: most
        // systems set XDG_DESKTOP_DIR nowhere and the environment variable is empty.
        assertEquals(home.resolve("Desktop"), XdgPaths.resolve(env(), home).desktopDir)
        assertEquals(home.resolve("Desktop"), XdgPaths.resolve(env("XDG_DESKTOP_DIR" to ""), home).desktopDir)
        assertEquals(
            temp.resolve("my-desktop"),
            XdgPaths.resolve(env("XDG_DESKTOP_DIR" to temp.resolve("my-desktop").toString()), home).desktopDir,
        )
        assertEquals(
            "a relative value is invalid and falls back, as it does for every other path here",
            home.resolve("Desktop"),
            XdgPaths.resolve(env("XDG_DESKTOP_DIR" to "Desktop"), home).desktopDir,
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
