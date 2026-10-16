# Port plan — Celeste for Linux

How the Android app at `../VoiceAssistant` becomes a native Arch/Debian desktop
application. Written against the actual source (150 `.kt` files, 28,986 lines of `app/src/main`,
8,066 lines of JVM tests, 20,889 lines of instrumented tests) after reading the manifest, the
gradle files, the service, the audio path and the VAD recorder. Numbers quoted below are measured,
not estimated.

The upstream tree is **read-only**. Nothing in this project writes to it.

---

## 1. Goal and definition of done

A single JVM application, `celeste-linux`, that on a stock Arch or Debian desktop with PipeWire:

1. Captures speech from the chosen input device and detects utterance boundaries on-device with
   Silero VAD, exactly as the phone does (16 kHz mono PCM16, 512-frame chunks, 3-chunk pre-roll,
   ~2 s of silence to end an utterance).
2. Sends the utterance to the Voice Gateway's `/transcribe`, gets a reply from the configured
   provider, and speaks it through one of three routes (gateway `/synthesize`, system
   speech-dispatcher, local eSpeak-NG).
3. Keeps the personas, servers, providers, skills, image-generation entries, models, rooms and
   history that the phone app has, and imports its backup file.
4. Generates images through ComfyUI and A1111 with the same workflow-token semantics
   (`%PROMPT%` / `%NEGATIVE%` / `%SEED%`, per-character workflow binding).
5. Schedules spontaneous messages while the app or the user service is running.
6. Installs and runs on Arch and Debian from a package, with no system JDK requirement and no
   emulation layer.

Done means: the hands-free round trip works with headphones, the ported test suites pass on both
distros, and both packages install and uninstall cleanly in a container.

## 2. Out of scope for v1

- Emulation, Waydroid, containers holding an APK. (Explicitly rejected.)
- macOS/Windows builds. The seams are written so they could be added, but nothing is spent on them.
- Multi-user, per-user audio routing, and running the daemon for more than one logged-in session.
- Keeping the phone app and the desktop app in sync (no shared server, no account, no relay).
  Out of scope and worth saying out loud: the two are separate installations that happen to
  share a backup file format.
- The Android-only affordances listed in §9 (quick-settings tile, proximity barge-in, phone-call
  guard, launcher shortcuts) — each gets a desktop equivalent or is dropped.

## 3. Architectural decisions

**D1 — One JVM process, not two.** Android needs a `Service` beside an `Activity` because the OS may
kill the activity; a desktop app does not. The service core and the UI live in one process and
communicate through the same `StateFlow`s they already use, which removes the binder, the
foreground-service dance, and any IPC design work. `--headless` runs the core with no window, for
the systemd launcher.

**D2 — Compose Multiplatform, not a new UI.** 20,585 lines across 23 files are Compose. The desktop
target hosts them; the edits are import substitutions and the interaction sites named in §7.

[D2 measured, 2026-10-15 — **it holds, and the number is 9.** `tools/compose-probe.sh` writes every
`androidx.compose.*` / `coil3.*` / `com.nikosm.*.ui.*` import the UI layer actually uses into one
file and compiles it against `:desktop`. Of **203** distinct imports, **194 resolve as they stand**.
The nine that do not, with `--deps` (which adds material3 and material-icons-extended through the
Compose plugin's own accessors and then puts the build script back):

| Import | What it is |
|---|---|
| `androidx.compose.material3.dynamicDarkColorScheme` / `dynamicLightColorScheme` | Android's Material You, which reads the wallpaper through a `Context`. No desktop counterpart; the theme's own palette is the fallback and already exists |
| `androidx.compose.ui.platform.LocalContext` | 10 call sites, every one wanting a `Context` for a Toast, a clipboard entry or a file picker — i.e. for a seam that already exists |
| `androidx.compose.ui.platform.LocalConfiguration` | 2 call sites, and the only thing read is `screenHeightDp` and `orientation`: on a desktop that is the window's own size, which `:desktop` knows |
| `android.app.Activity`, `android.os.Build` | `ui/theme/Theme.kt`'s edge-to-edge and SDK-version check |
| `coil3.compose.AsyncImage` | a dependency, not a port — Coil 3 is multiplatform and §8 already assumes it |
| `VoiceAssistantTheme`, `isDynamicColorSupported` | the app's own `ui/theme/` package (4 files, 152 lines), not yet copied |

Without `--deps`, 81 imports fail — every `androidx.compose.material3.*` and
`androidx.compose.material.icons.*` one. Those are **two dependency declarations on `:desktop`**,
not porting work, and they are the only new dependencies the UI needs. The conclusion is the one
the estimate hoped for: the Compose surface transfers with import-level edits, and the genuine work
is 12 call sites across 5 files — the `LocalContext`/`LocalConfiguration` ones — plus the seams §7
already assigns.]

[2026-10-15, measured rather than assumed: `tools/compose-probe.sh` compiles every `androidx.compose.*` import the UI
layer uses against `:desktop`, and of 203 distinct imports 194 resolve unchanged. The nine that do not are twelve call
sites across five files plus the app's own `ui/theme` (4 files, 152 lines, not yet copied): `LocalContext` at ten sites,
every one wanting a Context for a Toast, the clipboard or a file picker — that is, for seams 9 and 13, which the seam
list already predicted; `LocalConfiguration` at two sites, reading only screen height and orientation, which on a desktop
is the window; the Material-You dynamic colour pair, which reads the wallpaper through a Context and falls back to a
palette the theme already has; `android.app.Activity` and `android.os.Build` in `ui/theme`'s edge-to-edge and SDK check,
both dropped; and `coil3.compose.AsyncImage`, which is a dependency. So D2 holds — the UI is a port with a list of twelve
call sites, not a rewrite. The probe stays in the repo and gets re-run before each UI milestone: it answers this question
by compiling, which is the only kind of answer that has held up in this project.]

**D3 — Keep the Kotlin package and file names.** Copied files stay at
`com.nikosm.voiceassistant`, same names, so an upstream change can be copied in and reviewed as a
small diff. Renaming would be cosmetic and would destroy that.

**D4 — The copied files stay `internal`.** The app uses `internal` visibility heavily, so all
shared files must live in **one** Gradle module (`:core`), not several.

[2026-10-15, made concrete by the UI: `:core` has 301 `internal` top-level declarations and the UI needs 46 of
them, so the **ported screens live in `:core` too** — `:desktop` keeps the window, the tray, packaging and
`Main.kt`. The invariant is that the module boundary must not cut through upstream's visibility: upstream is a
one-module app, so a member it declares `internal` has to stay reachable by the files that used it there.
Rejected: `-Xfriend-paths` (an unsupported flag that breaks silently on a Kotlin upgrade) and demoting 46 COPY
declarations to public (a mass demotion of byte-identical files, each needing a §7 row change and a PROVENANCE
entry, and it destroys the clean-diff guarantee for every future upstream change).]

**D5 — Android's `Context` becomes one small interface.** An `AppEnv` object (paths, logger,
process launcher, device info) replaces `Context` and `Log`. This is the single most mechanical
change in the codebase and it touches a few hundred call sites.

