package com.nikosm.voiceassistant

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID

/**
 * THE Json every persisted type goes through: [Persona], [Room], [RoomMessage],
 * [ChatMessage], [Skill], the server/cloud lists, backups and history files.
 *
 * `encodeDefaults = true` is load-bearing, not decoration. Several models default a
 * field to something derived at CONSTRUCTION time — `Room.createdAt` and
 * `RoomMessage.timestamp` to `System.currentTimeMillis()`, `Room.id`/`RoomMessage.id`/
 * `Skill.id` to a random UUID — and kotlinx.serialization drops any field whose value
 * equals its default when `encodeDefaults` is false. A clock default is re-derived as
 * "now" on decode, so the round trip returns a different millisecond whenever the clock
 * ticks between encoding and decoding; the app would silently rewrite the timestamp of
 * anything it loaded. Emitting the key always means what is stored is exactly what was
 * saved.
 *
 * Declared here, at file scope, rather than privately on [SettingsManager], so the tests
 * that round-trip persisted models can use the production configuration itself instead
 * of a copy that can drift away from it.
 */
internal val SettingsJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = true
}

/**
 * The settings blob, on Linux.
 *
 * Ported from upstream `SettingsManager.kt` (PLAN.md §7, verdict PORT+). Everything to do with
 * *what* is stored is upstream's, unchanged — the corruption-tolerant reads, the legacy
 * migrations from pre-`_v2` key names, the rename and orphan bookkeeping, and the backup format.
 * What changed is *where* it is stored and with what:
 *
 *  - `Context` becomes [AppEnv]; `filesDir` for artwork becomes `dataDir`.
 *  - `SharedPreferences` becomes the [SettingsStore] seam (PLAN.md §5, seam 7), so the key-value
 *    shape and every accessor site port mechanically — which is the whole reason that seam is
 *    key-value rather than a schema.
 *  - `BitmapFactory`/`Bitmap.compress` become the [ImageCodec] seam (§5b).
 *  - `Uri` becomes [Path]. Every `Uri` in this file was only ever used to open one file, and the
 *    phone's `contentResolver` existed to make that safe across a permission grant; a desktop
 *    process reading a path it was handed needs none of it.
 *  - `android.util.Base64` becomes `java.util.Base64`.
 *
 * ## What was dropped, and why it is not a loss
 *
 * Upstream opens its store through `createEncryptedPrefsWithRecovery`, about fifty lines whose
 * entire purpose is to survive a documented Android Keystore bug: `EncryptedSharedPreferences`
 * can fail to initialise on some Pixel/Android 13 combinations, so the code deletes the corrupted
 * Keystore alias, deletes the undecryptable preferences file, retries, and — if the retry also
 * fails — falls back to *unencrypted* storage rather than crashing. The comment upstream warns the
 * user that "credentials/settings will not be encrypted at rest on this device".
 *
 * There is no Keystore here and no `EncryptedSharedPreferences`, so there is nothing to corrupt
 * and nothing to recover from. Credentials live in the settings file at mode 0600 by decision
 * (PLAN.md §5a), which is what the phone's fallback path produced anyway on the devices the
 * recovery existed for. The removal is recorded in `docs/PROVENANCE.md`.
 *
 * **The damage limitation that actually protects the user's data is untouched and is not the same
 * thing.** Every read below wraps its parse in `runCatching` and degrades *one entry* to its
 * default, so a single corrupt persona or room transcript costs that entry and nothing else; and
 * [JsonSettingsStore] quarantines a settings file that will not parse at all rather than
 * overwriting it. Those two together are the protection, and both survived the port.
 */
