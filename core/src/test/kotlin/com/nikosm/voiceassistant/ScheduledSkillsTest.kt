package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scheduled skills — the pure half (PLAN.md §10/M15).
 *
 * The two ways this feature can quietly misbehave are both arithmetic: running an occurrence
 * twice (a 30-second poll sees the same minute six times) and replaying one that was missed (an
 * application opened at noon delivering the 08:00 briefing). The grace window and the
 * once-per-occurrence bookkeeping are pinned here on virtual instants instead of by waiting.
 */
class ScheduledSkillsTest {

    private val zone = ZoneId.of("Europe/Athens")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun schedule(
        id: String = "sched-1",
        skillId: String = "skill-1",
        personaName: String = "Friend",
        minuteOfDay: Int = 8 * 60,
        enabled: Boolean = true
    ) = ScheduledSkill(
        id = id,
        skillId = skillId,
        personaName = personaName,
        minuteOfDay = minuteOfDay,
        enabled = enabled
    )

    private fun skill(instructions: String = "Summarise the day in three lines.") = Skill(
        id = "skill-1",
        name = "Morning briefing",
        triggerPhrases = listOf("brief me"),
        instructions = instructions
    )

    private fun persona(
        name: String = "Friend",
        model: String = "[Home] llama3",
        isCloud: Boolean = false,
        isTranslator: Boolean = false,
        isImageGenerator: Boolean = false
    ) = Persona(
        name = name,
        themeColor = Color(0xFF4ADE80),
        model = model,
        systemPrompt = "You are a warm friend.",
        isCloud = isCloud,
        isTranslator = isTranslator,
        isImageGenerator = isImageGenerator,
    )

    // ------------------------------------------------------------------ the editor's time field

    @Test
    fun `a time parses from HH MM with an optional single-digit hour`() {
        assertEquals(8 * 60, parseScheduledTime("08:00"))
        assertEquals(8 * 60 + 5, parseScheduledTime("8:05"))
        assertEquals(23 * 60 + 59, parseScheduledTime(" 23:59 "))
        assertEquals(0, parseScheduledTime("00:00"))
    }

    @Test
    fun `out-of-range and malformed times do not parse`() {
        // The editor writes nothing while this returns null, so "24:00" must never silently
        // become midnight of the next day and "8" must never become 08:00.
        assertNull(parseScheduledTime(""))
        assertNull(parseScheduledTime("8"))
        assertNull(parseScheduledTime("08:0"))
        assertNull(parseScheduledTime("24:00"))
        assertNull(parseScheduledTime("08:60"))
        assertNull(parseScheduledTime("-1:00"))
        assertNull(parseScheduledTime("08:00:00"))
        assertNull(parseScheduledTime("later"))
    }

    @Test
    fun `a stored minute renders back as HH MM`() {
        assertEquals("08:00", formatScheduledTime(8 * 60))
        assertEquals("00:05", formatScheduledTime(5))
        assertEquals("23:59", formatScheduledTime(23 * 60 + 59))
    }

    // ------------------------------------------------------------------ occurrence arithmetic

    @Test
    fun `the next occurrence is strictly after now`() {
        val now = at(2026, 10, 17, 8, 0)

        // At the exact instant the occurrence is "this one", already handled — the next is
        // tomorrow, which is what stops a run from arming itself again for the same minute.
        assertEquals(at(2026, 10, 18, 8, 0), nextScheduledOccurrence(now, zone, 8 * 60))
        assertEquals(at(2026, 10, 17, 9, 0), nextScheduledOccurrence(now, zone, 9 * 60))
        assertEquals(at(2026, 10, 18, 7, 0), nextScheduledOccurrence(now, zone, 7 * 60))
    }

    @Test
    fun `next occurrence crosses midnight without losing a day`() {
        val late = at(2026, 10, 17, 23, 30)

        assertEquals(at(2026, 10, 18, 0, 15), nextScheduledOccurrence(late, zone, 15))
        assertEquals(at(2026, 10, 18, 8, 0), nextScheduledOccurrence(late, zone, 8 * 60))
    }

    @Test
    fun `an occurrence is due at its instant and within the grace, and missed after it`() {
        val occurrence = at(2026, 10, 17, 8, 0)
        val grace = SCHEDULED_SKILL_LATE_GRACE_MS

        assertEquals(occurrence, dueScheduledOccurrence(occurrence, zone, 8 * 60))
        assertEquals(
            occurrence,
            dueScheduledOccurrence(occurrence + grace, zone, 8 * 60)
        )
        assertNull("half a second before the time is not due", dueScheduledOccurrence(occurrence - 500, zone, 8 * 60))
        assertNull("past the grace is a missed occurrence, never a replay", dueScheduledOccurrence(occurrence + grace + 1, zone, 8 * 60))
        assertNull("an old occurrence is not replayed at noon", dueScheduledOccurrence(at(2026, 10, 17, 12, 0), zone, 8 * 60))
    }

    @Test
    fun `the due set runs each occurrence once`() {
        val now = at(2026, 10, 17, 8, 0) + 10_000
        val schedules = listOf(schedule())

        val first = dueScheduledSkills(schedules, emptyMap(), now, zone)
        assertEquals(1, first.size)
        val occurrence = first.single().second

        // The same poll an instant later — and the one after — must not run it again.
        assertTrue(dueScheduledSkills(schedules, mapOf("sched-1" to occurrence), now + 20_000, zone).isEmpty())
        // A run recorded for an OLDER occurrence does not block today's.
        assertEquals(1, dueScheduledSkills(schedules, mapOf("sched-1" to occurrence - 86_400_000), now, zone).size)
    }

