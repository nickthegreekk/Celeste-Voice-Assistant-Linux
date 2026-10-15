# Provenance

Where every file that came from the upstream Android app came from, and what was done to it on the
way across.

Two things depend on this file being accurate:

- **The port must stay reviewable.** Files marked **COPY** in PLAN.md §7 are byte-identical to
  upstream forever. When upstream fixes something in one of them, the fix can be copied in and
  reviewed as a small diff rather than as "what did the port change here?". That only works if the
  file really is unmodified and the commit it came from is written down.
- **A COPY file that has to change is a deliberate demotion**, not an edit. See *Demotions* below.

## The sync

Everything here is copied by `tools/sync-from-android.sh`, which is the only sanctioned way to move
a file out of upstream (AGENTS.md §1). It reads from upstream and never writes to it.

| | |
|---|---|
| Upstream | `../VoiceAssistant` |
| Pinned at | `fe584ad01c39f996d692ac5547611c040dc44eb3` |
| Synced | 2026-10-15 (M0.2, M0.3) |

COPY files arrive in **groups**, one per milestone, because half the COPY list is extension
functions on `AssistantService` and cannot compile before the service is ported (PLAN.md §7a).
`--group m0` copies the six files that stand alone; the bare copy is refused. `--check` reports a
file that has not arrived yet as *not yet taken* rather than as a fault.

## Group m0 — taken

Destination for all of them: `core/src/main/kotlin/com/nikosm/voiceassistant/`.
Upstream: `app/src/main/java/com/nikosm/voiceassistant/` — same package `com.nikosm.voiceassistant`,
same file name, on purpose (PLAN.md §3, D3).

| File | Lines | Upstream commit |
|---|---:|---|
| `Models.kt` | 984 | `fe584ad0` |
| `ModelCatalog.kt` | 708 | `fe584ad0` |
| `SpontaneousScheduler.kt` | 434 | `fe584ad0` |
| `ComfyJobs.kt` | 343 | `fe584ad0` |
| `ComfyWorkflow.kt` | 260 | `fe584ad0` |
| `MessageTimestamps.kt` | 162 | `fe584ad0` |
| `ServerIdentity.kt` | 139 | `fe584ad0` |
| **Total** | **3,030** | |

`Models.kt` is COPY rather than ADAPT because its only Compose dependency turned out to be
`androidx.compose.ui.graphics.Color`, which is part of the *persisted* settings schema
(`object ColorSerializer`, three `@Serializable` fields, ~35 ARGB literals) and not an annotation.
`:core` takes `org.jetbrains.compose.ui:ui-graphics` pinned to the exact version `:desktop`
resolves, so this file stays byte-identical forever (PLAN.md §7a, amended 2026-10-15). That
dependency is the only reason `:core` is not Compose-free, and the note in `core/build.gradle.kts`
says so.

## Group m1 — not yet taken

These six are byte-identical copies too. They are simply not in `core/` yet, because each one is a
set of extension functions on `AssistantService` and cannot compile until `AssistantService.kt`
(3,371 lines, PORT+) is ported at M1.

| File | Lines | Arrives |
|---|---:|---|
| `AssistantServiceRoomTurns.kt` | 671 | M1 |
| `AssistantServiceSpontaneous.kt` | 648 | M1 |
| `AssistantServiceImageGen.kt` | 370 | M1 |
| `AssistantServiceComfy.kt` | 317 | M1 |
| `AssistantServiceRooms.kt` | 284 | M1 |
| `TurnSink.kt` | 98 | M1 |
| **Total** | **2,388** | |

3,030 + 2,388 = 5,418 across thirteen source files, which is PLAN.md §7's 4,434 plus `Models.kt`,
which §7 counted separately as ADAPT.

## Test sources — group m0, taken

Destination: `core/src/test/kotlin/com/nikosm/voiceassistant/`. Upstream:
`app/src/test/java/com/nikosm/voiceassistant/`. Same package, same names, same byte-identity rule as
the main sources — `--check` covers them too.

