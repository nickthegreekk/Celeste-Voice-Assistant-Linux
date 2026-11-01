package com.nikosm.voiceassistant.linux

import com.nikosm.voiceassistant.ScreenCapture
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M19's platform half without a screen: each strategy is a real process, but the test hands it a
 * tiny fixture and a path instead of a display. The default strategy list is not used here — the
 * machine running the test may or may not have a real capture tool.
 */
class LinuxScreenCaptureTest {

    private val png = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x01, 0x02, 0x03,
    )

    private val logger = RecordingLogger()

    @Test
    fun `the png signature is what makes a capture a screenshot`() {
        assertTrue(looksLikePng(png))
        assertFalse(looksLikePng("ERROR: no display".toByteArray()))
        assertFalse(looksLikePng(ByteArray(4)))
    }

    @Test
    fun `a stdout tool produces the image`() = runTest {
        val dir = Files.createTempDirectory("celeste-screen-test-")
        val fixture = dir.resolve("fixture.png").also { Files.write(it, png) }
        try {
            val capture = LinuxScreenCapture(
                cacheDir = dir.resolve("cache"),
                logger = logger,
                strategies = listOf(
                    ScreenCaptureStrategy("cat", writesFile = false) { _ -> listOf("cat", fixture.toString()) },
                ),
            )

            assertArrayEquals(png, capture.capture())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a file tool's output is read back`() = runTest {
        val dir = Files.createTempDirectory("celeste-screen-test-")
        val fixture = dir.resolve("fixture.png").also { Files.write(it, png) }
        try {
            val capture = LinuxScreenCapture(
                cacheDir = dir.resolve("cache"),
                logger = logger,
                strategies = listOf(
                    ScreenCaptureStrategy("cp", writesFile = true) { file ->
                        listOf("sh", "-c", "cp '$fixture' '$file'")
                    },
                ),
            )

            assertArrayEquals(png, capture.capture())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a missing tool falls through to the next strategy`() = runTest {
        val dir = Files.createTempDirectory("celeste-screen-test-")
        val fixture = dir.resolve("fixture.png").also { Files.write(it, png) }
        try {
            val capture = LinuxScreenCapture(
                cacheDir = dir.resolve("cache"),
                logger = logger,
                strategies = listOf(
                    ScreenCaptureStrategy("missing", writesFile = false) { _ ->
                        listOf("celeste-definitely-not-a-command")
                    },
                    ScreenCaptureStrategy("cat", writesFile = false) { _ -> listOf("cat", fixture.toString()) },
                ),
            )

            assertArrayEquals(png, capture.capture())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a tool that exits zero but prints text is not a screenshot`() = runTest {
        val dir = Files.createTempDirectory("celeste-screen-test-")
        val fixture = dir.resolve("fixture.png").also { Files.write(it, png) }
        try {
            val capture = LinuxScreenCapture(
                cacheDir = dir.resolve("cache"),
                logger = logger,
                strategies = listOf(
                    ScreenCaptureStrategy("text", writesFile = false) { _ ->
                        listOf("sh", "-c", "printf 'not a png'")
                    },
                    ScreenCaptureStrategy("cat", writesFile = false) { _ -> listOf("cat", fixture.toString()) },
                ),
            )

            assertArrayEquals(png, capture.capture())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `no working tool is a null capture, not an exception`() = runTest {
        val dir = Files.createTempDirectory("celeste-screen-test-")
        try {
            val capture: ScreenCapture = LinuxScreenCapture(
                cacheDir = dir.resolve("cache"),
                logger = logger,
                strategies = listOf(
                    ScreenCaptureStrategy("missing", writesFile = false) { _ ->
                        listOf("celeste-definitely-not-a-command")
                    },
                ),
            )

            assertNull(capture.capture())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the packaged strategies are argv only, never a shell`() {
        val target = Path.of("/tmp/celeste-screen.png")
        DEFAULT_SCREEN_CAPTURE_STRATEGIES.forEach { strategy ->
            val argv = strategy.argv(target)
            assertTrue(strategy.name.isNotBlank())
            assertTrue("${strategy.name} must have arguments", argv.isNotEmpty())
            assertFalse("${strategy.name} must not run a shell", argv.first() == "sh" || argv.first() == "bash")
        }
    }
}
