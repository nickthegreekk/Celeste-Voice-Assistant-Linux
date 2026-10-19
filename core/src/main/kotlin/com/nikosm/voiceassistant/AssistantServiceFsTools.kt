package com.nikosm.voiceassistant

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.nio.file.Path

/**
 * M8 on the service: which workspace a persona has, the loop that lets a model use it, and the one
 * piece of state a file write needs that nothing else in the app has — a question waiting for an
 * answer.
 *
 * Everything that could be pure is elsewhere: the containment rules in `FsWorkspace.kt`, the wire
 * formats in `FsToolProtocol.kt`, the operations and their caps in `FsToolExecutor.kt`. This file is
 * only what needs the live service.
 */

// ---------------------------------------------------------------------------- the workspace

/**
 * What this persona actually has: its own settings where set, the global ones otherwise
 * (`resolveFsWorkspace`), and nothing at all when the result is off.
 *
 * The excluded list is rebuilt per call rather than cached, because it is two `toRealPath`s and
 * because a cache of "which paths may not be read" is exactly the kind of value that goes stale in
 * the direction of allowing something.
 */
internal fun AssistantService.fsWorkspaceFor(persona: Persona): FsWorkspace? {
    val workspace = resolveFsWorkspace(
        personaMode = settingsManager.getPersonaFsMode(persona.name),
        personaPath = settingsManager.getPersonaFsWorkspace(persona.name),
        globalMode = settingsManager.getFsMode(),
        globalPath = settingsManager.getFsWorkspace(),
    ) ?: return null
    // A root that is itself excluded is refused where the user picked it (the settings screen), and
    // again here: the screen cannot be the only door, because the store can be edited by hand.
    if (workspaceRootRefusal(workspace.root, fsExcludedPaths()) != null) return null
    return workspace
}

/**
 * The settings screen's four calls. Thin on purpose: the rules live in `FsWorkspace.kt`, the storage
 * in `SettingsManager.kt`, and this is only the seam between them and a button.
 */
internal fun AssistantService.fsModeSetting(): FsToolMode = settingsManager.getFsMode()

internal fun AssistantService.fsWorkspaceSetting(): String? = settingsManager.getFsWorkspace()

internal fun AssistantService.setFsModeSetting(mode: FsToolMode) {
    settingsManager.saveFsMode(mode)
    // A mode change is a change of what is trusted, so the session's grants go with it: leaving a
    // folder trusted after the user has just reached for the switch would be answering a question
    // they have not been asked yet.
    forgetFsWriteTrust()
    logger.i("FsTools", "filesystem mode is now ${mode.name}")
}

internal fun AssistantService.setFsWorkspaceSetting(path: String?) {
    settingsManager.saveFsWorkspace(path)
    forgetFsWriteTrust()
    // M16: the commands' working directory is the same path, so a session grant was made about an
    // arrangement that just changed.
    forgetCommandTrust()
    logger.i("FsTools", if (path.isNullOrBlank()) "workspace cleared" else "workspace set")
}

/** The persona editor's two writes. Null mode means "follow the global", which is the default. */
internal fun AssistantService.setPersonaFsModeSetting(personaName: String, mode: FsToolMode?) {
    settingsManager.savePersonaFsMode(personaName, mode)
    forgetFsWriteTrust()
}

internal fun AssistantService.setPersonaFsWorkspaceSetting(personaName: String, path: String?) {
    settingsManager.savePersonaFsWorkspace(personaName, path)
    forgetFsWriteTrust()
    // M16: same reason as the global folder above.
    forgetCommandTrust()
}

/** Why this folder cannot be the workspace, or null when it can — asked where the user picks it. */
internal fun AssistantService.fsWorkspaceRefusalFor(path: Path): String? =
    workspaceRootRefusal(path, fsExcludedPaths())

internal fun AssistantService.fsExcludedPaths(): List<Path> =
    alwaysExcludedPaths(appEnv.configDir, appEnv.homeDir, appEnv.stateDir)

/** Every tool call, recorded where the user can read it afterwards. */
internal fun AssistantService.auditFsToolCall(entry: FsToolAuditEntry) {
    logger.i("FsTools", "${entry.operation} ${entry.relative} -> ${entry.result} (${entry.bytes} bytes, ${entry.confirmed})")
    appendFsToolAudit(appEnv.stateDir, entry, logger)
}

