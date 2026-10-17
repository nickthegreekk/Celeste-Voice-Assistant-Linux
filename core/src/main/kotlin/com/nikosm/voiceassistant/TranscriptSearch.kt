package com.nikosm.voiceassistant

/**
 * Transcript search across the main chat, every persona and every room (PLAN.md §10/M13).
 *
 * ## Local, on demand, and over the files the screens already read
 *
 * There is no index and no database: the search decodes the same JSON entries the chat screens
 * load, when the user types, and throws the results away when the dialog closes. The data is small
 * enough — 2000 messages per persona, a few personas — that a substring scan after a short debounce
 * is faster than building and maintaining a second store, and it cannot go stale. Nothing leaves
 * the machine.
 *
 * ## Scope, deliberately narrow
 *
 * Text only (not reasoning), case-insensitive substring matching (no regex, no fuzzy), one hit per
 * matching message, newest first, capped at [TRANSCRIPT_SEARCH_LIMIT]. Opening a hit switches to
 * the persona or room; it does **not** scroll to the message — the chat screen has no jump-to-index
 * affordance yet, and pretending otherwise would land the user at the bottom of a conversation
 * with no sign of the hit.
 */

internal enum class TranscriptKind(val label: String) {
    CHAT("Chat"),
    PERSONA("Persona"),
    ROOM("Room"),
}

/** One matching message, with enough context to recognise it. */
internal data class TranscriptHit(
    val kind: TranscriptKind,
    /** Persona name for [TranscriptKind.PERSONA], room id for [TranscriptKind.ROOM]. */
    val sourceId: String,
    val sourceName: String,
    /** `user`, `assistant`, or a room speaker's persona name. */
    val speaker: String,
    val timestamp: Long,
    val snippet: String,
)

/** How many hits may cross into the UI. Newest first; a cap keeps one common word from flooding it. */
internal const val TRANSCRIPT_SEARCH_LIMIT = 100

/**
 * The snippet for one message, or null when it does not match.
 *
 * Case-insensitive; whitespace runs collapse to one space so a match in a multi-line message reads
 * as one line; [contextChars] on each side, with an ellipsis where the message was cut.
 */
internal fun transcriptSnippet(text: String, query: String, contextChars: Int = 48): String? {
    if (query.isBlank()) return null
    val index = text.indexOf(query, ignoreCase = true)
    if (index < 0) return null
    val start = (index - contextChars).coerceAtLeast(0)
    val end = (index + query.length + contextChars).coerceAtMost(text.length)
    val clean = text.substring(start, end).replace(Regex("\\s+"), " ").trim()
    val prefix = if (start > 0) "…" else ""
    val suffix = if (end < text.length) "…" else ""
    return prefix + clean + suffix
}

// ---------------------------------------------------------------------------- the service seam

internal fun AssistantService.searchTranscripts(
    query: String,
    limit: Int = TRANSCRIPT_SEARCH_LIMIT,
): List<TranscriptHit> = settingsManager.searchTranscripts(query, limit)

/**
 * Open the transcript a hit belongs to. A persona reached from inside a room leaves the room first
 * — the room header would otherwise stay up over the persona chat the user asked for.
 */
internal fun AssistantService.openTranscriptHit(hit: TranscriptHit) {
    when (hit.kind) {
        TranscriptKind.CHAT -> Unit // the main chat is where the dialog was opened from
        TranscriptKind.PERSONA -> {
            if (_activeRoomId.value != null) leaveRoom()
            personas.value.firstOrNull { it.name == hit.sourceId }?.let { switchPersona(it) }
        }
        TranscriptKind.ROOM -> (settingsManager.getRooms() ?: emptyList())
            .firstOrNull { it.id == hit.sourceId }
            ?.let { switchRoom(it) }
    }
}
