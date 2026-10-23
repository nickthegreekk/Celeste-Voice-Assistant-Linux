package com.nikosm.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Test

class ExportDecisionTest {
    @Test fun plainExportWithoutExclusionNeedsConfirmation() =
        assertEquals(ExportDecision.NeedsConfirmation, decideExport(encrypt = false, excludeCredentials = false))

    @Test fun plainExportWithExclusionExportsRedacted() =
        assertEquals(ExportDecision.ExportNow(true), decideExport(encrypt = false, excludeCredentials = true))

    @Test fun encryptedExportWithoutExclusionExportsWithCredentials() =
        assertEquals(ExportDecision.ExportNow(false), decideExport(encrypt = true, excludeCredentials = false))

    @Test fun encryptedExportWithExclusionExportsRedacted() =
        assertEquals(ExportDecision.ExportNow(true), decideExport(encrypt = true, excludeCredentials = true))
}
