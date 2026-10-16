// PARTIALLY PORTED IN M1. What is here is the pure half: the spontaneous-schedule helpers the M1
// persona editor calls — `spontaneousSectionEligibility` above all, which decides whether the
// section is shown AND whether an opt-in survives a save, plus the slider and formatting helpers
// the section itself uses. None of them touch Compose or the platform, which is why they are
// here rather than with the screen.
//
// What is NOT here is `PersonaSpontaneousSection` and its two `@Preview` composables: the section
// is M4's, and the editor's call site carries `DEFERRED TO M4`. PLAN.md §5's shared-block rule is
// what puts the pure helpers in M1 — a shared building block arrives with its first caller, and
// the editor is the first caller.
//
// Upstream was one 394-line file; setting the section aside leaves the helpers, unchanged.

package com.nikosm.voiceassistant



/**
 * Spontaneous messages — the persona editor's section: the render gate, the controls, and
 * the manual "send now" affordance.
 *
 * ## The gate's source of truth (read this before changing anything here)
 *
 * [spontaneousSectionEligibility] is evaluated by the CALLER against the **service's live**
 * server lists — `service.ollamaServerNames()` / `service.cloudProviderNames()` — and the
 * same verdict is threaded into both the render gate below and the editor's commit, so the
 * two can never disagree. It is never evaluated from a `collectAsState()` snapshot of those
 * same flows.
 *
 * That distinction is load-bearing, and it is why this file exposes a pure function rather
 * than having the section collect for itself: a Compose snapshot starts at the flow's
 * *initial* value and fills in asynchronously from a `LaunchedEffect`, so
 * `MutableStateFlow(emptyList())` reads as `emptyList()` during the editor's FIRST
 * composition and for every frame until the collector delivers. Handing that empty Ollama
 * set to [Persona.spontaneousEligibility] makes a perfectly local persona
 * (`[HomeBox] llama3`) resolve as gateway-only — i.e. **Blocked** — so the section would
 * blink out on the first frame and pop back in. Worse, the editor's commit path runs off
 * the same verdict, so a save landing inside that window would strip a valid opt-in.
 *
 * The service has no such window: it loads its lists in `onCreate`, and the
 * `onServiceConnected` callback (which is what hands the editor a service at all) fires
 * only after `onCreate` returns. Reading the service is therefore correct on every frame,
 * including the first. See `SpontaneousEditorUiInstrumentedTest`, which pins the
 * first-frame case deliberately rather than only the settled state.
 *
 * [spontaneousSectionEligibility] is the pure decision, parameterised on the sets so it can
 * be exercised on the JVM.
 */
internal fun spontaneousSectionEligibility(
    persona: Persona,
    model: String,
    isTranslator: Boolean,
    isImageGenerator: Boolean,
    ollamaServerNames: Set<String>,
    cloudProviderNames: Set<String>
): RoomEligibility {
    val trimmed = model.trim()
    // `isCloud` is re-derived from the LIVE model text plus the LIVE server sets — never
    // taken from `persona.isCloud`, which is stale by construction while the model field is
    // being edited (the editor only writes it on commit).
    //
    // This matters for the REASON, not just the verdict: [Persona.spontaneousEligibility]
    // delegates to `roomEligibility`, whose cloud branch keys off the flag, so a
    // just-typed cloud model with a stale flag would otherwise fall through to the
    // "gateway-only" reason. That reason is true but it lies about *why* — the user is
    // looking at a cloud model, and the gate must say the cloud sentence, not a routing
    // technicality. A gateway-only model (no cloud flag either way) still gets the
    // gateway reason, unchanged.
    val displayServer = if (trimmed.startsWith("[") && trimmed.contains("] ")) {
        trimmed.substring(1, trimmed.indexOf("]"))
    } else {
        null
    }
    val resolvedCloud = persona.isCloud || (displayServer != null && displayServer in cloudProviderNames)
    return persona
        .copy(
            model = trimmed,
            isCloud = resolvedCloud,
            isTranslator = isTranslator,
            isImageGenerator = isImageGenerator
        )
        .spontaneousEligibility(ollamaServerNames, cloudProviderNames)
}

// ---------------------------------------------------------------- slider <-> value

/** Largest selectable gap: 24 hours. */
internal const val SPONTANEOUS_GAP_MAX_MINUTES = 24 * 60

