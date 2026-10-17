package com.nikosm.voiceassistant

import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Scheduled skills — the runner (PLAN.md §10/M15).
 *
 * ## The loop, and why it polls instead of sleeping to the exact instant
 *
 * A single coroutine on [AssistantService.serviceScope] wakes every [SCHEDULED_SKILL_POLL_MS],
 * asks [dueScheduledSkills] what is due, and runs those in order. A timer armed with one long
 * `delay` would be more precise and less robust: `delay` is monotonic, so a machine suspended
 * across the scheduled time would not wake for it (CLOCK_MONOTONIC stops during suspend), and a
 * wall-clock change would move every armed deadline. A cheap poll re-reads both the clock and the
 * live schedule list, so editing a time, deleting a skill or removing a persona takes effect on
 * the next tick without any change-notification plumbing.
 *
 * The cost of polling is bounded by the due window: an occurrence is handled once (the runner
 * records it), and one that the process was not running for is missed rather than replayed.
 *
 * ## Why the turn does not go through the streaming pipeline
 *
 * Same reason as the spontaneous turn this mirrors — see `AssistantServiceSpontaneous.kt`'s
 * header: the streaming path is built around a single live turn, and a scheduled run can fire
 * while the user is mid-conversation with the same persona. This path therefore never bumps the
 * chat request sequence, never writes `currentCall`, never sets [_state] and never plays audio.
 * It shares the RULES (`composeSystemPrompt` → `resolveHistoryBudget` → `budgetedHistory`) and
 * writes through the same [PersonaHistorySink].
 *
 * ## The duplicated request builder is forced by a COPY file
 *
 * `performSpontaneousOllamaCall` is private inside `AssistantServiceSpontaneous.kt`, one of the
 * byte-identical COPY files. Nothing may be added to that file, so the builder below is its
 * scheduled-skill twin, kept line-for-line beside it rather than extracted: an extraction would
 * still leave the COPY unable to call it. What is shared — prompt composition, budgeting,
 * history stamping, the silence parser and the sink — stays shared through their public
 * functions.
 */

private const val TAG = "ScheduledSkills"

/**
 * Client for the reachability preflight, with the spontaneous path's reasoning: the probe runs on
 * the path where "the server is not up" is the most likely answer, and a production read timeout
 * would be worse than useless.
 */
private val scheduledSkillPreflightClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .build()
}

/**
 * Start the once-per-service loop. Called from [AssistantService.start] after settings load, so
 * the first tick sees the persisted schedules.
 */
internal fun AssistantService.startScheduledSkillLoop() {
    // IO, not the service scope's Main dispatcher, and the reason is concrete: `runTest` in this
    // project's suites advances the Main test dispatcher until it is idle at the end of every test
    // body, and an infinite `delay` loop there is never idle — the first full run of this suite
    // hung in `FreshStartNetworkTest`. The periodic health check is launched on IO for the same
    // practical reason. StateFlow writes (`_messages`, the schedule list) are thread-safe, and the
    // turn switches to IO for its own network and history work anyway.
    serviceScope.launch(Dispatchers.IO) {
        // Keyed by schedule id; deliberately in memory. It stops the 30-second poll from running
        // the same occurrence twice, and a restart near the time may run it once more — bounded
        // by SCHEDULED_SKILL_LATE_GRACE_MS, never a full replay.
        val lastRunAt = mutableMapOf<String, Long>()
        while (isActive) {
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            // The due list is captured BEFORE the runs start: two schedules at the same time both
            // run even when the first takes longer than the grace.
            dueScheduledSkills(_scheduledSkills.value, lastRunAt, now, zone).forEach { (schedule, occurrence) ->
                lastRunAt[schedule.id] = occurrence
                // A scheduled run is user-configured and one-shot; a failure is logged and the
                // schedule is NOT retried. Retrying would put a duplicate message in a chat hours
                // after the time the user picked, and the next occurrence is tomorrow anyway.
                try {
                    runScheduledSkill(schedule)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    logger.e(
                        TAG,
                        "scheduled skill '${schedule.skillId}' for '${schedule.personaName}' " +
                            "failed — ${error.message ?: error::class.simpleName}"
                    )
                }
            }
            delay(SCHEDULED_SKILL_POLL_MS)
        }
    }
}

