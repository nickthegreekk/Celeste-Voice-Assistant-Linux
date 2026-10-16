package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `Persona.withEditorFields` — the one builder both of the editor's commit paths use.
 *
 * Its own KDoc claims it is "pure, so the local→cloud commit is unit-tested without a device";
 * it was not, and the gap matters more than it looks. Both commit paths rebuild a **whole**
 * Persona from the editor's snapshot, so a field the builder forgets is not "left alone" — it is
 * *reverted*, silently, on the next autosave. That is the hazard the editor's own comments call
 * out at three sites, and the compiler can only catch the half of it that is a missing constructor
 * argument: a `copy()` that simply does not mention a field compiles perfectly.
 *
 * The output device is deliberately NOT one of these fields: it lives in SettingsManager keyed by
 * persona name, because `Models.kt` is a COPY file and a new `Persona` field would demote it (see
 * `SettingsManager.savePersonaOutputDevice`). What is asserted here is the shape that hazard has
 * in general — a field the builder must carry, and the two it deliberately must not.
 */
class PersonaEditorFieldsTest {

    private val base = DEFAULT_PERSONAS.first()

    @Test
    fun `fields the editor has no control for are preserved, not cleared`() {
        val persona = base.copy(providerColor = Color(0xFF123456), spontaneousMaxPerDay = 7)
        val fields = fieldsOf(persona).copy(name = "renamed")

        val rebuilt = persona.withEditorFields(fields, isCloudModel = false, customProviderNames = emptySet(), spontaneousAllowed = true)

        assertEquals("renamed", rebuilt.name)
        assertEquals(persona.providerColor, rebuilt.providerColor)
        assertEquals(persona.spontaneousMaxPerDay, rebuilt.spontaneousMaxPerDay)
    }

    @Test
    fun `the spontaneous opt-in stays gated by the caller's decision`() {
        // Gated at plan location #2: the editor must never commit an opt-in the persona cannot
        // honour, so a "true" in the fields is not enough on its own.
        val fields = fieldsOf(base).copy(spontaneousEnabled = true)

        val allowed = base.withEditorFields(fields, isCloudModel = false, customProviderNames = emptySet(), spontaneousAllowed = true)
        val refused = base.withEditorFields(fields, isCloudModel = false, customProviderNames = emptySet(), spontaneousAllowed = false)

        assertEquals(true, allowed.spontaneousEnabled)
        assertEquals(false, refused.spontaneousEnabled)
    }

    /** The snapshot the editor builds from its local state, with each value taken from [p]. */
    private fun fieldsOf(p: Persona) = PersonaEditorFields(
        name = p.name,
        model = p.model,
        systemPrompt = p.systemPrompt,
        themeColor = p.themeColor,
        iconImageUri = p.iconImageUri,
        backgroundImageUri = p.backgroundImageUri,
        temperature = p.temperature,
        topP = p.topP,
        topK = p.topK,
        repeatPenalty = p.repeatPenalty,
        maxTokens = p.maxTokens,
        numCtx = p.numCtx,
        enableThinking = p.enableThinking,
        webSearchEnabled = p.webSearchEnabled,
        ragEnabled = p.ragEnabled,
        allowGatewayFailover = p.allowGatewayFailover,
        isTranslator = p.isTranslator,
        isImageGenerator = p.isImageGenerator,
        targetLanguage = p.targetLanguage,
        voiceMode = p.voiceMode,
        voiceEngine = p.voiceEngine,
        kokoroVoice = p.kokoroVoice,
        backendUrl = p.backendUrl,
        imageWorkflowId = p.imageWorkflowId,
        enabledSkillIds = p.enabledSkillIds,
        spontaneousEnabled = p.spontaneousEnabled,
        spontaneousInstruction = p.spontaneousInstruction,
        spontaneousGapMinutes = p.spontaneousGapMinutes,
        spontaneousWindowStartMin = p.spontaneousWindowStartMin,
        spontaneousWindowEndMin = p.spontaneousWindowEndMin,
    )
}
