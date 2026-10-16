package com.nikosm.voiceassistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * "Save to gallery", which on this platform is a folder and a copy.
 *
 * Upstream inserted the picture into `MediaStore.Images` through a `ContentResolver` — a content
 * provider with no counterpart here — behind three `Build.VERSION.SDK_INT` guards for API levels
 * this platform does not have. What survives is the part the user sees: the same
 * `Pictures/VoiceAssistant/` folder (upstream's own `RELATIVE_PATH`, and the folder the user asked
 * for) and the same file-name shape (`Gen_<millis>.png`), written with `Files.copy` instead of a
 * resolver.
 *
 * Deliberately **not** a save dialog, which PLAN.md §7 offered as the alternative through seam 13:
 * the button's whole promise is "put it with my pictures", and a folder named by the application is
 * what makes the pictures findable later. A dialog would ask a question the user has already
 * answered by configuration.
 *
 * Nothing here ever deletes: the folder is the user's, and this application writes into it exactly
 * when asked to.
 *
 * @return where the file landed, or null when it could not be written — the source missing, the
 * folder uncreatable, the disk full. Callers report the null; nothing is thrown at a button press.
 */
internal fun saveImageToPictures(
    source: Path,
    picturesDir: Path,
    nowMillis: Long = System.currentTimeMillis(),
): Path? {
    if (!Files.isRegularFile(source)) return null
    return runCatching {
        Files.createDirectories(picturesDir)
        // Upstream's name, with a suffix so a second save inside the same millisecond cannot
        // clobber the first. Collisions are otherwise impossible: nothing else writes here.
        var target = picturesDir.resolve("Gen_$nowMillis.png")
        var suffix = 1
        while (Files.exists(target)) {
            target = picturesDir.resolve("Gen_${nowMillis}_$suffix.png")
            suffix++
        }
        Files.copy(source, target)
        target
    }.getOrNull()
}

/**
 * Saves the generated image of the message at [messageIndex], then says where it went.
 *
 * The copy runs on `Dispatchers.IO` because this is reached from a button, and the report comes back
 * on Main because it is a state write for the snackbar. Both the log and the snackbar carry the
 * destination: a save whose result the user cannot find is not a save.
 */
internal fun AssistantService.saveGeneratedImage(messageIndex: Int) {
    val path = _messages.value.getOrNull(messageIndex)?.generatedImagePath
    val source = path?.let { runCatching { Path.of(it) }.getOrNull() }
    if (source == null) {
        notifyUser("Nothing to save — that message has no image")
        return
    }
    serviceScope.launch(Dispatchers.IO) {
        val saved = saveImageToPictures(source, appEnv.picturesDir)
        if (saved != null) {
            logger.i("AssistantService", "image saved to $saved")
        } else {
            logger.e("AssistantService", "could not save $source to ${appEnv.picturesDir}")
        }
        withContext(Dispatchers.Main) {
            notifyUser(if (saved != null) "Saved to $saved" else "Could not save the image — see the log")
        }
    }
}