// ---------------------------------------------------------------------------- the route that cannot

/**
 * Whether a turn for this persona is one the app **builds the request for** — a direct Ollama server
 * or a cloud provider — and therefore one that can carry the tools.
 *
 * The third case is the Voice Gateway, which assembles the prompt itself: the app sends it a model,
 * a backend URL and the user's words, and never a system prompt, so there is nowhere to put a tool
 * declaration or a tool contract. Asked through `requestRouting`, which is the same classification
 * Rooms and the history stamps use rather than a second parse that could drift from the router.
 */
internal fun AssistantService.fsToolsReachableFor(persona: Persona): Boolean {
    val routing = persona.requestRouting(ollamaServerNames(), cloudProviderNames())
    return routing.isDirectOllama || routing.routesToCloud
}

/**
 * What the user is told when their persona has a workspace and the turn cannot carry the tools.
 *
 * **Answering anyway is the failure this exists to prevent** (M8's design, in its own words): *"a
 * model asked to summarise a file it cannot read produces a fluent hallucination."* So the turn does
 * not reach the model at all, and the sentence says both why and what to do about it — it is spoken
 * as well as written, because the route it most often fires on is the one where the user is
 * listening rather than reading.
 */
internal const val FS_GATEWAY_REFUSAL =
    "Tools are on for this persona, but this turn goes through the Voice Gateway, which " +
        "assembles the prompt itself — so the tools cannot be offered here. Rather than describe " +
        "a file or a tool result I cannot see, I stopped. Use the main chat, or a persona that talks " +
        "to its model directly."

/**
 * Append the refusal, speak it, and let the caller end the turn.
 *
 * [append] rather than a [TurnSink] because the two callers reach this at different points in their
 * own setup: the text path has resolved its sink by then, and the voice path refuses **before it
 * transcribes anything**, so it has only the transcript itself. Speaking goes through the ordinary
 * playback path, so the persona's own voice mode decides how — exactly as it does for a reply.
 */
internal fun AssistantService.refuseFsToolsOnGatewayRoute(persona: Persona, append: (ChatMessage) -> Unit) {
    append(ChatMessage("assistant", FS_GATEWAY_REFUSAL))
    saveSettings()
    playResponse(persona, deviceText = cleanTextForTts(FS_GATEWAY_REFUSAL))
    logger.i("FsTools", "turn refused: the persona has tools but this route cannot carry them")
}

// ---------------------------------------------------------------------------- the loop

/**
 * What one call is carried out by, whichever source offered it.
 *
 * The round runner only needs this much: the filesystem executor and the MCP router both answer
 * with the same `FsToolResult`, so the loop, its caps and its provider dialects are untouched by
 * where a tool came from.
 */
internal interface ToolCallExecutor {
    suspend fun execute(call: FsToolCall): FsToolResult
}

/**
 * Routes one call to the filesystem executor or to an MCP server (PLAN.md §10/M9).
 *
 * The declared name is the key: MCP tools are `mcp_<server>_<tool>`, the filesystem ones are three
 * fixed names, so the two namespaces cannot collide. A name neither knows is the model inventing
 * one — answered with a sentence, like every other tool failure, rather than a thrown exception
 * that would end the turn.
 */
internal class ToolRouter(
    private val fs: ToolCallExecutor?,
    private val mcpTools: Map<String, McpTool>,
    private val callMcp: suspend (McpTool, JSONObject) -> McpCallOutcome,
    /** M10: the application's own `remember` tool, or null when the persona has memory off. */
    private val remember: (suspend (FsToolCall) -> FsToolResult)? = null,
    /** M16: the terminal tool, or null when commands are off or have no workspace. */
    private val commands: ToolCallExecutor? = null,
) : ToolCallExecutor {

    override suspend fun execute(call: FsToolCall): FsToolResult {
        if (call.name == MEMORY_TOOL) {
            val remember = remember
                ?: return FsToolResult(call.id, call.name, "There is no tool named '${call.name}'.", ok = false)
            return remember(call)
        }
        // M16: the command name is checked before MCP. It is a fixed name with no `mcp_` prefix, so
        // the namespaces cannot collide; the order only makes the refusal for a disabled command
        // name the feature rather than the generic sentence.
        if (call.name == TOOL_RUN_COMMAND) {
            val commands = commands
                ?: return FsToolResult(
                    call.id, call.name,
                    "There is no tool named '${call.name}'.", ok = false,
                )
            return commands.execute(call)
        }
        val tool = mcpTools[call.name]
        if (tool != null) {
            val outcome = callMcp(tool, toolArgumentsOf(call))
            return FsToolResult(call.id, call.name, outcome.text, outcome.ok)
        }
        val fsExecutor = fs
            ?: return FsToolResult(call.id, call.name, "There is no tool named '${call.name}'.", ok = false)
        return fsExecutor.execute(call)
    }
}

