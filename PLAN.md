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
- **Anything a desktop makes possible that a phone does not** — local speech, a wake word, screen understanding, system integration — is collected in [docs/BEYOND-THE-PORT.md](docs/BEYOND-THE-PORT.md) and is deliberately outside M0–M7. The port finishes before any of it starts.
- **Filesystem tools for the assistant** are new capability rather than a port item: nothing upstream reads or writes arbitrary files, so they cannot arrive as a ported file and must not be built inside another milestone. The user wants them so Celeste can act as a local agent on this machine; they are recorded as M8 in §10.
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
| 1 | `AudioCapture` | `AudioRecord` + `AcousticEchoCanceler` | `pw-record` → `parec` → `arecord`, raw s16le mono 16 kHz on **stdout, not a WAV on disk** — the VAD loop needs 32 ms every 32 ms and needs the newest one now, so a file it read back would be a file it was also writing. When the caller names no device and the session manager has no default, `open` retries with the first enumerated input and logs which one; a **named** device that will not open returns null instead, because silently substituting a different microphone is how a persona ends up listening to the wrong room. `read` returning 0 ends the stream and stops the loop — a subprocess can exit where `AudioRecord.read` would have blocked, and an ended stream that kept looping is a dead microphone as a hot loop. 2026-10-16: the stream also has `drain()`, which discards the backlog a paused reader lets accumulate — the pipe fills with the room as it was *before* the reply, and the VAD should judge the present, so the resume after a reply throws it away |
| 2 | `AudioPlayback` | `AudioTrack` + marker listener + `AudioFocusRequest` | `pw-play` → `paplay` → `aplay`; "finished" = process exit |
| 3 | `AudioDevices` | `AudioManager`, earpiece/Bluetooth routing | `pactl` first with `wpctl` as the fallback (2026-10-15 — the id has to be accepted by whichever player runs, and only the PulseAudio node name is taken by both `pw-play --target` and `paplay --device`); `--target` on capture and playback; **monitor sources are excluded from `inputs()`**, because a monitor feeds the system's own playback into the recogniser and fails as an incomprehensible transcript rather than as an error. **The default marker comes from the layer the spawned players actually consult** — `wpctl` — and not from `pactl get-default-source`, which reads PulseAudio's separate database: on buildhost the two disagree, `pactl` naming a source that a bare `pw-record` then refuses to open, so an honest query answered the wrong question and labelled the one input the recorder will not use. The join is on `node.name`, because PipeWire's global ids shift as nodes come and go |
| 4 | `Vad` | ONNX Runtime Android (`ai.onnxruntime.*`) | **Not a seam** (2026-10-15) — an artifact substitution, not an interface: the same `ai.onnxruntime.*` classes exist on both sides, and only the model origin changes, from the Android asset table to a classpath resource, so `VADDetector.kt` needs no abstraction and drops its `Context` parameter |
| 5 | `SpeechToText` | HTTP `POST /transcribe` | unchanged (already HTTP) |
| 6 | `TextToSpeech` | Android `TextToSpeech`, bundled eSpeak JNI, gateway HTTP | `spd-say` (speech-dispatcher), `espeak-ng` CLI or JNA to the distro `libespeak_ng.so`, gateway HTTP unchanged |
| 7 | `SettingsStore` | `EncryptedSharedPreferences` | JSON file in `~/.config/celeste-linux/` |
| 8 | `SecretStore` | AES/GCM via Android Keystore | **Not in v1** — credentials live in the settings file at mode 0600, see §5a |
| 9 | `Notifier` | `NotificationManager` + channels | `notify-send`, tracking the notification id so a cancel is possible. **Lands real in M1**, not as a stub, because a byte-identical COPY file calls `cancelSpontaneousNotification`; M4 adds the Open action and the click routing rather than replacing it |
| 10 | `Scheduler` | `WorkManager` (self-rescheduling, reboot-safe) | coroutine timer inside the service core, driven by the already pure `SpontaneousScheduler`; optional systemd user timer. **Lands in M1 as an interface plus `StubScheduler`**, since a byte-identical COPY file calls `armSpontaneousWork`, `disarmSpontaneousWork` and `isSpontaneouslyArmed` three times each; M4 replaces the stub |
| 11 | `Tray` | Quick-settings tile | AWT `SystemTray` menu (Listen now, Mute mic, Open, Quit) |
| 12 | `Hotkey` | None (proximity "wave to interrupt") | **Landed in M2 as a CLI affordance rather than an interface** (2026-10-16): `celeste-linux --listen` comes up with the microphone armed, and the per-desktop bindings live in `docs/DEVICES.md` — Plasma, GNOME, Xfce, sway, i3 and Hyprland. There is no `Hotkey.kt` and no in-process key grabber — the compositor is the only thing that knows the user's layout and which keys are free, and the plan's answer was already this flag. A second launch while one is running finds the instance lock taken, names the holding pid and exits 1 (`SingleInstance`), because two copies are two microphones: both transcribe the user and both answer. **What the replacement lost is the part worth naming**, in §5's family: the phone's gesture reached a *running* service and could therefore interrupt a reply, and a key that starts a process cannot — so "arm or interrupt from outside" belongs to M4's tray, which is in-process. The optional XDG GlobalShortcuts portal is still unbuilt, and would restore exactly that |
| 13 | `FileDialogs` | SAF `ActivityResult` pickers | AWT `FileDialog` / chooser |
| 14 | `CertTrust` | `networkSecurityConfig` + TOFU prompt | OkHttp `X509TrustManager` + TOFU prompt. **Landed in M1 as ported code and needed no `:platform` work** — it is pure JVM (`javax.net.ssl`, `MessageDigest`), and it was already cross-platform. The store is **not** a file in `~/.local/share/celeste-linux/`; it is `trusted_certs_v2` inside the settings file (host → SHA-256 fingerprint), which is also what makes it travel in a backup. Exercised for real 2026-10-16: an Android backup's two pins (`192.0.2.73`, `api.deepseek.com`) survived the import unchanged, no re-approval was asked for, and `PinnedClientTlsTest` (2026-10-16) drives real handshakes against it — see §5's fourth family member for the defect that test exists to prevent |
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

**Nothing is contacted at startup (2026-10-15).** Two instances of one shape, both found by running the app rather
than reading it. `syncOpenRouterPricing()` fired from `start()` because a fresh install's stored timestamp is zero, so
the daily throttle never held, and it made a real request to `openrouter.ai`. And `loadSettings()` fetches models for
every provider with a key *or* `icon == "C"`, which a default-seeded "OpenAI-Compatible" entry satisfies with an empty
key — so from the second launch the app calls `http://0.0.0.0:11434/v1/models`. The first moved to the manual trigger
the Settings screen already had. The second takes the same fix and states the rule: **an automatic model fetch requires
a provider the user has configured** — a key, or a base URL that differs from the seeded default — and the settings
screen's existing connection test is the user-initiated trigger. Rejected: carving out loopback as harmless, because a
rule with an exception stops being checkable by a grep, and a seeded `0.0.0.0` means the host is whatever happens to
answer there on the machine where the app is installed.

**Automatic network calls need a configured provider (2026-10-16).** The complement of AGENTS.md §5's
"no network call that the user did not configure", made concrete for the one place it kept being
violated: the model fetches. `AssistantService.fetchCloudModels` may run automatically only for a
provider the user has configured — a non-blank key, or a base URL differing from the template
`DEFAULT_CLOUD_APIS` seeds. An unconfigured template is displayed, is editable and is saved like any
other; it simply does not fetch until the user acts. The two user-initiated triggers are the Settings
connection test and the act of saving a provider, and **both must reach `fetchCloudModels`** —
`updateCloudApi` was the save path that did not, which is why adding a key to a built-in provider
needed a manual refresh.

This replaced a guard on the provider's *kind*: `if (api.apiKey.isBlank() && api.icon != "C" &&
api.icon != "CL") return`. The seeded `"OpenAI-Compatible"` template has an empty key and
`icon == "C"`, so the guard let it through, and from **the second launch** — the first run has to seed
the templates before there is anything to walk — the application called
`http://0.0.0.0:11434/v1/models`. Rejected: treating a loopback address as harmless. The address is a
template's placeholder rather than a promise about the network, a rule with a carve-out stops being
greppable, and the whole point of auditing before a milestone closes is that the audit is a grep.

`FreshStartNetworkTest` covers both ends: a first run, and a launch-save-launch sequence, because a
seeded template only exists on the second.

**Every URL in `:core`, and what makes it safe (2026-10-16).** The record the audit checks against.
AGENTS.md §5 requires that no network call fires without a user action, and the rule is enforced by
grepping rather than by remembering — so the grep's output belongs somewhere a change to any line can
be checked against. Nineteen distinct URLs; the file and line are where each is written.

