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
