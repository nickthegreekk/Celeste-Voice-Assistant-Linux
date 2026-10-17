package com.nikosm.voiceassistant

import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.serialization.Serializable

/**
 * A skill that runs on a clock (PLAN.md §10/M15).
 *
 * ## Why this is a separate record and not a field on [Skill]
 *
 * `Models.kt` is a COPY file — byte-identical to upstream, forever (AGENTS.md §3) — and [Skill]
 * lives in it. A schedule is this port's addition, so it lives in its own file and its own
 * settings key, which is also what makes the feature removable without touching upstream's data
 * class. The two are joined by [skillId], the same reference-by-id rule every other shared library
 * uses ([Persona.enabledSkillIds], [Persona.imageWorkflowId]): renaming a skill keeps its
 * schedule, and deleting one drops the schedule with it.
 *
 * ## One schedule per skill, by construction of the editor
 *
 * The store is a list, but [AssistantService.setScheduledSkill] replaces the record for a skill
 * rather than adding a second one, so the model is "this skill runs daily at this time, as this
 * persona". A second time of day is a second skill. The list shape is what a backup restores and
 * what the runner iterates; nothing in the runner assumes uniqueness, so lifting this restriction
 * later is a UI change, not a storage migration.
 *
 * ## The persona is named, not implied
 *
 * A skill is shared; the persona that speaks it is not. The schedule names its persona because
 * the reply lands in that persona's chat and is routed through that persona's model — see
 * [scheduledSkillEligibility] for which personas can be chosen at all.
 */
@Serializable
data class ScheduledSkill(
    val id: String = java.util.UUID.randomUUID().toString(),
    /** The [Skill] this run applies. A schedule whose skill is gone is skipped, never fired. */
    val skillId: String,
    /**
     * The persona that speaks and whose history receives the reply. Name-keyed like every other
     * persona reference; renamed and removed alongside its persona by the service.
     */
    val personaName: String,
    /** Local time of day, minutes from midnight (0..1439). Daily, including weekends. */
    val minuteOfDay: Int = DEFAULT_SCHEDULED_SKILL_MINUTE,
    /**
     * Off keeps the record (time and persona survive) but arms nothing. The editor's switch maps
     * here rather than deleting, because re-enabling a schedule should not mean retyping it.
     */
    val enabled: Boolean = true,
)

/** A sensible default, and the value a newly enabled schedule starts at. */
internal const val DEFAULT_SCHEDULED_SKILL_MINUTE = 8 * 60

/** The number of minutes in a day: the bounds of [ScheduledSkill.minuteOfDay]. */
internal const val SCHEDULED_SKILL_DAY_MINUTES = 24 * 60

/**
 * How late an occurrence may still run.
 *
 * The loop polls every [SCHEDULED_SKILL_POLL_MS], so a run is normally at most one poll late;
 * the grace is what lets a poll drift, a slow tick or a launch a minute after the time still
 * fire once. It is deliberately small: past this window the occurrence is treated as MISSED, not
 * replayed — an application started at noon must not deliver the 08:00 briefing, the same
 * no-catch-up rule the spontaneous scheduler documents.
 */
internal const val SCHEDULED_SKILL_LATE_GRACE_MS: Long = 2 * 60_000L

/** How often the loop re-reads the schedule and the wall clock. Cheap, and immune to suspend. */
internal const val SCHEDULED_SKILL_POLL_MS: Long = 30_000L

// ---------------------------------------------------------------- time

/**
 * Parse an editor field ("08:00", "8:00") into minutes from midnight, or null when it is not a
 * valid time.
 *
 * Strict about the shape and the range, because the alternative — accepting "8" as 08:00 — makes
 * a typo ("80") silently become a different time. The editor shows the warning instead, and
 * nothing is committed while the text is unparseable.
 */
internal fun parseScheduledTime(text: String): Int? {
    val match = Regex("^\\s*(\\d{1,2}):(\\d{2})\\s*$").matchEntire(text) ?: return null
    val hour = match.groupValues[1].toIntOrNull() ?: return null
    val minute = match.groupValues[2].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}

/** "08:00" for a stored minute, for the editor's starting text and every summary line. */
internal fun formatScheduledTime(minuteOfDay: Int): String =
    formatMinuteOfDay(minuteOfDay.coerceIn(0, SCHEDULED_SKILL_DAY_MINUTES - 1))

/** The local instant [minuteOfDay] on the LOCAL date of [now]. */
internal fun scheduledOccurrenceOn(now: Long, zoneId: ZoneId, minuteOfDay: Int): Long {
    val localDate = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zoneId).toLocalDate()
    val time = LocalTime.of((minuteOfDay / 60) % 24, minuteOfDay % 60)
    return localDate.atTime(time).atZone(zoneId).toInstant().toEpochMilli()
}