[2026-10-15, from the M1 service wiring: `AppEnv` carries a seam **only** when a caller that cannot be edited
forces it — the notifier, because a byte-identical COPY file passes `applicationContext` and nothing else. Every
other seam arrives as an explicit constructor parameter, `SettingsManager(appEnv, store, imageCodec)` being the
pattern. That distinction is the whole of D5's benefit: an object that accretes dependencies is precisely what
`Context` was, and this rule is cheap to apply and easy to check against a constructor signature.]

**D6 — Helper processes for audio, behind an interface.** `pw-record`/`parec`/`arecord` for capture
and `pw-play`/`paplay`/`aplay` for playback, wrapped so a JNA binding to `libpipewire` can replace
them later without touching callers. Rationale in §6.

**D7 — Secrets in libsecret, settings in a JSON file.** The Android app's
`EncryptedSharedPreferences` + Android Keystore is replaced by (a) a plain JSON settings file in
`~/.config`, and (b) `secret-tool` in libsecret for credentials, with a 0600 file fallback that the
settings UI warns about. The existing corruption-recovery and migration logic in `SettingsManager`
is kept — it is hard-won and platform-independent in spirit.

**D8 — No acoustic echo cancellation, by decision.** Headphones or push-to-talk. If it is ever
wanted, it is a PipeWire `module-echo-cancel` sink selected as the input device — no application
code.

**D9 — Notifications are DBus, not a bespoke surface.** `org.freedesktop.Notifications` with a
single "Open" action; `notify-send` is the implementation, because it is present on both targets and
avoids a DBus binding dependency.

**D10 — XDG paths everywhere.** Table in [README.md](README.md).

**D11 — The Gradle project is a plain JVM project plus the Compose plugin.** No Kotlin Multiplatform
targets are needed while Linux is the only target; a JVM project is simpler and keeps the Android
and desktop builds independent. If another desktop OS is ever wanted, that is a targets addition,
not a redesign.

## 4. Layout

```
VoiceAssistant-Linux/
├── README.md
├── PLAN.md                     (this file)
├── LICENSE                     copied verbatim from upstream (GPL-3.0)
├── settings.gradle.kts
├── gradle/libs.versions.toml
├── core/                       shared, ported from upstream, one module (D4)
│   └── src/main/kotlin/com/nikosm/voiceassistant/…
│   └── src/test/kotlin/…       the ported JVM tests
├── platform/                   Linux implementations of the seams (§5)
│   └── src/main/kotlin/com/nikosm/voiceassistant/linux/…
├── desktop/                    window, tray, packaging — never ported from anywhere; the screens live in :core
│   └── src/main/kotlin/com/nikosm/voiceassistant/…
│   └── src/test/kotlin/…       Compose desktop UI tests
├── packaging/
│   ├── arch/PKGBUILD
│   ├── debian/…                debian/ control, rules, changelog
│   └── systemd/celeste-linux.service   user unit
├── tools/
│   ├── sync-from-android.sh    copies upstream files into core/ (never edits upstream)
│   └── check-upstream.sh       diffs copied files against upstream HEAD
├── docs/
│   ├── PROVENANCE.md           file → upstream commit hash
│   ├── TEST-MAP.md             which instrumented test becomes what
│   └── DEVICES.md              input/sink selection, PipeWire notes
└── assets/
    └── silero_vad.onnx         copied from upstream (MIT)
```

## 5. The platform seams

Fourteen places where Android provided the platform. Each is an interface in `core/` with a Linux
implementation in `platform/`; `core` never imports from `platform`.

| # | Seam | Android today | Linux implementation |
|---|---|---|---|
| 1 | `AudioCapture` | `AudioRecord` + `AcousticEchoCanceler` | `pw-record` → `parec` → `arecord`, raw s16le mono 16 kHz on stdout |
| 2 | `AudioPlayback` | `AudioTrack` + marker listener + `AudioFocusRequest` | `pw-play` → `paplay` → `aplay`; "finished" = process exit |
| 3 | `AudioDevices` | `AudioManager`, earpiece/Bluetooth routing | `wpctl status` / `pactl list` parse; `--target` on capture and playback |
| 4 | `Vad` | ONNX Runtime Android (`ai.onnxruntime.*`) | **Not a seam** (2026-10-15) — an artifact substitution, not an interface: the same `ai.onnxruntime.*` classes exist on both sides, and only the model origin changes, from the Android asset table to a classpath resource, so `VADDetector.kt` needs no abstraction and drops its `Context` parameter |
| 5 | `SpeechToText` | HTTP `POST /transcribe` | unchanged (already HTTP) |
| 6 | `TextToSpeech` | Android `TextToSpeech`, bundled eSpeak JNI, gateway HTTP | `spd-say` (speech-dispatcher), `espeak-ng` CLI or JNA to the distro `libespeak_ng.so`, gateway HTTP unchanged |
| 7 | `SettingsStore` | `EncryptedSharedPreferences` | JSON file in `~/.config/celeste-linux/` |
| 8 | `SecretStore` | AES/GCM via Android Keystore | **Not in v1** — credentials live in the settings file at mode 0600, see §5a |
| 9 | `Notifier` | `NotificationManager` + channels | `notify-send`, tracking the notification id so a cancel is possible. **Lands real in M1**, not as a stub, because a byte-identical COPY file calls `cancelSpontaneousNotification`; M4 adds the Open action and the click routing rather than replacing it |
| 10 | `Scheduler` | `WorkManager` (self-rescheduling, reboot-safe) | coroutine timer inside the service core, driven by the already pure `SpontaneousScheduler`; optional systemd user timer. **Lands in M1 as an interface plus `StubScheduler`**, since a byte-identical COPY file calls `armSpontaneousWork`, `disarmSpontaneousWork` and `isSpontaneouslyArmed` three times each; M4 replaces the stub |
| 11 | `Tray` | Quick-settings tile | AWT `SystemTray` menu (Listen now, Mute mic, Open, Quit) |
| 12 | `Hotkey` | None (proximity "wave to interrupt") | WM/desktop hotkey to `celeste-linux --listen` (documented per desktop); optional XDG GlobalShortcuts portal |
| 13 | `FileDialogs` | SAF `ActivityResult` pickers | AWT `FileDialog` / chooser |
| 14 | `CertTrust` | `networkSecurityConfig` + TOFU prompt | OkHttp `X509TrustManager` + TOFU store file in `~/.local/share/celeste-linux/` |
| 15 | `ImageCodec` | Android `BitmapFactory` / `Bitmap.compress` | `javax.imageio` decode, scale and encode, with JPEG quality 88 and the upstream dimension limits |

The bundled `arm64-v8a/libespeak-ng.so`, `src/main/cpp/` (its CMake build and JNI shim) and the
34 MB of piper/espeak assets do **not** come across: on Linux eSpeak-NG is a distro package and
piper/Kokoro lives on the gateway.

