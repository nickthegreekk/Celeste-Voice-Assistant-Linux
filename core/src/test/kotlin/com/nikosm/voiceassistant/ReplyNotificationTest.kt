package com.nikosm.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reply notification's body rule (PLAN.md §10/M14): what a settled turn puts on the desktop.
 *
 * The decision of *when* to notify lives in the service's state collector and is judged live; this
 * pins what is posted once it does — one line, capped, an image-only reply said in words, and never
 * a user turn. Errors are replies too: an error arriving while the window is away is precisely what
 * the user needs to see.
 */
class ReplyNotificationTest {

    @Test
    fun `a reply becomes one line`() {
        val body = replyNotificationBody(ChatMessage(role = "assistant", text = "line one\n\nline   two"))!!

        assertEquals("line one line two", body)
    }

    @Test
    fun `a long reply is cut with an ellipsis`() {
        val body = replyNotificationBody(ChatMessage(role = "assistant", text = "x".repeat(500)))!!

        assertEquals(REPLY_NOTIFICATION_CHARS + 1, body.length)
        assertTrue(body.endsWith("…"))
    }

    @Test
    fun `an image-only reply says so`() {
        val body = replyNotificationBody(
            ChatMessage(role = "assistant", text = "  ", generatedImagePath = "/tmp/generated.png"),
        )

        assertEquals("Sent an image.", body)
    }

    @Test
    fun `a blank reply with nothing attached is not a notification`() {
        assertNull(replyNotificationBody(ChatMessage(role = "assistant", text = "   ")))
    }

    @Test
    fun `a user turn is never a notification`() {
        assertNull(replyNotificationBody(ChatMessage(role = "user", text = "hello")))
    }

    @Test
    fun `an error is a reply and keeps its sentence`() {
        val body = replyNotificationBody(
            ChatMessage(role = "assistant", text = "Error: the gateway is unreachable", isError = true),
        )

        assertEquals("Error: the gateway is unreachable", body)
    }
}
