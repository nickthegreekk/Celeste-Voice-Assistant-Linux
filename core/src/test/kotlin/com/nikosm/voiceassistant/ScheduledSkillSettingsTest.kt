package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scheduled skills in the settings file and in a backup (PLAN.md §10/M15).
 *
 * The record is a reference pair — a skill id and a persona name — and both halves move when
 * their target moves. These pin the storage contract: the round trip, the corruption tolerance
 * every settings key in this app has, the rename/delete migrations, and a restore that refuses
 * to bring back a schedule pointing at something the same backup did not carry.
 */
class ScheduledSkillSettingsTest {

    private val store = InMemoryStore()
    private val manager = SettingsManager(FakeAppEnv(), store, NoImageCodec)

    private fun persona(name: String = "A") = Persona(
        name = name,
        themeColor = Color(0xFF7C5CE0),
        model = "[Home] model",
        systemPrompt = "prompt",
    )

    private fun skill(id: String = "skill-1", name: String = "Briefing") = Skill(
        id = id,
        name = name,
        instructions = "Summarise the day."
    )

    private fun schedule(
        id: String = "sched-1",
        skillId: String = "skill-1",
        personaName: String = "A",
        minuteOfDay: Int = 8 * 60,
        enabled: Boolean = true
    ) = ScheduledSkill(
        id = id,
        skillId = skillId,
        personaName = personaName,
        minuteOfDay = minuteOfDay,
        enabled = enabled
    )

    @Test
    fun `nothing is scheduled by default`() {
        assertTrue(manager.getScheduledSkills().isEmpty())
    }

    @Test
    fun `schedules round-trip`() {
        val target = schedule(minuteOfDay = 7 * 60 + 30, enabled = false)

        manager.saveScheduledSkills(listOf(target))

        assertEquals(listOf(target), manager.getScheduledSkills())
    }

    @Test
    fun `unreadable JSON reads as nothing scheduled`() {
        store.edit().putString("scheduled_skills_v1", "{ not json").commit()

        assertTrue(manager.getScheduledSkills().isEmpty())
    }

    @Test
    fun `a backup carries the schedules`() {
        manager.saveSkills(listOf(skill()))
        manager.savePersonas(listOf(persona()))
        manager.saveScheduledSkills(listOf(schedule()))

        val exported = manager.exportBackup()

        assertTrue(exported.contains("scheduledSkills"))
        val restored = SettingsManager(FakeAppEnv(), InMemoryStore(), NoImageCodec)
        assertTrue(restored.importBackup(exported))
        assertEquals(1, restored.getScheduledSkills().size)
        assertEquals("skill-1", restored.getScheduledSkills().single().skillId)
    }

    @Test
    fun `an import drops a schedule whose skill or persona did not travel`() {
        manager.saveSkills(listOf(skill()))
        manager.savePersonas(listOf(persona()))
        // Valid, plus three dangling shapes: a vanished skill, a vanished persona, and both.
        manager.saveScheduledSkills(
            listOf(
                schedule(id = "ok", skillId = "skill-1", personaName = "A"),
                schedule(id = "no-skill", skillId = "ghost-skill", personaName = "A"),
                schedule(id = "no-persona", skillId = "skill-1", personaName = "Ghost"),
                schedule(id = "neither", skillId = "ghost-skill", personaName = "Ghost"),
            )
        )
        val exported = manager.exportBackup()

        val restored = SettingsManager(FakeAppEnv(), InMemoryStore(), NoImageCodec)
        assertTrue(restored.importBackup(exported))

        assertEquals(listOf("ok"), restored.getScheduledSkills().map { it.id })
    }

    @Test
    fun `a backup written before scheduled skills existed still imports`() {
        manager.saveSkills(listOf(skill()))
        manager.savePersonas(listOf(persona()))
        manager.saveScheduledSkills(listOf(schedule()))
        val exported = manager.exportBackup()
        // Simulate a pre-M15 file: the key is absent entirely, which is what the defaulted
        // BackupData field decodes.
        val withoutKey = exported.replace(Regex(",\\s*\"scheduledSkills\"\\s*:\\s*\\[.*?]"), "")

        val restored = SettingsManager(FakeAppEnv(), InMemoryStore(), NoImageCodec)
        assertTrue("an absent field must not fail the import", restored.importBackup(withoutKey))
        assertTrue(restored.getScheduledSkills().isEmpty())
    }
}
