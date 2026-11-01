package com.nikosm.voiceassistant

import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * M19: every screen capture, written down.
 *
 * The fourth JSONL audit file, after the filesystem, MCP and command ones, and it keeps their
 * rule: **what was asked, when, and whether the user approved it — never the screenshot and never
 * the description.** A screenshot contains whatever was on screen at that moment; a description
 * can repeat any of it. The audit answers "did something look at my screen, and did I say yes?",
 * which the question and the decision already do.
 *
 * Refusals and timeouts are recorded too, for the same reason the command audit records them: a
 * record of captures that happened is a receipt, while a record of the ones that were stopped is
 * the audit.
 */
internal data class ScreenAuditEntry(
    val timestamp: Long,
    val persona: String,
    /** The question the model asked, bounded, as shown in the approval dialog. */
    val question: String,
    /** The PNG's size, or 0 when nothing was captured. Never the image. */
    val bytes: Int = 0,
    val durationMs: Long = 0,
    /** `ok`, `error`, `denied`, `timeout`. */
    val result: String,
    /** `user`, `denied`, `timeout`. */
    val confirmed: String,
) {
    internal fun toJson(): JSONObject = JSONObject()
        .put("ts", timestamp)
        .put("persona", persona)
        .put("question", question)
        .put("bytes", bytes)
        .put("durationMs", durationMs)
        .put("result", result)
        .put("confirmed", confirmed)

    internal companion object {
        internal fun fromJson(json: JSONObject): ScreenAuditEntry? = runCatching {
            ScreenAuditEntry(
                timestamp = json.getLong("ts"),
                persona = json.optString("persona"),
                question = json.optString("question"),
                bytes = json.optInt("bytes"),
                durationMs = json.optLong("durationMs"),
                result = json.optString("result"),
                confirmed = json.optString("confirmed"),
            )
        }.getOrNull()
    }
}

internal const val SCREEN_AUDIT_FILE = "screen-capture-audit.jsonl"

private const val SCREEN_AUDIT_MAX_BYTES = 1_048_576L
private const val SCREEN_AUDIT_KEEP_LINES = 2000

/**
 * Append one record. Never throws and never fails a turn, like [appendCommandAudit]: a full disk
 * must not turn a successful look into an error.
 */
internal fun appendScreenAudit(stateDir: Path, entry: ScreenAuditEntry, logger: AppLogger) {
    runCatching {
        Files.createDirectories(stateDir)
        val file = stateDir.resolve(SCREEN_AUDIT_FILE)
        if (Files.exists(file) && Files.size(file) > SCREEN_AUDIT_MAX_BYTES) {
            val kept = Files.readAllLines(file).takeLast(SCREEN_AUDIT_KEEP_LINES)
            Files.write(file, kept)
        }
        Files.writeString(
            file,
            entry.toJson().toString() + "\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }.onFailure { logger.w("ScreenAudit", "could not write the screen audit record: ${it.message}") }
}

/** The newest [limit] records, newest first. Unreadable lines are skipped, not fatal. */
internal fun readScreenAudit(stateDir: Path, limit: Int): List<ScreenAuditEntry> {
    val file = stateDir.resolve(SCREEN_AUDIT_FILE)
    if (!Files.isReadable(file)) return emptyList()
    return runCatching {
        Files.readAllLines(file)
            .asReversed()
            .mapNotNull { line -> runCatching { ScreenAuditEntry.fromJson(JSONObject(line)) }.getOrNull() }
            .take(limit)
    }.getOrDefault(emptyList())
}
