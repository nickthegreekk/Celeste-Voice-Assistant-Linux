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

## Group m1 — taken

These six are byte-identical copies too, and they arrived at M1 with the `AssistantService.kt` port
they extend. Each one is a set of extension functions on that class and cannot compile without it,
which is what the group split existed to express.

| File | Lines | Taken |
|---|---:|---|
| `AssistantServiceSpontaneous.kt` | 648 | M1, commit `fe584ad0` |
| `AssistantServiceRoomTurns.kt` | 671 | M1, commit `fe584ad0` |
| `AssistantServiceImageGen.kt` | 370 | M1, commit `fe584ad0` |
| `AssistantServiceComfy.kt` | 317 | M1, commit `fe584ad0` |
| `AssistantServiceRooms.kt` | 284 | M1, commit `fe584ad0` |
| `TurnSink.kt` | 98 | M1, commit `fe584ad0` |
| **Total** | **2,388** | |

3,030 + 2,388 = 5,418 across thirteen source files, which is PLAN.md §7's 4,434 plus `Models.kt`,
which §7 counted separately as ADAPT.

**All six are still byte-identical to upstream**, `--check` covers them, and nothing in this port
edits them. That constraint is not decorative — it is what decided two things in M1, both recorded
below and in `docs/DECISIONS.md`: `AssistantService.cacheDir` is a `java.io.File` rather than a
`Path`, because `AssistantServiceComfy.kt` builds `File(cacheDir, …)` and calls `renameTo` on the
result; and seam 10 exists in M1 at all, because `AssistantServiceSpontaneous.kt` calls
`armSpontaneousWork`, `disarmSpontaneousWork` and `isSpontaneouslyArmed` three times each.

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
| `AssistantService.kt` | PORT+ | `app/src/main/java/com/nikosm/voiceassistant/AssistantService.kt` | The seeded port. `Service`→plain class; `Context`→`AppEnv`; the audio-focus, proximity, notification, permission and update-check subsystems deleted; seams 9/10 hung off `AppEnv`; `cacheDir` stays a `java.io.File`. Detail below. |
| `AssistantServiceAudio.kt` | PORT+ | `app/src/main/java/com/nikosm/voiceassistant/AssistantServiceAudio.kt` | Seams 1/2/3/6; the focus machinery deleted; the chunked sequencing extracted to `TtsChunkSequencer.kt`; `cleanTextForTts`'s astral-emoji class rewritten for the JVM regex engine. Detail below. |
| `AssistantServiceChat.kt` | ADAPT | `app/src/main/java/com/nikosm/voiceassistant/AssistantServiceChat.kt` | The seeded port. `Uri`→`Path` throughout the attachment and image paths, `BuildConfig.DEBUG`→`AppEnv.debugLogging`, `android.util.Base64`→`java.util.Base64`, the focus-release calls deleted. Detail below. |
| `AssistantServiceGateway.kt` | ADAPT | `app/src/main/java/com/nikosm/voiceassistant/AssistantServiceGateway.kt` | `Context`→`AppEnv`, `File`→`Path` (including OkHttp's `asRequestBody`, which needs `toOkioPath()` and `FileSystem.SYSTEM`), `normalizeGatewayUrl` absorbed from the retired borrow. Detail below. |
| `AssistantServiceContext.kt` | ADAPT | `app/src/main/java/com/nikosm/voiceassistant/AssistantServiceContext.kt` | `Context`→`AppEnv` and `debugLogging` read from it; `logSuggestedRepliesParse` became an extension because it needs the environment. Detail below. |
| `SettingsManager.kt` | PORT+ | `app/src/main/java/com/nikosm/voiceassistant/SettingsManager.kt` | Seams 7 and 15; `Context`→`AppEnv`; `Uri`→`Path`; `android.util.Base64`→`java.util.Base64`; `createEncryptedPrefsWithRecovery` removed. Detail below. |
| `SpontaneousNotifications.kt` | NEW | `app/src/main/java/com/nikosm/voiceassistant/SpontaneousNotifications.kt` | Seam 9; the M1 part only — the notification id, the preview text and the cancel path. The posting half is M4's. Detail below. |
| `VADAudioRecorder.kt` | **NEW** | `app/src/main/java/com/nikosm/voiceassistant/VADAudioRecorder.kt` | `AudioRecord`→seam 1, `AcousticEchoCanceler` deleted (§3/D8, no marker — a decision, not a deferral), `File`→`Path`, the WAV writer extracted to `WavFile.kt`. Every constant and invariant kept verbatim. Detail below. |
| `VADDetector.kt` | ADAPT | `app/src/main/java/com/nikosm/voiceassistant/VADDetector.kt` | Seam 4, which turns out not to be a seam: the `ai.onnxruntime` classes are identical on both sides, so the only change is that the model is a classpath resource instead of an APK asset — which is why the `Context` parameter is gone. Detail below. |
| `Scheduler.kt` | **NEW** | seam 10, plus three functions from `SpontaneousDeliveryWorker.kt` | The interface, and the three functions a byte-identical COPY file calls. `SpontaneousDeliveryWorker.kt` itself stays out of M1. Detail below. |
| `WavFile.kt` | **NEW** | the header writer inside `VADAudioRecorder.kt` | Extracted so that two producers — the VAD loop and push-to-talk — write one layout, which `wavDurationSeconds` reads back. Detail below. |
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

### The M1 service cluster — the ledger

The whole commit, measured against upstream, file by file. `removed` and `added` are line counts
from `diff -u <upstream> <ported>`; a byte-identical file is marked as such and is what
`tools/sync-from-android.sh --check` guards.

| File | Upstream | Ported | Removed | Added | Net |
|---|---:|---:|---:|---:|---:|
| `AssistantService.kt` | 3,372 | 3,114 | 789 | 531 | −258 |
| `AssistantServiceChat.kt` | 2,952 | 3,008 | 123 | 179 | **+56** |
| `AssistantServiceAudio.kt` | 1,380 | 849 | 1,072 | 541 | −531 |
| `AssistantServiceGateway.kt` | 656 | 700 | 35 | 79 | +44 |
| `AssistantServiceContext.kt` | 485 | 504 | 25 | 44 | +19 |
| `SettingsManager.kt` | 1,357 | 1,292 | 330 | 265 | −65 |
| `SpontaneousNotifications.kt` | 178 | 92 | 142 | 56 | −86 |
| `VADAudioRecorder.kt` | 323 | 266 | 137 | 80 | −57 |
| `VADDetector.kt` | 113 | 155 | 18 | 59 | +42 |
| 11 byte-identical files | 9,431 | 9,431 | 0 | 0 | 0 |
| **Total (22 files)** | **16,247** | **15,411** | **2,671** | **1,834** | **−836** |

**`AssistantServiceChat.kt` grew, and that is the honest outcome rather than a miss.** Its Android
surface was small — one `Uri` parameter list, eleven `BuildConfig.DEBUG` reads, two Base64 calls —
so there was little to delete, while the deleted calls each needed a comment saying what had
replaced them. A port that shrinks every file is a port that deleted the comments explaining it.

The two hand-written ports are the ones with a `diff` worth reading:

```
diff -u ../VoiceAssistant/app/src/main/java/com/nikosm/voiceassistant/AssistantService.kt \
        core/src/main/kotlin/com/nikosm/voiceassistant/AssistantService.kt | less
```

### What each deleted thing became

The audit of the 2,671 removed lines. Everything here is a **decision** unless marked otherwise, and
a decision carries no `DEFERRED TO M<n>` marker — nothing later restores it.

| Deleted | Lines | What replaced it, or why nothing does |
|---|---:|---|
| Audio focus: `requestAssistantFocus`/`abandonAssistantFocus`, the DELAYED retry loop, the A4 test seam, `audioFocusChangeListener`, mode/`setCommunicationDevice` routing | ~330 | Nothing, and nothing needed. PipeWire has no focus concept and playback is a helper process the compositor already arbitrates. The user-visible part — choosing an output device — is seam 3. Six call sites' comments now say what the release *was* for; five of them had one reader between them |
| Proximity barge-in: sensor fields, listener, arm/confirm runnables, debounce constants, telephony guardian | ~200 | Nothing. A hand waved over a phone's proximity sensor has no desktop counterpart, and the action it triggered — stop playback — is the Stop button. §8's dropped list, and §12's hotkey is the desktop gesture equivalent |
| The bundled eSpeak engine: `_espeakEngine`, `espeakLock`, the DCL getter, `warmUpEspeakEngine` | ~55 | Seam 6. The once-per-process construction rule the lock enforced is written into `TextToSpeech`'s KDoc so it is not lost with the code |
| The update check: `checkForAppUpdate`, `isNewerVersion`, `dismissUpdate`, `_updateAvailable`, `UPDATE_CHECK_INTERVAL_MS` | ~75 | Nothing. §8 drops it, and the reason is not only policy: it called `api.github.com` unconditionally, which §8's "no network call the user did not configure" forbids |
| The foreground-service lifecycle: `onBind`, `AssistantBinder`, `onStartCommand`, `promoteToForeground`, `createNotificationChannel`, `createNotification`, `onDestroy` | ~90 | `start()` and `shutdown()`, plus seam 9 for the notification. One process (D1) means the UI holds the instance; there is no IPC boundary for a binder to cross and no permission for `startForeground` to wait on |
| `MediaRecorder` push-to-talk, `currentPlayer`, `currentAudioTrack` | ~60 | Seams 1 and 2. The `MediaRecorder` capture loop is a coroutine over `CaptureStream` writing the same WAV the hands-free path writes; the two identity-comparison fields became `PlaybackHandle`s held by the sequencer |
| `contentResolver` / `Uri` throughout the attachment, image and backup paths | ~50 | `Path` and `Files`. A `content://` URI needed a resolver because the bytes might belong to another application under a revocable grant; a path from a dialog is a file this process can open. `isPlainTextAttachment` lost its MIME probe with it — see its own KDoc for the narrowing that costs |
| `ContextCompat.checkSelfPermission` / `ACCESS_LOCAL_NETWORK` / `RECORD_AUDIO` / `READ_PHONE_STATE` guards | ~35 | Nothing. There is no permission to hold for a microphone or a LAN socket on this platform |
| `BuildConfig.DEBUG` | 11 sites | `AppEnv.debugLogging`, the M0.5 privacy seam: a runtime flag defaulting to **false**, because the failure mode is the user's private conversations in journald |
| `Toast` | 18 sites | `notifyUser(...)` → `transientMessage`, the `:core` half of §9's "Toast to snackbar". The `LENGTH_SHORT`/`LENGTH_LONG` distinction does not cross the boundary; its KDoc says why |
| `android.util.Base64` | 8 sites | `java.util.Base64`: the basic encoder for `NO_WRAP`, the MIME decoder where the payload comes from a server, exactly as `SettingsManager.kt` splits it |
| The audio-focus retention through THINKING | ~40 | Nothing. The `playbackRequested` flag had one reader — the focus release — and that reader is gone. What it protected is still enforced: a turn that ends without handing off leaves THINKING and lands in IDLE |
| `AcousticEchoCanceler` in `VADAudioRecorder` | 10 | Nothing, **and this is the one deletion upstream never had** (§3/D8). It carries no marker on purpose: the marker means "a later milestone restores this", and nothing will. If it is ever wanted it is a PipeWire `module-echo-cancel` sink, i.e. no application code at all |

Two things are **deferrals** rather than decisions, and they are the only two `DEFERRED TO M4`
markers in the tree — both in `AssistantService.kt`, both the `removePersonaShortcut` call §9
records, deleted because M1 creates no `.desktop` entry for them to withdraw.

### `AssistantService.cacheDir` is a `java.io.File`, not a `Path`

Recorded here because it looks like an oversight and is the opposite. Two byte-identical COPY files
force it: `AssistantServiceComfy.kt` opens with `File(cacheDir, "gen_….png")` and then calls
`destination.name`, `partial.renameTo(destination)` and `partial.delete()`, and
`AssistantServiceImageGen.kt` takes a `File` into `publishGeneratedImage` and stores
`imageFile.absolutePath`. A `Path` member makes six lines of those files fail to compile.

It is also simply what upstream had: `Context.cacheDir` returns a `File`, so the member has always
been one, and the port is not changing it — it is declining to. `AppEnv.cacheDir` remains a `Path`,
which is the seam's type (AGENTS.md §4), and `AssistantService.cacheDir` is a `File` view of it.
The thirteen `:core` call sites that want a `Path` say `.toPath()`.

### The rest of the cluster, file by file

**`AssistantServiceGateway.kt`.** `Context`→`AppEnv`; `File`→`Path`; `debugLogging` read from the
environment. One substitution is invisible until it breaks: OkHttp streams a file body through
okio, and both of its entry points are okio's (`File.asRequestBody`, and `Path.asRequestBody`, which
takes a `FileSystem`). A `java.nio.file.Path` is neither, so the `/transcribe` upload crosses with
`toOkioPath()` and an explicit `FileSystem.SYSTEM`. The same substitution was needed twice more, in
`AssistantServiceChat.kt`'s audio upload. `normalizeGatewayUrl` moved in from the retired borrow
file and is now defined here.

