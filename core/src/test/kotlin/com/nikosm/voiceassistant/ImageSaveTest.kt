package com.nikosm.voiceassistant

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The gallery save, which is a folder and a copy on this platform.
 *
 * What is worth pinning is not the copy — that is one call — but the two promises the button makes:
 * the file keeps upstream's name shape and lands in the folder the user expects, and a save never
 * destroys an earlier one. The folder is the user's own `Pictures`, so "it overwrote something" is
 * the failure that cannot be apologised for afterwards.
 */
class ImageSaveTest {

    private val root: Path = Files.createTempDirectory("celeste-image-save").also { it.toFile().deleteOnExit() }
    private val pictures: Path = root.resolve("Pictures/VoiceAssistant")

    @After
    fun cleanUp() {
        // The folder is real, so it is emptied for real: deleteOnExit does not recurse.
        if (Files.exists(root)) {
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } }
        }
    }

    private fun source(name: String = "generated.png", bytes: ByteArray = byteArrayOf(1, 2, 3)): Path =
        root.resolve(name).also { Files.write(it, bytes) }

    @Test
    fun `the image lands in the VoiceAssistant folder under upstream's name shape`() {
        val saved = saveImageToPictures(source(), pictures, nowMillis = 1_700_000_000_000)

        assertEquals(pictures.resolve("Gen_1700000000000.png"), saved)
        assertTrue("the folder is created rather than required to exist", Files.exists(saved))
    }

    @Test
    fun `the bytes are the source's, unchanged`() {
        // A copy, not a re-encode: upstream wrote the file through to the resolver as it was, and a
        // re-encode here would quietly recompress a generation the user asked to keep.
        val bytes = ByteArray(64) { it.toByte() }
        val saved = saveImageToPictures(source(bytes = bytes), pictures, nowMillis = 42)

        assertTrue(Files.readAllBytes(saved!!).contentEquals(bytes))
    }

    @Test
    fun `a second save in the same millisecond does not overwrite the first`() {
        val first = saveImageToPictures(source(bytes = byteArrayOf(1)), pictures, nowMillis = 7)
        val second = saveImageToPictures(source(bytes = byteArrayOf(2)), pictures, nowMillis = 7)

        assertEquals(pictures.resolve("Gen_7.png"), first)
        assertEquals(pictures.resolve("Gen_7_1.png"), second)
        assertTrue("the first save survives", Files.exists(first))
        assertTrue(Files.readAllBytes(first!!).contentEquals(byteArrayOf(1)))
    }

    @Test
    fun `a source that is not there is a null, not an exception`() {
        // The button is reachable on a message whose file was deleted from under it — a cache sweep,
        // or the user tidying up. That is a snackbar, not a crash.
        assertNull(saveImageToPictures(root.resolve("never-existed.png"), pictures, nowMillis = 1))
    }

    @Test
    fun `a folder that cannot be created is a null too`() {
        // A file where the folder should be: `createDirectories` fails, and the caller reports it.
        val blocked = root.resolve("not-a-folder").also { Files.write(it, byteArrayOf(9)) }

        assertNull(saveImageToPictures(source(), blocked.resolve("VoiceAssistant"), nowMillis = 1))
    }
}
