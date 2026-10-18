package com.nikosm.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

// Guards against the one-bad-entry crash loop: every stored-format JSON decode in
// SettingsManager.kt is wrapped in runCatching and must degrade to that getter's
// null-case default instead of propagating. loadSettings()/switchPersona() run inside
// AssistantService.onCreate(), so a single unguarded decode throw turns one corrupt
// entry into a permanent startup crash loop.
//
// Ported from upstream `SettingsManagerCorruptionTest.kt`. **Every assertion is upstream's,
// unchanged** — this file is the executable specification for the behaviour PLAN.md §7 says the
// SettingsManager port must keep, so changing what it asserts would defeat the exercise. What
// changed is the harness, for two reasons:
//
//  - Upstream's premise is that `EncryptedSharedPreferences` cannot initialise without
//    AndroidKeyStore, so the constructor deterministically lands in its plaintext fallback,
//    which the test then backs with an in-memory `SharedPreferences` fake and injects corrupt
//    values into. There is no encrypted store here at all (PLAN.md §5a), so there is no fallback
//    branch to reach and nothing to fake at that level: the corrupt values are injected into a
//    plain in-memory [SettingsStore].
//  - Upstream's `FakeAppContext` exists to give the manager a `Context`. Here the manager takes
//    an [AppEnv], so the fake is four paths and a logger.
//
// The fake store rather than the real `JsonSettingsStore` because that one lives in `:platform`
// and `:core` may not import from it (AGENTS.md §2). Nothing is lost by that: the store's own
// behaviour — the file format, the 0600 mode, the atomic write and the file-level quarantine —
// is pinned by `JsonSettingsStoreTest`, and what this file is about is the per-entry tolerance
// that lives in the manager.
class SettingsManagerCorruptionTest {

    private companion object {
        const val GARBAGE = "{ this is ·definitely· not »valid« JSON ]"
    }

    // In-memory SettingsStore so tests can inject raw (corrupt) values into exactly the surface
    // the decoders read from. Stores values as put, like upstream's fake did.
    private class FakeSettingsStore : SettingsStore {
        val map = HashMap<String, Any>()

        override fun getString(key: String, default: String?): String? = map[key] as? String ?: default
        override fun getLong(key: String, default: Long): Long = map[key] as? Long ?: default
        override fun getFloat(key: String, default: Float): Float = map[key] as? Float ?: default
        override fun getBoolean(key: String, default: Boolean): Boolean = map[key] as? Boolean ?: default
        override fun allKeys(): Set<String> = map.keys.toSet()
        override fun edit(): SettingsEdit = FakeEdit()

