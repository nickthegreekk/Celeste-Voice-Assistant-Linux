package com.nikosm.voiceassistant

/**
 * M16.3: the app's own answer to a model that reports a command result it never obtained.
 *
 * The prompt contract forbids claiming a run unless a tool call returned, but a model can ignore a
 * contract, and the user cannot tell a fabricated result from a real one. The turn already knows
 * the facts: `TurnContext.commandEnabled` says the tool was offered, and `toolsUsed` says what was
 * actually called. When `run_command` is absent and the reply reads like a past-tense execution
 * claim, the reply gets [COMMAND_CLAIM_NOTE] and the service logs one warning.
 *
 * **The note is factual, not an accusation.** It says no tool call was made in this turn, which is
 * true even when the model was discussing a hypothetical command. The detector is deliberately
 * conservative — past-tense English only — because a warning on ordinary answers teaches the user
 * to ignore it; a missed unusual phrasing is the cheaper mistake.
 */
internal const val COMMAND_CLAIM_NOTE =
    "\n\n**No command ran in this turn.** No command tool call was made, so any command output " +
        "above is the model's text, not an app result."

private val COMMAND_CLAIM_PATTERNS = listOf(
    // "The command ran", "the command produced no output", "the command generated …"
    Regex(
        """\bcommand\s+(ran|executed|completed|finished|failed|timed\s+out|produced|printed|""" +
            """returned|generated|did\s+not\s+work|didn't\s+work)\b""",
        RegexOption.IGNORE_CASE,
    ),
    // "I ran the command", "we executed that command"
    Regex(
        """\b(i|we)\s+(ran|executed|invoked)\s+(the|a|that|your)?\s*command\b""",
        RegexOption.IGNORE_CASE,
    ),
    // "the command I ran", "the command we just executed" — the order the live miss used
    Regex(
        """\bcommand\s+(i|we)\s+(just\s+)?(ran|executed|invoked)\b""",
        RegexOption.IGNORE_CASE,
    ),
    // "I ran `dmesg`", "we executed `date`"
    Regex(
        """\b(i|we)\s+(just\s+)?(ran|executed|invoked)\s+`[^`\n]+`""",
        RegexOption.IGNORE_CASE,
    ),
    // "I ran the dmesg command", "we executed that shell command"
    Regex(
        """\b(i|we)\s+(just\s+)?(ran|executed|invoked)\s+(the\s+)?[a-z0-9_.-]+\s+command\b""",
        RegexOption.IGNORE_CASE,
    ),
    // "The tool returned …", "the tool timed out"
    Regex(
        """\bthe\s+tool\s+(ran|executed|returned|failed|timed\s+out)\b""",
        RegexOption.IGNORE_CASE,
    ),
    // A numeric exit code, not the general concept: "exit code 1", "exit code: 0".
    Regex("""\bexit\s+code\s*(?:was|is|:|=)?\s*-?\d+\b""", RegexOption.IGNORE_CASE),
    // "no output returned", "output was returned" — not "returned the output".
    Regex("""\b(no\s+output|output\s+was)\s+returned\b""", RegexOption.IGNORE_CASE),
    // "printed all the numbers", "printed the numbers"
    Regex("""\bprinted\s+(all\s+)?(the\s+)?numbers\b""", RegexOption.IGNORE_CASE),
)

/** Whether [text] reads as a claim that a command already did something. Pure and tested. */
internal fun claimsCommandResult(text: String): Boolean =
    COMMAND_CLAIM_PATTERNS.any { it.containsMatchIn(text) }

/**
 * The note a reply needs, or null when the turn did not offer commands, did call `run_command`, or
 * does not read like an execution claim.
 */
internal fun commandClaimNote(reply: String, turnContext: TurnContext): String? {
    if (!turnContext.commandEnabled) return null
    if (TOOL_RUN_COMMAND in turnContext.toolsUsed) return null
    if (!claimsCommandResult(reply)) return null
    return COMMAND_CLAIM_NOTE
}

/** [reply] with the claim note appended, or unchanged. The one place both tool loops call. */
internal fun appendCommandClaimNote(reply: String, turnContext: TurnContext): String =
    reply + (commandClaimNote(reply, turnContext) ?: "")
