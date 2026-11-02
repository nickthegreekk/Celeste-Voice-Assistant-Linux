package com.nikosm.voiceassistant

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the SkSL compiles on this Skia and paints a cloud, rather than trusting the HUD's
 * fallback to hide a broken shader forever.
 *
 * The render is raster (no window, no GPU context), so the test runs on a headless machine. The
 * assertions are structural — a hot centre, a transparent corner, and the persona hue reaching the
 * body — not an image fixture, because the shader is meant to be tuned.
 */
class NebulaShaderTest {

    private fun render(theme: Long): BufferedImage {
        val width = 160
        val height = 160
        val surface = Surface.makeRasterN32Premul(width, height)
        val renderer = NebulaRenderer()
        val palette = liveHudPalette(androidx.compose.ui.graphics.Color(theme))
        try {
            assertTrue("the nebula SkSL did not compile", renderer.available)
            val drawn = renderer.draw(
                canvas = surface.canvas,
                width = width.toFloat(),
                height = height.toFloat(),
                timeSeconds = 1.25f,
                level = 0.55f,
                energy = liveHudEnergy(AssistantState.SPEAKING),
                primary = rgb(palette.primary),
                secondary = rgb(palette.secondary),
                hot = rgb(palette.hot),
                deep = rgb(palette.deep),
            )
            assertTrue("the nebula shader rejected the draw", drawn)

            val image = surface.makeImageSnapshot()
            val data = requireNotNull(image.encodeToData(EncodedImageFormat.PNG))
            try {
                return ImageIO.read(ByteArrayInputStream(data.bytes))
            } finally {
                image.close()
                data.close()
            }
        } finally {
            renderer.close()
            surface.close()
        }
    }

    private fun rgb(colour: androidx.compose.ui.graphics.Color): FloatArray =
        floatArrayOf(colour.red, colour.green, colour.blue)

    private fun brightness(argb: Int): Int =
        ((argb shr 16) and 0xFF) + ((argb shr 8) and 0xFF) + (argb and 0xFF)

    private fun average(image: BufferedImage, cx: Int, cy: Int, radius: Int = 2): IntArray {
        var r = 0
        var g = 0
        var b = 0
        var n = 0
        for (y in (cy - radius)..(cy + radius)) {
            for (x in (cx - radius)..(cx + radius)) {
                val p = image.getRGB(x, y)
                r += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
                n++
            }
        }
        return intArrayOf(r / n, g / n, b / n)
    }

    @Test
    fun `the cloud has a hot centre and a dark corner`() {
        val image = render(0xFF00FFFF)
        val centre = brightness(image.getRGB(image.width / 2, image.height / 2))
        val corner = brightness(image.getRGB(4, 4))
        assertTrue("the centre was not luminous ($centre)", centre > 500)
        assertTrue("the corner was not empty ($corner vs $centre)", corner < centre / 3)
    }

    @Test
    fun `the persona hue reaches the body of the cloud`() {
        val cyan = average(render(0xFF00FFFF), 128, 80)
        val red = average(render(0xFFFF0000), 128, 80)
        assertTrue("the cyan persona lost its blue body: ${cyan.toList()}", cyan[2] > cyan[0])
        assertTrue("the red persona lost its red body: ${red.toList()}", red[0] > red[2])
    }
}