| File | Lines | Upstream commit | Notes |
|---|---:|---|---|
| `ComfyPromptFillingTest.kt` | 221 | `fe584ad0` | 10 tests |
| `ComfyWorkflowValidationTest.kt` | 114 | `fe584ad0` | 13 tests |
| `ComfyFixtures.kt` | 83 | `fe584ad0` | not a test class — the shared fixture both tests build their captured workflows from, so it travels with them |
| **Total** | **418** | | 23 tests, all green as of M0.4 |

None of the three needed retargeting: JUnit 4 and `kotlinx-serialization-json` only, no Android, no
`org.json`, no `Robolectric`. The two Android workarounds BOOTSTRAP.md M0.4 warned about —
`unitTests.isReturnDefaultValues` and the `org.json` test shim — exist upstream to prop up *other*
tests and arise nowhere here.

**A test that needs retargeting is not a copy.** The `AndroidJUnit4`, `WorkManager`,
`TestWorkerBuilder`, permission and Compose-device tests are adapted on purpose, stay out of
`TEST_SOURCES`, and get a line in `docs/TEST-MAP.md` instead. That is the COPY demotion rule
(AGENTS.md §3) pointed at tests, and it is why this list will never be all 45 upstream test files.

## Adapted files — verdicts ADAPT and PORT+

Files that came from upstream and were changed, rather than copied or written here. PLAN.md §7
gives each one a verdict; this records what actually changed, because "ADAPT" describes an
intention and a reader six months from now needs the diff.

These are not COPY files and never were, so nothing below is a demotion — but a change to a file
in this table that is not written down here is one nobody can review.

| File | Verdict | Upstream | What changed |
|---|---|---|---|
| `SettingsManager.kt` | PORT+ | `app/src/main/java/com/nikosm/voiceassistant/SettingsManager.kt` | Seams 7 and 15; `Context`→`AppEnv`; `Uri`→`Path`; `android.util.Base64`→`java.util.Base64`; `createEncryptedPrefsWithRecovery` removed. Detail below. |
| `AssistantServiceAudio.kt` | PORT+ | `app/src/main/java/com/nikosm/voiceassistant/AssistantServiceAudio.kt` | Landing in stages (PLAN.md §10). So far: `cleanTextForTts`'s astral-emoji class rewritten for the JVM regex engine, and the chunked sequencing extracted to `TtsChunkSequencer.kt`. Detail below. |
| `TtsChunkSequencer.kt` | **NEW** | policy extracted from `AssistantServiceAudio.kt` | Chunk order, the one-at-a-time rule, the cancel-previous guard and the progress arithmetic — lifted out of upstream's `playChunkedTtsGateway`/`startChunkPlayback` so they can be tested at all. Detail below. |

### `AssistantServiceAudio.kt`

The file arrives in pieces because only a third of it is portable on its own; the rest of the
1,379 lines is a `MediaPlayer`/`AudioTrack` state machine and the Android audio-focus machinery
PLAN.md §9 deletes. The order is the pure third first, then the sequencer, then what remains, which
lands with the service it hangs off.

**`cleanTextForTts`'s astral-emoji class was rewritten, because upstream's matches nothing here.**
Upstream strips astral-plane emoji with the surrogate-pair class
`[\uD83C-\uD83E][\uDC00-\uDFFF]`. That is correct on Android, whose `java.util.regex` is
ICU-backed, and it is inert on the JVM: OpenJDK pairs a well-formed surrogate pair into a single
code point *before* applying a character class, so neither half of the class is ever offered a
value it contains. Measured directly — the pair `D83D DE00` reports `matches = false`, and
`Pattern.UNICODE_CHARACTER_CLASS` does not change it.

This was worth catching because it fails **silently**. Nothing throws; the strip simply does
nothing, and emoji reach eSpeak and speech-dispatcher, which is the exact symptom the line exists
to prevent. It surfaced from a JVM test asserting a removed 😀 and getting "Nice one 😀" back,
not from an error.

