# Bootstrap — how to start, and how to not burn the budget

Read [../AGENTS.md](../AGENTS.md) first (it is the contract), then [../PLAN.md](../PLAN.md) (it is
the specification). This file is about the mechanics: the harness, the machine, and M0.

---

## 1. The machine

The build runs on **buildhost** (`withheld`), in `$HOME/VoiceAssistant-Linux`. That box also
hosts ComfyUI on a 12 GB GPU, and **ComfyUI is stopped for the duration of the build** to give the
JVM the whole 32 GB. Two consequences:

- Do not start, stop or restart any systemd service. The user stops and restarts `comfyui`
  themselves, because `nikos` cannot restart it without interactive authentication.
- Do not leave heavy processes behind: no background Gradle daemons beyond one, no orphaned
  capture/playback processes, no JVM left running after a test.

Toolchain already present: `java` 17.0.20, `/usr/bin/gradle`, `git`, `dpkg`, `pacman`. The project
targets **JDK 25** for the build and the bundled runtime — originally 21, changed on 2026-10-15
because this box carries 17, 25 (Temurin LTS under `~/.gradle/jdks`) and 27, Kotlin 2.4.10's
compiler stops at JVM_26 so 27 cannot be a target, and 25 was already present. Deviation recorded
in PLAN.md §10. The Gradle wrapper version is pinned in M0 step 1
and is the source of truth — do not rely on the system `gradle` after that.

## 2. The harness
**Front door: `tools/dev-session.sh`.** It resolves and verifies your DeepSeek key, resolves the flash model id, prints your balance, asks for your sudo password once, stops ComfyUI, unloads any resident ollama model, launches the harness here, and offers to start ComfyUI again when the session ends. `--check` shows key, model and balance and changes nothing; `--gate-only` frees the machine and skips the agent. The rest of this section is what the script sets up, for reference.

The build talks to **DeepSeek's own API**, not through a compatibility layer. That is deliberate:
an Anthropic-format shim silently maps model names, and one of those mappings is ~5× the price.

### opencode (recommended)
(Not installed on buildhost as of 2026-10-15. Claude Code 2.1.238 is, at `~/.local/bin/claude` — which is not on the PATH a non-interactive shell sees, so the script prepends `~/.local/bin` itself.)

Terminal-native, provider-agnostic, reads `AGENTS.md`, runs happily over SSH.

1. Install opencode per its own documentation (install script, npm or package manager), then confirm:
   `opencode --version`.
2. `cd $HOME/VoiceAssistant-Linux && opencode`, then `/connect` → **DeepSeek** → paste the API
   key from https://platform.deepseek.com/api_keys. The key is stored in
   `~/.local/share/opencode/auth.json`; it is never committed and never written into this repo.
3. Run `/models`, note the **exact flash model id**, and pin it in `opencode.json` at the repo root
   (create it as the first commit of M0 and add it to `.gitignore` if it ever holds a secret):

   ```json
   {
     "$schema": "https://opencode.ai/config.json",
     "model": "deepseek/<the-flash-id-you-just-read>",
     "provider": { "deepseek": { "blacklist": ["<the-v4-pro-id>"] } }
   }
   ```

   The `blacklist` is the point: with v4-pro hidden from the picker, a mis-click cannot quietly
   multiply the bill. **Verify the ids — do not trust an id written down anywhere, including this
   document.**

### Claude Code (the alternative, and its trap)

Claude Code works against `https://api.deepseek.com/anthropic`. If you use it, set every model slot
explicitly, because the mapping is by model name and `claude-opus*` maps to **deepseek-v4-pro**:

