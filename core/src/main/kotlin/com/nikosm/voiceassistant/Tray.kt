package com.nikosm.voiceassistant

/**
 * Seam 11 (PLAN.md §5): the tray, which is this platform's answer to the Android quick-settings
 * tile — §9 drops the tile and names its equivalent rather than a resurrection.
 *
 * ## What the seam is, and what it deliberately is not
 *
 * The interface is one method, because the *other* direction travels the other way: the menu's
 * actions are callbacks the composition root supplies when it builds the tray, since only that root
 * knows the service, the window and how to leave (`:core` cannot call `exitApplication`, and a tray
 * that could would be a second lifecycle). What the application sends *to* the tray is the status
 * line — the string `updateNotification` has been writing since M1 with nowhere to put it.
 *
 * So the tray is not a state machine the service owns; it is a surface that reads state and calls
 * back. That is also what keeps it testable: [trayMenu] is a pure function of [TrayMenuState], so
 * which items exist, what they say and whether they are enabled are pinned on the JVM rather than
 * eyeballed in a system tray.
 *
 * ## Why the labels move with the state
 *
 * §5's row sketches the menu as "Listen now, Mute mic, Open, Quit", and the sketch is the *shape*:
 * four actions. A desktop menu that says "Listen now" while it is listening is a menu that lies
 * about the one thing it is for, so the two toggles name the action they will take — "Stop
 * listening" and "Unmute microphone" when that is what a click does. Upstream's tile was a toggle
 * for the same reason, and its two states had two labels.
 */
interface Tray {

    /**
     * The status line, as the platform wants to show it — a tooltip here, a title elsewhere.
     *
     * Called with every value [AssistantService.updateNotification] publishes, so a tray shows
     * "Listening…" and "Thinking…" as they happen without the service knowing a tray exists.
     */
    fun setStatus(text: String)
}

/**
 * What one click in the tray asks for.
 *
 * Public, with its three neighbours below, for the same reason `WavFile` and `SharedRequest` are:
 * `AwtTray` lives in `:platform`, and Kotlin shares `internal` only within one module. The seam
 * *interface* would be useless without the vocabulary its implementation renders.
 */
enum class TrayAction { LISTEN, MUTE, OPEN, QUIT }

/**
 * One row of the menu.
 *
 * [action] is null for a row that does nothing when clicked — the status line at the top, and
 * separators, which are expressed as an empty label rather than as a platform type so that
 * [trayMenu] stays pure.
 */
data class TrayMenuItem(val action: TrayAction?, val label: String, val enabled: Boolean = true) {
    val isSeparator: Boolean get() = action == null && label.isEmpty()
}

/**
 * Everything the menu depends on.
 *
 * [status] is blank before the service's first transition, and the menu says the application's name
 * instead of an empty row. [windowAvailable] is false under `--headless`, where there is no window
 * to open and therefore no Open item: an entry that opened nothing would be worse than its absence,
 * the same rule the message menu's Share entry was held to while it waited for M3.
 */
data class TrayMenuState(
    val status: String = "",
    val listening: Boolean = false,
    val muted: Boolean = false,
    val windowAvailable: Boolean = true,
)

/**
 * The menu, in the order a person reads it: what is happening, then what can be done about it,
 * then leaving.
 *
 * Pure, and pinned by `TrayMenuTest`. The toggles state their *next* action rather than their
 * current state — see the KDoc on [Tray] — and Quit is never disabled: the tray is the only way out
 * of a headless run, and a menu item that cannot be clicked in the one mode that has nothing else is
 * a trap.
 */
fun trayMenu(state: TrayMenuState): List<TrayMenuItem> = buildList {
    add(TrayMenuItem(action = null, label = state.status.ifBlank { "Celeste" }, enabled = false))
    add(TrayMenuItem(action = null, label = ""))
    add(TrayMenuItem(TrayAction.LISTEN, if (state.listening) "Stop listening" else "Listen now"))
    add(TrayMenuItem(TrayAction.MUTE, if (state.muted) "Unmute microphone" else "Mute microphone"))
    if (state.windowAvailable) add(TrayMenuItem(TrayAction.OPEN, "Open Celeste"))
    add(TrayMenuItem(action = null, label = ""))
    add(TrayMenuItem(TrayAction.QUIT, "Quit"))
}
