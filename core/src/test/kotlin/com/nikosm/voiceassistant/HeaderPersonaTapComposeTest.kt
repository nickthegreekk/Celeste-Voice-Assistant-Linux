package com.nikosm.voiceassistant

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chat header's two taps are different actions: the icon opens the persona EDITOR, the name
 * opens the persona SWITCHER (ported from upstream's instrumented test in M7).
 *
 * `Header` takes all of its state as parameters and collects nothing, so it composes on its own
 * with no service bound. What is asserted is the routing, not the navigation: each target
 * reaches its OWN callback and never the other's.
 */
@OptIn(ExperimentalTestApi::class)
class HeaderPersonaTapComposeTest {

    private val persona = Persona(
        name = PERSONA,
        themeColor = Color(0xFF4ADE80),
        model = "qwen3:8b",
        systemPrompt = "Summarize things."
    )

    private var iconTaps = 0
    private var nameTaps = 0

    private fun ComposeUiTest.render() {
        iconTaps = 0
        nameTaps = 0
        setContent {
            MaterialTheme {
                Box(modifier = Modifier.size(HOST_WIDTH_DP.dp, 200.dp)) {
                    Header(
                        currentPersona = persona,
                        sessionUsage = UsageInfo(),
                        onPersonaClick = { nameTaps++ },
                        onPersonaIconClick = { iconTaps++ },
                        onEarpieceToggle = {},
                        isEarpieceMode = false,
                        onSettingsClick = {},
                        onTranslatorClick = {}
                    )
                }
            }
        }
        waitForIdle()
    }

    @Test
    fun iconTapsTheEditorAndNameTapsTheSwitcher() = runComposeUiTest {
        render()

        onNodeWithContentDescription("Edit $PERSONA").performClick()
        runOnIdle {
            assertEquals("the persona icon must open the EDITOR, exactly once", 1, iconTaps)
            assertEquals("the persona icon must not open the switcher", 0, nameTaps)
        }

        onNodeWithContentDescription("Switch persona").performClick()
        runOnIdle {
            assertEquals("the name must open the SWITCHER, exactly once", 1, nameTaps)
            assertEquals("the name must not open the editor", 1, iconTaps)
        }
    }

    /**
     * The two targets must not overlap: a tap aimed at the icon has to reach the icon, and the
     * name has to remain its own target beside it.
     */
    @Test
    fun bothTargetsAreSeparateAndAtLeastTouchSized() = runComposeUiTest {
        render()

        val icon = onNodeWithContentDescription("Edit $PERSONA")
        val name = onNodeWithContentDescription("Switch persona")

        icon.assertHeightIsAtLeast(MIN_TOUCH_DP.dp)
        // Fetching proves the name target exists as its own node rather than being folded
        // into the icon's.
        val nameBounds = name.fetchSemanticsNode().boundsInRoot
        val iconBounds = icon.fetchSemanticsNode().boundsInRoot

        assertTrue(
            "the icon and the name must not overlap (icon=$iconBounds name=$nameBounds)",
            iconBounds.right <= nameBounds.left
        )
    }

    private companion object {
        const val PERSONA = "Summarizer"
        const val HOST_WIDTH_DP = 360

        /** The accessibility floor for a touch target. */
        const val MIN_TOUCH_DP = 48
    }
}
