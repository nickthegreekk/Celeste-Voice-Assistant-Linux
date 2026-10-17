package com.nikosm.voiceassistant.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The choice of look and feel, without a display.
 *
 * [SystemLookAndFeel.install] itself starts an AWT toolkit and installs process-wide state, so what
 * is tested here is the decision it makes: **GTK before the JDK's own answer**, because
 * `UIManager.getSystemLookAndFeelClassName()` returns Metal on a GTK desktop (measured on JDK 27,
 * 2026-10-17), and a fallback chain that survives the GTK native half being absent.
 */
class SystemLookAndFeelTest {

    @Test
    fun `GTK is preferred to whatever the JDK calls the system look and feel`() {
        val candidates = lookAndFeelCandidates("javax.swing.plaf.metal.MetalLookAndFeel")

        assertEquals(
            listOf(SystemLookAndFeel.GTK_LOOK_AND_FEEL, "javax.swing.plaf.metal.MetalLookAndFeel"),
            candidates,
        )
    }

    @Test
    fun `a JDK that reports GTK as its system look and feel does not try it twice`() {
        val candidates = lookAndFeelCandidates(SystemLookAndFeel.GTK_LOOK_AND_FEEL)

        assertEquals(listOf(SystemLookAndFeel.GTK_LOOK_AND_FEEL), candidates)
    }

    @Test
    fun `the first candidate that installs wins and later ones are not attempted`() {
        val attempted = mutableListOf<String>()

        val chosen = firstInstallable(listOf("gtk", "system")) { name ->
            attempted += name
            name == "system"
        }

        assertEquals("system", chosen)
        assertEquals(listOf("gtk", "system"), attempted)
    }

    @Test
    fun `nothing is chosen when every candidate is unavailable`() {
        val chosen = firstInstallable(listOf("gtk", "system")) { false }

        assertNull(chosen)
    }
}
