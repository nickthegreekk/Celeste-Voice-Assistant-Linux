package com.nikosm.voiceassistant

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two export entry points, routed without a service (2026-10-20).
 *
 * `Header` and `RoomHeader` take all their state as parameters, so the only thing worth asserting
 * here is reachability: the main header's menu exposes "Export chat…", the room header exposes its
 * export action, and each calls its own callback exactly once. Whether the write produces the right
 * document is [ConversationExportTest]'s subject; whether the save dialog returns a path is the
 * fake seam's.
 */
@OptIn(ExperimentalTestApi::class)
class ConversationExportUiTest {

    private val persona = Persona(
        name = PERSONA,
        themeColor = Color(0xFF4ADE80),
        model = "qwen3:8b",
        systemPrompt = "Export probe persona.",
    )

    private fun ComposeUiTest.renderHeader(onExport: () -> Unit) {
        setContent {
            MaterialTheme {
                Box(modifier = Modifier.size(360.dp, 200.dp)) {
                    Header(
                        currentPersona = persona,
                        sessionUsage = UsageInfo(),
                        onPersonaClick = {},
                        onPersonaIconClick = {},
                        onEarpieceToggle = {},
                        isEarpieceMode = false,
                        onSettingsClick = {},
                        onTranslatorClick = {},
                        onExportClick = onExport,
                    )
                }
            }
        }
        waitForIdle()
    }

    @Test
    fun `the header menu reaches Export chat`() = runComposeUiTest {
        var exports = 0
        renderHeader { exports++ }

        onNodeWithContentDescription("Menu").performClick()
        onNodeWithText("Export chat…").performClick()

        runOnIdle { assertEquals("Export chat… must call its callback exactly once", 1, exports) }
    }

    @Test
    fun `the room header exposes an export action`() = runComposeUiTest {
        var exports = 0
        setContent {
            MaterialTheme {
                Box(modifier = Modifier.size(360.dp, 200.dp)) {
                    RoomHeader(
                        room = Room(name = "Book club"),
                        onRoomClick = {},
                        onLeaveClick = {},
                        onSettingsClick = {},
                        onExportClick = { exports++ },
                    )
                }
            }
        }
        waitForIdle()

        onNodeWithContentDescription("Export room chat").performClick()

        runOnIdle { assertEquals("the room export action must call its callback once", 1, exports) }
    }

    private companion object {
        const val PERSONA = "Celeste"
    }
}
