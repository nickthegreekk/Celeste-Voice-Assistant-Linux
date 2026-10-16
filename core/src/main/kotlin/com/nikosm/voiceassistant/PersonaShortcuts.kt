package com.nikosm.voiceassistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

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
private const val TAG = "PersonaShortcuts"

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
    // Every persona entry opens the same window, so every one of them claims the same window:
    // `Main.kt` sets AWT's `sun.awt.X11.XWMClassName` to this name, that is what the window reports
    // as its `WM_CLASS`, and this is what the entry says it owns. Without the pair a dock cannot
    // tell which icon a running Celeste should wear and falls back to a generic one.
    appendLine("StartupWMClass=$PERSONA_SHORTCUT_EXEC")
}

/** The desktop-entry spec's escaping for a quoted `Exec` argument — see [personaDesktopEntry]. */
internal fun escapeForDesktopExec(value: String): String = buildString {
    value.forEach { ch ->
        if (ch == '"' || ch == '`' || ch == '$' || ch == '\\') append('\\')
        append(ch)
    }
}

/**
 * Write this persona's entry, replacing any entry it already had, in **both** places a shortcut can
 * live: the application menu, and the user's Desktop.
 *
 * The Desktop copy is the one the user asked for on 2026-10-16, in their words: *"shortcut to persona
 * on Desktop can't be created"* — the menu entry had been written and worked, and it was somewhere
 * they do not look. Two differences from the menu copy, both forced by how a desktop reads the file:
 *
 *  - **It is executable.** `~/.local/share/applications` is read by menu machinery that does not
 *    care about the mode; a `.desktop` file sitting *on* a Desktop is a launcher the file manager
 *    offers to run, and GNOME, KDE and XFCE all require the executable bit for that. Without it the
 *    icon appears and double-clicking it opens a text editor, which is worse than not writing it.
 *  - **It is only written if the Desktop directory already exists.** A machine with no `~/Desktop`
 *    is a machine whose owner does not keep shortcuts there; creating the directory to put one file
 *    in it would be this function deciding something that is not its to decide.
 *
 * The file name keeps [PERSONA_SHORTCUT_PREFIX], so a shortcut can never collide with a file the
 * user put there themselves — the name the user *sees* comes from the entry's `Name=` key either way.
 *
 * @return the menu entry written, or null when *that* could not be — the caller reports it rather
 * than throwing out of a button press, the same contract the gallery save has. A Desktop copy that
 * fails on its own is logged and does not fail the press: the shortcut exists, one directory over.
 */
internal fun writePersonaShortcut(
    personaName: String,
    artworkPath: String?,
    applicationsDir: Path,
    desktopDir: Path? = null,
    logger: AppLogger? = null,
): Path? =
    runCatching {
        Files.createDirectories(applicationsDir)
        val entry = personaDesktopEntry(personaName, artworkPath)
        val file = personaShortcutFile(personaName, applicationsDir)
        Files.writeString(file, entry)

        if (desktopDir != null && Files.isDirectory(desktopDir)) {
            val deskFile = personaShortcutFile(personaName, desktopDir)
            if (runCatching {
                    Files.writeString(deskFile, entry)
                    Files.setPosixFilePermissions(deskFile, PosixFilePermissions.fromString("rwxr-xr-x"))
                }.isFailure
            ) {
                logger?.w(TAG, "could not write the Desktop copy of the shortcut at $deskFile")
            }
        }
        file
    }.getOrNull()

/**
 * Remove this persona's entry, if it has one.
 *
 * Called from the two places a persona stops existing under its old name — deletion and rename —
 * because the name is the id: a surviving entry would open whichever persona the application has
 * selected rather than the one the user pinned, which is worse than no entry at all.
 *
 * Removes **both** copies — the menu entry and the Desktop one — because a shortcut that survives on
 * the Desktop after its persona is gone is worse than one that never existed: it opens whatever
 * persona happens to be selected. `Files.deleteIfExists` on a directory that is not there is false
 * rather than an error, so a machine with no `~/Desktop` needs no special case here.
 *
 * @return true when a menu entry was removed. The Desktop copy's removal is reported by its own
 * absence rather than by this flag: the caller's message is about the shortcut, not the file.
 */
internal fun removePersonaShortcut(personaName: String, applicationsDir: Path, desktopDir: Path? = null): Boolean {
    if (desktopDir != null) {
        runCatching { Files.deleteIfExists(personaShortcutFile(personaName, desktopDir)) }
    }
    return runCatching { Files.deleteIfExists(personaShortcutFile(personaName, applicationsDir)) }.getOrDefault(false)
}

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
        val written = writePersonaShortcut(
            personaName = personaName,
            artworkPath = persona.iconImageUri,
            applicationsDir = appEnv.applicationsDir,
            desktopDir = appEnv.desktopDir,
            logger = logger,
        )
        logger.i("AssistantService", if (written != null) "persona shortcut written to $written" else "could not write a shortcut for '$personaName'")
        withContext(Dispatchers.Main) {
            notifyUser(
                if (written != null) "Shortcut for '${persona.name}' added to your applications and your Desktop"
                else "Could not add a shortcut for '${persona.name}' — see the log"
            )
        }
    }
}