class SettingsManager(
    private val appEnv: AppEnv,
    private val store: SettingsStore,
    private val imageCodec: ImageCodec,
) {

    private val logger = appEnv.logger

    private companion object {
        const val TAG = "SettingsManager"

        // C3's cap, raised from 200 to 2000 on 2026-10-17 at the user's request: the phone-sized
        // number was trimming a desktop transcript that is a single JSON entry in an ordinary
        // settings file. 2000 is 1000 exchanges, and eviction below still deletes the cache files
        // of every message it drops.
        const val MAX_PERSONA_MESSAGES = 2000

        // Same idea for room transcripts, which are separate entries entirely. A room
        // round can emit several messages per turn, so its cap is a little higher.
        const val MAX_ROOM_MESSAGES = 400

        // Persona artwork is COPIED into app-owned storage rather than persisted as
        // the raw photo-picker URI. A picked content:// URI is only readable while its
        // grant lasts, and that grant dies with the process; the user moving or
        // deleting the original photo would break it too. An app-owned copy survives
        // both, and needs no persistent URI-permission request at pick time.
        const val PERSONA_IMAGE_DIR = "persona_images"

        // Longest edge, in px, each slot is downsampled to before being written.
        // An icon is only ever drawn at <=40dp (ProviderLogo), so 512px stays crisp
        // on any density; a backdrop is drawn at screen size, so 1080px covers
        // 1080p-class panels. Keeping these bounded also keeps the backup's base64
        // payload (a 4/3 size inflation) from ballooning.
        //
        // These are behaviour, not tuning knobs: PLAN.md §5b records them as part of the
        // image seam's contract, so moving one is a decision rather than a tweak.
        const val ICON_MAX_DIM = 512
        const val BACKGROUND_MAX_DIM = 1080
        const val MESSAGE_IMAGE_MAX_DIM = 1024

        // The phone's Bitmap.compress qualities, kept because they are what every artwork file
        // already on disk was written at.
        const val ARTWORK_JPEG_QUALITY = 88
        const val MESSAGE_IMAGE_JPEG_QUALITY = 85

        const val DEFAULT_CHAT_FONT_SIZE = 14f

        /** The settings key prefix for a persona's chosen output device; see the accessors above. */
        const val PERSONA_OUTPUT_DEVICE_PREFIX = "persona_output_device_"

        /**
         * The two prefixes for a persona's filesystem access (PLAN.md §10/M8): the mode and the
         * workspace folder. Two keys rather than one encoded value because the persona may override
         * either independently — a different folder at the global trust level is a reasonable thing
         * to want, and a single value would have to be re-spelled in full to say so.
         */
        const val PERSONA_FS_MODE_PREFIX = "persona_fs_mode_"
        const val PERSONA_FS_WORKSPACE_PREFIX = "persona_fs_workspace_"

        /**
         * The workspaces the user has told the write gate to stop asking about (2026-10-17).
         *
         * **Not a `BackupData` field, deliberately** — which is what makes persisting it safe. The
         * backup is an allowlist, the files settings are not on it, and neither is this: a restored
         * backup cannot re-grant write access to a folder, because it can neither restore the folder
         * nor the grant. That is the whole of the design's original objection, answered structurally
         * rather than by keeping the value out of storage.
         */
        const val TRUSTED_FS_ROOTS = "trusted_fs_roots"

        /**
         * The MCP servers, as one JSON list (PLAN.md §10/M9). Their `env` and `headers` carry the
         * secrets a server needs — the same kind of value the provider keys beside them are — and
         * they live in this file at mode 0600. `trust` rides in each entry so a grant survives a
         * restart, and a changed command/args/URL revokes it where the list is saved.
         */
        const val MCP_SERVERS = "mcp_servers_v1"

        /** Which servers a persona offers, name-keyed like the filesystem pair. */
        const val MCP_PERSONA_PREFIX = "mcp_persona_"

        /**
         * Long-term memory per persona (PLAN.md §10/M10): one JSON list of notes, and the switch
         * that offers the `remember` tool. The switch is a key of its own rather than "enabled when
         * notes exist", so a user can turn the feature on before there is anything to remember.
         */
        const val PERSONA_MEMORY_PREFIX = "persona_memory_"
        const val PERSONA_MEMORY_ENABLED_PREFIX = "persona_memory_enabled_"

        /**
         * The window's size in **dp**, as the user left it — "WIDTHxHEIGHT", the shape a person can
         * read in the settings file and a test can write by hand (2026-10-17, by request).
         *
         * dp rather than pixels because that is what Compose's `rememberWindowState` takes, so a
         * restored size cannot drift when the display's scaling changes. A value this build cannot
         * parse is `null`, which falls back to the default window — the tolerant-decoder shape every
         * other setting here uses.
         */
        const val WINDOW_SIZE = "window_size"

        /** Whether the size above is kept. On by default: a resize the app forgets is the surprise. */
        const val REMEMBER_WINDOW_SIZE = "remember_window_size"
    }

    // The shared production Json, not a second copy of it — see [SettingsJson].
    private val json = SettingsJson

    fun saveServerBases(bases: List<ServerConfig>) {
        store.edit().putString("server_bases_v2", json.encodeToString(bases)).commit()
    }

    fun getServerBases(): List<ServerConfig>? {
        val data = store.getString("server_bases_v2")
        if (data != null) {
            return runCatching { json.decodeFromString<List<ServerConfig>>(data) }
                .onFailure { e ->
                    logger.w(
                        TAG,
                        "Corrupted/unreadable JSON in server_bases_v2 — ignoring entry, returning null",
                        e
                    )
                }
                .getOrNull()
        }

        // Legacy Migration
        val oldJson = store.getString("server_bases") ?: return null
        return try {
            val array = JSONArray(oldJson)
            List(array.length()) { i -> ServerConfig("Server ${i + 1}", array.getString(i)) }
        } catch (e: Exception) { null }
    }

    fun saveOllamaBases(bases: List<ServerConfig>) {
        store.edit().putString("ollama_bases_v2", json.encodeToString(bases)).commit()
    }

    fun getOllamaBases(): List<ServerConfig>? {
        val data = store.getString("ollama_bases_v2")
        if (data != null) {
            return runCatching { json.decodeFromString<List<ServerConfig>>(data) }
                .onFailure { e ->
                    logger.w(
                        TAG,
                        "Corrupted/unreadable JSON in ollama_bases_v2 — ignoring entry, returning null",
                        e
                    )
                }
                .getOrNull()
        }

        // Legacy Migration
        val oldJson = store.getString("ollama_bases") ?: return null
        return try {
            val array = JSONArray(oldJson)
            List(array.length()) { i -> ServerConfig("Ollama ${i + 1}", array.getString(i)) }
        } catch (e: Exception) { null }
    }

    fun saveImageGenBases(bases: List<ServerConfig>) {
        store.edit().putString("image_gen_bases_v2", json.encodeToString(bases)).commit()
    }

    fun getImageGenBases(): List<ServerConfig>? {
        val data = store.getString("image_gen_bases_v2")
        if (data != null) {
            return runCatching { json.decodeFromString<List<ServerConfig>>(data) }
                .onFailure { e ->
                    logger.w(
                        TAG,
                        "Corrupted/unreadable JSON in image_gen_bases_v2 — ignoring entry, returning null",
                        e
                    )
                }
                .getOrNull()
        }
        return null
    }

    /**
     * The image backend Celeste generates with — the two-section Image Gen screen's switch.
     *
     * Stored as the enum NAME and decoded through [imageGenBackendFromName], so an absent (every
     * existing install), unknown or hand-edited value degrades to [DEFAULT_IMAGE_GEN_BACKEND] rather
     * than breaking the setting. Unset therefore means "behaves exactly as before this existed".
     */
    fun saveActiveImageGenBackend(backend: ImageGenBackend) {
        store.edit().putString("active_image_gen_backend", backend.name).commit()
    }

    fun getActiveImageGenBackend(): ImageGenBackend =
        imageGenBackendFromName(store.getString("active_image_gen_backend"))

    fun saveCloudApis(apis: List<CloudApiSetting>) {
        store.edit().putString("cloud_apis_v2", json.encodeToString(apis)).commit()
    }

    fun getCloudApis(): List<CloudApiSetting>? {
        val data = store.getString("cloud_apis_v2")
        if (data != null) {
            return runCatching { json.decodeFromString<List<CloudApiSetting>>(data) }
                .onFailure { e ->
                    logger.w(
                        TAG,
                        "Corrupted/unreadable JSON in cloud_apis_v2 — ignoring entry, returning null",
                        e
                    )
                }
                .getOrNull()
        }

        // Legacy Migration
        val oldJson = store.getString("cloud_apis") ?: return null
        return try {
            val array = JSONArray(oldJson)
            List(array.length()) { i ->
                val obj = array.getJSONObject(i)
                CloudApiSetting(
                    name = obj.getString("name"),
                    baseUrl = obj.getString("baseUrl"),
                    apiKey = obj.getString("apiKey"),
                    icon = obj.getString("icon"),
                    color = androidx.compose.ui.graphics.Color(obj.getInt("color")),
                    isEditableUrl = obj.optBoolean("isEditableUrl", false)
                )
            }
        } catch (e: Exception) { null }
    }

    fun saveCustomCloudApis(apis: List<CloudApiSetting>) {
        store.edit().putString("custom_cloud_apis_v1", json.encodeToString(apis)).commit()
    }

    fun getCustomCloudApis(): List<CloudApiSetting>? {
        val data = store.getString("custom_cloud_apis_v1")
        if (data != null) {
            return runCatching { json.decodeFromString<List<CloudApiSetting>>(data) }
                .onFailure { e ->
                    logger.w(
                        TAG,
                        "Corrupted/unreadable JSON in custom_cloud_apis_v1 — ignoring entry, returning null",
                        e
                    )
                }
                .getOrNull()
        }
        return null
    }

    fun savePersonas(personas: List<Persona>) {
        // Spontaneous cloud guard (plan §6). This is the ONLY writer of personas_v2, so
        // it is the chokepoint every persisted write reaches — AssistantService.persistSettings
        // (editor commits, add/update, every other persona mutation) AND importBackup below.
        // Guarding here rather than only in AssistantService.updatePersona is what closes the
        // import path: importBackup calls savePersonas directly and never reaches updatePersona,
        // so an imported backup carrying spontaneousEnabled=true on a cloud persona would
        // otherwise persist untouched.
        //
        // The server lists are read from this same store, so a backup is judged against the
        // server lists IT carried — importBackup persists those before reaching this call —
        // rather than against whatever happened to be configured on the device beforehand.
        //
        // The `none` check short-circuits the three extra reads on the overwhelmingly common
        // path (nobody has this enabled). It is exact, not an optimisation that trades
        // correctness: sanitize only ever changes a persona that is enabled yet ineligible.
        val toStore = if (personas.none { it.spontaneousEnabled }) {
            personas
        } else {
            val ollamaNames = getOllamaBases().orEmpty().map { it.name }.toSet()
            val cloudNames = (getCloudApis().orEmpty() + getCustomCloudApis().orEmpty()).map { it.name }.toSet()
            personas.map { SpontaneousPolicy.sanitize(it, ollamaNames, cloudNames) }
        }
        // Name uniqueness, persistence chokepoint — the same two-location shape as the cloud
        // guard above, and the reason `importBackup` cannot reintroduce a duplicate either:
        // it calls savePersonas directly and never reaches AssistantService.addPersona. A
        // backup written by a build with the flag-only translator lookup (or hand-edited)
        // can legitimately carry two personas named "Translator", and persisting that is what
        // makes the damage real: both would then load back and share one history key.
        //
        // First occurrence wins, deterministically, and the drop is logged rather than
        // swallowed. Refusing the whole save is not an option here — this writer is reached
        // by ordinary edits too, and silently discarding them would be worse than the
        // duplicate. In-memory the service refuses such a write outright; storage only has
        // to make sure nothing duplicate ever reaches disk.
        val seen = HashSet<String>(toStore.size)
        val unique = toStore.filter { seen.add(it.name) }
        if (unique.size != toStore.size) {
            val kept = unique.mapTo(HashSet()) { it.name }
            logger.w(
                TAG,
                "savePersonas dropped duplicate-named personas: " +
                    toStore.map { it.name }.filterNot { it in kept }
            )
        }
        store.edit().putString("personas_v2", json.encodeToString(unique)).commit()
    }

    fun getPersonas(): List<Persona>? {
        val data = store.getString("personas_v2")
        if (data != null) {
            return runCatching { json.decodeFromString<List<Persona>>(data) }
                .onFailure { e ->
                    logger.w(
                        TAG,
                        "Corrupted/unreadable JSON in personas_v2 — ignoring entry, returning null",
                        e
                    )
                }
                .getOrNull()
        }

        // Legacy Migration
        val oldJson = store.getString("personas") ?: return null
        return try {
            val array = JSONArray(oldJson)
            List(array.length()) { i ->
                val obj = array.getJSONObject(i)
                Persona(
                    name = obj.getString("name"),
                    themeColor = androidx.compose.ui.graphics.Color(obj.getInt("themeColor")),
                    model = obj.getString("model"),
                    systemPrompt = obj.getString("systemPrompt"),
                    isCloud = obj.getBoolean("isCloud"),
                    providerIcon = obj.getString("providerIcon"),
                    providerColor = androidx.compose.ui.graphics.Color(obj.getInt("providerColor")),
                    isTranslator = obj.getBoolean("isTranslator"),
                    targetLanguage = obj.optString("targetLanguage", "English"),
                    backendUrl = obj.optString("backendUrl", "")
                )
            }
        } catch (e: Exception) { null }
    }

    // ----------------------------------------------------------- Spontaneous state

    private val SPONTANEOUS_STATE_KEY = "spontaneous_state_v1"

    /**
     * Read every persona's delivery state at once. Corruption-tolerant like every other
     * stored-format read in here: one bad entry degrades to "no state", never to a startup
     * crash.
     */
    fun getSpontaneousStates(): Map<String, SpontaneousState> {
        val data = store.getString(SPONTANEOUS_STATE_KEY) ?: return emptyMap()
        return runCatching { json.decodeFromString<Map<String, SpontaneousState>>(data) }
            .onFailure { e ->
                logger.w(
                    TAG,
                    "$SPONTANEOUS_STATE_KEY is corrupted/unreadable — ignoring entry, returning no state",
                    e
                )
            }
            .getOrDefault(emptyMap())
    }

    fun spontaneousStateFor(personaName: String): SpontaneousState =
        getSpontaneousStates()[personaName] ?: SpontaneousState()

    /**
     * Record that [personaName] actually SENT a message at [at] on [todayDate].
     *
     * Only ever called on a genuine send — silence and failures leave the state untouched,
     * which is what stops a persona that keeps declining from pushing its own gap forward
     * and never speaking again.
     */
    fun recordSpontaneousSent(personaName: String, at: Long, todayDate: String) {
        val current = spontaneousStateFor(personaName)
        val sameDay = current.sentTodayDate == todayDate
        val updated = SpontaneousState(
            lastSentAt = at,
            sentTodayCount = if (sameDay) current.sentTodayCount + 1 else 1,
            sentTodayDate = todayDate
        )
        val all = getSpontaneousStates() + (personaName to updated)
        store.edit().putString(SPONTANEOUS_STATE_KEY, json.encodeToString(all)).commit()
    }

    fun deleteSpontaneousState(personaName: String) {
        val all = getSpontaneousStates()
        if (personaName !in all) return
        store.edit().putString(SPONTANEOUS_STATE_KEY, json.encodeToString(all - personaName)).commit()
    }

    fun saveMessages(messages: List<ChatMessage>) {
        store.edit().putString("chat_history_v2", json.encodeToString(messages)).commit()
    }

    fun getMessages(): List<ChatMessage>? {
        val data = store.getString("chat_history_v2")
        if (data != null) {
            return runCatching { json.decodeFromString<List<ChatMessage>>(data) }
                .onFailure { e ->
                    logger.w(
                        TAG,
                        "Corrupted/unreadable JSON in chat_history_v2 — ignoring entry, returning null",
                        e
                    )
                }
                .getOrNull()
        }

        // Legacy Migration
        val oldJson = store.getString("chat_history") ?: return null
        return try {
            val array = JSONArray(oldJson)
            List(array.length()) { i ->
                val obj = array.getJSONObject(i)
                ChatMessage(role = obj.getString("role"), text = obj.getString("text"))
            }
        } catch (e: Exception) { null }
    }

    fun savePersonaMessages(personaName: String, messages: List<ChatMessage>) {
        // C3: cap persisted history per persona. Trim from the oldest end (keep the most
        // recent MAX_PERSONA_MESSAGES). Before discarding any evicted message, delete its
        // audio/image cache files if present — otherwise files become orphaned on disk.
        val capped = if (messages.size > MAX_PERSONA_MESSAGES) {
            val evicted = messages.subList(0, messages.size - MAX_PERSONA_MESSAGES)
            evicted.forEach { msg ->
                msg.audioFilePath?.let { path -> deleteQuietly(path) }
                msg.imagePath?.let { path -> deleteQuietly(path) }
                msg.generatedImagePath?.let { path -> deleteQuietly(path) }
            }
            messages.subList(messages.size - MAX_PERSONA_MESSAGES, messages.size)
        } else messages
        store.edit().putString("persona_messages_$personaName", json.encodeToString(capped)).commit()
    }

    fun getPersonaMessages(personaName: String): List<ChatMessage>? {
        val data = store.getString("persona_messages_$personaName")
        return if (data != null) {
            runCatching { json.decodeFromString<List<ChatMessage>>(data) }
                .onFailure { e ->
                    logger.w(
                        TAG,
                        "Corrupted/unreadable JSON in persona history for '$personaName' — "
                            + "ignoring entry, returning null",
                        e
                    )
                }
                .getOrNull()
        } else null
    }

    // ---------------------------------------------------------------- Rooms
    // Same shape as the persona list/history entries above: one JSON key for the room
    // definitions, one key per room transcript, corruption-tolerant reads.
    fun saveRooms(rooms: List<Room>) {
        store.edit().putString("rooms_v1", json.encodeToString(rooms)).commit()
    }

    fun getRooms(): List<Room>? {
        val data = store.getString("rooms_v1")
        return if (data != null) {
            runCatching { json.decodeFromString<List<Room>>(data) }
                .onFailure { e ->
                    logger.w(TAG, "Corrupted/unreadable JSON in rooms_v1 — ignoring entry, returning null", e)
                }
                .getOrNull()
        } else emptyList()
    }

    // ---------------------------------------------------------------- Skills
    // Same shape as the persona list/history and room entries above: one JSON key for the
    // shared library, corruption-tolerant reads, `emptyList()` when absent so a fresh
    // install and any pre-Skills backup both behave as "no skills defined".
    fun saveSkills(skills: List<Skill>) {
        store.edit().putString("skills_v1", json.encodeToString(skills)).commit()
    }

    fun getSkills(): List<Skill>? {
        val data = store.getString("skills_v1")
        if (data != null) {
            return runCatching { json.decodeFromString<List<Skill>>(data) }
                .onFailure { e ->
                    logger.w(TAG, "Corrupted/unreadable JSON in skills_v1 — ignoring entry, returning null", e)
                }
                .getOrNull()
        }
        return emptyList()
    }

    // ---------------------------------------------------------- Image Characters
    // The shared ComfyUI-workflow library, the same shape as skills_v1 above: one JSON key,
    // corruption-tolerant reads, `emptyList()` when absent so a fresh install and any
    // pre-Characters backup both behave as "no characters defined".
    fun saveImageWorkflows(workflows: List<ImageWorkflow>) {
        store.edit().putString("image_workflows_v1", json.encodeToString(workflows)).commit()
    }

    fun getImageWorkflows(): List<ImageWorkflow>? {
        val data = store.getString("image_workflows_v1")
        if (data != null) {
            return runCatching { json.decodeFromString<List<ImageWorkflow>>(data) }
                .onFailure { e ->
                    logger.w(TAG, "Corrupted/unreadable JSON in image_workflows_v1 — ignoring entry, returning null", e)
                }
                .getOrNull()
        }
        return emptyList()
    }

    fun saveRoomMessages(roomId: String, messages: List<RoomMessage>) {
        val capped = if (messages.size > MAX_ROOM_MESSAGES) {
            messages.subList(messages.size - MAX_ROOM_MESSAGES, messages.size)
        } else messages
        store.edit().putString(roomMessagesKey(roomId), json.encodeToString(capped)).commit()
    }

    fun getRoomMessages(roomId: String): List<RoomMessage>? {
        val data = store.getString(roomMessagesKey(roomId))
        return if (data != null) {
            runCatching { json.decodeFromString<List<RoomMessage>>(data) }
                .onFailure { e ->
                    logger.w(
                        TAG,
                        "Corrupted/unreadable JSON in room history for '$roomId' — ignoring entry, returning null",
                        e
                    )
                }
                .getOrNull()
        } else null
    }

    /**
     * Room deletion support: drop the room's transcript entry so it cannot sit on disk
     * orphaned. Unlike persona history there are no cached audio/image files to reclaim
     * (rooms are text-only in v1, and [RoomMessage] carries no file paths), so this is
     * just the key removal.
     */
    fun deleteRoomHistory(roomId: String) {
        store.edit().remove(roomMessagesKey(roomId)).commit()
    }

    private fun roomMessagesKey(roomId: String) = "room_messages_$roomId"


    // Rename support: history is persisted keyed by persona NAME, so a rename must
    // carry the old name-keyed entry over to the new one — otherwise the old entry
    // is orphaned on disk forever and the renamed persona starts empty. No-op when
    // the names match or the old persona never had saved history. (If the new name
    // collides with an existing persona's history, the renamed persona claims it —
    // persona identity is name-based throughout the app.)
    //
    // F1: the spontaneous delivery state is keyed by the same name, so it is the
    // SHARED rename surface — migratePersonaHistory carries it over too (via
    // migrateSpontaneousState below). The one production caller is
    // AssistantService.updatePersona's rename branch, where a rename must PRESERVE
    // lastSentAt/sentTodayCount, never reset them: a reset would read as "never sent",
    // and a freshly renamed persona could immediately re-message after its rename.
    fun migratePersonaHistory(oldName: String, newName: String) {
        if (oldName == newName) return
        // State first and independently of the history move below: a persona can carry
        // delivery state with no saved history entry at all, and the history read's
        // early-return must not strand the state under the old key.
        migrateSpontaneousState(oldName, newName)
        migratePersonaOutputDevice(oldName, newName)
        migratePersonaFs(oldName, newName)
        migratePersonaMcp(oldName, newName)
        migratePersonaMemory(oldName, newName)
        val data = store.getString("persona_messages_$oldName") ?: return
        store.edit()
            .putString("persona_messages_$newName", data)
            .remove("persona_messages_$oldName")
            .commit()
    }

    /**
     * F1 rename half: read the old key's [SpontaneousState], write it under the new name,
     * remove the old key — the state twin of [migratePersonaHistory]'s history move, with
     * the same claim semantics (an existing entry under the new name is claimed by the
     * renamed persona) and the same no-ops (same name, or nothing to move).
     */
    /**
     * Rename a server inside every stored `"[Server] model"` favorite.
     *
     * Written immediately rather than through the debounced save, because the service
     * renames the server in memory in the same call and any persist after it must already
     * see the new prefix - the same synchronous-migration contract as
     * [migratePersonaHistory].
     */
    fun migrateFavoriteModelServerPrefix(oldName: String, newName: String) {
        if (oldName == newName) return
        val current = getFavoriteModels() ?: return
        val moved = rewriteServerPrefixes(current, oldName, newName)
        if (moved != current) saveFavoriteModels(moved)
    }

    fun migrateSpontaneousState(oldName: String, newName: String) {
        if (oldName == newName) return
        val all = getSpontaneousStates()
        val state = all[oldName] ?: return
        store.edit()
            .putString(SPONTANEOUS_STATE_KEY, json.encodeToString(all - oldName + (newName to state)))
            .commit()
    }

    // ---------------------------------------------------------------------------
    // Persona artwork (custom icon + background image)
    //
    // Stored as app-owned JPEG copies under dataDir/persona_images and referenced
    // from Persona.iconImageUri / Persona.backgroundImageUri by absolute internal
    // path. See Models.kt for why the raw picker URI is never persisted.
    //
    // The fields are still called `...Uri` and still hold a String: renaming them would
    // change the persisted schema and therefore what a backup contains, which is the one
    // thing this port must not do.
    // ---------------------------------------------------------------------------

    private fun artworkDir(): Path = appEnv.dataDir.resolve(PERSONA_IMAGE_DIR)

    // Timestamp + random suffix so two picks in the same millisecond (and any file
    // left over from a previous install) can never collide.
    private fun newArtworkFile(slot: String): Path {
        val dir = artworkDir().also { Files.createDirectories(it) }
        val unique = UUID.randomUUID().toString().take(8)
        return dir.resolve(artworkFileName(slot, System.currentTimeMillis(), unique))
    }

    // Copies a user-picked image into app-owned storage, downsampled per slot.
    // Returns the internal path to persist on the Persona, or null when the pick
    // could not be read/decoded — the caller keeps the previous artwork then.
    //
    // The two-pass decode, the power-of-two subsample and the exact rescale all moved to the
    // ImageCodec seam when this was ported (PLAN.md §5b); the rule they encode is documented
    // there and pinned by ImageIoCodecTest, including the case that is invisible in the output —
    // that a panorama must not be subsampled on its long edge alone.
    fun savePersonaImage(source: Path, slot: String): String? {
        val maxDim = if (slot == "icon") ICON_MAX_DIM else BACKGROUND_MAX_DIM
        val target = newArtworkFile(slot)
        return if (imageCodec.downscaleToJpeg(source, target, maxDim, ARTWORK_JPEG_QUALITY)) {
            target.toAbsolutePath().toString()
        } else null
    }

    fun saveMessageImage(source: Path): String? {
        val target = newArtworkFile(MESSAGE_IMAGE_SLOT)
        return if (imageCodec.downscaleToJpeg(source, target, MESSAGE_IMAGE_MAX_DIM, MESSAGE_IMAGE_JPEG_QUALITY)) {
            target.toAbsolutePath().toString()
        } else null
    }

    // The path a pending composer attachment is SENT as.
    //
    // The share target materializes an incoming file when it arrives, so the composer's
    // attachment already points at one of our own msg_*.jpg files. Re-encoding that here
    // would write a SECOND file and strand the first on every shared-image send, so an
    // attachment that is already ours is reused untouched: it is downsampled to
    // MESSAGE_IMAGE_MAX_DIM already, which is exactly what saveMessageImage would have
    // produced. Anything else (the file picker's own path) is copied in as before,
    // so the transcript still owns a file that survives the picker's grant.
    fun messageImagePath(source: Path): String? = ownedMessageImagePath(source) ?: saveMessageImage(source)

    // Deletes the file behind a pending attachment when it is ours — the composer's remove
    // button. A picked file is not ours, so removing one is a no-op by design: the
    // user's own photo is never touched. Goes through deleteArtworkFile, the single
    // deletion choke point.
    fun discardMessageImage(source: Path) {
        deleteArtworkFile(ownedMessageImagePath(source))
    }

    // source's path when it names an EXISTING message image of ours, else null. Existence is
    // what separates "reuse/delete this file" from "copy it in": a path of ours that is
    // already gone (deleted between the share and the send) must be re-materialized rather
    // than handed on as a dangling imagePath.
    private fun ownedMessageImagePath(source: Path): String? {
        val path = source.toAbsolutePath().toString()
        if (!isMaterializedMessageImagePath(path, artworkDir().toAbsolutePath().toString())) return null
        return path.takeIf { Files.isRegularFile(Path.of(it)) }
    }

    // Deletes one app-owned artwork file. This is the single choke point every
    // artwork deletion goes through: persona removal, a save that replaced or cleared
    // a slot, and the backup-import orphan sweep. Missing files are ignored — a
    // half-deleted state is not an error.
    fun deleteArtworkFile(path: String?) {
        if (path.isNullOrBlank()) return
        deleteQuietly(path)
    }

    // Deletes both files a persona's artwork fields point at. Used by the deletion
    // path and by the backup-import orphan sweep, which has the stale Persona objects
    // in hand even though they are no longer in the stored list.
    fun deletePersonaArtworkFiles(persona: Persona) {
        deleteArtworkFile(persona.iconImageUri)
        deleteArtworkFile(persona.backgroundImageUri)
    }

    // Backup export: read the file and base64 its bytes. Null (rather than throwing)
    // when the file is missing — a persona can reference artwork whose file is
    // already gone, and a backup must still succeed.
    //
    // `Base64.NO_WRAP` upstream, which is the standard alphabet with no line breaks —
    // java.util.Base64's basic encoder emits exactly that, so a backup this port writes is
    // byte-for-byte what the phone would have written for the same artwork.
    private fun readArtworkAsBase64(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return try {
            val file = Path.of(path)
            if (!Files.exists(file)) null
            else Base64.getEncoder().encodeToString(Files.readAllBytes(file))
        } catch (e: Exception) {
            logger.e(TAG, "Failed to read persona image for backup", e)
            null
        }
    }

    // Backup restore: write the base64 payload back out as a fresh app-owned file and
    // return its internal path. The path is always regenerated here — the exported
    // paths point into the exporting device's filesDir and mean nothing elsewhere.
    //
    // Decoded with the MIME decoder rather than the basic one: Android's `Base64.DEFAULT`
    // tolerates and emits line breaks, so a backup exported by the phone may carry them, and the
    // MIME decoder ignores whitespace where the basic one would reject it.
    private fun writeArtworkFromBase64(data: String, slot: String): String? {
        return try {
            val bytes = Base64.getMimeDecoder().decode(data)
            val file = newArtworkFile(slot)
            Files.write(file, bytes)
            file.toAbsolutePath().toString()
        } catch (e: Exception) {
            logger.e(TAG, "Failed to restore persona image", e)
            null
        }
    }

    // Removal support: a genuine persona deletion must also drop its name-keyed
    // history entry — otherwise it stays on disk forever, orphaned (nothing can ever
    // display it again, and it leaks forever). Rename is the only other history-entry
    // lifecycle event and it deliberately MIGRATES (migratePersonaHistory above)
    // rather than deletes, so this is never reached on a rename. Before removing the
    // entry, delete the audio/image cache files its messages reference — same as eviction
    // in savePersonaMessages — otherwise those files would be orphaned in the entry's
    // place. A corrupted (unparseable) entry is still removed; its files are
    // unrecoverable anyway.
    //
    // F1: this is the SHARED persona-deletion cleanup surface, so the persona's
    // spontaneous delivery state (spontaneous_state_v1, keyed by the same name) dies
    // here too — the two are the same kind of residue and only ever die together. The
    // production deletion path (AssistantService.removePersona's history-IO lane) and
    // importBackup's orphan sweep both route through here, so the state entry cannot
    // outlive its persona on either path. An orphaned entry would not just leak: the
    // worker computes the O10 global daily cap over EVERY stored entry
    // (SpontaneousDeliveryWorker: allStates.values...), so a deleted persona's stale
    // counter would silently suppress the SURVIVING personas' messages for the rest of
    // the day. If a future caller genuinely wants history-without-state, extract the
    // two concerns rather than weakening this one.
    fun deletePersonaHistory(personaName: String) {
        getPersonaMessages(personaName)?.forEach { msg ->
            msg.audioFilePath?.let { deleteQuietly(it) }
            msg.imagePath?.let { deleteQuietly(it) }
            msg.generatedImagePath?.let { deleteQuietly(it) }
        }
        store.edit().remove("persona_messages_$personaName").commit()
        deleteSpontaneousState(personaName)
        // The sink follows the same rule as the delivery state: it is name-keyed state about a
        // persona, so it dies with the persona rather than orphaning under a name nothing can
        // reach again.
        store.edit().remove(PERSONA_OUTPUT_DEVICE_PREFIX + personaName).commit()
        removePersonaFs(personaName)
        removePersonaMcp(personaName)
        removePersonaMemory(personaName)
    }

    /** The one place a referenced cache/artwork file is removed. Missing is not an error. */
    private fun deleteQuietly(path: String) {
        try {
            Files.deleteIfExists(Path.of(path))
        } catch (_: Exception) {
            // Already gone, or a path we cannot touch. Neither is worth failing a caller for.
        }
    }

    fun exportBackup(): String {
        val personas = getPersonas() ?: emptyList()
        val personaMessages = personas.associate { it.name to (getPersonaMessages(it.name) ?: emptyList()) }
        val exportedRooms = getRooms() ?: emptyList()

        // Persona artwork travels as base64 BYTES, not as the paths stored on the
        // Persona: those paths point into this device's filesDir and are meaningless
        // after a restore on another device (or after a reinstall). Keyed by persona
        // name, matching the app's name-based persona identity everywhere else.
        // Personas with no artwork are omitted from the map entirely, so a backup of an
        // un-customised install carries an empty (and tiny) personaImages object. Two
        // Json settings matter here: encodeDefaults=true always emits the key, and
        // ignoreUnknownKeys=true lets an older build import a backup carrying it — just
        // without the artwork.
        val personaImages = personas.mapNotNull { persona ->
            val icon = readArtworkAsBase64(persona.iconImageUri)
            val background = readArtworkAsBase64(persona.backgroundImageUri)
            if (icon == null && background == null) null
            else persona.name to PersonaArtwork(icon = icon, background = background)
        }.toMap()

        val backup = BackupData(
            serverBases = getServerBases() ?: emptyList(),
            ollamaBases = getOllamaBases() ?: emptyList(),
            imageGenBases = getImageGenBases() ?: emptyList(),
            cloudApis = getCloudApis() ?: emptyList(),
            customCloudApis = getCustomCloudApis() ?: emptyList(),
            personas = personas,
            personaImages = personaImages,
            messages = getMessages() ?: emptyList(),
            personaMessages = personaMessages,
            totalCost = getTotalCost(),
            favoriteModels = getFavoriteModels() ?: emptyList(),
            trustedCerts = getTrustedCertificates(),
            searxngUrl = getSearxngUrl(),
            userLocation = getUserLocation(),
            ragServerUrl = getRagServerUrl(),
            ragUsername = getRagUsername(),
            ragPassword = getRagPassword(),
            // Rooms + their transcripts, keyed by room UUID. Resolved once: the
            // transcript map must be built from the SAME room list the backup carries.
            rooms = exportedRooms,
            roomMessages = exportedRooms.associate { it.id to (getRoomMessages(it.id) ?: emptyList()) },
            // Skills travel whole and unpruned: the personas below carry the ids that point
            // into this list, and the import prunes any that don't resolve.
            skills = getSkills() ?: emptyList(),
            // Same contract for Image Characters: the personas below carry imageWorkflowId
            // references into this list, and the import prunes any that don't resolve.
            imageWorkflows = getImageWorkflows() ?: emptyList(),
            // The chosen generator travels too: a restored install should keep generating with the
            // backend it was backed up with, not silently revert to A1111.
            activeImageGenBackend = getActiveImageGenBackend(),
            // Definitions only — the grant is stripped below. Per-persona enablement is name-keyed
            // state like the filesystem pair and is deliberately not on the allowlist.
            mcpServers = getMcpServers().map { it.copy(trust = McpTrust.ASK) },
            personaMemories = personas
                .associate { it.name to getPersonaMemory(it.name) }
                .filterValues { it.isNotEmpty() },
            personaMemoryEnabled = personas.filter { isPersonaMemoryEnabled(it.name) }.map { it.name },
        )
        return json.encodeToString(backup)
    }

    fun importBackup(jsonString: String): Boolean {
        return try {
            val backup = json.decodeFromString<BackupData>(jsonString)
            // Snapshot the pre-restore personas BEFORE the swap below: their artwork
            // files are what the post-restore sweep has to reconcile against, and once
            // savePersonas replaces the list those objects are unreachable.
            val previousPersonas = getPersonas() ?: emptyList()
            // Artwork restore: decode each base64 payload back into dataDir and rewrite
            // the persona's stored path to the freshly written file. A persona absent
            // from the map is left exactly as the backup serialized it — that covers
            // legacy backups (no artwork field at all), whose paths are device-local
            // but still valid for a same-device restore, and it degrades gracefully
            // elsewhere because a missing file just falls back to the letter logo.
            val restoredPersonas = backup.personas.map { persona ->
                val artwork = backup.personaImages[persona.name] ?: return@map persona
                persona.copy(
                    iconImageUri = artwork.icon?.let { writeArtworkFromBase64(it, "icon") },
                    backgroundImageUri = artwork.background?.let { writeArtworkFromBase64(it, "background") }
                )
            }
            saveServerBases(backup.serverBases)
            saveOllamaBases(backup.ollamaBases)
            saveImageGenBases(backup.imageGenBases)
            saveCloudApis(backup.cloudApis)
            saveCustomCloudApis(backup.customCloudApis)
            // MCP definitions restore; the grant does not. The export already stripped trust, and
            // this second copy of the rule is what protects against a hand-edited backup that says
            // TRUSTED — an imported file must never be a way to run tools without the confirmation.
            saveMcpServers(backup.mcpServers.map { it.copy(trust = McpTrust.ASK) })
            // Skills land BEFORE the personas, because each persona's enabledSkillIds are
            // pruned against this exact list below: a backup that carried skills but a
            // persona referencing a skill this backup doesn't contain must not restore an
            // enabled-but-missing skill.
            saveSkills(backup.skills)
            // Characters land alongside skills and BEFORE the personas, for the same reason:
            // each persona's imageWorkflowId is pruned against this exact list below, so a backup
            // that carried a character but a persona referencing one this backup doesn't contain
            // must not restore a dangling reference.
            saveImageWorkflows(backup.imageWorkflows)
            // Absent in a backup taken before the switch existed, and defaulted to A1111 there, so
            // this restores the behaviour that backup was taken with.
            saveActiveImageGenBackend(backup.activeImageGenBackend)
            val restoredSkillIds = backup.skills.map { it.id }.toSet()
            val restoredWorkflowIds = backup.imageWorkflows.map { it.id }.toSet()
            // Memory restores for the personas this backup carries. A backup from before the
            // feature has neither field, and this deliberately leaves whatever memory this install
            // already has for other names alone rather than wiping it on an old restore.
            val restoredNamesForMemory = restoredPersonas.map { it.name }.toSet()
            backup.personaMemories
                .filterKeys { it in restoredNamesForMemory }
                .forEach { (name, notes) -> savePersonaMemory(name, notes) }
            backup.personaMemoryEnabled
                .filter { it in restoredNamesForMemory }
                .forEach { setPersonaMemoryEnabled(it, true) }
            savePersonas(
                restoredPersonas.map { persona ->
                    val keptSkills = persona.enabledSkillIds.filter { it in restoredSkillIds }
                    val keptWorkflow = persona.imageWorkflowId?.takeIf { it in restoredWorkflowIds }
                    if (keptSkills.size == persona.enabledSkillIds.size &&
                        keptWorkflow == persona.imageWorkflowId
                    ) {
                        persona
                    } else {
                        persona.copy(enabledSkillIds = keptSkills, imageWorkflowId = keptWorkflow)
                    }
                }
            )
            saveMessages(backup.messages)
            backup.personaMessages.forEach { (name, msgs) ->
                savePersonaMessages(name, msgs)
            }
            saveTotalCost(backup.totalCost)
            saveFavoriteModels(backup.favoriteModels)
            saveTrustedCertificates(backup.trustedCerts)
            backup.searxngUrl?.let { saveSearxngUrl(it) }
            backup.userLocation?.let { saveUserLocation(it) }
            backup.ragServerUrl?.let { saveRagServerUrl(it) }
            backup.ragUsername?.let { saveRagUsername(it) }
            backup.ragPassword?.let { saveRagPassword(it) }
            // M2: the restore overwrites the persona LIST, but persona_messages_<name>
            // entries belonging to personas that existed before the restore and aren't
            // in this backup would survive as orphans — invisible forever and
            // accumulating across restores (the import-path sibling of the deletion-path
            // orphan deletePersonaHistory removes). Enumerate every stored history entry
            // via the store's key set (a snapshot; the filter materializes it before any
            // mutation, so removal during iteration is safe) and drop the ones whose
            // persona isn't in the restored list — reusing deletePersonaHistory so any
            // referenced audio cache files are cleaned up too.
            val restoredNames = restoredPersonas.map { it.name }.toSet()
            // Rooms: definitions first (pruned of members the restore dropped — the
            // transcript survives, the vanished members don't), then their transcripts.
            val restoredRooms = backup.rooms.map { room ->
                room.copy(memberNames = room.memberNames.filter { it in restoredNames })
            }
            saveRooms(restoredRooms)
            backup.roomMessages.forEach { (roomId, msgs) ->
                saveRoomMessages(roomId, msgs)
            }
            // Orphaned artwork sweep — the image-file sibling of the history cleanup
            // below. Any file a PRE-restore persona pointed at that no restored persona
            // points at is now unreachable forever: either the restore dropped that
            // persona, or it replaced it with a freshly written file above. Computed
            // from the live set (rather than deleted eagerly during the swap) so
            // artwork survives whenever the same path is legitimately still in use.
            val liveArtwork = restoredPersonas
                .flatMap { listOfNotNull(it.iconImageUri, it.backgroundImageUri) }
                .toSet()
            previousPersonas.forEach { persona ->
                listOfNotNull(persona.iconImageUri, persona.backgroundImageUri)
                    .filter { it !in liveArtwork }
                    .forEach { path -> deleteQuietly(path) }
            }
            store.allKeys()
                .filter { it.startsWith("persona_messages_") }
                .map { it.removePrefix("persona_messages_") }
                .filter { it !in restoredNames }
                .forEach { deletePersonaHistory(it) }
            // The output-device entries are name-keyed for the same reason the histories are, and
            // a restore can leave one behind for a persona it did not bring back. `deletePersonaHistory`
            // above drops the entry of every orphan that HAD a history; this catches the rest — the
            // same second pass, and the same reason, as the spontaneous-state sweep below.
            store.allKeys()
                .filter { it.startsWith(PERSONA_OUTPUT_DEVICE_PREFIX) }
                .map { it.removePrefix(PERSONA_OUTPUT_DEVICE_PREFIX) }
                .filter { it !in restoredNames }
                .forEach { store.edit().remove(PERSONA_OUTPUT_DEVICE_PREFIX + it).commit() }
            // The filesystem family has two prefixes and needs the same pass for both, for the same
            // reason: a restore can leave either behind for a persona it did not bring back, and a
            // mode left behind is a *grant* left behind. The MCP enablement is name-keyed the same
            // way and would otherwise be inherited by a future persona of that name.
            listOf(
                PERSONA_FS_MODE_PREFIX,
                PERSONA_FS_WORKSPACE_PREFIX,
                MCP_PERSONA_PREFIX,
            ).forEach { prefix ->
                store.allKeys()
                    .filter { it.startsWith(prefix) }
                    .map { it.removePrefix(prefix) }
                    .filter { it !in restoredNames }
                    .forEach { store.edit().remove(prefix + it).commit() }
            }
            // **The memory family is swept apart from the list above, and the reason is a real
            // trap:** `persona_memory_` is a prefix of `persona_memory_enabled_`, so one generic
            // pass over the notes prefix also matches every switch key, reads `enabled_A` as a
            // persona name, finds it in no restored persona and deletes the switch. The notes pass
            // therefore excludes the switch's prefix explicitly. A test caught this on the first
            // run: the notes restored and the switch did not.
            store.allKeys()
                .filter { it.startsWith(PERSONA_MEMORY_ENABLED_PREFIX) }
                .map { it.removePrefix(PERSONA_MEMORY_ENABLED_PREFIX) }
                .filter { it !in restoredNames }
                .forEach { store.edit().remove(PERSONA_MEMORY_ENABLED_PREFIX + it).commit() }
            store.allKeys()
                .filter { it.startsWith(PERSONA_MEMORY_PREFIX) && !it.startsWith(PERSONA_MEMORY_ENABLED_PREFIX) }
                .map { it.removePrefix(PERSONA_MEMORY_PREFIX) }
                .filter { it !in restoredNames }
                .forEach { store.edit().remove(PERSONA_MEMORY_PREFIX + it).commit() }
            // Same orphan sweep for room transcripts: the restore overwrites the ROOM
            // list, so a room_messages_<id> entry for a room that existed before the
            // restore but isn't in this backup would otherwise survive forever,
            // unreachable. Keyed by room UUID (rooms are id-based, not name-based).
            val restoredRoomIds = restoredRooms.map { it.id }.toSet()
            store.allKeys()
                .filter { it.startsWith("room_messages_") }
                .map { it.removePrefix("room_messages_") }
                .filter { it !in restoredRoomIds }
                .forEach { deleteRoomHistory(it) }
            // F1: spontaneous delivery state is keyed by persona name too. The history
            // sweep above already drops the state of every orphan that HAD a history
            // entry (deletePersonaHistory carries it); this second pass catches the
            // entries with no history entry — the two entry sets are independent, and
            // either can exist without the other. Delivery state is device-local
            // bookkeeping that never travels in the backup (see exportBackup), so every
            // entry here belongs to a PRE-restore persona; one whose persona the restore
            // did not bring back is unreachable forever — and worse than a leak, the
            // worker computes the global daily cap over every stored entry, so a stale
            // orphan would keep suppressing the restored personas' messages for the rest
            // of the day.
            getSpontaneousStates().keys
                .filter { it !in restoredNames }
                .forEach { deleteSpontaneousState(it) }
            true
        } catch (e: Exception) {
            // Try legacy import if new format fails. Logged because a silent fallback turns a
            // decoder mismatch into "the import did nothing", with no way to tell why.
            logger.w(TAG, "backup import failed — trying the legacy format", e)
            importLegacyBackup(jsonString)
        }
    }

    private fun importLegacyBackup(jsonString: String): Boolean {
        // Fix #14: report whether anything was ACTUALLY imported. This used to return
        // true for any valid JSON object (and even for real legacy backups it only
        // restores server_bases), so the UI showed "Import successful" for no-op and
        // partial imports alike. Legacy keys beyond server_bases have no known schema —
        // they are skipped rather than guessed at.
        return try {
            val backup = JSONObject(jsonString)
            var imported = false
            if (backup.has("server_bases")) {
                val array = backup.getJSONArray("server_bases")
                val bases = mutableListOf<ServerConfig>()
                for (i in 0 until array.length()) {
                    val item = array.get(i)
                    if (item is String) bases.add(ServerConfig("Server ${i + 1}", item))
                    else if (item is JSONObject) bases.add(ServerConfig(item.optString("name", "Server $i"), item.getString("url")))
                }
                // An explicitly empty list restores nothing — don't clobber the current
                // servers with it and don't claim success either.
                if (bases.isNotEmpty()) {
                    saveServerBases(bases)
                    imported = true
                }
            }
            imported
        } catch (e: Exception) { false }
    }

    fun saveTotalCost(cost: Double) {
        store.edit().putLong("total_cost_bits", java.lang.Double.doubleToRawLongBits(cost)).commit()
    }

    fun getTotalCost(): Double {
        val bits = store.getLong("total_cost_bits", 0L)
        return java.lang.Double.longBitsToDouble(bits)
    }

    fun saveFavoriteModels(models: List<String>) {
        store.edit().putString("favorite_models_v2", json.encodeToString(models)).commit()
    }

    fun getFavoriteModels(): List<String>? {
        val data = store.getString("favorite_models_v2")
        if (data != null) {
            return runCatching { json.decodeFromString<List<String>>(data) }
                .onFailure { e ->
                    logger.w(
                        TAG,
                        "Corrupted/unreadable JSON in favorite_models_v2 — ignoring entry, returning null",
                        e
                    )
                }
                .getOrNull()
        }

        val oldJson = store.getString("favorite_models") ?: return null
        return try {
            val array = JSONArray(oldJson)
            List(array.length()) { array.getString(it) }
        } catch (e: Exception) { null }
    }

    fun saveTrustedCertificates(certs: Map<String, String>) {
        store.edit().putString("trusted_certs_v2", json.encodeToString(certs)).commit()
    }

    fun getTrustedCertificates(): Map<String, String> {
        val data = store.getString("trusted_certs_v2")
        if (data != null) {
            return runCatching { json.decodeFromString<Map<String, String>>(data) }
                .onFailure { e ->
                    logger.w(
                        TAG,
                        "Corrupted/unreadable JSON in trusted_certs_v2 — ignoring entry, returning empty map",
                        e
                    )
                }
                .getOrElse { emptyMap() }
        }

        val oldJson = store.getString("trusted_certs") ?: return emptyMap()
        return try {
            val obj = JSONObject(oldJson)
            val map = mutableMapOf<String, String>()
            obj.keys().forEach { host -> map[host] = obj.getString(host) }
            map
        } catch (e: Exception) { emptyMap() }
    }

    fun isFirstRun(): Boolean {
        return store.getBoolean("is_first_run", true)
    }

    fun setFirstRunComplete() {
        store.edit().putBoolean("is_first_run", false).commit()
    }

    // Celestial UI (the HUD voice screen): persisted like is_first_run — a plain
    // boolean in the same store. Default ON, because the HUD is now the layout a
    // fresh install should open in. getBoolean only falls back to this default when the
    // key is absent, so an explicitly saved value always wins: a device whose user
    // turned the HUD off (or on) keeps the classic layout (or the HUD) exactly as saved.
    fun getCelestialUi(): Boolean {
        return store.getBoolean("celestial_ui", true)
    }

    fun saveCelestialUi(enabled: Boolean) {
        store.edit().putBoolean("celestial_ui", enabled).commit()
    }

    // Adaptive Theme (Material You dynamic color): same precedent as celestial_ui —
    // a plain boolean in the same store, default OFF so the existing static
    // green/amber theme is byte-identical until the user opts in. The wallpaper-
    // derived scheme itself is resolved in VoiceAssistantTheme.baseColorScheme(),
    // which also handles the API < 31 fallback (see isDynamicColorSupported).
    //
    // Not yet ported: the wallpaper-derived scheme has no Linux counterpart. The flag is
    // carried so a settings blob round-trips through a backup unchanged, and the desktop theme
    // decides what to do with it in M1's UI work.
    fun getAdaptiveTheme(): Boolean {
        return store.getBoolean("adaptive_theme", false)
    }

    fun saveAdaptiveTheme(enabled: Boolean) {
        store.edit().putBoolean("adaptive_theme", enabled).commit()
    }

    // The application's own light/dark choice, default ON (2026-10-17). A fresh install opens
    // dark because Compose's `isSystemInDarkTheme()` reports light on Linux — Skiko's system-theme
    // probe returns UNKNOWN for an Xfce session, which the theme maps to light — so "follow the
    // system" meant "always white" on a dark desktop. This is a device preference, like the window
    // size: deliberately not in the backup allowlist.
    fun getDarkTheme(): Boolean = store.getBoolean("dark_theme", true)

    fun saveDarkTheme(enabled: Boolean) {
        store.edit().putBoolean("dark_theme", enabled).commit()
    }

    fun saveSearxngUrl(url: String) {
        store.edit().putString("searxng_url", url).commit()
    }

    fun getSearxngUrl(): String? {
        return store.getString("searxng_url")
    }

    /**
     * The microphone the assistant listens to — an [AudioDevice.id], or "" / absent for the
     * sound server's default. A NEW key, not an upstream one: M2's source picker has no phone
     * counterpart to migrate from, and an absent key already means exactly what the default
     * value would.
     *
     * Global rather than per persona, unlike the output side: a persona's headphones say
     * something about that conversation, but there is one room being listened to and one
     * microphone in it. The input is also the one place where falling back is nearly always
     * right — see `inputDeviceForCapture`.
     */
    fun saveAudioInputDevice(deviceId: String?) {
        store.edit().putString("audio_input_device", deviceId.orEmpty()).commit()
    }

    fun getAudioInputDevice(): String? = store.getString("audio_input_device")?.takeIf { it.isNotBlank() }

    /**
     * The output a persona's replies play to, keyed by persona NAME — an [AudioDevice.id], or
     * absent for the system default.
     *
     * Keyed by name in the settings blob rather than carried as a `Persona` field, and the reason
     * is a hard constraint rather than taste: `Models.kt` is a **COPY** file (PLAN.md §7) and must
     * stay byte-identical to upstream forever, so a new field on `Persona` is not available without
     * demoting it. This blob already holds per-persona state that upstream keeps outside the model
     * — `persona_messages_<name>` and the spontaneous delivery state — and this is the same kind of
     * thing: device-local bookkeeping, meaningless on another machine.
     *
     * **It therefore does not travel in a backup.** A Linux sink name is not a phone routing
     * choice, and a restored backup's ids may not exist on the machine being restored to; the
     * persona editor shows the default in that case, and `sinkForPersona` logs the disappearance
     * rather than silently speaking somewhere else.
     *
     * Same lifecycle as the two neighbours: [migratePersonaHistory] carries it across a rename and
     * [deletePersonaHistory] drops it on deletion, and the import's orphan sweep drops it for a
     * persona the restore did not bring back.
     */
    fun savePersonaOutputDevice(personaName: String, deviceId: String?) {
        if (personaName.isBlank()) return
        val edit = store.edit()
        if (deviceId.isNullOrBlank()) edit.remove(PERSONA_OUTPUT_DEVICE_PREFIX + personaName)
        else edit.putString(PERSONA_OUTPUT_DEVICE_PREFIX + personaName, deviceId)
        edit.commit()
    }

    fun getPersonaOutputDevice(personaName: String): String? =
        store.getString(PERSONA_OUTPUT_DEVICE_PREFIX + personaName)?.takeIf { it.isNotBlank() }

    /** The rename half: move the entry, like the history and delivery-state twins above. */
    fun migratePersonaOutputDevice(oldName: String, newName: String) {
        if (oldName == newName || oldName.isBlank()) return
        val existing = getPersonaOutputDevice(oldName) ?: return
        store.edit()
            .putString(PERSONA_OUTPUT_DEVICE_PREFIX + newName, existing)
            .remove(PERSONA_OUTPUT_DEVICE_PREFIX + oldName)
            .commit()
    }

    /**
     * Filesystem tools (PLAN.md §10/M8): whether a persona may touch files at all, and in which
     * folder.
     *
     * Two levels, the shape the app already uses for image-generation entries: a global default and a
     * per-persona override that wins, with **absent meaning "follow the global"** — which is why the
     * per-persona getters answer `null` rather than a mode with a default. Shipped state is global
     * `OFF`, and the plan calls that what it is: off is a real answer.
     *
     * The path is a string because that is what the store holds and because a value the user typed
     * may be nonsense; [resolveFsWorkspace] is where a string becomes a usable workspace or nothing,
     * so nothing here can throw on a bad value.
     *
     * **Neither value travels in a backup** — the sink's rule, and one more reason on top: a restored
     * backup must not silently re-grant write access to a folder. Trust is the one setting that
     * should not survive a file transfer.
     */
    internal fun saveFsMode(mode: FsToolMode) {
        store.edit().putString("files_mode", mode.name).commit()
    }

    internal fun getFsMode(): FsToolMode = FsToolMode.fromName(store.getString("files_mode"))

    fun saveFsWorkspace(path: String?) {
        store.edit().putString("files_workspace", path.orEmpty()).commit()
    }

    fun getFsWorkspace(): String? = store.getString("files_workspace")?.takeIf { it.isNotBlank() }

    /**
     * Null means "follow the global", and **removes the folder with the mode** — the pair is the pin,
     * exactly as it is for [migratePersonaFs] and [removePersonaFs].
     *
     * Leaving the folder behind was a widening (M8 review): the resolution takes the mode from one
     * level and the path from the other, so a persona un-pinned from read-only would have kept its
     * folder at the *global* trust level — write access to a directory the user chose for something
     * narrower. And the editor hides the folder controls once the mode is `Global`, so it was a
     * folder they could no longer see, let alone clear. A user who wants a folder at the global trust
     * level chooses a folder and leaves the mode alone, which is a different action and still writes
     * only [savePersonaFsWorkspace].
     *
     * A value this build cannot read resolves to `OFF` rather than to "follow the global", and that
     * asymmetry is deliberate: `fromName`'s fallback is the deny-everything one, so a corrupted
     * persona value can only ever narrow access, never widen it to whatever the global happens to be.
     */
    internal fun savePersonaFsMode(personaName: String, mode: FsToolMode?) {
        if (personaName.isBlank()) return
        val key = PERSONA_FS_MODE_PREFIX + personaName
        val edit = store.edit()
        if (mode == null) {
            edit.remove(key)
            edit.remove(PERSONA_FS_WORKSPACE_PREFIX + personaName)
        } else {
            edit.putString(key, mode.name)
        }
        edit.commit()
    }

    internal fun getPersonaFsMode(personaName: String): FsToolMode? =
        store.getString(PERSONA_FS_MODE_PREFIX + personaName)?.takeIf { it.isNotBlank() }?.let(FsToolMode::fromName)

    fun savePersonaFsWorkspace(personaName: String, path: String?) {
        if (personaName.isBlank()) return
        val key = PERSONA_FS_WORKSPACE_PREFIX + personaName
        val edit = store.edit()
        if (path.isNullOrBlank()) edit.remove(key) else edit.putString(key, path)
        edit.commit()
    }

    fun getPersonaFsWorkspace(personaName: String): String? =
        store.getString(PERSONA_FS_WORKSPACE_PREFIX + personaName)?.takeIf { it.isNotBlank() }

    /** The roots the write gate does not ask about, as absolute paths. Unreadable JSON reads as none. */
    fun getTrustedFsRoots(): List<String> {
        val data = store.getString(TRUSTED_FS_ROOTS) ?: return emptyList()
        return runCatching { json.decodeFromString<List<String>>(data) }
            .onFailure { logger.w(TAG, "Corrupted/unreadable JSON in $TRUSTED_FS_ROOTS — asking again", it) }
            .getOrDefault(emptyList())
    }

    /** Replace the whole set. Empty means "ask again", which is what every fs setting change writes. */
    fun saveTrustedFsRoots(roots: Collection<String>) {
        store.edit().putString(TRUSTED_FS_ROOTS, json.encodeToString(roots.toList())).commit()
    }

    /** The rename half, both keys, like the history and output-device twins above. */
    fun migratePersonaFs(oldName: String, newName: String) {
        if (oldName == newName || oldName.isBlank()) return
        val mode = getPersonaFsMode(oldName)
        val workspace = getPersonaFsWorkspace(oldName)
        if (mode == null && workspace == null) return
        val edit = store.edit()
        if (mode != null) edit.putString(PERSONA_FS_MODE_PREFIX + newName, mode.name)
        if (workspace != null) edit.putString(PERSONA_FS_WORKSPACE_PREFIX + newName, workspace)
        edit.remove(PERSONA_FS_MODE_PREFIX + oldName)
        edit.remove(PERSONA_FS_WORKSPACE_PREFIX + oldName)
        edit.commit()
    }

    /**
     * The delete half: both keys. A workspace left behind for a name nothing can reach again is the
     * same orphan the sink's removal exists to prevent — and for this family it is worse than dead
     * state, since a persona later *created* with that name would inherit a folder the user never
     * chose for it.
     */
    fun removePersonaFs(personaName: String) {
        if (personaName.isBlank()) return
        store.edit()
            .remove(PERSONA_FS_MODE_PREFIX + personaName)
            .remove(PERSONA_FS_WORKSPACE_PREFIX + personaName)
            .commit()
    }

    // ------------------------------------------------------------------ MCP servers (2026-10-17, M9)

    /**
     * The configured MCP servers. Unreadable JSON reads as none — a corrupted entry costs the
     * feature, never a failed start, which is this file's rule for every stored collection.
     */
    fun getMcpServers(): List<McpServerConfig> {
        val data = store.getString(MCP_SERVERS) ?: return emptyList()
        return runCatching { json.decodeFromString<List<McpServerConfig>>(data) }
            .onFailure { logger.w(TAG, "Corrupted/unreadable JSON in $MCP_SERVERS — no MCP servers", it) }
            .getOrDefault(emptyList())
    }

    /**
     * Replace the server list.
     *
     * **Trust is only ever lowered here, and the rule is deliberate:** a grant was given for a
     * command, its arguments and its URL, so editing any of those makes it a different server — the
     * grant is revoked in the same write. Everything else about an entry (its name, whether it is
     * enabled) can change without touching the grant, or renaming a server would silently turn the
     * confirmation back on.
     */
    fun saveMcpServers(servers: List<McpServerConfig>) {
        val previous = getMcpServers().associateBy { it.id }
        val sanitized = servers.map { server ->
            val old = previous[server.id]
            val connectionChanged = old != null && (
                old.transport != server.transport ||
                    old.command != server.command ||
                    old.args != server.args ||
                    old.env != server.env ||
                    old.workingDir != server.workingDir ||
                    old.url != server.url ||
                    old.headers != server.headers
                )
            if (connectionChanged && server.trust == McpTrust.TRUSTED) server.copy(trust = McpTrust.ASK) else server
        }
        store.edit().putString(MCP_SERVERS, json.encodeToString(sanitized)).commit()
    }

    /** Grant or revoke one server's trust, leaving every other entry exactly as it was. */
    fun saveMcpTrust(serverId: String, trust: McpTrust) {
        val servers = getMcpServers()
        if (servers.none { it.id == serverId }) return
        saveMcpServers(servers.map { if (it.id == serverId) it.copy(trust = trust) else it })
    }

    /** The server ids a persona offers; empty means "none", which is every persona until chosen. */
    fun getMcpServersForPersona(personaName: String): List<String> {
        if (personaName.isBlank()) return emptyList()
        val data = store.getString(MCP_PERSONA_PREFIX + personaName) ?: return emptyList()
        return runCatching { json.decodeFromString<List<String>>(data) }
            .onFailure {
                logger.w(TAG, "Corrupted/unreadable JSON in $MCP_PERSONA_PREFIX$personaName — none", it)
            }
            .getOrDefault(emptyList())
    }

    fun saveMcpServersForPersona(personaName: String, serverIds: List<String>) {
        if (personaName.isBlank()) return
        val key = MCP_PERSONA_PREFIX + personaName
        val ids = serverIds.distinct()
        if (ids.isEmpty()) store.edit().remove(key).commit()
        else store.edit().putString(key, json.encodeToString(ids)).commit()
    }

    /** The rename half, like every other name-keyed persona setting. */
    fun migratePersonaMcp(oldName: String, newName: String) {
        if (oldName == newName || oldName.isBlank()) return
        val data = store.getString(MCP_PERSONA_PREFIX + oldName) ?: return
        store.edit()
            .putString(MCP_PERSONA_PREFIX + newName, data)
            .remove(MCP_PERSONA_PREFIX + oldName)
            .commit()
    }

    /** The delete half: an orphaned enablement would be inherited by a future persona of that name. */
    fun removePersonaMcp(personaName: String) {
        if (personaName.isBlank()) return
        store.edit().remove(MCP_PERSONA_PREFIX + personaName).commit()
    }

    // ------------------------------------------------------------------ long-term memory (2026-10-17, M10)

    /**
     * The persona's notes. Unreadable JSON reads as none — the same rule as every stored
     * collection in this file — and blank entries are dropped so a hand-edited file cannot render
     * an empty bullet in the prompt.
     */
    fun getPersonaMemory(personaName: String): List<String> {
        if (personaName.isBlank()) return emptyList()
        val data = store.getString(PERSONA_MEMORY_PREFIX + personaName) ?: return emptyList()
        return runCatching { json.decodeFromString<List<String>>(data) }
            .onFailure { logger.w(TAG, "Corrupted/unreadable JSON in ${PERSONA_MEMORY_PREFIX}$personaName — none", it) }
            .getOrDefault(emptyList())
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * Replace the notes. The count is capped defensively at [MEMORY_MAX_ENTRIES]: the tool refuses
     * over the cap in words, and the editor never adds beyond it, but a hand-edited file must not
     * be able to turn the prompt into a memoir either.
     */
    fun savePersonaMemory(personaName: String, entries: List<String>) {
        if (personaName.isBlank()) return
        val key = PERSONA_MEMORY_PREFIX + personaName
        val kept = entries.map { it.trim() }.filter { it.isNotEmpty() }.take(MEMORY_MAX_ENTRIES)
        if (kept.isEmpty()) store.edit().remove(key).commit()
        else store.edit().putString(key, json.encodeToString(kept)).commit()
    }

    fun isPersonaMemoryEnabled(personaName: String): Boolean =
        store.getBoolean(PERSONA_MEMORY_ENABLED_PREFIX + personaName, false)

    fun setPersonaMemoryEnabled(personaName: String, enabled: Boolean) {
        if (personaName.isBlank()) return
        val key = PERSONA_MEMORY_ENABLED_PREFIX + personaName
        if (enabled) store.edit().putBoolean(key, true).commit() else store.edit().remove(key).commit()
    }

    /** The rename half, both keys, like every other name-keyed persona setting. */
    fun migratePersonaMemory(oldName: String, newName: String) {
        if (oldName == newName || oldName.isBlank()) return
        val edit = store.edit()
        var moved = false
        store.getString(PERSONA_MEMORY_PREFIX + oldName)?.let {
            edit.putString(PERSONA_MEMORY_PREFIX + newName, it)
            edit.remove(PERSONA_MEMORY_PREFIX + oldName)
            moved = true
        }
        if (isPersonaMemoryEnabled(oldName)) {
            edit.putBoolean(PERSONA_MEMORY_ENABLED_PREFIX + newName, true)
            edit.remove(PERSONA_MEMORY_ENABLED_PREFIX + oldName)
            moved = true
        }
        if (moved) edit.commit()
    }

    /** The delete half: notes and switch die with the persona, like history and the filesystem pair. */
    fun removePersonaMemory(personaName: String) {
        if (personaName.isBlank()) return
        store.edit()
            .remove(PERSONA_MEMORY_PREFIX + personaName)
            .remove(PERSONA_MEMORY_ENABLED_PREFIX + personaName)
            .commit()
    }

    // ------------------------------------------------------------------ transcript search (2026-10-17, M13)

    /**
     * Search every saved transcript — the main chat, each persona's history, every room — for
     * [query]. Case-insensitive substring, one hit per matching message, newest first, capped at
     * [limit].
     *
     * Reads the same JSON entries the chat screens read and makes no network call. Legacy messages
     * with no timestamp sort last (timestamp 0); the sort is stable, so equal timestamps keep the
     * chat → personas → rooms order. Unreadable entries degrade to no hits, like every other getter
     * in this file.
     */
    internal fun searchTranscripts(query: String, limit: Int = TRANSCRIPT_SEARCH_LIMIT): List<TranscriptHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val hits = mutableListOf<TranscriptHit>()

        getMessages()?.forEach { message ->
            transcriptSnippet(message.text, q)?.let {
                hits += TranscriptHit(TranscriptKind.CHAT, "chat", "Chat", message.role, message.timestamp, it)
            }
        }
        (getPersonas() ?: emptyList()).forEach { persona ->
            getPersonaMessages(persona.name)?.forEach { message ->
                transcriptSnippet(message.text, q)?.let {
                    hits += TranscriptHit(
                        TranscriptKind.PERSONA,
                        persona.name,
                        persona.name,
                        message.role,
                        message.timestamp,
                        it,
                    )
                }
            }
        }
        (getRooms() ?: emptyList()).forEach { room ->
            getRoomMessages(room.id)?.forEach { message ->
                transcriptSnippet(message.text, q)?.let {
                    val speaker = if (message.isUser) "user" else message.speakerName
                    hits += TranscriptHit(TranscriptKind.ROOM, room.id, room.name, speaker, message.timestamp, it)
                }
            }
        }
        return hits.sortedByDescending { it.timestamp }.take(limit)
    }

    // ------------------------------------------------------------------ the window (2026-10-17)

    fun getWindowSize(): Pair<Int, Int>? {
        val raw = store.getString(WINDOW_SIZE)?.trim() ?: return null
        val (w, h) = raw.split('x').takeIf { it.size == 2 } ?: return null
        val width = w.trim().toIntOrNull() ?: return null
        val height = h.trim().toIntOrNull() ?: return null
        // A window smaller than this is unusable, and one larger than any real screen is a save from
        // a monitor this launch does not have; both fall back rather than open something broken.
        if (width < 320 || height < 320 || width > 10_000 || height > 10_000) return null
        return width to height
    }

    fun saveWindowSize(width: Int, height: Int) {
        store.edit().putString(WINDOW_SIZE, "${width}x${height}").commit()
    }

    fun getRememberWindowSize(): Boolean = store.getBoolean(REMEMBER_WINDOW_SIZE, true)

    fun saveRememberWindowSize(remember: Boolean) {
        store.edit().putBoolean(REMEMBER_WINDOW_SIZE, remember).commit()
    }

    fun saveUserLocation(location: String) {
        store.edit().putString("user_location", location).commit()
    }

    fun getUserLocation(): String? {
        return store.getString("user_location")
    }

    fun saveRagServerUrl(url: String) {
        store.edit().putString("rag_server_url", url).commit()
    }

    fun getRagServerUrl(): String? {
        return store.getString("rag_server_url")
    }

    fun saveRagUsername(username: String) {
        store.edit().putString("rag_username", username).commit()
    }

    fun getRagUsername(): String? {
        return store.getString("rag_username")
    }

    fun saveRagPassword(password: String) {
        store.edit().putString("rag_password", password).commit()
    }

    fun getRagPassword(): String? {
        return store.getString("rag_password")
    }

    fun saveModelPricing(pricing: Map<String, ModelPricing>) {
        store.edit().putString("model_pricing_v1", json.encodeToString(pricing)).commit()
    }

    fun getModelPricing(): Map<String, ModelPricing> {
        val data = store.getString("model_pricing_v1") ?: return emptyMap()
        return runCatching { json.decodeFromString<Map<String, ModelPricing>>(data) }
            .onFailure { e ->
                logger.w(
                    TAG,
                    "Corrupted/unreadable JSON in model_pricing_v1 — ignoring entry, returning empty map",
                    e
                )
            }
            .getOrElse { emptyMap() }
    }

    fun saveLastPriceSyncTimestamp(timestamp: Long) {
        store.edit().putLong("last_price_sync_ts", timestamp).commit()
    }

    fun getLastPriceSyncTimestamp(): Long {
        return store.getLong("last_price_sync_ts", 0L)
    }

    // ---- App-update check (Fix: update banner) — plain store keys, no JSON, no guards needed
    //
    // Kept although the update check itself is dropped in v1 (PLAN.md §8): these two keys are
    // part of the settings blob upstream, they cost nothing, and removing them is a decision
    // that belongs with the commit that removes their last caller, not with the port.

    fun saveLastUpdateCheckTimestamp(timestamp: Long) {
        store.edit().putLong("last_update_check_ts", timestamp).commit()
    }

    fun getLastUpdateCheckTimestamp(): Long {
        return store.getLong("last_update_check_ts", 0L)
    }

    fun getChatFontSize(): Float {
        return store.getFloat("chat_font_size", DEFAULT_CHAT_FONT_SIZE)
    }

    fun saveChatFontSize(size: Float) {
        store.edit().putFloat("chat_font_size", size).commit()
    }

    fun saveDismissedUpdateVersion(version: String) {
        store.edit().putString("dismissed_update_version", version).commit()
    }

    fun getDismissedUpdateVersion(): String? {
        return store.getString("dismissed_update_version")
    }
}