    @Test
    fun `disabled schedules are never due`() {
        val now = at(2026, 10, 17, 8, 0)
        assertTrue(dueScheduledSkills(listOf(schedule(enabled = false)), emptyMap(), now, zone).isEmpty())
    }

    @Test
    fun `two schedules at the same minute are both due`() {
        val now = at(2026, 10, 17, 8, 0)
        val due = dueScheduledSkills(
            listOf(schedule(id = "a", skillId = "s1"), schedule(id = "b", skillId = "s2")),
            emptyMap(),
            now,
            zone
        )

        assertEquals(setOf("a", "b"), due.map { it.first.id }.toSet())
    }

    // ------------------------------------------------------------------ eligibility

    @Test
    fun `a direct Ollama persona is eligible`() {
        val eligibility = scheduledSkillEligibility(persona(), setOf("Home"), setOf("Cloud"))

        assertEquals(RoomEligibility.Eligible(cloud = false), eligibility)
    }

    @Test
    fun `cloud gateway image translator and no-model personas are blocked with a reason`() {
        val cloud = scheduledSkillEligibility(
            persona(model = "[Cloud] model", isCloud = true), setOf("Home"), setOf("Cloud")
        )
        val gateway = scheduledSkillEligibility(persona(model = "llama3"), setOf("Home"), setOf("Cloud"))
        val image = scheduledSkillEligibility(persona(isImageGenerator = true), setOf("Home"), setOf("Cloud"))
        val translator = scheduledSkillEligibility(persona(isTranslator = true), setOf("Home"), setOf("Cloud"))
        val noModel = scheduledSkillEligibility(persona(model = ""), setOf("Home"), setOf("Cloud"))

        assertEquals(RoomEligibility.Blocked(SCHEDULED_CLOUD_BLOCK_REASON), cloud)
        assertEquals(RoomEligibility.Blocked(SCHEDULED_GATEWAY_BLOCK_REASON), gateway)
        assertEquals(RoomEligibility.Blocked(SCHEDULED_IMAGE_BLOCK_REASON), image)
        assertEquals(RoomEligibility.Blocked(SCHEDULED_TRANSLATOR_BLOCK_REASON), translator)
        assertEquals(RoomEligibility.Blocked("No model selected"), noModel)
        assertTrue((cloud as RoomEligibility.Blocked).reason.contains("cloud"))
        assertTrue((gateway as RoomEligibility.Blocked).reason.contains("gateway"))
    }

    // ------------------------------------------------------------------ copy

    @Test
    fun `the summary names the time, the persona and an off state`() {
        assertEquals(
            "Every day at 08:00 as Friend",
            scheduledSkillSummary(schedule(), listOf("Friend"))
        )
        assertEquals(
            "Every day at 08:00 as Friend — off",
            scheduledSkillSummary(schedule(enabled = false), listOf("Friend"))
        )
        assertEquals(
            "Every day at 08:00 as 'Gone' (missing)",
            scheduledSkillSummary(schedule(personaName = "Gone"), listOf("Friend"))
        )
        assertEquals(
            "Every day at 08:00 as no persona",
            scheduledSkillSummary(schedule(personaName = ""), listOf("Friend"))
        )
        assertEquals("Not scheduled", scheduledSkillSummary(null, listOf("Friend")))
    }

    @Test
    fun `a skill row only reports the schedule that belongs to it`() {
        val schedules = listOf(schedule(id = "a", skillId = "skill-1"), schedule(id = "b", skillId = "skill-2"))

        assertEquals(
            "Every day at 08:00 as Friend",
            skillScheduleSummary("skill-1", schedules, listOf("Friend"))
        )
        assertEquals("Not scheduled", skillScheduleSummary("skill-3", schedules, listOf("Friend")))
    }

    // ------------------------------------------------------------------ the framing

    @Test
    fun `the system suffix carries the skill's instructions and the silence sentinel`() {
        val suffix = scheduledSkillSystemPromptSuffix(skill(), persona("Mabel"))

        assertTrue(suffix.contains("Morning briefing"))
        assertTrue(suffix.contains("Summarise the day in three lines."))
        assertTrue(suffix.contains("Mabel"))
        assertTrue(suffix.contains(SPONTANEOUS_SILENCE_SENTINEL))
        assertTrue(
            "the silence instruction must come after the skill's own text",
            suffix.indexOf("Summarise the day") < suffix.indexOf(SPONTANEOUS_SILENCE_SENTINEL)
        )
    }

    @Test
    fun `the turn instruction names the skill and the persona and permits silence`() {
        val instruction = scheduledSkillTurnInstruction(skill(), persona("Mabel"))

        assertTrue(instruction.contains("Morning briefing"))
        assertTrue(instruction.contains("Mabel"))
        assertTrue(instruction.contains("ONE"))
        assertTrue(instruction.contains(SPONTANEOUS_SILENCE_SENTINEL))
    }

    @Test
    fun `blank skill instructions contribute nothing to the framing`() {
        val suffix = scheduledSkillSystemPromptSuffix(skill(instructions = "   "), persona())

        assertTrue(suffix.isNotBlank())
        assertTrue("no empty instruction block", !suffix.contains("Skill instructions:\n\n"))
    }
}
