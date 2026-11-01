package com.nikosm.voiceassistant

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a turn does with a requested screen capture (PLAN.md §10/M19): ask, capture, describe,
 * frame, audit — and refuse. The capture and the vision pass are fakes and the approval an
 * injected answer, so nothing touches the real screen or a model.
 */
class ScreenToolExecutorTest {

    private val png = "fake-png-bytes".toByteArray()

    private class Recorder {
        var captures = 0
        var approvals = mutableListOf<String>()
        var descriptions = mutableListOf<Pair<ByteArray, String>>()
    }

    private fun executor(
        decision: ScreenDecision = ScreenDecision.APPROVED,
        capture: suspend () -> ByteArray? = { png },
        describe: suspend (ByteArray, String) -> String? = { _, _ -> "A browser and a terminal." },
        audits: MutableList<ScreenAuditEntry> = mutableListOf(),
        recorder: Recorder = Recorder(),
    ) = ScreenToolExecutor(
        personaName = "Celeste",
        capture = { recorder.captures++; capture() },
        approve = { question -> recorder.approvals += question; decision },
        describe = { bytes, question -> recorder.descriptions += bytes to question; describe(bytes, question) },
        audit = { audits += it },
    )

    private fun call(question: String? = "What is open?"): FsToolCall {
        val args = if (question == null) JSONObject() else JSONObject().put("question", question)
        return FsToolCall("call-1", TOOL_LOOK_AT_SCREEN, args.toString())
    }

    @Test
    fun `an approved capture is described, framed and audited`() = runTest {
        val audits = mutableListOf<ScreenAuditEntry>()
        val recorder = Recorder()

        val result = executor(audits = audits, recorder = recorder).execute(call())

        assertTrue(result.ok)
        assertTrue(result.text.contains("What is open?"))
        assertTrue(result.text.contains(SCREEN_FRAME_BEGIN))
        assertTrue(result.text.contains("A browser and a terminal."))
        assertEquals(1, recorder.captures)
        assertEquals(listOf("What is open?"), recorder.approvals)
        assertEquals("What is open?", recorder.descriptions.single().second)
        assertTrue(recorder.descriptions.single().first.contentEquals(png))
        assertEquals("ok", audits.single().result)
        assertEquals("user", audits.single().confirmed)
        assertEquals(png.size, audits.single().bytes)
    }

    @Test
    fun `a refusal captures nothing and says so`() = runTest {
        val audits = mutableListOf<ScreenAuditEntry>()
        val recorder = Recorder()

        val result = executor(decision = ScreenDecision.DENIED, audits = audits, recorder = recorder).execute(call())

        assertFalse(result.ok)
        assertTrue(result.text.contains("refused"))
        assertEquals(0, recorder.captures)
        assertTrue(recorder.descriptions.isEmpty())
        assertEquals("denied", audits.single().result)
    }

    @Test
    fun `a timeout captures nothing and says which it was`() = runTest {
        val audits = mutableListOf<ScreenAuditEntry>()
        val recorder = Recorder()

        val result = executor(decision = ScreenDecision.TIMEOUT, audits = audits, recorder = recorder).execute(call())

        assertFalse(result.ok)
        assertTrue(result.text.contains("did not answer"))
        assertEquals(0, recorder.captures)
        assertEquals("timeout", audits.single().result)
        assertEquals("timeout", audits.single().confirmed)
    }

    @Test
    fun `a blank question is replaced by the default before the dialog`() = runTest {
        val recorder = Recorder()

        executor(recorder = recorder).execute(call(question = "   "))

        assertEquals(listOf(SCREEN_DEFAULT_QUESTION), recorder.approvals)
    }

    @Test
    fun `a question longer than the dialog can show is cut before it is asked`() = runTest {
        val recorder = Recorder()

        executor(recorder = recorder).execute(call(question = "x".repeat(SCREEN_MAX_QUESTION_CHARS + 50)))

        assertEquals(SCREEN_MAX_QUESTION_CHARS, recorder.approvals.single().length)
    }

    @Test
    fun `a failed capture is a sentence and an error record`() = runTest {
        val audits = mutableListOf<ScreenAuditEntry>()

        val result = executor(capture = { null }, audits = audits).execute(call())

        assertFalse(result.ok)
        assertTrue(result.text.contains("could not be captured"))
        assertEquals("error", audits.single().result)
        assertEquals(0, audits.single().bytes)
    }

    @Test
    fun `a vision model that returns nothing is reported, not invented`() = runTest {
        val audits = mutableListOf<ScreenAuditEntry>()

        val result = executor(describe = { _, _ -> null }, audits = audits).execute(call())

        assertFalse(result.ok)
        assertTrue(result.text.contains("vision model did not return a description"))
        assertEquals("error", audits.single().result)
        assertEquals(png.size, audits.single().bytes)
    }

    @Test
    fun `a capture above the byte cap is not sent to the model`() = runTest {
        val audits = mutableListOf<ScreenAuditEntry>()
        val recorder = Recorder()

        val result = executor(
            capture = { ByteArray(SCREEN_MAX_CAPTURE_BYTES + 1) },
            audits = audits,
            recorder = recorder,
        ).execute(call())

        assertFalse(result.ok)
        assertTrue(result.text.contains("MiB"))
        assertTrue(recorder.descriptions.isEmpty())
        assertEquals("error", audits.single().result)
    }

    @Test
    fun `one turn cannot spend more than the shared result budget`() = runTest {
        val recorder = Recorder()
        val executor = executor(
            describe = { _, _ -> "d".repeat(SCREEN_MAX_DESCRIPTION_CHARS) },
            recorder = recorder,
        )

        repeat(9) { executor.execute(call()) }

        // Eight 8k-character descriptions cross 64 KiB; the ninth is refused before the dialog.
        assertEquals(8, recorder.approvals.size)
        val last = executor.execute(call())
        assertFalse(last.ok)
        assertTrue(last.text.contains("already used as much"))
        assertEquals("still eight approvals", 8, recorder.approvals.size)
    }
}
