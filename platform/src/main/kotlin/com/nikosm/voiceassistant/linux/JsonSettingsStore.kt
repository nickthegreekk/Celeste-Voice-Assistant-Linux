package com.nikosm.voiceassistant.linux

import com.nikosm.voiceassistant.AppEnv
import com.nikosm.voiceassistant.AppLogger
import com.nikosm.voiceassistant.SettingsEdit
import com.nikosm.voiceassistant.SettingsStore
import com.nikosm.voiceassistant.StderrLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Seam 7 on Linux: one JSON object in a file, holding the settings blob (PLAN.md §5 D7).
 *
 * The file is `{"<key>": <value>, …}` — a plain object whose keys are the same keys
 * `SharedPreferences` used, so a person can open it, read it, and fix it by hand. That is worth
 * something: the settings blob is the one piece of state a user is ever asked to inspect when
 * something goes wrong, and README.md promises them the path.
 *
 * Three properties this implementation has to keep, each because a caller depends on it:
 *
 *  - **Atomic writes.** Every commit lands via a temporary file and an atomic rename. The settings
 *    blob is written from `Dispatchers.IO` while the UI reads it from the main thread, and a
 *    process killed mid-write must leave either the old file or the new one — never a truncated
 *    one. A half-written settings file is not a corruption the app can recover *from*, because
 *    the recovery would throw away everything the user configured.
 *
 *  - **0600, from birth.** The blob holds server credentials and the RAG password (see
 *    `docs/PROVENANCE.md` for why they stay here rather than in libsecret), so the file is created
 *    with the mode already on it rather than chmod'ed afterwards — there is no window in which it
 *    is readable by anyone else, not even the momentary one between create and chmod.
 *
 *  - **A corrupt file is quarantined, not deleted.** If the file will not parse, it is moved aside
 *    to `settings.json.corrupt-<epoch>` and the store starts empty, loudly. `SettingsManager`'s
 *    own damage limitation is per-entry — one unreadable value degrades to its default and the
 *    rest survive — and this keeps the whole-file case in the same spirit: the app comes up, and
 *    the evidence of what was there is still on disk for the person to look at.
 *
 * In-memory state is the source of truth between commits. Reads take a lock because the service
 * genuinely reads and writes this from different dispatchers — `persistSettings` runs on IO while
 * the model catalog and settings screens read on Main — and the map is mutated in place.
 */
