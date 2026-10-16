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
