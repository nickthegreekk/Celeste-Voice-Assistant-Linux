package com.nikosm.voiceassistant

import java.nio.file.Path

/**
 * Everything the ported code used to get from Android's `Context` (PLAN.md §3, D5).
 *
 * This interface is in `:core` and its Linux implementation in `:platform`, which is the rule for
 * every seam (AGENTS.md §4): `:core` never learns about `$XDG_*`, `$HOME` or the filesystem
 * layout, it receives paths. It is also why this is injectable rather than a singleton — a test
 * points it at a temp directory instead of the real `~/.config`.
 *
 * **It grows one seam at a time, and only when a caller that cannot be edited forces it** — which
 * is PLAN.md §5's rule for seams generally, applied to the object that hands them out. M1.1 had
 * the four paths, the debug flag and the logger, because those were what the ported call sites then
 * needed.
 *
 * Exactly two seams have been forced onto it, and it is worth naming why they are the same case
 * rather than two: [notifier] and [scheduler] are both reachable *only* through a byte-identical
 * COPY file that hands over `applicationContext` and nothing else. `AssistantServiceSpontaneous.kt`
 * calls `cancelSpontaneousNotification(applicationContext, …)` and the three scheduling functions
 * with the same first argument, so `AssistantService.applicationContext` has to exist, its type is
 * this interface, and whatever those calls need has to be inside it. Every other seam is a
 * constructor parameter on `AssistantService`, where it belongs.
 *
 * Nothing is added here speculatively. The keyring, the audio stack and the file dialogs are still
 * absent because their callers have not arrived — and adding them early would rebuild the
 * god-object `Context` was, which is what D5 exists to avoid.
 *
 * Two things it notably does **not** carry, both because the call sites do not need them:
 *
 *  - **A file resolver.** `SettingsManager` and the attachment path used `contentResolver` and
 *    `Uri`, but only ever to read or write a single file. Ported, those are `java.nio.Path`
 *    values, so the resolver disappears rather than being abstracted.
 *  - **Device info.** `getSystemService` call sites are the audio seams (M2) and the notification
 *    seam (M4); each gets what it needs from its own seam. `--selftest` reports the audio device
 *    layer through `AudioDeviceProbe`, not through here.
 *
 * The two directory pairs are not interchangeable and the port must not treat them as one:
 * [dataDir] was the phone's `filesDir` and held persona artwork and message images; since
 * 2026-10-18 those live in per-persona folders under `homeDir/.celeste-linux` (the user's chosen
 * shape), and [dataDir] keeps only what a pre-folder installation left there. [cacheDir] holds
 * things that may be evicted (recorded utterances, synthesised WAVs). Collapsing them would turn a
 * cache clear into data loss.
 */
interface AppEnv {

    /**
     * The user's home directory. `:core` does not resolve `$HOME` for itself (AGENTS.md §4); it
     * receives this path and the two things that need it are already named: the filesystem
     * tools' always-excluded paths (`~/.ssh` above all) and, since 2026-10-18, the per-persona
     * conversation store at `homeDir/.celeste-linux`, which the user asked for by that name.
     */
    val homeDir: Path

    /** Settings, personas, servers, skills, image-generation entries. */
    val configDir: Path

    /**
     * The pre-2026-10-18 artwork directory (`persona_images`). New artwork and message images are
     * written into the persona's own folder; this remains because an older install's files are
     * copied from it once, and nothing new is written here.
     */
    val dataDir: Path

    /** Logs, usage counters. */
    val stateDir: Path

    /** Utterance WAVs, downloaded images, model downloads. Safe to lose. */
    val cacheDir: Path
    /**
     * Where saved images go: `XDG_PICTURES_DIR/VoiceAssistant`.
     *
     * A path like the four above it, and for the same reason — `:core` never resolves `$HOME` or
     * `$XDG_*` for itself (AGENTS.md §4). Unlike them it is a *user* dir rather than an XDG base
     * dir, so `XdgPaths` resolves it on its own; upstream wrote into the same folder through
     * `MediaStore`'s `RELATIVE_PATH`, which is why the name is not this port's to choose.
     */
    val picturesDir: Path

    /**
     * Where per-persona `.desktop` entries go: `$XDG_DATA_HOME/applications` — the menu's own
     * directory, not this application's data directory (see `XdgPaths.applicationsDir`).
     */
    val applicationsDir: Path

    /**
     * The user's Desktop, for the second copy of a persona shortcut — the one they asked for on
     * 2026-10-16, after the menu entry turned out to be somewhere they do not look.
     *
     * A user dir like [picturesDir], resolved by `XdgPaths` for the same reason, and the one path
     * here that **may not exist**: a machine with no `~/Desktop` is a machine whose owner does not
     * keep shortcuts on one, so callers write there only when the directory is already there rather
     * than creating it.
     */
    val desktopDir: Path


    /**
     * Whether conversation-content logging is on — the port of `BuildConfig.DEBUG`.
     *
     * This is a privacy switch, not a verbosity switch (AGENTS.md §5). The Android call sites use
     * it to gate raw system prompts, model output, message-history previews and full request
     * payloads; upstream's own comment on one of them calls it "S4: gated so private prompts and
     * model output can never reach a release log".
     *
     * On Android it was a compile-time constant and a release build could not turn it on. A
     * desktop has one build, so it is a runtime value here — and it **defaults to false**, in the
     * resolver and in every implementation, because the failure mode of getting this wrong is the
     * user's private conversations in journald rather than a noisy log.
     */
    val debugLogging: Boolean

    /** Replaces `android.util.Log` for injected code; see [LogSink] for the static bridge. */
    val logger: AppLogger

    /**
     * Seam 9, and the port of `Context.getSystemService(NotificationManager::class.java)`.
     *
     * Here rather than passed to `AssistantService` separately because a byte-identical COPY file
     * calls `cancelSpontaneousNotification(applicationContext, …)`: the service's
     * `applicationContext` member is what satisfies that call, its type is this interface's, and
     * D5 says `Context` is what this object replaces.
     */
    val notifier: Notifier

    /**
     * Seam 10, and the same forcing rule as [notifier] rather than a coincidence — the two are the
     * only seams on this interface, and both are here for one reason: a file this port may not edit
     * hands over `applicationContext` and nothing else.
     *
     * `AssistantServiceSpontaneous.kt` is byte-identical and calls `isSpontaneouslyArmed`,
     * `armSpontaneousWork` and `disarmSpontaneousWork` three times each, passing `applicationContext`
     * as their first argument. Those are top-level functions taking `(context, …)`, so the argument's
     * type is what has to hold the capability — a constructor parameter on `AssistantService` could
     * not satisfy the call. The `:platform` implementation is `StubScheduler` until M4, which is
     * where spontaneous delivery lands.
     */
    val scheduler: Scheduler
}
