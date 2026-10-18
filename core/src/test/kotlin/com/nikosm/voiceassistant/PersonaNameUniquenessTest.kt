package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Persona NAME uniqueness — the IN-MEMORY write path (ported from upstream's instrumented test
 * in M7).
 *
 * The reported bug: the header button's translator lookup missed an existing persona named
 * "Translator" and added a second one beside it. [PersonaNameGuardTest] pins the pure lookup
 * and the persistence chokepoint; only a real service can show that the guard in
 * `AssistantService.addPersona` / `updatePersona` actually HOLDS, because the service owns the
 * live `personas` flow the picker renders from — a duplicate that reached it would be visible
 * to the user even if storage corrected it later.
 *
 * The port change is only the harness: upstream binds the Android service and snapshots/restores
 * the device's personas; here each test builds a service over a fresh in-memory store, so there
 * is nothing to isolate. Every assertion is upstream's.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PersonaNameUniquenessTest {
    private val mainDispatcher = StandardTestDispatcher()


    private val env = FakeAppEnv(notifier = RecordingNotifier())
    private val store = InMemoryStore()
    private val settings = SettingsManager(env, store, NoImageCodec)
    private val services = mutableListOf<AssistantService>()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        services.forEach { runCatching { it.shutdown() } }
        drainServiceScopes(services, mainDispatcher.scheduler)
        services.clear()
        Dispatchers.resetMain()
    }

    private fun newService(): AssistantService = AssistantService(
        appEnv = env,
        audioPlayback = SilentAudioPlayback,
        audioDevices = NoAudioDevices,
        tts = NoTextToSpeech,
        audioCapture = NoAudioCapture,
        fileDialogs = NoFileDialogs,
        settingsManager = settings,
    ).also { services += it }

    private fun persona(
        name: String,
        prompt: String = "You are $name.",
        translator: Boolean = false
    ) = Persona(
        name = name,
        themeColor = Color(0xFF4ADE80),
        model = "[ProbeOllamaNames] llama3",
        systemPrompt = prompt,
        isTranslator = translator
    )

    private fun AssistantService.stored(name: String): Persona =
        personas.value.firstOrNull { it.name == name }
            ?: error("persona '$name' is not in the service's list")

    private fun AssistantService.indexOf(name: String): Int =
        personas.value.indexOfFirst { it.name == name }
            .also { require(it >= 0) { "persona '$name' is not in the service's list" } }

    private fun AssistantService.translatorCount(): Int = personas.value.count { it.name == TRANSLATOR }

    /**
     * Replays the header button's "add the translator if it is missing" decision, and nothing
     * else — the button's select-and-clear half is orthogonal to name uniqueness.
     */
    private fun AssistantService.pressTranslatorButton() {
        val existing = existingTranslatorPersona(personas.value)
        if (existing == null) addPersona(TRANSLATOR_PERSONA)
    }

    // ---- the chokepoint itself --------------------------------------------------------

    @Test
    fun `addPersona refuses a second persona carrying an existing name`() {
        val service = newService()
        service.addPersona(persona(OWNER, prompt = "first"))
        val sizeAfterFirstAdd = service.personas.value.size

        service.addPersona(persona(OWNER, prompt = "second"))

        assertEquals(
            "addPersona is the chokepoint every creation path flows through; no caller may " +
                "slip a duplicate name past it",
            sizeAfterFirstAdd,
            service.personas.value.size,
        )
        assertEquals(1, service.personas.value.count { it.name == OWNER })
    }

    @Test
    fun `a manually created persona reusing an existing name neither duplicates nor overwrites it`() {
        // The user path: the editor's first commit calls addPersona with the typed name. The
        // two possible wrong outcomes are a duplicate row and clobbering the incumbent; this
        // pins that neither happens.
        val service = newService()
        service.addPersona(persona(OWNER, prompt = "incumbent prompt"))
        val incumbent = service.stored(OWNER)

        service.addPersona(persona(OWNER, prompt = "brand new persona", translator = true))

        assertEquals(1, service.personas.value.count { it.name == OWNER })
        val after = service.stored(OWNER)
        assertEquals("the incumbent must keep its prompt", incumbent.systemPrompt, after.systemPrompt)
        assertEquals("the incumbent must keep its template flags", incumbent.isTranslator, after.isTranslator)
    }

    // ---- the reported translator duplication -------------------------------------------

    @Test
    fun `the live translator lookup finds a Translator persona that lacks the flag`() {
        // The precondition of the bug: a persona NAMED "Translator" whose isTranslator was
        // never set (the flag comes from a template; a rename does not set it).
        val service = newService()
        val namedOnly = persona(TRANSLATOR, prompt = "user-named translator", translator = false)
        service.addPersona(namedOnly)

        assertSame(
            "the button must recognise its translator by name, not by a stale flag",
            namedOnly,
            existingTranslatorPersona(service.personas.value),
        )
    }

    @Test
    fun `the header button never adds a second Translator when one already exists`() {
        val service = newService()
        service.addPersona(persona(TRANSLATOR, prompt = "user-named translator"))
        val sizeBefore = service.personas.value.size

        // Repeated presses also cover the stale-list race: the picker renders from a flow
        // snapshot, so a second tap can be handled before the first add is collected.
        repeat(3) { service.pressTranslatorButton() }

        assertEquals("exactly one persona may be named Translator", 1, service.translatorCount())
        assertEquals("the button must not grow the persona list", sizeBefore, service.personas.value.size)
        assertEquals(
            "and the survivor is the pre-existing one",
            "user-named translator",
            service.stored(TRANSLATOR).systemPrompt,
        )
    }

    @Test
    fun `the header button still creates the translator when none exists`() {
        // The inverse control: a lookup that always matched would pass the test above.
        val service = newService()
        assertEquals(0, service.translatorCount())
        service.pressTranslatorButton()
        assertEquals(1, service.translatorCount())
        assertEquals(TRANSLATOR_PERSONA.name, service.stored(TRANSLATOR).name)
    }

    // ---- the rename side of the same invariant -----------------------------------------

    @Test
    fun `a rename onto an existing name is refused and the victim history survives`() {
        val service = newService()
        service.addPersona(persona(RENAMER))
        service.addPersona(persona(VICTIM))
        val transcript = listOf(
            ChatMessage(role = "user", text = "victim's chat"),
            ChatMessage(role = "assistant", text = "still here"),
        )
        settings.savePersonaMessages(VICTIM, transcript)

        // The editor's commit carries every field, so this is what typing an existing name
        // into the name box produces.
        service.updatePersona(service.indexOf(RENAMER), service.stored(RENAMER).copy(name = VICTIM))

        assertEquals("the refused rename must leave the renamer alone", RENAMER, service.stored(RENAMER).name)
        assertEquals(1, service.personas.value.count { it.name == VICTIM })
        assertEquals(
            "the rename must not have migrated history onto the victim's name key",
            transcript,
            settings.getPersonaMessages(VICTIM),
        )
    }

    @Test
    fun `a rename to a fresh name still works`() {
        // The inverse control: a guard that refused every rename would pass the test above.
        val service = newService()
        service.addPersona(persona(RENAMER))
        service.updatePersona(service.indexOf(RENAMER), service.stored(RENAMER).copy(name = CREATOR))
        assertTrue("the renamer must be gone under its old name", service.personas.value.none { it.name == RENAMER })
        assertEquals(CREATOR, service.stored(CREATOR).name)
    }

    private companion object {
        const val TRANSLATOR = "Translator"
        const val OWNER = "ProbeNameOwner"
        const val CREATOR = "ProbeNameCreator"
        const val VICTIM = "ProbeNameVictim"
        const val RENAMER = "ProbeNameRenamer"
    }
}
