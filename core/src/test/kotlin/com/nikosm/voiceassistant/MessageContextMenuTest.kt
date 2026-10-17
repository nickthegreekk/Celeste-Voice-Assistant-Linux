package com.nikosm.voiceassistant

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M17: the message actions menu on the desktop.
 *
 * The menu existed upstream behind a long-press, which is the touch gesture for "secondary
 * action". A mouse does not long-press; it has a second button. So the bubble's text lost its
 * `combinedClickable` (it made the message a button and swallowed selection drags) and gained a
 * secondary-button handler, while the menu itself is unchanged.
 *
 * **Scope: the gesture, not the menu's rendering.** `DropdownMenu` on the desktop is a popup
 * window, and `runComposeUiTest` cannot see into a second window — the same boundary recorded by
 * `ChatMessageImageTapTest` for `DialogWindow`. What is testable here is what was new: the
 * secondary button reaches the callback, a primary press (selection) does not, and a bubble with
 * no callback does not react at all. The menu's items are judged in the live run.
 */
@OptIn(ExperimentalTestApi::class)
class MessageContextMenuTest {

    @androidx.compose.runtime.Composable
    private fun Bubble(
        onLongClick: (() -> Unit)?,
        text: String = "probe message",
    ) {
        ChatMessageBubble(
            message = ChatMessage(role = "assistant", text = text),
            displayText = text,
            personaColor = Color(0xFF4ADE80),
            onLongClick = onLongClick,
        )
    }

    @Test
    fun `a secondary press invokes the bubble's action callback`() = runComposeUiTest {
        var fired = false
        setContent { MaterialTheme { Bubble(onLongClick = { fired = true }) } }

        onNodeWithText("probe message").performMouseInput {
            press(MouseButton.Secondary)
            release(MouseButton.Secondary)
        }
        waitForIdle()

        assertTrue("the secondary press should reach the bubble's action callback", fired)
    }

    @Test
    fun `a primary press selects and does not open the menu`() = runComposeUiTest {
        var fired = false
        setContent { MaterialTheme { Bubble(onLongClick = { fired = true }) } }

        onNodeWithText("probe message").performMouseInput {
            press(MouseButton.Primary)
            release(MouseButton.Primary)
        }
        waitForIdle()

        assertFalse("a left click is selection, not the actions menu", fired)
    }

    @Test
    fun `a compact bubble with no callback ignores the secondary button`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ChatMessageBubble(
                    message = ChatMessage(role = "assistant", text = "hud line"),
                    displayText = "hud line",
                    personaColor = Color(0xFF4ADE80),
                    isCompact = true,
                )
            }
        }

        // No crash and nothing to open: the compact HUD body has no actions menu upstream.
        onNodeWithText("hud line").performMouseInput {
            press(MouseButton.Secondary)
            release(MouseButton.Secondary)
        }
    }
}