/**
 * One text turn with tools, for a persona that has a workspace or MCP servers: ask, and while the
 * model answers with calls, carry them out and ask again — bounded, so a model that keeps calling
 * forever stops with a sentence rather than a hang.
 *
 * Each round is a fresh request through the **unchanged** single-turn function, with this turn's
 * calls and results carried in `TurnContext.toolTurns`. That is why the existing streaming,
 * supersede and placeholder machinery needs no changes: round two is just another turn, and the
 * first-token rule in the stream loop is what keeps a call out of the bubble.
 */
internal suspend fun AssistantService.performOllamaChatWithTools(
    baseUrl: String,
    model: String,
    text: String,
    persona: Persona,
    currentTurnInHistory: Boolean,
    generation: Long,
    attachments: List<Path>,
    imagePath: String?,
    turnContext: TurnContext,
    workspace: FsWorkspace?,
    mcpTools: List<McpTool> = emptyList(),
    memoryEnabled: Boolean = false,
    /** M16: the terminal working directory, or null when the persona has no command access. */
    commandWorkspace: Path? = null,
): Triple<String, String?, ByteArray?> {
    val excluded = fsExcludedPaths()
    var context = turnContext
    var rounds = 0
    var callsMade = 0
    val touched = LinkedHashSet<String>()
    val executor = toolExecutorFor(workspace, excluded, persona, mcpTools, memoryEnabled, commandWorkspace)

    while (true) {
        var captured: JSONObject? = null
        val outcome = performDirectOllamaChat(
            baseUrl = baseUrl,
            model = model,
            text = text,
            persona = persona,
            currentTurnInHistory = currentTurnInHistory,
            generation = generation,
            attachments = attachments,
            imagePath = imagePath,
            turnContext = context,
            toolSink = { captured = it },
        )
        val message = captured ?: return withToolSummary(outcome, callsMade)
        val body = JSONObject().put("message", message)
        val calls = fsToolCallsFrom(FsToolProvider.OLLAMA, body) ?: return withToolSummary(outcome, callsMade)

        // The turn may already be over by the time its answer arrived — Stop, a persona switch, a
        // newer message — and a call that arrived after that must not be carried out. Checked before
        // the refusal, so a stale turn is not told off for something no one is waiting for.
        if (!fsRoundMayRun(turnContext, persona.name, generation)) return outcome

        fsToolRoundRefusal(rounds, callsMade, calls.size)?.let { refusal ->
            notifyUser(refusal)
            return withToolSummary(outcome, callsMade)
        }

        val turn = fsRunToolRound(
            executor = executor,
            provider = FsToolProvider.OLLAMA,
            body = body,
            calls = calls,
            onCall = { touched += it.name },
        )
        rounds++
        callsMade += calls.size
        context = context.copy(toolTurns = context.toolTurns + turn)
    }
}

/**
 * The cloud twin of [performOllamaChatWithTools], and the same shape for the same reason: **the
 * single-turn function is not restructured** — it takes a capture sink, returns a sentinel the
 * wrapper discards, and each round is another ordinary call with this turn's earlier rounds
 * travelling in `TurnContext.toolTurns`.
 *
 * `parseCloudResponse` is never reached for a tool-call body, which is the one thing this had to get
 * right: such a body carries `content: null` and the parser reads that field with `getString`.
 */