**A seam is introduced with its first caller, not with its implementation (2026-10-15).** Earned by the M1
boundary finding: the chat flow in `AssistantServiceChat.kt` ends by calling `playResponse` and its finally
calls `abandonAssistantFocus`, so chat orchestration cannot compile without the audio seams existing — and the
file that defines them, `AssistantServiceAudio.kt` (1,379 lines), therefore has to be ported in the same
milestone as its callers, even though its *implementations* belong to M2. So: the milestone that ports the
first caller of a seam defines the interface in `:core` and ports the calling file; the `:platform`
implementation may stay a stub until its own milestone. Every stub is named `Stub*`, lives in `:platform`, and
logs once at INFO naming the milestone that replaces it — so `grep -rn 'Stub' platform/src/main` is the entire
to-do list for the milestone that inherits them.

**A PORT+ file that fuses platform callbacks with policy gets its policy extracted (2026-10-15).** One level up from
seam 2's shape decision: upstream's TTS sequencing - chunk order, one-at-a-time, cancel-previous and the progress
arithmetic - lives inside roughly 350 lines of MediaPlayer callbacks and `serviceScope.launch` in
`AssistantServiceAudio.kt`, reachable only through a live service with a real player, so no fake can drive it. The policy
moves to a NEW class in `:core` taking an injected `AudioPlayback`; the service keeps only what belongs to it - state
transitions, `voiceDuration` publication, the TTS generation counter - and **nothing else re-implements the guard**.
Expect this to recur wherever a ported file is mostly callbacks: the policy holds the tuned behaviour and deserves tests,
while the file keeps the shape its platform forces. It is a deliberate divergence from upstream's structure, so record it
in `docs/PROVENANCE.md` with the reason: upstream's shape is untestable, which is the same class of reason §9 gives for
the other replacements.

**A deliberate deletion carries a restore marker (2026-10-15).** Where a milestone ports a file but one upstream *call*
has no meaning yet - M1's `AssistantService.kt:2016` calls `removePersonaShortcut`, and M1 creates no `.desktop` entry
for a shortcut to be withdrawn from - the call is deleted with a comment reading
`DEFERRED TO M4: removePersonaShortcut(applicationContext, oldName)`, and the deletion is recorded in §9. Then
`grep -rn 'DEFERRED TO M' core/` is the complete restore list for the milestone each marker names, exactly as
`BORROWED FROM M1` is for declarations and `Stub` is for implementations. The marker is required precisely because the
alternative - M1 defining a no-op so the call survives - buys nothing when the feature is vacuous, and loses the mirror of
this rule, which is that a symbol a byte-identical COPY file calls must exist for real.

**The sequencer reports events; the service owns state (2026-10-15).** The complement of the extraction rule above.
The sequencer emits `started`, `finished`, `failed` and the progress value, and never writes service state. Where the
service needs to know something the sequencer sees — upstream enters SPEAKING when *chunk 0 starts playing*, not when
the sequence is created, and that state gates both the VAD pause and the UI — the answer is a callback, not a shared
field. The hook fires on the first chunk's start, which is what upstream's SPEAKING means, and it belongs in the
sequencer rather than in the synthesis lambda: synthesis finishing and playback starting are different events, and
conflating them puts a state transition in the wrong layer.

**Markers are lines, not phrases (2026-10-15).** Earned the first time a comment *mentioned* the convention: prose containing
the marker phrase made a grep count a mention as a marker, which would have failed a checkpoint on a file with nothing
borrowed in it. So every marker is a standalone line comment the phrase at the start of a comment line's content, colon optional, with the detail on the following lines - `// BORROWED FROM M1`, or ` * BORROWED FROM M1` inside a block comment, and all three checks are
anchored:

```
grep -rnE '^[[:space:]]*(//|\*)?[[:space:]]*BORROWED FROM M1\b' core/ platform/    # declarations borrowed from a m1 file
grep -rnE '^[[:space:]]*(//|\*)?[[:space:]]*DEFERRED TO M[0-9]\b' core/ platform/  # upstream calls deleted on purpose
grep -rnE '^[[:space:]]*(internal )?class Stub' platform/src/main           # seam implementations a milestone replaces
```

Prose may use those words freely - what matters is that only a marker starts a line that way. Run all three before a
checkpoint rather than at the end, and expect: zero BORROWED once m1 lands, zero DEFERRED until `AssistantService.kt`'s two
shortcut deletions are marked and then exactly two, and a `Stub*` for every seam whose callers landed in M1 while its implementation belongs to a later milestone. Each stub names its milestone, so the set is checked by reading the names rather than by counting: it has already grown from three to five as callers landed (1, 2, 3 and 6 for M2, 10 for M4).

**The shared-block rule, one layer up (2026-10-15).** The UI asks the question the service asked: M1's screens call about
twenty things that live in files whose *features* belong to M3-M5 — `ModelRowBody`, `FavoriteStar`, `EmptyNote`,
`LoadingRow`, `StaleNote`, `personaBackdropPath`, `readComfyWorkflowText`, `imageWorkflowDisplayName`. They come in with
M1, because a shared building block arrives with its **first caller**, exactly as a seam does. Only whole features are
cut — the Rooms panel, Skill settings, the spontaneous section, the image viewer, the tile — each with a
`DEFERRED TO M<n>` marker at its call site, so the markers are the list of feature gaps and nothing has a hole in it.
Cutting the edges literally instead would leave both holes and shared widget definitions reimplemented inline at the
call sites, which is how two copies of a widget begin to drift. Two details: where a small file is mostly the shared
leaf, take the file whole (`ImageWorkflowImport.kt`, 63 lines) rather than a fragment; and where a large file is taken
in part (`ModelScreens.kt` 467, `RoomScreens.kt` 996), say so in §7's row and in PROVENANCE — "partially ported in M1"
is a state worth stating rather than implying. Each marker names the milestone that restores it, and §10's M3-M5 lines
gain those feature names in the same commit, so the receiving milestone reads its own to-do list.

### 5a. Seam 8 is not in v1 (2026-10-15)

Credentials — provider API keys, server passwords and gateway credentials — live in the settings
file at `~/.config/celeste-linux/settings.json`, mode 0600. AGENTS.md §5 already permits the settings
file, D7 named the 0600 file as its fallback, and requiring a Secret Service daemon would be worse on
a bare X11 session where gnome-keyring or kwallet may not even be running. On a single-user desktop
this gives the same protection against other local users that libsecret would; what it gives up is
encryption at rest against root or a leaked backup.

**The condition that made this acceptable is the warning D7 asked for: the settings UI must say where
credentials are stored.** libsecret comes back only if something needs a secret that must not sit in
a file the user can read.

### 5b. Seam 15: the image codec (2026-10-15)

Upstream decodes and re-encodes persona artwork with Android `BitmapFactory` and `Bitmap.compress`;
on Linux that is `javax.imageio`, which costs no new dependency. It is a seam rather than an inlined
detail because more than one call site needs it — settings artwork import now, chat image attachments
later — and because the constants are behaviour: JPEG quality 88, and ICON_MAX_DIM 512,
BACKGROUND_MAX_DIM 1080, MESSAGE_IMAGE_MAX_DIM 1024.

## 6. Audio subsystem (the one genuinely new component)

**Capture** — keep the behaviour, replace the device access. `VADAudioRecorder` is rewritten around
a subprocess that emits raw PCM, but every constant and every invariant it encodes is preserved,
because each one was tuned against real speech:

