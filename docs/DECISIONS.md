# Decisions

Newest first. One entry per decision: what was decided, what was rejected, why, and where it lands in
[PLAN.md](../PLAN.md).

**The log and the plan have different jobs.** This file is the history of judgement — it explains why a rule
exists. PLAN.md is the current state — it states the rule, and a decision that changes a rule edits the rule
there. When a new decision changes a rule, edit the rule *and* add the entry here.

**When a decision is yours to make** (AGENTS.md §10): a fork the plan's existing rules already answer, or one
that reverses in a single commit, is the agent's call. Record it here in the same commit and keep going — do
not stop for it. Stop only for the four cases listed in AGENTS.md §10.

---

## 2026-10-17 — Trust persists, and the backup is why that is safe

The write confirmation's "always allow" lasted one session. The design said so in as many words — *"a root can be
trusted for the session only, so a restart returns to asking"* — and gave the reason: *"a value that survives a restart
is a value that survives a backup, and this one must not."* The user asked for the opposite after a morning of it: a
hands-free turn raises the dialog on a window that is behind another one, nobody answers, the question times out to
deny after two minutes, and the file is never written. A refusal nobody saw is indistinguishable from a bug.

**Decided: the grant persists.** The service writes the root into `trusted_fs_roots` when the decision is
`FsWriteDecision.TRUSTED`, seeds the gate from it at startup, and `forgetFsWriteTrust()` clears both halves — which every
setting that changes *what is trusted* calls (the mode, the folder, a persona's pair, and the settings screen's "Stop
trusting").

**The design's objection is answered structurally rather than overruled.** Its worry was a backup carrying a grant to a
machine that never chose it. But `exportBackup` is an **allowlist of fields**, and `trusted_fs_roots` is not one — the
same mechanism that already keeps `files_mode` and `files_workspace` out of a backup, because those are device facts
too. A restored backup can neither restore the workspace nor the grant, and `FsToolSettingsTest` pins both halves so a
future field added to `BackupData` cannot quietly reverse this. The confirmation itself is untouched and remains the
first line of defence; what changed is how long its answer is remembered.

**And the audit's word changed with it.** The value was `session-trust`, which on the first write after a restart would
describe a session that had not happened yet; it is `trusted` now, and the enum's `SESSION_TRUST` went with it. The
history in this file and in PLAN.md §10 still says `session-trust` where it is describing what was fixed on 2026-10-16 —
that is what it was called then.

→ PLAN.md §10 (M8), `AssistantServiceFsTools.kt`, `SettingsManager.kt` (`TRUSTED_FS_ROOTS`), `FsToolScreens.kt`

## 2026-10-17 — The prompt says what the tools are for, not only what they are not

M8's design asked for a system-prompt contract and §1 said where it goes (the `suffix` skills ride in, so the budget
counts it). It was never written, and the first live run measured the cost: `ornith-1.5:9b`, a 9B model whose Ollama
capabilities include `tools`, was asked to write a file and **answered that it had written it** — no tool call was
made, no audit line existed, nothing was created. The app was working exactly as built; the model had been handed a
schema and told nothing about it.

**Decided: `fsToolContractFor(mode)` returns the contract text for a turn that offers tools and `null` for one that
does not**, built from `fsToolNames(mode)` — the same list the declaration is built from, so a read-only persona is
never told about a write tool it cannot have. Both request builders append it to the suffix beside the skills.

Rejected: **relying on the per-tool `description` fields.** They do carry the stance, and they are where a schema-first
model looks — but they are inside the declaration, they are read once, and a small model that is not looking for tools
does not look there at all. The design asked for both channels for that reason.

**And one sentence in it is not the design's.** The design names three: file contents are data, instructions inside
them are never to be followed, and a write happens only when the user asked for it in this conversation. The contract
says a fourth — *"never say you have read, written or changed a file unless a tool call in this conversation did it"* —
because that is precisely what the acceptance run produced, and because it is the failure the whole milestone exists to
remove: an answer about a file that was never read is the same invention as a summary of a file that was never
opened. It is recorded here because it is an addition to the specification rather than a port of it.

→ PLAN.md §10 (M8), `FsToolProtocol.kt` (`fsToolContractFor`), `AssistantServiceChat.kt` (both builders),
`FsToolTurnTest.kt`, `FsToolProtocolTest.kt`

## 2026-10-17 — A spoken turn is a single-chat turn, so it carries the tools

M8's plan says tools are offered on **"single-chat turns only"**, and gives a safety reason for the narrowing: a room
turn has no single user to answer a write confirmation, and an autonomous turn must never write a file. The
implementation put them in `sendTextMessageToServer` and nowhere else, which meant a *voice* turn — this application's
main interface, and the one its acceptance runs on — offered no tools and said nothing about it. A hands-free "read my
notes and summarise them" got a fluent invention, which is the outcome the milestone exists to prevent.

**Decided: the voice route forks to the same loops.** `sendAudioToServer`'s direct-Ollama branch and its cloud branch
both ask `fsWorkspaceFor(persona)` and, when there is a workspace, call `performOllamaChatWithTools` /
`performCloudChatWithTools` with `fsToolMode` in the turn context — exactly the text path's fork, on the same loops.
A voice turn writes into the single-chat transcript and its writes go through the same confirmation, so it is
single-chat by the plan's own test (`TurnContext.isSingleChat`), and the two reasons for the narrowing do not apply to
it at all.

Rejected: **refusing on the voice route** rather than offering the tools there. A refusal is the right answer where the
app *cannot* offer them — the Voice Gateway assembles the prompt server-side, so there is nowhere to declare a tool or
to say what file contents are — and it is the wrong answer where the app merely had not wired them yet. The two are
now distinguishable in the code as well as in the argument: `fsToolsReachableFor` asks the router, and the refusal
fires only when it says the gateway.

**What this does not change:** a write asked for by voice still goes through the confirmation dialog, and with no
window it is denied and announced through the `Notifier` — the design's own answer for a user who is not looking at
the screen. Nothing about the caps, the audit or the exclusion rules is route-dependent.

→ PLAN.md §10 (M8), `AssistantServiceChat.kt` (the two forks, and the two refusal checks),
`AssistantServiceFsTools.kt` (`fsToolsReachableFor`, `refuseFsToolsOnGatewayRoute`, `FS_GATEWAY_REFUSAL`),
`FsToolTurnTest.kt`

## 2026-10-16 — A question that was never answered is not a "no", and one place ends a turn

M8's open list, item 4, was three findings with one cause: **the write gate answered with a `Boolean`.** A refusal
and a question nobody answered are the same `false`, so `requestFsWriteApproval` tried to tell them apart by asking
whether the question was still pending — after the gate had cleared it in its own `finally`, which made the
announcement dead code; the audit wrote `denied` for a timeout, because it had nothing else to write; and
`session-trust` was never written at all, because a write that asked nothing looked exactly like one the user pressed
a button for.

**Decided: `FsWriteDecision` — `USER`, `SESSION_TRUST`, `DENIED`, `TIMEOUT` — with `label` giving the audit's own
strings and `allows` the only thing a caller tests.** The two buttons map to the first two, the timeout to the third
value that is *not* a refusal, and `denyPending()` to `DENIED`.

**The announcement goes to the `Notifier` seam, not `notifyUser`.** The design's sentence names the seam, and the case
it exists for is the one where a snackbar cannot be seen — `--headless`, the tray, a spontaneous turn. `notifyUser` was
what the code did; it is a row in a window, and the window is precisely what is missing.

**`denyPending()` is called from `nextChatRequestSeq()`, not from its three callers.** The review's own phrasing —
*"a superseded turn denies the pending write"* — describes a rule about supersede, and a generation bump **is** the
definition of "that turn is over": Stop, a newer message, a room round taking the floor, a voice turn starting. Three
call sites would have been three chances for a fourth path to forget; the cost is that a counter now has a side effect,
which the function's KDoc says out loud. Rejected: denying in the UI when the dialog is dismissed — the dialog is a
*view* of the gate, and a headless turn never renders one.

→ PLAN.md §10 (M8), `AssistantServiceFsTools.kt`, `FsToolExecutor.kt`, `AssistantService.kt`
(`nextChatRequestSeq`)

## 2026-10-16 — A cap is a number the request is checked against, not a number in a comment

