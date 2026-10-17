package com.nikosm.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tray's menu, which is the half of seam 11 that can be pinned on the JVM.
 *
 * The other half is AWT: whether a desktop has a tray at all, and whether the icon appears. That is
 * the same split `AwtFileDialogs` has — the platform call is thin and the decision is not — and it
 * matters more here than usual, because the tray is the *only* way to reach a `--headless` run:
 * a menu item that is missing, disabled or mislabelled in that mode leaves a process the user cannot
 * stop without a terminal.
 */
class TrayMenuTest {

    private fun state(
        status: String = "Ready to help",
        listening: Boolean = false,
        muted: Boolean = false,
        windowAvailable: Boolean = true,
    ) = TrayMenuState(status, listening, muted, windowAvailable)

    private fun actions(items: List<TrayMenuItem>) = items.mapNotNull { it.action }

    @Test
    fun `the five actions are there, in the order a person reads them`() {
        assertEquals(
            listOf(TrayAction.LISTEN, TrayAction.MUTE, TrayAction.OPEN, TrayAction.SETTINGS, TrayAction.QUIT),
            actions(trayMenu(state())),
        )
    }

    @Test
    fun `the status line is the first row, and it does nothing when clicked`() {
        val first = trayMenu(state(status = "Thinking…")).first()

        assertEquals("Thinking…", first.label)
        assertNull("the status row is not an action", first.action)
        assertFalse("and it is not clickable", first.enabled)
    }

    @Test
    fun `an empty status says the application's name rather than showing a blank row`() {
        // Before the first state transition the flow is empty, and a menu whose first row is "" reads
        // as a rendering bug.
        assertEquals("Celeste", trayMenu(state(status = "")).first().label)
    }

    @Test
    fun `the toggles name the action they will take, not the state they are in`() {
        // A menu that says "Listen now" while it is listening lies about the one thing it is for.
        fun toggleLabels(listening: Boolean, muted: Boolean) = trayMenu(state(listening = listening, muted = muted))
            .filter { it.action == TrayAction.LISTEN || it.action == TrayAction.MUTE }
            .map { it.label }

        assertEquals(listOf("Listen now", "Mute microphone"), toggleLabels(listening = false, muted = false))
        assertEquals(listOf("Stop listening", "Unmute microphone"), toggleLabels(listening = true, muted = true))
    }

    @Test
    fun `there is no Open item where there is no window`() {
        // `--headless` has nothing to open, and an entry that opened nothing would be worse than its
        // absence — the rule the message menu's Share entry was held to while M3 was pending. The
        // Settings window follows the same rule.
        assertFalse(trayMenu(state(windowAvailable = false)).any { it.action == TrayAction.OPEN })
        assertTrue(trayMenu(state(windowAvailable = true)).any { it.action == TrayAction.OPEN })
        assertFalse(trayMenu(state(windowAvailable = false)).any { it.action == TrayAction.SETTINGS })
        assertTrue(trayMenu(state(windowAvailable = true)).any { it.action == TrayAction.SETTINGS })
    }

    @Test
    fun `Quit is always there and always enabled`() {
        // The one item that must survive every state: in headless mode it is the only way to stop the
        // process without a terminal, so a disabled Quit is a trap rather than a caution.
        listOf(state(), state(listening = true), state(muted = true), state(windowAvailable = false)).forEach {
            val quit = trayMenu(it).single { item -> item.action == TrayAction.QUIT }
            assertEquals("Quit", quit.label)
            assertTrue(quit.enabled)
        }
    }

    @Test
    fun `rows that do nothing are separators, in the places that group the menu`() {
        val items = trayMenu(state())
        val firstAction = items.indexOfFirst { it.action != null }
        val quit = items.indexOfFirst { it.action == TrayAction.QUIT }

        assertNull("the status row leads, and acts on nothing", items.first().action)
        assertTrue(
            "a separator groups the status away from the actions",
            items.take(firstAction).any { it.isSeparator },
        )
        assertTrue(
            "a separator keeps Quit apart from the actions above it",
            items.subList(firstAction, quit).any { it.isSeparator },
        )
    }
}
