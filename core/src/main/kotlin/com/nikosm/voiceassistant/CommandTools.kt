package com.nikosm.voiceassistant

import java.nio.file.Path
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The terminal tool on the service (PLAN.md §10/M16): the question a command needs answered before
 * it may run, the executor that asks it, and the settings resolution behind both.
 *
 * The pure parts are in `CommandRunner.kt` and `CommandAudit.kt`; this file is only what needs the
 * live service. The shape is M8's write gate, deliberately: a pending question on the service (so
 * closing and reopening the window re-renders it), a `CompletableDeferred` the calling coroutine
 * suspends on, and an answer that arrives from the UI.
 */

/** The question the dialog is showing. [id] makes a stale answer impossible to apply to a newer one. */
internal data class CommandApprovalRequest(
    val id: Long,
    val personaName: String,
    val command: String,
    /** M16.1: the input the call supplies, shown with the command because it is part of what runs. */
    val stdin: String?,
    /** M16.2: whether the call asked for a pseudo-terminal. */
    val tty: Boolean,
    val workingDirectory: Path,
)

/** What the user decided. ONCE and SESSION both allow; the audit distinguishes them. */
internal enum class CommandDecision {
    ONCE,
    SESSION,
    DENIED,
    TIMEOUT,
}

/**
 * The one piece of state a command needs: a question, and whoever is going to answer it.
 *
 * **Trust is one exact command in one directory, for this session only.** A shell cannot be
 * path-contained, so the alternative — "trust this folder" — would be trusting every command
 * anyone can type into it, which is exactly the prompt-injection shape this feature must not
 * create. Nothing is persisted: unlike the filesystem root trust (whose reversal is recorded in
 * docs/DECISIONS.md), there is nothing here that could be restored safely, and a restart should ask
 * again.
 */
internal class CommandApprovalGate {
    private val trusted = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val _pending = MutableStateFlow<CommandApprovalRequest?>(null)

    /** The question the UI is showing, or null when there is nothing to answer. */
    val pending = _pending.asStateFlow()

    @Volatile
    private var deferred: CompletableDeferred<CommandDecision>? = null

    @Volatile
    private var nextId: Long = 0

    private fun key(command: String, stdin: String?, tty: Boolean, workingDirectory: Path): String =
        "${workingDirectory.toAbsolutePath().normalize()}\u0000$command\u0000${stdin.orEmpty()}\u0000$tty"

    fun trusts(command: String, stdin: String?, tty: Boolean, workingDirectory: Path): Boolean =
        trusted.contains(key(command, stdin, tty, workingDirectory))

    /** Forget every session grant. Called when the arrangement they were granted under changes. */
    fun forgetTrust() {
        trusted.clear()
    }

    /**
     * The user's answer to the question [id]. Ignored when it is not the pending question: a dialog
     * left over from a superseded turn must not answer the newer one.
     */
    fun answer(id: Long, approve: Boolean, trustForSession: Boolean = false) {
        val waiting = deferred ?: return
        val request = _pending.value ?: return
        if (request.id != id) return
        if (approve && trustForSession) {
            trusted.add(key(request.command, request.stdin, request.tty, request.workingDirectory))
        }
        waiting.complete(
            when {
                !approve -> CommandDecision.DENIED
                trustForSession -> CommandDecision.SESSION
                else -> CommandDecision.ONCE
            },
        )
    }

    /** A newer turn started, or the app is closing: deny, so a stale dialog can never approve. */
    fun denyPending() {
        deferred?.complete(CommandDecision.DENIED)
    }

    /**
     * Ask, and wait. Answered by [answer], refused by [denyPending], and **denied on timeout** — a
     * turn must not hang forever on a question nobody is there to read. A trusted command skips the
     * question entirely.
     */
    suspend fun request(
        personaName: String,
        command: String,
        stdin: String?,
        tty: Boolean,
        workingDirectory: Path,
        timeoutMs: Long = COMMAND_APPROVAL_TIMEOUT_MS,
    ): CommandDecision {
        if (trusts(command, stdin, tty, workingDirectory)) return CommandDecision.SESSION
        val waiting = CompletableDeferred<CommandDecision>()
        deferred = waiting
        _pending.value = CommandApprovalRequest(++nextId, personaName, command, stdin, tty, workingDirectory)
        return try {
            withTimeoutOrNull(timeoutMs) { waiting.await() } ?: CommandDecision.TIMEOUT
        } finally {
            if (deferred === waiting) {
                _pending.value = null
                deferred = null
            }
        }
    }
}

