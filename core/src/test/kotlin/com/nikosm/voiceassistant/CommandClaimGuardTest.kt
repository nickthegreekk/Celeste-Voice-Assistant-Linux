package com.nikosm.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The claim guard (PLAN.md §10/M16.3 item 5): the app's own check that a reply reporting a command
 * result came from a turn that actually called `run_command`.
 *
 * The two true cases are the replies that motivated the guard; the false cases are the ordinary
 * ways an answer can mention commands without claiming to have run one, which must stay unflagged.
 */
class CommandClaimGuardTest {

    private val commandsOffered = TurnContext(commandEnabled = true)

    @Test
    fun `a fabricated successful summary is a claim`() {
        val reply = "The `seq` command generated a sequence from 1 to 100,000 — that's 100,000 " +
            "lines of output. The command ran successfully and printed all numbers from 1 to 100,000."

        assertTrue(claimsCommandResult(reply))
    }

    @Test
    fun `a fabricated timeout is a claim`() {
        val reply = "The `seq` command did NOT work. Command invoked: `seq 1 100000`. " +
            "Result: TIMED OUT / no output returned to me. The command failed to produce usable output."

        assertTrue(claimsCommandResult(reply))
    }

    @Test
    fun `a report on the command I ran is a claim`() {
        val reply = "Here's my report on the command I ran (`dmesg`):\n\n" +
            "**Command:** `dmesg`\n\n**Result:**\n```\n[ 0.000000] Linux version 6.1.0-...\n```"

        assertTrue(claimsCommandResult(reply))
    }

    @Test
    fun `a backticked command after I ran is a claim`() {
        assertTrue(claimsCommandResult("I ran `dmesg` and here is what it printed."))
    }

    @Test
    fun `ordinary mentions and future intent are not claims`() {
        assertFalse(claimsCommandResult("You can run a command in the terminal."))
        assertFalse(claimsCommandResult("I can't run commands for this persona."))
        assertFalse(claimsCommandResult("I will run the command now."))
        assertFalse(claimsCommandResult("The command is not available."))
        assertFalse(claimsCommandResult("An exit code tells you whether a program succeeded."))
        assertFalse(claimsCommandResult("The function returned the output."))
        assertFalse(claimsCommandResult("The output was not returned by that function in the example."))
        assertFalse(claimsCommandResult("I ran into a problem with the command."))
        assertFalse(claimsCommandResult("I ran the tests."))
    }

    @Test
    fun `the note needs an offered command tool, no call, and a claim`() {
        val claim = "The command ran successfully."

        assertNull("commands were not offered", commandClaimNote(claim, TurnContext(commandEnabled = false)))
        assertNull(
            "run_command was called",
            commandClaimNote(claim, commandsOffered.copy(toolsUsed = setOf(TOOL_RUN_COMMAND))),
        )
        assertNull("no claim", commandClaimNote("No command was involved.", commandsOffered))
        assertEquals(COMMAND_CLAIM_NOTE, commandClaimNote(claim, commandsOffered))
    }

    @Test
    fun `the note is appended only when the guard fires`() {
        val claim = "The command ran successfully."

        assertTrue(appendCommandClaimNote(claim, commandsOffered).endsWith(COMMAND_CLAIM_NOTE))
        assertEquals(
            claim,
            appendCommandClaimNote(claim, commandsOffered.copy(toolsUsed = setOf(TOOL_RUN_COMMAND))),
        )
    }
}
