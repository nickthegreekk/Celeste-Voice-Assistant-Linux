# Celeste 0.2.7

A live, full-screen HUD that reacts to your voice — and thinking is back for models the
old name list did not know.

## What changed

**The Live HUD.** A second window, not a restyle of the main one, so the conversation stays
usable beside it and the window can sit on another monitor. Open it from the main header's
menu (where it replaces the old **Phone mode** item — that toggle was inert on Linux because
a desktop has no earpiece route), from **View → Live HUD** (Ctrl+Shift+L), or with `--live`
at launch; `--live --listen` starts it already listening. It opens at 1100×700 and F11 or
the on-screen control toggles true fullscreen; Esc leaves fullscreen, a second Esc closes the
window.

The body is an arc-reactor composition: counter-rotating segmented rings, a tick ring, a
travelling waveform ring, a particle field, and real chrome (persona, state, model tokens,
clock). **LISTENING** reads the microphone's real peak amplitude; **SPEAKING** reads the
assistant's own voice, measured from the TTS PCM the process already holds (local eSpeak,
Gateway chunk WAVs, replays). **THINKING** runs an amber scan, **IDLE** breathes in sky blue,
and SPEAKING returns to the persona's colour. The transcript shows the last user line and the
last assistant reply with the existing karaoke highlight. System TTS exposes no samples, so it
uses a speech-shaped fallback cadence rather than claiming a measurement. The Celestial UI is
unchanged.

**Thinking restored.** Ollama 0.40 reads `think` from the request root, not from `options`;
the 31 October fix moved it there, but the port's DeepSeek-only name list then sent an
explicit `think:false` to Ornith and QwQ, so those models stopped reasoning. The flag now
follows the model's actual capability: the name list includes QwQ and Ornith, and an unknown
name is still thinking-capable when the live Ollama catalog has fetched its `thinking`
capability. The streaming chat, spontaneous and scheduled-skill request builders all use it;
the persona's own **Thinking** toggle still wins.

**COPY sync.** The m1 group was synced to upstream `99155cf`. `AssistantServiceSpontaneous.kt`
is byte-identical again (upstream had already made the same placement plus the capability
fix), the Welcome screen's example URLs moved to the documentation IP range, and the pinned
upstream commit moved. `tools/sync-from-android.sh --check` now reports **no drift**; the two
files it had been flagging since before 0.2.6 are resolved.

## Packages

| Package | Size | SHA-256 |
|---|---:|---|
| `celeste-linux-0.2.7-1-x86_64.pkg.tar.zst` (Arch) | 112,609,497 B | `a3a92f86c7f02933ba5417257180c3478a1ff4593d70dd403adcbc5617c1b5f1` |
| `celeste-linux_0.2.7_amd64.deb` (Debian) | 100,810,160 B | `df97501a45f4b680c760a1b259466f9ad317486c31bc0a1efff6f6cfdf33df51` |
| `celeste-linux-dbgsym_0.2.7_amd64.deb` | 1,452,192 B | `5132e4b873754950e35d9f4d944a59fdc19fd33fed2897b9ada17d384afdebe5` |

Install:

```sh
# Arch
sudo pacman -U celeste-linux-0.2.7-1-x86_64.pkg.tar.zst

# Debian
sudo apt install ./celeste-linux_0.2.7_amd64.deb
```

The Debian package was built in the `packaging/debian/Containerfile` Debian 13 environment,
so its bundled runtime links Debian's glibc.

## Verification

- `./gradlew build` — green; 1,177 core tests and 104 platform tests, 0 failures.
- Both artefacts were extracted and searched: `LiveHudKt`, `AudioEnvelope`,
  `isThinkingModel` and the `ornith` name are present in the bundled `core` jar, and
  `APP_VERSION` reads `0.2.7`.
- `tools/sync-from-android.sh --check` — **no drift**; the pin matches upstream HEAD
  `99155cf4c84593c004d55966bec07d53d5ee2f1e`.
- `tools/network-audit.sh` — every URL literal is recorded in PLAN.md §5; no new call is
  added by this release.
- The Live HUD was exercised in a live dev launch: the window opened, painted and rendered
  the transcript, and the layout was adjusted from that run (sky-blue idle accent, 960 dp
  transcript column, 1100×700 window). The thinking regression is pinned by
  `ThinkingFlagPlacementTest`: DeepSeek by name, Ornith by name, an unknown name with the
  catalog capability, and the persona toggle overriding the catalog.
