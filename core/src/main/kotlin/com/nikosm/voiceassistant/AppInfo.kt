package com.nikosm.voiceassistant

/**
 * The application's version, shown in About — the window and the Settings section.
 *
 * Kept in step by hand with `allprojects { version = "0.1.0" }` and the packaging files — the
 * same discipline `PKGBUILD` documents for `pkgver`. There is no manifest entry to read: a
 * jpackage app image does not install one, and inventing a resource for a constant that changes
 * once a release would be more machinery than the field is worth.
 *
 * It lives in `:core` rather than `:desktop` because Settings renders it (M17 follow-up,
 * 2026-10-17) and `:core` may not import the desktop shell.
 */
const val APP_VERSION = "0.1.0"

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
