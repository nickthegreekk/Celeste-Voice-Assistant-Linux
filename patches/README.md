# Android patches

`android-gateway-credential-guard.patch` is the Android counterpart of commit
`a594f11` in this repository. It targets `../VoiceAssistant` on branch
`hardening-wip` at commit `cc6a257`, and touches only
`app/src/main/java/com/nikosm/voiceassistant/AssistantServiceGateway.kt`.

Apply from the Android checkout:

```sh
git apply --check /mnt/SSD1TB/VoiceAssistant-Linux/patches/android-gateway-credential-guard.patch
git apply         /mnt/SSD1TB/VoiceAssistant-Linux/patches/android-gateway-credential-guard.patch
```

What it does: refuses gateway synthesis and the Test Voice action *before*
building a request when a BASIC entry has no username or password, or an
API-key entry has no key. The message names the empty field and the Servers
entry; an unauthenticated request is never sent. Android surfaces it through
the foreground notification; the Linux version additionally carries it as the
no-audio reason in the transcript.

Tests to port: the three credential cases in `GatewaySynthesisTest` in this
repository, which are mutation-checked against the guard. The Android JVM
harness is `GatewayResolutionTest`; the instrumented
`GatewayCredentialResolutionInstrumentedTest` covers the resolved-credential
path.

The patch was generated without writing to `../VoiceAssistant`; AGENTS.md §1
keeps that checkout read-only for this repository's agents.