**`AssistantServiceContext.kt`.** The smallest diff in the cluster, and the reason is that it was
already almost pure: `Context`→`AppEnv` and the debug flag read from it. `logSuggestedRepliesParse`
became an extension rather than a free function, because logging at the level it logs at needs
`debugLogging`, which lives on the receiver. The skill, suggestion-parsing and date helpers are
otherwise untouched.

**`SpontaneousNotifications.kt`.** Seam 9, and only the M1 part of it: the notification id
(`spontaneousNotificationId`, derived from the persona name so one persona's message replaces its
own card), the preview text, and `cancelSpontaneousNotification(appEnv, personaName)`. The posting
half — the part that needs a scheduler and a turn — is M4's, and `Notifier`'s KDoc records that its
mapping from id to desktop notification is in-memory only until M4 has a posting path to justify
persisting it. The extension extras and the delivery worker belong to `SpontaneousDeliveryWorker.kt`,
which stays out of M1.

**`VADDetector.kt`.** Seam 4, and §5's row turns out to describe an artifact substitution rather
than an API change: `OnnxTensor`, `OrtSession` and `OrtEnvironment` are the same classes in both
`ai.onnxruntime:onnxruntime-android` and `onnxruntime` for linux-x64. So there is no interface and
no `:platform` implementation — the only change is where the model comes from, `context.assets.open`
becoming a classpath resource with `assets/` on `:core`'s classpath. **The `Context` parameter is
gone because it existed only to reach the asset table.** Everything else is upstream's, including
the sigmoid branch for a model returning a logit and the `use {}` around `s.run` that stops a native
result leaking on the early-return path.

