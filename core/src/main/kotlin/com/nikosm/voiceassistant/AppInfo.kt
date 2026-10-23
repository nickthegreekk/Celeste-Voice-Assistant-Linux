package com.nikosm.voiceassistant

/**
 * The application's version, shown in About — the window and the Settings section.
 *
 * Kept in step by hand with `allprojects { version = "0.2.2" }` and the packaging files — the
 * same discipline `PKGBUILD` documents for `pkgver`. There is no manifest entry to read: a
 * jpackage app image does not install one, and inventing a resource for a constant that changes
 * once a release would be more machinery than the field is worth.
 *
 * It lives in `:core` rather than `:desktop` because Settings renders it (M17 follow-up,
 * 2026-10-17) and `:core` may not import the desktop shell.
 */
const val APP_VERSION = "0.2.2"

/**
 * The project's public page (2026-10-18).
 *
 * Set since the project was published; the About window links to it, and [projectReleasesUrl]
 * derives the page updates are obtained from. Keeping it a single constant means the application
 * never invents an address: an empty value would make About say "Not published yet" and disable
 * the updates button.
 */
const val PROJECT_URL = "https://github.com/nickthegreekk/Celeste-Voice-Assistant-Linux"

/**
 * Where updates are obtained: the project's releases page, or null while [PROJECT_URL] is blank.
 *
 * The application does not check for updates on its own — PLAN.md §8 keeps the automatic check
 * dropped, and §5 forbids network calls the user did not set up. The one manual check is the
 * About window's **Check for updates** button ([checkForLatestRelease]); it reports a version and
 * installs nothing. "The update path" is this page: the user opens it, sees the latest release,
 * and installs it with the package manager the application was installed with. There is nothing
 * to poll and nothing to trust beyond the page they chose to open.
 */
fun projectReleasesUrl(): String? {
    val root = PROJECT_URL.trim().trimEnd('/')
    return if (root.isEmpty()) null else "$root/releases"
}

private const val PIN_RESOURCE = "upstream-commit.txt"

/**
 * Reads the upstream revision the ported files were copied from. The resource lives in `:core`
 * beside the copied sources rather than in the desktop shell, so that the pin travels with the
 * files it describes and cannot be shipped without them.
 *
 * Both readers — the About window and the Settings About section — and `--selftest` go through
 * here, so they cannot disagree about the pin.
 */
fun upstreamPin(): String {
    val pin = AssistantService::class.java.classLoader
        .getResourceAsStream(PIN_RESOURCE)
        ?.use { it.readBytes().decodeToString() }
        ?.trim()

    if (pin.isNullOrEmpty()) {
        // A missing pin is a warning, not a failure: this function's callers print it, and the
        // selftest's rule is that only a failed checks-and-writes probe changes the exit code.
        return "UNKNOWN — $PIN_RESOURCE missing from the classpath (warning; does not affect the exit code)"
    }
    return pin
}
