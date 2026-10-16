package com.nikosm.voiceassistant

import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Every tool call, written down (PLAN.md §10/M8: *"every tool call logged — path, operation, size,
 * timestamp, persona — somewhere the user can audit"*).
 *
 * **Append-only JSONL under `stateDir`, and this is its first tenant.** README already documents that
 * directory as holding logs and usage counters; until now nothing wrote there. One JSON object per
 * line, so a truncated tail from a kill is a line the reader skips rather than a file that will not
 * parse, and so `jq`/`grep` work without this application.
 *
 * **Refusals are recorded too, and they are the interesting half.** A record of the reads that
 * succeeded is a receipt; a record of the paths that were refused, and why, is the audit — it is how
 * a user answers "did something try to read my keys?" without trusting the UI.
 */
internal data class FsToolAuditEntry(
    val timestamp: Long,
    val persona: String,
    /** `list_files`, `read_file`, `write_file` — the tool's own name, not a paraphrase. */
    val operation: String,
    /** The resolved absolute path that was actually touched, or would have been. */
    val path: String,
    /** What the model asked for, kept beside it: the difference between the two *is* the finding. */
    val relative: String,
    val root: String,
    val bytes: Long,
    /** `ok`, `denied`, `error`. */
    val result: String,
    /** For a write: `user`, `session-trust`, `denied`, `timeout`; otherwise `n/a`. */
    val confirmed: String,
    val detail: String? = null,
) {
    internal fun toJson(): JSONObject = JSONObject()
        .put("ts", timestamp)
        .put("persona", persona)
        .put("op", operation)
        .put("path", path)
        .put("relative", relative)
        .put("root", root)
        .put("bytes", bytes)
        .put("result", result)
        .put("confirmed", confirmed)
        .put("detail", detail ?: JSONObject.NULL)

    internal companion object {
        internal fun fromJson(json: JSONObject): FsToolAuditEntry? = runCatching {
            FsToolAuditEntry(
                timestamp = json.getLong("ts"),
                persona = json.optString("persona"),
                operation = json.getString("op"),
                path = json.optString("path"),
                relative = json.optString("relative"),
                root = json.optString("root"),
                bytes = json.optLong("bytes"),
                result = json.optString("result"),
                confirmed = json.optString("confirmed"),
                detail = json.opt("detail")?.takeIf { it != JSONObject.NULL }?.toString(),
            )
        }.getOrNull()
    }
}

internal const val FS_AUDIT_FILE = "fs-tool-audit.jsonl"

/** Past this the writer compacts to the newest [FS_AUDIT_KEEP_LINES] lines rather than growing. */
private const val FS_AUDIT_MAX_BYTES = 1_048_576L
private const val FS_AUDIT_KEEP_LINES = 2000

/**
 * Append one record. **Never throws and never fails a turn**: the audit is a record of what the
 * tools did, so a full disk must not turn a successful read into an error. A failure is logged to
 * the same stderr the systemd unit forwards to journald, which is the honest fallback.
 */
internal fun appendFsToolAudit(stateDir: Path, entry: FsToolAuditEntry, logger: AppLogger) {
    runCatching {
        Files.createDirectories(stateDir)
        val file = stateDir.resolve(FS_AUDIT_FILE)
        if (Files.exists(file) && Files.size(file) > FS_AUDIT_MAX_BYTES) {
            val kept = Files.readAllLines(file).takeLast(FS_AUDIT_KEEP_LINES)
            Files.write(file, kept)
        }
        Files.writeString(
            file,
            entry.toJson().toString() + "\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }.onFailure { logger.w("FsToolAudit", "could not write the filesystem audit record: ${it.message}") }
}

/** The newest [limit] records, newest first. Unreadable lines are skipped, not fatal. */
internal fun readFsToolAudit(stateDir: Path, limit: Int): List<FsToolAuditEntry> {
    val file = stateDir.resolve(FS_AUDIT_FILE)
    if (!Files.isReadable(file)) return emptyList()
    return runCatching {
        Files.readAllLines(file)
            .asReversed()
            .mapNotNull { line -> runCatching { FsToolAuditEntry.fromJson(JSONObject(line)) }.getOrNull() }
            .take(limit)
    }.getOrDefault(emptyList())
}
