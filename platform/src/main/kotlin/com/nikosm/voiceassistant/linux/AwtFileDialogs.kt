package com.nikosm.voiceassistant.linux

import com.nikosm.voiceassistant.AppLogger
import com.nikosm.voiceassistant.FileDialogs
import com.nikosm.voiceassistant.StderrLogger
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path

/**
 * Seam 13, implemented with `java.awt.FileDialog` (PLAN.md §5).
 *
 * ## Blocking, on purpose, and why that needs `invokeAndWait`
 *
 * An AWT `FileDialog` shows itself and does not return until the user answers — `isVisible = true`
 * blocks the calling thread. That is the opposite of the SAF contract this replaces, and it is
 * also the contract the port wants: a click handler that wants a path can simply ask for one.
 *
 * The one hazard is *which* thread blocks. AWT requires the dialog to be shown on the
 * Event Dispatch Thread, and Compose Desktop runs its composition on the EDT already — so a click
 * handler is normally on the right thread and `invokeAndWait` is a no-op. It is called anyway
 * because a composition can also be driven from a `LaunchedEffect` on another dispatcher, and the
 * two failure modes there are both bad: AWT throws `IllegalStateException` if a dialog is shown
 * off the EDT, and calling it directly from a non-EDT thread deadlocks against the toolkit lock
 * rather than failing cleanly. `invokeAndWait` turns both into "it works".
 *
 * ## The filter is a hint, and on Linux it is sometimes only that
 *
 * `setFilenameFilter` is honoured by AWT's own X11 dialog. Some distributions wire `FileDialog` to
 * a portal-backed chooser through `sun.awt.X11` or a toolkit-specific peer, and there the filter
 * is advisory — the user can type any name. So this class filters what it *can* and does not
 * promise more: every caller validates what it got, which it would have to do anyway, because a
 * file the user picked can always be unreadable.
 *
 * ## `parent = null`
 *
 * A `FileDialog` takes an owner `Frame`. `null` is legal and AWT uses a shared hidden frame, which
 * is what is wanted here: `:platform` has no handle on the application window and should not — the
 * window is `:desktop`'s, and threading a `Frame` through the seam would put a desktop type in the
 * interface for the sake of a modality hint.
 */
class AwtFileDialogs(
    private val logger: AppLogger = StderrLogger,
) : FileDialogs {

    override fun openImage(title: String): Path? = show(FileDialog.LOAD, title, IMAGE_EXTENSIONS).firstOrNull()

    override fun openTextFile(title: String): Path? = show(FileDialog.LOAD, title, TEXT_EXTENSIONS).firstOrNull()

    override fun openFiles(title: String): List<Path> =
        show(FileDialog.LOAD, title, null, multiple = true)

    override fun save(defaultName: String, title: String): Path? =
        show(FileDialog.SAVE, title, null, defaultName = defaultName).firstOrNull()

    private fun show(
        mode: Int,
        title: String,
        extensions: Set<String>?,
        multiple: Boolean = false,
        defaultName: String? = null,
    ): List<Path> {
        var chosen: List<Path> = emptyList()
        val show = {
            val dialog = FileDialog(null as Frame?, title, mode)
            try {
                if (multiple) dialog.isMultipleMode = true
                if (defaultName != null) dialog.file = defaultName
                if (extensions != null) {
                    dialog.filenameFilter = java.io.FilenameFilter { _: java.io.File, name: String ->
                        extensions.any { name.lowercase().endsWith(it) }
                    }
                }
                dialog.isVisible = true
                // `directory` is null only when the dialog never opened; `files` is empty when it
                // opened and the user pressed Cancel, which is the ordinary "changed my mind" path.
                val dir = dialog.directory
                if (dir != null) {
                    chosen = if (multiple) {
                        // `files` is `Array<File>` (the multi-select accessor), while `file`
                        // below is a bare name to join onto `directory` — an AWT asymmetry, not
                        // a mistake here.
                        dialog.files.map { it.toPath() }
                    } else {
                        dialog.file?.let { listOf(Path.of(dir, it)) } ?: emptyList()
                    }
                }
            } finally {
                // AWT dialogs are heavyweight peers and are not collected on their own; without
                // this every pick leaks a window's worth of native resources.
                dialog.dispose()
            }
        }
        if (EventQueue.isDispatchThread()) show() else EventQueue.invokeAndWait(show)
        if (chosen.isEmpty()) logger.d(TAG, "dialog dismissed: $title")
        return chosen
    }

    private companion object {
        const val TAG = "AwtFileDialogs"

        /**
         * What [com.nikosm.voiceassistant.ImageCodec] can actually decode, and what the persona
         * artwork and generated images are — offering a format the next step cannot read would be
         * a filter that helps nobody.
         */
        val IMAGE_EXTENSIONS = setOf(".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp")

        /**
         * Plain text and the two structured files the application imports: a ComfyUI workflow
         * (`.json`) and a backup (`.json`). Kept deliberately wide — a user who names a workflow
         * `.txt` should not be stopped by a filter, and the caller validates the *content*.
         */
        val TEXT_EXTENSIONS = setOf(".json", ".txt", ".yaml", ".yml", ".toml", ".md")
    }
}