M8's open list, item 3, was four findings that turned out to be one: the caps were arithmetic that
nothing consulted. `MAX_TURN_RESULT_BYTES` was enforced by an executor built *inside* each round, so
the bound reset whenever the model asked again; the read cap was compared against `String.length`
(UTF-16 units) while being named in bytes; and the 64 KiB a turn may read never reached
`resolveHistoryBudget`, so the one number that decides whether a request *fits* ignored the largest
thing in it — a turn that read 64 KiB adds ~16k tokens, and the request went out over the persona's
window and was truncated server-side, silently, which is exactly what that function exists to refuse
out loud.

**Decided: one `FsToolExecutor` per turn** (both loops build it before their `while` and pass it into
each round), **byte counts everywhere** — the cap, the audit's `bytes`, and the `spent` accumulator —
and **`resolveHistoryBudget(contextWindow, reservedOutput, systemPrompt, modelText, extraFixed = "")`**,
where the two tool-aware builders pass `fsToolTurnsText(turnContext.toolTurns)`. Defaulted, because
the room caller lives in a COPY file, exactly as the plan's own constraint requires.

Rejected: **counting the tool output as history.** `budgetedHistory` trims history to fit, and tool
turns must not be trimmed — a result the model is halfway through reading is not something to drop
quietly; it is a fixed part of this turn's request, so it belongs in the same bucket as the system
prompt, and a request that cannot fit should be refused rather than sent short.

**Where the two bounds meet, and why the tests say so.** On a default 8k window the budget refuses a
turn that has read 80 KB before the executor's byte cap can speak — a wider window is needed to test
the cap in isolation, and that is written into the test rather than left as a puzzle for the next
reader. Neither bound is redundant: the budget asks whether the *request* fits, the cap asks how much
one turn may put in it.

**And the read path got a number it did not have.** `Files.readAllBytes` had no bound at all, so the
caps were downstream of an allocation proportional to the file. `MAX_READ_SCAN_BYTES` (8 MiB) is that
bound, and it is deliberately far above anything a person reads: passing it costs the exact line
total in the header and nothing else, which is why the whole of the existing behaviour below it is
unchanged.

→ PLAN.md §10 (M8), `FsToolExecutor.kt`, `AssistantServiceFsTools.kt`, `FsToolProtocol.kt`,
`AssistantServiceChat.kt` (`resolveHistoryBudget`)

## 2026-10-16 — The currency guard belongs where the work happens, not where the text arrives

M8's open list, item 2: a tool round was carried out after Stop, a persona switch or a newer message, because the
currency and persona guards sat on the *content* path — after the tool-call capture and its `continue` — and a
tool-call round never reaches them.

**Decided: one shared `fsRoundMayRun(turnContext, personaName, generation)` asked immediately before a round's calls
are carried out, in both loop wrappers.** It is the content path's own rule, spelled once
(`isChatRequestCurrent && (!isSingleChat || isChatContextCurrent)`) so the two cannot drift.

**The window is worth naming, because "after Stop" sounds like a race and is not one.** Stop cancels the in-flight
`Call`, so a turn is safe while its request is out. But the response can arrive *before* the tap and be read *after*
it — between the last byte and the executor's first instruction there is no call left to cancel, and a tool round is
the only thing in this application that acts on what arrived in that window. For `read_file` that is privacy; for
`write_file` it is a file the user stopped.

Rejected: **putting the check in `performDirectOllamaChat`'s stream loop, beside the per-chunk guard it already
has.** That guard exists to abort a stream mid-flight, and its exception carries the partial that the caller persists
as real history — aborting a stream whose answer is already discarded buys nothing and adds a second place where
"stop reading" is decided. One place deciding *may this round run* is worth more than two deciding when to stop
reading. (The per-chunk guards are unchanged, and still do their own job on the content path.)

Rejected too: **re-checking only in the loop's own exit conditions.** The execution is the thing to prevent, not the
next request — a superseded round that writes a file has already done the damage by the time anyone notices no
third request went out.

→ PLAN.md §10 (M8), `AssistantServiceFsTools.kt` (`fsRoundMayRun`), `FsToolTurnTest.kt`

## 2026-10-16 — A tool turn is the loop, not the round; and an IDLE between rounds is a microphone

M8's review left one defect with two faces at the top of its open list: the cloud tool loop was unreachable (its gate
read a field that is `OFF` at every real call site), and a tool-call round returned a `Triple("", null, null)` sentinel
that fell through to the apply block — an empty bubble in the transcript, persisted, and the user's turn mangled in the
next round's history. The plan said to fix them together. Fixing them raised a third question the plan had answered in
one line — *"the UI stays in `THINKING`"* — which turned out to be the load-bearing one.

**Decided: `performCloudChat` is a *round* and can be told so.** It takes `ownsThinkingState: Boolean = true`; the loop
passes `false`, holds `recordThinkingOwner` + `THINKING` + the notification across all of its rounds, and ends the span
in a `finally` through a new `endThinkingIfOwned(generation)`. A round that called a tool returns **`null`** instead of
an empty triple, so "there is no reply to apply" is a type rather than a convention — and the failure mode the old
sentinel produced cannot be written by accident.

**Why not a flag that just skips the apply block**, which is the smaller edit: a round that skips the apply but still
runs the `finally` lands in `IDLE`, and `IDLE` is not a cosmetic state here — `AssistantService`'s state collector does
`AssistantState.IDLE -> if (_handsFreeMode.value) vadRecorder?.resume()`. So a between-rounds IDLE **reopens the
microphone mid-turn**: the user's room is transcribed while the model is still working, and the utterance can supersede
the loop that is asking for the next round — including while a write confirmation is on screen. Rejected, and the
restored-bug run is what made the size of it visible: with the sentinel put back, the new test fails on
`expected:<THINKING> but was:<IDLE>` before it ever reaches the empty bubble.

**Rejected too: keeping the state in the round and re-entering it in the next one.** Two writes of the same value do
not compose into "no interruption" — `_state` is a `StateFlow` collected on another coroutine, and the collector may
well observe the IDLE. A turn that must not be interrupted cannot be spelled as a fast interruption.

**And the gate fix is one condition, not two.** `if (fsWorkspaceForCloud != null)`, because `fsWorkspaceFor` already
answers the mode question (`resolveFsWorkspace` returns null for `OFF`). The old second clause asked
`turnContext.fsToolMode`, which is the field the fork itself fills in — a gate that reads the value it is about to
write. The general shape is worth keeping: **when a fork's condition mentions the thing the fork sets, it is dead.**

**The test that proves it is a new kind of test for this repository.** `FakeProviderServer` (loopback,
`com.sun.net.httpserver`, no new dependency) answers a real HTTP request with the bytes a provider really sends, so
`fsToolCallsFrom` parses a body the test did not write and the executor runs against a real temporary folder. This is
the fake provider PLAN.md §10 had recorded as missing, and the two defects above are precisely what its absence cost:
both lived in wiring that a unit test would have had to restate rather than exercise. Fixtures under
`core/src/test/…/ServiceFakes.kt` — the fakes `FreshStartNetworkTest` held privately — moved out in the same pass, so
the second test to need them does not fork them.

→ PLAN.md §10 (M8), `AssistantServiceChat.kt` (`ownsThinkingState`, `endThinkingIfOwned`),
`AssistantServiceFsTools.kt` (`performCloudChatWithTools`), `FsToolCloudTurnTest.kt`, `FakeProviderServer.kt`

## 2026-10-16 — The image viewer is a window we size, not a dialog the toolkit sizes

The ported `FullScreenImageViewer` compiled with one argument deleted (`decorFitsSystemWindows`), and
that was recorded as expected-but-unseen: a Compose Desktop `Dialog` takes its size from its content,
so the black surface would have been as large as the picture, by a rule nobody chose. The user's
question — *"it's linux, could we use another mechanism, ditch the android logic"* — is the right one,
and the answer is that the sizing rule is exactly the Android logic worth dropping.

**Decided: a `DialogWindow` with an explicit size** (`rememberDialogState(size = DpSize(1100.dp,
800.dp))`, resizable), the same API family as the application's own window. It is not only taste:
`imagePanBounds` — upstream's tuned clamp, whose pure test arrived byte-identical — measures the
*container*, so a container sized by its own content would have been measuring the picture against
itself and had nothing to clamp against. The gestures stay upstream's; a mouse drives them.

Rejected: **handing the file to the desktop's viewer** (`xdg-open` with an `imv`/`feh`/`eog`
fallback chain, a new seam in `:core` plus a `:platform` implementation). It is the most Linux-native
option and the least code, and it was the tempting one — but it leaves the app for a chat lightbox,
makes a viewer a runtime dependency, and throws away the pan tuning and its test. Rejected too:
**keeping upstream's `Dialog`** and judging it on screen, which is how a platform-decided size becomes
a permanent quirk nobody remembers choosing. Also rejected: sizing the window from the image, which
is a 256x256 generation as often as a photograph, or from the screen, which needs AWT — platform code
in `:core`. → PLAN.md §7, §8, §10

