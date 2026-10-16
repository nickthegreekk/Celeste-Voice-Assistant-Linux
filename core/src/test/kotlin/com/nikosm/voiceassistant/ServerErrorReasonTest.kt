package com.nikosm.voiceassistant

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The reasons a server gives, against the bodies that were actually measured on 2026-10-16 — the
 * ones that made this worth writing.
 *
 * The two that matter are verbatim from real responses: Ollama's 400 for an image sent to a
 * text-only model (nested inside a JSON *string* under `error`), and the SearXNG instance's 400
 * for an empty query. Both were reported as a bare number before this, on a run where two 400s
 * landed in the same second and neither message could be told from the other.
 *
 * The refusals are asserted as carefully as the extractions: a body that is not JSON, or is an
 * HTML page, must contribute **nothing**, because this message is not DEBUG-gated and a raw body
 * can echo the user's own conversation back (the S4 note in AssistantServiceChat.kt).
 */
class ServerErrorReasonTest {

    @Test
    fun `ollama's nested error string is unwrapped to its message`() {
        // Measured: POST /api/chat with an image against a text-only GGUF on 5800x.
        val body = """{"error":"{\"error\":{\"code\":400,\"message\":\"Multimodal data provided, but model does not support multimodal requests.\"}}"}"""

        assertEquals(
            "Multimodal data provided, but model does not support multimodal requests.",
            serverErrorReason(body),
        )
    }

    @Test
    fun `an unknown model's reason is carried through`() {
        // Also measured — and note it is a 404, which is why "a 400 must be a missing model"
        // was the wrong guess when the incident was diagnosed.
        assertEquals("model 'nope:0b' not found", serverErrorReason("""{"error":"model 'nope:0b' not found"}"""))
    }

    @Test
    fun `searxng's empty-query reason is carried through`() {
        assertEquals("No query", serverErrorReason("""{"error": "No query"}"""))
    }

    @Test
    fun `a fastapi gateway's detail field is read too`() {
        assertEquals("Not Found", serverErrorReason("""{"detail":"Not Found"}"""))
    }

    @Test
    fun `a plain-text body contributes nothing`() {
        // The refusal that keeps a chat bubble and journald free of a server's raw echo.
        assertNull(serverErrorReason("<html><body><h1>502 Bad Gateway</h1></body></html>"))
        assertNull(serverErrorReason("something went wrong"))
    }

    @Test
    fun `a missing or empty body contributes nothing`() {
        assertNull(serverErrorReason(null))
        assertNull(serverErrorReason(""))
        assertNull(serverErrorReason("{}"))
    }

    @Test
    fun `a long reason is truncated to one line`() {
        val reason = serverErrorReason("""{"error":"${"x".repeat(500)}\nsecond line"}""")!!

        assertEquals(SERVER_REASON_MAX_CHARS, reason.length)
        assertEquals(true, reason.endsWith("…"))
        assertEquals(false, reason.contains("\n"))
    }

    @Test
    fun `a multi-line reason is flattened`() {
        assertEquals("first line second line", serverErrorReason("""{"message":"first line\n   second line"}"""))
    }

    @Test
    fun `the message a user reads carries the code and the reason, or the code alone`() {
        fun response(code: Int, body: String) = okhttp3.Response.Builder()
            .code(code).message("Bad Request").protocol(okhttp3.Protocol.HTTP_1_1)
            .request(okhttp3.Request.Builder().url("http://server.invalid/").build())
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

        assertEquals(
            "Ollama error: 400 — Multimodal data provided, but model does not support multimodal requests.",
            serverFailure("Ollama error", response(400, """{"error":"Multimodal data provided, but model does not support multimodal requests."}""")),
        )
        // Unreadable bodies keep the status-only message rather than a raw dump.
        assertEquals(
            "Server error: 502",
            serverFailure("Server error", response(502, "<html>bad gateway</html>")),
        )
    }
}
