# Celeste — a voice assistant for Linux

Celeste is a desktop voice assistant that talks to **your own** models and services: speech in,
speech out, images, and text chat. It runs as a normal Linux application (Kotlin + Compose
Multiplatform): one process, real windows, a tray icon, PipeWire audio, notifications — no Android
emulator, no container, no cloud account.

It is the Linux port of the **Celeste / VoiceAssistant** Android app, keeping its behaviour,
its personas and its rooms, with the phone idioms replaced by desktop ones.

## What it does

- **Talk to it.** Push-to-talk or hands-free with on-device voice-activity detection (Silero VAD).
  Speech-to-text and text-to-speech go through your own Voice Gateway (Whisper / Kokoro), a
  system speech engine, or `espeak-ng`. Replies are spoken, replayable, and stop when you speak.
- **Chat with the models you choose.** Ollama and any OpenAI-compatible provider, per-persona
  models, streaming replies, reasoning display, token usage and cost tracking, suggested replies.
- **Generate images.** ComfyUI (including imported workflows) and A1111, rendered inline in the
  conversation with regenerate and save-to-gallery.
- **Personas and rooms.** Each persona has its own prompt, model, voice, theme and memories. Rooms
  put several personas in one conversation with ordered turns and a round cap.
- **Skills, schedules and memory.** Triggered skill instructions, a per-persona long-term memory,
  and skills that run daily at a set time.
- **Tools, with a gate.** Filesystem reads and writes (workspace-scoped, approval prompts, audit
  log), MCP servers, and terminal commands that ask per command — off by default.
- **Desktop-first.** A main window with a sidebar, Settings in its own window, a native menu bar
  and tray, desktop notifications, transcript search, per-persona folders in your home, and a
  colour style that can follow your GTK theme.
- **Private by design.** No telemetry, no analytics, no automatic update check. It talks only to
  the servers you configure; one optional button opens the releases page.

## Requirements

| | |
|---|---|
| **Distributions** | Arch Linux (and derivatives); Debian 13 "trixie" (and derivatives) |
| **Session** | X11 or Wayland, XDG-compliant |
| **Audio** | PipeWire (ALSA/PulseAudio fallbacks where present) |
| **Runtime** | Bundled JVM — no system JDK needed to run the packages |

Backends, at least one of which you already run:

| Purpose | Service |
|---|---|
| Chat (local) | Ollama |
| Chat (cloud) | Any OpenAI-compatible API — OpenAI, Anthropic, DeepSeek, Gemini, OpenRouter, … |
| Voice | A Voice Gateway with `POST /transcribe` (Whisper) and `POST /synthesize` (Kokoro) |
| Images | ComfyUI, or A1111 (`/sdapi/v1/txt2img`) |
| Optional | SearXNG for web search, a RAG server for documents |

**Use headphones.** There is no echo cancellation: Android's `AcousticEchoCanceler` has no Linux
counterpart, and a PipeWire `module-echo-cancel` sink is the right place for it if you need one.

## Install

Packages are built from this tree (see [Build from source](#build-from-source)); once a release is
published it will appear on the
[Releases](https://github.com/nickthegreekk/Celeste-Voice-Assistant-Linux/releases) page.

```sh
# Arch
sudo pacman -U celeste-linux-<version>-1-x86_64.pkg.tar.zst

# Debian (built on Debian)
sudo apt install ./celeste-linux_<version>_amd64.deb
```

Then start **Celeste** from your application menu, or run `celeste-linux`.

## Run it

```sh
celeste-linux              # the window
celeste-linux --listen     # start listening immediately (bind this to a hotkey)
celeste-linux --headless   # background process, reached through its tray icon
celeste-linux --selftest   # paths, audio devices and the upstream commit, then exit
```

Only one instance runs at a time. While a `--headless` process is up, launching the window says so
and exits — stop the background process (its tray's Quit, or `systemctl --user stop
celeste-linux.service` if you enabled the optional unit) before opening the window.

Both packages install a systemd **user** unit for the background process and deliberately do not
enable it. It needs a working `systemctl --user` session and a display that exports `DISPLAY`; on
systems without those, start `celeste-linux --headless` from your session instead.

## Build from source

Requires JDK 25 (for the build only; packages carry their own runtime).

```sh
git clone https://github.com/nickthegreekk/Celeste-Voice-Assistant-Linux.git
cd Celeste-Voice-Assistant-Linux
./gradlew :desktop:run                       # run from source
./gradlew :core:test :platform:test          # the test suites
./gradlew :desktop:packageDistribution       # jpackage app image
```

Packages are built from the same tree:

```sh
packaging/arch/PKGBUILD     # Arch, built on Arch
packaging/debian/           # .deb, built ON Debian (a jpackage runtime carries the build host's glibc)
```

## Where your data lives

| Path | Contents |
|---|---|
| `~/.config/celeste-linux/settings.json` | Settings, personas, servers, skills. Credentials are stored here, mode 0600 |
| `~/.celeste-linux/<persona>/` | That persona's `chat.json` and `images/` |
| `~/.celeste-linux/rooms/<room>-<id>/` | Room transcripts |
| `~/.local/state/celeste-linux/` | Logs, usage counters |
| `~/.cache/celeste-linux/` | Utterance WAVs, generated images |

Backups are a single `celeste-backup.json`: conversations, settings, personas, skills, rooms,
memories, artwork, attachments and generated images, encrypted with a password (AES-256-GCM) if you
ask for one. Cached speech audio is not included — it is regenerable.

## Licence and provenance

Celeste is **GPL-3.0-only**; the full text is in [LICENSE](LICENSE). It is a derivative work of the
Celeste / VoiceAssistant Android app (also GPL-3.0), and the ported files keep their original names
and package so upstream changes can be reviewed as diffs. The files shared with upstream, and the
commit each was taken from, are recorded in [docs/PROVENANCE.md](docs/PROVENANCE.md).

How the port is built, what was deliberately changed for the desktop, and why, is written down in
[PLAN.md](PLAN.md) and [docs/DECISIONS.md](docs/DECISIONS.md).
