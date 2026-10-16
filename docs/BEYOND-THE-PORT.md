# Beyond the port

Capabilities a desktop Linux host makes possible and an Android phone does not, collected so they are neither lost
nor folded into a port milestone. **Nothing here is in M0–M7's scope**, and this file is not a backlog to work
through: it is the answer to "what could this be on a desktop that it could never be on a phone", written down while
the details are fresh so the port can finish first. Each candidate gets what M8 got — a design pass, its
prerequisites named, and an acceptance criterion that is judged by use rather than by report.

## The thesis

The Android app outsources its heavy work: the language model to Ollama, speech recognition and synthesis to a Voice
Gateway, image generation to A1111 or ComfyUI. A desktop has the machine those services run on, so the same app can
stop outsourcing — a **fully local assistant with no network in the loop at all**. That is the desktop's advantage,
and it is one Android cannot match: not more models, but no reason to leave the box.

## 1. Local speech, both directions — the strongest candidate

Today the voice loop needs the gateway for `/transcribe` and optionally `/synthesize`. Locally neither is necessary:
whisper.cpp or ONNX Runtime on this machine's GPU for recognition, piper or Kokoro ONNX for synthesis. What it buys
is not quality but independence — the acceptance stops depending on a server on another host, a network hop leaves
the round trip, and nothing about the user's speech goes anywhere.

**Prerequisite to verify first, as a spike:** whether whisper.cpp's Vulkan or HIP backend builds and runs on gfx1030.
The ONNX Runtime ROCm execution provider is the shakier path, and ai-toolkit was already recorded as unverified on
this card (PLAN.md §13's risk note). Acceptance: **ten hands-free exchanges with the gateway switched off.**

## 2. A wake word — always-on that a phone cannot afford

An always-live microphone is what hands-free wants and what a phone's battery forbids. A local wake-word model
(openWakeWord, or a small ONNX classifier) lets listening be *gated*: the VAD stays cheap, nothing is transcribed
until the word is heard, and the room is not being recorded all day. Prerequisite: something that runs on CPU without
taking the GPU from generation, which on this box means sharing with ComfyUI. Acceptance: a day of ordinary use, with
the user judging how often it woke when it should not have.

## 3. Screen and vision — impossible on Android without a permission nobody wants

"What is on my screen" is a desktop question. X11 capture or PipeWire's desktop portal gives the frame; a local
vision model reads it. On Android this requires MediaProjection and a user-visible capture prompt every session,
which is why nobody uses it. Acceptance: ask about something visibly on screen and get an answer that names it.

## 4. Files — already recorded

Read and write on a path the user chooses, off by default, per persona, writes confirmed, the settings file and
`~/.ssh` always excluded (PLAN.md §10, M8).

## 5. Smaller integrations the phone cannot offer

MPRIS to pause whatever else is playing when a reply starts; clipboard read and write; `xdg-open` to hand a file to
another application; window and workspace queries; a systemd user unit so the assistant is present with no window
open. Each is small, none is urgent, and each is worth doing only when something asks for it.

## Sizes, and the one-model question (2026-10-16)

Asked directly: could one model do everything, and does it have to be gigabytes? The arithmetic, with the roles
kept apart because the architectures are:

| role | what it is | honest size |
|---|---|---|
| text, and vision | LLM or VLM, 7–9B at Q4 | 4–5 GB |
| speech in | Whisper-class, int8 | ~1.6 GB |
| speech out | Kokoro (82M) | ~0.3 GB, or 20–60 MB per piper voice |
| **speech, folded** | **an omni model: audio in, audio out, LLM inside** | 2–5 GB |
| images | diffusion — SDXL Q8, or the Z-Image lineage already here | 2.7 GB, or 7–12 GB |

**No single model does all of it, but voice is the exception worth knowing.** Omni speech models fold recognition,
the language model and synthesis into one file, so the whole voice loop becomes one download instead of three. Image
generation is not foldable: diffusion is a different architecture, and no LLM generates pictures anywhere near the
quality bar this project has already measured — Pony was rejected as "too cartoonish".

**Runtimes are closer to one than the models are.** llama.cpp hosts text, vision and audio models; stable-diffusion.cpp
hosts the image ones, and the Z-Image GGUF this box already uses was quantised for it. So "one runtime, several model
files" is true; "one file" is not.

**Which argues against bundling any of it.** This machine already runs ollama with twelve models, a voice gateway and
ComfyUI; a bundle would duplicate working software and, worse, ship native ROCm libraries for two distributions on an
old card — the packaging risk M6 has been most wary of, and the one whose surprises cost days. The app's existing
shape is already the right answer: one entry per capability, each pointing at whatever serves it. On the desktop that
means "local" is a URL, `127.0.0.1:11434`, rather than a bundle. What bundling would buy is an appliance that runs
with nothing else installed — a different product with a different budget, and not this milestone's.