## 2026-10-16 — The milestone names win over the marker numbers, and nine markers move

M1's amendment left fifteen `DEFERRED TO M<n>` markers and wrote each receiving milestone's §10 list
to match them. The lists were faithful; the *numbers* were wrong in two of the three cases, and the
contradiction was only visible when M3 started. `grep 'DEFERRED TO M3'` gave rooms, skills and the
models screen, while §8's parity matrix and the milestones' own names said *"M4 — Rooms, spontaneous,
daemon"* and *"M5 — … skills …"*; the image viewer, the save-to-gallery button and the `--send-text`
share entry were marked M5 while M3 is the milestone called *"Images and files"*.

**Decided: the names and §8 win, and the markers are renumbered** — rooms → M4 (3), skills → M5 (2),
the three image-action gaps → M3. Models stays, having no other claimant. The total is unchanged at
15, and no feature moved milestone: what changed is which milestone's list *claimed* it.

Rejected: **following the markers** because they are the more recent record. Recency is not evidence
when the newer text was written in one pass to match the markers themselves — that is a circle, and
it would have handed M3 roughly 1,900 lines of Compose belonging to two other milestones, plus a
"Images and files" milestone containing no image work. Also rejected: **splitting the difference**
(porting rooms in M3 because it is next) — that is how a milestone stops being a unit of anything.

The cost is a documentation commit and nine renumbered comments; the benefit is that each milestone
now contains what its name and its acceptance criterion describe. → PLAN.md §8, §10

## 2026-10-16 — The resume after a reply drains the backlog, and §6 gains the span a user feels

The acceptance run made the gap plain: §6 budgets "capture start to first chunk" and "utterance end
to WAV", and neither is the moment anyone notices. What is noticed is the pause after a reply — how
long before the microphone is listening again — and nothing measured it.

**Measuring it required fixing it first.** While the VAD is paused nobody reads the capture pipe, so
it fills: the recorder writes until the 64 KB buffer is full (about two seconds of 16 kHz mono s16)
and then blocks. Resuming and reading therefore delivers *old* audio first, which is wrong twice
over — the VAD judges the room by sound from before the reply, and "resume → first chunk" would
report 0 ms for audio that was seconds old. So `CaptureStream` gains `drain()`, implemented over
`InputStream.available()`: it discards exactly what is buffered and not a byte more, which is the
difference between clearing a backlog and eating live speech. `VADAudioRecorder.resume()` sets a flag
and the *loop* drains, because a drain is a read and reads belong to the thread that owns the stream
— draining from Main would be I/O on the UI thread plus a race with the loop's own read.

Rejected: **restarting the capture session** on resume, which is correct and pays 400 ms of liveness
grace every reply; and **leaving the backlog**, which is free and leaves the VAD listening to the
past.

**And the first implementation of it was wrong, in a way only a run could show.** It asked
`available()` before every read — which reads like the careful version and is the wrong one, because
draining unblocks the writer, the writer refills the pipe, and the next question finds work that
arrived *after* the resume. Four resumes measured 33, 96, 146 and 182 ms of drain, growing each time;
against a `yes` process the same loop takes 935 ms where one snapshot takes about one. So the drain
now takes **one** reading and discards exactly that many bytes: the backlog by definition, never a
byte that arrived after the call. Rejected: a size cap or a time cap, which bound the damage without
making the operation mean the right thing. → PLAN.md §5, §10

**The new span is logged with no budget, on purpose.** It reports total, drain and first read, and
§6 gets its number once a run has produced one; a figure written before the measurement would be a
guess inside the file that states acceptance — which is the mistake this week already made once, in
the other direction. → PLAN.md §5, §6

## 2026-10-16 — A failure that names no route is not a diagnosis, and a cancellation is not an error

Two defects from the acceptance run, one of them needing a measurement before it could be called
anything. **The unspoken replies** — three `chunk 0 could not be synthesised` lines, no reason —
turned out to be a *decision* by the gateway, correctly provoked and invisibly reported: the reply
carried a Russian phrase, so the on-device script heuristic classified it as Russian (a rule kept
exactly as upstream wrote it: a translation reply must be spoken in the target language), the gateway
answered `200 application/json` with `{"audio": null, "note": "Russian has no TTS voice available —
text only."}`, and that note was logged at DEBUG — FINE, which `java.util.logging` never prints.

**The phrase came from the transcription, and that is the finding that outlived the fix.** The reply
carried it because the *user's own turn* had been transcribed as Russian: re-sending that turn's
recording to `/transcribe` returns `{"text": " Я тут корректно"}` for 15.5 s of English speech —
identically with and without a `language` field, which the endpoint does not accept. Whisper
substituted a short fluent phrase in a language nobody spoke rather than failing, and the culprit is
the audio it was handed: the recording has no energy at all above 4 kHz — the Bluetooth headset's
HFP/CVSD telephone-band microphone, upsampled to 16 kHz by the capture path. So the chain is not "the
TTS mis-detected the reply" but "the recogniser invented a language, everything downstream obeyed, and
the voice route was the first link that refused to pretend". Nothing in the app can repair that — the
remedy is the microphone, which the settings pickers already allow — but it is why threading the
diagnostics was worth doing: a silent reply three steps from its cause is unattributable.

**So the reason travels, in the caller's words, to the line a user reads.** `synthesizeWithGateway`
returns a `GatewaySpeech` whose `noAudioReason` is non-null exactly when the audio is null;
`gatewayRefusal()` frames it with the route, the engine, the voice and the language, because "gateway
or local engine" is the first thing a reader has to rule out and the language is usually the whole
answer when a gateway declines; and the chunked pipeline's `synthesize` callback returns
`Synthesis.Ready`/`Unavailable` rather than a nullable `Path`, so `PlaybackEnd.Failed` carries the
reason instead of "chunk 0 could not be synthesised" alone. Rejected: logging the reason at its
source and leaving the failure message generic. It is cheaper by one type, and it fails the test that
matters — the message a user reads was the thing that discarded the cause, and a second line beside
it is a line that can be missing, reordered or scrolled past.

**The refusal is logged at WARN, not DEBUG.** The outcome is a reply that is in the transcript and
never reached the speakers; that is what WARN means here, and the DEBUG level it had is why three
turns' worth of answers existed only in a body nobody printed.

**What was deliberately not done: an automatic fallback to the local engine.** espeak-ng speaks the
Russian and Greek that this gateway's Kokoro voices do not, so the substitution would make the reply
audible — in a different voice from the one the user configured for the persona. That is a product
decision rather than a repair, it is not in any milestone's scope line, and the diagnostics are what
make it decidable: the log now says which route declined and why. → PLAN.md §5, §10

**A cancellation is not an error, in every loop that can be cancelled.** Both capture loops caught
`Exception` around their chunk loop, so `VADAudioRecorder.stop()`'s cancellation — delivered at the
paused branch's `delay(200)` — was logged as `Recording error: StandaloneCoroutine was cancelled` at
SEVERE, making every stop look like a fault. `CancellationException` is now caught first and
rethrown, which is also the only value that keeps the coroutine cancelled. The rule generalises and
belongs in the ported code's habits: a `catch (e: Exception)` in a suspendable loop needs the
cancellation case above it, or normal teardown is reported as breakage. → PLAN.md §10

## 2026-10-16 — §6's latency budget gets an instrument, and the marks are the definition

§6 states two spans as M2's to verify and nothing measured either: "capture start to first chunk < 50 ms; utterance
end to WAV on disk < 100 ms". The plan's own last note asks for a measurement or an honest "unmeasured", so the
instrument lands first and the numbers follow from a run.

**Where the marks are is the decision, because a budget's span is only as meaningful as its endpoints.** "Capture
start" is the instant before `capture.open` — the subprocess spawn is inside that call — and not the moment `start()`
was called: the dispatch hop onto `Dispatchers.IO` is real latency, but it is not the capture path, and a budget that
absorbed it would blame `pw-record` for the scheduler. "First chunk" is the first *full* 512-frame chunk in hand,
marked before the amplitude scan and the VAD inference that follow it in the loop, because those are downstream work.
"Utterance end" is the VAD's verdict that speech has stopped, and "on disk" is `WavFile.write` returning — it closes
its stream on every path, so returning is whole. The rest of the round trip is §6's own exclusion ("dominated by the
gateway and the model") and stays unmeasured.

