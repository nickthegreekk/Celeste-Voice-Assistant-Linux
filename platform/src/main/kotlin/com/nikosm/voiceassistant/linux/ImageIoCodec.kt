package com.nikosm.voiceassistant.linux

import com.nikosm.voiceassistant.ImageCodec
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageReadParam
import javax.imageio.ImageReader
import javax.imageio.ImageWriteParam
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The power-of-two subsample to decode at, chosen so **both** edges stay at or above [maxDim].
 *
 * Extracted and `internal` rather than left inline for one reason: the choice it makes is not
 * visible in the output. A version that checked only the long edge would produce the same
 * dimensions — the exact rescale afterwards lands on the same size either way — and differ only in
 * quality, which no assertion can see. Upstream's rule is deliberate (a 4000x300 panorama must not
 * be subsampled until its short edge is 75px, because that is a different photograph, not a
 * smaller one), so it is pinned here directly instead of being inferred from a resize.
 */
internal fun subsampleFor(width: Int, height: Int, maxDim: Int): Int {
    var sample = 1
    while (width / (sample * 2) >= maxDim && height / (sample * 2) >= maxDim) sample *= 2
    return sample
}

/**
 * Seam 15 on Linux: `javax.imageio`, which ships with the JDK, so this costs no dependency.
 *
 * The three steps are the phone's three steps, in the same order and for the same reasons — read
 * the header, subsample, then rescale exactly if the subsample was not enough. See [ImageCodec]
 * for why each one matters.
 */
class ImageIoCodec : ImageCodec {

    override fun downscaleToJpeg(source: Path, target: Path, maxDim: Int, quality: Int): Boolean {
        if (maxDim < 1) return false

        // Step 1: dimensions only. `ImageReader.getWidth/getHeight` reads the header without
        // decoding pixels, which is what keeps a 12 MP photo from becoming a 48 MB buffer.
        val (width, height) = readDimensions(source) ?: return false
        if (width <= 0 || height <= 0) return false

        val sample = subsampleFor(width, height, maxDim)

        val decoded = decodeSubsampled(source, sample) ?: return false

        // Step 2: subsampling only does powers of two, so it can still overshoot — a 3000px source
        // sampled by 2 is 1500px. Rescale exactly, preserving the aspect ratio. Never upscale: that
        // adds bytes and detail that were never there.
        val longest = max(decoded.width, decoded.height)
        val output = if (longest <= maxDim) decoded else rescaleExactly(decoded, maxDim, longest)

        // Step 3: encode.
        return try {
            target.parent?.let { Files.createDirectories(it) }
            writeJpeg(output, target, quality)
            true
        } catch (e: IOException) {
            false
        }
    }

    private fun readDimensions(source: Path): Pair<Int, Int>? = withReader(source) { reader ->
        reader.getWidth(0) to reader.getHeight(0)
    }

    private fun decodeSubsampled(source: Path, sample: Int): BufferedImage? = withReader(source) { reader ->
        val param: ImageReadParam = reader.defaultReadParam
        if (sample > 1) param.setSourceSubsampling(sample, sample, 0, 0)
        reader.read(0, param)
    }

    /**
     * Opens [source] with the first reader that claims it, hands that reader to [body], and always
     * disposes it. Every failure — no file, no reader for the format, a truncated image — comes
     * back as `null`, because the caller's answer to all of them is the same.
     */
    private fun <T> withReader(source: Path, body: (ImageReader) -> T): T? {
        if (!Files.isReadable(source)) return null
        return try {
            ImageIO.createImageInputStream(source.toFile())?.use { input ->
                val readers = ImageIO.getImageReaders(input)
                if (!readers.hasNext()) return null
                val reader = readers.next()
                try {
                    reader.input = input
                    body(reader)
                } finally {
                    reader.dispose()
                }
            }
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            // ImageIO throws unchecked exceptions for malformed data in some formats. A file the
            // user picked is not a reason to fail a persona save.
            null
        }
    }

    private fun rescaleExactly(source: BufferedImage, maxDim: Int, longest: Int): BufferedImage {
        val scale = maxDim.toDouble() / longest
        val width = max(1, (source.width * scale).roundToInt())
        val height = max(1, (source.height * scale).roundToInt())
        return draw(source, width, height)
    }

    /** Renders [source] into a new image of the given size, bilinear — the JVM's `filter = true`. */
    private fun draw(source: BufferedImage, width: Int, height: Int): BufferedImage {
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(source, 0, 0, width, height, null)
        } finally {
            g.dispose()
        }
        return out
    }

    private fun writeJpeg(image: BufferedImage, target: Path, quality: Int) {
        // JPEG carries no alpha, so a source with transparency has to be composited onto something
        // first or the transparent regions come out black. The phone's encoder dropped alpha too
        // and effectively produced black; white is chosen here instead, deliberately, because
        // these images are persona artwork drawn onto the application's own light background and
        // black corners read as a broken file rather than as transparency. Recorded as a
        // deviation: it is the one place this seam is not byte-for-byte the phone's behaviour.
        val opaque = if (image.type == BufferedImage.TYPE_INT_RGB) image else flattenOntoWhite(image)

        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        try {
            val param = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                // Android's Bitmap.compress takes 0-100; ImageIO takes 0.0-1.0.
                compressionQuality = (quality.coerceIn(0, 100)) / 100f
            }
            ImageIO.createImageOutputStream(target.toFile())?.use { out ->
                writer.output = out
                writer.write(null, IIOImage(opaque, null, null), param)
            }
        } finally {
            writer.dispose()
        }
    }

    private fun flattenOntoWhite(source: BufferedImage): BufferedImage {
        val out = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.color = Color.WHITE
            g.fillRect(0, 0, out.width, out.height)
            g.drawImage(source, 0, 0, null)
        } finally {
            g.dispose()
        }
        return out
    }
}
