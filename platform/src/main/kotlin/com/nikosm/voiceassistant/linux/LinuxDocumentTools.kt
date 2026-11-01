package com.nikosm.voiceassistant.linux

import com.nikosm.voiceassistant.AppLogger
import com.nikosm.voiceassistant.DocumentTools
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * M18.3's platform half: poppler's `pdftotext`, `pdfinfo` and `pdftoppm`, run as separate argv
 * processes (never a shell). `ProcessBuilder` is here because AGENTS.md §4 says it may live
 * nowhere else.
 *
 * Every process has a timeout and a stdout cap, and the whole tree is killed on timeout. A
 * missing tool is a null result with one log line, not an exception: the caller turns it into a
 * sentence about installing poppler.
 */
class LinuxDocumentTools(
    private val cacheDir: Path,
    private val logger: AppLogger,
) : DocumentTools {

    private data class Result(val ok: Boolean, val stdout: ByteArray)

    override suspend fun pdfText(path: Path): String? = withContext(Dispatchers.IO) {
        val result = run(
            argv = listOf("pdftotext", "-layout", "-enc", "UTF-8", path.toString(), "-"),
            timeoutSeconds = 30,
            maxStdoutBytes = 16 * 1024 * 1024,
        )
        if (!result.ok) {
            logger.d("DocumentTools", "pdftotext could not read ${path.fileName}; PDF text unavailable")
            null
        } else {
            String(result.stdout, Charsets.UTF_8)
        }
    }

    override suspend fun pdfPageCount(path: Path): Int? = withContext(Dispatchers.IO) {
        val result = run(
            argv = listOf("pdfinfo", path.toString()),
            timeoutSeconds = 15,
            maxStdoutBytes = 256 * 1024,
        )
        if (!result.ok) return@withContext null
        Regex("(?m)^Pages:\\s+(\\d+)")
            .find(String(result.stdout, Charsets.UTF_8))
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    override suspend fun renderPdfPage(path: Path, page: Int, dpi: Int): ByteArray? = withContext(Dispatchers.IO) {
        Files.createDirectories(cacheDir)
        val dir = runCatching { Files.createTempDirectory(cacheDir, "celeste-ocr-") }.getOrNull()
            ?: return@withContext null
        try {
            val prefix = dir.resolve("page").toString()
            val result = run(
                argv = listOf(
                    "pdftoppm", "-png", "-r", dpi.toString(),
                    "-f", page.toString(), "-l", page.toString(), "-singlefile",
                    path.toString(), prefix,
                ),
                timeoutSeconds = 60,
                maxStdoutBytes = 64 * 1024,
            )
            if (!result.ok) return@withContext null
            val png = Path.of("$prefix.png")
            if (!Files.isReadable(png)) return@withContext null
            if (Files.size(png) > 16L * 1024 * 1024) return@withContext null
            Files.readAllBytes(png)
        } finally {
            runCatching { dir.toFile().deleteRecursively() }
        }
    }

    private fun run(argv: List<String>, timeoutSeconds: Long, maxStdoutBytes: Int): Result {
        val process = try {
            ProcessBuilder(argv)
                .redirectErrorStream(false)
                .start()
        } catch (e: IOException) {
            logger.d("DocumentTools", "${argv.first()} is not available: ${e.message}")
            return Result(false, ByteArray(0))
        }
        try {
            // Drain stderr beside stdout; leaving it unread fills a pipe and hangs the child.
            val stderrDrain = Thread {
                runCatching { process.errorStream.readBytes() }
            }.apply {
                isDaemon = true
                start()
            }

            val out = ByteArrayOutputStream(minOf(maxStdoutBytes, 64 * 1024))
            var stored = 0
            val buffer = ByteArray(64 * 1024)
            try {
                while (true) {
                    val read = process.inputStream.read(buffer)
                    if (read < 0) break
                    val room = maxStdoutBytes - stored
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

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                logger.w("DocumentTools", "${argv.first()} timed out after ${timeoutSeconds}s")
                runCatching { process.descendants().forEach { it.destroyForcibly() } }
                runCatching { process.destroyForcibly() }
            }
            runCatching { process.waitFor() }
            runCatching { stderrDrain.join(500) }
            return Result(finished && process.exitValue() == 0, out.toByteArray())
        } catch (e: Exception) {
            logger.d("DocumentTools", "${argv.first()} failed: ${e.message}")
            runCatching { process.destroyForcibly() }
            return Result(false, ByteArray(0))
        }
    }
}
