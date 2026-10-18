package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Spontaneous-messages follow-up: the persona selection as ONE observable value (ported from
 * upstream's instrumented test in M7).
 *
 * `currentPersonaName` was a plain field and the UI kept its own mirror of it, which is how a
 * switch could move the service while the screen kept showing the previous persona. The fix is
 * that `currentPersonaFlow` is now the single derived value, and this class proves it follows
 * every real path that moves the selection.
 *
 * Every assertion here is about the exact value a consumer of the flow would observe, so
 * passing these is the service-layer proof that the UI (which derives from the same value) is
 * correct. Nothing in this file needs a Compose host, a notification, or a fake server.
 *
 * The port change is only the harness: a fresh service per test and Main pumped while the
 * history swaps and saves run.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PersonaSelectionTest {

    private companion object {
        const val PROBE = "ProbeSelectionPersona"
        const val OTHER = "ProbeSelectionOther"
        const val GHOST = "ProbeSelectionGhost"
    }

    private val env = FakeAppEnv(notifier = RecordingNotifier())
    private val store = InMemoryStore()
    private val settings = SettingsManager(env, store, NoImageCodec)
    private val mainDispatcher = StandardTestDispatcher()
    private val services = mutableListOf<AssistantService>()

    private lateinit var service: AssistantService

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        service = AssistantService(
            appEnv = env,
            audioPlayback = SilentAudioPlayback,
            audioDevices = NoAudioDevices,
            tts = NoTextToSpeech,
            audioCapture = NoAudioCapture,
            fileDialogs = NoFileDialogs,
            settingsManager = settings,
        ).also { services += it }

        // Two personas, so a switch has somewhere to go and a wrong target is distinguishable
        // from "already there".
        service.addPersona(persona(OTHER))
        service.addPersona(persona(PROBE))
        service.switchPersona(persona(OTHER))
        assertTrue("probe setup must leave OTHER selected", waitFor { service.currentPersonaName == OTHER })
        // Settle the swap so the mid-swap assertions below start from a known state.
        assertTrue("probe setup must settle the history owner", waitFor { service.messagesOwnerName == OTHER })
    }

    @After
    fun tearDown() {
        services.forEach { runCatching { it.shutdown() } }
        drainServiceScopes(services, mainDispatcher.scheduler)
        services.clear()
        Dispatchers.resetMain()
    }

    /** A room created through the production path. */
    private fun seedRoom(name: String, members: List<String>): Room {
        val room = Room(name = name, memberNames = members)
        service.addRoom(room)
        assertTrue(
            "the probe room must really be in the service's list",
            waitFor { service.rooms.value.any { it.id == room.id } }
        )
        return room
    }

    private fun persona(name: String) = Persona(
        name = name,
        themeColor = Color(0xFF4ADE80),
        model = "[ProbeSelectionOllama] llama3",
        systemPrompt = "You are $name.",
        isCloud = false
    )

    private fun waitFor(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            mainDispatcher.scheduler.runCurrent()
            if (condition()) return true
            Thread.sleep(20)
        }
        mainDispatcher.scheduler.runCurrent()
        return condition()
    }

    /** The flow's value, awaited to [name]. `null` means "no selection". */
    private fun awaitSelection(name: String?, timeoutMs: Long = 10_000): Persona? {
        waitFor(timeoutMs) { service.currentPersonaFlow.value?.name == name }
        return service.currentPersonaFlow.value
    }

    // ---- the flow follows every path that moves the selection ----------------------------

    @Test
    fun `the flow follows a switch`() {
        assertEquals("precondition: the switch must be a real move", OTHER, awaitSelection(OTHER)?.name)
        service.switchPersona(persona(PROBE))
        assertEquals(PROBE, awaitSelection(PROBE)?.name)
    }

    @Test
    fun `the flow and the synchronous getter are the same resolution`() {
        // One join, one instance: the value a collector sees and the value a service-internal
        // pull sees cannot be different objects, let alone different personas.
        val fromFlow = awaitSelection(OTHER)
        assertSame(
            "the flow and currentPersona must resolve to the identical instance",
            service.currentPersona, fromFlow
        )
    }

    @Test
    fun `an edit to the active persona is reflected in the flow`() {
        // Exercises the LIST half of the combine, not just the name half: the editor
        // republishes the whole list on every keystroke, and the selection must observe it.
        val idx = service.personas.value.indexOfFirst { it.name == OTHER }
        assertTrue("precondition: the active persona must be findable", idx >= 0)
        assertEquals("You are $OTHER.", service.currentPersonaFlow.value?.systemPrompt)

        service.updatePersona(idx, service.personas.value[idx].copy(systemPrompt = "edited"))

        assertTrue(
            "an edit to the ACTIVE persona must reach the flow",
            waitFor { service.currentPersonaFlow.value?.systemPrompt == "edited" }
        )
        assertEquals(OTHER, service.currentPersonaFlow.value?.name)
    }

    @Test
    fun `the flow follows a rename of the active persona`() {
        val renamed = "ProbeSelectionRenamed"
        service.switchPersona(persona(PROBE))
        assertEquals(PROBE, awaitSelection(PROBE)?.name)

        val idx = service.personas.value.indexOfFirst { it.name == PROBE }
        service.updatePersona(idx, service.personas.value[idx].copy(name = renamed))

        assertEquals(
            "a rename of the ACTIVE persona must move the selection with it, not orphan it",
            renamed, awaitSelection(renamed)?.name
        )
    }

    @Test
    fun `deleting the active persona re-points the flow`() {
        service.switchPersona(persona(PROBE))
        assertEquals(PROBE, awaitSelection(PROBE)?.name)

        val idx = service.personas.value.indexOfFirst { it.name == PROBE }
        service.removePersona(idx)

        val fallback = service.currentPersonaName
        assertTrue("the service must never be left pointing at a deleted persona", fallback != null)
        assertEquals(
            "and the fallback must be a persona that is really in the list",
            service.personas.value.first().name, fallback
        )
        assertEquals(fallback, awaitSelection(fallback)?.name)
    }

    @Test
    fun `deleting the last persona leaves the flow with no selection`() {
        // Also the non-vacuity control for every other assertion here: the flow genuinely can
        // be null, so "it equals persona X" is never true by default.
        service.personas.value.indices.reversed().forEach { service.removePersona(it) }
        assertTrue("precondition: the persona list must be empty", service.personas.value.isEmpty())
        assertNull(awaitSelection(null))
    }

    @Test
    fun `adding an unselected persona does not disturb the selection`() {
        val before = awaitSelection(OTHER)
        service.addPersona(persona("ProbeSelectionAdded"))
        Thread.sleep(250)
        assertEquals(OTHER, service.currentPersonaFlow.value?.name)
        // The IDENTICAL instance, not merely an equal one: an unrelated list change must not
        // re-derive the selection into a fresh value that would recompose its consumers.
        assertSame(before, service.currentPersonaFlow.value)
    }

    // ---- the spontaneous-message deep link, which is what exposed all of this -------------

    @Test
    fun `a deep link moves the flow`() {
        // THE regression from the spontaneous-messages work: the tap switched the service and
        // left the screen behind, because the screen mirrored the selection by hand. Now the
        // selection IS the observable, so this test is the whole proof.
        assertEquals(OTHER, awaitSelection(OTHER)?.name)
        service.applySpontaneousDeepLink(PROBE)
        assertEquals(PROBE, awaitSelection(PROBE)?.name)
    }

    @Test
    fun `a deep link for a missing persona moves nothing`() {
        val before = awaitSelection(OTHER)
        service.applySpontaneousDeepLink(GHOST)
        Thread.sleep(250)
        assertEquals(OTHER, service.currentPersonaFlow.value?.name)
        assertSame(before, service.currentPersonaFlow.value)
    }

    // ---- the four call sites that had to change, pinned individually ----------------------

    @Test
    fun `the translator add-and-select sequence resolves to a real persona`() {
        service.personas.value.filter { it.isTranslator }
            .forEach { service.removePersona(service.personas.value.indexOf(it)) }
        assertTrue("precondition: no translator may remain", service.personas.value.none { it.isTranslator })

        service.addPersona(TRANSLATOR_PERSONA)
        service.switchPersona(TRANSLATOR_PERSONA)

        val resolved = awaitSelection(TRANSLATOR_PERSONA.name)
        assertTrue("the added translator must be a real, stored persona", resolved != null)
        assertTrue("and it must be the translator, not a namesake", resolved!!.isTranslator)
        assertTrue(
            "resolution must hand back the STORED instance, not the constant",
            service.personas.value.any { it === resolved }
        )
    }

    @Test
    fun `the language bar call reaches the flow with no local copy`() {
        // The bar used to ALSO assign a local `currentPersona.copy(...)`. The only write left is
        // `updatePersona(idx, copy)`, so this pins everything the screen now depends on.
        val translator = selectATranslator()
        val idx = service.personas.value.indexOfFirst { it.name == translator.name }
        assertTrue("precondition: the selected translator must be findable", idx >= 0)

        service.updatePersona(idx, service.personas.value[idx].copy(targetLanguage = "French"))

        assertTrue(
            "the language edit must reach the selection's flow",
            waitFor { service.currentPersonaFlow.value?.targetLanguage == "French" }
        )
        assertEquals(translator.name, service.currentPersonaFlow.value?.name)
        assertTrue(
            "and it must be the stored instance, not a local copy",
            service.personas.value.any { it === service.currentPersonaFlow.value }
        )
    }

    @Test
    fun `a selection naming a persona that is not in the list does not resolve`() {
        assertTrue("precondition: the list must be non-empty", service.personas.value.isNotEmpty())
        service.currentPersonaName = GHOST

        assertNull("the synchronous getter must not invent a persona", service.currentPersona)
        assertTrue(
            "nor may the flow, which is what the screen reads",
            waitFor { service.currentPersonaFlow.value == null }
        )
    }

    /** Find-or-add the default translator and select it — the two translator call sites. */
    private fun selectATranslator(): Persona {
        service.personas.value.filter { it.isTranslator }
            .forEach { service.removePersona(service.personas.value.indexOf(it)) }
        service.addPersona(TRANSLATOR_PERSONA)
        service.switchPersona(TRANSLATOR_PERSONA)
        val resolved = awaitSelection(TRANSLATOR_PERSONA.name)
        assertTrue("precondition: a translator must be selected", resolved != null)
        return resolved!!
    }

    @Test
    fun `the flow follows the name before the history owner catches up`() {
        // The join derives from the NAME, so the selection is the selection — never the
        // transcript. switchPersona moves the name synchronously and loads the incoming
        // history off Main, so inside this one main-thread block the two legitimately differ;
        // the flow must already report the incoming persona.
        service.switchPersona(persona(PROBE))
        assertEquals(PROBE, service.currentPersonaName)
        assertEquals(
            "the recorded history owner lags until the swap settles",
            OTHER, service.messagesOwnerName
        )
        assertEquals(PROBE, awaitSelection(PROBE)?.name)
        assertTrue("the swap must settle", waitFor { service.messagesOwnerName == PROBE })
    }

    // ---- the picker's selection must also step out of room mode ---------------------------

    @Test
    fun `picking a persona from inside a room exits room mode into the normal chat of that persona`() {
        val room = seedRoom("ProbeSelectionRoomOutside", listOf(OTHER))
        service.switchRoom(room)
        assertTrue("precondition: room mode must actually be on",
            waitFor { service.activeRoomId.value == room.id })
        assertTrue("precondition: the picked persona must NOT be in this room",
            PROBE !in room.memberNames)

        // The diagnosis, executable: the selection itself follows a switch even while a room
        // is open — which is exactly why the bug looked like nothing happened.
        service.switchPersona(persona(PROBE))
        assertEquals("precondition: the bare switch must move the selection under room mode",
            PROBE, awaitSelection(PROBE)?.name)
        assertEquals("precondition: and that is the bug — the room is still open",
            room.id, service.activeRoomId.value)
        service.switchPersona(persona(OTHER))
        assertEquals(OTHER, awaitSelection(OTHER)?.name)

        // The fixed picker entry point: one tap, the outcome "Leave" then selecting PROBE
        // would have given.
        service.switchPersonaFromPicker(persona(PROBE))

        assertEquals("the tap must select the persona it was given", PROBE, awaitSelection(PROBE)?.name)
        assertTrue("and it must exit room mode",
            waitFor { service.activeRoomId.value == null })
        assertNull("with no room left selected", service.activeRoomId.value)
    }

    @Test
    fun `picking a persona that is a member of the active room still exits to the private chat`() {
        // Membership must not turn the tap into a no-op either: the outcome is the persona's
        // PRIVATE chat, not the room — the room is where they were, not where they are now.
        val room = seedRoom("ProbeSelectionRoomInside", listOf(PROBE, OTHER))
        service.switchRoom(room)
        assertTrue("precondition: room mode must actually be on",
            waitFor { service.activeRoomId.value == room.id })
        assertTrue("precondition: the picked persona must really be a member",
            PROBE in room.memberNames)

        service.switchPersonaFromPicker(persona(PROBE))

        assertEquals("the tap must select the persona it was given", PROBE, awaitSelection(PROBE)?.name)
        assertTrue("membership must not keep the user inside the room",
            waitFor { service.activeRoomId.value == null })
        assertTrue("and leaving must not delete the room — it is still there to re-enter",
            service.rooms.value.any { it.id == room.id })
    }
}
