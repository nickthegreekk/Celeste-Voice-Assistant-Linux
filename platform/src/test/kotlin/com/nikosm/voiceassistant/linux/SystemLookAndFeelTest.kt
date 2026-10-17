package com.nikosm.voiceassistant.linux

import java.awt.GraphicsEnvironment
import javax.swing.UIManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /**
     * **The defect this test exists for, measured live on 2026-10-17.** The installed application
     * logged `Swing look and feel: …GTKLookAndFeel` at startup, and then its file chooser rendered
     * as Metal. Skiko — Compose Desktop's renderer — calls
     * `UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())` while the application
     * starts, and on Linux that is Metal; a one-shot install before the window existed was undone
     * before the first dialog was created. [SystemLookAndFeel.install] is therefore re-asserted at
     * the point of use, and this drives the same reset the renderer performs.
     *
     * It needs a display: the class is a documented no-op headless, and a machine without GTK has
     * nothing to repair. Both cases skip rather than fail — the alternative is a test that passes
     * for the wrong reason on a server and fails on a developer's box.
     */
    @Test
    fun `a look and feel reset is repaired at the next point of use`() {
        if (GraphicsEnvironment.isHeadless()) return
        if (SystemLookAndFeel.install() != SystemLookAndFeel.GTK_LOOK_AND_FEEL) return

        // Exactly what Skiko does while Compose starts (Actuals_awtKt.setSystemLookAndFeel).
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
        assertTrue(
            "the reset should have moved the process off GTK, or this test proves nothing",
            UIManager.getLookAndFeel()?.javaClass?.name != SystemLookAndFeel.GTK_LOOK_AND_FEEL,
        )

        SystemLookAndFeel.install()

        assertEquals(
            SystemLookAndFeel.GTK_LOOK_AND_FEEL,
            UIManager.getLookAndFeel()?.javaClass?.name,
        )
    }
}
