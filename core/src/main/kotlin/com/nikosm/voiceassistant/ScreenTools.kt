package com.nikosm.voiceassistant

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * M19 on the service: the question a screen capture needs answered, the executor that asks it,
 * and the settings resolution behind both.
 *
 * The pure parts are in `ScreenCapture.kt` and `ScreenAudit.kt`; this file is only what needs the
 * live service. The shape is M16's command gate, deliberately — with one thing removed: **there is
 * no session trust.** A command is the same command tomorrow; a screen is whatever the user is
 * doing at the next capture, so every one asks.
 */

/** The question the dialog is showing. [id] makes a stale answer impossible to apply to a newer one. */
internal data class ScreenApprovalRequest(
    val id: Long,
    val personaName: String,
    /** The model's own question, bounded; shown so the user knows what is being looked for. */
    val question: String,
)

/** What the user decided. There is no "trust" answer for a screen. */
internal enum class ScreenDecision {
    APPROVED,
    DENIED,
    TIMEOUT,
    ;

    val allows: Boolean get() = this == APPROVED
}

/** One card, reused: there is at most one question pending, answered or withdrawn. */
internal const val SCREEN_APPROVAL_NOTIFICATION_ID = 4

internal class ScreenApprovalGate {
    private val _pending = MutableStateFlow<ScreenApprovalRequest?>(null)

    /** The question the UI is showing, or null when there is nothing to answer. */
    val pending = _pending.asStateFlow()

    @Volatile
    private var deferred: CompletableDeferred<ScreenDecision>? = null

    @Volatile
    private var nextId: Long = 0

    /**
     * The user's answer to the question [id]. Ignored when it is not the pending question: a
     * dialog left over from a superseded turn must not approve a newer capture.
     */
    fun answer(id: Long, approve: Boolean) {
        val waiting = deferred ?: return
        val request = _pending.value ?: return
        if (request.id != id) return
        waiting.complete(if (approve) ScreenDecision.APPROVED else ScreenDecision.DENIED)
    }

    /** A newer turn started, or the app is closing: deny, so a stale dialog can never approve. */
    fun denyPending() {
        deferred?.complete(ScreenDecision.DENIED)
    }

    /**
     * Ask, and wait. Answered by [answer], refused by [denyPending], and **denied on timeout** —
     * a turn must not hang forever on a question nobody is there to read.
     */
    suspend fun request(
        personaName: String,
        question: String,
        timeoutMs: Long = SCREEN_APPROVAL_TIMEOUT_MS,
    ): ScreenDecision {
        val waiting = CompletableDeferred<ScreenDecision>()
        deferred = waiting
        _pending.value = ScreenApprovalRequest(++nextId, personaName, question)
        return try {
            withTimeoutOrNull(timeoutMs) { waiting.await() } ?: ScreenDecision.TIMEOUT
        } finally {
            if (deferred === waiting) {
                _pending.value = null
                deferred = null
            }
        }
    }
}

/**
 * Ask before a capture. A timeout denies, and the user is told through the notifier — the channel
 * that still works when no window can render the dialog.
 *
 * Logging and the notification never carry the question: it is model-generated and can quote the
 * conversation, and the audit file is the record that matters.
 */
internal suspend fun AssistantService.requestScreenApproval(personaName: String, question: String): ScreenDecision {
    val decision = screenGate.request(personaName, question)
    if (decision == ScreenDecision.TIMEOUT) {
        runCatching {
            appEnv.notifier.post(
                SCREEN_APPROVAL_NOTIFICATION_ID,
                "Celeste",
                "A screen capture was not approved — no answer in time. Nothing was captured or sent.",
            )
        }
    }
    return decision
}

/** Record one capture attempt, in the log and in the audit file. Never the image or description. */
internal fun AssistantService.auditScreenCapture(entry: ScreenAuditEntry) {
    logger.i(
        "Screen",
        "look_at_screen -> ${entry.result} (${entry.bytes} bytes, ${entry.confirmed})"
    )
    appendScreenAudit(appEnv.stateDir, entry, logger)
}

/**
 * The persona's effective screen policy: its own value, else the global one. Same decode rule as
 * the command pair — an unreadable stored value is OFF.
 */
internal fun AssistantService.screenModeFor(persona: Persona): ScreenToolMode =
    settingsManager.getPersonaScreenMode(persona.name) ?: settingsManager.getScreenMode()

/**
 * Whether this persona may be offered the tool this turn. Two questions, both required:
 *
 *  - the mode allows it, and
 *  - a **direct Ollama** vision target resolves. A cloud persona and a gateway-only persona get
 *    no tool rather than one that always fails; the four cloud image shapes are a second
 *    implementation and deliberately not in v1.
 *
 * The target is resolved from `[Server] model` tags and the persona's own backend URL through
 * [resolveVisionTarget], the same function the OCR path uses, so the two cannot disagree about
 * which model can see.
 */
internal fun AssistantService.screenVisionAvailableFor(persona: Persona): Boolean =
    screenModeFor(persona).allowsScreen &&
        resolveVisionTarget(persona, _ollamaBaseUrls.value) != null

/** The settings screen's global switch. */
internal fun AssistantService.setScreenModeSetting(mode: ScreenToolMode) {
    settingsManager.saveScreenMode(mode)
    logger.i("Screen", "screen vision is now ${mode.name}")
}