/**
 * The next occurrence STRICTLY AFTER [now].
 *
 * Strict on purpose: a just-run schedule must not immediately be due again for the same minute.
 * DST is handled by [java.time]'s zone rules rather than by adding 24 hours of millis, which is
 * what keeps a 08:00 schedule at 08:00 local on the day the clocks change.
 */
internal fun nextScheduledOccurrence(now: Long, zoneId: ZoneId, minuteOfDay: Int): Long {
    val today = scheduledOccurrenceOn(now, zoneId, minuteOfDay)
    if (today > now) return today
    val nextDay = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zoneId).toLocalDate().plusDays(1)
    val time = LocalTime.of((minuteOfDay / 60) % 24, minuteOfDay % 60)
    return nextDay.atTime(time).atZone(zoneId).toInstant().toEpochMilli()
}

/**
 * Today's occurrence of [minuteOfDay] if it is due at [now] within [graceMs], else null.
 *
 * "Due" is `occurrence <= now <= occurrence + grace`; an occurrence older than the grace has
 * been missed and is not replayed. The returned value is the OCCURRENCE, not now, because the
 * runner records it as "this slot was handled" — recording `now` would let a poll at the end of
 * the grace window leave the same slot due again.
 */
internal fun dueScheduledOccurrence(
    now: Long,
    zoneId: ZoneId,
    minuteOfDay: Int,
    graceMs: Long = SCHEDULED_SKILL_LATE_GRACE_MS,
): Long? {
    val occurrence = scheduledOccurrenceOn(now, zoneId, minuteOfDay)
    return if (now >= occurrence && now - occurrence <= graceMs) occurrence else null
}

/**
 * The enabled schedules that are due at [now] and whose occurrence has not already run.
 *
 * Pure, so the two rules that matter — the grace window and "once per occurrence" — are tested
 * on virtual instants rather than by waiting on a clock. [lastRunAt] is keyed by schedule id and
 * lives in the runner's memory: it exists to stop a 30-second poll from firing the same 08:00
 * slot six times, not to survive a restart (a restart near the time may re-run it once, which
 * the grace bounds).
 */
internal fun dueScheduledSkills(
    schedules: List<ScheduledSkill>,
    lastRunAt: Map<String, Long>,
    now: Long,
    zoneId: ZoneId,
    graceMs: Long = SCHEDULED_SKILL_LATE_GRACE_MS,
): List<Pair<ScheduledSkill, Long>> =
    schedules.mapNotNull { schedule ->
        if (!schedule.enabled) return@mapNotNull null
        val occurrence = dueScheduledOccurrence(now, zoneId, schedule.minuteOfDay, graceMs)
            ?: return@mapNotNull null
        if ((lastRunAt[schedule.id] ?: Long.MIN_VALUE) >= occurrence) return@mapNotNull null
        schedule to occurrence
    }

// ---------------------------------------------------------------- eligibility

/**
 * Which personas a scheduled skill can run on.
 *
 * The run is a NON-STREAMING direct-Ollama completion (see [AssistantService.runScheduledSkill]),
 * so the answer is exactly the direct-Ollama routing branch: a cloud persona would need a
 * different request builder, and a gateway-only persona has no history-carrying path at all.
 * The reasons are written for this feature rather than reusing [Persona.roomEligibility]'s
 * "(can't converse in a room)" wording, but the routing decision itself comes from the same
 * [Persona.requestRouting], so the editor and the runner cannot disagree.
 */
internal fun scheduledSkillEligibility(
    persona: Persona,
    ollamaServerNames: Set<String>,
    cloudProviderNames: Set<String>
): RoomEligibility {
    if (persona.isImageGenerator) return RoomEligibility.Blocked(SCHEDULED_IMAGE_BLOCK_REASON)
    if (persona.isTranslator) return RoomEligibility.Blocked(SCHEDULED_TRANSLATOR_BLOCK_REASON)
    if (persona.model.isBlank()) return RoomEligibility.Blocked("No model selected")
    val routing = persona.requestRouting(ollamaServerNames, cloudProviderNames)
    return when {
        routing.routesToCloud -> RoomEligibility.Blocked(SCHEDULED_CLOUD_BLOCK_REASON)
        routing.isDirectOllama -> RoomEligibility.Eligible(cloud = false)
        else -> RoomEligibility.Blocked(SCHEDULED_GATEWAY_BLOCK_REASON)
    }
}

internal const val SCHEDULED_IMAGE_BLOCK_REASON =
    "Image generators can't carry out a scheduled skill."
internal const val SCHEDULED_TRANSLATOR_BLOCK_REASON =
    "Translators replace the conversation with a single translation request."