**The comparison lives in the code rather than in the reader's head.** Each line logs "N ms over/under the M ms
budget", so the acceptance run is self-judging and whoever reads the log later needs no copy of §6. §6's bounds are
strict ("< 50 ms"), so a span exactly at its budget is reported as over — by zero. Rejected: treating equality as a
pass, which is a rule the plan does not state and would read as a millisecond of headroom that is not there.

**One line per span, at INFO, always on.** `StderrLogger` is `java.util.logging` at its default level, where DEBUG is
FINE and never printed — "VAD monitoring started" is DEBUG and invisible today — so a measurement logged at DEBUG
would be missing in exactly the run that needs it. Rejected: a settings toggle, which would make the acceptance
evidence depend on a checkbox. A handful of lines per session is not a stream.

**The capture span is reported split — `open` and first `read` — because the two misses have different repairs**, and
there is a specific reason to expect the first: `ProcessAudioCapture.open` waits out a 400 ms liveness grace before it
returns, deliberately, so a recorder that cannot open the device is caught before the VAD loop ever sees a dead
stream, and the fallback to the first enumerated input depends on that. Whether the grace gives way for the 50 ms is
a decision for after the run, not before it; the instrument exists so that decision gets made on a number.

**Extracted as `AudioLatency` rather than two `nanoTime()` pairs in the loop**, for the same reason
`TtsChunkSequencer` was extracted out of the service: `VADAudioRecorder` cannot be constructed in a test, because its
detector is a concrete ONNX wrapper. The arithmetic and the wording are pinned by `AudioLatencyTest` against a fake
clock, and the loop's five call sites are wiring the acceptance run exercises. PLAN.md §11's fake detector, in M7, is
what will make the loop itself testable. → PLAN.md §6, §10

## 2026-10-16 — The startup pricing sync is deleted; a network call the user did not configure

`syncOpenRouterPricing()` fired from `AssistantService.start()` on every launch, throttled to once a
day by a stored timestamp — which on a fresh install is zero, so the first launch always made the
call. It fetches `https://openrouter.ai/api/v1/models`, OpenRouter's whole catalogue, to price models
by provider. AGENTS.md §5 forbids it: "No network call that the user did not configure, except the
build's own dependency resolution." Found by running the app for the first time and watching a
42 KB `settings.json` appear with 465 model prices in it and `last_price_sync_ts` set to that
minute. This is the same reasoning §8 used to drop the update check, applied to a call §8 did not
catch — the update check was audited because it was *named* in §8, and this one was not named
anywhere. The lesson generalises past this call: §5's rule is not enforced by the plan's list of
dropped features, so any future audit should be a grep for hardcoded URLs rather than a reading of
that list.

**There is a second startup call of the same class, and it was fixed the same day.**
`fetchCloudModels` is invoked from the `loadSettings()` tail for every cloud provider with a key or
with `icon == "C"`/"CL", and `DEFAULT_CLOUD_APIS` seeds an `"OpenAI-Compatible"` entry that has an
empty key and `icon == "C"`. So `fetchCloudModels`'s guard lets it through, and from the **second**
launch onward the app calls `http://0.0.0.0:11434/v1/models` — Ollama's port on loopback. Not an
external host, and the entry is in the user's own settings after the first run, so it is closer to
§5's line than `openrouter.ai` was; but it is still a call the user did not make, to an address the
*app* chose. Fixed by replacing the guard on the provider's *kind* with a guard on the user's intent — a key, or a
base URL differing from the seeded template — and by making `updateCloudApi`, the save path that did
not, reach `fetchCloudModels` like its two siblings. The triggers needed no invention: the connection
test already called it, and saving a provider already called it on the custom-list paths. Rejected:
treating loopback as harmless, because a rule with a carve-out stops being greppable and a seeded
`0.0.0.0` means the host is whatever answers there on the installed machine.
`FreshStartNetworkTest` now runs launch → save → launch, since the bug only exists on the second.
→ AGENTS.md §5, PLAN.md §5

**The feature stays; its trigger moved.** `SettingsScreens` already had a manual "sync prices"
button calling `syncOpenRouterPricing(force = true)`, which is where a fetch the user asked for
belongs. What changed is that a run that only wanted to talk to its own Ollama no longer reaches
openrouter.ai first, and the pricing data is only meaningful for a provider the user has configured
a key for anyway. → AGENTS.md §5

## 2026-10-16 — The ported UI lives in `:core`, because the module boundary must not cut upstream's visibility

`:core` has 301 top-level `internal` declarations and the ported screens need 46 of them. They are in
COPY files — `ModelCatalog.kt` 40, `ComfyJobs.kt` 33, `AssistantServiceImageGen.kt` 30,
`ComfyWorkflow.kt` 21 — which may not be edited, and Kotlin does not share `internal` across Gradle
modules. So a module boundary drawn between the service and its UI cuts through upstream's
visibility, and 118 of the UI's first compile errors were exactly that rather than the Android
surface. `:core` gains the Compose UI stack and Coil; `:desktop` keeps the window, the tray,
packaging and `Main.kt`. The invariant is now written into PLAN.md §3 D4. Rejected: `-Xfriend-paths`,
which is not a supported Gradle-Kotlin-plugin feature for plain JVM modules and breaks silently on
an upgrade; and demoting the 46 declarations, which is a mass demotion of byte-identical files. This
also supersedes the earlier same-day decision that put the theme in `:desktop` — the theme moved
back with the rest.

## 2026-10-16 — A shared building block arrives with its first caller, one layer up from the seam rule

The M3–M5 screens are not separable from the M1 ones by file: `ModelScreens.kt` hosts five
general-purpose widgets that `ModelPicker.kt` calls, `RoomScreens.kt` hosts a helper `MainVoiceScreen`
calls, and `SpontaneousScreens.kt` hosts the eligibility verdict that gates the persona editor's
*commit* rather than only its drawing. So M1 takes those leaves — 442 lines across four files, with
each large file recorded as **partially ported in M1** in PLAN §7 and PROVENANCE, because that is a
state worth stating rather than implying. Only whole *features* are deferred, each with a
`DEFERRED TO M<n>` marker at its call site, so the markers are the feature-gap list and no screen has
a hole in it. The receiving milestones' §10 scope lines now name what they receive in the same
commit, so M3–M5 read their own to-do lists. → PLAN.md §5

## 2026-10-15 — The UI dependencies: Coil 3 with no fetcher, and a module for the theme

**Coil.** `io.coil-kt.coil3:coil-compose:3.1.0` — the coordinate and the version the upstream app
pins. It is a KMP artifact and Gradle's variant-aware resolution picks the `-jvm` variants on its
own: the resolved tree is `coil-compose-jvm` → `coil-jvm` → `coil-core-jvm` +
`coil-compose-core-jvm`, so no separate desktop artifact exists or is needed. Checked by resolving
the tree rather than by reading the artifact's documentation.

**No network fetcher, deliberately.** Coil 3 splits the loader from its network stacks, and this
port takes only the local half: every image it displays is a file the application already owns in
`dataDir` or `cacheDir`. `coil-network-okhttp` and kin are absent, verified against the resolved
tree — Coil's subtree brings skiko, coroutines, stdlib and okio and nothing else. Adding a fetcher
would hand the UI the ability to make a network call nothing asked it to make (AGENTS.md §5).

**material3 and the icon set come through the Compose plugin's accessors**, not as coordinates.
CMP versions material3 on its own release line independently of `compose`, and the Android app gets
both from the AndroidX BOM — a coordinate written into the build file would be a guess that drifts
silently, and neither AndroidX artifact has a desktop variant for its version to transfer from.

**The theme lands in `:desktop`.** AGENTS.md §2 decides it — a theme is Compose UI, and `:core`
must not depend on Compose. This cost one build to learn, and needed `sync-from-android.sh` to grow
a `UI_SOURCES` list and an explicit module tag in `PORT_SOURCES`. → PLAN.md D2, docs/PROVENANCE.md

## 2026-10-15 — D2 is measured before the UI is ported, and it holds

The plan assumed Compose comes across with import-level edits, and nothing had exercised that. If it
held, the later milestones were mostly mechanical; if it did not, the cost would land in a milestone
nobody had reached — so it was worth knowing before M2 rather than at M6. `tools/compose-probe.sh`
turns the assumption into an assertion: it writes every `androidx.compose.*` import the UI layer
uses into one file and compiles it against `:desktop`, so an unresolved import is a compile error
and the compiler's list is the answer rather than an inference. Of 203 distinct imports, 194 resolve
unchanged; the nine that do not are two Compose dependencies (material3, material-icons-extended), a
third not yet declared (Coil 3), four Android-only symbols with named replacements, and the app's own
`ui/theme/` package which simply has not been copied. The real work is **12 call sites across 5
files** — `LocalContext` and `LocalConfiguration`. Rejected: porting a representative screen first,
which costs a screen to learn what the import set answers in one compile. → PLAN.md D2

