package com.nikosm.voiceassistant.linux

import com.nikosm.voiceassistant.AppLogger
import com.nikosm.voiceassistant.FileDialogs
import com.nikosm.voiceassistant.StderrLogger
import java.awt.EventQueue
import java.io.File
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Seam 13, implemented with `javax.swing.JFileChooser` (PLAN.md §5).
 *
 * ## Why Swing, and not the `java.awt.FileDialog` this used to be
 *
 * AWT's X11 file dialog is drawn by AWT's own peer. It never consults the Swing look and feel, so
 * on a dark desktop every picker was the one light-grey surface left in the application. A Swing
 * chooser is drawn by the look and feel, and [SystemLookAndFeel] installs the desktop's GTK theme —
 * dark variant included — so the pickers now match the rest of the session. The user asked for
 * exactly this on 2026-10-17, after the first day of real use.
 *
 * The directory mode already needed a `JFileChooser` (AWT's dialog has no third mode), so the
 * switch also leaves one chooser for all five calls instead of two dialog types with different
 * behaviour.
 *
 * ## Blocking, on purpose, and why that needs `invokeAndWait`
 *
 * A chooser shows itself and does not return until the user answers — `showOpenDialog` blocks the
 * calling thread. That is the opposite of the SAF contract this replaces, and it is also the
 * contract the port wants: a click handler that wants a path can simply ask for one.
 *
 * The one hazard is *which* thread blocks. Swing requires its dialogs to be shown on the Event
 * Dispatch Thread, and Compose Desktop runs its composition on the EDT already — so a click handler
 * is normally on the right thread and `invokeAndWait` is a no-op. It is called anyway because a
 * composition can also be driven from a `LaunchedEffect` on another dispatcher, and the two
 * failure modes there are both bad: AWT throws `IllegalStateException` if a dialog is shown off the
 * EDT, and calling it directly from a non-EDT thread deadlocks against the toolkit lock rather than
 * failing cleanly. `invokeAndWait` turns both into "it works".
 *
 * ## The filter is a hint, and the caller still validates
 *
 * The chooser filters its listing and still offers "All Files", and a user can type a name the
 * filter would hide. So this class does not promise more than a listing hint: every caller
 * validates what it got, which it would have to do anyway, because a file the user picked can
 * always be unreadable.
 *
 * ## `parent = null`
 *
 * The dialog takes an owner `Component`. `null` is legal and Swing uses a shared hidden frame,
 * which is what is wanted here: `:platform` has no handle on the application window and should
 * not — the window is `:desktop`'s, and threading a `Frame` through the seam would put a desktop
 * type in the interface for the sake of a modality hint.
 */
class AwtFileDialogs(
    private val logger: AppLogger = StderrLogger,
) : FileDialogs {

    override fun openImage(title: String): Path? = choose(title, IMAGE_EXTENSIONS).firstOrNull()

    override fun openTextFile(title: String): Path? = choose(title, TEXT_EXTENSIONS).firstOrNull()

    override fun openFiles(title: String): List<Path> = choose(title, multiple = true)

    override fun save(defaultName: String, title: String): Path? =
        choose(title, save = true, defaultName = defaultName).firstOrNull()

    /**
     * The directory mode AWT's `FileDialog` could not express. It disables the "All Files" filter
     * on purpose: a directory picker has nothing to filter, and leaving it on would let the user
     * select a file and have this return one.
     */
    override fun openDirectory(title: String): Path? = choose(title, directoryOnly = true).firstOrNull()

    private fun choose(
        title: String,
        extensions: Set<String>? = null,
        save: Boolean = false,
        multiple: Boolean = false,
        defaultName: String? = null,
        directoryOnly: Boolean = false,
    ): List<Path> {
        var chosen: List<Path> = emptyList()
        val show = {
            val chooser = JFileChooser().apply {
                dialogTitle = title
                // A chooser opens at the process's working directory by default, which for a
                // development run is a checkout and for an installed package is wherever the
                // launcher happened to be. Home is the directory a person means.
                currentDirectory = File(System.getProperty("user.home") ?: ".")
                isMultiSelectionEnabled = multiple
                fileSelectionMode =
                    if (directoryOnly) JFileChooser.DIRECTORIES_ONLY else JFileChooser.FILES_ONLY
                if (directoryOnly) isAcceptAllFileFilterUsed = false
                if (extensions != null) {
                    val suffixes = extensions.map { it.removePrefix(".") }.sorted()
                    fileFilter = FileNameExtensionFilter(
                        suffixes.joinToString(", ") { it.uppercase() },
                        *suffixes.toTypedArray(),
                    )
                }
                if (save && defaultName != null) selectedFile = File(currentDirectory, defaultName)
            }
            val answer = if (save) chooser.showSaveDialog(null) else chooser.showOpenDialog(null)
            if (answer == JFileChooser.APPROVE_OPTION) {
                chosen = if (multiple) {
                    chooser.selectedFiles.map { it.toPath() }
                } else {
                    listOfNotNull(chooser.selectedFile?.toPath())
                }
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