| URL | Where | What makes it safe |
|---|---|---|
| `https://api.anthropic.com` | `Models.kt:919` | [1] |
| `https://api.openai.com/v1` | `Models.kt:920` | [1] |
| `https://api.deepseek.com/v1` | `Models.kt:921` | [1] |
| `https://generativelanguage.googleapis.com` | `Models.kt:922` | [1] |
| `https://api.cline.bot/api/v1` | `Models.kt:923` | [1], and its branch in `fetchCloudModels` uses a static list — it never opens a socket at all |
| `http://0.0.0.0:11434/v1` | `Models.kt:924` | [1]. The `"OpenAI-Compatible"` template, and the entry the second §5 violation hid behind: it has an empty key and `icon == "C"`, which is what the old kind-based guard tested. |
| `https://generativelanguage.googleapis.com/v1beta/models` | `AssistantService.kt:2856` | [1] — built inside `fetchCloudModels` from a configured provider's `baseUrl` and key |
| `https://openrouter.ai/api/v1/models` | `AssistantServiceChat.kt:2120` | **No automatic caller at all.** `syncOpenRouterPricing` is reached only from Settings' "sync prices" button. Was called from `start()` until 2026-10-16. |
| `http://0.0.0.0:7860` | `AssistantService.kt:1191` | **The seeded A1111 default, and it is inert.** Reached only by an image generation the user starts; the periodic health check walks `_ollamaBaseUrls` and `_serverBases` and never `_imageGenBases`, and the only image-gen probe is `forceCheckHealth(…, true)` from a Settings button. Worth its own row precisely because it looks like the `11434` one. |
| `http://192.168.1.10:11434` | `WelcomeScreen.kt:94` | [2] |
| `http://192.168.1.10:8080` | `SettingsScreens.kt:1388` | [2] |
| `http://192.168.1.10:8880` | `WelcomeScreen.kt:152`, `SettingsScreens.kt:1765` | [2] |
| `https://192.168.1.10:8882` | `SettingsScreens.kt:1427` | [2] |
| `http://0.0.0.0:11434/v1/models` | `AssistantService.kt:2802` | [3] — the KDoc on `isUserConfigured()` naming the address the second violation called. Found by running `tools/network-audit.sh`, which is the argument for having it: a URL in a *comment* is still a URL literal, and the audit cannot tell one from a fetch target without a human saying so. Which is the right division — the script finds them, the table says what they are. |
| `http://host:7860`, `http://host:7860/` | `ServerIdentity.kt:84` | [3] |
| `http://host:8880`, `http://host:8880/`, `http://host:8880//` | `AssistantServiceGateway.kt:130-131` | [3] |

[1] **A configured provider, or nothing.** Every fetch target here is reached only through
`fetchCloudModels`, which returns immediately unless `CloudApiSetting.isUserConfigured()` — a
non-blank key, or a base URL differing from the template `DEFAULT_CLOUD_APIS` seeds. The five
`Models.kt` rows are that template list and are themselves COPY-file content; being in the list is not
being fetched. `FreshStartNetworkTest` asserts the negative on both a first and a second run, and
asserts the positive when a provider is configured, so a template that quietly starts fetching fails
the build.

[2] **Placeholder text.** A literal inside `Text(...)` — a hint about the shape of an address, shown
in an empty field and never parsed, resolved or opened.

[3] **Comment text.** KDoc examples about URL normalisation and trailing slashes, in a KDoc and a
related note. Never executed.

**Adding a row is the point of the table.** A new URL literal in `:core` needs one of three answers:
it is behind the configuration gate, it is a string a human reads, or it is a comment. A fourth
answer — "it fires on its own and it is fine because it is loopback" — is the carve-out §5 rejects.

**Replacing a blocking call is a behaviour change with no exception attached (2026-10-15).** Both silent failure
modes this port *created* rather than inherited came from that one shape. The chunked playback's completion arrives as
a callback where `MediaPlayer`'s listener was synchronous; and a `pw-record` subprocess can simply end where
`AudioRecord.read` would have blocked on a device that was still open — so an ended stream kept warning once per
iteration for as long as the app stayed open, a dead microphone as a hot loop. Neither threw, neither failed a test,
and both surfaced only by running against real hardware. So: when a blocking call becomes a process, a callback or a
poll, name how the replacement fails *differently* — end of stream, repeated failure, silence, a stale completion —
and handle that case where the call is made. Expect the same question in the hands-free loop, the hotkey, and M4's
scheduler.

**A stream is not a file (2026-10-15).** The third member of the same family, and the sharpest. `espeak-ng --stdout` writes
to a pipe, so the RIFF size it stamps at the start is one it can never seek back to patch — measured, `2,147,479,552`
declared in a 76,284-byte stream. A parser that bounds-checked every chunk alike called that corruption, and the local
voice route was dead on a machine where `espeak-ng` works. A `data` chunk now means "samples start here, everything to
EOF is payload" while every other chunk keeps the old guard. Two things to carry forward. The family's common shape: the
replacement lost a property the old path had — synchronous completion, a blocking read, a seekable sink — and the loss
shows up as *plausible data* rather than as an error, which is why all three were found by running against real hardware
and none by a test. And the response that made this one readable: `parseSpeechWav` is top-level and `internal`, so
`SpeechWavParseTest` reaches all six of its failure shapes with no engine installed. A parser of external data has to be
testable without the external thing — that is what turns a silent failure into a test.

**The same call can name two different objects (2026-10-16).** The family's fourth member, and the first one
that is not a *lost property* but a *changed referent*: upstream's S1 fix installed
`hostnameVerifier(HttpsURLConnection.getDefaultHostnameVerifier())`, and that expression is a self-contained,
strict verifier on Android and `javax.net.ssl.HttpsURLConnection$DefaultHostnameVerifier` on the JVM — which only
works from inside the JDK's own HTTPS machinery and returns **false** for every standalone caller. So every HTTPS
request the application made failed hostname verification, against certificates that plainly matched: measured,
false for `192.0.2.73` against a certificate carrying `IP Address:192.0.2.73`, and false for
`api.deepseek.com` against `*.deepseek.com`. It went unnoticed for the family's usual reason — plain HTTP kept
working, every Ollama entry is HTTP, and the failure reads as a server problem rather than as a client bug.

Two things to carry forward, because the shape will recur wherever a platform API is named in a portable file:
**the identifier crossing the port is a *call*, not a class** — `android.*` and `java.*` names were the ones being
watched, and this one lives in `javax.net.ssl` and is spelled identically on both platforms. And the response is
the family's: `buildPinnedClient` is top-level and `internal` so `PinnedClientTlsTest` can drive real handshakes
against a loopback server with a generated certificate, which is the only place a trust decision is observable.

**A same-named platform API can mean something different (2026-10-16).** The fourth member of the family, and the one that
hid longest. Upstream's S1 fix installs `hostnameVerifier(HttpsURLConnection.getDefaultHostnameVerifier())`. On Android that
returns a self-contained strict verifier; on the JVM the same expression returns
`javax.net.ssl.HttpsURLConnection$DefaultHostnameVerifier`, which is built for that class's own internal flow and returns
false for every standalone caller — measured, false for `192.0.2.73` against a certificate carrying that exact IP, and
false for `api.deepseek.com` against `*.deepseek.com`. So *every* HTTPS call in the port failed while every plain-HTTP call
to an Ollama host worked, and the failure read as a server problem rather than as a client one. The fix was to delete the
line, because OkHttp's default verifier is the behaviour upstream was asking for; `PinnedClientTlsTest` reproduces it. What
to look for next time: source and destination agree on the *name* and disagree on the *semantics*, so a platform call
carried over unchanged is verified by behaviour, not by signature.

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

**Latency budget (restated 2026-10-16, after it was measured for the first time).** The original
wording — "capture start to first chunk < 50 ms" — measured the wrong moment for a design that spawns
a process, and the first acceptance run said so: every arming measured 404-408 ms, all of it the
*deliberate* 400 ms liveness grace in `ProcessAudioCapture.open`, paid once per arming and never per
turn. So the budget now names the three moments that exist, each with where its number comes from:

- **arming a capture session < 500 ms.** Measured 404, 404, 408 and 408 ms across four armings in two
  runs — the grace, plus the spawn and the first-open tool probe at ~8 ms. 500 bounds the design and
  leaves room on a loaded machine; 450 would still clear every measurement if a tighter bound is
  wanted later.
- **utterance end → WAV on disk < 100 ms.** Unchanged, and met with two orders of magnitude to spare:
  0-9 ms over the utterances of three runs, the 9 ms one a 1.4 MB file. This number bounds the disk,
  not the design.
- **resume after a reply → the first live chunk: no figure yet.** This is the span a user actually
  feels and the one the original budget never named. It was measured at 154, 214, 251 and 214 ms —
  but with the drain's first (wrong) implementation, which chased the writer for 33-182 ms of that;
  the snapshot fix landed after those samples and the span has not been measured since. The figure to
  beat is therefore "well under 154 ms", and one short hands-free run fills it in.

WAV to `/transcribe` to reply to first audio out stays out of the budget on purpose: it is dominated
by the gateway and the model.
[Measured on the user's own run, 2026-10-16 — `grep -E 'capture start|utterance end'`, because the logger is
a single facade and the message text never contains the class name: `capture start → first chunk: 404 ms (open
404 ms, first read 0 ms)` on all three armings, and `utterance end → WAV: 0-1 ms` on all four utterances,
72-115 KB each. So the second budget passes with 100 ms to spare and the first misses by 354 ms, and every
millisecond of the miss is `open` — the deliberate 400 ms liveness grace in `ProcessAudioCapture.open`, not the
subprocess spawn this section's risk row worried about. Two things the numbers settle. Capture is opened **per
arming, not per utterance**: three opens against four utterances in one run, so the 404 ms is paid when listening
starts rather than on every turn. And the span the user actually feels is the *resume* after playback, which this
section never named. The budget as written measures the wrong moment for a design that spawns a process: state it
as arming and resume, and keep the grace, whose cost is invisible exactly where it is paid.]


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
| Image viewer with pinch-zoom/pan | M3 | A `DialogWindow` sized by the app, not a dialog the toolkit sizes; the same gestures, driven by a mouse. The gallery save beside it is a copy into `Pictures/VoiceAssistant/` |
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
| Earpiece/Bluetooth route toggle | `wpctl` sink selection, remembered per persona | Different model, same intent. **Landed 2026-10-16** as the persona editor's *Output device* row (`persona_output_device_<name>` in settings, an `AudioDevice.id`, absent = the system default), resolved by
`sinkForPersona` on every playback. **In settings rather than on `Persona` because `Models.kt` is a COPY file** — a
new field there is a verdict demotion, so this uses the pattern the blob already has for name-keyed per-persona state
(`persona_messages_<name>`, the spontaneous delivery state), with the same rename/delete/import lifecycle. It is
therefore device-local and does **not** travel in a backup, which is also the honest answer: a Linux sink name means
nothing on the phone The input side is a separate, **global** choice (Settings → Audio, `audio_input_device`) — a persona's headphones say something about that conversation, but there is one room being listened to and one microphone in it. Push-to-talk used to open its capture with `playbackTarget()`, a port artefact: upstream's `MediaRecorder` named no device at all. A stored device that has since disappeared falls back to the default **and says so** in the log, which is the weaker claim a preference makes — a *per-turn* choice is still refused rather than substituted (`AudioCapture.open`) |
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

