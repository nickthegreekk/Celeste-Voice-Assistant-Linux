package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Persona NAME uniqueness — the decision and the persistence chokepoint.
 *
 * Ported from upstream's JVM test (M7; upstream at port time:
 * `34fb385e04eaf9fda1177f897d8a2cda540113ba336d8e7c8e24f9218feeaf31`, 176 lines). It arrived
 * after this port's M0/M1 harvest and was missed until the instrumented-test triage pointed at
 * its service-side sibling.
 *
 * **The port change is only the store**: upstream's `FakeSharedPreferences`/`FakeAppContext`
 * become the port's `InMemoryStore` + `FakeAppEnv`, the same substitution its other retargeted
 * settings tests make. Every assertion is upstream's.
 *
 * The reported bug: the header button's translator lookup found nothing, added
 * [TRANSLATOR_PERSONA], and the picker ended up with TWO personas named "Translator".
 *
 * Why that is a data bug and not a cosmetic one: the name IS the identity. History lives in
 * `persona_messages_<name>`, room membership is `memberNames: List<String>`, the spontaneous
 * state map is name-keyed, and the scheduler's chain and notification id are named after it.
 * Two personas sharing a name share one history file and one scheduler chain, and make
 * membership ambiguous — nothing anywhere in the app disambiguates them.
 *
 * Layering, mirroring the spontaneous cloud guard exactly:
 *   Part 1 — the pure decision the header button runs (`existingTranslatorPersona`).
 *   Part 2 — `SettingsManager.savePersonas`, the ONLY writer of `personas_v2`, which
 *            `importBackup` reaches directly without ever touching the service.
 * The in-memory layer (`AssistantService.addPersona` / `updatePersona`) needs a real service
 * and is proven by [PersonaNameUniquenessTest] beside this file.
 */
class PersonaNameGuardTest {

    private fun persona(
        name: String,
        translator: Boolean = false,
        prompt: String = "You are $name."
    ) = Persona(
        name = name,
        themeColor = Color(0xFF4ADE80),
        model = "[HomeBox] llama3",
        systemPrompt = prompt,
        isTranslator = translator
    )

    // ------------------------------------------- Part 1: the header button's lookup

    @Test
    fun `the translator lookup finds a persona named Translator even without the flag`() {
        // EXACTLY the reported situation. The flag comes from a template; nothing sets it when
        // a user names a persona "Translator" (or renames one into that name). The old
        // flag-only lookup returned null here — which is precisely why a second "Translator"
        // was created beside the first.
        val namedOnly = persona("Translator", translator = false)
        val found = existingTranslatorPersona(listOf(persona("Bob"), namedOnly))
        assertSame("a name is identity here; the lookup must find it", namedOnly, found)
    }

    @Test
    fun `the translator lookup still finds a properly flagged translator`() {
        val flagged = persona("Übersetzer", translator = true)
        assertSame(flagged, existingTranslatorPersona(listOf(persona("Bob"), flagged)))
    }

    @Test
    fun `a flagged translator wins over a same-named unflagged one, whatever the order`() {
        // Order-independent on purpose: the button must not depend on which row the picker
        // happens to render first.
        val flagged = persona("Translator", translator = true, prompt = "flagged")
        val impostor = persona("Translator", translator = false, prompt = "impostor")
        assertSame(flagged, existingTranslatorPersona(listOf(flagged, impostor)))
        assertSame(flagged, existingTranslatorPersona(listOf(impostor, flagged)))
    }

    @Test
    fun `the lookup reports no translator when neither the flag nor the name matches`() {
        // Guards against a fix that over-matches and hijacks an unrelated persona.
        assertNull(existingTranslatorPersona(listOf(persona("Bob"), persona("Translations"))))
        assertNull(existingTranslatorPersona(emptyList()))
    }

    // ------------------------------------------- Part 2: the persistence chokepoint

    private fun newManager(): SettingsManager = SettingsManager(FakeAppEnv(), InMemoryStore(), NoImageCodec)

    @Test
    fun `savePersonas drops the second persona carrying an existing name`() {
        val manager = newManager()
        manager.savePersonas(
            listOf(
                persona("Translator", prompt = "the real one"),
                persona("Translator", translator = true, prompt = "the duplicate")
            )
        )
        val stored = manager.getPersonas()!!
        assertEquals("only one persona may own a name", 1, stored.count { it.name == "Translator" })
        assertEquals("first occurrence wins, deterministically", "the real one", stored.single().systemPrompt)
    }

    @Test
    fun `savePersonas keeps distinct names untouched`() {
        // The inverse control: a dedupe that dropped more than duplicates would pass the
        // test above.
        val manager = newManager()
        manager.savePersonas(listOf(persona("A"), persona("B"), persona("C", translator = true)))
        assertEquals(listOf("A", "B", "C"), manager.getPersonas()!!.map { it.name })
    }

    @Test
    fun `importing a backup that carries two personas named Translator keeps one`() {
        // The import path never reaches AssistantService.addPersona — it calls savePersonas
        // directly — so a backup written by a build with the flag-only lookup (or hand-edited)
        // is exactly how a duplicate would otherwise become permanent on disk.
        val manager = newManager()
        manager.savePersonas(listOf(persona("A"), persona("B")))
        val export = manager.exportBackup()

        val target = newManager()
        assertTrue("the crafted backup must import", target.importBackup(export.replace("\"B\"", "\"A\"")))

        val restored = target.getPersonas()!!
        assertEquals("the restore must not persist a duplicate name", 1, restored.count { it.name == "A" })
    }
}
