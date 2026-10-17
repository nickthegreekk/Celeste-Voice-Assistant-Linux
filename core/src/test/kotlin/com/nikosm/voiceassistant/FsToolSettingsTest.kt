package com.nikosm.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

/**
 * The filesystem settings' two halves: the four stored values and how they resolve, and the
 * **lifecycle** every name-keyed setting in this app has to join.
 *
 * The lifecycle is the half worth testing hardest. A name-keyed entry outlives the thing it names
 * unless somebody moves it, drops it and sweeps it — and for this family the failure is not dead
 * state: a rename that leaves a mode behind under the old name, or an import that restores a persona
 * list without it, can hand a *new* persona the previous one's folder and trust level.
 *
 * An in-memory [SettingsStore] rather than the real `JsonSettingsStore`, which lives in `:platform`
 * and cannot be imported from `:core` (AGENTS.md §2) — the same choice `SettingsManagerCorruptionTest`
 * documents, and the store's own behaviour is pinned by `JsonSettingsStoreTest`.
 */
class FsToolSettingsTest {

    // The shape `SettingsManagerCorruptionTest` documents: `:core` cannot import the real
    // `JsonSettingsStore` (AGENTS.md §2), and what this file is about is the manager's own two levels
    // and their lifecycle rather than the file format, which `JsonSettingsStoreTest` pins.
    private class FakeSettingsStore : SettingsStore {
        val map = LinkedHashMap<String, Any>()
        override fun getString(key: String, default: String?): String? = (map[key] as? String) ?: default
        override fun getLong(key: String, default: Long): Long = (map[key] as? Long) ?: default
        override fun getFloat(key: String, default: Float): Float = (map[key] as? Float) ?: default
        override fun getBoolean(key: String, default: Boolean): Boolean = (map[key] as? Boolean) ?: default
        override fun allKeys(): Set<String> = map.keys.toSet()
        override fun edit(): SettingsEdit = object : SettingsEdit {
            private val changes = LinkedHashMap<String, Any?>()
            override fun putString(key: String, value: String): SettingsEdit = also { changes[key] = value }
            override fun putLong(key: String, value: Long): SettingsEdit = also { changes[key] = value }
            override fun putFloat(key: String, value: Float): SettingsEdit = also { changes[key] = value }
            override fun putBoolean(key: String, value: Boolean): SettingsEdit = also { changes[key] = value }
            override fun remove(key: String): SettingsEdit = also { changes[key] = null }
            override fun commit() {
                for ((key, value) in changes) {
                    if (value == null) map.remove(key) else map[key] = value
                }
                changes.clear()
            }
        }
    }

    /** Four paths and a logger — the whole of what a settings manager asks of its environment. */
    private class FakeEnv(private val root: Path) : AppEnv {
        override val homeDir: Path get() = root.resolve("home")
        override val configDir: Path get() = root.resolve("config")
        override val dataDir: Path get() = root.resolve("data")
        override val stateDir: Path get() = root.resolve("state")
        override val cacheDir: Path get() = root.resolve("cache")
        override val picturesDir: Path get() = cacheDir.resolve("Pictures")
        override val applicationsDir: Path get() = cacheDir.resolve("applications")
        override val desktopDir: Path get() = cacheDir.resolve("no-such-desktop")
        override val debugLogging: Boolean get() = false
        override val logger: AppLogger get() = StderrLogger
        override val notifier: Notifier get() = throw UnsupportedOperationException("no settings test posts a notification")
        override val scheduler: Scheduler get() = throw UnsupportedOperationException("no settings test arms a timer")
    }

    /** No artwork is touched below; a codec that always fails would fail loudly if one ever were. */
    private class NoImageCodec : ImageCodec {
        override fun downscaleToJpeg(source: Path, target: Path, maxDim: Int, quality: Int): Boolean = false
    }

    private val root: Path = Path.of(System.getProperty("java.io.tmpdir"), "fs-tool-settings")
    private val store = FakeSettingsStore()
    private val manager = SettingsManager(FakeEnv(root), store, NoImageCodec())

    // ------------------------------------------------------------------ resolution

    @Test
    fun `off is the shipped state and the answer for every way the pairs fail to add up`() {
        assertNull("nothing is set at all", resolveFsWorkspace(null, null, FsToolMode.OFF, null))
        assertNull("read-write with no folder is effectively off", resolveFsWorkspace(null, null, FsToolMode.READ_WRITE, null))
        assertNull("so is read-only with no folder", resolveFsWorkspace(null, null, FsToolMode.READ_ONLY, "   "))
        assertNull(
            "a relative folder is refused rather than resolved against the process's working directory",
            resolveFsWorkspace(null, null, FsToolMode.READ_ONLY, "notes"),
        )
        assertNull("and a path this JVM cannot parse is not an exception either", resolveFsWorkspace(FsToolMode.READ_ONLY, null, FsToolMode.OFF, null))
    }