- 16 kHz, mono, s16le, 512-frame (32 ms) chunks.
- Silero VAD per chunk through ONNX Runtime; speech if `prob > 0.5` for 3 consecutive chunks.
- 3-chunk pre-roll ring buffer, prepended on trigger — the comment in the source is explicit that
  dropping it clipped short words ("Hi", "Go") badly enough to confuse STT. Keep it.
- Speech end after 60 silent chunks (~2 s), then write a WAV (the existing header writer, kept).
- A single lock around the sample buffers, never held across file I/O or a suspension point.
- Pause while the reply is spoken, resume on the service's signal or the hotkey — the phone already
  behaves this way, and on a desktop it is also what keeps the microphone from hearing the speakers.

**Playback** — a `pw-play` process per utterance, one at a time, with the same
"current playback" guard the Android code uses around `currentAudioTrack`, so a second utterance
cancels the first. "Audio focus" becomes: stop our own playback, and optionally pause other media
players over MPRIS (`playerctl pause`) if the user enables it. This is weaker than Android's focus
guarantee and is documented as such in the settings UI rather than pretended away.

**Why subprocesses rather than JNI.** They work identically on both distros and on both PipeWire and
a PulseAudio-alias setup, they need no native build in the project, and they make device selection a
single `--target` argument. The cost is a process spawn per utterance (~10 ms) and a copy. If that
ever shows up in the latency budget, the seam is narrow enough to swap for a JNA binding to
`libpipewire`.

**Seam 2 is a handle, not a suspension (2026-10-15).** Decided from the call sites in `AssistantServiceAudio.kt`, which is
the first caller of seams 2, 3 and 6. `AudioPlayback.playFile` and `playPcm` return a `PlaybackHandle` carrying `stop()`,
`positionMs`, `durationMs` and completion/error callbacks; the chunked sequence stays a sequence, and the one-playback
guard stays in the caller (`:core`), mirroring the `currentAudioTrack` identity checks that the upstream comments exist
to explain. Four things the interface must get right:

- **`positionMs` is elapsed-since-start capped at `durationMs`, not a hardware position** - a `pw-play` subprocess reports
  none. `durationMs` comes from the WAV header that the ported `wavDurationSeconds` already reads, so the progress fraction
  the karaoke highlight and the reveal need never depends on the player.
- **Completion distinguishes three cases:** exited zero without being stopped (`onFinished`), failed (`onError`), and
  stopped on purpose (neither). A cancel that reported "finished" would advance the chunk sequence - the bug class the
  upstream identity checks exist for.
- **`playPcm` assembles a temporary WAV and reuses the file path**, unless the eSpeak call site genuinely needs incremental
  streaming, in which case say why where it matters. One mechanism, one place to sweep temp files.
- **Callbacks are dispatched on a defined dispatcher** (the UI reads the progress value) and `stop()` is safe to call from
  it while the process runs.

The payoff is testability, and that is why this shape beats a suspending one: a fake handle in `:core`'s tests makes the
chunked sequencing, the cancel-previous guard and the progress arithmetic provable in M1 - with no audio hardware and no
M2 implementation - instead of leaving 240 lines of tuned behaviour unchecked until the stubs are replaced.

**Latency budget to verify in M2:** capture start to first chunk < 50 ms; utterance end to WAV on
disk < 100 ms; WAV to `/transcribe` to reply to first audio out is dominated by the gateway and the
model.

## 7. File-by-file migration map

All 46 files of `app/src/main`, by measured size. **COPY** = ported verbatim, **ADAPT** = mechanical
edits, **PORT+** = real work but the logic survives, **NEW** = new implementation.

| File | Lines | Verdict | What happens |
|---|---:|---|---|
| `AssistantService.kt` | 3371 | PORT+ | Drops `: Service()`; `onStartCommand`/`onDestroy`/`startForeground` become `start()`/`shutdown()`; the foreground notification becomes tray state + `Notifier` |
| `AssistantServiceChat.kt` | 2951 | ADAPT | `Context` to `AppEnv`, `Log` to logger; turn orchestration untouched |
| `SettingsScreens.kt` | 2423 | ADAPT | `Toast` to snackbar; pickers to `FileDialogs`; clipboard to AWT |
| `MainVoiceScreen.kt` | 2097 | ADAPT | Runtime permissions to `AudioDevices` picker; insets/edge-to-edge to window chrome |
| `PersonaEditor.kt` | 1579 | ADAPT | Image pick (SAF) to file dialog; the pin/workflow picker stays |
| `AssistantServiceAudio.kt` | 1379 | PORT+ | Seams 1/2/3/6. **Arrives in M1 with the chat flow that calls it** (PLAN.md §5 rule); its `:platform` implementations stay `Stub*` until M2. Its TTS sequencing is **extracted** to the new `TtsChunkSequencer` class and tested against a fake playback handle (§5 rule), so the surviving third is smaller than a straight port implies |
| `SettingsManager.kt` | 1356 | PORT+ | Encrypted prefs/Keystore to seams 7/8; keeps corruption recovery, migration, backup read/write |
| `RoomScreens.kt` | 996 | ADAPT | Compose only |
| `Models.kt` | 984 | **COPY** | Data classes + the persisted colour schema; takes `ui-graphics` in `:core` (§7a) |
| `ChatComponents.kt` | 887 | ADAPT | Share sheet to `--send-text`/clipboard; Coil file models unchanged |
| `ModelCatalog.kt` | 708 | **COPY** | Pure |
| `SkillScreens.kt` | 696 | ADAPT | Compose only |
| `AssistantServiceRoomTurns.kt` | 671 | **COPY** | Pure |
| `AssistantServiceGateway.kt` | 655 | ADAPT | TOFU cert store to a file (seam 14) |
| `AssistantServiceSpontaneous.kt` | 648 | **COPY** | Pure |
| `CelestialHud.kt` | 620 | ADAPT | Compose drawing; becomes the voice screen's state indicator |
| `AssistantServiceContext.kt` | 484 | ADAPT | `Context` to `AppEnv` |
| `SpontaneousDeliveryWorker.kt` | 483 | PORT+ | `Worker` becomes a coroutine in the service core (seam 10); the body is already pure |
| `ModelScreens.kt` | 467 | ADAPT | Compose only |
| `MainActivity.kt` | 434 | PORT+ | Becomes the window + composition root; deep-link extras become CLI flags |
| `SpontaneousScheduler.kt` | 434 | **COPY** | Pure — this is why scheduling is nearly free |
| `SpontaneousScreens.kt` | 393 | ADAPT | Compose only |
| `AssistantServiceImageGen.kt` | 370 | **COPY** | Pure |
| `ComfyJobs.kt` | 343 | **COPY** | Pure |
| `VADAudioRecorder.kt` | 322 | NEW | Subprocess capture; constants and pre-roll preserved (§6) |
| `AssistantServiceComfy.kt` | 317 | **COPY** | Pure |
| `AssistantServiceRooms.kt` | 284 | **COPY** | Pure |
| `ModelPicker.kt` | 281 | ADAPT | Compose only |
| `ComfyWorkflow.kt` | 260 | **COPY** | Pure — token substitution semantics must not drift |
| `UIComponents.kt` | 248 | ADAPT | Compose only |
| `PersonaShortcuts.kt` | 238 | NEW | One `.desktop` entry per persona (replaces launcher shortcuts) |
| `WelcomeScreen.kt` | 206 | ADAPT | Compose only |
| `FullScreenImageViewer.kt` | 183 | ADAPT | Compose only |
| `SpontaneousNotifications.kt` | 177 | NEW | `Notifier` (seam 9) |
| `MessageTimestamps.kt` | 162 | **COPY** | Pure |
| `ServerIdentity.kt` | 139 | **COPY** | Pure |
| `EspeakEngine.kt` | 131 | ADAPT | JNA to the distro `libespeak-ng.so`, or the `espeak-ng` CLI |
| `VADDetector.kt` | 112 | ADAPT | Same ONNX API, JVM artifact |
| `SharedContent.kt` | 105 | NEW | `--send-text` / `--send-file` + `SendTo` entries |
| `TurnSink.kt` | 98 | **COPY** | Pure |
| `CelesteTileService.kt` | 79 | NEW | Tray item (seam 11) |
| `ImageWorkflowImport.kt` | 63 | ADAPT | File dialog instead of SAF |

