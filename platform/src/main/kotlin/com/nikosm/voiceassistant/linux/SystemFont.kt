package com.nikosm.voiceassistant.linux

import java.nio.file.Files
import java.nio.file.Path

/**
 * The desktop's own UI font file, resolved through fontconfig (M17).
 *
 * Compose's Material typography otherwise renders with whatever font Skiko resolves itself,
 * which on Linux is not the system UI font — the single largest "this is an Android app" tell
 * after the layout. `fc-match` answers the same question every other desktop application asks
 * ("what is the sans-serif here?"), and the returned file is loaded as a Compose `FontFamily`.
 *
 * Reads only, and total: a machine without fontconfig, a font that does not exist, or a
 * process that does not answer within the timeout all return null, and the theme keeps its
 * default family. It never starts anything but `fc-match`, a query that reads the font
 * database and exits.
 */
fun systemUiFontPath(): Path? = runCatching {
    val process = ProcessBuilder("fc-match", "-f", "%{file}\n", "sans-serif")
        .redirectErrorStream(true)
        .start()
    // Drain beside the deadline: fontconfig normally answers in milliseconds and closes stdout,
    // but a hung `fc-match` must not hang the launch on a `readBytes()` that never sees EOF.
    val output = process.communicateWithin(PROBE_TIMEOUT_MS)
    if (output.timedOut || output.exitCode != 0) return null
    val firstLine = String(output.stdout).lineSequence().firstOrNull()?.trim()
    if (firstLine.isNullOrBlank()) return null
    val path = Path.of(firstLine)
    path.takeIf { Files.isRegularFile(it) }
}.getOrNull()

private const val PROBE_TIMEOUT_MS = 3_000L