@kotlinx.serialization.Serializable
data class BackupData(
    val serverBases: List<ServerConfig>,
    val ollamaBases: List<ServerConfig>,
    val imageGenBases: List<ServerConfig> = emptyList(),
    val cloudApis: List<CloudApiSetting>,
    val customCloudApis: List<CloudApiSetting> = emptyList(),
    val personas: List<Persona>,
    val messages: List<ChatMessage>,
    val personaMessages: Map<String, List<ChatMessage>> = emptyMap(),
    val totalCost: Double,
    val favoriteModels: List<String>,
    val trustedCerts: Map<String, String>,
    val searxngUrl: String? = null,
    val userLocation: String? = null,
    val ragServerUrl: String? = null,
    val ragUsername: String? = null,
    val ragPassword: String? = null,
    // Persona artwork, keyed by persona name. Base64-encoded JPEG bytes rather than
    // the on-device file paths carried in `personas`, which are meaningless off-device.
    // Defaulted, so backups written before artwork existed still decode: the Json
    // instance sets ignoreUnknownKeys/coerceInputValues, so an absent key (or a null
    // slot inside an entry) falls back to empty rather than failing the import.
    val personaImages: Map<String, PersonaArtwork> = emptyMap(),
    // Rooms travel with the backup, like personas do. Both fields are defaulted, so a
    // backup written before Rooms existed still decodes (and, thanks to
    // ignoreUnknownKeys, a NEW backup still imports into an older build).
    val rooms: List<Room> = emptyList(),
    val roomMessages: Map<String, List<RoomMessage>> = emptyMap(),
    // The shared Skills library travels with the backup like personas and rooms do, so a
    // restored install keeps its skills AND the personas' enabledSkillIds that point at them.
    // Defaulted, so a backup written before Skills existed still decodes (and, thanks to
    // ignoreUnknownKeys, a NEW backup still imports into an older build — which simply
    // ignores the field and leaves every persona with no skills).
    val skills: List<Skill> = emptyList(),
    // The shared Image Characters library (ComfyUI workflows) travels with the backup like skills
    // do, so a restored install keeps its characters AND the personas' imageWorkflowId references
    // that point at them. Defaulted, so a backup written before Characters existed still decodes
    // (and, thanks to ignoreUnknownKeys, a NEW backup still imports into an older build).
    val imageWorkflows: List<ImageWorkflow> = emptyList(),
    // Which image backend Celeste generates with. Defaulted to A1111, so a backup written before
    // the switch existed still decodes — and restores to exactly the behaviour it was taken with.
    val activeImageGenBackend: ImageGenBackend = DEFAULT_IMAGE_GEN_BACKEND,
    // The MCP servers, definitions only: the export strips their trust and the import re-adds it as
    // ASK, because a grant to run tools on this machine is a permission and a restored backup must
    // not confer one. Defaulted, so a backup written before MCP existed still decodes.
    val mcpServers: List<McpServerConfig> = emptyList(),
    // Long-term memory travels with the personas it belongs to: entries keyed by persona name, and
    // the names of the personas whose memory is switched on. Unlike a filesystem workspace or a
    // tool trust, this is the conversation's own content, which is what a backup is for. Defaulted,
    // so a backup written before memory existed still decodes.
    val personaMemories: Map<String, List<String>> = emptyMap(),
    val personaMemoryEnabled: List<String> = emptyList(),
)

