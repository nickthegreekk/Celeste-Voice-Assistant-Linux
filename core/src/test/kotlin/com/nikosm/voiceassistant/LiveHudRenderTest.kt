package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.nikosm.voiceassistant.ui.theme.VoiceAssistantTheme
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A smoke test that the Live HUD actually paints.
 *
 * The body owns a fixed-rate loop that never ends, so the usual `waitForIdle` never settles.
 * Driving the test clock by hand instead — `autoAdvance = false` and one explicit advance —
 * renders a real frame without waiting for an animation that never ends. The assertion is
 * deliberately not an image comparison: a canvas this layered would make every palette tweak a
 * fixture rewrite. What it catches is the failure that matters here — a composition that throws,
 * paints nothing, or paints a single flat colour because a draw call was lost.
 */
@OptIn(ExperimentalTestApi::class)
class LiveHudRenderTest {

    @Test
    fun `the live hud paints a non-uniform surface`() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            VoiceAssistantTheme(darkTheme = true) {
                LiveHudBody(
                    state = AssistantState.SPEAKING,
                    personaName = "Render Probe",
                    personaColor = Color.Cyan,
                    usage = UsageInfo(totalTokens = 1_240),
                    messages = listOf(
                        ChatMessage("user", "A fabricated question."),
                        ChatMessage(
                            "assistant",
                            "A fabricated reply, long enough to exercise the transcript line wrap " +
                                "and the karaoke highlight in the render.",
                        ),
                    ),
                    voiceDuration = 3_000,
                    ttsPlaybackFraction = 0.4f,
                    ttsWordTimestamps = null,
                    micAmplitude = 0f,
                    speechAmplitude = 0.55f,
                    muted = false,
                    silenced = false,
                    handsFree = true,
                    isFullscreen = true,
                    onMicClick = {},
                    onStopClick = {},
                    onMuteToggle = {},
                    onSilenceToggle = {},
                    onHandsFreeToggle = {},
                    onToggleFullscreen = {},
                    onClose = {},
                )
            }
        }
        mainClock.advanceTimeBy(300)

        val image = onNodeWithTag("live_hud").captureToImage()
        assertTrue("the HUD captured no surface", image.width > 300 && image.height > 300)

        val bitmap = image.asSkiaBitmap()
        val colours = mutableSetOf<Int>()
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                colours += bitmap.getColor(x, y)
                x += 24
            }
            y += 24
        }
        assertTrue(
            "the HUD painted a flat surface (${colours.size} colours across ${bitmap.width}x${bitmap.height})",
            colours.size > 8,
        )
    }
}
