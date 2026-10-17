package com.nikosm.voiceassistant

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MCP confirmation's state machine, driven directly — no service, no dialog.
 *
 * The rules are the filesystem write gate's, and they are the security-relevant half: **dismissal
 * denies, an unanswered question times out to deny, and a newer turn denies whatever was pending**,
 * so a stale dialog can never approve a call. Trust is the one answer that is remembered, and the
 * gate itself only reports it; the service persists it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class McpApprovalGateTest {

    private fun request() = McpApprovalRequest(
        serverId = "home",
        serverName = "Home",
        toolName = "mcp_home_set_light",
        arguments = """{"on":true}""",
        personaName = "Celeste",
    )

    /** Start a request and let it suspend, returning the running job and the gate. */
    private fun kotlinx.coroutines.test.TestScope.ask(gate: McpApprovalGate, timeoutMs: Long = 60_000) =
        async { gate.request(request(), timeoutMs) }

    @Test
    fun `allow and trust are distinct answers`() = runTest {
        val allowGate = McpApprovalGate()
        val allow = ask(allowGate)
        runCurrent()
        allowGate.answer(allow = true)
        assertEquals(McpApprovalDecision.ALLOW, allow.await())
        assertNull(allowGate.pending.value)

        val trustGate = McpApprovalGate()
        val trust = ask(trustGate)
        runCurrent()
        trustGate.answer(allow = true, trustServer = true)
        assertEquals(McpApprovalDecision.TRUST, trust.await())
    }

    @Test
    fun `a refusal is a denial`() = runTest {
        val gate = McpApprovalGate()
        val answer = ask(gate)
        runCurrent()

        gate.answer(allow = false)

        assertEquals(McpApprovalDecision.DENIED, answer.await())
    }

    @Test
    fun `a newer turn denies whatever was pending`() = runTest {
        val gate = McpApprovalGate()
        val answer = ask(gate)
        runCurrent()
        assertTrue("the question should be on screen for the UI", gate.pending.value != null)

        gate.denyPending()

        assertEquals(McpApprovalDecision.DENIED, answer.await())
    }

    @Test
    fun `an unanswered question times out to deny`() = runTest {
        val gate = McpApprovalGate()

        val answer = ask(gate, timeoutMs = 50)
        runCurrent()

        assertEquals(McpApprovalDecision.TIMEOUT, answer.await())
        assertNull(gate.pending.value)
    }

    @Test
    fun `an answer with nothing pending is ignored`() {
        val gate = McpApprovalGate()

        gate.answer(allow = true, trustServer = true)

        assertNull(gate.pending.value)
    }
}
