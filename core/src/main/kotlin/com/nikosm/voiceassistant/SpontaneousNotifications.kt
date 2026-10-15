package com.nikosm.voiceassistant

/**
 * Spontaneous delivery — the notification surface (step 6, plan §4).
 *
 * This file is the ONLY place that posts persona-initiated notifications, and those are
 * deliberately kept away from the foreground service's status UI. Two of upstream's three rules
 * survive the port; the third is an Android concept that does not.
 *
 * 1. **Delivery notifications are separate from the service's status surface.** Upstream separates
 *    them by channel and notification id; here there is no status surface yet — the tray arrives
 *    in M4 (PLAN.md §9) — so the rule reduces to "delivery posts, and nothing else does". It stays
 *    written down because M4 is exactly when it will start to matter.
 *
 * 2. **No touch on the service's notification helpers.** Delivery posts through [Notifier]
 *    directly, never via `updateNotification`, and never while holding audio. A background message
 *    must not hijack the status surface while the user is recording or listening — the same rule
 *    the turn itself follows in `AssistantServiceSpontaneous.kt`.
 *
 * 3. *(Dropped.)* Upstream's "different channel AND different notification id than the FGS status
 *    notification" is Android's grouping model. `notify-send` has no channel and no group, so
 *    there is nothing to keep the two apart — and nothing to keep apart until M4 has a tray. What
 *    survives is the *id* discipline, which is [spontaneousNotificationId] below and is the part
 *    that was doing the work anyway: one card per persona, updated rather than stacked.
 *
 * ## Ported in stages
 *
 * Only what M1 has a caller for is here. `postSpontaneousNotification` is **not yet ported** — its
 * only caller is the spontaneous delivery worker, which is M4's — and neither are upstream's
 * channel creation and group summary, which have no desktop counterpart at all. When M4 brings the
 * worker it will find the seam, the id rule and the preview helper already in place, and posting
 * becomes a call to [Notifier.post] rather than a new surface to design.
 */

/** Deep-link extra naming the persona whose chat the tap must open. Read by the window. */
internal const val EXTRA_TARGET_PERSONA_NAME = "spontaneousTargetPersona"

/**
 * Notification IDs, and why they are what they are.
 *
 * Delivery IDs derive from the persona name so that (a) messages from one persona UPDATE its
 * notification instead of stacking, and (b) N enabled personas produce N notifications, one per
 * conversational thread. The base keeps the whole namespace clear of a status notification's id by
 * three orders of magnitude; a hash collision between two persona names would merely merge their
 * cards, which is a rendering artefact, never data loss.
 *
 * Upstream also reserves an id for a group *summary* card, which the port does not have: grouping
 * is Android's, and a desktop notification is a card on its own.
 */
internal const val SPONTANEOUS_NOTIFICATION_ID_BASE = 1_000

/** Stable per-persona delivery id. Pure, so it is pinned on the JVM rather than on-device. */
internal fun spontaneousNotificationId(personaName: String): Int =
    SPONTANEOUS_NOTIFICATION_ID_BASE + (personaName.hashCode() and 0x7FFF)

/**
 * Truncate [text] to its first line, bounded so the collapsed card reads as one thought.
 * Newlines and runs of whitespace inside the preview are flattened.
 *
 * Upstream notes that the full message is always available via the expanded `BigTextStyle`. The
 * desktop equivalent is the notification body a daemon chooses to show — which this cannot control
 * — so the preview here is the whole of what is sent on a collapsed card, and the bound is what
 * stops one long message from filling a screen. Kept at upstream's default of 120 characters so a
 * card reads the same on both platforms.
 */
internal fun spontaneousNotificationPreview(text: String, maxChars: Int = 120): String =
    text.lineSequence().firstOrNull().orEmpty().replace(Regex("\\s+"), " ").trim()
        .let { if (it.length <= maxChars) it else it.take(maxChars - 1).trimEnd() + "…" }

/**
 * Remove a persona's delivery notification. Best-effort companion to disarm paths — a cancelled
 * chain must not leave a stale card that opens a chat which then never hears again.
 *
 * The first parameter is [AppEnv] because this is byte-identical-caller territory: upstream passes
 * `applicationContext`, and `AssistantServiceSpontaneous.kt` — one of the six COPY files, which
 * this port may not edit — passes exactly that name. Under D5 the service's `applicationContext`
 * *is* the `AppEnv`, so the arity and types match the call site rather than the call site being
 * bent to suit the port.
 *
 * Upstream follows the cancel with a summary-card check, cancelling the group summary when no
 * persona cards remain. There is no summary to cancel here — see the file header — so the single
 * call is the whole of it. Never throws: it is reached from a disarming path that has to complete,
 * and a desktop that cannot close a card is not a reason to leave a chain armed.
 */
internal fun cancelSpontaneousNotification(appEnv: AppEnv, personaName: String) {
    try {
        appEnv.notifier.cancel(spontaneousNotificationId(personaName))
    } catch (e: Exception) {
        appEnv.logger.w("Spontaneous", "Notification cancel failed: ${e.message}")
    }
}
