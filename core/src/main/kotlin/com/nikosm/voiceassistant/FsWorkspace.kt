package com.nikosm.voiceassistant

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths

/**
 * What a persona may do with files, chosen by the user (PLAN.md §10/M8): nothing, read, or read and
 * write. **Off is the default and a real answer** — the plan's words — so an absent or unreadable
 * setting resolves to [OFF] rather than to the permissive value.
 */
internal enum class FsToolMode {
    OFF,
    READ_ONLY,
    READ_WRITE,
    ;

    val allowsReads: Boolean get() = this != OFF
    val allowsWrites: Boolean get() = this == READ_WRITE

    companion object {
        /**
         * Tolerant decoder, the shape `imageGenBackendFromName` established for stored enums: an
         * unknown value is [OFF], because a value this build cannot read must never widen access.
         */
        fun fromName(value: String?): FsToolMode {
            val normalized = value?.trim()?.replace('-', '_')?.replace(' ', '_')
            return entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) } ?: OFF
        }
    }
}

/** A root the user chose and the mode they chose for it. */
internal data class FsWorkspace(val root: Path, val mode: FsToolMode)

/**
 * What a persona actually has, from the four stored values: its own mode and folder, and the global
 * pair. **The persona's value wins per field, else the global's** — the shape the app already uses
 * for image-generation entries — and `null` means nothing is reachable, which is the answer for
 * every way this can fail to add up:
 *
 *  - the resolved mode is [FsToolMode.OFF];
 *  - there is no folder at either level (the plan's "read-write with no workspace is effectively
 *    off", and saying so is the caller's job, not this function's);
 *  - the stored folder is not a usable absolute path. A **relative** one is refused rather than
 *    resolved: it would resolve against the process's working directory, which for a packaged
 *    application is `/` and for a development run is a checkout — a workspace that moves depending
 *    on how the program was started is worse than one that is off.
 *
 * Pure, so the settings UI, the service and the tests all ask the same question of the same four
 * values and cannot disagree about what "follow the global" means.
 */
internal fun resolveFsWorkspace(
    personaMode: FsToolMode?,
    personaPath: String?,
    globalMode: FsToolMode,
    globalPath: String?,
): FsWorkspace? {
    val mode = personaMode ?: globalMode
    if (mode == FsToolMode.OFF) return null
    val raw = personaPath?.takeIf { it.isNotBlank() } ?: globalPath?.takeIf { it.isNotBlank() } ?: return null
    val root = runCatching { Path.of(raw) }.getOrNull() ?: return null
    if (!root.isAbsolute) return null
    return FsWorkspace(root, mode)
}

/**
 * A path a tool call may not touch, with the sentence the model is told. An exception rather than a
 * null because every one of these is a *refusal*: the caller turns it into a tool result the model
 * can read and adapt to, which is not the same thing as an empty answer.
 */
internal class FsToolRefusal(val reason: String) : Exception(reason)

/**
 * The paths no workspace may ever reach, resolved (PLAN.md §10/M8).
 *
 * Two of them, and the plan's reason for naming them in the UI rather than quietly enforcing them is
 * worth repeating because it is about a *silent* failure: *"Read my notes" and "read my keys" are one
 * word apart, and that failure is silent — the key would go into a prompt, possibly a cloud one.*
 *
 *  - `configDir` — the settings file, which holds the API keys and server passwords.
 *  - `~/.ssh` — private keys.
 *
 * Resolved with `toRealPath` where the path exists, so a symlinked `~/.ssh` is still the same path,
 * and normalised where it does not, so a machine without `~/.ssh` still gets the rule rather than a
 * crash.
 *
 * Takes the two paths rather than an `AppEnv`: the caller has one (`appEnv.configDir`,
 * `appEnv.homeDir`) and this function then needs no service, no fake and no environment to be
 * tested — which for the rule that guards the user's private keys is worth more than the two
 * arguments it saves.
 */
internal fun alwaysExcludedPaths(configDir: Path, homeDir: Path): List<Path> =
    listOf(configDir, homeDir.resolve(".ssh")).map(::realOrNormalized)

/** Whether [target] is an excluded path or lives inside one. Both sides must already be resolved. */
internal fun isDenied(target: Path, excluded: List<Path>): Boolean =
    excluded.any { target == it || target.startsWith(it) }

/**
 * Why this directory cannot be a workspace, or null when it can. For the settings UI, which names the
 * reason at the moment the user picks — an accepted-then-dead workspace is the failure this prevents.
 */
internal fun workspaceRootRefusal(candidate: Path, excluded: List<Path>): String? = when {
    !Files.isDirectory(candidate) -> "That is not a directory."
    isDenied(realOrNormalized(candidate), excluded) ->
        "That folder is always excluded, so it cannot be a workspace. Choose another folder."
    else -> null
}