/** One pending question, reused: there is at most one at a time, answered or withdrawn. */
internal const val COMMAND_APPROVAL_NOTIFICATION_ID = 3

/**
 * Ask before a command. A timeout denies, and the user is told through the notifier — the one
 * channel that still works when no window can render the dialog.
 *
 * Logging here never carries the command text: the audit file is the record, and a journal line
 * duplicating a command line is one more place a secret in an argument can leak. Only the outcome
 * and how it was approved are logged.
 */
internal suspend fun AssistantService.requestCommandApproval(
    personaName: String,
    command: String,
    stdin: String?,
    tty: Boolean,
    workingDirectory: Path
): CommandDecision {
    val decision = commandGate.request(personaName, command, stdin, tty, workingDirectory)
    if (decision == CommandDecision.TIMEOUT) {
        runCatching {
            appEnv.notifier.post(
                COMMAND_APPROVAL_NOTIFICATION_ID,
                "Celeste",
                "Waiting for you to approve a command: ${command.lineSequence().firstOrNull().orEmpty().take(140)}",
            )
        }
    }
    return decision
}

/** Record one command and its outcome, in the log and in the audit file. */
internal fun AssistantService.auditCommand(entry: CommandAuditEntry) {
    logger.i(
        "Commands",
        "run_command -> ${entry.result} (${entry.durationMs}ms, ${entry.confirmed})"
    )
    appendCommandAudit(appEnv.stateDir, entry, logger)
}

/**
 * The persona's effective command policy: its own value, else the global one. Same shape as the
 * filesystem pair, and the same decode rule — an unreadable stored value is OFF.
 */
internal fun AssistantService.commandModeFor(persona: Persona): CommandToolMode =
    settingsManager.getPersonaCommandMode(persona.name) ?: settingsManager.getCommandMode()

/**
 * The working directory commands run in, or null when commands are unavailable for this persona.
 *
 * It is the **same folder the filesystem tools use**, resolved independently of their mode so a
 * user who wants a terminal but not file reads can have exactly that. The folder is validated the
 * same way (`workspaceRootRefusal`): a directory that exists and is not one of the always-excluded
 * paths. It is a starting directory, not a jail — see `CommandRunner.kt`'s header.
 */
internal fun AssistantService.commandWorkspaceFor(persona: Persona): Path? {
    if (!commandModeFor(persona).allowsCommands) return null
    val raw = settingsManager.getPersonaFsWorkspace(persona.name)
        ?: settingsManager.getFsWorkspace()
        ?: return null
    val root = runCatching { Path.of(raw) }.getOrNull() ?: return null
    if (!root.isAbsolute) return null
    if (workspaceRootRefusal(root, fsExcludedPaths()) != null) return null
    return root
}

/** The settings screen's global switch. A mode change is a change of what is trusted. */
internal fun AssistantService.setCommandModeSetting(mode: CommandToolMode) {
    settingsManager.saveCommandMode(mode)
    commandGate.forgetTrust()
    logger.i("Commands", "terminal commands are now ${mode.name}")
}

/** The persona editor's override. Null means "follow the global". */
internal fun AssistantService.setPersonaCommandModeSetting(personaName: String, mode: CommandToolMode?) {
    settingsManager.savePersonaCommandMode(personaName, mode)
    commandGate.forgetTrust()
}

/** Settings, workspace and persona changes all invalidate a session grant; this is the one call. */
internal fun AssistantService.forgetCommandTrust() {
    commandGate.forgetTrust()
}

// ---------------------------------------------------------------- the executor

/**
 * One turn's command tool: parse the call, ask, run, frame, audit.
 *
 * The per-turn byte bound is [MAX_TURN_RESULT_BYTES], the same one the filesystem executor uses and
 * for the same reason: everything a command prints becomes tokens in the next request, and a turn
 * that ran `yes` for a minute must not be able to put a megabyte of it into the model's context.
 * It is counted here rather than in the loop because only this executor knows what a command's
 * output weighed after framing.
 */