class JsonSettingsStore(
    private val file: Path,
    private val logger: AppLogger = StderrLogger,
) : SettingsStore {

    private val lock = Any()

    /** Deliberately not a live view: see [allKeys]. */
    private val values: MutableMap<String, Any> = load()

    override fun getString(key: String, default: String?): String? = read(key, default) { it as? String }

    override fun getLong(key: String, default: Long): Long = read(key, default) { it as? Long }

    override fun getFloat(key: String, default: Float): Float =
        read(key, default) { (it as? Number)?.toFloat() }

    override fun getBoolean(key: String, default: Boolean): Boolean = read(key, default) { it as? Boolean }

    override fun allKeys(): Set<String> = synchronized(lock) { values.keys.toSet() }

    override fun edit(): SettingsEdit = JsonSettingsEdit(::applyChanges)

    private fun <T> read(key: String, default: T, convert: (Any) -> T?): T {
        val stored = synchronized(lock) { values[key] }
        if (stored == null) return default
        val converted = convert(stored)
        if (converted == null) {
            // A value of the wrong type. Android would throw ClassCastException here; returning
            // the default instead matches what the rest of this application does with damage it
            // cannot interpret — one bad entry degrades to "unset" and everything else loads. It
            // is logged rather than swallowed, because a type mismatch is a bug in whoever wrote
            // it, not a condition to tolerate quietly.
            logger.w(TAG, "settings key '$key' holds a ${stored::class.simpleName}, not a ${default?.let { it::class.simpleName }} — using the default")
            return default
        }
        return converted
    }

    private fun applyChanges(changes: Map<String, Any?>) {
        synchronized(lock) {
            for ((key, value) in changes) {
                if (value == null) values.remove(key) else values[key] = value
            }
            write()
        }
    }

    private fun load(): MutableMap<String, Any> {
        if (!Files.exists(file)) return mutableMapOf()
        val text = try {
            Files.readString(file)
        } catch (e: IOException) {
            logger.e(TAG, "could not read ${file.fileName} — starting from empty settings", e)
            return mutableMapOf()
        }
        if (text.isBlank()) return mutableMapOf()

        return try {
            val obj = JSON.parseToJsonElement(text).jsonObject
            val decoded = mutableMapOf<String, Any>()
            for ((key, element) in obj) {
                val value = decode(element)
                if (value != null) decoded[key] = value
            }
            decoded
        } catch (e: Exception) {
            quarantine(e)
            mutableMapOf()
        }
    }

    /** `null` for a JSON null, a nested object or an array — none of which this store writes. */
    private fun decode(element: JsonElement): Any? {
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive.isString) return primitive.content
        primitive.longOrNull?.let { return it }
        primitive.doubleOrNull?.let { return it }
        return when (primitive.content) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    private fun encode(value: Any): JsonElement = when (value) {
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        is Float -> JsonPrimitive(value)
        else -> throw IllegalArgumentException("settings values are String, Long, Float or Boolean, not ${value::class}")
    }

    /**
     * Moves an unparseable file aside rather than overwriting it.
     *
     * The timestamp is in the name so a second failure cannot clobber the first one's evidence —
     * which matters, because the failure this recovers from is precisely the one where somebody
     * will want to see what the file used to say.
     *
     * **Its contents are never logged.** The blob holds the provider keys and the RAG password, and
     * a log line is what journald keeps; the file path and the parse exception are the diagnosis.
     */
    private fun quarantine(cause: Exception) {
        val aside = file.resolveSibling("${file.fileName}.corrupt-${System.currentTimeMillis()}")
        try {
            Files.move(file, aside)
            logger.e(
                TAG,
                "settings file would not parse (${cause.message}) — moved to ${aside.fileName} " +
                    "and starting from empty settings. The old file is kept for inspection.",
                cause,
            )
        } catch (e: IOException) {
            logger.e(
                TAG,
                "settings file would not parse and could not be moved aside — it is left in place for inspection",
                e,
            )
        }
    }

    private fun write() {
        try {
            file.parent?.let { Files.createDirectories(it) }
            val json = buildJsonObject { for ((key, value) in values) put(key, encode(value)) }
            val text = JSON.encodeToString(JsonObject.serializer(), json)

            // A sibling temp file, so the rename below stays on one filesystem and is therefore
            // atomic. A temp in /tmp would make it a copy.
            val tmp = file.resolveSibling("${file.fileName}.tmp")
            writePrivate(tmp, text)
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            // Loud, but not fatal: a settings write that fails must not take the application
            // down, and the in-memory state is still correct for the rest of the session.
            logger.e(TAG, "could not write ${file.fileName} — settings will not survive a restart", e)
        }
    }

    private fun writePrivate(path: Path, text: String) {
        try {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(PRIVATE))
        } catch (_: FileAlreadyExistsException) {
            // Left behind by an interrupted write. About to be truncated and overwritten.
        } catch (_: UnsupportedOperationException) {
            // Not a POSIX filesystem. Nothing to set and nothing sensible to do about it.
        }
        Files.write(path, text.toByteArray(Charsets.UTF_8))
    }

    companion object {
        const val TAG = "SettingsStore"

        /** README.md, *Where things live*: `~/.config/celeste-linux/settings.json`. */
        const val FILE_NAME = "settings.json"

        private val PRIVATE: Set<PosixFilePermission> = PosixFilePermissions.fromString("rw-------")

        // Indented rather than compact: the file is one a user may be asked to open and read, and
        // one key per line is the difference between readable and a wall of escaped JSON. The
        // indent has to be set *alongside* prettyPrint — on its own the builder refuses it
        // ("Indent should not be specified when default printing mode is used").
        private val JSON = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }

        /** The store for [appEnv]'s settings file. */
        fun forEnv(appEnv: AppEnv, logger: AppLogger = appEnv.logger): JsonSettingsStore =
            JsonSettingsStore(appEnv.configDir.resolve(FILE_NAME), logger)
    }
}

/**
 * A staged set of writes, applied by the store in one go.
 *
 * `null` in the map means "remove": `SharedPreferences.Editor` has the same conflation, and it is
 * what lets `migratePersonaHistory`'s move — write the new key, drop the old one — be one commit
 * rather than two writes with a window in between where neither name exists.
 *
 * Holds nothing but the pending map, so an edit that is never committed has no effect at all.
 * Android's editor behaves the same way, which matters because `SettingsManager` is being adapted
 * rather than rewritten and a leaked uncommitted edit there must stay harmless.
 */
private class JsonSettingsEdit(
    private val commitAll: (Map<String, Any?>) -> Unit,
) : SettingsEdit {

    private val changes = LinkedHashMap<String, Any?>()

    override fun putString(key: String, value: String): SettingsEdit = also { changes[key] = value }

    override fun putLong(key: String, value: Long): SettingsEdit = also { changes[key] = value }

    override fun putFloat(key: String, value: Float): SettingsEdit = also { changes[key] = value }

    override fun putBoolean(key: String, value: Boolean): SettingsEdit = also { changes[key] = value }

    override fun remove(key: String): SettingsEdit = also { changes[key] = null }

    override fun commit() {
        commitAll(changes)
    }
}