The replacement denotes the same set as the class it replaces: high `D83C`–`D83E` over low
`DC00`–`DFFF` is code points `U+1F000`–`U+1FBFF`, which is the emoji, symbol, transport and
enclosed-alphanumeric blocks — including the regional indicators that make flags. The BMP half of
the class is unchanged, because those are single code units and behave identically on both engines.
`AssistantServiceAudioTest` pins the three emoji shapes (face, symbol, flag), and also pins that
ordinary prose and Greek text survive untouched — a class widened too far is a worse bug than the
one it fixes.

**The chunked sequencing is extracted, not copied, and the reason is that upstream's shape is
untestable.** `playChunkedTtsGateway` and `startChunkPlayback` are ~370 lines that reach their
branches only through a live service and a real `MediaPlayer`: three-deep `postDelayed` polling,
`currentPlayer === mp` identity comparisons in every callback, state flips interleaved with the
sequencing. **Upstream has no test for any of it**, and could not have one. PLAN.md §6 requires the
opposite on this side — the sequencing, the cancel-previous guard and the progress arithmetic must
be provable with no audio hardware and no M2 implementation — so the policy moves to
`TtsChunkSequencer.kt` and is driven entirely through the `AudioPlayback` seam, which
`FakeAudioPlayback` implements.

The rule this produced is recorded in PLAN.md §5 and is general: **a PORT+ file that fuses platform
callbacks with policy gives the policy up to a NEW class in `:core`, and the file keeps the shape
its platform forces.** What stays in `AssistantServiceAudio.kt` is what the platform genuinely
forces — the parts that talk to the audio stack — and what stays in the service is what belongs to
the service: state transitions, `voiceDuration` publication, and the TTS generation counter.

The extraction kept three things that a rewrite would have lost, each noted in the source: synthesis
runs *concurrently* with playback rather than before it (the first-audio latency PLAN.md §6 cares
about), the duration estimate is refined on every chunk rather than measured once, and the 64-bit
product in that estimate is deliberate — `cumulativeDurationMs * fullTextLen` overflows 32-bit
around 5.6k characters, and the wrapped negative value froze the fraction at 0 and published a
negative duration, killing the highlight and the reveal together.

### `SettingsManager.kt`

Nothing about *what* is stored changed. The corruption-tolerant read on every entry, the legacy
migrations from the pre-`_v2` key names, the name-keyed history and spontaneous-state moves on a
rename, the two prefix scans that sweep orphans after a restore, the artwork orphan sweep and the
`BackupData` shape are all upstream's, comments included — that last one especially, because
importing an Android backup is a stated feature and the on-disk format is therefore a
compatibility surface rather than an implementation detail.

**Removed: `createEncryptedPrefsWithRecovery` and its `buildEncryptedPrefs` helper** (upstream
lines 79–139, about fifty lines plus the constants they used). They exist for one reason, stated
in their own comments: `EncryptedSharedPreferences` can fail to initialise against Android
Keystore on some Pixel/Android 13 combinations, so the code deletes the corrupted Keystore alias,
deletes the undecryptable preferences file, retries once, and — if the retry also fails — falls
back to *unencrypted* storage rather than crashing, warning that credentials "will not be
encrypted at rest on this device". There is no Keystore here and no encrypted store, so there is
nothing to corrupt and no recovery to attempt; it is dead weight that would also have implied a
seam (8) that v1 does not have (PLAN.md §5a). The settings file is mode 0600 by decision, which is
what the phone's own fallback produced on the devices the recovery existed for.

**Deliberately not removed: the per-entry tolerance.** Every `runCatching` around a decode, and
every degrade-to-default, is upstream's. That — together with the settings file's own
quarantine-and-keep behaviour in `JsonSettingsStore` — is the protection the user's data actually
depends on, and it is pinned by `SettingsManagerCorruptionTest` (41 tests, all of upstream's
assertions unchanged).

