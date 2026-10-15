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
is 3,372 lines and its port reads 789 removed / 531 added; retyping would have discarded that diff and put
transcription errors into a file only the compiler partly checks. Two constraints make it safe: `PORT_SOURCES`
must stay disjoint from `COPY_SOURCES` (a file that is byte-identical forever must never be seedable), and the
seeded content is a working-tree step, never a commit — the port replaces it before the cluster commit lands, so
`:core` never holds the Android original. Rejected: a second copy path outside the script, which is the door
AGENTS.md §1 exists to close. → PLAN.md §7a, AGENTS.md §1

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