/** The persona editor's override. Null means "follow the global". */
internal fun AssistantService.setPersonaScreenModeSetting(personaName: String, mode: ScreenToolMode?) {
    settingsManager.savePersonaScreenMode(personaName, mode)
}

// ---------------------------------------------------------------- the executor

/**
 * One turn's screen tool: parse the call, ask, capture, send to the vision model, frame, audit.
 *
 * The per-turn byte bound is [MAX_TURN_RESULT_BYTES], the same one the other executors use.
 * A description is capped per call as well ([SCREEN_MAX_DESCRIPTION_CHARS]); a screen full of
 * text can otherwise produce an answer larger than the request should carry.
 */
internal class ScreenToolExecutor(
    private val personaName: String,
    private val capture: suspend () -> ByteArray?,
    private val approve: suspend (question: String) -> ScreenDecision,
    private val describe: suspend (png: ByteArray, question: String) -> String?,
    private val audit: (ScreenAuditEntry) -> Unit,
) : ToolCallExecutor {

    /** One turn's worth, in UTF-8 bytes of framed result text. */
    private var spent = 0L

    override suspend fun execute(call: FsToolCall): FsToolResult {
        val args = toolArgumentsOf(call)
        val question = args.optString("question").trim()
            .take(SCREEN_MAX_QUESTION_CHARS)
            .ifBlank { SCREEN_DEFAULT_QUESTION }

        if (spent > MAX_TURN_RESULT_BYTES) {
            return FsToolResult(
                call.id, call.name,
                "This turn has already used as much of the screen output as it may; ask for something narrower.",
                ok = false,
            )
        }

        val decision = approve(question)
        if (!decision.allows) {
            audit(
                ScreenAuditEntry(
                    timestamp = System.currentTimeMillis(),
                    persona = personaName,
                    question = question,
                    result = if (decision == ScreenDecision.TIMEOUT) "timeout" else "denied",
                    confirmed = if (decision == ScreenDecision.TIMEOUT) "timeout" else "denied",
                )
            )
            val text = if (decision == ScreenDecision.TIMEOUT) {
                "The user did not answer the confirmation in time, so nothing was captured."
            } else {
                "The user refused the screen capture. Nothing was captured or sent."
            }
            return FsToolResult(call.id, call.name, text, ok = false)
        }

        val started = System.currentTimeMillis()
        val png = try {
            capture()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            audit(
                ScreenAuditEntry(
                    timestamp = System.currentTimeMillis(),
                    persona = personaName,
                    question = question,
                    durationMs = System.currentTimeMillis() - started,
                    result = "error",
                    confirmed = "user",
                )
            )
            return FsToolResult(
                call.id, call.name,
                "The screen could not be captured: ${error.message ?: error::class.simpleName}.",
                ok = false,
            )
        }
        if (png == null || png.isEmpty()) {
            audit(
                ScreenAuditEntry(
                    timestamp = System.currentTimeMillis(),
                    persona = personaName,
                    question = question,
                    durationMs = System.currentTimeMillis() - started,
                    result = "error",
                    confirmed = "user",
                )
            )
            return FsToolResult(
                call.id, call.name,
                "The screen could not be captured. No screenshot tool is available on this " +
                    "desktop, or the capture failed — nothing was sent to the model.",
                ok = false,
            )
        }
        if (png.size > SCREEN_MAX_CAPTURE_BYTES) {
            audit(
                ScreenAuditEntry(
                    timestamp = System.currentTimeMillis(),
                    persona = personaName,
                    question = question,
                    bytes = png.size,
                    durationMs = System.currentTimeMillis() - started,
                    result = "error",
                    confirmed = "user",
                )
            )
            return FsToolResult(
                call.id, call.name,
                "The screenshot was ${png.size / (1024 * 1024)} MiB, larger than the " +
                    "${SCREEN_MAX_CAPTURE_BYTES / (1024 * 1024)} MiB limit, so it was not sent.",
                ok = false,
            )
        }

        val description = try {
            describe(png, question)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            LogSink.logger.w("Screen", "vision pass failed: ${error.message ?: error::class.simpleName}")
            null
        }
        if (description.isNullOrBlank()) {
            audit(
                ScreenAuditEntry(
                    timestamp = System.currentTimeMillis(),
                    persona = personaName,
                    question = question,
                    bytes = png.size,
                    durationMs = System.currentTimeMillis() - started,
                    result = "error",
                    confirmed = "user",
                )
            )
            return FsToolResult(
                call.id, call.name,
                "The screen was captured, but the vision model did not return a description. " +
                    "The persona's direct Ollama model may not be able to see images.",
                ok = false,
            )
        }

        val capped = description.take(SCREEN_MAX_DESCRIPTION_CHARS)
        val formatted = formatScreenResultText(question, capped, png.size)
        spent += formatted.toByteArray(Charsets.UTF_8).size
        audit(
            ScreenAuditEntry(
                timestamp = System.currentTimeMillis(),
                persona = personaName,
                question = question,
                bytes = png.size,
                durationMs = System.currentTimeMillis() - started,
                result = "ok",
                confirmed = "user",
            )
        )
        return FsToolResult(call.id, call.name, formatted, ok = true)
    }
}