Totals: **12 files / 4,434 lines copied verbatim**, 26 files adapted, 8 files rewritten.

### 7a. Arrival order, and one measurement correction (2026-10-15)

Reported by the agent during the first M0 attempt, and confirmed by compiling.

**The purity test was wrong.** The COPY column was derived by grepping for `import android.*`. Three
of those files use `android.util.Log` **fully qualified**, so no import line exists and the grep could
not see it: `SpontaneousScheduler.kt:410`, `AssistantServiceRoomTurns.kt` (x6) and
`AssistantServiceSpontaneous.kt` (x6). A file that touches `android.*` cannot compile on the JVM, so
"pure, copy verbatim" was wrong for those three. The fix is a shim, not a demotion — see below.

**Half the COPY list is not standalone: it is extension functions on `AssistantService`.** Upstream
split one ~15k-line service across files by concern, so `AssistantServiceComfy.kt:189`,
`AssistantServiceRooms.kt`, `AssistantServiceRoomTurns.kt`, `AssistantServiceSpontaneous.kt`,
`AssistantServiceImageGen.kt` and `TurnSink.kt` cannot compile without `AssistantService.kt`
(3,371 lines, PORT+) and its `Context` to `AppEnv` work, which §10 schedules for M1. M0 cannot carry
them without importing M1's core (~10k lines) into one commit.

**The rule that fixes both: a COPY file keeps its verdict, but each one has an arrival milestone.**

| Group | Files | Lines | Arrives | Needs first |
|---|---|---:|---|---|
| **m0** | `ModelCatalog.kt`, `ComfyJobs.kt`, `ComfyWorkflow.kt` | 1,311 | M0 | nothing — compile clean today |
| **m0** | `MessageTimestamps.kt`, `SpontaneousScheduler.kt` | 596 | M0 | `Models.kt` + the `android.util.Log` shim |
| **m0** | `ServerIdentity.kt` | 139 | M0 | `Models.kt`, the shim, `normalizeGatewayUrl` |
| **m1** | `AssistantService{Comfy,Rooms,RoomTurns,Spontaneous,ImageGen}.kt`, `TurnSink.kt` | 2,388 | M1 | `AssistantService.kt` |

The m0 group is six files, 2,046 lines. The m1 group is six files, 2,388 lines, and the 12 add to the
4,434 originally claimed — the count was right, the ordering was missing.

`tools/sync-from-android.sh --group m0|m1` copies one group; the bare copy is refused, because
copying a file whose dependency has not been ported produces a source set that does not compile.
`--check` reports a file that has not arrived yet as *not yet taken* rather than as a fault.

**The `android.util.Log` shim.** One file, `core/src/main/kotlin/android/util/Log.kt`, defines
`d/i/w/e/v/wtf(tag, msg)` and their `(tag, msg, Throwable)` forms, forwarding to the project logger.
It exists so that a COPY file is never edited for a logging call: logging is behaviourally
invisible, byte-identity is preserved, and demoting three files to ADAPT would put a permanent diff
on files we would otherwise take verbatim. It is the **only** file in this project allowed to live
under an `android.*` package, and it is not a compatibility layer — nothing else is added to it.

**`normalizeGatewayUrl`.** Upstream defines it inside `AssistantServiceGateway.kt:118`, a 655-line
file that references `AssistantService` 22 times and so cannot arrive before M1. It is defined now in
a small NEW file, `GatewayUrl.kt`, with upstream's semantics (`raw.trim().trimEnd('/')`). When
`AssistantServiceGateway.kt` is ported, its copy of the function is dropped in favour of this one:
one definition, and the removal is an ADAPT recorded in `docs/PROVENANCE.md`.

**`Models.kt`.** §7 calls it ADAPT only because of a Compose import. If that import is annotation-only
(`androidx.compose.runtime.Immutable` or similar), keep the file byte-identical and add
`androidx.compose.runtime:runtime` to `:core`: one dependency buys a permanent no-diff. Decide when
the import is actually visible, and record which way it went.


[Amended 2026-10-15, both points raised by the agent and both upheld.] **Models.kt is COPY, not ADAPT.** Its Compose dependency is not an annotation: `androidx.compose.ui.graphics.Color` is part of the *persisted* settings schema (`object ColorSerializer` at line 13 persisting `value.toArgb()`, `@Serializable` fields at 134/140/248, about 35 ARGB literal sites). `:core` therefore takes `org.jetbrains.compose.ui:ui-graphics`, pinned to exactly the version `:desktop` resolves, because a version skew there would put two different `Color` classes on the classpath. That dependency is worth its cost: the alternative, a project-owned colour type, is a permanent diff against the most upstream-volatile file in the app, and a hand-rolled `Color` risks silently different persisted values, which would break importing an Android backup, a stated feature. A comment in the `:core` build file must say why the dependency is there, so nobody tidies it away. **`ComfyWorkflow.kt` stays in m0, with one borrowed declaration.** Its only m0-to-m1 reference is `IMAGE_GEN_NEGATIVE_PROMPT` (the default for `ComfyPromptValues.negative`), defined at `AssistantServiceImageGen.kt:78`. Moving the file to m1 would exile the best-tested logic in M0 (`ComfyPromptFillingTest`, `ComfyWorkflowValidationTest`); editing the file would be a permanent diff. So the constant is borrowed the same way `normalizeGatewayUrl` is: both live in one file, `BorrowedFromM1.kt`, whose header states the rule - every declaration there is defined upstream in a m1 file and is duplicated only so the m0 files compile; when the upstream file arrives, that declaration is deleted in the same commit and recorded in `docs/PROVENANCE.md`. Nothing else is ever added there.

[Revised the same day: the borrowed declarations live in separate files named for what they hold — `GatewayUrl.kt` and `ImageGenConstants.kt` — not in one file called `BorrowedFromM1.kt`. The safety property is kept by a marker rather than by a file name: every such file carries the exact line `BORROWED FROM M1` in its header and names the upstream file that will absorb it, so the M1 checklist is one command, `grep -rl BORROWED FROM M1 core/`, and it works however many of them appear.]

