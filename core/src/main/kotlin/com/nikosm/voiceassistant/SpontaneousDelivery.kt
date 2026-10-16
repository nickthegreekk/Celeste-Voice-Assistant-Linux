package com.nikosm.voiceassistant

import java.time.ZoneId
import java.util.Random
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * One delivery run: what upstream's `SpontaneousDeliveryWorker` did, minus everything that existed
 * only because WorkManager lives in another process.
 *
 * ## What ported, and what dissolved
 *
 * The decision, the preflight, the turn and the re-arm are upstream's, expression for expression:
 * `evaluateSpontaneousCycle` (a COPY file), `preflightSpontaneousServer`, `runSpontaneousTurn` (a
 * COPY file) and `armSpontaneousWork`. What did **not** come across is the service binding —
 * `useAssistantService`, its `ServiceConnection`, the 15-second timeout and the `BIND_AUTO_CREATE`
 * reasoning — because the run happens *inside* the service here. PLAN.md §3/D1's "one JVM process"
 * deletes that whole section rather than translating it, and the ordering it protected (bind late,
 * after the cheap checks, so a construction costing TTS and a VAD detector only happens when a
 * message is genuinely about to be generated) is preserved where it now matters: [deliveryPreflight]
 * and the decision run before the turn does any work.
 *
 * ## Upstream's contracts, kept because the ports of them are what make this short
 *
 * - **deliver only on a real send.** The notification is attached to the turn's *outcome*, so
 *   silence, a skip and a failure all produce no card. A card for a message that does not exist is
 *   worse than no card.
 * - **never a retry.** Every path ends in a re-arm one full gap from now, or in nothing at all when
 *   a precondition is gone. Upstream used `Result.success()` for that; here it is the absence of an
 *   `arm`, and the reasoning survives: a backoff would desynchronise the gap the user configured.
 * - **a throw is logged, not propagated.** A transport failure that escaped would end the chain
 *   silently, which is the one outcome this feature's guard-and-log design exists to avoid.
 * - **the slot.** The run arms the *other* slot, so a re-arm can never be seen by whoever armed this
 *   run — upstream's involution, kept because `alternateSpontaneousSlot` is already here and
 *   because the caller's contract should not have to care where the arm sits in the run.
 */
private const val TAG = "Spontaneous"

/**
 * Client used ONLY by the reachability probe — upstream's, with its comment intact in spirit: this
 * runs on the path where the answer is most likely "the network is down", and a preflight that waited
 * for a production read timeout would be worse than useless.
 */
private val deliveryPreflightClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .build()
}

/** Upstream's jitter source. One instance per process, as the worker's field was one per run. */
private val deliveryRandom = Random()

/**
 * Run one scheduled delivery for [personaName], which occupied [slot].
 *
 * Reads the *live* persona list and server lists rather than anything captured when the run was
 * armed — upstream's §6 location #4 — so a persona renamed, deleted, switched off or re-routed since
 * then is caught by the decision instead of by an exception later.
 */
internal suspend fun AssistantService.runSpontaneousDelivery(personaName: String, slot: Int) {
    val now = System.currentTimeMillis()
    val zone = ZoneId.systemDefault()
    val persona = personas.value.find { it.name == personaName }
    val allStates = settingsManager.getSpontaneousStates()
    val today = spontaneousToday(now, zone)

    val decision = evaluateSpontaneousCycle(
        SpontaneousCycleRequest(
            now = now,
            zoneId = zone,
            personaExists = persona != null,
            spontaneousEnabled = persona?.spontaneousEnabled == true,
            eligibility = persona?.spontaneousEligibility(ollamaServerNames(), cloudProviderNames())
                ?: RoomEligibility.Blocked("The persona no longer exists."),
            state = settingsManager.spontaneousStateFor(personaName),
            gapMillis = SpontaneousPolicy.effectiveGapMinutes(persona?.spontaneousGapMinutes ?: 0) * 60_000L,
            maxPerDay = SpontaneousPolicy.effectiveMaxPerDay(persona?.spontaneousMaxPerDay ?: 0),
            globalSentToday = allStates.values
                .filter { it.sentTodayDate == today }
                .sumOf { it.sentTodayCount },
            globalMaxPerDay = SpontaneousPolicy.GLOBAL_MAX_PER_DAY,
            windowStartMin = persona?.spontaneousWindowStartMin,
            windowEndMin = persona?.spontaneousWindowEndMin,
        )
    )

    when (decision) {
        is SpontaneousDecision.Cancel -> {
            // No disarm: not re-arming *is* the cancel, and disarming would race a newer chain that
            // another path may have just armed. Upstream said the same about its unique work.
            logger.i(TAG, "spontaneous for '$personaName' cancelled: ${decision.reason}")
        }

        is SpontaneousDecision.NotDue -> rearmSpontaneous(
            personaName = personaName,
            targetAt = decision.nextAttemptAt,
            persona = persona,
            zone = zone,
            slot = slot,
        )

        SpontaneousDecision.Proceed -> attemptSpontaneousDelivery(
            personaName = personaName,
            persona = persona ?: return,
            attemptedAt = now,
            zone = zone,
            slot = slot,
        )
    }
}

