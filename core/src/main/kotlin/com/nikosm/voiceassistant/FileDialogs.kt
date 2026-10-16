package com.nikosm.voiceassistant

import java.nio.file.Path

/**
 * Seam 13 (PLAN.md §5): choosing files.
 *
 * Android reached these through the Storage Access Framework — `ActivityResultContracts`, a
 * `rememberLauncherForActivityResult` per picker, and a `Uri` back through a callback that
 * survives configuration changes. Every call site is a `LaunchedEffect`-free click handler that
 * asked the system a question and got an answer later. The desktop equivalent is
 * `java.awt.FileDialog`: a call that **blocks until the user answers and returns the answer**,
 * which is why this interface is synchronous and why every method's return type is nullable
 * rather than a callback registration.
 *
 * That difference is the seam's whole shape. It is not that the desktop dialog is simpler; it is
 * that there is no callback to thread through a composition, so the ported call sites lose their
 * launcher and their `onResult` lambda and gain a `val path = … ?: return`.
 *
 * ## Cancelling is not an error
 *
 * Every method returns null or an empty list when the user dismisses the dialog, and the callers
 * treat that as "the user changed their mind" rather than as a failure — which is what upstream's
 * `if (uri == null) return@launch` meant at each of these sites. Nothing here throws for a
 * cancelled pick; an implementation may throw for a dialog that cannot be shown at all, and the
 * caller's `runCatching` is where that lands.
 *
 * ## Why the titles are parameters
 *
 * Upstream's dialogs had no titles — the system picker drew its own, and what it was picking was
 * clear from the contract. An AWT `FileDialog` is a plain window and shows whatever it is told, so
 * the caller supplies the sentence. It is the caller's text because the same seam serves six
 * different questions.
 */
interface FileDialogs {

    /**
     * Pick one image file to read. Null when the user cancels.
     *
     * Filtered to the extensions [ImageCodec] can decode, which is the honest filter: offering a
     * `.webp` this build cannot read would be offering a file that will fail on the next step.
     */
    fun openImage(title: String): Path?

    /**
     * Pick one text file to read — an imported ComfyUI workflow, an exported backup.
     *
     * Null when the user cancels.
     */
    fun openTextFile(title: String): Path?

    /**
     * Pick zero or more files to attach to a message. Empty when the user cancels.
     *
     * More than one, unlike the two above, because `MessageInput`'s attachment strip holds a list
     * and upstream's `ActivityResultContracts.OpenMultipleDocuments` was the picker behind it.
     */
    fun openFiles(title: String): List<Path>

    /**
     * Choose where to write a new file called [defaultName]. Null when the user cancels.
     *
     * The default name matters: an export that defaulted to an empty field would make the user
     * invent one, and upstream's share-to-file and backup-export paths both had a name in mind
     * (`celeste-backup-<date>.json`). The returned path is the user's choice and may be
     * anywhere — [FileDialogs] does not decide, and nothing in `:core` assumes a directory.
     */
    fun save(defaultName: String, title: String): Path?
}