internal class CommandToolExecutor(
    private val runner: CommandRunner,
    private val workingDirectory: Path,
    private val personaName: String,
    private val approve: suspend (command: String, stdin: String?, tty: Boolean, workingDirectory: Path) -> CommandDecision,
    private val audit: (CommandAuditEntry) -> Unit,
) : ToolCallExecutor {

    /** One turn's worth, in UTF-8 bytes of framed result text. */
    private var spent = 0L

    override suspend fun execute(call: FsToolCall): FsToolResult {
        val args = toolArgumentsOf(call)
        val command = args.optString("command").trim()
        // M16.1: the optional input. Blank means none, so a model that emits `"stdin": ""` is not
        // shown to the user as an input block that does nothing.
        val stdin = args.optString("stdin").takeIf { it.isNotEmpty() }
        val tty = args.optBoolean("tty", false)
        val timeoutSeconds = resolveCommandTimeoutSeconds(
            if (args.has("timeout_seconds")) args.optInt("timeout_seconds", 0) else null
        )
        if (command.isEmpty()) {
            return FsToolResult(call.id, call.name, "The command was empty, so nothing ran.", ok = false)
        }
        if (stdin != null && stdin.length > COMMAND_MAX_STDIN_CHARS) {
            // Refused, not truncated: the user is asked to approve the exact input, and an approval
            // of a shortened script is an approval of something that will not be what runs.
            return FsToolResult(
                call.id, call.name,
                "The stdin text is ${stdin.length} characters; the limit is $COMMAND_MAX_STDIN_CHARS. " +
                    "Write the script to a file with write_file and run the file instead.",
                ok = false,
            )
        }
        if (spent > MAX_TURN_RESULT_BYTES) {
            return FsToolResult(
                call.id, call.name,
                "This turn has already run as much as it may; ask for something narrower.",
                ok = false,
            )
        }

        val decision = approve(command, stdin, tty, workingDirectory)
        if (decision == CommandDecision.DENIED || decision == CommandDecision.TIMEOUT) {
            audit(
                CommandAuditEntry(
                    timestamp = System.currentTimeMillis(),
                    persona = personaName,
                    command = command,
                    root = workingDirectory.toString(),
                    stdinChars = stdin?.length ?: 0,
                    tty = tty,
                    exitCode = null,
                    timedOut = false,
                    truncated = false,
                    durationMs = 0,
                    result = if (decision == CommandDecision.TIMEOUT) "timeout" else "denied",
                    confirmed = if (decision == CommandDecision.TIMEOUT) "timeout" else "denied",
                )
            )
            val text = if (decision == CommandDecision.TIMEOUT) {
                "The user did not answer the confirmation in time, so the command did not run."
            } else {
                "The user refused to run this command. It did not run."
            }
            return FsToolResult(call.id, call.name, text, ok = false)
        }

        val started = System.currentTimeMillis()
        val run = try {
            runner.run(CommandRunRequest(command, workingDirectory, timeoutSeconds, stdin = stdin, tty = tty))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val text = "The command could not be run: ${error.message ?: error::class.simpleName}."
            audit(
                CommandAuditEntry(
                    timestamp = System.currentTimeMillis(),
                    persona = personaName,
                    command = command,
                    root = workingDirectory.toString(),
                    stdinChars = stdin?.length ?: 0,
                    tty = tty,
                    exitCode = null,
                    timedOut = false,
                    truncated = false,
                    durationMs = System.currentTimeMillis() - started,
                    result = "error",
                    confirmed = if (decision == CommandDecision.SESSION) "session-trust" else "user",
                )
            )
            return FsToolResult(call.id, call.name, text, ok = false)
        }

        // A PTY's output is screen-control instructions; the model gets the text a human would
        // have read. Cleaning happens before the cap so the bytes kept are the useful ones.
        val rawOutput = if (tty) sanitizeTtyOutput(run.output) else run.output
        val (cappedOutput, cappedHere) = truncateCommandOutput(rawOutput)
        val finalRun = CommandRunResult(
            exitCode = run.exitCode,
            output = cappedOutput,
            timedOut = run.timedOut,
            truncated = run.truncated || cappedHere,
        )
        val formatted = formatCommandResultText(finalRun, timeoutSeconds)
        spent += formatted.toByteArray(Charsets.UTF_8).size

        audit(
            CommandAuditEntry(
                timestamp = System.currentTimeMillis(),
                persona = personaName,
                command = command,
                root = workingDirectory.toString(),
                stdinChars = stdin?.length ?: 0,
                tty = tty,
                exitCode = finalRun.exitCode,
                timedOut = finalRun.timedOut,
                truncated = finalRun.truncated,
                durationMs = System.currentTimeMillis() - started,
                result = when {
                    finalRun.timedOut -> "timeout"
                    finalRun.exitCode == 0 -> "ok"
                    else -> "error"
                },
                confirmed = if (decision == CommandDecision.SESSION) "session-trust" else "user",
            )
        )
        return FsToolResult(
            call.id,
            call.name,
            formatted,
            ok = !finalRun.timedOut && finalRun.exitCode == 0,
        )
    }
}
