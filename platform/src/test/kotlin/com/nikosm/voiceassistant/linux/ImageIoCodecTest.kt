package com.nikosm.voiceassistant.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * The artwork path is the one place in the port where a bug is invisible until it is not: a
 * wrongly-chosen subsample still produces an image of the right size, just a worse one, and the
 * failure only shows up as a permanently degraded persona icon. So `subsampleFor` is tested
 * directly rather than inferred from a resize.
 *
 * Everything runs in a temp directory on files this test writes itself — no real photo, and
 * nothing of the author's.
 */
class ImageIoCodecTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val codec = ImageIoCodec()

    private fun writeImage(name: String, width: Int, height: Int, transparent: Boolean = false): Path {
        val type = if (transparent) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
        val image = BufferedImage(width, height, type)
        val g = image.createGraphics()
        try {
            // A few blocks of colour, so a resize has something to be wrong about.
            g.color = java.awt.Color.RED
            g.fillRect(0, 0, width / 2, height / 2)
            g.color = java.awt.Color.BLUE
            g.fillRect(width / 2, height / 2, width / 2, height / 2)
        } finally {
            g.dispose()
        }
        val file = temp.root.toPath().resolve(name)
        ImageIO.write(image, if (transparent) "png" else "png", file.toFile())
        return file
    }

    private fun size(file: Path): Pair<Int, Int> {
        val image = ImageIO.read(file.toFile())
        assertNotNull("output should be a readable image", image)
        return image.width to image.height
    }

    // ------------------------------------------------------------------ subsample choice

    @Test
    fun `a four-by-three photo subsamples by the largest power of two that keeps both edges`() {
        // 4000x3000 at 512: /2 leaves 2000x1500 (both fine), /4 leaves 1000x750 (both fine),
        // /8 would leave 500x375 and the 500 is under the target, so it stops at 4.
        assertEquals(4, subsampleFor(4000, 3000, 512))
    }

    @Test
    fun `a panorama is not subsampled on its long edge alone`() {
        // The case the rule exists for. 300/2 = 150, well under 512, so the short edge forbids any
        // subsampling at all — even though the 4000-wide edge would happily take four.
        assertEquals(1, subsampleFor(4000, 300, 512))
    }

    @Test
    fun `an image already within the target is not subsampled`() {
        assertEquals(1, subsampleFor(100, 80, 512))
    }

    @Test
    fun `a square exactly twice the target subsamples by two and lands exactly on it`() {
        assertEquals(2, subsampleFor(1024, 1024, 512))
    }

    // ------------------------------------------------------------------------- the conversion

    @Test
    fun `a large image comes out at the target size with its aspect ratio intact`() {
        val source = writeImage("big.png", 2000, 1000)
        val target = temp.root.toPath().resolve("icon.jpg")

        assertTrue(codec.downscaleToJpeg(source, target, maxDim = 512, quality = 88))

        val (width, height) = size(target)
        assertEquals(512, width)
        assertEquals(256, height)
    }

    @Test
    fun `a small image is not enlarged`() {
        // Upscaling would add bytes and detail that were never in the file.
        val source = writeImage("small.png", 100, 80)
        val target = temp.root.toPath().resolve("icon.jpg")

        assertTrue(codec.downscaleToJpeg(source, target, maxDim = 512, quality = 88))

        assertEquals(100 to 80, size(target))
    }

    @Test
    fun `a panorama is downscaled to the long edge and keeps its proportions`() {
        val source = writeImage("wide.png", 4000, 300)
        val target = temp.root.toPath().resolve("background.jpg")

        assertTrue(codec.downscaleToJpeg(source, target, maxDim = 1080, quality = 88))

        val (width, height) = size(target)
        assertEquals(1080, width)
        // 300 * 1080/4000 = 81; the exact rescale rounds, and one pixel either way is fine.
        assertTrue("height was $height, expected about 81", height in 80..82)
    }

    @Test
    fun `a transparent png is composited onto white rather than coming out black`() {
        val source = writeImage("alpha.png", 64, 64, transparent = true)
        val target = temp.root.toPath().resolve("icon.jpg")

        assertTrue(codec.downscaleToJpeg(source, target, maxDim = 512, quality = 88))

        // The fixture paints only the top-left and bottom-right quadrants, so (0, 0) is opaque red
        // and this pixel is fully transparent in the source. It is the one that would come out
        // black if alpha were simply dropped.
        val image = ImageIO.read(target.toFile())
        val transparentInSource = java.awt.Color(image.getRGB(image.width - 1, 0))
        assertTrue(
            "a transparent pixel should composite onto white, was $transparentInSource",
            transparentInSource.red > 200 && transparentInSource.green > 200 && transparentInSource.blue > 200,
        )

        // And the opaque part is still the colour it was — the flatten must not tint everything.
        val painted = java.awt.Color(image.getRGB(1, 1))
        assertTrue("the painted quadrant should stay red, was $painted", painted.red > 200 && painted.green < 60)
    }

    @Test
    fun `the output directory is created when it does not exist`() {
        val source = writeImage("big.png", 2000, 1000)
        val target = temp.root.toPath().resolve("persona_images").resolve("icon_1_ab.jpg")

        assertTrue(codec.downscaleToJpeg(source, target, maxDim = 512, quality = 88))
        assertTrue(Files.exists(target))
    }

    // ---------------------------------------------------------------------- the failure modes

    @Test
    fun `a source that is not an image is reported rather than thrown`() {
        // What a user picks when they pick the wrong file. Upstream returns null and the caller
        // keeps the previous artwork; an exception here would fail the persona save instead.
        val notAnImage = temp.root.toPath().resolve("notes.txt")
        Files.writeString(notAnImage, "this is not a picture")

        assertFalse(codec.downscaleToJpeg(notAnImage, temp.root.toPath().resolve("out.jpg"), 512, 88))
    }

    @Test
    fun `a missing source is reported rather than thrown`() {
        val missing = temp.root.toPath().resolve("gone.png")

        assertFalse(codec.downscaleToJpeg(missing, temp.root.toPath().resolve("out.jpg"), 512, 88))
    }

    @Test
    fun `a truncated image is reported rather than thrown`() {
        val source = writeImage("truncated.png", 800, 600)
        val bytes = Files.readAllBytes(source)
        Files.write(source, bytes.copyOf(bytes.size / 2))

        assertFalse(codec.downscaleToJpeg(source, temp.root.toPath().resolve("out.jpg"), 512, 88))
    }

    @Test
    fun `a nonsensical target size is refused rather than producing an empty file`() {
        val source = writeImage("big.png", 800, 600)

        assertFalse(codec.downscaleToJpeg(source, temp.root.toPath().resolve("out.jpg"), maxDim = 0, quality = 88))
    }

    @Test
    fun `a failure leaves no half-written output behind`() {
        val notAnImage = temp.root.toPath().resolve("notes.txt")
        Files.writeString(notAnImage, "still not a picture")
        val target = temp.root.toPath().resolve("out.jpg")

        assertFalse(codec.downscaleToJpeg(notAnImage, target, 512, 88))
        assertFalse("a failed conversion must not leave a file the caller would treat as artwork", Files.exists(target))
    }
}
