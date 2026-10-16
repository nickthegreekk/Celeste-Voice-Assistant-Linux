package com.nikosm.voiceassistant.linux

import com.nikosm.voiceassistant.AppLogger
import com.nikosm.voiceassistant.StderrLogger
import com.nikosm.voiceassistant.Tray
import com.nikosm.voiceassistant.TrayAction
import com.nikosm.voiceassistant.TrayMenuState
import com.nikosm.voiceassistant.trayMenu
import java.awt.AWTException
import java.awt.Color
import java.awt.EventQueue
import java.awt.Graphics2D
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage

/**
 * Seam 11, implemented with AWT's `SystemTray` — which is what §5's row asks for and the only tray
 * API the JDK has.
 *
 * ## Why this is a `:platform` class rather than `:desktop`'s
 *
 * The menu's *actions* belong to the composition root and arrive as [onAction], but the tray itself
 * is a platform surface, and the rule that decides where a file lives is the same one that put
 * `AwtFileDialogs` here: an AWT type is a platform API, not an application one. `:desktop` chooses
 * the implementation and wires it; it does not contain it.
 *
 * ## Threading
 *
 * Every call is marshalled onto the AWT event thread, because that is where `SystemTray` and its
 * `PopupMenu` live and because the callers are not: the status collector runs on a coroutine
 * dispatcher, and the menu's state is read when a user opens it. `EventQueue.invokeLater` is the
 * whole mechanism, and it also means [setStatus] is safe to call from anywhere — which it has to be,
 * since it is called from a flow collector.
 *
 * ## The icon is drawn, and that is temporary on purpose
 *
 * There is no icon file in the repository yet: `assets/` holds the VAD model and the licence, and
 * packaging — where icons belong — is M6. A sixteen-pixel disc in the persona colour is enough to be
 * clickable and honest about being a placeholder; M6 replaces this function with the real asset
 * rather than growing a second source of truth for what the application looks like.
 */
class AwtTray(
    private val state: () -> TrayMenuState,
    private val onAction: (TrayAction) -> Unit,
    private val logger: AppLogger = StderrLogger,
) : Tray {

    private var icon: TrayIcon? = null
    private var popup: PopupMenu? = null

    /**
     * Install the icon, or report why there is none.
     *
     * Returns false when the platform has no tray — a bare session, a Wayland compositor without an
     * appindicator host (§12's risk), a headless box — and the caller decides what to do about it.
     * The three cases need the same sentence rather than three: the application keeps running, and
     * what the user loses is the icon, not the assistant.
     */
    fun install(): Boolean {
        if (!SystemTray.isSupported()) {
            logger.w("AwtTray", "no system tray on this desktop — the icon is absent, the application is not")
            return false
        }
        val menu = PopupMenu()
        val created = try {
            TrayIcon(drawIcon(), "Celeste", menu).also {
                it.isImageAutoSize = true
                SystemTray.getSystemTray().add(it)
            }
        } catch (e: AWTException) {
            logger.w("AwtTray", "the system tray refused the icon: ${e.message}")
            return false
        }
        icon = created
        popup = menu
        rebuild()
        logger.i("AwtTray", "tray icon installed")
        return true
    }

    override fun setStatus(text: String) {
        EventQueue.invokeLater {
            icon?.toolTip = text.ifBlank { "Celeste" }
            // The menu's first row *is* the status, so a change has to rebuild it: AWT menus are
            // built once and read when opened, and there is no binding to change that.
            rebuild()
        }
    }

    /** Remove the icon. Called on the way out so a stale tray entry does not outlive the process. */
    fun remove() {
        EventQueue.invokeLater {
            icon?.let { SystemTray.getSystemTray().remove(it) }
            icon = null
            popup = null
        }
    }

    private fun rebuild() {
        val menu = popup ?: return
        val current = state()
        menu.removeAll()
        trayMenu(current).forEach { item ->
            if (item.isSeparator) {
                menu.addSeparator()
            } else {
                menu.add(MenuItem(item.label).apply {
                    isEnabled = item.enabled
                    item.action?.let { action -> addActionListener { onAction(action) } }
                })
            }
        }
        icon?.toolTip = current.status.ifBlank { "Celeste" }
    }

    private companion object {
        /**
         * A sixteen-pixel disc — the size a tray icon is drawn at on every desktop this port targets,
         * and a placeholder until M6 brings the real asset (see the class comment).
         */
        fun drawIcon(): BufferedImage {
            val size = 16
            val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val g: Graphics2D = image.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = Color(0x7C, 0x5C, 0xE0)
            g.fillOval(1, 1, size - 2, size - 2)
            g.color = Color.WHITE
            g.fillOval(5, 5, size - 10, size - 10)
            g.dispose()
            return image
        }
    }
}