/**
 * Gap slider detent, deliberately equal to the platform floor. Every detent is then a legal
 * value, so the clamp is structural rather than a correction applied behind the user's back.
 */
internal const val SPONTANEOUS_GAP_STEP_MINUTES = SpontaneousPolicy.MIN_GAP_MINUTES

/** Window sliders move in half-hour steps — the finest granularity worth offering. */
internal const val SPONTANEOUS_WINDOW_STEP_MINUTES = 30

internal const val SPONTANEOUS_DAY_MINUTES = 24 * 60

/**
 * The window the toggle materialises the first time it is switched on: 08:00–22:00. Picking
 * a concrete default beats starting an overnight window (start == end == 00:00, which reads
 * as a full day and would surprise nobody until 3am).
 */
internal const val DEFAULT_WINDOW_START_MIN = 8 * 60
internal const val DEFAULT_WINDOW_END_MIN = 22 * 60

/**
 * Snap a gap slider position to the value that gets stored: 15-minute detents, clamped to
 * `[MIN_GAP_MINUTES, SPONTANEOUS_GAP_MAX_MINUTES]`.
 *
 * Clamping here as well as in [SpontaneousPolicy.effectiveGapMinutes] is deliberate
 * belt-and-braces: the policy clamp is what protects the scheduler from a value that
 * arrived by any other route, and this one is what guarantees the editor can never even
 * produce one.
 */
internal fun spontaneousGapFromSlider(value: Float): Int {
    val snapped = Math.round(value / SPONTANEOUS_GAP_STEP_MINUTES) * SPONTANEOUS_GAP_STEP_MINUTES
    return snapped.coerceIn(SpontaneousPolicy.MIN_GAP_MINUTES, SPONTANEOUS_GAP_MAX_MINUTES)
}

/**
 * Snap a window slider position to a minute-of-day: 30-minute detents, clamped to
 * `[0, 1440 - step]` so a bound can never land on the exclusive end of the day.
 */
internal fun spontaneousWindowMinuteFromSlider(value: Float): Int {
    val snapped = Math.round(value / SPONTANEOUS_WINDOW_STEP_MINUTES) * SPONTANEOUS_WINDOW_STEP_MINUTES
    return snapped.coerceIn(0, SPONTANEOUS_DAY_MINUTES - SPONTANEOUS_WINDOW_STEP_MINUTES)
}


// ---------------------------------------------------------------- summaries

/** "08:00" / "22:30" for a minute-of-day. Minutes are taken modulo a day. */
internal fun formatMinuteOfDay(minute: Int): String =
    String.format(java.util.Locale.US, "%02d:%02d", (minute / 60) % 24, minute % 60)

/**
 * Human summary of the delivery window. An overnight window (start > end) is labelled as
 * such rather than shown as a nonsense reversed range; `start == end` reads as a full day,
 * which is what a wrapping zero-length window means — the plan's documented convention.
 */
internal fun spontaneousWindowSummary(start: Int?, end: Int?): String = when {
    start == null && end == null -> "any time of day"
    start != null && end != null ->
        "${formatMinuteOfDay(start)}–${formatMinuteOfDay(end)}" +
            if (start > end) " (overnight)" else ""
    start != null -> "after ${formatMinuteOfDay(start)}"
    else -> "before ${formatMinuteOfDay(end!!)}"
}

/** "4h" / "90m". */
internal fun formatGap(minutes: Int): String =
    if (minutes % 60 == 0) "${minutes / 60}h" else "${minutes}m"

/** One-line delivery summary for the section subtitle. */
internal fun spontaneousScheduleSummary(
    gapMinutes: Int,
    isDefaultGap: Boolean,
    start: Int?,
    end: Int?
): String =
    "at least ${formatGap(gapMinutes)} apart" +
        (if (isDefaultGap) " (default)" else "") +
        ", ${spontaneousWindowSummary(start, end)}"

// ---------------------------------------------------------------- the section

/**
 * The persona editor's Spontaneous Messages section.
 *
 * [ollamaServerNames] / [cloudProviderNames] MUST be the caller's LIVE
 * `service.ollamaServerNames()` / `service.cloudProviderNames()` values — see the file
 * header for why a collected snapshot must never be passed here.
 */