/**
 * The single gate every tool path goes through. Returns the **resolved** absolute path the operation
 * may touch, or throws [FsToolRefusal] with a sentence for the model.
 *
 * ## Why it resolves on both ends
 *
 * A string check for `..` is not containment: `notes/link.md` is a perfectly ordinary name whether it
 * is a file or a symlink to `/etc/shadow`, and a workspace is allowed to contain symlinks — the user
 * made them. So the decision is made on `toRealPath()`, which follows every hop, and the comparison is
 * between two real paths. A symlink inside the workspace that points outside it is therefore refused
 * *because of where it lands*, not because of how it is spelled.
 *
 * ## Writes resolve the parent, reads resolve the target
 *
 * A write's target usually does not exist yet — `toRealPath` on it would throw — so the *parent* is
 * resolved (it must exist, which also makes a dangling symlink impossible there), required to be
 * inside the root, and the final component is taken as a plain name with no separators. If a file of
 * that name already exists, its own real path is checked too, so overwriting *through* a symlink that
 * leaves the workspace is refused as well. Nothing here ever creates a symlink.
 *
 * The excluded list is checked on the resolved path in every branch, which is what makes a workspace
 * of `$HOME` — the obvious mistake — refuse `~/.ssh` specifically rather than as a whole.
 */
internal fun resolveInside(
    root: Path,
    requested: String,
    forWrite: Boolean,
    excluded: List<Path>,
): Path {
    val rootReal = realOrNormalized(root)
    if (!Files.isDirectory(rootReal)) {
        throw FsToolRefusal("the workspace folder is not available.")
    }
    val raw = runCatching { Paths.get(requested) }.getOrNull()
        ?: throw FsToolRefusal("'$requested' is not a usable path.")
    val candidate = if (raw.isAbsolute) raw.normalize() else rootReal.resolve(raw).normalize()

    return if (forWrite) resolveForWrite(rootReal, candidate, excluded) else resolveForRead(rootReal, candidate, excluded)
}

private fun resolveForRead(rootReal: Path, candidate: Path, excluded: List<Path>): Path {
    val real = runCatching { candidate.toRealPath() }.getOrElse {
        throw FsToolRefusal("there is no such file or folder inside the workspace.")
    }
    if (isDenied(real, excluded)) {
        throw FsToolRefusal("that path is always excluded from filesystem tools, whatever the workspace.")
    }
    if (!real.startsWith(rootReal)) {
        throw FsToolRefusal("that path resolves outside the workspace (a symlink can leave it), so it cannot be read.")
    }
    return real
}

private fun resolveForWrite(rootReal: Path, candidate: Path, excluded: List<Path>): Path {
    val name = candidate.fileName?.toString().orEmpty()
    if (name.isEmpty() || name == "." || name == "..") {
        throw FsToolRefusal("that is not a file name inside the workspace.")
    }
    val parent = candidate.parent ?: throw FsToolRefusal("that path has no folder to write into.")
    val parentReal = runCatching { parent.toRealPath() }.getOrElse {
        throw FsToolRefusal("the folder for that file does not exist inside the workspace.")
    }
    if (!parentReal.startsWith(rootReal)) {
        throw FsToolRefusal("that path resolves outside the workspace (a symlink can leave it), so it cannot be written.")
    }
    val target = parentReal.resolve(name)
    if (isDenied(parentReal, excluded) || isDenied(target, excluded)) {
        throw FsToolRefusal("that path is always excluded from filesystem tools, whatever the workspace.")
    }
    // An existing target is followed once more: overwriting through a symlink that leaves the
    // workspace is the same escape as reading through one.
    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
        val targetReal = runCatching { target.toRealPath() }.getOrNull()
            ?: throw FsToolRefusal("that path is a link that cannot be followed, so it cannot be written.")
        if (!targetReal.startsWith(rootReal)) {
            throw FsToolRefusal("that file is a symlink out of the workspace, so it cannot be written.")
        }
        // The exclusion, asked again on the *resolved* path. The check above it saw only the
        // spelling, so a link the user made — `notes/settings.json -> ~/.config/celeste-linux/…` —
        // passed both tests and wrote the credentials file. The read path has always resolved first;
        // this one now does too.
        if (isDenied(targetReal, excluded)) {
            throw FsToolRefusal("that path is always excluded from filesystem tools, whatever the workspace.")
        }
        if (Files.isDirectory(targetReal)) {
            throw FsToolRefusal("that is a folder, not a file.")
        }
        return targetReal
    }
    return target
}

/**
 * `toRealPath` where the path exists — so a symlinked `~/.ssh` is still recognised as itself — and a
 * plain normalised absolute path where it does not, so the rule survives a machine that has no such
 * directory yet. Resolution is the whole point of the comparison, so a version of this that silently
 * returned the raw path would be a security bug rather than a fallback.
 */
private fun realOrNormalized(path: Path): Path =
    runCatching { path.toRealPath() }.getOrElse { path.toAbsolutePath().normalize() }