    @Test
    fun `the persona's value wins per field, and the global fills the other one`() {
        val globalPath = Path.of("/home/u/notes").toString()

        val inherited = resolveFsWorkspace(null, null, FsToolMode.READ_ONLY, globalPath)
        assertEquals(FsWorkspace(Path.of(globalPath), FsToolMode.READ_ONLY), inherited)

        val ownFolder = resolveFsWorkspace(null, "/home/u/work", FsToolMode.READ_ONLY, globalPath)
        assertEquals(
            "a different folder at the global trust level is a reasonable thing to want",
            FsWorkspace(Path.of("/home/u/work"), FsToolMode.READ_ONLY),
            ownFolder,
        )

        val ownMode = resolveFsWorkspace(FsToolMode.READ_WRITE, null, FsToolMode.READ_ONLY, globalPath)
        assertEquals(FsToolMode.READ_WRITE, ownMode?.mode)
        assertEquals("and it keeps the global folder", Path.of(globalPath), ownMode?.root)

        assertNull(
            "a persona whose own mode is off has nothing, whatever the global says",
            resolveFsWorkspace(FsToolMode.OFF, "/home/u/work", FsToolMode.READ_WRITE, globalPath),
        )
    }

    // ------------------------------------------------------------------ the stored values

    @Test
    fun `the global pair round-trips, and an unreadable mode reads as off`() {
        assertNull("absent", manager.getFsWorkspace())
        assertEquals(FsToolMode.OFF, manager.getFsMode())

        manager.saveFsMode(FsToolMode.READ_WRITE)
        manager.saveFsWorkspace("/home/u/notes")
        assertEquals(FsToolMode.READ_WRITE, manager.getFsMode())
        assertEquals("/home/u/notes", manager.getFsWorkspace())

        store.map["files_mode"] = "READ_WRITE_MAYBE"
        assertEquals("a mode this build cannot read must narrow, never widen", FsToolMode.OFF, manager.getFsMode())
    }

    @Test
    fun `a persona's null mode removes the key, and a blank folder does too`() {
        manager.savePersonaFsMode("Work", FsToolMode.READ_ONLY)
        manager.savePersonaFsWorkspace("Work", "/home/u/work")
        assertEquals(FsToolMode.READ_ONLY, manager.getPersonaFsMode("Work"))
        assertEquals("/home/u/work", manager.getPersonaFsWorkspace("Work"))

        manager.savePersonaFsMode("Work", null)
        manager.savePersonaFsWorkspace("Work", "  ")
        assertNull("null means follow the global, which is the absence of a key", manager.getPersonaFsMode("Work"))
        assertNull(manager.getPersonaFsWorkspace("Work"))
        assertFalse(store.allKeys().any { it.startsWith("persona_fs_") })
    }

    @Test
    fun `un-pinning a persona drops its folder with the mode, so the pair cannot be mixed`() {
        // The two halves of the resolution are read from two levels, and that is what made this a
        // widening rather than a tidiness bug: the global mode applied to the persona's own folder.
        manager.saveFsMode(FsToolMode.READ_WRITE)
        manager.saveFsWorkspace("/home/u/everything")
        manager.savePersonaFsMode("Work", FsToolMode.READ_ONLY)
        manager.savePersonaFsWorkspace("Work", "/home/u/work")

        manager.savePersonaFsMode("Work", null)

        assertNull("the mode is gone", manager.getPersonaFsMode("Work"))
        assertNull("and the folder goes with it", manager.getPersonaFsWorkspace("Work"))
        assertEquals(
            "so the persona follows the global pair rather than keeping read-write access to a " +
                "folder it chose for reading",
            FsWorkspace(Path.of("/home/u/everything"), FsToolMode.READ_WRITE),
            resolveFsWorkspace(
                manager.getPersonaFsMode("Work"),
                manager.getPersonaFsWorkspace("Work"),
                manager.getFsMode(),
                manager.getFsWorkspace(),
            ),
        )
    }

