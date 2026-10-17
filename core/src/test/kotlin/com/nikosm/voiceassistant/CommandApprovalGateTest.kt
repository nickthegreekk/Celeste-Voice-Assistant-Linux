package com.nikosm.voiceassistant

import java.nio.file.Path
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The command gate (PLAN.md §10/M16). The rules, on virtual time:
 *
 *  - an answer applies to the question it was given for, and only that one;
 *  - a session grant covers exactly one command line in one directory;
 *  - dismissal, a superseded turn and a timeout all deny;
 *  - a trusted command skips the dialog but is still reported as a session decision.
 */
class CommandApprovalGateTest {

    private val root = Path.of("/tmp/celia-workspace")

    @Test
    fun `allow once answers once, and the next ask asks again`() = runTest {
        val gate = CommandApprovalGate()
        var decision: CommandDecision? = null
        val job = launch { decision = gate.request("Celeste", "echo hi", null, false, root) }
        runCurrent()

        val pending = gate.pending.value
        assertNotNull("the question must be visible to the UI", pending)
        assertEquals("echo hi", pending!!.command)

        gate.answer(pending.id, approve = true, trustForSession = false)
        runCurrent()

        assertEquals(CommandDecision.ONCE, decision)
        assertNull("the question is withdrawn once answered", gate.pending.value)
        assertFalse("once is not a session grant", gate.trusts("echo hi", null, false, root))
        job.join()
    }

    @Test
    fun `a session grant covers that exact command in that directory and nothing else`() = runTest {
        val gate = CommandApprovalGate()
        var first: CommandDecision? = null
        launch { first = gate.request("Celeste", "echo hi", null, false, root) }
        runCurrent()
        gate.answer(gate.pending.value!!.id, approve = true, trustForSession = true)
        runCurrent()
        assertEquals(CommandDecision.SESSION, first)

        // The same command later does not ask at all and reports itself as trusted.
        var second: CommandDecision? = null
        launch { second = gate.request("Celeste", "echo hi", null, false, root) }
        runCurrent()
        assertEquals(CommandDecision.SESSION, second)
        assertNull("a trusted command never opens the dialog", gate.pending.value)

        // A different command, or the same one elsewhere, asks again.
        assertFalse(gate.trusts("echo bye", null, false, root))
        assertFalse(gate.trusts("echo hi", null, false, Path.of("/tmp/other")))
    }

    @Test
    fun `a session grant does not cover the same command with different input`() = runTest {
        val gate = CommandApprovalGate()
        launch { gate.request("Celeste", "./ask.sh", "yes\n", false, root) }
        runCurrent()
        gate.answer(gate.pending.value!!.id, approve = true, trustForSession = true)
        runCurrent()

        assertTrue(gate.trusts("./ask.sh", "yes\n", false, root))
        assertFalse("a different answer is a different action", gate.trusts("./ask.sh", "no\n", false, root))
        assertFalse("and so is no input at all", gate.trusts("./ask.sh", null, false, root))

        var decision: CommandDecision? = null
        launch { decision = gate.request("Celeste", "./ask.sh", "no\n", false, root) }
        runCurrent()
        assertNotNull("the new input must ask again", gate.pending.value)
        gate.answer(gate.pending.value!!.id, approve = false)
        runCurrent()
        assertEquals(CommandDecision.DENIED, decision)
    }

    @Test
    fun `a session grant does not cover the same command run with a terminal`() = runTest {
        val gate = CommandApprovalGate()
        launch { gate.request("Celeste", "ls", null, false, root) }
        runCurrent()
        gate.answer(gate.pending.value!!.id, approve = true, trustForSession = true)
        runCurrent()

        assertTrue(gate.trusts("ls", null, false, root))
        assertFalse("a PTY changes what the program does", gate.trusts("ls", null, true, root))
    }

    @Test
    fun `a refusal denies and runs nothing`() = runTest {
        val gate = CommandApprovalGate()
        var decision: CommandDecision? = null
        launch { decision = gate.request("Celeste", "rm -rf build", null, false, root) }
        runCurrent()

        gate.answer(gate.pending.value!!.id, approve = false)
        runCurrent()

        assertEquals(CommandDecision.DENIED, decision)
        assertNull(gate.pending.value)
    }

    @Test
    fun `an unanswered question denies itself on timeout`() = runTest {
        val gate = CommandApprovalGate()
        var decision: CommandDecision? = null
        launch { decision = gate.request("Celeste", "sleep 10", null, false, root) }
        runCurrent()
        assertNotNull(gate.pending.value)

        advanceTimeBy(COMMAND_APPROVAL_TIMEOUT_MS + 1)
        runCurrent()

        assertEquals(CommandDecision.TIMEOUT, decision)
        assertNull(gate.pending.value)
    }

    @Test
    fun `a superseded turn denies the pending command`() = runTest {
        val gate = CommandApprovalGate()
        var decision: CommandDecision? = null
        launch { decision = gate.request("Celeste", "make", null, false, root) }
        runCurrent()

        gate.denyPending()
        runCurrent()

        assertEquals(CommandDecision.DENIED, decision)
        assertNull(gate.pending.value)
    }

    @Test
    fun `an answer for a different question is ignored`() = runTest {
        val gate = CommandApprovalGate()
        var decision: CommandDecision? = null
        launch { decision = gate.request("Celeste", "make", null, false, root) }
        runCurrent()
        val pending = gate.pending.value!!

        gate.answer(pending.id + 1, approve = true)
        runCurrent()
        assertNull("a stale dialog must not answer the current question", decision)
        assertTrue(gate.pending.value != null)

        gate.answer(pending.id, approve = true)
        runCurrent()
        assertEquals(CommandDecision.ONCE, decision)
    }

    @Test
    fun `forgetting trust makes the next ask a question again`() = runTest {
        val gate = CommandApprovalGate()
        launch { gate.request("Celeste", "make", null, false, root) }
        runCurrent()
        gate.answer(gate.pending.value!!.id, approve = true, trustForSession = true)
        runCurrent()
        assertTrue(gate.trusts("make", null, false, root))

        gate.forgetTrust()

        assertFalse(gate.trusts("make", null, false, root))
    }
}
