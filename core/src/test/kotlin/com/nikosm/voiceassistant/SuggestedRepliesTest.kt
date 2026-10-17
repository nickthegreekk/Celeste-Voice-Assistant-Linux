package com.nikosm.voiceassistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Persona role flags survive a copy (ported from upstream's instrumented test in M7).
 *
 * Upstream wrapped this in a Compose rule it never used; the assertions only touch
 * `DEFAULT_PERSONAS[0].copy(...)`, so the port is the same test without the runner.
 */
class SuggestedRepliesTest {

    @Test
    fun `persona role flags survive a copy`() {
        val translatorPersona = DEFAULT_PERSONAS[0].copy(isTranslator = true, isImageGenerator = false)
        val imageGenPersona = DEFAULT_PERSONAS[0].copy(isTranslator = false, isImageGenerator = true)
        val normalPersona = DEFAULT_PERSONAS[0].copy(isTranslator = false, isImageGenerator = false, model = "test-model")

        assertTrue(translatorPersona.isTranslator)
        assertTrue(imageGenPersona.isImageGenerator)
        assertFalse(normalPersona.isTranslator)
        assertFalse(normalPersona.isImageGenerator)
    }
}
