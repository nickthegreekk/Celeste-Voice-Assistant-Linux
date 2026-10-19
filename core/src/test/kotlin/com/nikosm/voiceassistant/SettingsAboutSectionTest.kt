package com.nikosm.voiceassistant

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.junit.Test

/**
 * M17 follow-up, 2026-10-17: the About section at the very bottom of Settings.
 *
 * Two properties, because "at the bottom" is as much of the request as the content: in a short
 * window the section is below the fold (a lazy list has not composed it), and scrolling to the
 * end reveals the same facts the About window shows. That the two readers use
 * [APP_VERSION]/[upstreamPin] is what keeps them from disagreeing.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsAboutSectionTest {

    private fun ComposeUiTest.render() {
        setContent {
            MaterialTheme {
                Box(Modifier.size(360.dp, 420.dp)) {
                    SettingsCategoryList(
                        accentColor = Color(0xFF4ADE80),
                        summaries = settingsCategorySummaries(0, 0, 0, 0, 0, 0, 0, 0),
                        onCategorySelected = {}
                    )
                }
            }
        }
    }

    @Test
    fun `the About section sits below the last category and carries the build's facts`() =
        runComposeUiTest {
            render()

            // "Very bottom" is a position in the list, not just a presence: the short host ends
            // well before the last category, so the section has not been composed at all yet.
            onNodeWithText("About").assertDoesNotExist()

            // Scroll each fact into view in turn: the section is taller than what remains of the
            // viewport once it is reached (more so since System added categories on 2026-10-18).
            fun assertInList(text: String) {
                onNode(hasScrollAction()).performScrollToNode(hasText(text))
                onNodeWithText(text).assertIsDisplayed()
            }
            assertInList("About")
            assertInList("Version $APP_VERSION")
            assertInList("Upstream commit ${upstreamPin()}")
            assertInList("GPL-3.0-only · no telemetry, no automatic update check")
            assertInList(PROJECT_URL)
            assertInList("Check for updates")
        }
}
