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
import java.awt.MouseInfo
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JWindow
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

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
 * ## The icon is AWT; the menu is Swing, and the split is deliberate
 *
 * `SystemTray`/`TrayIcon` are the only tray API the JDK has, but their `PopupMenu` is an AWT
 * component: AWT draws it itself, it never consults the Swing look and feel, and it therefore kept
 * the default grey while every other surface followed the desktop's theme. Even Compose Desktop's
 * own `Tray` menu is that same `PopupMenu` — measured 2026-10-17 — so it could not have been
 * adopted either. The menu here is a `JPopupMenu`, which [SystemLookAndFeel] themes, shown at the
 * pointer from a one-pixel `JWindow` anchor: a `JPopupMenu` needs an owning `Component` to be shown
 * and a `TrayIcon` is not one. The anchor is hidden again the moment the menu closes.
 *
 * ## Threading
 *
 * Every call is marshalled onto the AWT event thread, because that is where `SystemTray`, its mouse
 * events and every Swing component live, and because the callers are not: the status collector runs
 * on a coroutine dispatcher, and the menu's state is read when a user opens it. `EventQueue.invokeLater`
 * is the whole mechanism, and it also means [setStatus] is safe to call from anywhere — which it has
 * to be, since it is called from a flow collector.
 *
 * ## The icon is drawn, and is still a placeholder
 *
 * A sixteen-pixel disc in the persona colour is enough to be clickable and honest about being a
 * placeholder. The real artwork lives in `packaging/icons/`, which is installed into the desktop's
 * icon theme but is not on the application classpath — using it here needs a decision about where
 * an icon lives, and this class does not guess one.
 */
class AwtTray(
    private val state: () -> TrayMenuState,
    private val onAction: (TrayAction) -> Unit,
    private val logger: AppLogger = StderrLogger,
) : Tray {

    private var icon: TrayIcon? = null
    private var menu: JPopupMenu? = null
    private var anchor: JWindow? = null

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
        var installed = false
        val doInstall = { installed = installOnEventThread() }
        if (EventQueue.isDispatchThread()) doInstall() else EventQueue.invokeAndWait(doInstall)
        return installed
    }

    private fun installOnEventThread(): Boolean {
        val created = try {
            TrayIcon(drawIcon(), "Celeste").also { trayIcon ->
                trayIcon.isImageAutoSize = true
                trayIcon.addMouseListener(object : MouseAdapter() {
                    override fun mouseReleased(e: MouseEvent) {
                        // The desktop decides which button opens a tray menu — a panel may send
                        // button 1 to the icon itself — so both the platform's popup trigger and a
                        // primary click mean "show the menu" here.
                        if (e.isPopupTrigger || e.button == MouseEvent.BUTTON1) toggleMenu()
                    }
                })
                SystemTray.getSystemTray().add(trayIcon)
            }
        } catch (e: AWTException) {
            logger.w("AwtTray", "the system tray refused the icon: ${e.message}")
            return false
        }
        icon = created
        menu = JPopupMenu().apply {
            // The popup is its own window: it is shown from a one-pixel anchor, and a lightweight
            // popup would be clipped to that anchor's bounds.
            isLightWeightPopupEnabled = false
            addPopupMenuListener(object : PopupMenuListener {
                override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) = Unit
                override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) { anchor?.isVisible = false }
                override fun popupMenuCanceled(e: PopupMenuEvent) { anchor?.isVisible = false }
            })
        }
        rebuild()
        logger.i("AwtTray", "tray icon installed")
        return true
    }

    override fun setStatus(text: String) {
        EventQueue.invokeLater {
            icon?.toolTip = text.ifBlank { "Celeste" }
            // The menu's first row *is* the status, so a change has to rebuild the items: they are
            // built before the menu is shown and nothing binds them to the flow.
            rebuild()
        }
    }

    /** Remove the icon. Called on the way out so a stale tray entry does not outlive the process. */
    fun remove() {
        EventQueue.invokeLater {
            menu?.isVisible = false
            anchor?.dispose()
            anchor = null
            menu = null
            icon?.let { SystemTray.getSystemTray().remove(it) }
            icon = null
        }
    }

    /**
     * Show the menu at the pointer, or close it when the icon is clicked again.
     *
     * Always on the event thread: it is called from the icon's mouse listener. The anchor is
     * created lazily because a `JWindow` cannot exist before the toolkit does, and [remove] drops
     * it so an exit leaves no window behind.
     */
    private fun toggleMenu() {
        val menu = menu ?: return
        if (menu.isVisible) {
            menu.isVisible = false
            return
        }
        val pointer = MouseInfo.getPointerInfo()?.location
        if (pointer == null) {
            logger.w("AwtTray", "no pointer location to anchor the tray menu to")
            return
        }
        rebuild()
        val anchor = this.anchor ?: JWindow().also {
            // The anchor must never take focus: it only exists to give the popup an owner.
            it.setFocusableWindowState(false)
            this.anchor = it
        }
        anchor.setLocation(pointer)
        if (!anchor.isVisible) anchor.isVisible = true
        menu.show(anchor, 0, 0)
    }

    private fun rebuild() {
        val menu = menu ?: return
        // Rebuilding a menu that is on screen would empty it under the user's cursor; the next
        // open rebuilds it anyway, so a status change while it is open waits rather than swapping
        // the items out from under the pointer.
        if (menu.isVisible) return
        val current = state()
        menu.removeAll()
        trayMenu(current).forEach { item ->
            if (item.isSeparator) {
                menu.addSeparator()
            } else {
                menu.add(JMenuItem(item.label).apply {
                    isEnabled = item.enabled
                    item.action?.let { action ->
                        addActionListener {
                            menu.isVisible = false
                            onAction(action)
                        }
                    }
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
