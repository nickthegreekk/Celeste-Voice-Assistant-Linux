package com.nikosm.voiceassistant

/**
 * Seam 9 (PLAN.md §5): desktop notifications.
 *
 * Android reached these through `NotificationManager`, a channel per concern, and a
 * `PendingIntent` for the tap. PLAN.md §4/D9 makes the desktop equivalent
 * `org.freedesktop.Notifications` — DBus, with a single "Open" action — because it is what every
 * desktop already has and it avoids a DBus binding dependency.
 *
 * ## Why this is two methods
 *
 * [post] and [cancel], because those are the two things the ported code does. Upstream's channel
 * creation, group/summary cards and the `areNotificationsEnabled` gate are all Android concepts
 * with no PipeWire-side counterpart: a desktop notification has no channel, `notify-send` has no
 * group, and whether notifications are shown is the desktop's business rather than a permission
 * this application can query. [enabled] is the one piece of that worth keeping, and it is narrower
 * than upstream's — see its own note.
 *
 * ## The id is the caller's, and the seam tracks the rest
 *
 * [post] and [cancel] are keyed by the id the *caller* chooses — `spontaneousNotificationId`
 * derives it from the persona name so that messages from one persona replace its card rather than
 * stacking. That is upstream's rule and it is pure, so it lives in `:core` where it can be tested.
 *
 * What the seam adds is the mapping from that id to whatever the desktop uses to identify the
 * notification, which is not knowable in `:core` and is not stable across implementations. The
 * mapping is **in memory only**: nothing here survives a restart, and a notification posted before
 * a restart simply cannot be cancelled afterwards. Persisting the map is a decision with a real
 * cost — a stale id is a cancel that silently does nothing — so it is M4's to make when there is a
 * posting path to justify it, rather than something guessed at now.
 */
interface Notifier {

    /**
     * Whether the desktop is likely to show anything.
     *
     * Narrower than upstream's `areNotificationsEnabled()`, on purpose. There is no permission to
     * query here; the honest question is "is the notifier present and usable", which for a
     * `notify-send` implementation is "is `notify-send` installed". It is a constant per run, and
     * a caller that finds it false must log and carry on rather than fail — the message it is
     * announcing has already been persisted, which is where delivery's responsibility ends.
     */
    val enabled: Boolean

    /**
     * Posts a notification, replacing any earlier one with the same [id].
     *
     * Must not throw: it is on the path of a background delivery that has to re-arm regardless,
     * and upstream says the same thing at the same place — "never let delivery take down
     * scheduling". A failure is logged, not propagated.
     */
    fun post(id: Int, title: String, body: String)

    /**
     * Removes the notification with [id], if one is showing. A no-op for an id this run never
     * posted, which is the common case after a restart. Must not throw.
     */
    fun cancel(id: Int)
}