## 2026-10-15 — `AssistantService.cacheDir` stays a `java.io.File`

Two byte-identical COPY files force the type: `AssistantServiceComfy.kt` builds `File(cacheDir, …)` and then
calls `name`, `renameTo` and `delete()` on the result, and `AssistantServiceImageGen.kt` takes a `File` into
`publishGeneratedImage` and stores `imageFile.absolutePath`. A `Path` member makes six lines of files this port
may not edit fail to compile. Rejected: demoting those two files to ADAPT, which would trade two byte-identical
files for one nicer type inside a third — the port is not changing this member so much as declining to change
it, since `Context.cacheDir` returns a `File` and the member has always been one. `AppEnv.cacheDir` remains the
seam's `Path` (AGENTS.md §4) and this is a `File` view of it; the thirteen `:core` call sites that want a `Path`
say `.toPath()`. → PLAN.md §5, docs/PROVENANCE.md

## 2026-10-15 — Seam 10 lands in M1, and a stubbed declaration is the wrong shape for a forced call

`AssistantServiceSpontaneous.kt` is byte-identical and calls `isSpontaneouslyArmed`, `armSpontaneousWork` and
`disarmSpontaneousWork` three times each — so the seam rule ("a seam is introduced with its first caller") puts
seam 10's interface and a `StubScheduler` in M1, not M4. Rejected: borrowing the three declarations with stub
bodies. A borrow whose stub returns a plausible wrong value is not inert — `isSpontaneouslyArmed` answering
`false` forever is exactly the condition that function exists to detect, and answering it wrongly makes
`refreshSpontaneousSchedule` restart a healthy chain's countdown on every service start. A `Stub*` announces
itself in the log and in the grep; a stubbed declaration looks like real code. The generalisation is now in §5.
→ PLAN.md §5 (seam 10), §10

## 2026-10-15 — `AppEnv` carries a seam only when an un-editable caller forces it — and exactly two are forced

`notifier` and `scheduler` are the only seams on `AppEnv`, and they are the same case twice: a byte-identical
COPY file hands over `applicationContext` and nothing else, so whatever those calls need has to be reachable
from the argument's type. Every other seam is a constructor parameter on `AssistantService`. Rejected: a
forwarding property per seam "for symmetry", which would rebuild the god-object `Context` was. → PLAN.md D5

## 2026-10-15 — A stub checkpoint is a set, not a count

One `Stub*` per seam whose callers landed while its implementation belongs to a later milestone, each naming
that milestone. The count went three → four → five as callers landed, which is why a number was the wrong way
to state it: the set is checked by reading the names, and a milestone that adds a caller for a later
implementation adds a stub rather than changing a total. Current: 1, 2, 3 and 6 for M2; 10 for M4.
→ PLAN.md §5

## 2026-10-15 — `Toast` reaches the UI as a `StateFlow<String?>`

Eighteen call sites in the settings and backup paths showed a transient message; Compose has no toast, and §9
substitutes a snackbar, which the UI draws. So what crosses the module boundary is the string plus a
`consume()` — a `StateFlow<String?>` and not a channel, because a consumer must be able to read the current
value without having been subscribed at the instant it was set, which is how everything else in this service
reaches the UI. `LENGTH_SHORT`/`LENGTH_LONG` deliberately does not cross: it is a two-second difference in how
long an OS toast lingers, and a desktop snackbar's dwell is the UI's business. → PLAN.md §9

## 2026-10-15 — A PORT+ file is seeded, not retyped

`tools/sync-from-android.sh --seed <name>` places one PORT+ file as a verbatim starting copy, so the port is
reviewable as an unedited `diff -u` against upstream instead of being retyped from reading. `AssistantService.kt`
is 3,371 lines and its port reads 789 removed / 531 added; retyping would have discarded that diff and put
transcription errors into a file only the compiler partly checks. Two constraints make it safe: `PORT_SOURCES`
must stay disjoint from `COPY_SOURCES` (a file that is byte-identical forever must never be seedable), and the
seeded content is a working-tree step, never a commit — the port replaces it before the cluster commit lands, so
`:core` never holds the Android original. Rejected: a second copy path outside the script, which is the door
AGENTS.md §1 exists to close. → PLAN.md §7a, AGENTS.md §1

## 2026-10-15 — The ported screens live in `:core`, because `internal` is not shared

`:core` holds 301 `internal` top-level declarations and the UI needs 46 of them, in COPY files, so the ported screens
cannot compile from `:desktop`. They move into `:core`, which gains the Compose and Coil dependencies approved for the
UI; `:desktop` keeps the window, the tray, packaging and `Main.kt`. The invariant behind it: the module boundary must
not cut through upstream's visibility, because upstream is a one-module app and a member it declares `internal` has to
stay reachable by the files that used it there. Rejected: `-Xfriend-paths`, an unsupported flag that breaks silently on
a Kotlin upgrade; and demoting the 46 COPY declarations to public, which edits byte-identical files, needs a §7 row and
a PROVENANCE entry each, and would have to be repeated every time a ported file crosses a module line. The `UI_SOURCES`
list and module tag added for the old boundary come back out.

## 2026-10-15 — Three UI dependencies, approved on a measurement

`compose.material3`, `compose.materialIconsExtended` and Coil 3 for `AsyncImage` are declared in `:desktop` — the only
new dependencies the UI needs — pinned in `gradle/libs.versions.toml` at the versions the Android app already uses. A
new dependency is a stop under AGENTS.md §10.2, and what settled it was the probe rather than an argument: without them
81 imports fail, and every one of those is material3 or material icons, so the fix is two declarations rather than a
porting effort. Coil serves local files only, as on Android, so no network artefact is needed. The probe script
(`tools/compose-probe.sh`) stays in the repository: it answers D2 by compiling rather than by reading, and it will
answer again if the UI's import picture changes.

## 2026-10-15 — A grep is evidence of text, not of behaviour

Three times a grep-derived claim went into the plan and the compiler disproved it. Three COPY files call
`android.util.Log` fully qualified and were recorded as pure until they would not compile; `Models.kt`'s Compose use
looked like an annotation and turned out to be the persisted colour schema; and `SpontaneousDeliveryWorker.kt`'s
references "are all comments" until a byte-identical COPY file called into it three times. Greps remain the right tool
for finding things and for the debt ledger, where the text is the artefact. They are not evidence about what compiles
or what a call means, so a claim of that shape is confirmed by building before it is written down as a rule.

## 2026-10-15 — Seam 9 lands real in M1, not as a stub

A byte-identical COPY file calls `cancelSpontaneousNotification` at `AssistantServiceSpontaneous.kt:442`, and
that file cannot be edited, so the symbol must exist — and it must be honest, because a stub that cancels
nothing would prove nothing. Minimised to send plus cancel-by-id; M4 adds the Open action and the click
routing on top rather than replacing it. Rejected: a `StubNotifier`, which would make `Stub*` mean two
different milestones and muddy the one grep that tracks it. → PLAN.md §5 seam 9

## 2026-10-15 — Markers are the phrase at the start of a comment line

`grep` is the checklist, so the marker has to be defined by what the files actually do, not by a format
invented for them: the phrase at the start of a comment line's content, colon optional, `//` or ` *` leader.
Three attempts were needed — a mention counted as a marker, then a KDoc marker was missed entirely, then a
colon the files never used. Rejected: asking writers to avoid mentioning the convention in prose, which is a
rule someone has to remember where a grep needs no memory. → PLAN.md §5

## 2026-10-15 — A deliberate deletion carries a restore marker

`removePersonaShortcut` at `AssistantService.kt:2016` is deleted in M1 because M1 creates no `.desktop` entry
to withdraw, and re-adding two lines in M4 is cheap — unlike re-porting 1,379 lines, which is why the audio
case went the other way. The deletion carries `DEFERRED TO M4:` and a row in §9, so
`grep -rnE '^[[:space:]]*(//|\*)?[[:space:]]*DEFERRED TO M[0-9]' core/` is the whole restore list. Rejected:
defining a no-op so the call survives, which buys nothing for a vacuous feature. → PLAN.md §9, §5

## 2026-10-15 — A PORT+ file that fuses callbacks with policy gives the policy up