// One persona's embedded artwork. Both slots are optional: a persona may have only an
// icon, only a background, or neither — in which case it is absent from the map above.
@kotlinx.serialization.Serializable
data class PersonaArtwork(
    val icon: String? = null,        // base64 JPEG, written to an "icon" file on restore
    val background: String? = null   // base64 JPEG, written to a "background" file
)

// ---------------------------------------------------------------------------
// Message images: the naming and ownership rules shared by the materializer
// above and the send/remove paths that consume its output.
//
// A shared image is materialized on arrival (saveMessageImage) so the composer
// has an app-owned file it can preview and send. That file is the only copy:
// the send path REUSES it rather than re-encoding it, and the remove button
// DELETES it. Both decisions turn on "is this path one of ours?", which is the
// predicate below — deliberately pure (string shape only, no filesystem) so it
// is pinned by a JVM test instead of only ever being exercised on a device.
// ---------------------------------------------------------------------------

/** Slot [SettingsManager.newArtworkFile] files materialized message images under. */
internal const val MESSAGE_IMAGE_SLOT = "msg"

/**
 * The filename slot-based artwork is written under: `<slot>_<stamp>_<unique>.jpg`.
 *
 * Extracted from the writer so the ownership predicate below can be tested against the SAME
 * naming the writer produces. A pattern copied into the test would drift the moment either side
 * changed, and the failure mode of that drift is silent: a stale pattern simply stops matching,
 * which turns "reuse the attachment's file" back into "write a second file" without a sound.
 */
