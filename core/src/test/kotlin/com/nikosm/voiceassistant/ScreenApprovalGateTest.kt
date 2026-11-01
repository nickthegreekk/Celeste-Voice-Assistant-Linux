package com.nikosm.voiceassistant

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The screen gate (PLAN.md §10/M19), on virtual time:
 *
 *  - the question the dialog shows is the persona and the model's question;
 *  - an answer applies to that question and only that one;
 *  - dismissal, a superseded turn and a timeout all deny;
 *  - there is no grant that survives the question — a second capture asks again.
 */
class ScreenApprovalGateTest {

    @Test
    fun `an approval answers once and the next capture asks again`() = runTest {
        val gate = ScreenApprovalGate()
        var decision: ScreenDecision? = null
        val job = launch { decision = gate.request("Celeste", "What is open?") }
        runCurrent()

        val pending = gate.pending.value
        assertNotNull("the question must be visible to the UI", pending)
        assertEquals("Celeste", pending!!.personaName)
        assertEquals("What is open?", pending.question)

        gate.answer(pending.id, approve = true)
        runCurrent()
        assertEquals(ScreenDecision.APPROVED, decision)
        assertNull("the question is withdrawn once answered", gate.pending.value)
        job.join()

        var second: ScreenDecision? = null
        launch { second = gate.request("Celeste", "What is open?") }
        runCurrent()
        assertNotNull("every capture asks, even the same question twice", gate.pending.value)
        gate.answer(gate.pending.value!!.id, approve = false)
        runCurrent()
        assertEquals(ScreenDecision.DENIED, second)
    }

    @Test
    fun `a refusal denies and captures nothing`() = runTest {
        val gate = ScreenApprovalGate()
        var decision: ScreenDecision? = null
        launch { decision = gate.request("Celeste", "Read the bank tab") }
        runCurrent()

        gate.answer(gate.pending.value!!.id, approve = false)
        runCurrent()

        assertEquals(ScreenDecision.DENIED, decision)
        assertNull(gate.pending.value)
    }

    @Test
    fun `an unanswered question denies itself on timeout`() = runTest {
        val gate = ScreenApprovalGate()
        var decision: ScreenDecision? = null
        launch { decision = gate.request("Celeste", "What is open?") }
        runCurrent()
        assertNotNull(gate.pending.value)

        advanceTimeBy(SCREEN_APPROVAL_TIMEOUT_MS + 1)
        runCurrent()

        assertEquals(ScreenDecision.TIMEOUT, decision)
        assertNull(gate.pending.value)
    }

    @Test
    fun `a superseded turn denies the pending capture`() = runTest {
        val gate = ScreenApprovalGate()
        var decision: ScreenDecision? = null
        launch { decision = gate.request("Celeste", "What is open?") }
        runCurrent()

        gate.denyPending()
        runCurrent()

        assertEquals(ScreenDecision.DENIED, decision)
        assertNull(gate.pending.value)
    }

    @Test
    fun `an answer for a different question is ignored`() = runTest {
        val gate = ScreenApprovalGate()
        var decision: ScreenDecision? = null
        launch { decision = gate.request("Celeste", "What is open?") }
        runCurrent()
        val pending = gate.pending.value!!

        gate.answer(pending.id + 1, approve = true)
        runCurrent()
        assertNull("a stale dialog must not answer the current question", decision)
        assertNotNull(gate.pending.value)

        gate.answer(pending.id, approve = true)
        runCurrent()
        assertEquals(ScreenDecision.APPROVED, decision)
    }
}