```sh
export ANTHROPIC_BASE_URL=https://api.deepseek.com/anthropic
export ANTHROPIC_AUTH_TOKEN=<DeepSeek API key>
export ANTHROPIC_MODEL='deepseek-flash[1m]'
export ANTHROPIC_DEFAULT_OPUS_MODEL='deepseek-flash[1m]'
export ANTHROPIC_DEFAULT_SONNET_MODEL='deepseek-flash[1m]'
export ANTHROPIC_DEFAULT_HAIKU_MODEL='deepseek-flash'
export CLAUDE_CODE_SUBAGENT_MODEL='deepseek-flash'
export CLAUDE_CODE_EFFORT_LEVEL=max
export CLAUDE_CODE_AUTO_COMPACT_WINDOW=786432
```

Never switch the session model to Opus while working here. Also note `cache_control` is ignored on
that endpoint — DeepSeek's context cache is automatic, so the cheap cache-hit rate applies only
while the conversation's prefix stays stable. Which leads to:

## 3. Budget discipline

The whole port is budgeted at about **$50** of DeepSeek tokens (flash, off-peak: $0.60/M output,
$0.15/M cache-miss input, $0.003/M cache-hit input — peak hours double everything and are
01:00–04:00 and 06:00–10:00 UTC, Mon–Fri).

What that means in practice:

- **Do not re-explore the tree.** The map is PLAN.md §7. Read the plan section, then the one file.
- **Do not rewrite PLAN.md or AGENTS.md mid-session if you can append instead.** They are the
  cached prefix; rewriting them invalidates the cache for the rest of the session.
- **Do not paste whole files back into the conversation** to show a change. Edit in place.
- **Do not enable web search.** It bills extra requests and this work needs no network beyond the
  package manager.
- **Checkpoint at every milestone boundary, for drift rather than budget.** Keep an eye on the usage page, but the risk these checkpoints exist to catch is scope creep - a milestone sliding past what was asked - not money. M0 measured 0.22 USD for 15.5M tokens, about 96% cache-hit input, so the budget has large headroom; the earlier rule about stopping if M0+M1 exceeded 10 USD is retired.

## 4. What is already in the repository

```
README.md                     what this is
PLAN.md                       the specification
AGENTS.md                     the contract
tools/sync-from-android.sh    the only sanctioned way to copy upstream files (COPY list = PLAN §7)
docs/BOOTSTRAP.md             this file
```

Everything else is created in M0. `git log` already has an initial commit; every step below is a
commit of its own.

Upstream is at `../VoiceAssistant`, commit `fe584ad01c39f996d692ac5547611c040dc44eb3` at the
time this was written, and it is **read-only**.

## 5. M0 — skeleton, in order