/**
 * Plan §2.2 steps 4–6: **preflight → generate + persist → re-arm from now**.
 *
 * Every exit re-arms one full gap from this moment except the "server is gone" case, which is a
 * precondition and ends the chain: there is nothing to wait for, and a timer left waiting would
 * never fire anything but a skip.
 */
private suspend fun AssistantService.attemptSpontaneousDelivery(
    personaName: String,
    persona: Persona,
    attemptedAt: Long,
    zone: ZoneId,
    slot: Int,
) {
    val baseUrl = resolveSpontaneousTarget(persona, _ollamaBaseUrls.value).baseUrl
    if (baseUrl == null) {
        // Direct-Ollama eligibility implied a configured server; losing it since then is the same
        // class of precondition as a deletion.
        logger.w(TAG, "spontaneous for '$personaName' cancelled — no direct Ollama server")
        return
    }

    if (!preflightSpontaneousServer(baseUrl, _ollamaBaseUrls.value, deliveryPreflightClient)) {
        logger.i(TAG, "spontaneous for '$personaName' skipped — '$baseUrl' unreachable")
        rearmAfterSpontaneousAttempt(personaName, persona, attemptedAt, zone, slot)
        return
    }

    // The turn owns everything about generating and persisting the message; it throws on transport
    // errors and returns normally on silence, which is exactly the contract this needs.
    val outcome: SpontaneousTurnOutcome? = try {
        runSpontaneousTurn(persona)
    } catch (error: Throwable) {
        logger.w(TAG, "spontaneous for '$personaName' failed — ${describeSpontaneousFailure(error)}")
        null
    }

    // Delivery hangs off the OUTCOME, never off the attempt: only a real send produces a card.
    if (outcome is SpontaneousTurnOutcome.Sent) {
        postSpontaneousNotification(appEnv.notifier, personaName, outcome.text)
    }

    rearmAfterSpontaneousAttempt(personaName, persona, attemptedAt, zone, slot)
}

/**
 * Re-arm one gap after the attempt just made, measuring from a real send when the turn managed one
 * so that the gap belongs to the message rather than to when the attempt started.
 */
private fun AssistantService.rearmAfterSpontaneousAttempt(
    personaName: String,
    persona: Persona,
    attemptedAt: Long,
    zone: ZoneId,
    slot: Int,
) {
    val now = System.currentTimeMillis()
    val lastActivity = maxOf(attemptedAt, settingsManager.spontaneousStateFor(personaName).lastSentAt)
    val target = nextSpontaneousAttempt(
        lastAttemptAt = lastActivity,
        gapMillis = SpontaneousPolicy.effectiveGapMinutes(persona.spontaneousGapMinutes) * 60_000L,
        now = now,
        zoneId = zone,
        windowStartMin = persona.spontaneousWindowStartMin,
        windowEndMin = persona.spontaneousWindowEndMin,
    )
    rearmSpontaneous(personaName, target, persona, zone, slot)
}

/**
 * Arm the next run at absolute instant [targetAt], spread by up to the policy's jitter (dropped when
 * the jitter would leave the window), into the slot this run did not occupy.
 */
private fun AssistantService.rearmSpontaneous(
    personaName: String,
    targetAt: Long,
    persona: Persona?,
    zone: ZoneId,
    slot: Int,
) {
    val now = System.currentTimeMillis()
    val at = withSpontaneousJitter(
        base = targetAt,
        zoneId = zone,
        windowStartMin = persona?.spontaneousWindowStartMin,
        windowEndMin = persona?.spontaneousWindowEndMin,
        random = deliveryRandom,
    )
    armSpontaneousWork(applicationContext, personaName, alternateSpontaneousSlot(slot), at - now)
}