/**
 * Run one due schedule: resolve the live skill and persona, guard, generate one message, persist
 * it into the persona's history and post the per-persona delivery card.
 *
 * Every precondition is re-read HERE rather than trusted from the schedule record — the same
 * live-read rule the spontaneous delivery follows — so a deleted skill, a renamed or removed
 * persona, an edited schedule or a removed Ollama server is a logged skip, never an exception
 * and never a message attributed to something that no longer exists.
 */
internal suspend fun AssistantService.runScheduledSkill(schedule: ScheduledSkill) {
    val skill = _skills.value.firstOrNull { it.id == schedule.skillId }
    if (skill == null) {
        logger.i(TAG, "scheduled run skipped — skill '${schedule.skillId}' no longer exists")
        return
    }
    if (skill.instructions.isBlank()) {
        // A blank instruction set has nothing to run; the editor's warning says so before the
        // user gets here. Sending only the framing would ask the model to invent a task.
        logger.i(TAG, "scheduled run skipped — skill '${skill.name}' has no instructions")
        return
    }
    val persona = personas.value.firstOrNull { it.name == schedule.personaName }
    if (persona == null) {
        logger.i(TAG, "scheduled run skipped — persona '${schedule.personaName}' no longer exists")
        return
    }
    val eligibility = scheduledSkillEligibility(persona, ollamaServerNames(), cloudProviderNames())
    if (eligibility !is RoomEligibility.Eligible) {
        logger.i(
            TAG,
            "scheduled run skipped — '${persona.name}' is not eligible: " +
                (eligibility as RoomEligibility.Blocked).reason
        )
        return
    }

    val target = resolveSpontaneousTarget(persona, _ollamaBaseUrls.value)
    val baseUrl = target.baseUrl
    if (baseUrl == null) {
        logger.i(TAG, "scheduled run skipped — no direct Ollama server for '${persona.name}'")
        return
    }
    if (!preflightSpontaneousServer(baseUrl, _ollamaBaseUrls.value, scheduledSkillPreflightClient)) {
        logger.i(TAG, "scheduled run skipped — '$baseUrl' unreachable")
        return
    }

    val history = withContext(Dispatchers.IO) {
        settingsManager.getPersonaMessages(persona.name) ?: emptyList()
    }
    val sink = PersonaHistorySink(this, persona.name, history)
    val startedAt = System.currentTimeMillis()
    val (content, thinking) = performScheduledSkillOllamaCall(
        baseUrl = baseUrl,
        model = target.actualModel,
        persona = persona,
        history = history,
        turnInstruction = scheduledSkillTurnInstruction(skill, persona),
        suffix = scheduledSkillSystemPromptSuffix(skill, persona)
    )
    val responseTimeMs = System.currentTimeMillis() - startedAt

    // Silence is a first-class outcome, exactly as in a spontaneous turn: the model is allowed
    // to conclude the skill has nothing to report, and that must write nothing.
    val text = spontaneousReplyText(content)
    if (text == null) {
        logger.i(TAG, "scheduled skill '${skill.name}' for '${persona.name}' chose silence")
        return
    }
    val index = sink.append(ChatMessage("assistant", text, thinking, responseTimeMs = responseTimeMs))
    if (index < 0) {
        logger.w(TAG, "scheduled output for '${persona.name}' discarded — persona gone")
        return
    }
    sink.persist()

    // The same per-persona card the spontaneous delivery posts: both are "this persona said
    // something while you were elsewhere", and one card per persona is the desktop rule (there
    // are no Android channels to keep them apart).
    postSpontaneousNotification(appEnv.notifier, persona.name, text)
    logger.i(
        TAG,
        "scheduled skill '${skill.name}' ran for '${persona.name}' " +
            "(${text.length} chars, ${responseTimeMs}ms)"
    )
}