**[Corrected 2026-10-15 — PLAN.md §7a supersedes this section's M0.2 and M0.3.]** M0.2: run `tools/sync-from-android.sh --group m0`, not the bare script (it now refuses on purpose). The six m1 files arrive in M1, with the `AssistantService.kt` they extend. M0.3: the real file set is the six m0 COPY files (2,046 lines) plus three hand-written ones — the `android.util.Log` shim, `GatewayUrl.kt` and `Models.kt` — and nothing else. If all twelve were already copied in one go, `git rm` the six m1 files out of `core/`: `--group m1` brings them back byte-identical in M1, and upstream is the source of truth, so nothing is lost.

Do these in order, committing after each. Do not start M1 until all six are done.

**M0.1 — Gradle skeleton.** `settings.gradle.kts` with three modules (`:core`, `:platform`,
`:desktop`), `gradle/libs.versions.toml`, one root `build.gradle.kts`, the Gradle wrapper
(`gradle wrapper --gradle-version <current stable>`, then always `./gradlew`), and a JDK 21
toolchain. Match the Kotlin version the upstream app pins (2.4.10) and use the Compose Multiplatform
release that pairs with it; pin the Compose Compiler plugin to the Kotlin version exactly.
`:core` is a plain JVM library with `kotlinx-serialization`, `okhttp`, `coroutines`, `onnxruntime`
and `junit`. No Android plugin anywhere.
*Accept:* `./gradlew projects` lists the three modules; `./gradlew build` succeeds on empty sources.

**M0.2 — the COPY files.** Run `tools/sync-from-android.sh`. It writes the 12 pure Kotlin files into
`core/src/main/kotlin/com/nikosm/voiceassistant/`, plus `LICENSE` and
`assets/silero_vad.onnx`. Run `tools/sync-from-android.sh --check` and confirm it reports no
differences. Create `docs/PROVENANCE.md` and record, per file, the upstream commit the sync printed.
*Accept:* `--check` is clean; nothing under `../VoiceAssistant` has changed.

**M0.3 — the dependency closure. This is the real work of M0.** The COPY files are not
self-contained: they reference types defined in upstream files that are *not* on the copy list
(`Models.kt` is the obvious one). Do **not** copy the other 34 files "to make it compile". Instead,
let the compiler name what is missing, bring across **one** file at a time as an ADAPT file in
`:core`, and prefer the smallest type that satisfies the reference. Expect this to reach maybe 3–6
files (most likely `Models.kt` and one or two service files whose pure functions are already
referenced). Every file you bring across gets a `docs/PROVENANCE.md` line.
*Accept:* `:core` compiles; the diff of the upstream tree is still empty; the commit body lists
which files were pulled in and why.

**M0.4 — the ported JVM tests.** Copy the upstream JVM tests that cover the ported logic (start with

[Corrected 2026-10-15: tests arrive through the same sanctioned path now — `tools/sync-from-android.sh --group m0` copies `ComfyWorkflowValidationTest.kt`, `ComfyPromptFillingTest.kt` and `ComfyFixtures.kt` byte-identical into `core/src/test/kotlin/`, and `--check` covers them. All three are pure `org.junit` (one also uses kotlinx-serialization), so the two known differences described below do not arise for them: `isReturnDefaultValues` and the `org.json` shim belong to the other, Android-coupled tests, which are adapted on purpose and recorded in `docs/TEST-MAP.md`.]
the ComfyUI/workflow-token, timestamp, scheduler and resolution tests), retargeting anything
Android-specific. Two known differences: upstream sets `unitTests.isReturnDefaultValues = true` and
shims `org.json` for tests — neither applies to a JVM project, so use the real dependency. Every
test that fails because of a genuine porting mistake is a bug to fix; a test that fails because it
asserts Android behaviour is a `docs/TEST-MAP.md` entry.
*Accept:* `./gradlew :core:test` green.

**M0.5 — `--selftest`.** A headless entry point that prints the resolved XDG paths, the upstream
commit it was built against, the Python-free audio device probe result (a stub at this stage), and
exits non-zero if the settings directory is not writable.
*Accept:* `./gradlew :desktop:run --args="--selftest"` prints the paths and exits 0.

**M0.6 — tag it.** `git tag m0`. Write the first entry in `docs/PROVENANCE.md` how-to if the format
needed adjusting. Report to the user: milestone spend from the DeepSeek dashboard, and whether the
M0 experience suggests M1 is the 8–12 days the plan claims.

## 6. The first prompt

Paste this to start, and nothing more until M0 is done:

> Read AGENTS.md, then PLAN.md, then docs/BOOTSTRAP.md. We are doing M0 only, the six steps in
> docs/BOOTSTRAP.md §5, one commit per step. The upstream Android project at
> ../VoiceAssistant is read-only — never write there, and use tools/sync-from-android.sh
> for every copy. Do not copy upstream files that are not on the COPY list unless the compiler
> names them as missing, and ask before bringing across anything beyond Models.kt and one or two
> other files. Do not start M1. Commit after each step with a body explaining why, and stop and ask
> if a step turns out to be different from how the plan describes it.

## 7. After M0

M1 is the text-first application (PLAN.md §10): `AppEnv`, the settings store and secret store, the
persona and provider model, the chat orchestration, the main window and the settings screens. Its
acceptance criterion is a real conversation with Ollama and one cloud provider, surviving a restart,
with an imported Android backup visible. Work it the same way: one commit per concern, plan section
in the commit body, `./gradlew build` before the milestone commit.
