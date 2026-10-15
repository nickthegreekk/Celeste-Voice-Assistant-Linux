package com.nikosm.voiceassistant

/**
 * Seam 7 (PLAN.md §5): the key–value store the settings blob lives in, replacing Android's
 * `SharedPreferences`.
 *
 * **Why key–value and not a schema.** PLAN.md §7 described this seam as "a JSON settings file",
 * which is true and also undersells what is in it. Reading `SettingsManager` shows about forty
 * independent entries, and two of them are *dynamic families*: `persona_messages_<name>` and
 * `room_messages_<id>`, one entry per persona and per room, created and removed as those come and
 * go. The import path also walks the store **by prefix** — `prefs.all.keys.filter { it.startsWith(
 * "persona_messages_") }` — to find history entries belonging to personas a restore dropped, so
 * they can be swept instead of leaking forever. A typed schema has nowhere to put either of those;
 * a key–value view ports both the sweep and the whole settings blob without changing a line of
 * their logic.
 *
 * **Why the interface is this thin.** Every method below exists because a call site uses it, and
 * the method set is deliberately the same shape as `SharedPreferences`. That is not laziness: the
 * value of porting `SettingsManager` as an ADAPT is that its hard-won behaviour survives — the
 * corruption-tolerant reads, the legacy migrations, the rename and orphan bookkeeping. A store
 * with a different shape would force that file to be rewritten rather than adapted, and the rules
 * in it are the part that took the time to get right.
 *
 * Types are the four `SharedPreferences` supports. Kotlin's `Long` covers the timestamps and the
 * `total_cost_bits` bit-pattern the Android code stores; `Float` is the chat font size.
 */
interface SettingsStore {

    fun getString(key: String, default: String? = null): String?
    fun getLong(key: String, default: Long = 0L): Long
    fun getFloat(key: String, default: Float = 0f): Float
    fun getBoolean(key: String, default: Boolean = false): Boolean

    /**
     * Every key currently in the store.
     *
     * The port of `prefs.all.keys`. Its one caller is the post-restore orphan sweep, which needs
     * the *whole* key set to find `persona_messages_*` and `room_messages_*` entries that no
     * longer have an owner — so this returns a snapshot rather than a live view, or a sweep that
     * deletes as it walks would be reading and writing the same collection.
     */
    fun allKeys(): Set<String>

    /**
     * The port of `prefs.edit()`: stage several writes and land them together.
     *
     * Batching is load-bearing rather than an optimisation. `persistSettings` writes a dozen keys
     * in a row and `migratePersonaHistory` moves one key to another name; against a file-backed
     * store each write would be a full rewrite of the settings file, and a crash between two of
     * them would leave the store in a state no caller ever intended — a persona list saved but
     * the client list not, or history written under both names.
     */
    fun edit(): SettingsEdit
}

/**
 * A staged set of writes, committed as one.
 *
 * The terminal call is `commit()`, not `apply()`. Android's `apply()` is deliberately
 * asynchronous and its `commit()` is the synchronous one; this store is synchronous throughout,
 * so borrowing the name `apply` would import a promise it does not keep — and in Kotlin it would
 * also shadow the scope function of that name.
 */
interface SettingsEdit {
    fun putString(key: String, value: String): SettingsEdit
    fun putLong(key: String, value: Long): SettingsEdit
    fun putFloat(key: String, value: Float): SettingsEdit
    fun putBoolean(key: String, value: Boolean): SettingsEdit

    /** Drops [key] entirely, as `SharedPreferences.Editor.remove` does. */
    fun remove(key: String): SettingsEdit

    /** Applies every staged change in one write. */
    fun commit()
}
