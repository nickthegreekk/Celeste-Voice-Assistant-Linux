package com.nikosm.voiceassistant.linux

import com.nikosm.voiceassistant.AppLogger
import com.nikosm.voiceassistant.StderrLogger
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import javax.swing.UIManager

/**
 * Puts the application's Swing surfaces on the desktop's own GTK theme.
 *
 * ## Why this is a separate step rather than a line in each dialog
 *
 * A Swing component takes its colours, fonts and painter from the look and feel that is installed
 * **when the component is created**. There is no per-component opt-in, and a look and feel set after
 * a component exists does not reach it — so this has to run once, before the first file picker or
 * tray menu, which is why `Main` calls it before it builds anything. The surfaces that need it are
 * seam 13's pickers and [AwtTray]'s menu; the Compose window is drawn by Skia and is not affected
 * either way.
 *
 * ## GTK first, then whatever the JDK calls the system's
 *
 * `UIManager.getSystemLookAndFeelClassName()` returns Metal on this JDK even on a GTK desktop —
 * measured 2026-10-17 on JDK 27 — so asking it first would type every dialog in grey. The GTK look
 * and feel is therefore tried first and the JDK's answer is the fallback. It follows the desktop's
 * GTK theme, including its dark variant: on the user's dark Xfce theme the chooser renders with a
 * `#383c4a` panel and light text, which is the whole point of this class.
 *
 * A machine with no GTK libraries at all is not a failure — the fallback class name is tried, and
 * if neither installs, the JDK default stays and the application still opens its dialogs, in the
 * wrong colours. That is worth one warning, never a failed launch.
 *
 * ## It is not a one-shot, because Compose resets it
 *
 * **Measured 2026-10-17, from the installed package:** the file chooser rendered as Metal although
 * the startup log said GTK had been installed. The cause is Skiko, Compose Desktop's renderer:
 * `org.jetbrains.skiko.Actuals_awtKt.setSystemLookAndFeel()` calls
 * `UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())` while the application
 * starts, which on Linux replaces GTK with Metal. Installing at the window root is therefore too
 * early — the components created afterwards see Metal.
 *
 * So [install] is idempotent and cheap: when the process already runs GTK it returns without doing
 * anything, and the two surfaces that care — [AwtFileDialogs] and [AwtTray] — call it immediately
 * before they create their Swing components. The startup call stays for the paths where Skiko
 * never runs (the headless tray), and for the log line that names the chosen theme.
 */
object SystemLookAndFeel {

    /**
     * The class the JDK ships for GTK. Kept as a name rather than a `LookAndFeel` reference so the
     * linker never needs the class on a runtime that omits it; installation is attempted, not
     * assumed, which is also what makes the fallback meaningful.
     */
    const val GTK_LOOK_AND_FEEL = "com.sun.java.swing.plaf.gtk.GTKLookAndFeel"

    /** Set when both candidates were refused; see [install]'s cheap path. */
    @Volatile
    private var gaveUp = false

    /**
     * Install the best available look and feel and return its class name, or null when the
     * process has no display or nothing installed.
     *
     * Safe to call once from the main thread: Swing's install is marshalled onto the event thread
     * because creating a look and feel touches toolkit state, and `invokeAndWait` is a no-op when
     * the caller is already there.
     */
    fun install(logger: AppLogger = StderrLogger): String? {
        if (GraphicsEnvironment.isHeadless()) {
            // `--headless` on a box with no display, or a test worker. No Swing surface will ever
            // exist, so there is nothing to theme and no reason to start a toolkit.
            logger.d(TAG, "headless: no Swing surfaces to theme")
            return null
        }
        // The cheap path, and the reason a caller may invoke this at every point of use: if GTK is
        // already in force nothing is reinstalled. The check is against the *current* look and
        // feel, not a remembered one, because Skiko resets the process look and feel during
        // startup (see the class comment).
        if (UIManager.getLookAndFeel()?.javaClass?.name == GTK_LOOK_AND_FEEL) return GTK_LOOK_AND_FEEL
        // A JDK that refused GTK once will refuse it every time; retrying per dialog would only
        // repeat the warning.
        if (gaveUp) return null
        return try {
            if (EventQueue.isDispatchThread()) {
                installOnEventThread(logger)
            } else {
                var result: String? = null
                EventQueue.invokeAndWait { result = installOnEventThread(logger) }
                result
            }
        } catch (e: Exception) {
            // A toolkit that cannot start must not take the application with it: the dialogs and
            // the tray have their own failure handling, and the wrong colours beat no window.
            logger.w(TAG, "could not install a look and feel: ${e.message}")
            null
        }
    }

    private fun installOnEventThread(logger: AppLogger): String? {
        val chosen = firstInstallable(lookAndFeelCandidates(UIManager.getSystemLookAndFeelClassName())) { name ->
            try {
                UIManager.setLookAndFeel(name)
                true
            } catch (e: Exception) {
                logger.w(TAG, "look and feel $name is not available: ${e.message}")
                false
            } catch (e: LinkageError) {
                // A JDK without the GTK native half fails with an *error*, not an exception, when
                // the class initialises. It is exactly the case this fallback exists for.
                logger.w(TAG, "look and feel $name is not available: ${e.message}")
                false
            }
        }
        if (chosen == null) {
            gaveUp = true
            logger.w(TAG, "no look and feel installed; Swing surfaces keep the JDK default")
        } else {
            logger.i(TAG, "Swing look and feel: $chosen")
        }
        return chosen
    }

}

private const val TAG = "SystemLookAndFeel"

/**
 * The order to attempt: GTK, then the JDK's own answer — deduplicated, so a JDK that reports GTK
 * as the system class does not try it twice.
 */
internal fun lookAndFeelCandidates(systemClassName: String): List<String> =
    listOf(SystemLookAndFeel.GTK_LOOK_AND_FEEL, systemClassName).distinct()

/** The first candidate the JDK accepts, or null when every one of them refuses. */
internal fun firstInstallable(candidates: List<String>, install: (String) -> Boolean): String? =
    candidates.firstOrNull(install)
