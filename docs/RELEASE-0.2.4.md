# Celeste 0.2.4

The Ollama-chat and local-speech follow-up to 0.2.3. The 0.2.3 TTS fixes (sentence splitting,
RIFF-chunk WAV durations, escaped word timestamps, eSpeak generation guard) are carried forward
unchanged.

## What changed

**Ollama chat**

- `think` is now sent as a top-level request field. Ollama 0.40 ignores `options.think`; a
  thinking-capable model such as `ornith-1.5:9b` therefore stayed in thinking mode, and hidden
  reasoning consumed `num_predict` — a 4096-token reply budget could end with an empty
  `message.content` and a blank assistant bubble.
- The history budget holds back **one eighth of the context window** for tokenizer drift.
  `estimateTokens` is chars/4, not the server's tokenizer: measured on the 5800x, a 32k persona
  filled the window to 32,672 prompt + 96 output tokens, then 32,724 + 44 — both replies stopped
  mid-sentence at the 32,768 ceiling. The headroom makes `Max Tokens` a real output reserve.

**Local speech**

- The eSpeak route publishes a playback fraction, which opens the teleprompter gate. Word
  timings are estimated char-proportionally from the text and the audio duration, the same
  fallback the UI already uses for engines without word timestamps.
- Stop and application close now send `spd-say -S` as well as killing the client. Killing the
  `spd-say` process alone left speech-dispatcher's `sd_espeak-ng` module speaking a queued
  utterance after the application was gone.

## Packages

| Package | Size | SHA-256 |
|---|---:|---|
| `celeste-linux-0.2.4-1-x86_64.pkg.tar.zst` (Arch) | 108 MB | `181d1fb1a0267ea9691fd21473c5cc84e7415f3958a6817a568a06120c37c421` |
| `celeste-linux_0.2.4_amd64.deb` (Debian) | 96 MB | `064fc4628f79f98bdaf8b9737203dfc62decff9530c219a002362a3b8b279926` |
| `celeste-linux-dbgsym_0.2.4_amd64.deb` | 1.4 MB | `1edba76967c6fc5fe1ff72ad106e1e3e1768edc59a98accdd74dd4c0d7bce324` |

Install:

```sh
# Arch
sudo pacman -U celeste-linux-0.2.4-1-x86_64.pkg.tar.zst

# Debian
sudo apt install ./celeste-linux_0.2.4_amd64.deb
```

The Debian package was built in the `packaging/debian/Containerfile` Debian 13 environment, so
its bundled runtime links Debian's glibc.

## Verification

- `./gradlew build` — green; 1061 core tests and 94 platform tests, 0 failures.
- Live Linux checks: stop-during-synthesis, stop-then-new-turn, full eSpeak playback, numbered
  lists, quoted dialogue/highlighting, and normal conversation across eSpeak/system/Kokoro all
  passed. Greek through Kokoro remains unavailable by design — the gateway answers "Greek has no
  TTS voice available — text only"; Russian was not tested.
- The missing-credential guard, `think` placement, history headroom and eSpeak fraction are
  pinned by their own tests; the `think` and credential tests were mutation-checked.

## Android

The corresponding Android changes travel as patches under `patches/` because `../VoiceAssistant`
is read-only for this repository (AGENTS.md §1): the gateway credential guard, and the Ollama
`think`/headroom fixes. Apply them on that branch with `git apply`.
