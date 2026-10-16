package com.nikosm.voiceassistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * Per-persona shortcuts: one entry in the application menu that opens straight into that persona's
 * chat — PLAN.md §9's replacement for Android's launcher shortcuts.
 *
 * ## The design is upstream's, translated
 *
 * Upstream's file says the important thing about this feature in its own header, and it survives the
 * translation intact: **a shortcut adds no launch path of its own.** It is another producer of the
 * deep link the spontaneous-message notifications use — `--persona <name>` here, the same
 * `EXTRA_TARGET_PERSONA_NAME` extra there — so a tap inherits that path's properties: it interrupts
 * an in-flight turn on another persona exactly as a manual picker tap does, it leaves room mode, and
 * a persona renamed or deleted since is consumed harmlessly rather than crashing.
 *
 * **The name IS the id**, as it was upstream, because [Persona] carries no stable identifier. The
 * file name is therefore derived from the name and carries a short hash of it, so two personas whose
 * names differ only in what a file name cannot hold ("Vicky" and "Vicky ") get two entries rather
 * than one that silently replaces the other. And because the id is the name, a **rename withdraws
 * the old entry** rather than leaving an icon that opens whatever happens to be selected — the one
 * sharp edge upstream named, handled in the same place it was: the persona-update path.
 *
 * ## What is not here
 *
 * The launcher's confirmation dialog. Upstream *requested* a shortcut and the launcher asked the
 * user; a `.desktop` file is written directly and there is no dialog to ask for. What replaces it is
 * the notification `notifyUser` already raises on the way out — the same "Saved to …" shape the
 * gallery save uses — so a click that did something says what.
 */
internal const val PERSONA_SHORTCUT_PREFIX = "celeste-linux-"

/**
 * The command the entry runs. `celeste-linux` by name rather than by absolute path, for the same
 * reason `packaging/systemd/celeste-linux.service` names it that way: these files are written by the
 * installed application, and the installed application is on the PATH. A build tree that has no such
 * binary yet is what M6's packaging fixes rather than what this function should guess at.
 */
internal const val PERSONA_SHORTCUT_EXEC = "celeste-linux"

/**
 * A file-name-safe form of [personaName], plus a hash of the exact name.
 *
 * The hash is not decoration: file names cannot hold trailing spaces, case-only differences on a
 * case-insensitive filesystem, or a slash, and two personas may differ in exactly one of those. A
 * slug alone would let the second entry overwrite the first, which is a silent loss of a shortcut
 * the user asked for; the hash makes the mapping injective instead.
 */
internal fun personaShortcutSlug(personaName: String): String {
    val slug = personaName.lowercase()
        .map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("")
        .trim('-')
        .take(48)
    val hash = personaName.hashCode().toUInt().toString(16).padStart(8, '0')
    return if (slug.isEmpty()) hash else "$slug-$hash"
}

/** The file this persona's entry lives in, inside [applicationsDir]. */
internal fun personaShortcutFile(personaName: String, applicationsDir: Path): Path =
    applicationsDir.resolve("$PERSONA_SHORTCUT_PREFIX${personaShortcutSlug(personaName)}.desktop")

/**
 * The entry's text.
 *
 * `Exec` quoting follows the desktop-entry specification rather than the shell: inside a quoted
 * argument, `"`, `` ` ``, `$` and `\` must each be escaped with a backslash, and the name is quoted
 * because persona names contain spaces. Getting this wrong is not a crash — it is an entry that
 * opens the wrong persona, or none, which is the kind of failure this file exists to avoid.
 *
 * `Icon` is the persona's artwork when it has one, as a file path. It is written without checking
 * that the image is a format every menu renders: the alternative is either refusing artwork that
 * works on most desktops or converting it (M6's packaging work), and a missing icon is a menu that
 * falls back to a generic one rather than a broken entry.
 */
internal fun personaDesktopEntry(personaName: String, artworkPath: String?): String = buildString {
    appendLine("[Desktop Entry]")
    appendLine("Type=Application")
    appendLine("Name=${personaName.trim()}")
    appendLine("Comment=Chat with ${personaName.trim()}")
    appendLine("Exec=$PERSONA_SHORTCUT_EXEC --persona \"${escapeForDesktopExec(personaName)}\"")
    if (!artworkPath.isNullOrBlank()) appendLine("Icon=$artworkPath")
    appendLine("Terminal=false")
    appendLine("Categories=Utility;")
}

/** The desktop-entry spec's escaping for a quoted `Exec` argument — see [personaDesktopEntry]. */
internal fun escapeForDesktopExec(value: String): String = buildString {
    value.forEach { ch ->
        if (ch == '"' || ch == '`' || ch == '$' || ch == '\\') append('\\')
        append(ch)
    }
}

/**
 * Write this persona's entry, replacing any entry it already had.
 *
 * @return the file written, or null when it could not be — the caller reports that rather than
 * throwing out of a button press, the same contract the gallery save has.
 */
internal fun writePersonaShortcut(personaName: String, artworkPath: String?, applicationsDir: Path): Path? =
    runCatching {
        Files.createDirectories(applicationsDir)
        val file = personaShortcutFile(personaName, applicationsDir)
        Files.writeString(file, personaDesktopEntry(personaName, artworkPath))
        file
    }.getOrNull()

/**
 * Remove this persona's entry, if it has one.
 *
 * Called from the two places a persona stops existing under its old name — deletion and rename —
 * because the name is the id: a surviving entry would open whichever persona the application has
 * selected rather than the one the user pinned, which is worse than no entry at all.
 *
 * @return true when a file was removed.
 */
internal fun removePersonaShortcut(personaName: String, applicationsDir: Path): Boolean =
    runCatching { Files.deleteIfExists(personaShortcutFile(personaName, applicationsDir)) }.getOrDefault(false)

/**
 * Create (or refresh) a persona's entry, and say where it went.
 *
 * IO on `Dispatchers.IO` and the report on the snackbar, like every other button that touches the
 * filesystem. The persona's artwork is read from the model so that a persona which has one gets an
 * icon and one that does not still gets a working entry.
 */
internal fun AssistantService.createPersonaShortcut(personaName: String) {
    val persona = personas.value.find { it.name == personaName }
    if (persona == null) {
        notifyUser("No persona named '$personaName'")
        return
    }
    serviceScope.launch(Dispatchers.IO) {
        val written = writePersonaShortcut(personaName, persona.iconImageUri, appEnv.applicationsDir)
        logger.i("AssistantService", if (written != null) "persona shortcut written to $written" else "could not write a shortcut for '$personaName'")
        withContext(Dispatchers.Main) {
            notifyUser(
                if (written != null) "Shortcut for '${persona.name}' added to your applications"
                else "Could not add a shortcut for '${persona.name}' — see the log"
            )
        }
    }
}
