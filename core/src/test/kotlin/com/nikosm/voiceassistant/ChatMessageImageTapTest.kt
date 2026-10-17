package com.nikosm.voiceassistant

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.runComposeUiTest
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The thumbnail half of upstream's full-screen-viewer test (M7).
 *
 * **The cross-root half is dropped with a reason, not quietly.** Upstream asserts that the tap
 * produces a node in the viewer's tree because Android's `Dialog` shares the test's semantics
 * forest. This port's viewer is a Compose Desktop `DialogWindow` — a separate OS window — which
 * the desktop UI-test runner cannot see into. What remains testable is the part the upstream test
 * itself calls the prerequisite: both image kinds (the user's attachment and the assistant's
 * generation) are clickable nodes, so neither block can silently lose its tap target. The viewer's
 * own gesture maths is covered by `FullScreenImageViewerBoundsTest`, and its window is judged in a
 * live run.
 */
@OptIn(ExperimentalTestApi::class)
class ChatMessageImageTapTest {

    private lateinit var imageFile: File

    @Before
    fun setUp() {
        imageFile = File.createTempFile("fullscreen_probe", ".png")
        // A real, decodable PNG: Coil must actually succeed for the image node to lay out.
        val image = BufferedImage(64, 48, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.color = java.awt.Color(0x33, 0x66, 0xCC)
        g.fillRect(0, 0, image.width, image.height)
        g.dispose()
        ImageIO.write(image, "png", imageFile)
    }

    @After
    fun tearDown() {
        imageFile.delete()
    }

    @Test
    fun `a generated image is a clickable node`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ChatMessageBubble(
                    message = ChatMessage(
                        role = "assistant",
                        text = "a serene mountain lake",
                        generatedImagePath = imageFile.absolutePath,
                    ),
                    displayText = "a serene mountain lake",
                    personaColor = Color(0xFF4ADE80),
                )
            }
        }

        // The clickable Surface merges the AsyncImage's description, so the description IS the
        // clickable node — the same contract the upstream test asserts before it taps.
        onNodeWithContentDescription("Generated image").assertHasClickAction()
    }

    @Test
    fun `an attached image is a clickable node`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ChatMessageBubble(
                    message = ChatMessage(role = "user", text = "", imagePath = imageFile.absolutePath),
                    displayText = "",
                    personaColor = Color(0xFF4ADE80),
                )
            }
        }

        onNodeWithContentDescription("Attached image").assertHasClickAction()
    }

    @Test
    fun `the probe fixture is a real image, not a placeholder`() {
        // Guards the fixture: a zero-byte file would let the node assertions above pass while
        // proving nothing, because the description is attached before the decode completes.
        assertTrue("the fixture decodes", ImageIO.read(imageFile) != null)
    }
}