Upstream's TTS sequencing lives inside ~350 lines of MediaPlayer callbacks and `serviceScope.launch`,
reachable only through a live service with a real player, so no fake can drive it and the behaviours could not
be tested at all. The policy moves to `TtsChunkSequencer` in `:core` with an injected `AudioPlayback`; the
service keeps state transitions, `voiceDuration` and the generation counter, and nothing else re-implements
the guard. Rejected: keeping upstream's shape, which is untestable — a divergence worth recording as one.
→ PLAN.md §5, §7

## 2026-10-15 — A seam arrives with its first caller, not with its implementation

`AssistantServiceChat.kt`'s flow ends by calling `playResponse` and its finally calls `abandonAssistantFocus`,
so the chat orchestration cannot compile without the audio seams, and `AssistantServiceAudio.kt` therefore
belongs in M1 with its callers even though its implementations are M2's. Same rule later pulled in
`Context.kt`, the VAD files and `EspeakEngine.kt`. Rejected: stripping the voice call sites from a 3,371-line
PORT+ file and restoring them in M2 — two passes over the same file, each a chance to drift from upstream.
→ PLAN.md §5, §7, §10

## 2026-10-15 — The audio engine moves into M1; M2 becomes platform-only

Consequence of the rule above. M1 grows to about 15–20 days and M2 drops to 3–6, mostly replacing `Stub*`
classes; the total is unchanged. → PLAN.md §10

## 2026-10-15 — Seam 2 is a handle, not a suspension

`playFile` and `playPcm` return a `PlaybackHandle` with `stop()`, `positionMs`, `durationMs` and callbacks.
A fake handle makes the chunked sequencing, the cancel-previous guard and the progress arithmetic provable in
M1 with no audio hardware, which a suspending `play()` would not without re-expressing upstream's identity
checks as job cancellation. Four contracts recorded, including that completion must distinguish finished,
failed and stopped — a cancel reporting "finished" would advance the sequence. → PLAN.md §6

## 2026-10-15 — COPY files arrive in groups, and `android.util.Log` gets a shim

Two corrections earned by compiling. The COPY column had been derived by grepping for `import android.*`,
which cannot see a fully-qualified `android.util.Log` call, so three files were misclassified; and six of the
twelve COPY files are extension functions on `AssistantService`, so they cannot compile without it. Files now
carry an arrival group (`m0`/`m1`) and the log calls are answered by a shim rather than by demoting three files
to ADAPT — logging is behaviourally invisible, byte-identity is not. → PLAN.md §7a

## 2026-10-15 — `Models.kt` is COPY, and `:core` takes `ui-graphics`

`androidx.compose.ui.graphics.Color` is part of the *persisted* settings schema, not an annotation. Rejected:
a project-owned colour type, which is a permanent diff against the most upstream-volatile file in the app and
risks silently different persisted values — and importing an Android backup is a stated feature. → PLAN.md §5/§7

## 2026-10-15 — Seam 8 is not in v1

Credentials live in the settings file at mode 0600. AGENTS.md §5 already permitted it, D7 named it as the
fallback, and requiring a Secret Service daemon is worse on a bare X11 session where gnome-keyring may not run.
The condition is restored in writing: the settings UI must say where credentials are stored. → PLAN.md §5a

## 2026-10-15 — Seam 15: the image codec

`javax.imageio` where Android used `BitmapFactory`/`Bitmap.compress`, no new dependency, and the constants
travel with it because JPEG quality 88 and the three dimension limits are behaviour. → PLAN.md §5b

## 2026-10-15 — Tests move through the same sanctioned path

A `TEST_SOURCES` list beside `COPY_SOURCES`, same groups, same `--check` coverage. Rejected: a second copy
path for tests, which is the door AGENTS.md §1 exists to close, and a separate flag with its own accounting.
A test needing retargeting stays off the list and is recorded in `docs/TEST-MAP.md`. → PLAN.md §7a

## 2026-10-15 — Cost is not the constraint

Measured: M0 cost 0.22 USD for 15.5M tokens, about 96% cache-hit input, so the whole port projects to
single-digit dollars. The earlier "stop if M0+M1 exceed 10 USD" rule is retired; the checkpoints exist to catch
scope creep, not spend. Correctness is the constraint — every defect found so far came from compiling and
executing, not from reading the plan. → AGENTS.md §8

## 2026-10-16 — A failed tool probe is never cached

Four seams decide "is the helper installed" by running it once, and caching the answer. Three cache a
*failure* as faithfully as a success, which is how a cold start gets mistaken for a missing package:
`espeak-ng` was judged absent for a session because a socket-activated `spd-say` answered slowly on the
first call, and `by lazy` has no way to be told the difference. The rule is now: **a success is cached
for the life of the process, a failure is not — the next call probes again.** Applied to
`ProcessTextToSpeech` (fixed: was `val … by lazy`); already true of `PwPlayback.detect()` and
`ProcessAudioCapture.detect()`, which assign the null and so re-probe by construction.

`NotifySendNotifier` keeps its `by lazy`, deliberately and not by omission: its probe is
`Files.isExecutable` over `$PATH` — a stat with no cold state behind it, so a `false` is a true answer
about the filesystem rather than a transient one, and the cost of the remaining case (installing
libnotify mid-session) is one dropped transitory notification. The distinction is process-probe versus
filesystem-probe, and it is a distinction rather than an exemption. → AGENTS.md §9.6, PLAN.md §5 seam 6

## 2026-10-16 — Seam 12 is a flag, and a second launch is refused rather than forwarded

The hotkey is a WM binding to `celeste-linux --listen`, per PLAN.md §5's row, so M2 owed the flag and the
docs, not an interface. **There is no `Hotkey.kt` in `:core`**: upstream has no counterpart to implement
against ("None — proximity"), and on this platform the key is the compositor's, so an interface would have
one caller (the CLI) and one implementation (nothing). Rejected: grabbing the key in-process, which is the
XDG GlobalShortcuts portal — still marked optional in the plan, still unbuilt, and the one thing that would
restore what the replacement lost (see below).

`--listen` makes a second launch easy, and two copies are two VAD loops on one microphone, both
transcribing the user and both answering. Rejected: a socket or pidfile handoff that would let the second
launch tell the first to arm — that is the daemon protocol D1 forbids, and M4's tray is the in-process
answer to "arm it while it is running". Chosen: an `fcntl` record lock in `$XDG_RUNTIME_DIR` (fallback
`stateDir`), taken before anything else starts, and a second launch exits 1 naming the pid that holds it.
The kernel drops the lock when the process dies, so a crash leaves nothing stale — which is what a flag
file would have left. Exit code 1 rather than 0 because a launcher reporting success for a no-op is how a
hotkey ends up looking broken.

