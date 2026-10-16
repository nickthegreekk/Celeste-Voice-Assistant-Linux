package com.nikosm.voiceassistant.linux

import java.nio.file.Path

/**
 * The four XDG base directories this application uses, resolved for a Linux session.
 *
 * This is the only place in the project allowed to read `XDG_*`, `$HOME` or a `~`-relative path
 * (AGENTS.md §4). `:core` never sees the environment — it will receive these paths from `AppEnv`
 * when M1 builds that on top of this. Keeping the resolution in one class is not tidiness: the
 * fallbacks are the part that breaks on somebody else's machine, and they are only testable if
 * they live somewhere a test can reach without touching the real `~/.config`.
 *
 * [resolve] therefore takes the environment and the home directory as parameters rather than
 * reading them itself, which is what lets `XdgPathsTest` cover the unset case at all.
 *
 * The rules are the XDG Base Directory Specification's, not invented here:
 *
 *  - an environment variable that is unset **or empty** falls back to the default;
 *  - a value that is not an absolute path is invalid and is ignored, falling back to the default,
 *    because the specification says so and because a relative `XDG_CONFIG_HOME` would otherwise
 *    silently write a config directory into whatever the process's working directory happens to be.
 *
 * The app-specific directories are the base plus a fixed leaf, so nothing else in the project ever
 * writes the string "celeste-linux" next to a path.
 */
class XdgPaths(
    val configHome: Path,
    val dataHome: Path,
    val stateHome: Path,
    val cacheHome: Path,
    /**
     * `XDG_PICTURES_DIR` — a *user* dir, not an XDG base dir, so it resolves alone rather than as a
     * home to hang an application directory off. The spec's fallback is `$HOME/Pictures`, and a
     * value that is empty or not absolute falls back the same way `base` does for the others.
     */
    val picturesHome: Path,
) {
    /** Settings, personas, servers, skills, image-generation entries (README.md, *Where things live*). */
    val configDir: Path get() = configHome.resolve(APP_DIR)

    /** Chat history per persona, room transcripts, persona artwork. */
    val dataDir: Path get() = dataHome.resolve(APP_DIR)

    /** Logs, usage-tracking counters. */
    val stateDir: Path get() = stateHome.resolve(APP_DIR)

    /** Utterance WAVs, downloaded images, model downloads. */
    val cacheDir: Path get() = cacheHome.resolve(APP_DIR)

    /**
     * Where "Save to gallery" writes: `Pictures/VoiceAssistant/`, which is upstream's own folder —
     * it asked `MediaStore` for `RELATIVE_PATH = Pictures/VoiceAssistant` — so an image saved on
     * either platform lands in the same place within the user's pictures.
     */
    val picturesDir: Path get() = picturesHome.resolve(PICTURES_APP_DIR)

    companion object {
        const val APP_DIR = "celeste-linux"

        /**
         * The pictures folder, which is **not** [APP_DIR] and deliberately so: it is the user's, not
         * this port's private storage. Upstream asked `MediaStore` for
         * `RELATIVE_PATH = Pictures/VoiceAssistant`, so an image saved on either platform lands in
         * the same place — and it is the name the user asked for when they chose the folder.
         */
        const val PICTURES_APP_DIR = "VoiceAssistant"

        fun resolve(
            env: (String) -> String? = { System.getenv(it) },
            home: Path = Path.of(System.getProperty("user.home")),
        ): XdgPaths = XdgPaths(
            configHome = base(env, "XDG_CONFIG_HOME", home.resolve(".config")),
            dataHome = base(env, "XDG_DATA_HOME", home.resolve(".local/share")),
            stateHome = base(env, "XDG_STATE_HOME", home.resolve(".local/state")),
            cacheHome = base(env, "XDG_CACHE_HOME", home.resolve(".cache")),
            picturesHome = base(env, "XDG_PICTURES_DIR", home.resolve("Pictures")),
        )

        private fun base(env: (String) -> String?, name: String, fallback: Path): Path {
            val raw = env(name)?.takeIf { it.isNotEmpty() } ?: return fallback
            val candidate = Path.of(raw)
            return if (candidate.isAbsolute) candidate.normalize() else fallback
        }
    }
}
