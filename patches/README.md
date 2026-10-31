# Android patches

These are the Android counterparts of fixes in this repository, delivered as patches because
`../VoiceAssistant` is read-only for this repository's agents (AGENTS.md §1). They target
`hardening-wip` at commit `cc6a257`.

| Patch | What it fixes | Linux reference |
|---|---|---|
| `android-gateway-credential-guard.patch` | Refuses gateway TTS/Test Voice before sending when a BASIC entry has no username/password or an API-key entry has no key; names the empty field. | `a594f11` |
| `android-ollama-think-flag.patch` | Sends `think` as a top-level request field; Ollama ignores `options.think`, which left thinking models burning `num_predict` and returning blank replies. | `61e44b1` |
| `android-context-headroom.patch` | Holds back an eighth of the context window from history, so a near-window prompt cannot leave the reply with only a few dozen output tokens. | `1a29b17` |

All three apply together from the Android checkout:

```sh
cd ../VoiceAssistant
git apply --check /mnt/SSD1TB/VoiceAssistant-Linux/patches/android-gateway-credential-guard.patch \
                     /mnt/SSD1TB/VoiceAssistant-Linux/patches/android-ollama-think-flag.patch \
                     /mnt/SSD1TB/VoiceAssistant-Linux/patches/android-context-headroom.patch
git apply         /mnt/SSD1TB/VoiceAssistant-Linux/patches/android-gateway-credential-guard.patch \
                     /mnt/SSD1TB/VoiceAssistant-Linux/patches/android-ollama-think-flag.patch \
                     /mnt/SSD1TB/VoiceAssistant-Linux/patches/android-context-headroom.patch
```

Tests to port:

- credentials: the three credential cases in `GatewaySynthesisTest`, mutation-checked against
  the guard; Android's harness is `GatewayResolutionTest` / the instrumented
  `GatewayCredentialResolutionInstrumentedTest`.
- `think`: `ThinkingFlagPlacementTest`, which captures the request body and asserts `think` is
  top-level and absent from `options`, for a thinking and a non-thinking persona.
- headroom: the budget arithmetic cases in `Fix6OverflowTest`, plus
  `MessageTimestampServiceTest`'s recomputation of the budget.

The eSpeak teleprompter and the speech-dispatcher stop fix are Linux-specific implementations
(seams 2 and 6); they have no Android counterpart in these patches.
