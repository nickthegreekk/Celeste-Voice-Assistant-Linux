package com.nikosm.voiceassistant

/** What the Export button does, decided without any UI. */
internal sealed interface ExportDecision {
    /** A plain-text export that would carry credentials: ask first. */
    object NeedsConfirmation : ExportDecision
    /** Export now, redacted or not as stated. */
    data class ExportNow(val excludeCredentials: Boolean) : ExportDecision
}

internal fun decideExport(encrypt: Boolean, excludeCredentials: Boolean): ExportDecision =
    if (!encrypt && !excludeCredentials) ExportDecision.NeedsConfirmation
    else ExportDecision.ExportNow(excludeCredentials)
