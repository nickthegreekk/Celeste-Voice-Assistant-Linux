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
 * The output device is the field that made this worth writing, being the newest (M2's sink
 * selection), but the assertions cover the shape rather than one field: set, cleared, and the two
 * fields the builder deliberately does not touch.
 */
class PersonaEditorFieldsTest {

    private val base = DEFAULT_PERSONAS.first()

    @Test
    fun `the chosen output device survives the rebuild`() {
        val persona = base.copy(outputDeviceId = "alsa_output.hdmi")
        val fields = fieldsOf(persona).copy(outputDeviceId = "alsa_output.analog")

        val rebuilt = persona.withEditorFields(fields, isCloudModel = false, customProviderNames = emptySet(), spontaneousAllowed = true)

        assertEquals("alsa_output.analog", rebuilt.outputDeviceId)
    }

    @Test
    fun `choosing the default clears it rather than keeping the old sink`() {
        // The direction that would otherwise strand a persona on a device the user just moved it
        // off: "System default" in the picker is null, and null has to win over the stored id.
        val persona = base.copy(outputDeviceId = "alsa_output.hdmi")
        val fields = fieldsOf(persona).copy(outputDeviceId = null)

        val rebuilt = persona.withEditorFields(fields, isCloudModel = false, customProviderNames = emptySet(), spontaneousAllowed = true)

        assertNull(rebuilt.outputDeviceId)
    }

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
        outputDeviceId = p.outputDeviceId,
        imageWorkflowId = p.imageWorkflowId,
        enabledSkillIds = p.enabledSkillIds,
        spontaneousEnabled = p.spontaneousEnabled,
        spontaneousInstruction = p.spontaneousInstruction,
        spontaneousGapMinutes = p.spontaneousGapMinutes,
        spontaneousWindowStartMin = p.spontaneousWindowStartMin,
        spontaneousWindowEndMin = p.spontaneousWindowEndMin,
    )
}
