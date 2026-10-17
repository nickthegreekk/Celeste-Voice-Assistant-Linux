package com.nikosm.voiceassistant

/**
 * Reply notifications for an unfocused window (PLAN.md §10/M14).
 *
 * The service watches its own state: a manual turn that goes `THINKING` (or `SPEAKING`) → `IDLE`
 * has settled, and if the transcript grew while nobody was looking, the last assistant message is
 * worth a desktop notification. That one transition is the shared settle point of every manual
 * route — text, voice, cloud, tool loops — so no per-route hook can drift; rooms write to their own
 * transcript and spontaneous turns never enter these states, which is why neither notifies here
 * (spontaneous delivery has its own notification).
 *
 * The notification is posted only when **all** of these hold: the feature is on, the window is not
 * focused, silent mode is off, and the transcript actually grew during the turn — a stopped turn
 * leaves the last message as the user's own, and a turn that failed before `THINKING` never
 * reaches the transition. A reply the user is watching is never notified, and regaining focus
 * cancels the standing notification.
 */

/** One id for all reply notifications, so a newer reply replaces the older one. */
internal const val REPLY_NOTIFICATION_ID = 7101

/** How much of a reply fits in a notification body before an ellipsis. */
internal const val REPLY_NOTIFICATION_CHARS = 180

/**
 * The body for [message], or null when it is not a reply worth posting.
 *
 * Whitespace collapses to one line (a notification with newlines renders as a stub in several
 * notification daemons); an image-only reply says so; a user turn or an empty turn is nothing.
 * Errors are replies too — an error arriving while the window is away is exactly what the user
 * needs to see.
 */
internal fun replyNotificationBody(message: ChatMessage): String? {
    if (message.role != "assistant") return null
    val text = message.text.replace(Regex("\\s+"), " ").trim()
    if (text.isNotEmpty()) {
        return if (text.length <= REPLY_NOTIFICATION_CHARS) text else text.take(REPLY_NOTIFICATION_CHARS) + "…"
    }
    return if (message.generatedImagePath != null || message.imagePath != null) "Sent an image." else null
}
