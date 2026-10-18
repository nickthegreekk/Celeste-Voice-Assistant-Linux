package com.nikosm.voiceassistant

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.name

/**
 * Where a persona's conversation and images live on disk (2026-10-18).
 *
 * The user asked for this shape by name: one folder per persona under `~/.celeste-linux/`, holding
 * the chat and its images, with rooms getting the same treatment under [ROOMS_DIR]. It replaces
 * the `persona_messages_<name>` / `room_messages_<id>` entries in the settings blob, which was the
 * phone's shape (one `SharedPreferences` file) and treated the desktop's disk as an accident.
 *
 * ```
 * ~/.celeste-linux/
 *   <persona>/
 *     chat.json          List<ChatMessage>, the same JSON that was in the settings key
 *     images/            that persona's artwork, attachments and generated images
 *   rooms/
 *     <room>-<id>/       the id suffix is the room's identity, so a rename moves nothing
 *       chat.json        List<RoomMessage>
 * ```
 *
 * **The folder name is derived, not stored.** A persona is identified by its name everywhere in
 * this application (the settings keys, backups, shortcuts), so the folder is the name made safe
 * for a filesystem: letters, digits, spaces and `._-()[]` pass through; anything else becomes
 * `_` and the name gets a short hash suffix so two names that sanitise alike (`a/b` and `a_b`)
 * cannot share a folder. `rooms` is reserved at the root because rooms live there. Rooms carry
 * their full id as a suffix, which is what makes rename safe — the folder is found by id, and the
 * readable part is just a label.
 *
 * **Writes are atomic.** `chat.json` is written to a sibling `.tmp` and moved into place, so a
 * crash mid-save leaves the previous transcript rather than half a file. This is the same
 * guarantee `JsonSettingsStore` gives the settings blob, and here it matters more: a transcript
 * is the user's data, not a preference.
 *
 * `:core` may do file I/O with paths `AppEnv` supplied (the same licence `cacheDir` already
 * has); it still may not resolve `$HOME` or `$XDG_*` for itself. `homeDir` comes from the host.
 */
internal class ConversationFolders(private val homeDir: Path) {

    val root: Path get() = homeDir.resolve(ROOT_DIR)
    val roomsRoot: Path get() = root.resolve(ROOMS_DIR)

    fun personaDir(personaName: String): Path = root.resolve(personaFolderName(personaName))
    fun personaChatFile(personaName: String): Path = personaDir(personaName).resolve(CHAT_FILE)
    fun personaImagesDir(personaName: String): Path = personaDir(personaName).resolve(IMAGES_DIR)

    fun roomDir(roomId: String, roomName: String?): Path =
        roomsRoot.resolve(roomFolderName(roomName, roomId))

    fun roomChatFile(roomId: String, roomName: String?): Path = roomDir(roomId, roomName).resolve(CHAT_FILE)

    /**
     * The room's folder as it exists now, found by the id suffix — which is how a renamed room
     * keeps its transcript without the caller having to remember the old name.
     */
    fun findRoomDir(roomId: String): Path? = runCatching {
        if (!Files.isDirectory(roomsRoot)) return null
        Files.list(roomsRoot).use { entries ->
            entries.filter { Files.isDirectory(it) && it.name.endsWith("-$roomId") }
                .findFirst().orElse(null)
        }
    }.getOrNull()

    /** Moves a renamed room's folder into place, or just returns the target for a new room. */
    fun ensureRoomDir(roomId: String, roomName: String?): Path {
        val target = roomDir(roomId, roomName)
        val existing = findRoomDir(roomId)
        if (existing != null && existing != target) moveDirectory(existing, target)
        return target
    }

    fun readText(file: Path): String? = runCatching {
        if (Files.isRegularFile(file)) Files.readString(file) else null
    }.getOrNull()