**What this seam's replacement lost**, in §5's family ("the replacement lost a property the old path had
and the loss shows up as plausible data rather than an error"): the phone's gesture reached a *running*
service, so it could interrupt a reply. A key that starts a process cannot, and the failure is silence —
a keypress that does nothing. The response is the family's too: make it legible. The refusal names the
pid, and `--listen` warns plainly when it armed nothing because no gateway is configured (the service's
own "No server configured" goes through `updateNotification`, a DEBUG line the default threshold hides —
found by running the flag on a machine with `server_bases_v2 = "[]"`, where it armed nothing and said
nothing). → PLAN.md §5 seam 12, §10 M2, §14 Q3; docs/DEVICES.md

## 2026-10-16 — The pinned client is extracted so a trust decision can be tested at all

`createUnsafeOkHttpClient()` was a private member that reached directly into two pieces of service state
(`settingsManager.getTrustedCertificates()` and `_pendingCertApproval`). It is now the top-level `internal`
`buildPinnedClient(trustedCerts, onUntrusted)`. Rejected: leaving it private and testing only its parts — a hostname
or pin decision cannot be observed without a handshake, a handshake needs a client, and constructing
`AssistantService` needs every collaborator faked (PLAN §11 deliberately defers that to M7). Seam 14 had no test
before this and now has three, against a loopback TLS server with a `keytool`-generated certificate. The
`sha256Fingerprint` extension lost its unused `AssistantService` receiver for the same reason: a top-level function
has no `this` to be the receiver. Both files are PORT+ and the changes are recorded in PROVENANCE.md.

The defect it exists for: `.hostnameVerifier(HttpsURLConnection.getDefaultHostnameVerifier())` — upstream's S1 line
— names a self-contained verifier on Android and a JDK one on the JVM that returns **false** for every standalone
caller. Every HTTPS request failed hostname verification; plain HTTP worked, which is why nothing noticed. Removing
the line restores OkHttp's default, which is the verifier Android's call returned, so this preserves S1's intent
rather than relaxing it — `PinnedClientTlsTest`'s third case fails if hostname checking is weakened instead.
→ PLAN.md §5 (the family's fourth member), §10 M2, docs/PROVENANCE.md

## 2026-10-16 — A server's refusal carries the server's words, but only its error field

A 400 from the direct-Ollama path logged as `Ollama error: 400` and nothing else, in the same second as an
unrelated SearXNG 400 — two failures that could not be told apart from their own messages. Both bodies said
exactly what was wrong: `{"error":"Multimodal data provided, but model does not support multimodal requests."}`
and `{"error":"No query"}`. `serverFailure(prefix, response)` now appends the server's reason, truncated to
200 characters and flattened to one line, at the Ollama, Anthropic, Gemini, OpenAI and gateway call sites and
in the SearXNG path.

**Only the error field is read — never the raw body.** The suggestion paths in AssistantServiceChat.kt already
carry an S4 note saying why: a server's raw error body can echo the user's conversation back, and that is why
they log it at DEBUG only. This message is not DEBUG-gated; the user sees it *and* journald keeps it. So
`serverErrorReason` walks `error` → `message` → `detail`, unwraps a value that is itself JSON (Ollama nests
`{"error":{"code":400,"message":…}}` inside a string), and returns **null** for anything else — an HTML error
page contributes nothing and the message stays status-only. `ServerErrorReasonTest` asserts both directions.

Also fixed on the same path: an empty query is no longer sent to SearXNG. Measured on the development
instance, a blank `q` is the only thing that produces a 400 (`{"error":"No query"}`) — every populated query
answers 200 whatever the format, including json — and a blank query only arrives from a turn with no text.
The guard turns a pointless request and an alarming log line into nothing at all. → PLAN.md §5 seam rules

## 2026-10-16 — Source and sink selection: two different scopes, and a preference falls back

M2's last code item. The sink is **per persona** (`persona_output_device_<name>` in the settings blob, NOT a
`Persona` field: `Models.kt` is a COPY file and adding one would demote it, which is a stop-and-ask — the port already
has this pattern for name-keyed per-persona state, and the entry gets the same rename/delete/import lifecycle as the
message history beside it; it is device-local and does not travel in a backup) and the microphone is **global**
(`audio_input_device` in settings, Settings → Audio), because the plan's substitution is per-persona for the output
(§9 replaced the earpiece route toggle, which the phone used to say "this conversation is private") while there is
only one room being listened to and one microphone in it. Rejected: per-persona microphones (a persona cannot be
listening on a different device from the one the VAD loop opened — the loop is one stream) and a global sink (that
is what the toggle was, and it is the thing the plan specifically replaced).

**A stored device that is gone falls back to the default and logs it** — `pickDevice` returns null either way and
`sinkForPersona`/`inputDeviceForCapture` are where the two cases get told apart in words. This is deliberately weaker
than `AudioCapture.open`'s rule, which *refuses* to substitute a device the caller named: that rule protects a
per-turn choice from silently listening to the wrong room, while a stored preference is a claim made once, days ago,
and a persona whose headphones are unplugged should still be able to speak. Changing the microphone takes effect on
the **next** capture session, not on a running one — swapping the device under an open stream is how one utterance
ends up half on one microphone and half on another — and the picker says so.

Two things found while wiring it, both recorded rather than fixed silently:

- **Push-to-talk opened its capture with `playbackTarget()`** — a port artefact, not upstream behaviour (upstream's
  `MediaRecorder` named no device at all and relied on Android's process-wide route). Harmless while `earpiece()`
  answered null on every desktop, and it would have handed `pw-record` a *sink* name the moment a persona could choose
  one. Both capture paths now use `inputDeviceForCapture()`.
- **The "Phone mode" menu item is left in place, inert.** On this platform `AudioDevices.earpiece()` returns null by
  decision, so the toggle has done nothing since M2 began, and now the per-persona sink outranks it. Removing a visible
  affordance is a user-facing change and is not this milestone's call — flagged for the user instead.

`AudioDevicePickerRow` (its own NEW file — `UIComponents.kt` is a COPY file too, so new UI cannot arrive inside it)
is the one picker both screens use, and it takes the device list as a
parameter: `outputs()`/`inputs()` shell out to `pactl`/`wpctl` with a 3s bound, so each screen fetches once, off the
main thread, when it opens. → PLAN.md §9, §10 M2; docs/DEVICES.md

## 2026-10-16 — The Arch package builds the tree it lives in, installs under `/usr/lib`, and makes the ONNX cut itself

M6's first packaging artefact is `packaging/arch/PKGBUILD`, and the plan left three forks inside it open. All three were
decided here rather than asked about, because each reverses in one commit.

**The source is the tree, because there is no release to point at.** `source=()` is empty and `build()` runs Gradle in
`$startdir/../..`. Rejected: a tarball beside the PKGBUILD with `sha256sums=('SKIP')` — the same non-hermetic build wearing a
checksum it cannot honour — and a URL invented for the occasion. The cost is named in the file and shows up in namcap's output
("File referenced in $startdir", five times, for the files this PKGBUILD reads from its own directory and the tree above it).
It disappears the day there is a release: `source=("$pkgname-$pkgver.tar.gz::https://…")` and a real hash, nothing else.

**`/usr/lib/celeste-linux`, not `/opt`.** The self-contained-bundle convention for a jpackage app image is `/opt`, and namcap
reports it as an error — "ELF files outside of a valid path" — which is Arch's own guidance speaking, since `/opt` is for what
has nowhere else to be. The prefix is the package's choice because the app image is relocatable, and that was verified rather
than assumed: copied into a fake prefix under `/tmp` and launched **through a `/usr/bin` symlink**, it printed the resolved XDG
paths, the audio devices and the upstream commit.

**The ONNX cut belongs to the packaging, and it has to survive being run twice.** Two facts came out of doing it (the second
run of `makepkg` is what found the second one), both recorded with the file:

- The four `zip -d` patterns take the jar from 90 MB to **5.7 MB**, measured — the `linux-x64` library this package loads is
  15.5 MB of the original, and what goes is 298.7 MB *uncompressed* of Windows debug symbols plus the macOS and
  linux-aarch64 natives.
- **`zip -d` exits 12 when a pattern matches nothing**, which is exactly what a second build of an already-trimmed image is.
  So the cut is guarded by a listing test and a `found` flag: an already-trimmed image is left alone, and a renamed or missing
  jar stops the build instead of shipping every platform's natives in silence.

`check()` is what makes the cut verifiable at all: `:core`'s tests link the *untrimmed* Gradle dependency, so no test in the
Gradle build can see it. The check compiles `OnnxSmokeTest.java` against the image's own jars and creates a session over
`assets/silero_vad.onnx`, which is the call `VADDetector` makes. It deliberately does not use the `java` on `PATH` — Arch's
default is JDK 17 on this machine, which cannot compile for or run the JVM-25 classes in the image — and takes the newest JDK
under `/usr/lib/jvm` that has a compiler instead. That is `desktop/build.gradle.kts`'s `javaHome` pin seen from the other side,
and it is the second place in the project that has to know which JDK is complete.

**Two namcap errors are left standing, named in the file rather than argued with.** *Dependency java-runtime … not included*:
adding it would put a second JDK on the machine for an application that carries its own, which is PLAN.md §2.6's point, and the
dependency namcap is asking for is inside `lib/runtime/`, where it cannot see it. *Missing url*: there is no published home for
this port or for the upstream app, and a URL invented to silence a linter is worse than an empty field. → PLAN.md §4, §10 M6

## 2026-10-16 — The `.deb`'s build environment, and a guard that asked the wrong question

M6's Debian half. `packaging/debian/` is debhelper packaging built inside a Debian 13 container, and three forks in it were
decided here rather than asked about.

**The source is staged, not the checkout.** `dpkg-buildpackage` wants a source root with `debian/` at it, and PLAN.md §4 puts
those files under `packaging/debian/`, so `build-package.sh` copies the tree — minus `.git`, build output and packaging output
— into a `celeste-linux-<version>/` staging directory, puts `debian/` at its root, and builds there. Rejected: moving `debian/`
to the repository root, which would contradict §4's layout to save a forty-line script.

**`Build-Depends` lists fourteen C libraries in a package that compiles no C.** Not a mistake: `dh_shlibdeps` must find every
library the bundled runtime and Skiko link against, because that is how it decides which package owns each one, and an
uninstalled library is an error rather than a guess — measured, the first container build died at "cannot find library
libX11.so.6 … cannot continue due to the errors listed above". The `-dev` names are used rather than the runtime ones because
they are the stable ones across releases: Debian 13 spells two of the runtimes `libpng16-16t64` and `libasound2t64` after the
64-bit `time_t` transition, and a `Build-Depends` should not have to be renamed along with it.

**The container is the build environment; the source is mounted read-only.** The `.deb` cannot be assembled anywhere but
Debian, because the bundled runtime links the build host's glibc (§12's risk row), so the Containerfile carries the toolchain
— `openjdk-25-jdk-headless`, `debhelper`, `zip` and `unzip`, `build-essential`, the library packages — and the tree arrives as
a volume rather than as a build context, which also keeps `dpkg-buildpackage` from ever writing into the checkout. The
Containerfile restates `control`'s `Build-Depends`, which is duplication with a reason: nothing reads a control file to build
an image, and `dpkg-checkbuilddeps` will refuse to build if any of them is missing.

**The defect that made the whole exercise worth running: the ONNX cut silently did not happen.** `unzip` was missing from the
container, and the guard was a *skip test* — `unzip -l … || continue` — which cannot tell "the tool failed" from "already
trimmed". The second guard tested that the jar existed, and it did. So the `.deb` shipped 93,522,826 bytes of onnxruntime
against the Arch package's 5,959,524, and what found it was comparing the two packages rather than reading either. The fix is
a different *question* rather than a tighter condition: after the cut, **exactly one platform directory must remain in the
jar**, and both packagings now assert that — the PKGBUILD too, since its loop had the same shape and the same hole in
principle. The general form is worth keeping: when a step is conditional, assert its post-condition, because the condition
itself can be wrong in ways the step never notices.

→ PLAN.md §4, §10/M6; `packaging/debian/`

## 2026-10-16 — The window's name, the Desktop copy, and two square frames

Five findings from the user's first day running the installed Arch package. Four are code; all five are decisions about
things the plan left open, so they are recorded here rather than in a commit message only.

**The window claims `celeste-linux` as its X11 `WM_CLASS`, by reflection and with an `--add-opens`.** A dock draws a running
application's icon by matching the window's `WM_CLASS` against the entries that claim it; AWT had named ours after the
bottom frame of the stack that constructed the toolkit — `java-lang-Thread`, measured — so no match was possible and the
taskbar had a generic icon while the menu entry beside it had the right one. The three ways to fix it, and why this one:

- `-Dsun.awt.X11.XWMClassName=celeste-linux` — **rejected because it does not exist.** It is a JetBrains Runtime feature;
  the JDK's own `java.desktop` X11 sources do not mention it. A first version of the fix set this property, which is why
  the sources were read at all.
- `StartupWMClass=java-lang-thread` in the `.desktop` files — rejected as a measurement written down as an assumption: it
  depends on how Compose happens to start AWT, and it would break silently the day that changes, with a name in our own file
  that nobody could explain.
- Setting `sun.awt.X11.XToolkit.awtAppClassName` reflectively — chosen. The field is what the toolkit actually reads, it is
  read at *window creation* rather than at toolkit construction, so the toolkit is forced into existence first and the field
  set afterwards. It needs `--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED`, declared in `desktop/build.gradle.kts` beside
  the native-access flag; the failure mode without it is a logged warning and AWT's own name, which is where this started.

`Window(icon = …)` covers the other half — the `_NET_WM_ICON` a taskbar draws for a window it *cannot* match — from the same
PNG the packages install, wired onto the resource path from `packaging/icons` so there is still one icon file.

**A persona shortcut is written twice: the menu, and the Desktop.** The user's words were "shortcut to persona on Desktop
can't be created" — and the file *was* being created, in `$XDG_DATA_HOME/applications`, which is a directory they do not
look in. So the entry now also lands on the Desktop, with two differences that are forced rather than chosen: it is
**executable** (a `.desktop` file in the menu is read by machinery that ignores the mode, while one on a Desktop is a
launcher the file manager offers to run, and every desktop requires the bit for that), and it is written **only if the
Desktop directory already exists** — a machine with no `~/Desktop` is a machine whose owner keeps no shortcuts there, and
creating one to hold a single file would be this function deciding something that is not its to decide. Removal takes both
copies, because a shortcut that outlives its persona opens whichever one happens to be selected. The file keeps the
`celeste-linux-` prefix so it can never collide with something the user put there themselves; the name they *see* comes from
the entry's `Name=` key either way.

**Two frames are square, deliberately departing from upstream's Material shapes.** The persona selector's
`ModalBottomSheet` and the header's persona-name frame both carried rounded corners copied from a phone UI. The user asked
for square on both, and the second had a defect in it rather than a taste: the rounded clip cut into the first glyph of a
long persona name, because the name is drawn flush to the frame's edge. Both files are **ADAPT**, so this is an ordinary
edit rather than a COPY demotion, recorded in docs/PROVENANCE.md as the deviation from upstream's appearance that it is.

→ PLAN.md §10/M6; docs/PROVENANCE.md

## 2026-10-16 — M8: native tool calling, the zero-COPY constraint, and a gate that never guesses

The filesystem-tools milestone (PLAN.md §10/M8), ten commits. The forks worth recording, in the order
they were taken.

**Native tool calling on all four providers, not a fenced JSON block in the prompt.** Prompt-based was
cheaper to build — one dialect instead of four — and was rejected for a reason specific to this app:
the model's prose **streams into a chat bubble as it arrives**, so a call written inside the prose would
be rendered before anything could parse it, and the fix for that is buffering every reply. Native also
puts the call in a structural channel rather than in text the user can see, and all four providers
constrain the arguments, so there is no repair pass for a malformed call. The cost was four declaration
shapes and four result shapes, which is what `FsToolProtocol.kt` is.

**Two COPY files constrain the whole design, and the constraint is load-bearing.**
`AssistantServiceRoomTurns.kt` calls `performDirectOllamaChat` and reads its `Triple` return;
`AssistantServiceSpontaneous.kt` calls `resolveHistoryBudget`. So that signature and return type are
frozen, every new parameter is **defaulted** (`toolSink`, `extraFixed`, `TurnContext.toolTurns`,
`TurnContext.fsToolMode`), and **the entire milestone touches no COPY file** — which is its own
acceptance test. The consequence is a real narrowing: **tools are offered in the main chat only**, and
the reason is safety as much as compilation — a room turn has no single user to answer a write
confirmation, and an autonomous turn must never write a file at all.

**Tool turns are never persisted.** The calls and results live in the in-flight request only; the
transcript gets the final reply. Persisting them would re-send a file's contents with every later turn:
a privacy cost on a cloud provider, a token cost on a small local model, and one more chance for a
document to be read as instructions. It also avoids foreign `role`s reaching renderers, TTS, replay,
the 200-message cap and the backup.

**A tool-only turn answers with a sentence.** "I used 2 file operations and have nothing to add." An
empty bubble is worse than a sentence, and the sentence flows through the ordinary apply block — so it
is spoken, which is what a hands-free user gets instead of silence.

**The turn's tool output is capped at 64 KiB**, deliberately far below the per-read cap times the round
cap: that output is *tokens* in the next request, and 256 KiB is roughly 64k of them, which would not
fit beside a conversation on the local models this port is aimed at.

**In containment, the exclusion is asked before the workspace boundary.** A symlink aimed at `~/.ssh`
reports "always excluded", not "outside the workspace" — the more specific refusal is the more useful
sentence, both for the model that reads the result and for whoever reads the audit line. Found by a
test that asserted the wrong reason and was right to.

**A corrupted per-persona mode resolves to `OFF`, never to "follow the global".** The global may be
read-write, so following it would let a corrupted byte *widen* access; the decoder's deny-everything
fallback is the only safe direction, and the asymmetry is commented where it lives.

**The write gate never guesses.** Allow only on the button; **dismissal denies**, an unanswered
confirmation **times out to deny**, and a superseded turn denies the pending write — so a stale dialog
can never approve anything, and a hands-free utterance arriving while the prompt is up fails safe.
Trust is per root, in memory, never persisted: a value that survives a restart is a value that survives
a backup, and this one must not. **Writes were refused outright from commit 7 to commit 9**, because
the gate existed and nothing could answer it — a staged milestone's honest intermediate state rather
than an accident, and the reason the confirmation dialog is in the same milestone as the executor.

→ PLAN.md §10; `FsWorkspace.kt`, `FsToolProtocol.kt`, `FsToolExecutor.kt`, `FsToolAudit.kt`,
`AssistantServiceFsTools.kt`, `FsToolScreens.kt`