[Verified by hand by the user on 2026-10-16, in the running app: a text conversation works against both a local Ollama host and a cloud provider — the cloud one is what proves `595faaa`'s HTTPS fix end to end — each of them speaking through Kokoro, the Android
backup restores through the UI and its personas appear, both voice routes speak — Kokoro over the gateway and the
local espeak-ng — A1111 generates images, an image upload works, and a persona's artwork can be picked and lands
correctly, that last one being seam 15's only real-data path and previously unexercised. M1's acceptance is therefore
substantially met. Still open for M1: settings surviving a restart, which nobody has watched. M3's ComfyUI half is open
too — ComfyUI is stopped while the build has the RAM — and when it returns, check the app's ComfyUI entry address,
because the imported one points at 192.0.2.12:8188 rather than the local 127.0.0.1:8188.]

[Verified by hand by the user, 2026-10-16 (later the same day): **voice input and voice output with a headset on an
Ollama persona (`Friend`) work**, text chat works, and ComfyUI image generation works. That is M2's acceptance in
substance — the first hands-free use of the repaired HTTPS path, which is the voice-in sibling of the cloud
conversation that first proved `595faaa` by use — and it also closes the ComfyUI question this file left open two
paragraphs up: ComfyUI is running again and both `127.0.0.1:8188` and the imported `192.0.2.12:8188` answer
`GET /system_stats` with 200, so the imported entry address is fine, and that entry carries a 2,556-character
`comfyWorkflow`, which is what the ComfyUI route needs (`COMFY_NO_WORKFLOW_MESSAGE` is thrown without one).

**Two clauses of this milestone's acceptance are not covered by that report, and one of them has never been measured
by anyone:** it does not say *ten consecutive* exchanges with no dropped utterance — it says the voice works, which
is the substance but not the count — and §6's latency budget (capture start to first chunk < 50 ms; utterance end to
WAV on disk < 100 ms) has no instrumentation at all. The second is the honest gap: those two numbers are the
milestone's stated budget and the only place they could be checked is a run like the one just done, with timing around
`VADAudioRecorder`'s open and its `saveToWav`. Either measure them or record them as unmeasured; do not let "voice
works" quietly stand in for "the budget is met".

Also still unlooked-at: **the device pickers' rendering** (Settings → Audio, and a persona editor's Output device
row). The voice run above used whatever the machine's default devices are, which is a different question from the
pickers drawing and writing correctly — those were compiled and their logic is tested, but no one has seen them.]

