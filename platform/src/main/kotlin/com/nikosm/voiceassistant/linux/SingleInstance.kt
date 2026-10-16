package com.nikosm.voiceassistant.linux

import com.nikosm.voiceassistant.AppLogger
import com.nikosm.voiceassistant.StderrLogger
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * One copy of the program, enforced by an advisory lock on a file under `$XDG_RUNTIME_DIR`.
 *
 * ## Why this exists, and why it is not IPC
 *
 * M2 gives the user a hotkey, and the hotkey the plan records is a WM binding to `celeste-linux
 * --listen` (PLAN.md §5, seam 12). A binding is a key that runs a command, which means the command
 * runs whether or not the application is already up — and two copies of this program are two VAD
 * loops reading the same microphone, so both would transcribe the user's speech and both would
 * answer it. So a second launch has to be able to tell that it is second.
 *
 * What that is *not* is a protocol. There is no message, no handshake, no request to the running
 * instance and nothing it must do in response: the second process finds the lock taken, says so on
 * stderr, and exits. PLAN.md §3/D1's "one JVM process, no IPC" is the constraint, and this is the
 * smallest mechanism that satisfies it — the alternative, having the second launch ask the first to
 * start listening, is the daemon protocol D1 exists to avoid, and it is why seam 12's "arm while
 * running" affordance belongs to M4's tray, which is in-process.
 *
 * ## The kernel releases it, which is the whole reason it is a lock and not a flag
 *
 * `FileChannel.tryLock` on Linux is a **POSIX record lock (`fcntl(F_SETLK)`)**, not an `flock` —
 * worth stating precisely, because a `flock` taken by any other program on this file does not
 * exclude it and it does not exclude a `flock`. Two copies of *this* program both come through
 * here, so they conflict as they must; what would not work is treating this file as a general
 * mutual-exclusion primitive that another tool could join.
 *
 * The kernel drops a record lock when the process dies, however it dies. A crashed instance
 * therefore leaves no stale "running" marker to be cleaned up by hand and no pidfile to go stale on
 * a pid the OS has since reused — the failure mode both of those have and this does not. The file
 * itself is deliberately **never deleted**, because deleting it races: between one process's delete
 * and the next process's open, a third can create and lock a file the second is about to open, and
 * two processes hold "the" lock again. A leftover file holding a released lock is inert, and the
 * pid inside it is for a human reading `~/.local/state` rather than for the code.
 *
 * **Do not open this file a second time while holding the lock.** POSIX record locks are released
 * by closing *any* descriptor for the file in the same process, so a helpful `Files.readString` on
 * the pid — to report it, to log it, to test it — silently drops the guard for every other process
 * while this one still believes it holds it. Java's own `FileLockTable` does not notice, so the
 * mistake is invisible from inside the JVM that made it. Nothing here reads the file once locked,
 * and [readPid] runs only on the path where this process holds nothing.
 *
 * ## It never prevents the program from starting
 *
 * A machine where the lock file cannot be created — a read-only `$XDG_RUNTIME_DIR`, an exhausted
 * inode table — starts normally and logs why it is unguarded ([acquire] returns a handle with
 * nothing behind it). Refusing to start because the *guard* failed would turn a diagnostic into an
 * outage, which is the wrong trade for a program whose main job is to talk to its user.
 */
class SingleInstance internal constructor(
    private val channel: FileChannel?,
    private val lock: FileLock?,
    /** Where the lock lives, or null when there is none. The caller may print it; nothing reads it. */
    val path: Path?,
) : AutoCloseable {

    /** Releases the lock. Idempotent, and safe on an unguarded handle. */
    override fun close() {
        runCatching { lock?.release() }
        runCatching { channel?.close() }
    }

    companion object {
        const val LOCK_NAME = "instance.lock"
        private const val TAG = "SingleInstance"

        /**
         * Takes the lock, or reports that somebody else holds it.
         *
         * @return null **only** when another instance is running and has the lock; a guard that
         *   could not be created returns a handle that does nothing rather than null, so that the
         *   caller's one check means one thing.
         *
         * [env] is a parameter rather than a direct `System.getenv` read for the reason
         * [XdgPaths.resolve] has the same shape: it is what lets a test cover the unset case without
         * writing into the real session's runtime directory.
         */
        fun acquire(
            fallbackDir: Path,
            logger: AppLogger = StderrLogger,
            env: (String) -> String? = { System.getenv(it) },
        ): SingleInstance? {
            val dir = runtimeDir(env) ?: fallbackDir
            val path = try {
                Files.createDirectories(dir)
                dir.resolve(LOCK_NAME)
            } catch (e: IOException) {
                logger.w(TAG, "no instance lock — cannot create $dir: ${e.message}")
                return SingleInstance(null, null, null)
            }

            val channel = try {
                FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            } catch (e: IOException) {
                logger.w(TAG, "no instance lock — cannot open $path: ${e.message}")
                return SingleInstance(null, null, null)
            }

            val lock = try {
                channel.tryLock()
            } catch (e: IOException) {
                runCatching { channel.close() }
                logger.w(TAG, "no instance lock — ${path} cannot be locked: ${e.message}")
                return SingleInstance(null, null, null)
            } catch (e: OverlappingFileLockException) {
                // The same JVM holds it. Unreachable in the application — there is one `main` — and
                // reachable only from a test or a second main in one JVM. It means "already
                // running" exactly as the cross-process case does, and the channel opened above was
                // closed before this branch: a second descriptor on the file would drop the record
                // lock the *first* acquisition is holding.
                runCatching { channel.close() }
                null
            }

            if (lock == null) {
                runCatching { channel.close() }
                // Safe to read its own descriptor: this path holds no lock to lose.
                val holder = readPid(path)
                logger.w(
                    TAG,
                    "another instance is already running" +
                        (holder?.let { " (pid $it)" } ?: "") +
                        " — this launch will exit; arm listening from the running window instead",
                )
                return null
            }

            writePid(channel)
            return SingleInstance(channel, lock, path)
        }

        /**
         * `$XDG_RUNTIME_DIR/celeste-linux`, or null when the session has not set one.
         *
         * The specification guarantees the directory exists, is owned by the invoking user and is
         * `0700` — everything a lock wants — but it guarantees nothing at all when the variable is
         * unset, which happens on a bare X session or over ssh. There is no sensible default to
         * invent there, so the caller's fallback is used instead of a second guess.
         */
        private fun runtimeDir(env: (String) -> String?): Path? {
            val raw = env("XDG_RUNTIME_DIR")?.takeIf { it.isNotEmpty() } ?: return null
            val dir = Path.of(raw)
            return if (dir.isAbsolute) dir.resolve(XdgPaths.APP_DIR) else null
        }

        /** The pid of whoever holds the lock, for the log line. Best effort by definition. */
        private fun readPid(path: Path): Int? = try {
            FileChannel.open(path, StandardOpenOption.READ).use { ch ->
                val buf = ByteBuffer.allocate(32)
                ch.read(buf)
                String(buf.array(), 0, buf.position()).trim().toIntOrNull()
            }
        } catch (e: IOException) {
            null
        }

        private fun writePid(channel: FileChannel) {
            runCatching {
                val pid = ProcessHandle.current().pid().toString() + "\n"
                channel.truncate(0)
                channel.position(0)
                channel.write(ByteBuffer.wrap(pid.toByteArray()))
                channel.force(false)
            }
        }
    }
}
