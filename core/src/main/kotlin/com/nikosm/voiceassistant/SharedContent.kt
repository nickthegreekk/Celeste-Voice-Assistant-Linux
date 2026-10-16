package com.nikosm.voiceassistant


// PARTIALLY PORTED IN M1. What is here is the half the composer uses: the semantics tags its
// widgets carry, the cap on inserted text, and `appendSharedTextToDraft` — all pure, none of them
// naming an Android type.
//
// What is NOT here is the share target itself: `SharedIntentContent`, `parseSharedIntent` and
// `SharedRequest`, which take an `ACTION_SEND` intent and a `Uri` and are therefore Android's
// incoming-share mechanism. PLAN.md §2 leaves desktop share and drag-and-drop integration out of
// v1, so it is not deferred to a milestone — there is nothing here for a `.desktop` entry or a
// command-line flag to receive. Its call sites in `MainActivity` and `MainVoiceScreen` carry
// markers saying the same thing, so the cut is counted where it is felt.
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
