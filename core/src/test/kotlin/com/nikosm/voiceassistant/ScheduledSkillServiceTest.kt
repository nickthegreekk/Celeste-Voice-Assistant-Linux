package com.nikosm.voiceassistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The service's scheduled-skill bookkeeping (PLAN.md §10/M15).
 *
 * The record references two things that can be renamed or deleted — its skill and its persona —
 * and the service is the shared surface for all three lifecycle events. These pin the parts the
 * pure and settings tests cannot: one record per skill, the id surviving an edit, a deleted
 * skill taking its schedule with it, and a persona rename/delete reaching BOTH the in-memory flow
 * the runner reads and the store an export reads.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ScheduledSkillServiceTest {
    private val mainDispatcher = StandardTestDispatcher()


    private val env = FakeAppEnv()
    private val store = InMemoryStore()
    private val settings = SettingsManager(env, store, NoImageCodec)
    private val services = mutableListOf<AssistantService>()

    private fun newService(): AssistantService = AssistantService(
        appEnv = env,
        audioPlayback = SilentAudioPlayback,
        audioDevices = NoAudioDevices,
        tts = NoTextToSpeech,
        audioCapture = NoAudioCapture,
        fileDialogs = NoFileDialogs,
        settingsManager = settings,
    ).also { services += it }

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        // Register what you build and tear it down (AGENTS.md §6): the service owns a scope,
        // and a test that walks away leaves coroutines for the next test to trip over.
        services.forEach { runCatching { it.shutdown() } }
        drainServiceScopes(services, mainDispatcher.scheduler)
        services.clear()
        Dispatchers.resetMain()
    }

    private fun skill(id: String = "skill-1", name: String = "Briefing") = Skill(
        id = id,
        name = name,
        instructions = "Summarise the day."
    )

    private fun schedule(
        id: String = "sched-1",
        skillId: String = "skill-1",
        personaName: String = "A",
        minuteOfDay: Int = 8 * 60
    ) = ScheduledSkill(
        id = id,
        skillId = skillId,
        personaName = personaName,
        minuteOfDay = minuteOfDay,
    )

    @Test
    fun `a skill has one schedule, and editing it keeps its id`() {
        val service = newService()
        service.setScheduledSkill(schedule(minuteOfDay = 8 * 60))

        service.setScheduledSkill(schedule(minuteOfDay = 9 * 60))

        assertEquals(1, service.scheduledSkills.value.size)
        val stored = service.scheduledSkills.value.single()
        assertEquals("the id is what the runner's once-per-occurrence map keys on", "sched-1", stored.id)
        assertEquals(9 * 60, stored.minuteOfDay)
    }

    @Test
    fun `removing a schedule leaves the skill alone`() {
        val service = newService()
        service.setScheduledSkill(schedule(minuteOfDay = 8 * 60))

        service.removeScheduledSkill("skill-1")

        assertTrue(service.scheduledSkills.value.isEmpty())
    }

    @Test
    fun `deleting a skill deletes its schedule`() {
        val service = newService()
        val target = skill()
        service.addSkill(target)
        service.setScheduledSkill(schedule(minuteOfDay = 8 * 60))

        service.removeSkill(target)

        assertTrue("a schedule with no skill can never run and cannot be reached", service.scheduledSkills.value.isEmpty())
    }

    @Test
    fun `renaming a persona re-points schedules in the flow and in the store`() {
        val service = newService()
        service.setScheduledSkill(schedule(personaName = "A", minuteOfDay = 8 * 60))

        service.renameScheduledSkillsForPersona("A", "B")

        assertEquals("B", service.scheduledSkills.value.single().personaName)
        assertEquals(
            "the synchronous write is what an immediate export sees",
            "B",
            settings.getScheduledSkills().single().personaName
        )
    }

    @Test
    fun `renaming one persona leaves schedules for another alone`() {
        val service = newService()
        service.setScheduledSkill(schedule(id = "s1", skillId = "skill-1", personaName = "A"))
        service.setScheduledSkill(schedule(id = "s2", skillId = "skill-2", personaName = "C"))

        service.renameScheduledSkillsForPersona("A", "B")

        assertEquals(
            listOf("B", "C"),
            service.scheduledSkills.value.map { it.personaName }
        )
        assertEquals(
            listOf("B", "C"),
            settings.getScheduledSkills().map { it.personaName }
        )
    }

    @Test
    fun `deleting a persona deletes its schedules`() {
        val service = newService()
        service.setScheduledSkill(schedule(id = "s1", skillId = "skill-1", personaName = "A"))
        service.setScheduledSkill(schedule(id = "s2", skillId = "skill-2", personaName = "C"))

        service.removeScheduledSkillsForPersona("A")

        assertEquals(listOf("s2"), service.scheduledSkills.value.map { it.id })
        assertEquals(listOf("s2"), settings.getScheduledSkills().map { it.id })
    }
}