**`VADAudioRecorder.kt`.** NEW, and §6's "keep the behaviour, replace the device access" taken as
literally as it can be. Three changes and only three: `AudioRecord`→seam 1, `AcousticEchoCanceler`
deleted (§3/D8), `File`→`Path` with the WAV written by `WavFile`. **Every constant and invariant is
kept verbatim**, including the pre-roll ring buffer whose comment explains that dropping it clipped
short words badly enough to confuse STT, and both locks with the rules upstream arrived at — the
data lock never held across file I/O or a suspension point, and the detector's separate lock so that
inference never runs under the data lock. Those are not tuning and this port did not touch them.

**`WavFile.kt`.** NEW, extracted from `VADAudioRecorder`'s private header writer. It exists because
push-to-talk needed the same container: two writers of one layout is one too many when a third
function, `wavDurationSeconds`, parses those same 44 bytes back, and the failure is silent — a
disagreeing header does not throw, it reports a wrong duration, and `voiceDuration` is what the
timing of the UI reveal is built on. `header()` and `write()` are together for that reason: the
header's size derives from the same snapshot the samples come from.

**`Scheduler.kt`.** NEW, and §5's seam-10 row plus three functions lifted from
`SpontaneousDeliveryWorker.kt` — `isSpontaneouslyArmed`, `armSpontaneousWork`,
`disarmSpontaneousWork`, by their upstream names and with `Context`→`AppEnv` as their only change.
They are top-level rather than members of `AssistantService` because the COPY file that calls them
writes `isSpontaneouslyArmed(applicationContext, personaName)`, and a member extension does not
accept that shape. `AppEnv.scheduler` exists for the same reason `AppEnv.notifier` does — the only
two seams on that interface, and both because a file this port may not edit hands over
`applicationContext` and nothing else.