[m0 arithmetic, 2026-10-15: `Models.kt` is COPY, so m0 is **seven** upstream files and **3,030** lines — `tools/sync-from-android.sh --group m0` copies all seven — plus two hand-written files, `android/util/Log.kt` and `BorrowedFromM1.kt`.]

[Tests arrive the same way: `TEST_SOURCES` in the sync script copies pure upstream JVM tests byte-identical into `core/src/test/kotlin/`, under the same groups and the same `--check` coverage. A test needing retargeting stays off that list: adapted on purpose, recorded in `docs/TEST-MAP.md`. M0.4 starts with `ComfyWorkflowValidationTest.kt`, `ComfyPromptFillingTest.kt` and `ComfyFixtures.kt`. — and this is the pattern for all 45 upstream JVM test files.]
**Consequence for §10.** M0's upstream file set is the six m0 files (2,046 lines) plus `Models.kt`,
the shim and `GatewayUrl.kt`. M1 gains the six m1 files (2,388 lines) unchanged, since they arrive
with the service they extend.

**Seeding a PORT+ file (2026-10-15).** `tools/sync-from-android.sh --seed <name>` places one PORT+ file as a verbatim
starting copy, so the port is reviewable as an unedited `diff -u` against upstream — `AssistantService.kt` currently
reads 755 lines removed and 495 added — rather than retyped, which would discard that diff and put transcription errors
into 3,371 lines that only the compiler partly checks. Two constraints: `PORT_SOURCES` must stay disjoint from
`COPY_SOURCES`, because a file that is byte-identical forever must never be seedable; and the seeded content is a
working-tree step, never a commit, so `:core` never holds the Android original — the port replaces it before the
cluster commit lands.

## 8. Feature parity matrix

| Feature (upstream README) | Milestone | Notes |
|---|---|---|
| Hands-free voice with on-device VAD | M2 | The heart of the port |
| Whisper STT + Kokoro TTS via Voice Gateway | M2 | HTTP, unchanged |
| Per-persona TTS choice (gateway / system / local) | M2 | `spd-say`, `espeak-ng`, gateway |
| On-device script-based TTS language routing | M2 | Pure Kotlin, copies over |
| Push-to-talk + hotkey | M2 | New; replaces proximity barge-in |
| Providers: Ollama, Anthropic, OpenAI, DeepSeek, Google, OpenAI-compatible | M1 | HTTP, unchanged |
| Personas, persona artwork, per-persona servers/pins | M1 | Coil 3 is multiplatform |
| Conversation history, usage tracking, thinking mode | M1/M5 | Unchanged |
| Translator mode (16 languages) | M5 | Persona + gateway models, unchanged |
| RAG, SearXNG web search, file attachments | M5 | HTTP, unchanged |
| Image generation: ComfyUI + A1111, workflow tokens | M3 | Pure logic copies over |
| Per-character workflow binding (persona to template id) | M3 | Unchanged |
| Image viewer with pinch-zoom/pan | M3 | Pointer gestures instead of touch |
| Skills and skill triggers | M5 | Unchanged |
| Rooms (multi-persona group chat) | M4 | Unchanged |
| Spontaneous messages | M4 | Scheduler is pure; the driver is new |
| Export / import backup (incl. Android file) | M3 | Import path already exists |
| Encrypted settings | M1 | libsecret instead of Keystore |
| Certificate pinning / TOFU for self-signed certs | M1 | Same policy, file-backed store |
| Update check against GitHub Releases | — | **Dropped in v1** (no releases to check yet) |
| Quick-settings tile | M4 | Tray item |
| Per-persona launcher shortcut | M4 | `.desktop` entry per persona |
| Share target (text/image into the composer) | M3 | `SendTo` + CLI flags |
| Proximity barge-in, phone-call guard | — | **Dropped**: no phone calls on a desktop |

## 9. Desktop substitutions (documented deviations)

| Android behaviour | Linux behaviour | Why |
|---|---|---|
| `AcousticEchoCanceler` on the mic session | Headphones or push-to-talk; optionally a PipeWire echo-cancel sink | No OS counterpart; decision recorded in README |
| Transient audio focus with retry-on-DELAYED | Stop our own playback; optional `playerctl pause` | No focus stack on PipeWire |
| Earpiece/Bluetooth route toggle | `wpctl` sink selection, remembered per persona | Different model, same intent |
| Foreground service + persistent notification | Tray icon while listening; systemd user unit for autostart | Desktop concept |
| `WorkManager` (survives reboot, batched) | In-process timer, exact; systemd timer if the app must wake a sleeping session | A desktop may be off too, and this is honest about it |
| Runtime permission prompts | Device picker in settings; mic access is granted by the session | No permission model for a normal user app |
| Launcher shortcuts (added and withdrawn per persona) | per-persona `.desktop` entries (M4) | The two call sites in `AssistantService.kt` are deleted in M1, each marked `DEFERRED TO M4` — the feature is vacuous there, since M1 creates no entry to withdraw, and M4 restores the calls when `PersonaShortcuts.kt` lands |
| Android Keystore | libsecret / 0600 fallback | Available on both distros |

## 10. Milestones

Estimates are focused working days for one developer at this code's quality bar (dense comments,
tests for behaviour). They assume the seams in §5 are respected and the copied files stay copied.

**M0 — Skeleton and build (2–3 d).** Gradle project, Compose Desktop plugin, JDK 25 toolchain
*(deviation, 2026-10-15: was JDK 21. The build box has 17, 25 and 27; Kotlin 2.4.10's compiler
accepts jvmTarget at most JVM_26, so 27 cannot be a target and 25 — a Temurin LTS already present
— was chosen over installing 21. See the root `build.gradle.kts`.)*, `:core` with the six m0 COPY files (PLAN.md §7a) plus the Log shim, `GatewayUrl.kt` and `Models.kt` compiling, and the ported JVM tests running, a headless `--selftest` that
prints the resolved paths and audio devices, `tools/sync-from-android.sh`, `docs/PROVENANCE.md`,
`.gitignore`, `git init`.
*Accept:* `./gradlew :core:test` passes the copied suites on Arch; the same in a Debian container.

**M1 — Text app (12–15 d, revised mid-milestone).** `AppEnv`, seams 7/13/14 plus the new 15, the settings blob and its recovery, personas,