        private inner class FakeEdit : SettingsEdit {
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
            }
        }
    }

    /** Four paths and a logger — the whole of what `SettingsManager` asks of a `Context` now. */
    private class FakeAppEnv(private val root: Path) : AppEnv {
        override val configDir: Path get() = root.resolve("config")
        override val dataDir: Path get() = root.resolve("data")
        override val stateDir: Path get() = root.resolve("state")
        override val cacheDir: Path get() = root.resolve("cache")
        override val picturesDir: Path get() = cacheDir.resolve("Pictures")
    override val applicationsDir: Path get() = cacheDir.resolve("applications")
    // No Desktop in a test: `null` is not possible on the interface, so this points at a directory
    // that does not exist — which is exactly the case the writer has to skip (it never creates it).
    override val desktopDir: Path get() = cacheDir.resolve("no-such-desktop")
    // A home that does not exist, like the Desktop above: nothing in these tests reaches into it,
    // and the workspace rules that will are tested against real temp directories of their own.
    override val homeDir: Path get() = cacheDir.resolve("home")
        override val debugLogging: Boolean get() = false
        override val logger: AppLogger get() = StderrLogger
        override val notifier: Notifier get() = throw UnsupportedOperationException("no settings test posts a notification")
        override val scheduler: Scheduler get() = throw UnsupportedOperationException("no settings test arms a timer")
    }

    /**
     * No artwork is touched by anything below. A codec that always reports failure is the honest
     * fake here: if a future edit to these tests did reach for an image, it would fail loudly
     * rather than silently write a file.
     */
    private class NoImageCodec : ImageCodec {
        override fun downscaleToJpeg(source: Path, target: Path, maxDim: Int, quality: Int): Boolean = false
    }

    /**
     * A manager on its own store, i.e. its own "device" — the port of upstream's
     * `newManagerWithPlainPrefs`, which built a fresh `FakeAppContext` per call for exactly that
     * reason. Several tests below restore a backup onto a second manager and assert on what came
     * out, which only means anything if the second manager has never seen the data.
     *
     * The root comes from [TempRoots], not a fixed name in `/tmp`: since conversations are files
     * under `homeDir` (2026-10-18), a fixed path made one RUN leave a room transcript behind that
     * the NEXT run's corruption test then read — a cross-run leak no test could see while the
     * store was in memory.
     */
    private fun newManager(): Pair<SettingsManager, SettingsStore> {
        val env = FakeAppEnv(TempRoots.newRoot("settings-corruption"))
        val store = FakeSettingsStore()
        return SettingsManager(env, store, NoImageCodec()) to store
    }

    private fun SettingsStore.put(key: String, value: String) {
        edit().putString(key, value).commit()
    }

    // ---- Mechanism pin: getters really read the raw store, so the corrupt-entry
    // ---- tests below inject into exactly the surface the decoder sees (not vacuous).

    @Test fun `getters round-trip through the plaintext fallback store`() {
        val (manager, store) = newManager()
        manager.saveServerBases(listOf(ServerConfig("S1", "http://192.0.2.2:11434")))
        val stored = store.getString("server_bases_v2")
        assertTrue("expected raw JSON in the store, got: $stored", stored!!.contains("\"url\":\"http://192.0.2.2:11434\""))
        assertEquals(listOf(ServerConfig("S1", "http://192.0.2.2:11434")), manager.getServerBases())
    }

    // ---- Each stored-format getter survives a corrupt entry by returning its null-case default

    @Test fun `corrupted server_bases_v2 returns null instead of crashing`() {
        val (manager, store) = newManager()
        store.put("server_bases_v2", GARBAGE)
        assertNull(manager.getServerBases())
    }

    @Test fun `corrupted ollama_bases_v2 returns null instead of crashing`() {
        val (manager, store) = newManager()
        store.put("ollama_bases_v2", GARBAGE)
        assertNull(manager.getOllamaBases())
    }

    @Test fun `corrupted cloud_apis_v2 returns null instead of crashing`() {
        val (manager, store) = newManager()
        store.put("cloud_apis_v2", GARBAGE)
        assertNull(manager.getCloudApis())
    }

    @Test fun `corrupted custom_cloud_apis_v1 returns null instead of crashing`() {
        val (manager, store) = newManager()
        store.put("custom_cloud_apis_v1", GARBAGE)
        assertNull(manager.getCustomCloudApis())
    }

    @Test fun `corrupted personas_v2 returns null instead of crashing`() {
        val (manager, store) = newManager()
        store.put("personas_v2", GARBAGE)
        assertNull(manager.getPersonas())
    }

    @Test fun `corrupted chat_history_v2 returns null instead of crashing`() {
        val (manager, store) = newManager()
        store.put("chat_history_v2", GARBAGE)
        assertNull(manager.getMessages())
    }

    @Test fun `corrupted persona history returns null instead of crashing`() {
        val (manager, store) = newManager()
        store.put("persona_messages_default", GARBAGE)
        assertNull(manager.getPersonaMessages("default"))
    }

    @Test fun `corrupted room history returns null instead of crashing`() {
        val (manager, store) = newManager()
        store.put("room_messages_r1", GARBAGE)
        assertNull(manager.getRoomMessages("r1"))
    }

    @Test fun `corrupted rooms_v1 returns null instead of crashing`() {
        val (manager, store) = newManager()
        store.put("rooms_v1", GARBAGE)
        assertNull(manager.getRooms())
    }

    @Test fun `corrupted skills_v1 returns null instead of crashing`() {
        val (manager, store) = newManager()
        store.put("skills_v1", GARBAGE)
        assertNull(manager.getSkills())
    }

    @Test fun `corrupted favorite_models_v2 returns null instead of crashing`() {
        val (manager, store) = newManager()
        store.put("favorite_models_v2", GARBAGE)
        assertNull(manager.getFavoriteModels())
    }

    @Test fun `corrupted trusted_certs_v2 returns empty map instead of crashing`() {
        val (manager, store) = newManager()
        store.put("trusted_certs_v2", GARBAGE)
        assertEquals(emptyMap<String, String>(), manager.getTrustedCertificates())
    }

    @Test fun `corrupted model_pricing_v1 returns empty map instead of crashing`() {
        val (manager, store) = newManager()
        store.put("model_pricing_v1", GARBAGE)
        assertEquals(emptyMap<String, ModelPricing>(), manager.getModelPricing())
    }

    // ---- Future-format-change scenario: VALID JSON that no longer matches the expected type

    @Test fun `valid JSON of the wrong shape is treated as corrupt`() {
        val (manager, store) = newManager()
        store.put("server_bases_v2", "\"just a string, not a list\"")
        store.put("personas_v2", "[1, 2, 3]")
        assertNull(manager.getServerBases())
        assertNull(manager.getPersonas())
    }

    // ---- One bad entry must not take down the others

    @Test fun `one corrupt entry does not affect other entries`() {
        val (manager, store) = newManager()
        manager.saveFavoriteModels(listOf("qwen2.5", "llama3"))
        manager.saveMessages(listOf(ChatMessage(role = "user", text = "hello")))
        store.put("server_bases_v2", GARBAGE)
        assertNull(manager.getServerBases())
        assertEquals(listOf("qwen2.5", "llama3"), manager.getFavoriteModels())
        assertEquals(listOf(ChatMessage(role = "user", text = "hello")), manager.getMessages())
    }

    // ---- Happy paths are unchanged by the guards

    @Test fun `favorite models round-trip unchanged`() {
        val (manager, _) = newManager()
        manager.saveFavoriteModels(listOf("a", "b"))
        assertEquals(listOf("a", "b"), manager.getFavoriteModels())
    }

    @Test fun `trusted certificates round-trip unchanged`() {
        val (manager, _) = newManager()
        manager.saveTrustedCertificates(mapOf("host.local" to "CERTDATA"))
        assertEquals(mapOf("host.local" to "CERTDATA"), manager.getTrustedCertificates())
    }

    @Test fun `chat history round-trip unchanged`() {
        val (manager, _) = newManager()
        val messages = listOf(
            ChatMessage(role = "user", text = "hi"),
            ChatMessage(role = "assistant", text = "hello", responseTimeMs = 42L)
        )
        manager.saveMessages(messages)
        assertEquals(messages, manager.getMessages())
    }

    @Test fun `model pricing round-trip unchanged`() {
        val (manager, _) = newManager()
        val pricing = mapOf("llama3" to ModelPricing(prompt = 0.1, completion = 0.2))
        manager.saveModelPricing(pricing)
        assertEquals(pricing, manager.getModelPricing())
    }

    // ---- Fix #14: legacy/no-op imports must not be reported as successes

    @Test fun `legacy backup with server_bases imports and reports success`() {
        val (manager, _) = newManager()
        val legacy = "{\"server_bases\":[\"http://192.0.2.2:11434\",\"http://192.0.2.3:11434\"]}"
        assertTrue(manager.importBackup(legacy))
        assertEquals(
            listOf(ServerConfig("Server 1", "http://192.0.2.2:11434"), ServerConfig("Server 2", "http://192.0.2.3:11434")),
            manager.getServerBases()
        )
    }

    @Test fun `arbitrary json object is not reported as a successful import`() {
        val (manager, store) = newManager()
        assertFalse(manager.importBackup("{\"unrelated\":\"value\"}"))
        assertNull(manager.getServerBases())
        assertFalse(store.allKeys().contains("server_bases_v2"))
    }

    @Test fun `garbage input fails import`() {
        val (manager, _) = newManager()
        assertFalse(manager.importBackup("not json at all"))
    }

    @Test fun `explicitly empty legacy server_bases imports nothing`() {
        val (manager, store) = newManager()
        assertFalse(manager.importBackup("{\"server_bases\":[]}"))
        assertFalse(store.allKeys().contains("server_bases_v2"))
    }

    @Test fun `new-format backup round-trips`() {
        val (manager, _) = newManager()
        manager.saveServerBases(listOf(ServerConfig("S1", "http://192.0.2.2:11434")))
        manager.saveTotalCost(1.25)
        val export = manager.exportBackup()

        val (manager2, _) = newManager()
        assertTrue(manager2.importBackup(export))
        assertEquals(listOf(ServerConfig("S1", "http://192.0.2.2:11434")), manager2.getServerBases())
        assertEquals(1.25, manager2.getTotalCost(), 0.0)
    }

    // ---- Rooms

    @Test fun `rooms and their transcripts round-trip through a backup`() {
        val (manager, _) = newManager()
        // The personas must travel in the backup: restore prunes any room member that the
        // restored persona list doesn't contain, so a room backed up without its members
        // would legitimately come back empty. That pruning is asserted separately below.
        manager.savePersonas(
            listOf(
                Persona("A", androidx.compose.ui.graphics.Color(0xFF4ADE80), "[S] m", "sys"),
                Persona("B", androidx.compose.ui.graphics.Color(0xFF60A5FA), "[S] m", "sys")
            )
        )
        val room = Room(id = "r1", name = "War Room", memberNames = listOf("A", "B"), autoReplyCap = 9)
        manager.saveRooms(listOf(room))
        val messages = listOf(RoomMessage(speakerName = "A", text = "hello"))
        manager.saveRoomMessages("r1", messages)
        val export = manager.exportBackup()

        val (manager2, _) = newManager()
        assertTrue(manager2.importBackup(export))
        assertEquals(listOf(room), manager2.getRooms())
        val restored = manager2.getRoomMessages("r1")!!
        assertEquals(1, restored.size)
        assertEquals("A", restored.single().speakerName)
        assertEquals("hello", restored.single().text)
        // The id and timestamp must survive too — a fresh id would mean a NEW message
        // rather than the persisted one.
        assertEquals(messages.single().id, restored.single().id)
        assertEquals(messages.single().timestamp, restored.single().timestamp)
    }

    @Test fun `a zero cap round-trips as zero, not as an absent cap`() {
        // The serialization hazard behind "0 means initial round only": a stored 0 must come
        // back as 0. If it decayed to null on the way, the room would silently inherit the
        // default and resume autonomous chatter the user had switched off — and a null here
        // is exactly what an absent key looks like, so this pins the two apart.
        val (manager, _) = newManager()
        manager.savePersonas(
            listOf(
                Persona("A", androidx.compose.ui.graphics.Color(0xFF4ADE80), "[S] m", "sys"),
                Persona("B", androidx.compose.ui.graphics.Color(0xFF60A5FA), "[S] m", "sys")
            )
        )
        manager.saveRooms(
            listOf(
                Room(id = "zero", name = "Zero", memberNames = listOf("A", "B"), autoReplyCap = 0),
                Room(id = "inherit", name = "Inherit", memberNames = listOf("A", "B"), autoReplyCap = null)
            )
        )
        val loaded = manager.getRooms()!!.associateBy { it.id }
        assertEquals(0, loaded.getValue("zero").autoReplyCap)
        assertNull("an unset cap must still read back as null", loaded.getValue("inherit").autoReplyCap)
        assertEquals(0, RoomReplyPolicy.capFor(loaded.getValue("zero")))
        assertEquals(
            "only the unset room inherits the default",
            RoomReplyPolicy.DEFAULT_AUTO_REPLY_CAP,
            RoomReplyPolicy.capFor(loaded.getValue("inherit"))
        )
    }

    @Test fun `restoring prunes room members the restore dropped but keeps the transcript`() {
        val (manager, _) = newManager()
        // The backup knows "A" and "B"; only "A" survives the restore.
        manager.savePersonas(
            listOf(
                Persona("A", androidx.compose.ui.graphics.Color(0xFF4ADE80), "[S] m", "sys")
            )
        )
        manager.saveRooms(listOf(Room(id = "r1", name = "R", memberNames = listOf("A", "B"))))
        val original = listOf(RoomMessage(speakerName = "B", text = "bye"))
        manager.saveRoomMessages("r1", original)
        val export = manager.exportBackup()

        // Now simulate a restore onto a device that has no "B" at all.
        val (manager2, _) = newManager()
        val trimmed = export.replace("\"B\"", "\"ZZZ\"")
        assertTrue(manager2.importBackup(trimmed))
        assertEquals(listOf("A"), manager2.getRooms()!!.single().memberNames)
        // B's words stay in the transcript: membership is corrected, history is not.
        // Compared field-wise, NOT as whole objects — a freshly constructed RoomMessage
        // carries a new id and timestamp and could never equal the persisted one.
        val restored = manager2.getRoomMessages("r1")!!
        assertEquals(1, restored.size)
        assertEquals("ZZZ", restored.single().speakerName)
        assertEquals("bye", restored.single().text)
        assertEquals(original.single().timestamp, restored.single().timestamp)
    }

    @Test fun `a backup written before rooms existed still imports`() {
        val (manager, _) = newManager()
        manager.saveServerBases(listOf(ServerConfig("S1", "http://192.0.2.2:11434")))
        val export = manager.exportBackup()
        // Strip the room fields entirely, as an older build would have written it.
        val legacy = export.replace(Regex(",\"rooms\":.*?\"roomMessages\":\\{.*?\\}"), "")
        val (manager2, _) = newManager()
        assertTrue("an old backup must still import", manager2.importBackup(legacy))
        assertEquals(emptyList<Room>(), manager2.getRooms())
    }

    // ---- Skills: the shared library is persona-INDEPENDENT, so a persona holds only ids.
    // ---- These pin that relationship surviving a full backup/restore onto another device.

    private val skillLibrary = listOf(
        Skill(id = "s-poem", name = "Poet", triggerPhrases = listOf("write a poem"), instructions = "Answer in verse."),
        Skill(id = "s-joke", name = "Joker", triggerPhrases = listOf("tell me a joke"), instructions = "Be funny.")
    )

    @Test fun `skills round-trip unchanged through their own key`() {
        val (manager, store) = newManager()
        manager.saveSkills(skillLibrary)
        val stored = store.getString("skills_v1")
        assertTrue("expected raw JSON in the store, got: $stored", stored!!.contains("\"s-poem\""))
        assertEquals(skillLibrary, manager.getSkills())
    }

    @Test fun `an absent skills key reads as no skills rather than null`() {
        val (manager, _) = newManager()
        // A fresh install and every pre-Skills backup must mean "no skills", not "unknown".
        assertEquals(emptyList<Skill>(), manager.getSkills())
    }

    @Test fun `a persona's enabled skills survive a backup and restore`() {
        val (manager, _) = newManager()
        manager.saveSkills(skillLibrary)
        manager.savePersonas(
            listOf(
                Persona(
                    name = "A",
                    themeColor = androidx.compose.ui.graphics.Color(0xFF4ADE80),
                    model = "[S] m",
                    systemPrompt = "sys",
                    enabledSkillIds = listOf("s-joke", "s-poem")
                )
            )
        )
        val export = manager.exportBackup()

        // Restore onto a different (empty) device, as a reinstall or a new phone would.
        val (manager2, _) = newManager()
        assertTrue(manager2.importBackup(export))
        assertEquals(skillLibrary, manager2.getSkills())
        assertEquals(listOf("s-joke", "s-poem"), manager2.getPersonas()!!.single().enabledSkillIds)
    }

    @Test fun `restoring prunes enabled skills the backup does not carry`() {
        val (manager, _) = newManager()
        // The persona's store references the joker, but the library does not contain it —
        // the state a restore faces when a backup was hand-edited, or written by a build
        // whose library had already dropped the skill. Written straight to the store so the
        // two lists genuinely disagree: going through removeSkill would (correctly) prune the
        // persona too, leaving nothing here to test.
        manager.saveSkills(listOf(skillLibrary[0]))
        manager.savePersonas(
            listOf(
                Persona(
                    name = "A",
                    themeColor = androidx.compose.ui.graphics.Color(0xFF4ADE80),
                    model = "[S] m",
                    systemPrompt = "sys",
                    enabledSkillIds = listOf("s-joke")
                )
            )
        )
        val export = manager.exportBackup()
        val (manager2, _) = newManager()
        assertTrue("backup must import", manager2.importBackup(export))
        assertEquals(
            "the dangling id must be pruned on restore",
            emptyList<String>(),
            manager2.getPersonas()!!.single().enabledSkillIds
        )
        // The persona that survives is otherwise untouched, and the real skill is kept.
        assertEquals(listOf("s-poem"), manager2.getSkills()!!.map { it.id })
        assertEquals("A", manager2.getPersonas()!!.single().name)
    }

    @Test fun `a backup written before skills existed still imports`() {
        val (manager, _) = newManager()
        manager.saveServerBases(listOf(ServerConfig("S1", "http://192.0.2.2:11434")))
        val export = manager.exportBackup()
        // Strip the skills field entirely, as an older build would have written it.
        val legacy = export.replace(Regex(",\"skills\":\\[.*?\\]"), "")
        assertTrue("old backup must still import", !legacy.contains("\"skills\""))
        val (manager2, _) = newManager()
        assertTrue(manager2.importBackup(legacy))
        assertEquals(emptyList<Skill>(), manager2.getSkills())
    }

    // ---- Spontaneous messages: the six per-persona fields travel WITH the persona, the
    // ---- delivery state deliberately does NOT, and a pre-feature backup still imports.
    // ---- Same shape as the Rooms/Skills blocks above: export from one manager, import into
    // ---- a second one that has never seen the data, assert on what came out.

    private fun spontaneousPersona(
        name: String,
        enabled: Boolean = false,
        instruction: String = "",
        gapMinutes: Int = 0,
        windowStart: Int? = null,
        windowEnd: Int? = null,
        maxPerDay: Int = 0
    ) = Persona(
        name = name,
        themeColor = androidx.compose.ui.graphics.Color(0xFF4ADE80),
        model = "[S] m",
        systemPrompt = "sys",
        spontaneousEnabled = enabled,
        spontaneousInstruction = instruction,
        spontaneousGapMinutes = gapMinutes,
        spontaneousWindowStartMin = windowStart,
        spontaneousWindowEndMin = windowEnd,
        spontaneousMaxPerDay = maxPerDay
    )

    @Test fun `the spontaneous fields survive a backup and restore, set and defaulted`() {
        val (manager, _) = newManager()
        // The opt-in only survives a write for an ELIGIBLE persona (a local model on a
        // configured server), so the probe has to be one — otherwise savePersonas strips the
        // flag first and the test would be asserting a strip rather than a round-trip.
        manager.saveOllamaBases(listOf(ServerConfig("S", "http://192.0.2.2:11434")))
        manager.savePersonas(
            listOf(
                spontaneousPersona(
                    name = "Set",
                    enabled = true,
                    instruction = "only ask about the garden",
                    gapMinutes = 90,
                    windowStart = 9 * 60,
                    windowEnd = 21 * 60,
                    maxPerDay = 3
                ),
                // The other half of the contract, and the half a "set everything" test misses:
                // a persona the user never touched must come back DEFAULTED, not as some other
                // persona's values and not as an explicitly-off copy.
                spontaneousPersona(name = "Defaults")
            )
        )
        val export = manager.exportBackup()
        // Non-vacuity: the values really are in the payload, spelled the way a hand-written
        // backup would spell them (encodeDefaults=true is what puts the keys on the persona
        // that was left alone, too).
        assertTrue(export.contains("\"spontaneousEnabled\":true"))
        assertTrue(export.contains("\"spontaneousInstruction\":\"only ask about the garden\""))
        assertTrue(export.contains("\"spontaneousGapMinutes\":90"))
        assertTrue(export.contains("\"spontaneousWindowStartMin\":540"))
        assertTrue(export.contains("\"spontaneousWindowEndMin\":1260"))
        assertTrue(export.contains("\"spontaneousMaxPerDay\":3"))

        // Restore onto a different (empty) device, as a reinstall or a new phone would.
        val (manager2, _) = newManager()
        assertTrue(manager2.importBackup(export))

        val restored = manager2.getPersonas()!!.associateBy { it.name }
        val set = restored.getValue("Set")
        assertTrue("the opt-in must survive", set.spontaneousEnabled)
        assertEquals("only ask about the garden", set.spontaneousInstruction)
        assertEquals(90, set.spontaneousGapMinutes)
        assertEquals(9 * 60, set.spontaneousWindowStartMin)
        assertEquals(21 * 60, set.spontaneousWindowEndMin)
        assertEquals(3, set.spontaneousMaxPerDay)

        val defaults = restored.getValue("Defaults")
        assertFalse(defaults.spontaneousEnabled)
        assertEquals("", defaults.spontaneousInstruction)
        assertEquals(0, defaults.spontaneousGapMinutes)
        assertNull(defaults.spontaneousWindowStartMin)
        assertNull(defaults.spontaneousWindowEndMin)
        assertEquals(0, defaults.spontaneousMaxPerDay)
    }

    @Test fun `delivery state is not backed up and restores as never sent`() {
        val (manager, _) = newManager()
        manager.savePersonas(listOf(spontaneousPersona("Set", enabled = true, gapMinutes = 60)))
        manager.recordSpontaneousSent("Set", at = 1_700_000_000_000L, todayDate = "2026-01-01")
        assertEquals(
            "the probe must really have state to lose",
            1,
            manager.spontaneousStateFor("Set").sentTodayCount
        )

        val export = manager.exportBackup()
        // Deliberately NOT in the backup. Asserted on the PAYLOAD, so the exclusion is a fact
        // about the format rather than a claim about the code: the delivery counters are
        // runtime bookkeeping (when this persona last spoke, how often today), not settings.
        // Carrying them across a restore would hand the new device another device's history —
        // "already sent 3 today" would silently suppress messages for the rest of the day.
        assertFalse("the state entry must not travel", export.contains("spontaneous_state_v1"))
        assertFalse("lastSentAt must not travel", export.contains("lastSentAt"))
        assertFalse("sentTodayCount must not travel", export.contains("sentTodayCount"))

        val (manager2, _) = newManager()
        assertTrue(manager2.importBackup(export))
        // Absent entry → the never-sent default. Not null, not a throw. Pacing after a restore
        // comes from the ARMING path, which measures the gap from arm time (now + gap), so a
        // restored persona does not fire the moment the app opens — see
        // SpontaneousSchedulerTest's "re-arming from a fresh attempt schedules one full gap
        // ahead". "Never sent" is therefore the safe fallback, not merely a harmless one.
        assertEquals(SpontaneousState(), manager2.spontaneousStateFor("Set"))
        assertEquals(0L, manager2.spontaneousStateFor("Set").lastSentAt)
        assertEquals(emptyMap<String, SpontaneousState>(), manager2.getSpontaneousStates())
    }

    @Test fun `a corrupt spontaneous state entry degrades to never sent`() {
        val (manager, store) = newManager()
        store.put("spontaneous_state_v1", GARBAGE)
        // Same contract as every other stored-format read in SettingsManager: the getter
        // swallows the bad entry (these reads run inside AssistantService.onCreate, where a
        // throw is a permanent startup crash loop) and the scheduler sees "never sent" rather
        // than dying on the way to its first decision.
        assertEquals(emptyMap<String, SpontaneousState>(), manager.getSpontaneousStates())
        assertEquals(SpontaneousState(), manager.spontaneousStateFor("Anyone"))
    }

    @Test fun `a backup written before spontaneous messages existed still imports`() {
        val (manager, _) = newManager()
        // Eligible, so the export genuinely carries a live opt-in and non-default settings —
        // the strip below then removes something real rather than a block the guard had
        // already flattened.
        manager.saveOllamaBases(listOf(ServerConfig("S", "http://192.0.2.2:11434")))
        manager.savePersonas(
            listOf(
                spontaneousPersona(
                    name = "Set",
                    enabled = true,
                    instruction = "say something",
                    gapMinutes = 90,
                    windowStart = 9 * 60,
                    windowEnd = 21 * 60,
                    maxPerDay = 3
                )
            )
        )
        val export = manager.exportBackup()
        // Non-vacuity: the export really did carry the live opt-in, so the strip below removes
        // a real setting rather than a block the guard had already flattened.
        assertTrue(export.contains("\"spontaneousEnabled\":true"))
        // Strip the six fields from every persona, exactly as a pre-feature build's backup
        // would have written them. They serialize as one contiguous trailing run (Persona
        // declares them last), so the comma in front of the run goes with it and the persona
        // objects stay valid JSON.
        val legacy = export.replace(
            Regex(",\"spontaneousEnabled\":.*?,\"spontaneousMaxPerDay\":[0-9]+"),
            ""
        )
        assertTrue("the strip must actually have happened", !legacy.contains("spontaneous"))

        val (manager2, _) = newManager()
        assertTrue("an old backup must still import", manager2.importBackup(legacy))

        // Absent keys → the field defaults, through the same ignoreUnknownKeys /
        // defaulted-field contract every other post-hoc field relies on (Rooms, Skills,
        // artwork). No migration, and the persona itself is otherwise intact.
        val restored = manager2.getPersonas()!!.single()
        assertEquals("Set", restored.name)
        assertFalse(restored.spontaneousEnabled)
        assertEquals("", restored.spontaneousInstruction)
        assertEquals(0, restored.spontaneousGapMinutes)
        assertNull(restored.spontaneousWindowStartMin)
        assertNull(restored.spontaneousWindowEndMin)
        assertEquals(0, restored.spontaneousMaxPerDay)
    }

    // ---- F1: the delivery state's LIFECYCLE — it is keyed by persona NAME, so it must
    // ---- follow the persona through deletion, rename and restore exactly like the
    // ---- name-keyed history entry does. All three cases exercise the SHARED surfaces
    // ---- the real service paths route through (deletePersonaHistory / migratePersonaHistory
    // ---- / importBackup), not private reimplementations.

    /**
     * The O10 global daily cap is computed over EVERY stored state entry (the worker's
     * `allStates.values.filter { it.sentTodayDate == today }.sumOf { it.sentTodayCount }`),
     * mirrored here assertion-for-assertion against the same getter.
     */
    private fun globalSentToday(manager: SettingsManager, today: String): Int =
        manager.getSpontaneousStates().values
            .filter { it.sentTodayDate == today }
            .sumOf { it.sentTodayCount }

    @Test fun `the persona deletion path drops the spontaneous state so it stops counting toward the global daily cap`() {
        val (manager, _) = newManager()
        manager.savePersonas(
            listOf(
                spontaneousPersona("Keep", enabled = true, gapMinutes = 60),
                spontaneousPersona("Doomed", enabled = true, gapMinutes = 60)
            )
        )
        manager.recordSpontaneousSent("Keep", at = 1_000L, todayDate = "2026-01-01")
        manager.recordSpontaneousSent("Doomed", at = 2_000L, todayDate = "2026-01-01")
        manager.recordSpontaneousSent("Doomed", at = 3_000L, todayDate = "2026-01-01")
        assertEquals(
            "precondition: the probe really counts toward the cap before its deletion",
            3, globalSentToday(manager, "2026-01-01")
        )

        // THE REAL removePersona path: this is the exact cleanup call its history-IO lane
        // runs (AssistantService.removePersona → deletePersonaHistory). Pre-F1 it dropped
        // only the history entry, and the persona's state entry went on counting toward
        // the cap above for the rest of the day.
        manager.deletePersonaHistory("Doomed")

        assertNull("the deleted persona's history entry is gone", manager.getPersonaMessages("Doomed"))
        assertEquals(
            "and its delivery state must die with it",
            SpontaneousState(), manager.spontaneousStateFor("Doomed")
        )
        assertFalse(
            "the state map must not carry the deleted persona",
            "Doomed" in manager.getSpontaneousStates()
        )
        assertEquals(
            "a dead persona must no longer count toward the global daily cap",
            1, globalSentToday(manager, "2026-01-01")
        )
        assertEquals(
            "a surviving persona's pacing is untouched",
            1, manager.spontaneousStateFor("Keep").sentTodayCount
        )
    }

    @Test fun `a rename carries the spontaneous state to the new name, pacing intact`() {
        val (manager, _) = newManager()
        manager.savePersonas(listOf(spontaneousPersona("Old", enabled = true, gapMinutes = 60)))
        manager.recordSpontaneousSent("Old", at = 1_234_500L, todayDate = "2026-02-02")
        manager.recordSpontaneousSent("Old", at = 1_234_600L, todayDate = "2026-02-02")
        val before = manager.spontaneousStateFor("Old")
        assertEquals("precondition: the probe really has pacing to lose", 2, before.sentTodayCount)
        // A history entry too, so the rename exercises the whole shared surface at once.
        manager.savePersonaMessages("Old", emptyList())

        // THE REAL updatePersona rename path: the exact call its rename branch runs
        // (AssistantService.updatePersona → migratePersonaHistory). Pre-F1 the state
        // entry stayed stranded under the old key — orphaned AND lost to the renamed
        // persona, whose pacing silently reset to "never sent".
        manager.migratePersonaHistory("Old", "New")

        val moved = manager.spontaneousStateFor("New")
        assertEquals("lastSentAt must survive the rename", before.lastSentAt, moved.lastSentAt)
        assertEquals("and the daily counter", before.sentTodayCount, moved.sentTodayCount)
        assertEquals("and the date that counter belongs to", before.sentTodayDate, moved.sentTodayDate)
        assertEquals("the old key must be gone", SpontaneousState(), manager.spontaneousStateFor("Old"))
        assertFalse("Old" in manager.getSpontaneousStates())
        assertEquals("the history entry moved too", emptyList<ChatMessage>(), manager.getPersonaMessages("New"))
        assertNull("and left nothing behind", manager.getPersonaMessages("Old"))

        // A persona carrying state but NO history entry must still migrate: the state move
        // runs independently of the history move, not after its absent-entry early-return.
        manager.recordSpontaneousSent("StateOnly", at = 7_000L, todayDate = "2026-02-02")
        manager.migratePersonaHistory("StateOnly", "StateOnlyNew")
        assertEquals(7_000L, manager.spontaneousStateFor("StateOnlyNew").lastSentAt)
        assertEquals(SpontaneousState(), manager.spontaneousStateFor("StateOnly"))
    }

    @Test fun `an import drops spontaneous state entries for personas the restore did not bring back`() {
        val (manager, _) = newManager()
        manager.savePersonas(listOf(spontaneousPersona("Restored", enabled = true, gapMinutes = 60)))
        // A backup that restores ONLY "Restored".
        val export = manager.exportBackup()

        // The device being restored ONTO carries local delivery state (the device-local
        // bookkeeping that never travels in the backup) for two personas this restore does
        // not bring back — one WITH a history entry, one with state only, because the entry
        // sets are independent — plus local state for the persona that IS restored.
        val (manager2, _) = newManager()
        manager2.recordSpontaneousSent("GhostWithHistory", at = 1_000L, todayDate = "2026-01-01")
        manager2.savePersonaMessages("GhostWithHistory", emptyList())
        manager2.recordSpontaneousSent("GhostStateOnly", at = 2_000L, todayDate = "2026-01-01")
        manager2.recordSpontaneousSent("Restored", at = 3_000L, todayDate = "2026-01-01")

        assertTrue(manager2.importBackup(export))

        assertEquals(
            "an orphan that had a history entry must lose its state",
            SpontaneousState(), manager2.spontaneousStateFor("GhostWithHistory")
        )
        assertNull("and its history entry too", manager2.getPersonaMessages("GhostWithHistory"))
        assertEquals(
            "an orphan with state only must lose it as well — the history sweep cannot see it",
            SpontaneousState(), manager2.spontaneousStateFor("GhostStateOnly")
        )
        assertEquals(
            "the restored persona's LOCAL state survives the import",
            3_000L, manager2.spontaneousStateFor("Restored").lastSentAt
        )
    }
}
