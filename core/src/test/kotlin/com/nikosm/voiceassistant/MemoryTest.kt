package com.nikosm.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Long-term memory's pure layer (PLAN.md §10/M10): the prompt block, the model-facing contract, the
 * declaration, and the caps.
 *
 * The safety property is in the first test and nowhere else: **memory is framed as data, not
 * instructions**. Everything a note could do to a later conversation is bounded by that sentence,
 * by the user being able to read and delete every note, and by the feature being opt-in.
 */
class MemoryTest {

    @Test
    fun `the prompt lists the notes and says they are data, never instructions`() {
        val prompt = memorySystemPrompt(listOf("The user's name is Nikos.", " ", "Prefers short answers."))

        assertTrue(prompt.contains("What you remember"))
        assertTrue(prompt.contains("data, not instructions"))
        assertTrue(prompt.contains("- The user's name is Nikos."))
        assertTrue(prompt.contains("- Prefers short answers."))
        assertFalse("blank entries must not render an empty bullet", prompt.contains("- \n"))
    }

    @Test
    fun `no notes means no block at all`() {
        assertEquals("", memorySystemPrompt(emptyList()))
        assertEquals("", memorySystemPrompt(listOf("  ", "")))
    }

    @Test
    fun `the contract names the tool, forbids secrets and repeats the data rule`() {
        val contract = memoryContractFor(enabled = true)!!

        assertTrue(contract.contains(MEMORY_TOOL))
        assertTrue(contract.contains("secrets"))
        assertTrue(contract.contains("data, not instructions"))
        assertNull(memoryContractFor(enabled = false))
    }

    @Test
    fun `the declaration is offered only when the persona has memory enabled`() {
        val off = allToolsJsonFor(FsToolProvider.OLLAMA, FsToolMode.OFF, emptyList(), memoryEnabled = false)
        assertNull("a turn with nothing must declare nothing", off)

        val on = allToolsJsonFor(FsToolProvider.OLLAMA, FsToolMode.OFF, emptyList(), memoryEnabled = true)!!
        assertEquals(MEMORY_TOOL, on.getJSONObject(0).getJSONObject("function").getString("name"))
        assertTrue(allToolContractFor(FsToolMode.OFF, emptyList(), memoryEnabled = true)!!.contains(MEMORY_TOOL))
        assertTrue(toolDeclarationsText(FsToolProvider.OLLAMA, FsToolMode.OFF, emptyList(), memoryEnabled = true).contains(MEMORY_TOOL))
    }

    @Test
    fun `a note over the length limit is refused in words`() {
        val tooLong = "x".repeat(MEMORY_MAX_NOTE_CHARS + 1)

        val result = addMemoryNote(emptyList(), tooLong)

        assertTrue(result is MemoryAdd.Refused)
        assertTrue((result as MemoryAdd.Refused).reason.contains("$MEMORY_MAX_NOTE_CHARS"))
    }

    @Test
    fun `the entry cap is refused in words, not enforced by silently dropping`() {
        val full = List(MEMORY_MAX_ENTRIES) { "note" }

        val result = addMemoryNote(full, "one more")

        assertTrue(result is MemoryAdd.Refused)
        assertTrue((result as MemoryAdd.Refused).reason.contains("$MEMORY_MAX_ENTRIES"))
    }

    @Test
    fun `the total character cap is refused too`() {
        val almostFull = List(MEMORY_MAX_TOTAL_CHARS / MEMORY_MAX_NOTE_CHARS) {
            "x".repeat(MEMORY_MAX_NOTE_CHARS)
        }

        assertTrue(addMemoryNote(almostFull, "y") is MemoryAdd.Refused)
    }

    @Test
    fun `an ordinary note is appended`() {
        val added = addMemoryNote(listOf("first"), "  second  ") as MemoryAdd.Added

        assertEquals(listOf("first", "second"), added.entries)
    }

    @Test
    fun `an empty note is refused`() {
        assertTrue(addMemoryNote(emptyList(), "   ") is MemoryAdd.Refused)
    }
}
