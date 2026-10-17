package com.nikosm.voiceassistant

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import org.junit.Test

/**
 * The smoke test for M7's desktop UI test runner.
 *
 * Deliberately trivial: it proves the Compose test runtime composes and finds a node on this
 * classpath. The screen tests below it are the reason the dependency exists; this one exists so a
 * broken test runtime reads as "the runner is broken", not as six screen tests failing for reasons
 * that look like screen defects.
 */
@OptIn(ExperimentalTestApi::class)
class ComposeUiSmokeTest {

    @Test
    fun `a composable composes and its text is found`() = runComposeUiTest {
        setContent { Text("hello from the test runner") }

        onNodeWithText("hello from the test runner").assertIsDisplayed()
    }
}