[Instrument landed, 2026-10-16 — the second gap above is now measurable, which is the whole of what changed: the
numbers still come from a run, so this is progress and not a result. `AudioLatency.kt` marks §6's two spans in
`VADAudioRecorder` — from before `capture.open` to the first full 512-frame chunk, and from the VAD's verdict to
`WavFile.write` returning — and logs one line per span, at INFO because `StderrLogger` is `java.util.logging` at its
default level where DEBUG is FINE and never printed. Each line carries the budget it is judged against, so the run
reads as "N ms over/under the M ms budget" rather than as a number to compare by hand, and `grep AudioLatency` is the
whole of reading it. The capture line is split into `open` and first `read`, and there is a reason to expect the first
to dominate: `ProcessAudioCapture.open` waits out a 400 ms process-liveness grace before it returns, deliberately, so
that a `pw-record` which cannot open the device is caught where the answer is "this microphone does not work" and the
fallback to the first enumerated input can still run. Whether that grace has to give way for the 50 ms now rests on a
measurement rather than on an argument. `AudioLatencyTest` pins the arithmetic and the wording against a fake clock;
the loop's own five call sites are exercised by the acceptance run — `VADAudioRecorder` cannot be constructed in a
test until §11's fake detector arrives with M7.]


**M2 — Voice (3–6 d, revised: platform work only).** No `:core` porting here — the audio engine and its callers landed in M1. Implement seams 1/2/3/6 for real, replacing the `Stub*` classes (`grep -rn 'Stub' platform/src/main` is the list), then add capture and VAD (`VADAudioRecorder.kt`, `VADDetector.kt`), `/transcribe`, the three TTS routes, the hands-free loop, push-to-talk, source/sink selection, mute and cancel-playback.
hands-free loop, push-to-talk hotkey, source/sink selection, mute, cancel-playback.
*Accept:* ten consecutive hands-free exchanges with headphones, no manual input, no dropped
utterance, and the latency budget in §6 met.

[Progress, 2026-10-16 — the hotkey is in, and one prerequisite is missing from the development box. Seams 1/2/3/6 are
real and all four have now been run against the hardware; the hands-free loop and push-to-talk were ported in M1 and
are wired to those seams; seam 12 lands as `--listen` (§5's row, with what it lost). What is left of M2: **source/sink
selection**, the last unbuilt item on the scope line above — §9's substitution for the earpiece/Bluetooth route toggle
is `wpctl` sink selection remembered per persona — **done (2026-10-16), and M2's last code item**: the persona editor
now has an *Output device* row and Settings an *Audio* category for the microphone. What remains is the acceptance run,
and the headset it needs. (Re-read the note below for why that run is not blocked on anything else.)

**The acceptance splits in two, and only one half is blocked (2026-10-16).** The earlier note above is kept for the
history — it was written when `server_bases_v2` was `"[]"` and applied.

- **Voice-out is testable now.** The local route (`espeak-ng --stdout` → `SpeechSamples` → `AudioPlayback`) needs
  nothing but a headset: it was verified end to end in this milestone (76,240 bytes, 22,050 Hz, 1,728 ms) and its
  playback path runs through `PwPlayback`, which was verified against PipeWire. A ten-exchange acceptance run cannot
  happen without the gateway, because every reply begins as a transcript — but "the app can speak, audibly, through
  the headset" needs only the headset.
- **Voice-in needs the gateway, and the gateway is now known and configured.** An Android backup imported through the
  app's own restore path installed `https://192.0.2.73:8880` as the one server entry, and the app's own
  `transcribeWithGateway` got an answer from its `/transcribe` route — so **that entry is the Voice Gateway**, and the
  input half is blocked on nothing but the headset too. (The two other candidates answer on other routes:
  `:8882/transcribe` is a 404, and `http://192.0.2.106:8888` serves an unrelated web UI.) The two imported TOFU
  pins survived the import, so no certificate re-approval is waiting.

So the remaining prerequisites for the ten hands-free exchanges are: a headset (D8), a configured gateway (done), and
the sink selection below if the reply should go anywhere other than the default output. **Until this milestone the
voice-in path could not have run at all** — every HTTPS call was failing hostname verification, see §5's fourth family
member; that defect is fixed and guarded by `PinnedClientTlsTest`.]

[Pre-flight, 2026-10-15: buildhost runs PipeWire 1.6.9 with pipewire-pulse and WirePlumber, and `pw-record`, `pw-play`,
`pw-cat`, `wpctl`, `parec`, `paplay`, `pactl`, `arecord` and `aplay` are all present — so both the primary path and the
fallbacks can be built and hand-tested on the acceptance machine. What is missing is a capture device: the only input is
the motherboard's analog ALCS1200A (line and mic jacks) and no USB audio device is attached, while playback is HDMI or
analog. D8 makes headphones a hardware requirement for this milestone, so a headset — wired or Bluetooth, and Blueman is
running — has to be attached before the ten hands-free exchanges can be judged. The implementations are unaffected: they
can be written and exercised without one. **Two packages are also missing there:** `speech-dispatcher` and `espeak-ng`
are both in the EndeavourOS repos and neither is installed, so seam 6's missing-engine path is what has run and its
happy path stays unverified until the user installs them; the gateway voice keeps working meanwhile. The README
already lists both as runtime dependencies of the finished app, so this is a gap on the development box rather than
a new requirement. **The machine also has no default capture device:** WirePlumber answers "no target node
available" to a bare `pw-record`, which is why the fallback above exists and why it logs the device it chose.
Setting one is the machine's business rather than the app's — `wpctl set-default alsa_input.pci-0000_2f_00.4.analog-stereo`
— and it makes the app's behaviour predictable instead of dependent on enumeration order. (Both packages landed on 2026-10-16 and both voice routes were verified end to end afterwards. **Bluetooth profile lore, measured by the user on 2026-10-16:** opening a Bluetooth headset's microphone switches it to HFP/HSP, which costs the *output* quality — "HSP/HSP=bad, A2DP/SBC-XQ=perfect" — because one radio carries one profile at a time. Two ways to keep both: the picker now allows separate input and output, so the headset's mic can record while playback goes to another sink (the analog jack), or a wired headset avoids the trade entirely and removes Bluetooth's own delay from the round-trip latency. The headset's HFP profile offers **CVSD only** — no `headset-head-unit-msbc` is listed, so wideband HFP (16 kHz) is unavailable on this hardware and the microphone's ceiling is 8 kHz narrowband, which is the oldest and narrowest codec in the family. The A2DP side is already at its best: `a2dp-sink-sbc_xq` active, with WirePlumber marking the headset as the default sink. Neither is a port defect, and the acceptance should be run in the configuration the user can live with rather than the one that happens to be default.) **Open from the same run:** `Chunked playback failed — chunk 0 could not be synthesised` in three of the turns with no reason logged — the reply was simply not spoken, and the cause is discarded the same way the Ollama 400's was; and `Recording error: StandaloneCoroutine was cancelled` logged at SEVERE although nothing failed, which will make every stop look like a fault.

[Both closed the same day, and the first one's cause was measured rather than argued — which is the point, because the log could not say it. **The unspoken replies:** the gateway had been answering all along. The 16:00:21 reply carried 13 Cyrillic characters in its first 120, so `detectScriptLanguage` classified it as Russian — which is what that rule is for, since a reply beginning "Here is the translation:" must be spoken in the target language — and the request went out as `language=Russian` with `engine=kokoro, voice=af_heart`. Measured against the gateway: that is answered `200 application/json`, `{"audio": null, "note": "Russian has no TTS voice available — text only."}`, where the same request in English returns 144 KB of audio. The note was logged at DEBUG — FINE, which `java.util.logging` never prints — so three SEVERE lines about unspoken replies carried no reason at all, and the two that came two minutes later carried none either: they were replays of that same reply, not the intermittency they looked like. One of the eight replies in that window was never spoken. Reasons now travel: `synthesizeWithGateway` returns a `GatewaySpeech` that carries one, `gatewayRefusal()` frames it with the route, engine, voice and language, and the sequencer's failure message is the caller's own words. **The SEVERE on every stop:** both capture loops caught `Exception` around their chunk loop, so the cancellation `VADAudioRecorder.stop()` uses to end monitoring — delivered at the paused branch's `delay(200)`, which is where the loop sits between an utterance and its reply — was reported as a recording error. `CancellationException` is now caught first and rethrown in both loops.

Still open on §6, and it is not this section's to close: the run's numbers measure *arming* (404 ms, three times) and the WAV (0-1 ms), and the span a user feels — the resume after a reply — has no figure at all. The bracket under §6 proposes restating the budget as arming and resume; that restatement is unapplied here because writing it means choosing a number for the resume, and nothing has measured one. What the run does settle is that "voice works" was a statement about speech that reached the speakers, and one turn in eight did not.]

[Nine voice turns ran clean, 2026-10-16 17:38-17:45 — and they were not the acceptance clause. `/tmp/celeste-run.log` contains no SEVERE line at all: no cancelled-coroutine noise, no unspoken reply, no failed synthesis. The transcript agrees — nine exchanges, every user message Latin-script (English, transcribed correctly nine times out of nine), every reply answered and spoken, `response_*.wav` at 0.4-3.6 MB and 24 kHz — so both defects closed above are absent from a real run, which is what they were closed for.

**What it is not is "ten consecutive hands-free exchanges".** Nine `recording_<ms>.wav` files — the push-to-talk path, written by `stopRecording` — and **zero** `vad_recording_*.wav`, which is what the VAD loop writes; one capture open per turn; and not one `AudioLatency` line, which only the hands-free loop emits. Every turn began with a button press, so the clause stays open, and so does the question of whether it should be run as written: the wording says hands-free, no manual input, and nine push-to-talk turns do not meet it however well they went.

Two things the same run measured on its way through. **The microphone is unchanged** — the new recordings have the same 4 kHz cliff as the mis-transcribed one (200 Hz 40, 3.4 kHz 0.9, 4 kHz 0.06, and exactly zero from 4.2 kHz up) — and yet English came through nine times out of nine, so the Russian was *intermittent*, not systematic. That is the property that made it worth instrumenting: a user cannot tell a hallucinated transcription from their own slip, and neither could the log. And **the device named in settings is gone**: `audio_input_device` is still `bluez_input.AA:BB:CC:DD:EE:FF`, which PipeWire no longer lists — only the analog source is present. A hands-free start with that name is a device that cannot be opened, which logs "the selected microphone could not be opened" and stops monitoring, because the fallback to the first enumerated input applies only when *no* device is named.]

[Where the Russian actually came from, 2026-10-16 — the bracket above says the reply "carried 13 Cyrillic characters" and stops there, which reads as a flourish of the model's. It is not, and the difference matters because it moves the fault upstream of everything the app can see. **The transcription was the Russian.** The 16:00:21 turn was push-to-talk, and its recording — `~/.cache/celeste-linux/recording_1792155606824.wav`, 15.49 s, the user speaking English — was re-sent to the gateway's `/transcribe` with and without a `language` field: both answers are `{"text": " Я тут корректно"}` — 13 Cyrillic characters, byte-for-byte the 16-character user message in the transcript. So Whisper did not fail, it *substituted*: on audio it could not read, it returned a short fluent phrase in a language nobody spoke. The model then echoed that phrase in its English reply, the script heuristic classified the echo as Russian — correctly, by its own rule — and the gateway refused the voice. The app passed on what it was given at every step. **The audio explains it:** the recording's average spectrum over ten voiced windows is real up to ~3.4 kHz, 0.03 at 4 kHz and *exactly zero* from 4.2 kHz up — a hard band-limit at the HFP/CVSD Nyquist, i.e. the headset's telephone-band microphone upsampled to 16 kHz by the capture path. Feeding band-limited, accented speech to a language-detecting recogniser is what produces this, and the remedy is the microphone rather than the code: the settings pickers allow the analog input to record while the headset plays the reply. The endpoint takes no language parameter (measured from its own `openapi.json`: `POST /transcribe` declares `audio` and nothing else), so the app cannot pin the language even if that were the right repair.]

[Acceptance met, 2026-10-16 17:56-18:07 — **eleven** consecutive hands-free exchanges with the analog headset, no manual input, nothing dropped, and the first run of this port whose application output contains no SEVERE and no WARNING line at all (29 lines: 15 INFO, 14 blank/continuation; the four WARNINGs in the file are Gradle's own native-access notices). And the claim does not rest on the user's word, because hands-free and push-to-talk leave different marks: **one** `capture via pw-record` and **one** `capture start → first chunk` for the whole session — where the push-to-talk run an hour earlier had one of each per turn — with eleven `vad_recording_*.wav`, eleven exchanges in the transcript, eleven `response_*.wav` totalling 267 s of spoken audio, and every user message Latin-script, so the intermittent Whisper substitution did not recur. The input was the analog headset and the measurement says so: those recordings carry real energy to 7 kHz (1 kHz 78, 3.4 kHz 33-67, 7 kHz 10-16) where the Bluetooth path was 0.06 at 4 kHz and exactly zero above 4.2.

The latency numbers from the same run: arming 408 ms (`open 408 ms, first read 0 ms`, once), and the WAV span 1, 1, 1, 2, 2, 2, 2, 2, 3, 7, 9 ms against its 100 ms budget. So §6's second number passes with two orders of magnitude to spare, and the first is 358 ms over — every millisecond of it the deliberate liveness grace, paid once when listening is armed and never per turn. That is the restatement the §6 bracket above asks for, and it is deliberately not written here: it changes an acceptance number, the honest form is arming (bounded by the grace) plus resume (the span a user actually feels, still unmeasured by anyone), and a number for the second is not this file's to invent. M2 is therefore met as written on the count and on the WAV budget, and met-in-substance on the capture budget with that one open edit.]

[The resume span, first measured — and the drain's first version was wrong, 2026-10-16 evening. The run after the drain landed was another long one: sixteen utterances hands-free, and four resume samples — `154 ms (drain 33 ms, first read 121)`, `214 (96, 117)`, `251 (146, 105)`, `214 (182, 31)`. The first read is steady at ~100-120 ms. The *drain* is not steady at all, and that is the finding: it grew with every resume, which is the signature of a loop chasing the writer instead of clearing a backlog. Re-asking `available()` between reads finds what arrived *after* the resume, because draining unblocks the recorder and the recorder refills the pipe; against a `yes` process the same loop takes **935 ms** where a single snapshot takes about one. So the drain now takes one reading and discards exactly that many bytes — `ProcessCaptureStreamDrainTest` pins both halves (it returns; the stream still reads afterwards), and restoring the old shape fails it with `the drain took 935 ms, which is chasing the writer` as the assertion message. The contract it had broken was the one written a commit earlier — "must not consume a byte that arrived after the call" — which matters most in the milliseconds after a reply ends, when a user is most likely to already be talking.

From the same run, and **not** a defect: `SEVERE: Chat failed / java.lang.Exception: Empty transcription result`. The VAD cut a 2.2 s utterance holding no speech a recogniser could read — a cough, a bump, one unclear word — and the gateway returned empty text; the throw is upstream's own, twice in `AssistantServiceChat.kt`, so the port is faithful and the run recovered and kept listening. What is worth a decision is its *shape here*: hands-free listening triggers on far more than speech, so "nothing recognised" is routine on this platform where on a phone it meant a deliberately recorded clip, and it currently arrives as a Java stack trace at SEVERE plus an `Error:` bubble in the chat. Left as it is for now — changing it edits a ported flow rather than repairing one — but written down so the choice gets made rather than inherited.

The §6 resume number stays unwritten, because every figure above is the *unfixed* drain and the fix landed after them.]

[Seam 6's happy paths ran, 2026-10-16: `speech-dispatcher` 0.12.1 and `espeak-ng` 1.52.0 are installed and the
socket unit is enabled. Both routes are verified on the development box. The system route reported `Finished` twice,
3,192 ms on the first call — the cold socket activation — and 1,717 ms warm, with `systemReady` still true
afterwards, which is the cache holding the success. The local route returns 76,240 bytes at 22,050 Hz, 1,728 ms,
and round-trips through `WavFile`/`wavDurationSeconds` as 76,284 bytes / 1.73 s. Two findings are now in the code
rather than in this note: the probe cache stores success and re-probes failure (DECISIONS.md, 2026-10-16), and
`espeak-ng --stdout` writes a streaming WAV whose `data` size is a placeholder it can never patch — see
`parseSpeechWav`. Both were the difference between a working route and a route that judged the voice missing.]

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
the half of `ModelScreens.kt` M1 did not take — the shared widgets came across, the screen did not.
**Image actions:** the "Save to gallery" button (upstream inserted into `MediaStore.Images`; the
desktop equivalent is a save dialog or a write under `XDG_PICTURES_DIR`) and the full-screen image
viewer (§7's `FullScreenImageViewer.kt`, a file M1 never took). **Share:** the message menu's Share
entry, whose §7 substitute is the `--send-text` flag — and the flag is also what wants the one-shot
handoff slot `MainVoiceScreen` no longer takes a parameter for.

[Scope corrected, 2026-10-16. Those markers — Rooms (3), Skills (2) **and** the image actions (3,
which had been marked M5) — were assigned by M1's amendment, and it put two milestones' features in
the wrong ones: it gave M3 `RoomScreens.kt` and `SkillScreens.kt` while §8's matrix and the
milestones' own names say *"M4 — Rooms, spontaneous, daemon"* and *"M5 — … skills …"*, and it parked
the image viewer, the save button and `--send-text` in M5 while M3 is the milestone named for images
and files. **The names and §8 win**, confirmed by the user before any of it was ported: the markers
are renumbered (rooms → M4, skills → M5, the three image gaps → M3) and these §10 lists follow them.
Models has no other claimant and stays. The census below is re-counted rather than restated, and the
total is unchanged — this moved nine markers, it added no feature to any milestone. What it does
change is M3's honest size: the image viewer, the save button and `--send-text` are real porting
(183 lines for the viewer alone, plus a save dialog and two CLI flags), where the rooms and skills
screens were never M3's work to begin with.

The first of them landed the same evening. `FullScreenImageViewer.kt` was **seeded** rather than
retyped (`tools/sync-from-android.sh --seed`, the rule from 2026-10-15), and its entire Android
surface turned out to be one argument: `decorFitsSystemWindows` is not a parameter of Compose
Desktop's `DialogProperties` — the compiler said so and nothing else did. The call site in
`ChatComponents.kt` is restored, and the marker promised it would be one call rather than a rebuild;
it was. Its pure geometry test came across **byte-identical** on a new `m3` arrival group added to
the sync script for the purpose — a test whose subject has not been ported is exactly what the group
split exists to keep out of the source set, and the ledger reconciles with it (82 lines, 0 changes).
The container changed the same evening, on the user's call: upstream's `Dialog` — which compiles here
but hands its size to the toolkit, since a Compose Desktop dialog takes its size from its content —
became a `DialogWindow` with an explicit size. That is the whole of "ditch the Android logic" for
this file, and it removes the one thing the first version could only have flagged as unseen: the pan
clamp measures its *container*, so a container sized by its own content measures the picture against
itself. A window we size is a window we can reason about.

**"Save to gallery" followed it the same evening, and it is the same substitution in miniature.** Upstream
inserted into `MediaStore.Images` through a `ContentResolver`, behind three `Build.VERSION.SDK_INT` guards; here the
button copies the file into `$XDG_PICTURES_DIR/VoiceAssistant/` under upstream's own `Gen_<millis>.png` name — the
folder is upstream's `RELATIVE_PATH`, and the user asked for that folder by name. The path reaches `:core` through
`AppEnv.picturesDir` (AGENTS.md §4: `:core` never resolves `$HOME` or `$XDG_*` for itself), and `XdgPaths` resolves
`XDG_PICTURES_DIR` alone rather than as a base dir, because it is a user dir and not an XDG home. Rejected: the save
dialog §7 offered as the alternative — the button's promise is "put it with my pictures", which configuration has
already answered. `ImageSaveTest` pins the two promises that matter: the name shape with its collision suffix, and
that a second save never destroys a first, because a folder full of the user's pictures is the one place a mistake
cannot be apologised for afterwards

**The share target came third, and it settles what that feature is on a desktop.** §7 substitutes Android's
`ACTION_SEND` with `--send-text` / `--send-file`, and §8 rows the share target as M3 — but the M1 comments at the
call sites said §2 put desktop share out of scope, which §2 does not say, so the flags arrived with that claim
corrected rather than inherited. The mechanism is a cold-start one-shot: the flag is parsed before the window exists,
handed to `MainScreen` as one more request pair beside the spontaneous deep link, and landed synchronously in the
composer (`appendSharedTextToDraft` for text, the attachment slot for an image). Upstream's shape needed a
composition-scoped `shareScope` and a warning about an effect that consumes its own key cancelling itself at its
first suspension, because an `ACTION_SEND` `Uri` arrived with a temporary grant and had to be copied before it died;
a command-line path is readable for the life of the process, so nothing suspends and the hazard has nothing to bite.
`--send-file` takes images only, judged by extension — the substitute for upstream's MIME check, since the composer
renders and sends its attachment as an image. **A second launch while one is running is refused by the instance lock
(seam 12), so these flags are cold-start by construction and a payload cannot be forwarded into a live window**; that
is stated in `Main.kt` where the flags are read rather than left for a script's author to discover. The outbound half
stays where §2 put it: handing a file to another application is `xdg-open`, which `docs/BEYOND-THE-PORT.md` keeps
outside M0–M7.]

[M3's acceptance met, 2026-10-16 evening — and met by use, not by assertion. An image was generated through the
**active backend, ComfyUI** (`active_image_gen_backend` says COMFYUI; the entry is "buildhost" at
`http://192.0.2.12:8188` with its 2,556-character workflow), rendered in the chat, opened in the viewer — the second
attempt, after the first one's crash turned out to be the build race recorded in AGENTS.md §6 and not the viewer — and
saved: `~/Pictures/VoiceAssistant/Gen_1792166349965.png`, 1,117,556 bytes at 18:59:09, byte-for-byte the generation
written to the cache at 18:56:33, with the log line and the snackbar both naming the full path. The third clause, "the
same workflow tokens behave as on Android", is what the copied upstream tests already pin — `ComfyPromptFillingTest`
and `ComfyWorkflowValidationTest` are byte-identical m0 files — so it was executable specification before this run
rather than a hope during it.

That also retires the one thing the viewer's first version could only have flagged: the window sizing that
`DialogWindow` was chosen to make explicit is now *seen*, since the picture opens in a window rather than at whatever
size a dialog felt like taking.

What is left in M3 is its third item, and it is the only one a click cannot exercise: `--send-text` / `--send-file`
have not been invoked. One cold start closes it — `./gradlew :desktop:run --args="--send-text hello"` should come up
with "hello" in the composer, and `--send-file <image>` with the attachment.

[Correction, later the same evening — the bracket above claims M3's acceptance met, and one of its three clauses was
not. What the user reported as "image was just a black picture, not the cat ComfyUI rendered" was the **chat bubble**,
and it was the *attachment* bubble: `--send-file ~/Pictures/VoiceAssistant/Gen_1792166349965.png` reached the
application with the tilde intact, because neither `gradle --args` nor a `.desktop` entry's `Exec` line is a shell. The
composer attached a path that does not exist, Coil drew nothing, and the bubble showed its own surface colour — which
is precisely what "just a black picture" looks like from outside, with nothing anywhere saying why. The transcript has
it in one line: the message at 19:05:09 carries `imagePath=~/Pictures/VoiceAssistant/Gen_1792166349965.png`,
`exists=False`.

Two repairs, both in the *flag* path rather than the image path: `parseShareArgs` now takes the home directory as a
parameter and expands a leading `~` (a parameter and not a `System.getProperty` call, for §4's reason — `:core`
receives paths), and the landing checks the file is there and names it when it is not, so a typo is a sentence instead
of a black rectangle. A `~` naming another user is left alone and named by that check rather than guessed at.

The generated image itself was never at risk — `gen_1792166193384.png`, 1,117,556 bytes, the same bytes the save
copied — but the clause that matters, "**rendered in the chat**", is still unverified: what was rendered in the chat
was a missing attachment. It is verified when the generation's own bubble is looked at and shows the cat.

[Verified later the same evening: the cat renders in the chat. So M3's acceptance is met in full — generated through
the active ComfyUI backend, **rendered in the chat**, tokens pinned by the copied upstream tests, opened in the
viewer, saved to `Pictures/VoiceAssistant/`, and shared in at the command line — with two of those five reached only
after a defect each: the viewer's first crash was a build racing a running application (AGENTS.md §6), and the first
shared attachment was black because a tilde reached the application unexpanded. Both are recorded where they happened
rather than only here.]

The counts, settled by the anchored grep rather than by a mention count:

```
grep -rnE '^[[:space:]]*(//|\*)?[[:space:]]*DEFERRED TO M[0-9]' core/ platform/
```

gives **15 markers — 4 × M3, 9 × M4, 2 × M5** (re-counted 2026-10-16 after the scope correction
above moved nine of them). A plain `grep -o 'DEFERRED TO M[0-9]'` gives 18,
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
two `removePersonaShortcut` call sites in `AssistantService.kt` that §9 already records.
**Rooms — received from M3 in the 2026-10-16 correction:** `RoomScreens.kt` in full — `RoomChatArea`
(MainVoiceScreen), `RoomPickerRows` and the `"Rooms" -> RoomSettings(…)` branch (SettingsScreens) —
and with them the editor, the member list, the round-status line and the pure room helpers above
them, because this is the milestone named for rooms.]
[M4's first slice, 2026-10-16 — the scheduler and the delivery, which are the engine the acceptance exercises.
**Seam 10 is real and lives in the core**, which is what this line said it should be, and it retires `StubScheduler`:
`grep -rn 'Stub' platform/src/main` now finds nothing, where `Main.kt` had a comment promising exactly that. The
choice §5 left open — a coroutine timer versus a systemd timer — is settled by the acceptance criterion itself: "while
only the tray process runs" requires the process to be up either way, and once it is, a systemd timer would add a
second scheduling authority that has to agree with `SpontaneousPolicy`'s windows, gaps and caps. The unit is still
M4's, and it is what a *login* is missing rather than what a delay is.

**The delivery is upstream's worker minus WorkManager.** `SpontaneousDelivery.kt` keeps the decision
(`evaluateSpontaneousCycle`, a COPY symbol), the preflight, the turn and the re-arm; `useAssistantService`, its
`ServiceConnection` and its 15-second bind timeout dissolve into D1's single process rather than being translated, and
what that binding protected — bind late, so an expensive construction happens only when a message is genuinely about
to be generated — survives as the order the checks run in. Upstream's four contracts came across with it: deliver only
on a real send, never retry, a throw is logged rather than propagated, and the re-arm goes to the *other* slot.
`alternateSpontaneousSlot` had to be defined here (upstream keeps it in the worker file, which stays out) and is
carried with its reasoning, because the caller's contract depends on it.

**The notification's posting half landed too** — `postSpontaneousNotification`, a call to the `Notifier` seam with the
id rule and the preview helper that M1 had already ported and left waiting for exactly this caller. One thing is
named rather than implied: a tap on the card cannot open the persona's chat yet, because `Notifier.post` carries no
action and `notify-send` is called without one; `MainScreen` already accepts a `deepLinkPersona` for a tap it does not
get. The gap is at the seam.

**Two upstream tests arrived, one could not.** `SpontaneousTurnTest.kt` is byte-identical on a new `m4` arrival group;
`SpontaneousSchedulerTest.kt` mixes 35 pure decision cases with WorkManager assertions (`spontaneousWorkName`, the
channel and summary ids) and is recorded in `docs/TEST-MAP.md` as not taken, with what retargeting it would mean.
`SpontaneousTimerTest` covers the timer on virtual time: the slot survives the round trip, a second arm replaces
rather than adds, `isArmed` is true for the whole life of a run — the property `refreshSpontaneousSchedule`'s guard
reads — and a delivery that throws does not end the chain silently.

**`--headless` landed next**, which is the other half of "while only the tray process runs" and needs no
tray to be useful: the same program with no window, the service up, seam 10 arming, and a spontaneous message
arriving as a desktop notification with nothing on screen. It is deliberately not a Compose application with an
empty composition — AWT's event thread exits when no window is displayable, and would take the process with it — but
the same JVM with the service running and a latch holding the main thread. Teardown is a shutdown hook, so Ctrl-C and
`systemctl --user stop` run the very same `shutdown()` the window's close button does: the settings flush and the
audio teardown happen on all three paths rather than only the one with a UI. What it does *not* yet give is a way to
reach the running service from the desktop — no tray icon, no Open, no Quit — which is the next slice.

**The tray followed — seam 11, and the destination the status line has been waiting for since M1.** §5's row sketched the
menu as "Listen now, Mute mic, Open, Quit"; the shape is kept and the labels move with the state, because a menu that says
"Listen now" while it is listening lies about the one thing it is for. `updateNotification`'s string — upstream's
ongoing-notification content text, which the port had been writing to a DEBUG log line with nowhere else to put it — now
publishes a `statusLine` flow that the tray shows as its tooltip and its first menu row; not one ported call site changed for
it, which is what that KDoc promised. Two shapes fell out of the implementation and are decisions rather than details: the
**Open item is absent where there is no window** (headless has nothing to open, and an entry that opened nothing is worse than
its absence — the rule the Share entry was held to), and **Quit is never disabled**, because in headless mode the tray is the
only way to stop the process without a terminal. The icon is drawn in code and says so: `assets/` holds the model and the
licence, and icons belong to M6's packaging rather than to a second source of truth here. `TrayMenuTest` pins the menu as a
pure function of state — which items exist, what they say, what is enabled — because the AWT half is a thin call and the
decisions are not.

**The unit is written, at the path §4 reserved for it, and `systemd-analyze verify` accepts it**: one line of output,
"Command celeste-linux is not executable", which is M6's package arriving rather than a fault in the file — and no
syntax or directive complaints. It is `Type=simple` over `celeste-linux --headless` (D1's flag, not a second program),
`Restart=on-failure` rather than `always` because a clean exit is a decision — the tray's Quit, or a Ctrl-C — and
restarting a decision is how a daemon becomes unkillable, with `StartLimitIntervalSec`/`StartLimitBurst` so a crash loop
stops knocking instead of hiding behind restarts. `WantedBy=default.target` is the autostart, and `PartOf=graphical-session.target`
is why it stops with the session. §12's linger risk is answered where the user will meet it: the header documents
`loginctl enable-linger`, and says why that is the user's decision rather than a package's. Its header also carries the
one thing a unit cannot: how to point `ExecStart` at a build tree until M6 installs a binary.

**The per-persona entries followed, and the design came across whole.** Upstream's `PersonaShortcuts.kt` says the thing that
matters about this feature in its own header — *a shortcut adds no launch path of its own* — and that survived the translation:
an entry is another producer of the deep link (`--persona <name>` here, the same one-shot `MainScreen` already honours for the
spontaneous notifications), so a tap inherits that path's properties: it interrupts an in-flight turn, it leaves room mode, and a
persona deleted since is consumed harmlessly. **The name is the id**, as it was upstream, and the one sharp edge upstream named is
handled where it handled it: a rename *withdraws* the old entry rather than leaving one that opens whichever persona is selected —
the two `removePersonaShortcut` call sites the M1 markers deleted are restored at exactly those places, on the persona-update and
persona-delete paths, best-effort and logged, because a shortcut that could not be withdrawn must not fail a rename. The medium is
what changed: a `.desktop` entry written into `$XDG_DATA_HOME/applications`, with a hash of the exact name in the file name, because
"Vicky" and "Vicky " slug identically and a file name cannot hold the difference — two names, two entries, no silent replacement.
The launcher's confirmation dialog has no counterpart; the snackbar says where the entry went. `escapeForDesktopExec` is the
desktop-entry specification's escaping rather than the shell's, which is the difference between an entry that opens the right
persona and one that opens none.

**And the controls, which close the loop between the feature and a person.** `PersonaSpontaneousSection` is upstream's shape
whole — the master switch, the optional instruction, the gap slider, the window's two sliders and their overnight rule, and a
Send-now button that stays *visible* while the feature is off, because hiding it would put the section's most useful debugging
control behind the switch that turns it on. Two things are this platform's rather than Android's. The eligibility verdict was
already here (M1 computes it and it gates the commit, which is why a persona that has become ineligible cannot keep an opt-in it
can no longer act on) — the controls only draw it now, using the same words the guard uses. And one paragraph is rewritten: upstream
warned that Android decides when background work actually runs, expecting a message later than the window says, because of doze,
Battery Saver and the charger. The desktop's uncertainty is the opposite: the timer is exact, and what it needs is for the process to
be alive — "the tray icon is how you know it is listening for the moment you picked", which is the honest sentence for this side and
the one the user needs before trusting a schedule.

[**Acceptance met in substance, 2026-10-16 evening, by use rather than by assertion.** The user enabled spontaneous
messages through the new controls (the persona carries a real instruction from the section's field, a 60-minute gap and an
08:00-22:00 window), and a message landed without anyone touching the application: the log says `Spontaneous message from
'Celeste' landed (356 chars, 32134ms)`, `spontaneous_state_v1` holds `sentTodayCount: 1` for today's date — the counter the
daily caps read — and the same log reports `tray icon installed`. So the four links the milestone is about all worked in one
run: an eligible persona, a timer that fired, a turn that generated and persisted, and a surface that told the user.

**One clause is not literally witnessed, and the log is what says so:** that run was *window* mode, not `--headless` — the
`--headless: no window` line is absent from it — so "while only the tray process runs" is demonstrated for a run nobody
interacted with, and not yet for a run with nothing on screen. What differs is the mode that was started, not the code path:
the timer, the delivery and the notification do not know whether a window exists, and `--headless` is one flag away. Recorded
precisely rather than rounded up, because "a spontaneous message arrived" and "a spontaneous message arrived with no window in
existence" are different claims and only the first was made.

**The rooms screens landed, and they were the largest remaining file anywhere in the port.** `RoomScreens.kt` came across *wholesale*
— 1,006 lines, compiled unchanged — which is the outcome §7's "Compose only" for that file predicted and the opposite of what two
scripted attempts at slicing it produced. The one deliberate difference is M1's KDoc above `personaBackdropPath`, kept because it
explains the room-versus-persona backdrop bug in words upstream's does not. The three call sites — `RoomChatArea` (MainVoiceScreen),
`RoomPickerRows` and the `"Rooms" -> RoomSettings(…)` branch (SettingsScreens) — were restored from upstream's own text rather than
retyped, which is the same lesson one level down: the block that failed twice under a hand-written edit went in first time as an
extraction. Its two tests arrived byte-identical on the m4 group, 40 cases between them, and core now runs 302.

**M4 closes with no markers left.** The last pair was the quick-settings tile and the `"Shortcuts"` section that held it — and the
resolution was not a port: §9 names the tray as the tile's equivalent, the tray exists, so the tile became a **recorded drop** (the
comment says why, and §9's table already had the row) and the section came back saying where one-tap listening actually lives on this
platform: the tray's "Listen now", a compositor binding to `celeste-linux --listen`, and the tray's mute for a meeting. That is the
section's stated reason for returning — "the user's one-tap voice entry is what the section comes back for" — answered in the words of
the surface that now provides it, rather than with a row that would have asked a user to pin something no desktop has.

`grep -rn 'DEFERRED TO M4' core/` returns nothing.

**M3 closed the same evening, and both of its stragglers resolved the same way.** `ModelsScreen` and its three private helpers came
across in a wholesale copy of `ModelScreens.kt` — the third time in one night that copying a file whole beat slicing it, and by now
that is the method rather than the lucky answer: the widgets M1 had taken arrived with their first callers as §5's shared-block rule
asked, and the screen arrived with the milestone that draws it. Its 467 lines needed exactly one deletion to compile:
`BackHandler(onBack = onBack)`, because a desktop window has no system back gesture and §9's substitute is the header arrow upstream
already draws. Its call site went in from upstream's own text, like the rooms call sites. The Share menu entry became a **recorded
drop** rather than a build, and the reasoning is worth its line: this platform has no cross-application share sheet, §7 substitutes
the *inbound* direction with the flags M3 built, Copy already *is* the clipboard, and handing a file to another application is
`xdg-open` — which §2 and `BEYOND-THE-PORT` keep outside M0–M7.

**M5 closed the same night, and it closed the census.** `SkillScreens.kt` — `SkillSettings`, the skill editor and
`PersonaSkillSection` — came across whole and compiled **unchanged**, the fourth file whose §7 "Compose only" verdict turned out to be
literally true: no `android.*` import, no `Context`, `Toast`, `Uri` or `BackHandler`. Its two tests (`SkillEditorUiTest`,
`SkillMatchingTest`) arrived byte-identical on a new `m5` group, 69 cases between them, and its two call sites went in from upstream's
own text. So `grep -rn 'DEFERRED TO M[0-9]' core/ returns nothing`: **every deferred feature in the port is now built**, and the three
milestones that were carrying markers — M3, M4, M5 — read 0, 0, 0.

What M5 still owes is not code but a run: its acceptance is "a spoken translation round trip; RAG and web search answers", and the
translator, RAG and web search logic all arrived in M1's COPY files. It is the same shape M4's acceptance had — the substance is
here, and one session with the app settles it.

[**M6 opened with the app image, and two of its facts were measured rather than assumed.** `nativeDistributions` — deliberately
absent until now, since the build file said it was this milestone's work — carries the package name the unit file and the
per-persona entries already use, GPL-3.0, the `Deb` target (the containers build that one, per this file's own glibc note), a
`linux` block with category, maintainer and a **drawn placeholder icon** (`packaging/icons/`, the same shape as the tray's, because
the repository had no icon and jpackage wants a file), every module, and the `--enable-native-access=ALL-UNNAMED` the plan asked for.

**The packaging JDK has to be one with `jmods`, and this box's did not.** The Gradle-provisioned Adoptium 25 has no `jmods/`
directory, so `jlink` refused it — "This JDK does not contain packaged modules and cannot be used to create another image" — and the
image could not be built at all. The system JDK 27 has them and a 27 runtime runs the JVM-25 classes the project compiles, so
`javaHome` is pinned to it, with a fallback to the toolchain for a machine that differs. The trade is recorded in the build file:
that one knob also decides which JDK `:desktop:run` uses.

**The runtime was then trimmed to the call sites that need it, and the image went 354 MB → 269 MB.** `includeAllModules = true`
became a seven-module list, each entry written down with the call site that requires it (`java.desktop` for AWT and Skiko,
`jdk.unsupported` for `sun.misc.Unsafe`, `java.instrument`, `java.management`, `java.naming`, `java.xml`, `jdk.crypto.ec` for the
elliptic-curve half of TLS). The runtime is now 81 MB where it was 165. **What that list cannot promise is completeness** — the
self-test exercises paths, a graphics stack and an application, and it does not exercise TLS, an ONNX inference or an audio device —
so the packaged application running for real is what closes it, which is what the containers are for.

**And the remaining 187 MB is the application itself, where the measurement found something worth knowing:** the biggest single
entry is `onnxruntime-1.19.2.jar` at 90 MB, and *inside* it are `native/win-x64/onnxruntime.pdb` — 301 MB of Windows debug symbols
— plus macOS natives (29 MB and 26 MB) and a `linux-aarch64` library. The linux-x64 library this machine runs is 16 MB. So the
packaging can shed most of that jar by excluding platform directories it will never load, which is a change to the *packaging
scripts* rather than to the build's dependency graph, and therefore belongs with the PKGBUILD and the `debian/` rules rather than
here. Recorded rather than done, because it is the kind of cut that should be visible in the file that makes it.

**Measured, not estimated: the image was 354 MB, 165 MB of it the bundled runtime** — which is what §12's risk row asked for and
what makes the next slice obvious, since `includeAllModules = true` is the safe choice rather than the small one. And the image
*runs*: `bin/celeste-linux --selftest` printed the resolved XDG paths, the audio devices and the upstream commit, with no Gradle
and no system JDK in the picture.

**The `.desktop` entry and the runtime trim followed, and then the Arch package — whose first two runs changed the file
twice.** `packaging/arch/PKGBUILD` builds the tree it lives in (empty `source=()`: there is no release to point at yet, and a
tarball with `sha256sums=('SKIP')` is the same non-hermetic build wearing a checksum it cannot honour), and it installs under
`/usr/lib/celeste-linux` rather than the bundle-conventional `/opt`, because namcap reports `/opt` as an error and Arch's own
guidance is to keep it for what has nowhere else to be. The image is relocatable — jpackage's launcher resolves its own
directory — so that was checked rather than assumed: copied into a fake prefix and launched **through a `/usr/bin` symlink**,
it printed the resolved XDG paths, the audio devices and the upstream commit.

**The ONNX cut is the packaging's, as this section said it should be, and it lands at 5.7 MB from 90 MB** — measured by doing
it. Two facts came out of doing it. **`zip -d` exits 12 when a pattern matches nothing**, which is exactly what a second build
of an already-trimmed image is; the cut is therefore guarded by a listing test and a `found` flag, so a renamed or missing jar
stops the build rather than shipping every platform's natives in silence. And **`check()` is what makes the cut verifiable at
all**: `:core`'s tests link the *untrimmed* Gradle dependency, so nothing in the Gradle build can see it, and the check
compiles `packaging/arch/OnnxSmokeTest.java` against the image's own jars and creates a session over `assets/silero_vad.onnx` —
the call `VADDetector` makes. It does not use the `java` on `PATH` either, which is Arch's default **JDK 17** on this machine
and cannot compile for or run the JVM-25 classes in the image: the same fact `desktop/build.gradle.kts` pins its way around,
seen from the packaging side.

**namcap's first run found five real things, all fixed, and its remainder is named in the file.** The fixed ones: `/opt`'s path
error, and four dependencies read out of the bundled libraries' own `NEEDED` entries — `libxtst` and `libxi` for
`libawt_xawt.so` (the window, the tray, the file dialogs), `libglvnd` for Skiko's `libGL.so.1`, and `hicolor-icon-theme` for
the hierarchy the icon is installed into. The two left standing: *Dependency java-runtime … not included*, because adding it
would put a second JDK on the machine for an application that carries its own — §2.6's whole point — and *Missing url*,
because there is no published home for this port or for the upstream app and a URL invented to silence a linter is worse than
an empty field. **The package is 111,408,490 bytes** with the trimmed jar inside it.

**The `debian/` half landed next, and it took three container builds to be right — each one a fact that reading could not have
given.** `packaging/debian/` is debhelper packaging (`control`, `rules`, `changelog`, `copyright`, `.install`, `.links`,
`source/format`), built inside Debian 13 by `packaging/debian/Containerfile` from a *staged copy* of the tree:
`dpkg-buildpackage` wants a source root with `debian/` at it, and this repository keeps its Debian files under `packaging/`.
What the builds taught, in order. `dpkg-buildpackage` refuses to start without `build-essential` — "unmet build dependencies:
build-essential:native". `dh_shlibdeps` must *find* every library the bundled runtime links against in order to say which
package owns it, which is where `Depends`'s `libx11-6`, `libxi6`, `libxtst6`, `libgl1`, `libasound2t64`, `libpng16-16t64` and
the rest come from: the same set namcap found by hand on the Arch side, computed here by the tool that owns the question.
And **Debian's own JDK 25 is `openjdk-25-jdk-headless`, which ships `jmods`** — the thing this milestone spent a build
discovering the hard way on Arch is one apt line here.

**And the third build found a defect worth more than the package: the ONNX cut had not run at all.** `unzip` was not
installed in the container, `unzip -l … || continue` read "the tool is missing" as "already trimmed", and the `found` guard
passed because the jar *existed*. The `.deb` was 186,596,996 bytes against the Arch package's 111 MB, and the reason was one
comparison — `onnxruntime-*.jar` at 93,522,826 bytes in the `.deb` against 5,959,524 in the `.pkg.tar.zst`. The fix is not a
tighter condition but a different question: **a skip test cannot tell a missing tool from finished work, so what is asserted
now is what must be *true afterwards* — exactly one platform directory survives in the jar** — and both packagings carry that
post-condition. With it the `.deb` is **99,618,448 bytes** (xz, against the Arch package's zstd), plus a 1.45 MB `dbgsym`
companion that debhelper produces by default.

**M6's acceptance is therefore met on both distribution families**, and by running rather than by inspection. A clean Arch
container: `pacman -U` pulls all seven declared dependencies, `celeste-linux --selftest` prints the XDG paths and exits 0 in
**0.24 s**, and `pacman -Rns` leaves nothing under `/usr`. A clean Debian 13 container: apt pulls `pipewire-bin`,
`libnotify-bin`, `libglib2.0-bin`, `libxtst6` and `hicolor-icon-theme`, the installed binary runs in **0.30 s** with
**zero `openjdk` packages in the image**, and `apt-get remove` leaves nothing under `/usr` either. The audio warnings both
containers print are the documented graceful answer to having no sound server. What M6 still owes is the README's install
section — which can now be written from what the containers actually did rather than from the packaging's intent.

**[2026-10-16, the installed Arch package in daily use — five findings, four of them fixed here.]**

**The taskbar icon was missing, and the cause was a name no dock could match.** Measured on the running process:
`WM_CLASS(STRING) = "java-lang-Thread", "java-lang-Thread"`. `sun.awt.X11.XToolkit`'s constructor names the class after the
**bottom frame of the stack trace that built it**, and Compose builds AWT from a coroutine on a Swing thread — so the window
said `java-lang-Thread` while `celeste-linux.desktop` claimed `celeste-linux`, and a dock that cannot match falls back to a
generic icon. The fix is two halves that have to agree: `Main.kt` claims the class, and the three `.desktop` files (the
package's and each persona's) say `StartupWMClass=celeste-linux`. **The obvious way to claim it does not work** —
`-Dsun.awt.X11.XWMClassName` is a JetBrains Runtime feature, not stock OpenJDK, and grepping the JDK's own `java.desktop`
X11 sources for it returns nothing; the first version of this fix set that property and would have changed nothing. What
exists is the private static `awtAppClassName`, read when a *window* is created rather than when the toolkit is, so
`claimTheWindowClass` forces the toolkit into existence, sets the field reflectively, and
`--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED` (declared beside the native-access flag) is what makes it reachable. The
window also carries the icon itself now — `Window(icon = …)` reads the same PNG the packages install, wired onto the
resource path from `packaging/icons` so there is one icon file and not two.

**Hardware acceleration was already there, measured rather than assumed.** The question was whether the animations could use
the GPU; the answer is that they already do. The running process's address space holds `libGL.so.1`, `libGLX.so.0`,
`libGLX_mesa.so.0`, `libgallium-26.2.4` and `libdrm_amdgpu.so.1` — Skiko renders through OpenGL into Mesa's gallium on the
AMD driver, with no `llvmpipe` anywhere in it. There is no video decode in this application to accelerate: the animations are
Skia drawing on the GPU, and what they cost is composition, not codecs.

**Three smaller ones, all the user's own list.** A persona shortcut can now go on the **Desktop** as well as into the
application menu — the menu entry had been written and worked, and it was somewhere they do not look; the Desktop copy is
executable (every desktop requires that bit for a launcher found there) and is written only if a Desktop directory already
exists, so nothing invents `~/Desktop` for someone who does not keep one. The persona selector's sheet and the header's name
frame are **square** now, on request: the header's rounded clip was cutting into the first glyph of a long persona name, and
a frame that eats the first letter of the thing it frames is not a style. Both are in ADAPT files, so both are recorded as
deliberate deviations from upstream's appearance rather than as ports (docs/PROVENANCE.md).

That leaves **M7 (hardening)**, which is an estimate rather than a measurement, and therefore where the remaining percentage is
softest.
entries, the rooms screens, and the spontaneous controls in the persona editor.

*Accept:* a spontaneous message arrives as a desktop notification while only the tray process runs;
the unit starts at login and stops cleanly.

**M5 — The rest (3–4 d).** Translator mode, skills, RAG, web search, usage, thinking mode.

[Receives from M1, 2026-10-16 — re-assigned the same day. **Skills:** the `"Skills" -> SkillSettings(…)`
branch in `SettingsScreens` and the Skills section in the persona editor (`SkillSettings`,
`PersonaSkillSection`, and `SkillScreens.kt` with them) — the skill library and its editor are one
feature, and personas were passed into the screen so each row could say who would lose a shared skill
on delete. The three image-action markers this list used to claim (save to gallery, the full-screen
viewer, the Share entry) moved to M3 in the scope correction above, which is the milestone named for
images. §2's share target stays out of v1.]
*Accept:* a spoken translation round trip; RAG and web search answers.

**M6 — Packaging (3–5 d).** `PKGBUILD`, `debian/`, jpackage app image with a bundled runtime,
desktop entries, icons, user unit, README install section, and `--enable-native-access=ALL-UNNAMED` in jpackage's `javaOptions` — JDK 25 warns that
Skiko loads its native library without declaring native access.
*Accept:* install, run and uninstall in a clean Arch container and in a Debian 13 container.

**M7 — Hardening (5–8 d).** Remaining test porting, desktop UI tests, a fake gateway for the voice
loop, a soak test for start/stop/restart leaks, error paths (gateway down, no mic, no Secret
Service, ComfyUI restarted mid-job), `docs/TEST-MAP.md`.
*Accept:* the soak test runs an hour of start/stop cycles with no leaked processes or file handles.

**M8 — Filesystem tools (new scope, not a port; not counted in the total below).** The user wants Celeste to read and write
files on this machine so the assistant can be used as a local agent, **and the app's user chooses where and how**: a
workspace path, picked through seam 13's file dialogs, and a mode. So the capability is configuration rather than a
build-time decision, and the staging lives in the setting instead of across milestones:

- **Off by default, and "off" is a real answer.** Nothing is reachable until the user names a path and picks a mode.
- **Two modes: read-only, and read-write.** Read-write adds a confirmation showing the path and the change, and a root can
  be trusted for the session only, so a restart returns to asking.
- **Per persona, with a global default** — the shape the app already uses for image-generation entries and their pins: the
  persona you chat with casually has nothing, the one you use for work has a directory, and a persona's own setting wins.
- **The always-excluded paths are named in the UI rather than hidden:** `~/.config/celeste-linux`, which holds the
  credentials, and `~/.ssh`. "Read my notes" and "read my keys" are one word apart, and that failure is silent — the key
  would go into a prompt, possibly a cloud one.
- **File contents are data, never instructions.** RAG already ingests text; it is the write path that turns a document
  saying "now delete X" into a changed file.
- **Tool calling is a provider capability**, and not every model has it, so a persona bound to one that cannot call tools
  gets a clear answer rather than silence.
- **Every tool call logged** — path, operation, size, timestamp, persona — somewhere the user can audit.
- **No command execution at any stage.** A voice-driven shell is a different project with a different risk class, and
  nothing here needs it.

It depends on M5, because the skill and tool-call plumbing is what it extends; if it is wanted earlier, that plumbing moves
earlier with it. It also belongs after the voice loop is trusted rather than before it.


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
| A public-CA host pinned by fingerprint (`api.deepseek.com`) turns routine certificate rotation into a forced re-approval | A confusing `Cert changed!` prompt on a provider that did nothing wrong | Faithful to upstream, and it fails safe. Revisit at M7: pinning earns its keep on self-signed private hosts, and the system CA chain already covers public ones |

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
3. ~~**Default interaction on the desktop:** always-listening while the app runs, or a hotkey that
   arms listening for the session?~~ **Answered by M2's implementation (2026-10-16): neither is
   forced.** `--listen` arms listening at launch, so the hotkey case exists; the app does not arm
   itself, so a run that only wanted to chat is not listening to the room. Which one a user gets is
   which command their key is bound to — `celeste-linux` or `celeste-linux --listen` — and M4's tray
   is where an always-on preference would be stored if one is wanted.
   (Was: "Affects M2 and the hotkey design.")
4. **v1 scope:** is rooms + spontaneous messaging wanted in the first release, or is
   text + voice + images enough to ship and the rest follows?
5. **Local offline TTS:** rely on the distro's `espeak-ng` (thin, robotic, always available), or
   ship an optional piper voice download (better quality, about 60 MB per voice) alongside the
   gateway's Kokoro?
6. **Default gateway:** ship placeholder URLs or pre-fill the Voice Gateway / Ollama addresses of
   your own boxes (no credentials in any file)?
7. **Update check:** drop it for v1 (recommended, nothing published yet) or implement it against a
   releases endpoint?
