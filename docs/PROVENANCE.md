# Provenance

Where every file that came from the upstream Android app came from, and what was done to it
on the way across.

Two things depend on this file being accurate:

- **The port must stay reviewable.** Files marked **COPY** in PLAN.md §7 are byte-identical to
  upstream forever. When upstream fixes something in one of them, the fix can be copied in and
  reviewed as a small diff rather than as "what did the port change here?". That only works if
  the file really is unmodified and the commit it came from is written down.
- **A COPY file that has to change is a deliberate demotion**, not an edit. See *Demotions*
  below.

## The sync

Everything here is copied by `tools/sync-from-android.sh`, which is the only sanctioned way to
move a file out of upstream (AGENTS.md §1). It reads from upstream and never writes to it.

| | |
|---|---|
| Upstream | `../VoiceAssistant` |
| Pinned at | `fe584ad01c39f996d692ac5547611c040dc44eb3` |
| Synced | 2026-10-15 (M0.2) |

The COPY list is the COPY column of PLAN.md §7, and it lives in the script. If a file is not in
that list, it does not get copied; `--check` will not miss it because it never knew about it.

## Source files — PLAN.md §7, verdict COPY

Destination for all of them: `core/src/main/kotlin/com/nikosm/voiceassistant/`.
Upstream: `app/src/main/java/com/nikosm/voiceassistant/` — same package `com.nikosm.voiceassistant`,
same file name, on purpose (PLAN.md §3, D3).

| File | Lines | Upstream commit | Notes |
|---|---:|---|---|
| `ModelCatalog.kt` | 708 | `fe584ad0` | pure |
| `AssistantServiceRoomTurns.kt` | 671 | `fe584ad0` | pure |
| `AssistantServiceSpontaneous.kt` | 648 | `fe584ad0` | pure |
| `SpontaneousScheduler.kt` | 434 | `fe584ad0` | pure — the reason M4's scheduling is nearly free |
| `AssistantServiceImageGen.kt` | 370 | `fe584ad0` | pure |
| `ComfyJobs.kt` | 343 | `fe584ad0` | pure |
| `AssistantServiceComfy.kt` | 317 | `fe584ad0` | pure |
| `AssistantServiceRooms.kt` | 284 | `fe584ad0` | pure |
| `ComfyWorkflow.kt` | 260 | `fe584ad0` | pure — token substitution semantics must not drift |
| `MessageTimestamps.kt` | 162 | `fe584ad0` | pure |
| `ServerIdentity.kt` | 139 | `fe584ad0` | pure |
| `TurnSink.kt` | 98 | `fe584ad0` | pure |
| **Total** | **4,434** | | matches PLAN.md §7's 4,434 |

## Other byte-identical files

| File | Upstream | Upstream commit | Notes |
|---|---|---|---|
| `LICENSE` | `LICENSE` | `fe584ad0` | GPL-3.0, verbatim. This port is a derivative work, so it is GPL-3.0 too (README.md). |
| `assets/silero_vad.onnx` | `app/src/main/assets/silero_vad.onnx` | `fe584ad0` | 2,327,524 bytes, sha256 `2623a2953f6ff3d2c1e61740c6cdb7168133479b267dfef114a4a3cc5bdd788f`. MIT. Seam 4 runs this exact model file — the same one the phone uses — so a VAD decision here means what it means there. |

## Files deliberately NOT taken

Recorded because their absence is a decision, not an oversight (PLAN.md §5, §7):

- `app/src/main/cpp/` and its CMake build — the JNI shim for the bundled `arm64-v8a/libespeak-ng.so`.
  On Linux eSpeak-NG is a distro package (seam 6).
- `app/src/main/jniLibs/arm64-v8a/libespeak-ng.so` — arm64 only; wrong architecture besides.
- The piper/espeak voice assets (~34 MB) — piper and Kokoro live on the gateway.
- The Android-only affordances in PLAN.md §9 — quick-settings tile, proximity barge-in,
  phone-call guard. Each gets a desktop equivalent or is dropped.

## Demotions

A **COPY** file must never be edited quietly. If one has to change, all three of these happen in
**one commit** (AGENTS.md §3):

1. Move its row in PLAN.md §7 out of the COPY column, to ADAPT or PORT+.
2. Delete it from `COPY_SOURCES` in `tools/sync-from-android.sh`.
3. Add a row below saying what changed and why.

*None yet.*

## Bringing in a file that is not on the list

M0.3 covers this: the COPY files are not self-contained, and the compiler names what is missing.
Each file pulled in that way enters `:core` as an ADAPT file, gets a row here, and gets an entry
in the M0.3 commit body saying why the smallest satisfying type was not enough.

## Checking for drift

```sh
tools/sync-from-android.sh --check   # exit 1 and a diff if any COPY file has moved
tools/check-upstream.sh              # planned: what changed upstream since the pin above
```
