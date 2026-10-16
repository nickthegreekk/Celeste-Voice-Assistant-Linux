package com.nikosm.voiceassistant

import java.nio.file.Path


// PARTIALLY PORTED IN M1. What is here is the half the composer uses: the semantics tags its
// widgets carry, the cap on inserted text, and `appendSharedTextToDraft` — all pure, none of them
// naming an Android type.
//
// The share target's *receiving* half is here too, in the shape this platform has for it rather
// than upstream's. `ACTION_SEND` and its `Uri` payload are Android's mechanism, and `parseSharedIntent`
// reads an intent's action/type/subject, none of which a command line has; PLAN.md §7 and §8
// substitute `--send-text` / `--send-file` instead. The M1 comments at the two call sites said §2 put
// desktop share out of scope — §2 does not say that, and §8 lists the share target as M3 work, so
// they were corrected on 2026-10-16 along with this note.
//
// The tag constants stay because the composer's own widgets carry them and the UI tests read them;
// they are not share-specific despite the file's name.

/** Cap on the text one share may insert. Longer payloads are trimmed and the user is told. */
internal const val MAX_SHARED_TEXT_CHARS = 30_000

/**
 * Semantics tags on the chat composer — the share landing point. Read by
 * `ShareTargetInstrumentedTest`, so the share's effect is asserted on the real UI rather than
 * inferred from the intent.
 */

/** The draft field the shared text lands in. */
internal const val COMPOSER_DRAFT_TAG = "composerDraftField"

/** The pending image attachment's thumbnail; present only while one is attached. */
internal const val COMPOSER_ATTACHMENT_TAG = "composerImageAttachment"

/** The pending attachment's remove ("×") button. */
internal const val COMPOSER_ATTACHMENT_REMOVE_TAG = "composerImageAttachmentRemove"

/** The composer's single trailing button: sends while there is content, else mic/stop. */
internal const val COMPOSER_ACTION_TAG = "composerActionButton"

/** What an accepted `ACTION_SEND` payload is. */
internal data class SharedDraftAppend(val draft: String, val truncated: Boolean)

/**
 * Append [shared] to the current draft, separated by a newline, capping the INSERTED text at
 * [MAX_SHARED_TEXT_CHARS]. Never sends: the whole point is that the user adds text and sends.
 */
internal fun appendSharedTextToDraft(draft: String, shared: String): SharedDraftAppend {
    val capped = shared.take(MAX_SHARED_TEXT_CHARS)
    val next = if (draft.isEmpty()) capped else "$draft\n$capped"
    return SharedDraftAppend(next, capped.length < shared.length)
}

/**
 * The one-shot handoff from the command line to the composer.
 *
 * Upstream's `SharedRequest` carried a `Uri`, because an `ACTION_SEND` payload arrives as one and
 * its read grant is temporary — which is why the screen had to materialize an image before the grant
 * went away. `celeste-linux --send-file <path>` carries a path this process can read for as long as
 * it likes, so the type carries a [Path] and there is nothing to copy first.
 *
 * Public, where most of this file is `internal`, for the same reason `WavFile` is: `:desktop` is a
 * different module and parses the flags, and Kotlin shares `internal` only within one module.
 *
 * One payload, never several: upstream's rule ("single item only ... never `ACTION_SEND_MULTIPLE`")
 * is kept, because two shared items are two turns nobody composed.
 */
sealed interface SharedRequest {
    data class Text(val text: String) : SharedRequest
    data class Image(val path: Path) : SharedRequest
}

/** What a `.desktop` entry can hand over: the extensions the composer's attachment renders. */
private val SHAREABLE_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")

/**
 * Parse `--send-text <text>` and `--send-file <path>` out of the command line.
 *
 * Null means "nothing was shared", and it covers the cases a caller cannot tell apart from outside:
 * no flag, a flag with no value, an empty value, and — this one is a rule rather than a formality —
 * a `--send-file` whose extension is not an image. The composer's attachment is rendered and sent as
 * an image, so a PDF handed to it would be a broken thumbnail and a refused turn; upstream refused a
 * non-image MIME type at exactly this point, and a command line's substitute for a MIME type is the
 * extension.
 *
 * The first flag wins, and an unknown flag is ignored rather than fatal: this is what a `.desktop`
 * entry or a script calls, and a malformed invocation should start the application rather than
 * refuse to.
 *
 * Public for `:desktop`; see [SharedRequest].
 */
fun parseShareArgs(args: List<String>): SharedRequest? {
    for ((index, arg) in args.withIndex()) {
        val value = args.getOrNull(index + 1)?.takeIf { it.isNotBlank() } ?: continue
        when (arg) {
            "--send-text" -> return SharedRequest.Text(value)
            "--send-file" -> {
                val path = runCatching { Path.of(value) }.getOrNull() ?: continue
                val extension = path.fileName?.toString()?.substringAfterLast('.', "")?.lowercase()
                if (extension in SHAREABLE_IMAGE_EXTENSIONS) return SharedRequest.Image(path)
            }
        }
    }
    return null
}