**Push-to-talk, in `AssistantService.kt`.** Not a file, but the largest piece of new code in the
port, so it belongs in this ledger. Upstream's `MediaRecorder` wrote AAC into an MP4; seam 1 hands
over signed 16-bit frames and nothing else, so the capture loop writes the same 16 kHz mono WAV the
hands-free path already sends to `/transcribe`. `MediaRecorder.maxAmplitude` became the peak of the
block — the same number `VADAudioRecorder` computes for its half of the visualiser — and one
polling coroutine disappeared with it, because the samples are already in hand. The `runCatching`
around the write and the flag-and-lock pairing are upstream's shapes: the snapshot is taken on Main
while the loop appends on IO, and an unsynchronized version of that is the
ConcurrentModificationException that killed the recorder coroutine upstream.

## Hand-written files in `core/`

Not from upstream, so they have no upstream commit.

| File | Lines | Why it exists |
|---|---:|---|
| `android/util/Log.kt` | 71 | The `Log` shim. COPY files call `android.util.Log` **fully qualified**, so no `import android.*` line exists to find; a file in that state cannot compile on the JVM. The only file in this project allowed under an `android.*` package (PLAN.md §7a). |
| `AppEnv.kt` | 101 | D5's replacement for `Context`. |
| `AppLogger.kt` | 80 | The project logger, the `LogSink` bridge and the levels. |
| `SettingsStore.kt` | 76 | Seam 7's interface. |
| `ImageCodec.kt` | 49 | Seam 15's interface. |
| `AudioCapture.kt` | 63 | Seam 1. |
| `AudioPlayback.kt` | 118 | Seam 2, with the three-valued `PlaybackEnd` and `PlaybackHandle`. |
| `AudioDevices.kt` | 65 | Seam 3. |
| `TextToSpeech.kt` | 85 | Seam 6, the two on-device routes. |
| `Notifier.kt` | 61 | Seam 9. |
| `Scheduler.kt` | 144 | Seam 10 — see the adapted-files table. |
| `TtsChunkSequencer.kt` | 354 | The extracted chunked-TTS policy. |
| `WavFile.kt` | 73 | The WAV writer both capture paths share. |