internal suspend fun AssistantService.performCloudChatWithTools(
    text: String,
    persona: Persona,
    useDeviceVoice: Boolean,
    startTime: Long,
    currentTurnInHistory: Boolean,
    generation: Long,
    attachments: List<Path>,
    imagePath: String?,
    turnContext: TurnContext,
    workspace: FsWorkspace?,
    mcpTools: List<McpTool> = emptyList(),
    memoryEnabled: Boolean = false,
    /** M16: the terminal working directory, or null when the persona has no command access. */
    commandWorkspace: Path? = null,
): Job {
    val excluded = fsExcludedPaths()
    var context = turnContext
    var rounds = 0
    var callsMade = 0
    val executor = toolExecutorFor(workspace, excluded, persona, mcpTools, memoryEnabled, commandWorkspace)

    return serviceScope.launch {
        // M8: **the loop holds THINKING, not the round** — the rounds pass `ownsThinkingState =
        // false`, so nothing between them drops to IDLE. That is not tidiness: the state collector
        // resumes the VAD on IDLE, so a gap between rounds would put the microphone back on while
        // the next round is still being asked for, and while a write confirmation is on screen.
        // `updateNotification` is the same sentence a single cloud turn announces, for the same
        // reason — from the user's side this is one turn that is thinking.
        recordThinkingOwner(generation)
        _state.value = AssistantState.THINKING
        updateNotification("Thinking (Cloud)...")
        try {
            while (true) {
                // The sink carries the dialect as well as the body: `performCloudChat` already resolved
                // the persona's cloud entry to build the request, so the loop does not look it up a
                // second time and the two cannot disagree about which provider a round is for.
                var provider: FsToolProvider? = null
                var captured: JSONObject? = null
                performCloudChat(
                    text = text,
                    persona = persona,
                    useDeviceVoice = useDeviceVoice,
                    startTime = startTime,
                    currentTurnInHistory = currentTurnInHistory,
                    generation = generation,
                    attachments = attachments,
                    imagePath = imagePath,
                    turnContext = context,
                    toolSink = { from, body -> provider = from; captured = body },
                    ownsThinkingState = false,
                ).join()
                val toolProvider = provider ?: return@launch
                val body = captured ?: return@launch
                val calls = fsToolCallsFrom(toolProvider, body) ?: return@launch
                // The same guard as the Ollama loop's, at the same point and for the same window:
                // a call that arrived after the user moved on is not carried out. It matters more
                // here than there, because the round that captured it returned *before* the currency
                // checks that follow the parse — this is the only thing standing between a superseded
                // turn and a write.
                if (!fsRoundMayRun(turnContext, persona.name, generation)) return@launch
                fsToolRoundRefusal(rounds, callsMade, calls.size)?.let { refusal ->
                    notifyUser(refusal)
                    return@launch
                }
                val turn = fsRunToolRound(executor, toolProvider, body, calls)
                rounds++
                callsMade += calls.size
                context = context.copy(toolTurns = context.toolTurns + turn)
            }
        } finally {
            endThinkingIfOwned(generation)
        }
    }
}

/**
 * One round: carry out the calls and build the messages that report them. Shared by both loops so
 * the executor, the approval gate and the audit cannot be wired differently in one of them.
 */
private suspend fun AssistantService.fsRunToolRound(
    executor: ToolCallExecutor,
    provider: FsToolProvider,
    body: JSONObject,
    calls: List<FsToolCall>,
    onCall: (FsToolCall) -> Unit = {},
): FsToolTurn {
    val results = calls.map { call ->
        onCall(call)
        executor.execute(call)
    }
    return fsToolTurnFor(provider, body, calls, results)
}

/**
 * The executor for one turn — **one, for all of its rounds**, which is what makes its caps mean a
 * turn rather than a round.
 *
 * It used to be built inside [fsRunToolRound], and the byte bound it enforces ("no more bytes than
 * [FsToolExecutor.MAX_TURN_RESULT_BYTES]") reset with every round: a model that asked for one file
 * per round could read eight times the 64 KiB the cap promises, in a turn whose whole purpose is to
 * bound what one turn can put in the next request.
 */
private fun AssistantService.fsExecutorFor(workspace: FsWorkspace, excluded: List<Path>, persona: Persona): FsToolExecutor =
    FsToolExecutor(
        workspace = workspace,
        excluded = excluded,
        personaName = persona.name,
        approveWrite = { request -> requestFsWriteApproval(request, workspace) },
        audit = ::auditFsToolCall,
    )

/**
 * The turn's executor: the filesystem one when there is a workspace, the MCP router when the
 * persona offers servers, and both behind one interface when it offers each. A turn with MCP tools
 * and no workspace gets a router whose filesystem side is null — a file tool the model invents is
 * answered with a sentence, not a crash.
 */