internal const val SCHEDULED_CLOUD_BLOCK_REASON =
    "Local (Ollama) personas only — a scheduled run addresses the model directly, so a cloud " +
        "provider can't be used."
internal const val SCHEDULED_GATEWAY_BLOCK_REASON =
    "Only reachable through the gateway, which can't carry a conversation history for this run."

// ---------------------------------------------------------------- the copy the UI renders

/** The row/section line: "Every day at 08:00 as Friend", with an explicit off/missing state. */
internal fun scheduledSkillSummary(
    schedule: ScheduledSkill?,
    personaNames: Collection<String>
): String {
    if (schedule == null) return "Not scheduled"
    val who = schedule.personaName.takeIf { it in personaNames }
        ?: if (schedule.personaName.isBlank()) "no persona" else "'${schedule.personaName}' (missing)"
    val base = "Every day at ${formatScheduledTime(schedule.minuteOfDay)} as $who"
    return if (schedule.enabled) base else "$base — off"
}

/** The skill list row's schedule line; the same text the editor shows when no schedule exists. */
internal fun skillScheduleSummary(
    skillId: String,
    schedules: List<ScheduledSkill>,
    personaNames: Collection<String>
): String = scheduledSkillSummary(schedules.firstOrNull { it.skillId == skillId }, personaNames)

// ---------------------------------------------------------------- the turn's framing

/**
 * The one-run system-prompt suffix: the app's framing, then the skill's instructions.
 *
 * The skill's instructions land in the suffix — the LAST part of the system prompt — so the
 * existing budget arithmetic counts them (the same contract every other injected text has), and
 * they apply to this run only, exactly as a trigger-fired skill applies to one turn. The silence
 * sentinel is reused from the spontaneous turn because the failure it prevents is the same: a
 * scheduled run with nothing to report must not put an empty bubble (or an invented one) in the
 * user's chat.
 */
internal fun scheduledSkillSystemPromptSuffix(skill: Skill, persona: Persona): String =
    listOf(
        "A scheduled skill is running now: the user set \"${skill.name}\" to run automatically " +
            "at this time. Nothing they said prompted it.",
        "Carry out the skill's instructions in ONE message to the user, in character as " +
            "${persona.name}. Never write the user's side of the conversation, and never invent " +
            "words they did not write.",
        "Skill instructions:",
        skill.instructions.trim(),
        "If there is genuinely nothing to send, reply with exactly $SPONTANEOUS_SILENCE_SENTINEL " +
            "and nothing else."
    ).filter { it.isNotBlank() }.joinToString("\n")

/** The user-side turn that names the due skill, in the shape every background turn uses. */
internal fun scheduledSkillTurnInstruction(skill: Skill, persona: Persona): String =
    "The scheduled skill \"${skill.name}\" is due now. Carry out its instructions and send the " +
        "result to the user as ONE message, in character as ${persona.name}. If there is " +
        "genuinely nothing to send, reply with exactly $SPONTANEOUS_SILENCE_SENTINEL and " +
        "nothing else."

// ---------------------------------------------------------------- persona lifecycle

/**
 * Re-point every schedule that names [oldName] at [newName].
 *
 * Called from [AssistantService.updatePersona]'s rename branch, which is the shared rename
 * surface for every name-keyed reference. The FLOW is the source of truth here, not the store:
 * a schedule set moments ago may still be waiting out the debounced save, and reading the store
 * would silently miss it — the same staleness trap `refreshSpontaneousSchedule` documents. The
 * moved list is therefore written through synchronously, the [SettingsManager.migratePersonaHistory]
 * contract, so an export taken before the next debounced save already carries the new name.
 */
internal fun AssistantService.renameScheduledSkillsForPersona(oldName: String, newName: String) {
    if (oldName == newName) return
    val current = _scheduledSkills.value
    if (current.none { it.personaName == oldName }) return
    val moved = current.map { if (it.personaName == oldName) it.copy(personaName = newName) else it }
    _scheduledSkills.value = moved
    settingsManager.saveScheduledSkills(moved)
}

/**
 * Drop every schedule that names a deleted persona.
 *
 * A schedule is a record the user can still see in the skill editor; leaving it pointing at a
 * name that can never resolve would be a dead switch they could not diagnose. The editor can
 * still delete it, but there is no reason to keep it — same rule as room membership and history.
 * Written through synchronously for the same reason the rename is.
 */
internal fun AssistantService.removeScheduledSkillsForPersona(personaName: String) {
    val current = _scheduledSkills.value
    if (current.none { it.personaName == personaName }) return
    val kept = current.filterNot { it.personaName == personaName }
    _scheduledSkills.value = kept
    settingsManager.saveScheduledSkills(kept)
}
