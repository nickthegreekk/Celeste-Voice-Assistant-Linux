package com.nikosm.voiceassistant.linux

import com.nikosm.voiceassistant.AppLogger
import com.nikosm.voiceassistant.SCREEN_MAX_CAPTURE_BYTES
import com.nikosm.voiceassistant.ScreenCapture
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One way to take a screenshot on this desktop. */
data class ScreenCaptureStrategy(
    val name: String,
    /**
     * True when [argv] writes the PNG to the path it is handed; false when the tool writes the
     * image to stdout instead. Both are validated the same way — a PNG signature and a size cap —
     * so a tool that prints an error and exits 0 is not mistaken for a screenshot.
     */
    val writesFile: Boolean,
    val argv: (Path) -> List<String>,
)

/**
 * M19's platform half: one PNG of the whole screen through whichever capture tool this desktop
 * has. `ProcessBuilder` is here because AGENTS.md §4 says it may live nowhere else.
 *
 * The strategies are tried in order and the first that produces a PNG wins. `import` is first
 * because ImageMagick is present on both development machines and works on X11; `grim` covers
 * Wayland; the desktop-specific tools cover a box without ImageMagick. A missing tool is a debug
 * line and the next strategy, not an exception: the caller turns "none worked" into a sentence.
 *
 * Every process has a timeout and a stdout cap, and the whole tree is killed on timeout. The
 * temporary directory is deleted in a `finally`, so a capture leaves no file behind.
 */
class LinuxScreenCapture(
    private val cacheDir: Path,
    private val logger: AppLogger,
    private val strategies: List<ScreenCaptureStrategy> = DEFAULT_SCREEN_CAPTURE_STRATEGIES,
) : ScreenCapture {

    private data class Result(val ok: Boolean, val bytes: ByteArray)

    override suspend fun capture(): ByteArray? = withContext(Dispatchers.IO) {
        Files.createDirectories(cacheDir)
        for (strategy in strategies) {
            val dir = runCatching { Files.createTempDirectory(cacheDir, "celeste-screen-") }.getOrNull()
            if (dir == null) {
                logger.w("ScreenCapture", "could not create a temporary directory in $cacheDir")
                return@withContext null
            }
            try {
                val bytes = runStrategy(strategy, dir)
                if (bytes != null) {
                    logger.d("ScreenCapture", "captured ${bytes.size} bytes with ${strategy.name}")
                    return@withContext bytes
                }
            } finally {
                runCatching { dir.toFile().deleteRecursively() }
            }
        }
        logger.w("ScreenCapture", "no screenshot tool could capture the screen")
        null
    }

    private fun runStrategy(strategy: ScreenCaptureStrategy, dir: Path): ByteArray? {
        val target = dir.resolve("screen.png")
        val argv = strategy.argv(target)
        val process = try {
            ProcessBuilder(argv)
                .redirectErrorStream(false)
                .start()
        } catch (e: IOException) {
            logger.d("ScreenCapture", "${strategy.name} is not available: ${e.message}")
            return null
        }
        try {
            // Drain stderr beside stdout; leaving it unread fills a pipe and hangs the child.
            val stderrDrain = Thread {
                runCatching { process.errorStream.readBytes() }
            }.apply {
                isDaemon = true
                start()
            }

            val out = ByteArrayOutputStream(minOf(SCREEN_MAX_CAPTURE_BYTES, 64 * 1024))
            var stored = 0
            val buffer = ByteArray(64 * 1024)
            try {
                while (true) {
                    val read = process.inputStream.read(buffer)
                    if (read < 0) break
                    val room = SCREEN_MAX_CAPTURE_BYTES - stored
                    if (room > 0) {
                        val take = minOf(room, read)
                        out.write(buffer, 0, take)
                        stored += take
                    }
                    // Past the cap the bytes are read and discarded so the pipe keeps draining.
                }
            } catch (_: IOException) {
                // The stream was closed under us; what was read is the output.
            }

            val finished = process.waitFor(SCREEN_CAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                logger.w("ScreenCapture", "${strategy.name} timed out after ${SCREEN_CAPTURE_TIMEOUT_SECONDS}s")
                runCatching { process.descendants().forEach { it.destroyForcibly() } }
                runCatching { process.destroyForcibly() }
            }
            runCatching { process.waitFor() }
            runCatching { stderrDrain.join(500) }
            if (!finished || process.exitValue() != 0) {
                logger.d("ScreenCapture", "${strategy.name} exited unsuccessfully; trying the next tool")
                return null
            }

            val bytes = if (strategy.writesFile) {
                if (!Files.isReadable(target)) return null
                if (Files.size(target) > SCREEN_MAX_CAPTURE_BYTES) {
                    logger.w("ScreenCapture", "${strategy.name} produced a capture larger than the cap")
                    return null
                }
                Files.readAllBytes(target)
            } else {
                out.toByteArray()
            }
            if (!looksLikePng(bytes)) {
                logger.d("ScreenCapture", "${strategy.name} did not produce a PNG; trying the next tool")
                return null
            }
            return bytes
        } catch (e: Exception) {
            logger.d("ScreenCapture", "${strategy.name} failed: ${e.message}")
            runCatching { process.destroyForcibly() }
            return null
        }
    }
}

/** The PNG magic number: what makes a capture a screenshot rather than a tool's error text. */
internal fun looksLikePng(bytes: ByteArray): Boolean {
    if (bytes.size < PNG_SIGNATURE.size) return false
    return PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }
}

private val PNG_SIGNATURE = byteArrayOf(
    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
)

internal const val SCREEN_CAPTURE_TIMEOUT_SECONDS = 15L

/**
 * Tried in order. `import` needs ImageMagick and an X11 root window; `grim` is the Wayland tool;
 * the rest are desktop-specific fallbacks. Each returns the image on stdout or writes the path it
 * is given — never a shell, so a filename cannot become an argument.
 */
val DEFAULT_SCREEN_CAPTURE_STRATEGIES: List<ScreenCaptureStrategy> = listOf(
    ScreenCaptureStrategy("import", writesFile = false) { _ ->
        listOf("import", "-window", "root", "png:-")
    },
    ScreenCaptureStrategy("grim", writesFile = false) { _ ->
        listOf("grim", "-")
    },
    ScreenCaptureStrategy("xfce4-screenshooter", writesFile = true) { file ->
        listOf("xfce4-screenshooter", "-f", "-s", file.toString())
    },
    ScreenCaptureStrategy("gnome-screenshot", writesFile = true) { file ->
        listOf("gnome-screenshot", "-f", file.toString())
    },
    ScreenCaptureStrategy("scrot", writesFile = true) { file ->
        listOf("scrot", "-o", file.toString())
    },
)