private fun AssistantService.toolExecutorFor(
    workspace: FsWorkspace?,
    excluded: List<Path>,
    persona: Persona,
    mcpTools: List<McpTool>,
    memoryEnabled: Boolean,
    commandWorkspace: Path? = null,
): ToolCallExecutor = ToolRouter(
    fs = workspace?.let { FsToolAdapter(fsExecutorFor(it, excluded, persona)) },
    mcpTools = mcpTools.associateBy { it.declaredName },
    callMcp = { tool, arguments -> runMcpTool(tool, arguments, persona.name) },
    remember = if (memoryEnabled) {
        { call -> rememberTool(call, persona.name) }
    } else {
        null
    },
    // M16: one executor for the whole turn, so its per-turn output budget means a turn rather
    // than a round — the same reason the filesystem executor is built here and not per round.
    commands = commandWorkspace?.let { root ->
        CommandToolExecutor(
            runner = commandRunner,
            workingDirectory = root,
            personaName = persona.name,
            approve = { command, stdin, tty, workingDirectory ->
                requestCommandApproval(persona.name, command, stdin, tty, workingDirectory)
            },
            audit = ::auditCommand,
        )
    },
)

/**
 * `FsToolExecutor.execute` is internal (it is nobody else's business), and an internal method
 * cannot implement an interface member; this adapter is the one place that gap is bridged, in the
 * module and package where the method is visible.
 */
private class FsToolAdapter(private val executor: FsToolExecutor) : ToolCallExecutor {
    override suspend fun execute(call: FsToolCall): FsToolResult = executor.execute(call)
}

/**
 * A turn that only ever called tools has no prose to show, and an **empty bubble is worse than a
 * sentence**: the reply says what was done, in the same shape as any other answer — so it is also
 * spoken, which is what a hands-free user gets instead of silence. Any non-blank answer is left
 * exactly as the model wrote it.
 */
private fun withToolSummary(
    outcome: Triple<String, String?, ByteArray?>,
    callsMade: Int,
): Triple<String, String?, ByteArray?> =
    if (outcome.first.isBlank() && callsMade > 0) {
        Triple(fsToolSummarySentence(callsMade), outcome.second, outcome.third)
    } else {
        outcome
    }

/**
 * The sentence a turn with no prose answers with — **one string, because two loops say it.** A user
 * with both a local and a cloud persona configured should not be able to tell which route produced
 * the reply, and two copies of a sentence are two sentences within one edit.
 */
internal fun fsToolSummarySentence(callsMade: Int): String =
    "I used $callsMade file operation${if (callsMade == 1) "" else "s"} and have nothing to add."

/**
 * Whether a round's calls may still be carried out: the turn that asked for them is still the newest,
 * and — in single chat — still the active persona's. **The content path's own guards, asked where the
 * work happens.**
 *
 * They were missing here, and the window they close is not exotic: it is the one the content path's
 * guards were written for. Stop cancels the *call*, so a turn is safe while its request is in flight
 * — but between the last byte of a response and the executor's first instruction there is no call
 * left to cancel, and a tool round is the only thing in this application that acts on what arrived
 * in that window. Without the check, a model's `write_file` that arrived a moment after the user
 * pressed Stop was carried out anyway, and a fresh request went out to answer it.
 *
 * The two dialects ask it differently for the same reason the apply blocks do: a room turn's speaker
 * is never the active persona, so its round generation is the whole question.
 */
internal fun AssistantService.fsRoundMayRun(turnContext: TurnContext, personaName: String, generation: Long): Boolean =
    isChatRequestCurrent(generation) && (!turnContext.isSingleChat || isChatContextCurrent(generation, personaName))

/**
 * Why this round may not run, or null when it may. **Pure, and tested**, because it is the only
 * thing standing between a model that keeps calling and a user watching a spinner forever.
 */
internal fun fsToolRoundRefusal(rounds: Int, callsMade: Int, callsThisRound: Int): String? = when {
    rounds >= MAX_TOOL_ROUNDS ->
        "I stopped after $rounds rounds of using files without reaching an answer. Ask again with a narrower request."
    callsMade + callsThisRound > MAX_TOOL_CALLS_PER_TURN ->
        "I stopped after $callsMade tool operations in one turn. Ask again with a narrower request."
    else -> null
}

internal const val MAX_TOOL_ROUNDS = 6

// ---------------------------------------------------------------------------- asking the user

