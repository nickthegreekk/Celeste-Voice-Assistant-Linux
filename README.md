# Celeste for Linux

A native Linux port of the **VoiceAssistant** Android app (package `com.nikosm.voiceassistant`,
UI name *Celeste*): a voice assistant that talks to your own LLM backends, your own Whisper/Kokoro
Voice Gateway, your own RAG server and your own image-generation server.

Native means native: a JVM desktop application built with Kotlin and Compose Multiplatform,
talking to the system's own PipeWire, DBus, libsecret and systemd. No Android emulator, no Waydroid,
no container running an APK.

> **Status: planning.** This directory contains the plan and this README. There is no source code,
> no build and no package yet. The work is broken into milestones in [PLAN.md](PLAN.md).

---

## What this is

| | |
|---|---|
| **Primary target** | Arch Linux (and derivatives: EndeavourOS, CachyOS, Manjaro) |
| **Second target** | Debian 13 "trixie" and derivatives (Ubuntu 24.04+) |
| **Session** | X11 (xfwm4) and Wayland, XDG-compliant |
| **Audio** | PipeWire via WirePlumber; ALSA/PulseAudio fallbacks |
| **Microphone** | Headset / headphones, or push-to-talk. See *No echo cancellation* below. |
| **UI** | Compose Multiplatform desktop, one window plus a system-tray icon |
| **Runtime** | JVM 25 (bundled into the package; no system JDK needed at run time) |

## What this is not

- **Not an emulator or wrapper.** No Waydroid, no Android-x86, no emulator image running the APK.
  The Android build depends on an arm64-only `libespeak-ng.so` and on Android's audio stack; on an
  x86_64 desktop it would need ARM translation and would still be a phone UI in a box.
- **Not a rewrite from scratch.** Roughly two thirds of the Android app is already
  platform-independent Kotlin — the HTTP clients, the ComfyUI workflow-token logic, the persona and
  settings model, the room-turn logic, the spontaneous-message scheduler — and around 20,000 lines
  of it are Jetpack Compose UI that Compose Multiplatform can host almost unchanged. Those parts are
  **ported, not reinvented**, and they keep their file names and their package so that upstream fixes
  can be copied in and reviewed as a diff.
- **Not a fork of the phone app's behaviour on purpose.** Where the port deviates it is because the
  desktop is a different machine, and each deviation is recorded in [PLAN.md](PLAN.md) §9.

## Why the port is smaller than it looks

The hard parts of Celeste already run on Linux:

- Speech-to-text is `POST /transcribe` on your Voice Gateway (Whisper). Already HTTP.
- Kokoro text-to-speech is `POST /synthesize` on the same gateway. Already HTTP.
- Chat is OpenAI-compatible `/chat/completions`, Ollama `/api/chat`, RAG `/retrieve` and `/ingest`,
  SearXNG for web search. Already HTTP.
- Image generation is ComfyUI `/prompt` + `/history` + `/view` and A1111 `/sdapi/v1/txt2img`.
  Already HTTP.
- Voice activity detection is Silero VAD through ONNX Runtime, which publishes an x86_64 Linux JVM
  build and takes the same `silero_vad.onnx`.

What genuinely has to be written is the layer Android was providing: audio capture and playback, a
long-running process instead of a foreground service, notifications, secret storage, scheduling,
tray and hotkeys, file dialogs.

## No echo cancellation, by decision

Android's `AcousticEchoCanceler` has no Linux counterpart, and the box will not be asked to invent
one. **Use headphones** (or an external mic that does not hear the speakers), and/or push-to-talk.
The capture code is written so a future PipeWire `module-echo-cancel` sink can be selected as the
input device with no code change: if the echo cancellation is ever wanted, it becomes a
WirePlumber/PipeWire configuration choice rather than an application feature.

## Requirements at run time

Installed by the package, not by hand:

| Purpose | Arch | Debian |
|---|---|---|
| Audio capture/playback | `pipewire`, `pipewire-audio`, `alsa-utils` | `pipewire-bin`, `alsa-utils` |
| Device/sink selection | `wireplumber` | `wireplumber` |
| Desktop notifications | `libnotify` | `libnotify-bin` |
| Credentials | in the settings file, mode 0600 — no Secret Service daemon needed | same |
| Local TTS fallback | `espeak-ng` | `espeak-ng` |
| Speech-dispatcher fallback | `speech-dispatcher` | `speech-dispatcher` |

`parec`/`paplay` from PulseAudio or `pipewire-pulse` are used when present; `arecord`/`aplay` are
the last fallback. No audio path is hard-wired to one of them.

## Build from source

Expectations for the finished project, so the shape is on the record:

```sh
git clone <this repo> celeste-linux && cd celeste-linux
./gradlew :desktop:run                  # run from source
./gradlew :desktop:packageDistribution  # jpackage app image under build/compose/binaries
./gradlew :core:test :desktop:test      # the ported test suites
```

Packaging:

```sh
packaging/arch/PKGBUILD                 # builds an Arch package (also usable from the AUR)
packaging/debian/                       # debian/ control files, built ON Debian, not on Arch
```

The `.deb` is built on a Debian machine (or a container) because a jpackage runtime carries the
build host's glibc. The Arch package is built from source on Arch. An AppImage is a possible third
artefact and is not planned for the first release.

## Where things live (XDG)

| Path | Contents |
|---|---|
| `~/.config/celeste-linux/settings.json` | All settings, personas, servers, skills — the ported settings blob |
| `~/.local/share/celeste-linux/` | Chat history per persona, room transcripts, persona artwork |
| `~/.local/state/celeste-linux/` | Logs (also sent to journald), usage-tracking counters |
| `~/.cache/celeste-linux/` | Utterance WAVs, downloaded images, model downloads |
| (no keyring daemon) | Credentials live in the settings file above, mode 0600. libsecret is not used in v1 — see PLAN.md §5a. |

The Android app's own export/import backup file can be imported, because the backup reader is pure
Kotlin and comes across with the rest. Persona artwork paths are rewritten into the Linux data
directory on import, exactly as the Android restore path already does for a different device.

## Licence and provenance

Celeste is **GPL-3.0**. This port is a derivative work and is therefore also **GPL-3.0**; the
original `LICENSE` text is kept verbatim. The upstream Android project lives at
`../VoiceAssistant` and is treated as **read-only**: nothing in this project ever writes
to it. Files shared with it are copied, never edited in place, and each one is recorded with the
upstream commit it came from in `docs/PROVENANCE.md` (planned, M0).

## Roadmap

See **[PLAN.md](PLAN.md)** for the architecture, the seam-by-seam design, the file-by-file
migration map, the milestones with their estimates, and the risks.