**Platform substitutions, none of which change behaviour:** `Context` → `AppEnv` (with `filesDir`
→ `dataDir`); `SharedPreferences` → the `SettingsStore` seam, so `prefs.edit().putString(…).apply()`
becomes `store.edit().putString(…).commit()`; `BitmapFactory`/`Bitmap.compress` → the `ImageCodec`
seam, with the dimensions and JPEG qualities staying here because PLAN.md §5b records them as
behaviour; `Uri` → `Path`; `android.util.Base64` → `java.util.Base64`, encoding with the basic
encoder (`Base64.NO_WRAP`'s exact output, so a written backup matches what the phone would write)
and decoding with the MIME decoder (Android's `Base64.DEFAULT` emits line breaks the basic decoder
would reject).

The `Persona.iconImageUri` / `backgroundImageUri` fields keep their names and their `String` type
even though they now hold a path. Renaming them would change the persisted schema and therefore
what a backup contains, which is the one thing this port must not do.

## Hand-written files in `core/`

Not from upstream, so they have no upstream commit — but two of them exist only because an upstream
declaration is stuck behind the m1 boundary.

| File | Why it exists |
|---|---|
| `android/util/Log.kt` | The `Log` shim. Three COPY files call `android.util.Log` **fully qualified**, so no `import android.*` line exists to find; a file in that state cannot compile on the JVM. The only file in this project allowed under an `android.*` package (PLAN.md §7a). |
| `com/nikosm/voiceassistant/GatewayUrl.kt` | `BORROWED FROM M1` — `normalizeGatewayUrl`, defined upstream at `AssistantServiceGateway.kt:118`. |
| `com/nikosm/voiceassistant/ImageGenConstants.kt` | `BORROWED FROM M1` — `IMAGE_GEN_NEGATIVE_PROMPT`, defined upstream at `AssistantServiceImageGen.kt:74`. |

**Borrowed declarations.** A declaration that a m0 file needs but upstream defines inside a m1 file
is duplicated into its own file, whose header carries the exact line `BORROWED FROM M1` and names
the upstream file that will absorb it. `grep -rl "BORROWED FROM M1" core/` is the M1 checklist
(AGENTS.md §3). When the upstream file arrives, that declaration is deleted from here in the same
commit and the deletion is recorded below.

Neither borrowed declaration may be edited to differ from upstream. If one needs to change, the
upstream file has changed, and this is a sync — not an edit.

## Other byte-identical files

| File | Upstream | Commit | Notes |
|---|---|---|---|
| `LICENSE` | `LICENSE` | `fe584ad0` | GPL-3.0, verbatim. This port is a derivative work, so it is GPL-3.0 too (README.md). |
| `assets/silero_vad.onnx` | `app/src/main/assets/silero_vad.onnx` | `fe584ad0` | 2,327,524 bytes, sha256 `2623a2953f6ff3d2c1e61740c6cdb7168133479b267dfef114a4a3cc5bdd788f`. MIT. Seam 4 runs this exact model file — the same one the phone uses — so a VAD decision here means what it means there. Grouped m1, but taken early in M0.2 before the group split existed; it is inert data and does not affect compilation. |

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
2. Remove it from `COPY_SOURCES` in `tools/sync-from-android.sh`.
3. Add a row below saying what changed and why.

*None yet.*

## Borrowed-declaration removals

The other half of the same rule: when an upstream m1 file arrives, the declaration borrowed from it
is deleted from `core/` in the same commit.

*None yet. Both borrowed declarations are still live.*

## Checking for drift

```sh
tools/sync-from-android.sh --check      # exit 1 and a diff if any taken file has moved — main and test
grep -rl "BORROWED FROM M1" core/       # the M1 checklist: every borrowed declaration
tools/check-upstream.sh                 # planned: what changed upstream since the pin above
```