/**
 * The one piece of state a write needs: a question, and whoever is going to answer it.
 *
 * The shape is the backup-password handshake's (`backupPasswordDeferred` +
 * `_backupPasswordRequested`), for the reason that one documents: the *request* is session state on
 * the service — so closing and reopening the window renders the same pending question instead of
 * losing it — and the answer arrives from the UI through a `CompletableDeferred` the calling
 * coroutine is suspended on.
 *
 * **Trust is per root, and the service persists it** (2026-10-17; the design said session-only and
 * the user asked for it to survive a restart — docs/DECISIONS.md records the reversal and why it is
 * safe: a backup is an allowlist of fields, and this one is not among them, so a restored backup
 * cannot re-grant write access any more than it can restore the workspace). The set lives here; the
 * *decision* to write it down is the service's, at [AssistantService.requestFsWriteApproval], and
 * [AssistantService.forgetFsWriteTrust] clears both halves when the arrangement changes.
 */
internal class FsWriteApprovalGate {
    private val trustedRoots = java.util.Collections.synchronizedSet(mutableSetOf<Path>())

    private val _pending = MutableStateFlow<FsWriteRequest?>(null)

    /** The question the UI is showing, or null when there is nothing to answer. */
    val pending = _pending.asStateFlow()

    /** Written on the service coroutine, completed from the UI: the volatile is the hand-off. */
    @Volatile
    private var deferred: CompletableDeferred<FsWriteDecision>? = null

    fun trusts(root: Path): Boolean = trustedRoots.contains(root)

    /** The roots trusted now, for the settings screen to show and to withdraw. */
    fun trustedRoots(): Set<Path> = trustedRoots.toSet()

    /**
     * Trust these roots before any question is asked — the service calls this once, from its
     * settings load, so a restart does not ask about a folder the user approved yesterday.
     */
    fun seedTrustedRoots(roots: Collection<Path>) {
        trustedRoots.addAll(roots)
    }

    fun forgetTrust() {
        trustedRoots.clear()
    }

    fun trustRoot(root: Path) {
        trustedRoots.add(root)
    }

    /** The user's answer. Ignored when no question is pending, like every dialog dismissal. */
    fun answer(approve: Boolean, trustRoot: Boolean = false) {
        val waiting = deferred ?: return
        if (approve && trustRoot) {
            _pending.value?.root?.let { trustedRoots.add(it) }
        }
        waiting.complete(
            when {
                !approve -> FsWriteDecision.DENIED
                // "Always allow writes in this folder" is one checkbox, and the audit has a word for
                // it — the same one an already-trusted root gets, because from the user's side those
                // are the same decision: stop asking about this folder.
                trustRoot -> FsWriteDecision.TRUSTED
                else -> FsWriteDecision.USER
            },
        )
    }

    /** A newer turn started, or the app is closing: deny, so a stale dialog can never approve. */
    fun denyPending() {
        deferred?.complete(FsWriteDecision.DENIED)
    }

    /**
     * Ask, and wait. Answered by [answer], refused by [denyPending], and **denied on timeout** — a
     * turn must not be able to hang forever on a question nobody is there to read, which is the
     * ordinary case when the window is closed and a spontaneous turn runs.
     *
     * Returns true when the write may proceed, and a root trusted for this session skips the
     * question entirely.
     */
    suspend fun request(workspace: FsWorkspace, requestBody: FsWriteRequest, timeoutMs: Long = FS_WRITE_APPROVAL_TIMEOUT_MS): FsWriteDecision {
        // No question at all when the root is already trusted for this session, and the answer says
        // so — which is how a write the user never saw a dialog for still explains itself in the
        // audit.
        if (trusts(workspace.root)) return FsWriteDecision.TRUSTED
        val waiting = CompletableDeferred<FsWriteDecision>()
        deferred = waiting
        _pending.value = requestBody
        return try {
            withTimeoutOrNull(timeoutMs) { waiting.await() } ?: FsWriteDecision.TIMEOUT
        } finally {
            if (deferred === waiting) {
                _pending.value = null
                deferred = null
            }
        }
    }
}

internal const val FS_WRITE_APPROVAL_TIMEOUT_MS = 120_000L

