package com.nikosm.voiceassistant

import okhttp3.Response

/**
 * The reason a server refused, taken from the server's own body.
 *
 * A status code alone is not a diagnosis, and the servers this application talks to all explain
 * themselves in the body: Ollama answers a text-only model sent an image with
 * `400 {"error":"Multimodal data provided, but model does not support multimodal requests."}`, and
 * an unknown model with `404 {"error":"model 'x' not found"}`; a SearXNG instance answers an empty
 * query with `400 {"error":"No query"}`; a FastAPI gateway answers with `{"detail":"Not Found"}`.
 * Reporting only the code turned every one of those into a bare number in the chat bubble and the
 * log — found on 2026-10-16, when a 400 shared a timestamp with an unrelated 400 and neither could
 * be told apart *from its own message*.
 *
 * ## The reason is a FIELD, not the body
 *
 * Deliberately not "the first 200 characters of the response": the suggestion paths in
 * `AssistantServiceChat.kt` carry an S4 note about exactly that — a server's raw error body can
 * echo the user's conversation back (it was the clue for a llama.cpp long-prompt crash), and that
 * is why they log it at DEBUG only. This message is not DEBUG-gated; it is shown to the user *and*
 * written to the log. So only the server's own error field is read, and a body that is not JSON
 * with a recognisable field contributes **nothing** — the status stands alone exactly as it did
 * before. Full bodies keep the DEBUG gate they already have.
 *
 * Truncation is part of the contract rather than politeness: an engine dumping a stack trace into
 * `error` must not push a screenful into a chat bubble. Whitespace is collapsed so a multi-line
 * reason stays one line in a log and a bubble.
 */
internal const val SERVER_REASON_MAX_CHARS = 200

/**
 * The engine's own words, or null when the body does not carry them.
 *
 * The shape is whatever the server felt like, and every one of these was observed:
 * `{"error":"No query"}` (a string), `{"detail":"Not Found"}` (a different key), and Ollama's
 * `{"error":"{\"error\":{\"code\":400,\"message\":\"…\"}}"}` — a JSON *document* inside a string,
 * whose `error` is then an *object*. So the walk is structural: prefer `error`, then `message`,
 * then `detail`; unwrap a value that is itself a JSON object; and treat a string that parses as
 * JSON as one more layer. The nested form is unwrapped rather than shown because on screen it is
 * unreadable, which is the whole point of surfacing it.
 */
internal fun serverErrorReason(body: String?): String? {
    val raw = body?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val json = runCatching { org.json.JSONObject(raw) }.getOrNull() ?: return null
    return oneLine(reasonFrom(json) ?: return null)
}

private fun reasonFrom(value: Any?, depth: Int = 0): String? {
    if (depth > 4) return null
    return when (value) {
        is String -> {
            val text = value.trim()
            when {
                text.isEmpty() -> null
                else -> runCatching { org.json.JSONObject(text) }.getOrNull()
                    ?.let { reasonFrom(it, depth + 1) }
                    ?: text
            }
        }
        is org.json.JSONObject ->
            REASON_KEYS.firstNotNullOfOrNull { key ->
                if (value.has(key)) reasonFrom(value.opt(key), depth + 1) else null
            }
        else -> null
    }
}

/** The keys servers in this project's world use, in the order they are worth preferring. */
private val REASON_KEYS = listOf("error", "message", "detail")

/**
 * `"<prefix>: <code>"`, plus the server's own reason when it gave one —
 * `"Ollama error: 400 — Multimodal data provided, but model does not support multimodal requests."`
 *
 * Takes the [Response] rather than a body string so call sites stay one line, and reads the body
 * here: every call site reaches this only when the response failed, and none of them reads the
 * body afterwards.
 */
internal fun serverFailure(prefix: String, response: Response): String {
    val reason = serverErrorReason(runCatching { response.body.string() }.getOrNull())
    return if (reason == null) "$prefix: ${response.code}" else "$prefix: ${response.code} — $reason"
}

/**
 * A server's words as one line, capped at [SERVER_REASON_MAX_CHARS].
 *
 * `internal` rather than private because it is the same promise wherever a server gets to speak in
 * a message the user reads or journald keeps — `gatewayRefusal` in `AssistantServiceGateway.kt`
 * carries the gateway's own `note` through it, and a second copy of "flatten and cap" is how the
 * two would drift apart.
 */
internal fun oneLine(s: String): String? {
    val flat = s.replace(Regex("\\s+"), " ").trim().trimEndToSentenceEnd()
    if (flat.isEmpty()) return null
    return if (flat.length <= SERVER_REASON_MAX_CHARS) flat
    else flat.take(SERVER_REASON_MAX_CHARS - 1).trimEnd() + "…"
}

/** Drops a trailing `".`/`","` left by a reason that was quoted inside something else. */
private fun String.trimEndToSentenceEnd(): String = trimEnd('"', ',', ';').trim()