    @Test
    fun `a corrupted persona mode narrows to off rather than following the global`() {
        // The asymmetry that matters: "follow the global" would be a widening if the global is
        // read-write, so the decoder's deny-everything fallback is the only safe direction here.
        manager.savePersonaFsMode("Work", FsToolMode.READ_WRITE)
        store.map["persona_fs_mode_Work"] = "yes please"

        val mode = manager.getPersonaFsMode("Work")
        assertEquals(FsToolMode.OFF, mode)
        assertNull(
            "and the resolution therefore has nothing to offer",
            resolveFsWorkspace(mode, "/home/u/work", FsToolMode.READ_WRITE, "/home/u/everything"),
        )
    }

    // ------------------------------------------------------------------ the three lifecycle legs

    @Test
    fun `a rename carries both keys, and leaves nothing under the old name`() {
        manager.savePersonaFsMode("Old", FsToolMode.READ_WRITE)
        manager.savePersonaFsWorkspace("Old", "/home/u/work")

        manager.migratePersonaHistory("Old", "New")

        assertEquals(FsToolMode.READ_WRITE, manager.getPersonaFsMode("New"))
        assertEquals("/home/u/work", manager.getPersonaFsWorkspace("New"))
        assertNull("a mode left under the old name could be inherited by a later persona", manager.getPersonaFsMode("Old"))
        assertNull(manager.getPersonaFsWorkspace("Old"))
    }

    @Test
    fun `deleting a persona drops both keys`() {
        manager.savePersonaFsMode("Doomed", FsToolMode.READ_WRITE)
        manager.savePersonaFsWorkspace("Doomed", "/home/u/work")

        manager.deletePersonaHistory("Doomed")

        assertNull(manager.getPersonaFsMode("Doomed"))
        assertNull(manager.getPersonaFsWorkspace("Doomed"))
    }

    @Test
    fun `an import sweeps both keys for a persona it did not bring back`() {
        // The leg that is easy to forget and the one that outlives everything: without it the entry
        // stays in the blob forever, unreachable and waiting for a persona of that name.
        manager.savePersonaFsMode("Ghost", FsToolMode.READ_WRITE)
        manager.savePersonaFsWorkspace("Ghost", "/home/u/work")

        // A *new-format* backup, and that detail is the whole test: a backup this build cannot
        // decode falls back to `importLegacyBackup`, which restores server bases and touches no
        // name-keyed state at all — so a legacy input would pass while proving nothing. An empty
        // export is a valid backup whose persona list is empty, which is exactly the restore that
        // leaves an orphan behind.
        val backupWithoutGhost = manager.exportBackup()
        assertTrue(manager.importBackup(backupWithoutGhost))

        assertNull("keys after the import: ${store.map.keys}", manager.getPersonaFsMode("Ghost"))
        assertNull(manager.getPersonaFsWorkspace("Ghost"))
        assertFalse(store.allKeys().any { it.startsWith("persona_fs_") })
    }

    @Test
    fun `neither value travels in a backup`() {
        // The same call the output device made, with one more reason: a restored backup must not
        // silently re-grant write access to a folder on a machine that never chose it.
        manager.saveFsMode(FsToolMode.READ_WRITE)
        manager.saveFsWorkspace("/home/u/notes")
        manager.savePersonaFsMode("Work", FsToolMode.READ_WRITE)
        manager.savePersonaFsWorkspace("Work", "/home/u/work")

        val backup = manager.exportBackup()

        assertFalse("the global mode is device-local", backup.contains("files_mode"))
        assertFalse("and so is the folder", backup.contains("/home/u/notes"))
        assertFalse("and the per-persona pair", backup.contains("persona_fs_"))
    }

    /**
     * **The trust does not travel either, and that is what makes persisting it safe** (2026-10-17).
     * The design kept trust out of storage because "a value that survives a restart is a value that
     * survives a backup"; the backup is an *allowlist*, so a stored grant travels no further than the
     * workspace it is about — which itself cannot travel. Pinned here rather than argued in a comment,
     * because adding a field to `BackupData` is a one-line change nobody would think to reconsider.
     */
    @Test
    fun `the write trust is stored, and still never travels in a backup`() {
        manager.saveTrustedFsRoots(listOf("/home/u/notes"))

        assertEquals(listOf("/home/u/notes"), manager.getTrustedFsRoots())
        assertFalse(
            "a restored backup must not re-grant write access to a folder",
            manager.exportBackup().contains("trusted_fs_roots"),
        )
        assertFalse(manager.exportBackup().contains("/home/u/notes"))
    }

    @Test
    fun `an unreadable trust record means ask again, not trust everything`() {
        store.map["trusted_fs_roots"] = "not json"

        assertEquals("the fail-closed direction: no roots, so every write asks", emptyList<String>(), manager.getTrustedFsRoots())
    }
}