internal fun artworkFileName(slot: String, stampMillis: Long, unique: String): String =
    "${slot}_${stampMillis}_$unique.jpg"

/**
 * Whether [path] names a file this app materialized for a pending message image: a DIRECT child
 * of [imagesDirPath] whose name carries the [MESSAGE_IMAGE_SLOT] prefix.
 *
 * This gates both destructive decisions — reusing a file instead of copying it, and deleting it
 * when the user drops the attachment — so it must accept nothing else. A picked file from the
 * user's own directories, a persona icon or background, a `msg_*` file in a subdirectory, and a
 * `..` traversal all have to come back false. Existence is deliberately NOT checked here (that
 * needs the filesystem, and this stays pure): the callers that write or delete look at the file
 * itself.
 *
 * String comparison, not `Path` equality: paths are normalized before they get here (both sides
 * go through `toAbsolutePath().toString()`), and a trailing separator is trimmed so the
 * comparison cannot be defeated by one.
 */
internal fun isMaterializedMessageImagePath(path: String?, imagesDirPath: String?): Boolean {
    if (path.isNullOrBlank() || imagesDirPath.isNullOrBlank()) return false
    val separator = '/'
    val trimmedPath = path.trimEnd(separator)
    val parent = trimmedPath.substringBeforeLast(separator, missingDelimiterValue = "")
    if (parent.isEmpty() || parent != imagesDirPath.trimEnd(separator)) return false
    val name = trimmedPath.substringAfterLast(separator)
    return name.startsWith("${MESSAGE_IMAGE_SLOT}_")
}