/**
 * One non-streaming direct-Ollama completion, the scheduled twin of
 * `performSpontaneousOllamaCall` (see the file header for why it is a twin).
 *
 * Search and RAG are not consulted, for the spontaneous path's reason: both use the turn's text
 * as their query, and a scheduling instruction is not a question — retrieving documents about
 * the instruction itself would be worse than no retrieval.
 */
private suspend fun AssistantService.performScheduledSkillOllamaCall(
    baseUrl: String,
    model: String,
    persona: Persona,
    history: List<ChatMessage>,
    turnInstruction: String,
    suffix: String
): Pair<String, String?> = withContext(Dispatchers.IO) {
    // The same backend normalisation the streaming direct path performs, including the refusal
    // to treat a gateway (8880) as an Ollama backend.
    val stripped = baseUrl.trim()
    val resolvedBackend = if (stripped.isBlank() || stripped.contains(":8880")) {
        _ollamaBaseUrls.value.firstOrNull()?.url ?: ""
    } else {
        stripped.removeSuffix("/")
    }
    if (resolvedBackend.isBlank()) throw Exception("No Ollama server configured")
    val url = if (resolvedBackend.endsWith("/api/chat")) resolvedBackend
    else "${resolvedBackend.trimEnd('/')}/api/chat"

    val systemPrompt = composeSystemPrompt(
        persona = persona,
        currentDateTime = getCurrentDateTimeString(),
        searchContext = "",
        ragContext = "",
        suffix = suffix
    )
    val budget = resolveHistoryBudget(
        contextWindow = persona.numCtx,
        reservedOutput = persona.maxTokens.coerceAtLeast(1024),
        systemPrompt = systemPrompt,
        modelText = turnInstruction
    )
    val stampedHistory = spontaneousHistoryForRequest(
        history = history,
        stampsHistory = persona.stampsHistoryWithTimestamps(ollamaServerNames(), cloudProviderNames())
    )
    val trimmed = budgetedHistory(stampedHistory, budget)

    val messages = JSONArray()
        .put(JSONObject().put("role", "system").put("content", systemPrompt))
    trimmed.forEach { messages.put(JSONObject().put("role", it.role).put("content", it.text)) }
    messages.put(JSONObject().put("role", "user").put("content", turnInstruction))

    val options = JSONObject()
        .put("temperature", persona.temperature)
        .put("top_p", persona.topP)
        .put("top_k", persona.topK)
        .put("repeat_penalty", persona.repeatPenalty)
        .put("num_ctx", persona.numCtx)
        .put("think", persona.enableThinking && isKnownThinkingModel(model))
        .put("num_predict", persona.maxTokens)

    val json = JSONObject()
        .put("model", model)
        .put("stream", false)
        .put("messages", messages)
        .put("options", options)

    val requestBuilder = Request.Builder()
        .url(url)
        .post(json.toString().toRequestBody("application/json".toMediaType()))
    _ollamaBaseUrls.value.find { it.url == resolvedBackend }?.let { config ->
        when (config.effectiveAuthType) {
            AuthType.NONE -> { /* no Authorization header */ }
            AuthType.BASIC -> if (!config.username.isNullOrBlank()) {
                requestBuilder.header("Authorization", Credentials.basic(config.username, config.password ?: ""))
            }
            AuthType.API_KEY -> if (!config.apiKey.isNullOrBlank()) {
                requestBuilder.header("Authorization", "Bearer ${config.apiKey}")
            }
        }
    }

    logger.d(TAG, "scheduled request: model=$model history=${trimmed.size} budget=$budget url=$url")
    getDynamicClient(persona).newCall(requestBuilder.build()).execute().use { response ->
        if (!response.isSuccessful) throw Exception("Ollama error: ${response.code}")
        val obj = JSONObject(response.body.string())
        val message = obj.optJSONObject("message")
        val content = message?.optString("content").orEmpty()
        val thinking = message?.optString("thinking")?.ifBlank { null }
        content to thinking
    }
}
