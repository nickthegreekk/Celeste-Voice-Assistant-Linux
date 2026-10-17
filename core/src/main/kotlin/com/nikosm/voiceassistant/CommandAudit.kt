package com.nikosm.voiceassistant

import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Every terminal command, written down (PLAN.md §10/M16).
 *
 * The third JSONL audit file, after `fs-tool-audit.jsonl` and `mcp-tool-audit.jsonl`, and it keeps
 * their rule: **the command text, the directory, the outcome and how it was approved — never the
 * output.** Output can hold secrets a command happened to print, and the audit's job is to answer
 * "what was run on my machine, and did I agree to it?", which the command line and the approval
 * answer already do.
 *
 * Refusals and timeouts are recorded too: a record of the commands that ran is a receipt, while a
 * record of the ones that were stopped or denied is the audit — it is how a user answers "did
 * something try to run this?" without trusting the UI.
 */
internal data class CommandAuditEntry(
    val timestamp: Long,
    val persona: String,
    /** The exact command line the model proposed. This is the record's reason to exist. */
    val command: String,
    /** The workspace folder that was the working directory. */
    val root: String,
    /**
     * How many characters of stdin the call supplied. **Not the text itself**: like a command's
     * output, input can carry a credential a model was tricked into using, and the audit must not
     * become a secret store. The command line identifies the record; this says whether the dialog
     * the user approved had an input block.
     */
    val stdinChars: Int = 0,
    /** M16.2: whether the call asked for a pseudo-terminal. */
    val tty: Boolean = false,
    val exitCode: Int?,
    val timedOut: Boolean,
    val truncated: Boolean,
    val durationMs: Long,
    /** `ok`, `error`, `denied`, `timeout`. */
    val result: String,
    /** `user`, `session-trust`, `denied`, `timeout`. */
    val confirmed: String,
) {
    internal fun toJson(): JSONObject = JSONObject()
        .put("ts", timestamp)
        .put("persona", persona)
        .put("command", command)
        .put("root", root)
        .put("stdinChars", stdinChars)
        .put("tty", tty)
        .put("exit", exitCode ?: JSONObject.NULL)
        .put("timedOut", timedOut)
        .put("truncated", truncated)
        .put("durationMs", durationMs)
        .put("result", result)
        .put("confirmed", confirmed)

    internal companion object {
        internal fun fromJson(json: JSONObject): CommandAuditEntry? = runCatching {
            CommandAuditEntry(
                timestamp = json.getLong("ts"),
                persona = json.optString("persona"),
                command = json.optString("command"),
                root = json.optString("root"),
                stdinChars = json.optInt("stdinChars"),
                tty = json.optBoolean("tty"),
                exitCode = json.opt("exit")?.takeIf { it != JSONObject.NULL }?.toString()?.toIntOrNull(),
                timedOut = json.optBoolean("timedOut"),
                truncated = json.optBoolean("truncated"),
                durationMs = json.optLong("durationMs"),
                result = json.optString("result"),
                confirmed = json.optString("confirmed"),
            )
        }.getOrNull()
    }
}

internal const val COMMAND_AUDIT_FILE = "command-audit.jsonl"

private const val COMMAND_AUDIT_MAX_BYTES = 1_048_576L
private const val COMMAND_AUDIT_KEEP_LINES = 2000

/**
 * Append one record. Never throws and never fails a turn: the audit is a record of what ran, so a
 * full disk must not turn a successful command into an error. A failure is logged to the same
 * stderr the systemd unit forwards to journald.
 */
internal fun appendCommandAudit(stateDir: Path, entry: CommandAuditEntry, logger: AppLogger) {
    runCatching {
        Files.createDirectories(stateDir)
        val file = stateDir.resolve(COMMAND_AUDIT_FILE)
        if (Files.exists(file) && Files.size(file) > COMMAND_AUDIT_MAX_BYTES) {
            val kept = Files.readAllLines(file).takeLast(COMMAND_AUDIT_KEEP_LINES)
            Files.write(file, kept)
        }
        Files.writeString(
            file,
            entry.toJson().toString() + "\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }.onFailure { logger.w("CommandAudit", "could not write the command audit record: ${it.message}") }
}

/** The newest [limit] records, newest first. Unreadable lines are skipped, not fatal. */
internal fun readCommandAudit(stateDir: Path, limit: Int): List<CommandAuditEntry> {
    val file = stateDir.resolve(COMMAND_AUDIT_FILE)
    if (!Files.isReadable(file)) return emptyList()
    return runCatching {
        Files.readAllLines(file)
            .asReversed()
            .mapNotNull { line -> runCatching { CommandAuditEntry.fromJson(JSONObject(line)) }.getOrNull() }
            .take(limit)
    }.getOrDefault(emptyList())
}
