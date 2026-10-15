package com.nikosm.voiceassistant.linux

import com.nikosm.voiceassistant.AppLogger
import com.nikosm.voiceassistant.Notifier
import com.nikosm.voiceassistant.StderrLogger
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Seam 9 on Linux: `notify-send`, per PLAN.md §5 and D9.
 *
 * D9's reasoning is worth keeping in view: notifications go through
 * `org.freedesktop.Notifications` because every desktop has it, and `notify-send` is the
 * implementation because it is present on both target distributions and needs no DBus binding.
 * This class would be **real, not a `Stub*`** — M4 builds the "Open" action and the click routing
 * on top of it rather than replacing it, which is why the checkpoint's three-stub condition still
 * holds.
 *
 * ## The two commands, and why there are two
 *
 * Posting is `notify-send -p …`: `-p` makes it print the notification's DBus id, which is the only
 * way to learn it — `notify-send` exits immediately and the desktop owns the card from then on.
 * That id is what [cancel] needs, and `notify-send` itself cannot close a notification, so
 * cancelling is a `gdbus` `CloseNotification` call.
 *
 * That second command is the part worth admitting to: `gdbus` ships with glib, which every GTK and
 * GNOME desktop has, but a minimal window manager might not. When it is missing, cancelling is a
 * logged no-op and the card stays until it is dismissed — a stale notification, not a failure, and
 * nothing in the ported code depends on the cancellation having happened. The alternative was
 * binding DBus directly, which D9 rejects for a dependency this small.
 *
 * ## What is deliberately absent
 *
 * No channel, no group, no summary card: those are Android's grouping model, and a desktop
 * notification has no equivalent. No `--wait`: this must not block the caller, which is a
 * background delivery path. And no persisted id map — see [Notifier].
 */
class NotifySendNotifier(
    private val logger: AppLogger = StderrLogger,
    /** Injected so the command surface is testable without a notification daemon. */
    private val run: (List<String>) -> CommandResult = ::runCommand,
    private val which: (String) -> Boolean = ::isOnPath,
) : Notifier {

    /** Our id -> the desktop's DBus id. In memory only; see [Notifier]. */
    private val posted = ConcurrentHashMap<Int, Int>()

    private val notifySendAvailable: Boolean by lazy { which("notify-send") }
    private val gdbusAvailable: Boolean by lazy { which("gdbus") }

    override val enabled: Boolean get() = notifySendAvailable

    override fun post(id: Int, title: String, body: String) {
        if (!notifySendAvailable) {
            logger.w(TAG, "notify-send is not installed — notification '$title' dropped (the message itself is kept)")
            return
        }
        val result = run(listOf("notify-send", "-p", "-a", APP_NAME, "-u", "normal", title, body))
        if (!result.ok) {
            logger.w(TAG, "notify-send failed (exit ${result.exitCode}) — notification '$title' dropped")
            return
        }
        // `-p` prints the id; anything unparseable means we simply cannot cancel this one later,
        // which is worth a line and not worth failing over.
        val dbusId = result.stdout.trim().toIntOrNull()
        if (dbusId == null) {
            logger.d(TAG, "notify-send did not report an id for '$title' — it will not be cancellable")
        } else {
            posted[id] = dbusId
        }
    }

    override fun cancel(id: Int) {
        val dbusId = posted.remove(id) ?: return
        if (!gdbusAvailable) {
            logger.d(TAG, "gdbus is not installed — notification $dbusId left on screen")
            return
        }
        val result = run(
            listOf(
                "gdbus", "call",
                "--session",
                "--dest", "org.freedesktop.Notifications",
                "--object-path", "/org/freedesktop/Notifications",
                "--method", "org.freedesktop.Notifications.CloseNotification",
                dbusId.toString(),
            )
        )
        if (!result.ok) {
            logger.d(TAG, "could not close notification $dbusId (exit ${result.exitCode})")
        }
    }

    private companion object {
        const val TAG = "Notifier"

        /** Shown by desktops as the application a notification came from. */
        const val APP_NAME = "celeste-linux"
    }
}

/** What a command produced. [exitCode] is -1 when it could not be started at all. */
data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val ok: Boolean get() = exitCode == 0
}

/**
 * Runs a command and captures its output. `ProcessBuilder` lives in `:platform` and nowhere else
 * (AGENTS.md §4), which is why this free function is here rather than beside its callers.
 *
 * Both streams are drained: a child that fills its stderr pipe while nobody reads it blocks, and
 * `notify-send` on a broken DBus does exactly that.
 */
private fun runCommand(command: List<String>): CommandResult {
    return try {
        val process = ProcessBuilder(command).start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        CommandResult(process.waitFor(), stdout, stderr)
    } catch (e: IOException) {
        CommandResult(exitCode = -1, stdout = "", stderr = e.message ?: "could not start ${command.firstOrNull()}")
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        CommandResult(exitCode = -1, stdout = "", stderr = "interrupted")
    }
}

/** Whether [name] is an executable on `PATH`, without starting it. */
private fun isOnPath(name: String): Boolean {
    val path = System.getenv("PATH") ?: return false
    return path.split(':').any { dir ->
        val candidate = java.nio.file.Path.of(dir, name)
        java.nio.file.Files.isExecutable(candidate) && java.nio.file.Files.isRegularFile(candidate)
    }
}