    fun writeText(file: Path, text: String): Boolean = runCatching {
        Files.createDirectories(file.parent)
        val temp = file.resolveSibling("${file.name}.tmp")
        Files.writeString(temp, text)
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        }
        true
    }.getOrDefault(false)

    fun movePersona(oldName: String, newName: String) {
        if (oldName == newName) return
        val from = personaDir(oldName)
        val to = personaDir(newName)
        if (!Files.isDirectory(from)) return
        moveDirectory(from, to)
    }

    fun deletePersona(personaName: String) {
        deleteTree(personaDir(personaName))
    }

    fun deleteRoom(roomId: String, roomName: String?) {
        deleteTree(findRoomDir(roomId) ?: roomDir(roomId, roomName))
    }

    /**
     * Removes persona/room folders that no current persona or room owns — the folder twin of the
     * post-restore sweep that used to walk `persona_messages_*` keys. A backup restore replaces
     * the persona and room lists; without this, the dropped entries' conversations would sit on
     * disk forever, which the key sweep existed to prevent.
     */
    fun sweepOrphans(personaNames: Collection<String>, rooms: Collection<Room>) {
        val expectedPersonas = personaNames.map { personaFolderName(it) }.toSet()
        val expectedRooms = rooms.map { roomFolderName(it.name, it.id) }.toSet()

        runCatching {
            if (Files.isDirectory(root)) {
                Files.list(root).use { entries ->
                    entries.filter { Files.isDirectory(it) }
                        .filter { it.name != ROOMS_DIR && it.name !in expectedPersonas }
                        .forEach { deleteTree(it) }
                }
            }
        }
        runCatching {
            if (Files.isDirectory(roomsRoot)) {
                Files.list(roomsRoot).use { entries ->
                    entries.filter { Files.isDirectory(it) && it.name !in expectedRooms }
                        .forEach { deleteTree(it) }
                }
            }
        }
    }

    private fun moveDirectory(from: Path, to: Path) {
        runCatching {
            Files.createDirectories(to.parent)
            if (!Files.exists(to)) {
                Files.move(from, to)
                return
            }
            // The destination already exists (a persona renamed onto a name whose folder survived
            // a deletion, for instance). Per-file moves keep both; the arriving persona's own
            // files win by name, and anything only the destination had is left in place.
            Files.createDirectories(to)
            Files.list(from).use { entries ->
                entries.forEach { entry ->
                    val target = to.resolve(entry.fileName.toString())
                    if (Files.isDirectory(entry)) {
                        if (!Files.exists(target)) Files.move(entry, target)
                    } else {
                        Files.move(entry, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
            Files.deleteIfExists(from)
        }
    }

    private fun deleteTree(path: Path) {
        runCatching {
            if (!Files.exists(path)) return
            Files.walk(path).use { walk ->
                walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    companion object {
        const val ROOT_DIR = ".celeste-linux"
        const val ROOMS_DIR = "rooms"
        const val CHAT_FILE = "chat.json"
        const val IMAGES_DIR = "images"

        private val SAFE_NAME = Regex("[^\\p{L}\\p{N} ._\\-()\\[\\]]")
        private val RESERVED = setOf(ROOMS_DIR, IMAGES_DIR)

        /**
         * The persona's folder name. Normal names pass through unchanged — `Sylvia` is `Sylvia`,
         * `Translator (inn)` is `Translator (inn)`. A name that had to be altered, or that would
         * collide with a reserved directory, carries a short hash of the original so the mapping
         * stays one-to-one.
         */
        internal fun personaFolderName(personaName: String): String {
            val clean = personaName.replace(SAFE_NAME, "_").trim().trimEnd('.').take(64).ifBlank { "unnamed" }
            return if (clean == personaName && clean !in RESERVED) clean
            else "${clean.take(48)}-${shortHash(personaName)}"
        }

        /** `<readable name>-<full id>`: the id is the identity, the name is the label. */
        internal fun roomFolderName(roomName: String?, roomId: String): String {
            val clean = (roomName ?: "").replace(SAFE_NAME, "_").trim().trimEnd('.').take(40).ifBlank { "room" }
            return "$clean-$roomId"
        }

        private fun shortHash(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            return digest.take(4).joinToString("") { "%02x".format(it) }
        }
    }
}