**Borrowed declarations, and their retirement.** A declaration that a m0 file needs but upstream
defines inside a m1 file was duplicated into its own file, whose header carried the exact line
`BORROWED FROM M1` and named the upstream file that would absorb it. `grep -rl "BORROWED FROM M1"
core/` was the M1 checklist (AGENTS.md §3), and it is now empty — see "Borrowed-declaration
removals" below.

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

Both retired at the M1 cluster commit, and the compiler insisted on it rather than merely allowing
it — each upstream file declares the same symbol in the same package, so leaving the borrow in place
is a redeclaration error, not a warning. The deletion is deliberate and this is the record of it:

| File deleted | Declaration | Absorbed by | Where it landed |
|---|---|---|---|
| `GatewayUrl.kt` | `normalizeGatewayUrl` | `AssistantServiceGateway.kt:118` | `AssistantServiceGateway.kt` |
| `ImageGenConstants.kt` | `IMAGE_GEN_NEGATIVE_PROMPT` | `AssistantServiceImageGen.kt:74` | `AssistantServiceImageGen.kt` (byte-identical, untouched) |

`GatewayUrl.kt` was folded into the gateway file rather than kept as its own — `normalizeGatewayUrl`
is a gateway helper and belongs beside the rest of them, so the borrow's *file* disappeared while its
declaration survived, now defined in `:core` instead of duplicated into it. `ImageGenConstants.kt`
had no such home and is simply gone.

`grep -rl "BORROWED FROM M1" core/` is empty and must stay that way: there is no M2 borrow list, and
a future milestone that needs one gets its own marker rather than reviving this one.

## Checking for drift

```sh
tools/sync-from-android.sh --check      # exit 1 and a diff if any taken file has moved — main and test
grep -rl "BORROWED FROM M1" core/       # the M1 checklist: every borrowed declaration
tools/check-upstream.sh                 # planned: what changed upstream since the pin above
```