/**
 * Ask before a write. When nothing can render the question — no window, `--headless`, a spontaneous
 * turn — the gate's timeout denies it and the user is told through the notifier, which is the one
 * channel that still works with the window closed.
 *
 * **A timeout is not a refusal, and the answer says which it was.** This used to ask
 * `fsWriteGate.pending.value != null` after the call, which the gate clears in its own `finally`
 * before returning — so the announcement was dead code and a question nobody ever saw left no trace
 * beyond an audit line reading `denied`. Carrying the decision back is what makes the difference
 * knowable at all.
 */
internal suspend fun AssistantService.requestFsWriteApproval(request: FsWriteRequest, workspace: FsWorkspace): FsWriteDecision {
    val decision = fsWriteGate.request(workspace, request)
    if (decision == FsWriteDecision.TRUSTED) {
        // **Remembered across restarts** (the user's call, 2026-10-17 — docs/DECISIONS.md). Written
        // only when it changes something, so the ordinary case — a root approved days ago — does not
        // rewrite the settings file on every write.
        rememberTrustedRoot(workspace.root)
    }
    if (decision == FsWriteDecision.TIMEOUT) {
        announceWriteTimedOut(request)
    }
    return decision
}

/** Persist one trusted root, if the store does not already have it. */
private fun AssistantService.rememberTrustedRoot(root: Path) {
    val stored = settingsManager.getTrustedFsRoots()
    val asText = root.toString()
    if (asText in stored) return
    settingsManager.saveTrustedFsRoots(stored + asText)
    logger.i("FsTools", "writes in this workspace will not ask again: ${root.fileName}")
}

/**
 * Writes ask again from now on: the grants go, in memory and on disk.
 *
 * Called by everything that changes what is trusted — the mode, the folder, a persona's pair — because
 * a grant is an answer about one *arrangement*, and changing the arrangement has to re-ask. The
 * settings screen's "Stop trusting" uses it too.
 */
internal fun AssistantService.forgetFsWriteTrust() {
    fsWriteGate.forgetTrust()
    settingsManager.saveTrustedFsRoots(emptyList())
}

/** One card, reused: there is at most one question pending, and it is answered or withdrawn. */
internal const val FS_WRITE_NOTIFICATION_ID = 2

/**
 * Tell the user a write question went unanswered, on the channel that works when nothing can
 * render the dialog.
 *
 * **The seam and not `notifyUser`, which is what the design's own sentence asks for**: a snackbar is
 * a row in a window, and the case this exists for is a window that is not there — `--headless`, the
 * tray, a spontaneous turn. The message is past tense because it fires on the timeout, after the
 * question has already been withdrawn: the write did not happen, and asking the user to open the
 * window to decide would send them to an empty dialog. The path is named so the notice is
 * actionable (retry the request), not just alarming.
 */
internal fun AssistantService.announceWriteTimedOut(request: FsWriteRequest) {
    appEnv.notifier.post(
        id = FS_WRITE_NOTIFICATION_ID,
        title = "A file write was not approved",
        body = "Celeste wanted to write ${request.relativePath}, but no one answered in time — nothing was written.",
    )
}

/**
 * How a write was decided — the audit's `confirmed` column, and the one thing a caller cannot
 * recover from a Boolean.
 *
 * The values are the strings `FsToolAuditEntry` documents. Two of them were never written: a
 * **timeout** was recorded as `denied`, because a refusal and a question nobody answered were
 * indistinguishable to every caller; and **trust** was recorded as `user`, because a written
 * approval that asked nothing looked exactly like one that did.
 *
 * **`TRUSTED` was called `SESSION_TRUST` until 2026-10-17**, when trust became something the service
 * persists rather than a set that dies with the process (the user's call — see docs/DECISIONS.md).
 * The label changed with it: an audit line saying "session-trust" on the first write after a restart
 * would have described a session that had not happened yet.
 */
internal enum class FsWriteDecision(val label: String) {
    /** Allowed once, by the dialog's first button. */
    USER("user"),

    /**
     * Allowed because this root is trusted — approved earlier with the dialog's "always allow", and
     * remembered since, across restarts.
     */
    TRUSTED("trusted"),

    /** Refused: the user said no, or the question was withdrawn when its turn ended. */
    DENIED("denied"),

    /** Nobody answered in time. **Not a refusal** — nobody decided, and the user is told so. */
    TIMEOUT("timeout"),
    ;

    val allows: Boolean get() = this == USER || this == TRUSTED
}
