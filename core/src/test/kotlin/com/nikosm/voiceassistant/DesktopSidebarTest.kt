package com.nikosm.voiceassistant

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * M17: the main window's sidebar, composed on its own.
 *
 * The width breakpoint lives at the call site in `MainScreen`; what is testable here is the
 * component's contract — every persona and room is listed, a row reports exactly the object it
 * names, and the Rooms section does not exist when there are no rooms. The active highlight is
 * colour, not semantics, so the live run is where it is judged.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopSidebarTest {

    private fun persona(name: String) = Persona(
        name = name,
        themeColor = Color(0xFF4ADE80),
        model = "probe-model",
        systemPrompt = "You are $name.",
    )

    private fun room(name: String, members: List<String>) = Room(
        id = "room-$name",
        name = name,
        memberNames = members,
    )

    @Test
    fun `personas and rooms are listed, and a persona row reports its persona`() = runComposeUiTest {
        val alice = persona("Alice")
        val bob = persona("Bob")
        val den = room("The Den", listOf("Alice", "Bob"))
        var selectedPersona: Persona? = null
        var selectedRoom: Room? = null

        setContent {
            MaterialTheme {
                DesktopSidebar(
                    personas = listOf(alice, bob),
                    currentPersonaName = "Alice",
                    rooms = listOf(den),
                    activeRoomId = null,
                    accentColor = Color(0xFF4ADE80),
                    onPersonaSelected = { selectedPersona = it },
                    onRoomSelected = { selectedRoom = it },
                )
            }
        }

        onNodeWithText("Alice").assertExists()
        onNodeWithText("Bob").assertExists()
        onNodeWithText("The Den").assertExists()
        onNodeWithText("2 members").assertExists()

        onNodeWithText("Bob").performClick()
        assertEquals("Bob", selectedPersona?.name)

        onNodeWithText("The Den").performClick()
        assertEquals("The Den", selectedRoom?.name)
    }

    @Test
    fun `an empty room list renders no Rooms section`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                DesktopSidebar(
                    personas = listOf(persona("Alice")),
                    currentPersonaName = "Alice",
                    rooms = emptyList(),
                    activeRoomId = null,
                    accentColor = Color(0xFF4ADE80),
                    onPersonaSelected = {},
                    onRoomSelected = {},
                )
            }
        }

        onNodeWithText("Personas").assertExists()
        onNodeWithText("Rooms").assertDoesNotExist()
    }
}
