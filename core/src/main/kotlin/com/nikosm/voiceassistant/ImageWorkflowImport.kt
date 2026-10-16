package com.nikosm.voiceassistant

import java.nio.file.Files
import java.nio.file.Path

// ---------------------------------------------------------------------------
// Reading an imported ComfyUI workflow file.
//
// The places a user brings a workflow INTO the app: the Image Gen entry's dialog
// (SettingsScreens.kt) and the persona editor's Image Character picker. Deliberately NOT in
// `ComfyWorkflow.kt`: that file is pure, so its validation and token filling stay unit-testable,
// and file reading has no place there.
//
// **Ported whole, as the shared leaf it mostly is** (PLAN.md §5's shared-block rule). Both
// functions are `Path`-based here rather than `Context`/`Uri`-based, and the substitution removed
// more than it changed: a `content://` URI needed a resolver because the bytes might belong to
// another application under a revocable grant, and the display name needed a `OpenableColumns`
// query because a URI has no name — a path from a file dialog is a real file with a real name, so
// the query and its "some providers answer neither" fallback are gone rather than ported.
// ---------------------------------------------------------------------------

/**
 * Reads a picked workflow as UTF-8 text.
 *
 * Capped at [COMFY_MAX_WORKFLOW_CHARS] bytes plus one — the count is in BYTES while the validation
 * cap is in characters, and for a JSON workflow the byte count is at least the character count, so
 * the read always stops early enough for the "too large" message rather than for a silently
 * truncated — and therefore invalid — template. Returns null only when the file cannot be read.
 *
 * The loop and its cap are upstream's, and the cap has to be enforced *during* the read rather
 * than by checking the file's length first: the file is the user's, and a workflow that is
 * enormous is exactly the case this exists for.
 */
internal fun readComfyWorkflowText(path: Path): String? = try {
    Files.newInputStream(path).use { stream ->
        val limit = COMFY_MAX_WORKFLOW_CHARS
        val buffer = ByteArray(64 * 1024)
        val collected = java.io.ByteArrayOutputStream()
        var total = 0
        while (total <= limit) {
            val read = stream.read(buffer)
            if (read <= 0) break
            collected.write(buffer, 0, read)
            total += read
        }
        String(collected.toByteArray(), Charsets.UTF_8)
    }
} catch (_: Exception) {
    null
}

/**
 * A display name for a picked workflow: the file's own name without its extension, or "" when the
 * path names no file at all.
 *
 * Labels an imported Image Character in the library. Upstream this was a `OpenableColumns`
 * query with a `Uri.lastPathSegment` fallback, because a provider could answer neither and the
 * caller then substitutes its own default rather than showing a blank row. A `Path` always has a
 * name, so the query is gone; the empty-string contract is kept, because the caller's default is
 * still what shows on a row that would otherwise be blank.
 */
internal fun imageWorkflowDisplayName(path: Path): String =
    path.fileName?.toString().orEmpty().substringBeforeLast('.')