[2026-10-15: `AppEnv` is designed HERE, not in M0.5. M0.5 resolved the XDG paths in `:platform` and the selftest exercised them; the interface itself is derived by reading what the ported files actually ask of `Context` — paths, the logger the `android.util.Log` shim routes into, the process launcher from D6, device info — rather than by guessing a shape a milestone early. Promoting the resolved-paths holder into `:core` is the mechanical first step, and `AppEnv` takes it as a parameter, so `:core` never sees a `:platform` type.]
[Revised mid-M1, 2026-10-15: M1.1-M1.3 were all foundation - AppEnv, AppLogger, SettingsStore, ImageCodec - and no ported :core file had landed yet. That is the same boundary-discovery pattern M0 showed, so M1 is re-estimated at 12-15 days. Do NOT re-baseline M2-M7 from it: these foundations are inherited by every later milestone, and only M2 audio seams are comparable new-code work.]
[Cluster landed, 2026-10-15. The two hand-picked estimates were off in opposite directions and the
real figures belong here: `AssistantService.kt` 3,371 → **3,113** (789 removed, 531 added) against a
"~2,850" estimate, and `AssistantServiceChat.kt` 2,951 → **3,007** — it GREW by 56 lines. The second
is the more useful correction: its Android surface turned out to be one `Uri` parameter list, eleven
`BuildConfig.DEBUG` reads and two Base64 calls, so there was little to delete, while every deleted
call needed a comment saying what had replaced it. A port that shrinks every file is a port that
deleted the comments explaining it. Per-file removals and additions for all 26 files, plus a
deleted-thing-by-deleted-thing table, are in docs/PROVENANCE.md.

[Cluster inventory, 2026-10-15: about 8,000 lines - `AssistantService.kt` (3,371 to ~2,850), `Chat.kt` (2,951), `Audio.kt`
(1,379 to ~600), `Gateway.kt` (655), `Context.kt` (484), plus `VADAudioRecorder.kt` (322, NEW) and `VADDetector.kt` (112)
pulled in by real call sites in the service, `EspeakEngine.kt` (131 to ~80, its asset copier deleted since no Android
assets come across), and the six m1 COPY files byte-identical. `SpontaneousDeliveryWorker.kt` is NOT in the cluster:
the worker *file* stays out of the cluster, but the scheduler capability it wraps does not: `AssistantServiceSpontaneous.kt`, byte-identical, calls `isSpontaneouslyArmed`, `armSpontaneousWork` and `disarmSpontaneousWork` three times each, so seam 10's interface and a `StubScheduler` land in M1 — see the seam-10 row in §5.
Consequence: M2's remaining scope is the seam implementations and the hands-free loop, nothing else.]

[One-commit cluster, 2026-10-15: the four service files (`AssistantService`, `Chat`, `Audio`, `Gateway`) plus the six
m1 COPY files cannot be split — nothing in that set compiles alone — so they land together at roughly 7,000 lines
under the exception in AGENTS.md §7. Two things must be true at the end of it: both `BORROWED FROM M1` declarations
are gone (`GatewayUrl.kt` and `ImageGenConstants.kt` deleted, which the compiler insists on anyway since upstream
declares the same symbols in the same package — the deletion must be deliberate, not incidental), and the only
`Stub*` classes left in `:platform` are ones M2 will genuinely replace.]

[M1 boundary revised again, 2026-10-15: `AssistantServiceAudio.kt` (1,379 lines) moves into M1. Of its 24 top-level functions the six m1 COPY files call exactly one, but `AssistantService.kt` needs seven and `AssistantServiceChat.kt` seven more - the chat flow ends by calling `playResponse` and its finally calls `abandonAssistantFocus`, so chat and voice are not separable here. Porting it in M1 with `Stub*` platform implementations is the same work M2 would have done, moves earlier; it edits `AssistantService.kt` and `Chat.kt` once instead of twice, and keeps the twelve-COPY-files-compiling invariant at M1 rather than pushing it to M2. M1 acceptance stays text-only - nothing is claimed about voice until M2. Shift: M1 to about 15-20 days, M2 down to 3-6, total unchanged.]
servers, providers, chat orchestration, main window, settings/persona screens, image-gen config
screens. Port the JVM tests.
*Accept:* a real conversation with Ollama and with one cloud provider end to end; settings survive a
restart; an Android backup file imports and shows its personas.

**M2 — Voice (3–6 d, revised: platform work only).** No `:core` porting here — the audio engine and its callers landed in M1. Implement seams 1/2/3/6 for real, replacing the `Stub*` classes (`grep -rn 'Stub' platform/src/main` is the list), then add capture and VAD (`VADAudioRecorder.kt`, `VADDetector.kt`), `/transcribe`, the three TTS routes, the hands-free loop, push-to-talk, source/sink selection, mute and cancel-playback.
hands-free loop, push-to-talk hotkey, source/sink selection, mute, cancel-playback.
*Accept:* ten consecutive hands-free exchanges with headphones, no manual input, no dropped
utterance, and the latency budget in §6 met.

**M3 — Images and files (3–4 d).** ComfyUI + A1111, workflow library and per-character binding,
image viewer, attachments, backup export/import round trip.

[Receives from M1, 2026-10-16 — the feature gaps M1's markers name, so this milestone reads its own
to-do list rather than reconstructing it from a grep. Each is a `DEFERRED TO M3` comment at its call
site; `grep -rn 'DEFERRED TO M3' core/` finds them. **Rooms:** `RoomChatArea` (MainVoiceScreen),
`RoomPickerRows` and the `"Rooms" -> RoomSettings(...)` branch (SettingsScreens), and with them the
rest of `RoomScreens.kt` — the editor, the member list, the round-status line and the pure room
helpers above them. **Skills:** the `"Skills" -> SkillSettings(...)` branch and the Skills section in
the persona editor (`SkillSettings`, `PersonaSkillSection`, and `SkillScreens.kt` with them).
**Models:** `ModelsScreen` and its `ModelsHeader`/`filterEmptyMessage`/`ModelRow` helpers, which are
the half of `ModelScreens.kt` M1 did not take — the shared widgets came across, the screen did not.]

The counts, settled by the anchored grep rather than by a mention count:

```
grep -rnE '^[[:space:]]*(//|\*)?[[:space:]]*DEFERRED TO M[0-9]' core/ platform/
```

gives **15 markers — 6 × M3, 6 × M4, 3 × M5**. A plain `grep -o 'DEFERRED TO M[0-9]'` gives 18,
because three of them are *mentioned* rather than carried: the file headers of `ModelScreens.kt`,
`RoomScreens.kt` and `SpontaneousScreens.kt` each say "the call site carries the `DEFERRED TO M3/M4`
marker" while the marker itself is elsewhere. The unanchored number is the one to distrust — it was
published as 18 once and as a 7/5/3 split before that, and neither was the ledger. Nothing here is a hole in a screen: every cut is a whole feature, and the
screens that host them render without them rather than around them.
*Accept:* an image generated from a persona against the real ComfyUI on buildhost and rendered in the
chat; the same workflow tokens (`%PROMPT%`, `%NEGATIVE%`, `%SEED%`) behave as on Android.

**M4 — Rooms, spontaneous, daemon (4–6 d).** Room screens, spontaneous scheduler in the core, tray,
notifications, systemd user unit, autostart `.desktop`, per-persona `.desktop` entries.

[Receives from M1, 2026-10-16 — six `DEFERRED TO M4` markers. **Tray:** the Quick Settings tile
row (`QuickSettingsTileRow()`, the whole composable) and the `"Shortcuts"` section that held it; §9
drops the tile and the tray is its equivalent, and the user's one-tap voice entry is what the section
comes back for. **Spontaneous:** the Spontaneous Messages section in the persona editor
(`PersonaSpontaneousSection`) — the *controls*; the eligibility verdict that gates them is already in
M1, because a persona that has become ineligible must not keep speaking on a schedule nobody can
reach the controls for. **Shortcuts:** the "add to home screen" button in the persona list, and the
two `removePersonaShortcut` call sites in `AssistantService.kt` that §9 already records.]
*Accept:* a spontaneous message arrives as a desktop notification while only the tray process runs;
the unit starts at login and stops cleanly.

**M5 — The rest (3–4 d).** Translator mode, skills, RAG, web search, usage, thinking mode.

[Receives from M1, 2026-10-16 — three `DEFERRED TO M5` markers, all in `ChatComponents.kt`, plus the
share target §2 leaves out of v1. **Image actions:** the "Save to gallery" button (upstream inserted
into `MediaStore.Images` through a resolver, with three `Build.VERSION.SDK_INT` guards) and the
full-screen image viewer. **Share:** the message menu's Share entry, which §7 substitutes with a
`--send-text` command-line flag — and which is also what will want the one-shot handoff slot
`MainVoiceScreen` no longer takes a parameter for.]
*Accept:* a spoken translation round trip; RAG and web search answers.

**M6 — Packaging (3–5 d).** `PKGBUILD`, `debian/`, jpackage app image with a bundled runtime,
desktop entries, icons, user unit, README install section.
*Accept:* install, run and uninstall in a clean Arch container and in a Debian 13 container.

**M7 — Hardening (5–8 d).** Remaining test porting, desktop UI tests, a fake gateway for the voice
loop, a soak test for start/stop/restart leaks, error paths (gateway down, no mic, no Secret
Service, ComfyUI restarted mid-job), `docs/TEST-MAP.md`.
*Accept:* the soak test runs an hour of start/stop cycles with no leaked processes or file handles.

**Total: 33–50 focused days** (about 4–8 weeks full time, 3–5 months at evenings and weekends).
A first usable milestone is **M2**: roughly **15–23 days**.

## 11. Test strategy

- **Port the 45 JVM test files (8,066 lines) in M0/M1.** They are the executable specification for
  the token substitution, the resolution/precedence rules, the settings recovery and the
  timestamps. They must pass before any UI work starts.
- **The 59 instrumented files (20,889 lines) are triaged in `docs/TEST-MAP.md`:** Compose UI tests
  move to the desktop `runComposeUiTest` runner; `AndroidJUnit4`/`WorkManager`/
  `TestWorkerBuilder`/shortcut/permission tests are rewritten against the seams or deleted with a
  written reason.
- **New tests:** a fake Voice Gateway (a local HTTP server serving `/transcribe` and `/synthesize`
  from fixtures) so the whole voice loop is testable without a mic; WAV fixtures of speech and
  silence against the VAD thresholds; a `--selftest` audio probe using PipeWire's null sink; a soak
  test for process leaks around capture/playback and for the service core's start/stop.
- **Both distros, in containers, in CI:** `gradle test` plus a packaging smoke test that installs
  the artefact and runs `celeste-linux --selftest`.

**What M1 covers, and what it does not (2026-10-15).** Covered: the ported upstream JVM tests (workflow tokens, settings
recovery), the new platform tests (XDG resolution, the JSON settings store, the image codec) and - once the sequencer is
extracted - TTS sequencing, cancel-previous and the progress arithmetic against a fake playback handle. **Not covered in
M1: the service's own state transitions.** `AssistantService.kt` is touched by no test until M2's hands-free acceptance
run and M7's soak test, because upstream fused its logic with live Android callbacks and constructing it needs every
collaborator faked. That is a known, accepted gap rather than an oversight: the M2 acceptance criterion is the first
thing that exercises it.

[Interop test required, 2026-10-15: the Android-backup import is proven with a fixture whose artwork payload is
MIME-wrapped base64, because the port deliberately keeps the MIME decoder on the backup path while the word-timestamp
header uses the plain one. That fixture is what protects the promise that an upstream backup imports unchanged - the same
interop requirement that justified `ui-graphics` in `:core` - and it is the test that would catch either Base64
substitution being made the wrong way round.]

## 12. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| No echo cancellation (D8) | Speaker playback gets transcribed | Headphones or push-to-talk; PipeWire echo-cancel sink as an option |
| Subprocess audio adds latency/jitter | Sluggish or clipped utterances | Measure in M2; the seam allows a JNA `libpipewire` swap |
| AWT tray on Wayland needs an appindicator host | Tray icon missing | Tray is optional; hotkey and window still work; documented in `docs/DEVICES.md` |
| Compose Desktop needs working GL | Window fails on exotic drivers | Document `SKIKO_RENDER_API=SOFTWARE` |
| App image carries the build host's glibc | `.deb` broken on Debian | Build the `.deb` on Debian (container), the Arch package on Arch |
| ONNX Runtime JVM jar is large | Fat package | Bundle it; measure the app image in M6 |
| Upstream app changes | Port drifts | Copied files verbatim + `docs/PROVENANCE.md` + `tools/check-upstream.sh` diff report |
| `internal` visibility across modules | Won't compile if split | One `:core` module (D4) |
| No Secret Service daemon on the box | Credentials cannot be stored | 0600 file fallback, warned about in the UI |
| systemd user unit without linger | Daemon dies at logout | Document `loginctl enable-linger`; the unit is optional |
| Phone and desktop settings diverge | Confusion | Documented; the backup file is the only bridge |
| Gradle 9.8.1 warns the build uses features removed in Gradle 10 | The build breaks on a future Gradle upgrade, possibly mid-milestone | The wrapper is pinned, so nothing moves by accident; deal with it deliberately at M6 packaging, not in the middle of porting |

## 13. Upstream sync policy

1. `../VoiceAssistant` is **read-only**. `tools/sync-from-android.sh` only reads from it.
2. Files marked **COPY** in §7 are copied byte-for-byte. Any needed change means moving the file to
   the ADAPT/PORT+ column deliberately, and recording why in `docs/PROVENANCE.md`.
3. `docs/PROVENANCE.md` records, per file, the upstream commit hash it was taken from.
4. `tools/check-upstream.sh` diffs `core/` against the upstream tree and prints what changed
   upstream, so a sync is a reviewable list rather than a surprise.

## 14. Open questions

1. **Binary and package name** — `celeste-linux` as the package with `celeste` as the command, or
   something else?
2. **Fresh `git init` here?** Recommended: this directory becomes its own repository, upstream stays
   untouched and unlinked.
3. **Default interaction on the desktop:** always-listening while the app runs, or a hotkey that
   arms listening for the session? (Affects M2 and the hotkey design.)
4. **v1 scope:** is rooms + spontaneous messaging wanted in the first release, or is
   text + voice + images enough to ship and the rest follows?
5. **Local offline TTS:** rely on the distro's `espeak-ng` (thin, robotic, always available), or
   ship an optional piper voice download (better quality, about 60 MB per voice) alongside the
   gateway's Kokoro?
6. **Default gateway:** ship placeholder URLs or pre-fill the Voice Gateway / Ollama addresses of
   your own boxes (no credentials in any file)?
7. **Update check:** drop it for v1 (recommended, nothing published yet) or implement it against a
   releases endpoint?
