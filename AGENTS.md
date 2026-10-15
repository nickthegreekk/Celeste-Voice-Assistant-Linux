# AGENTS.md

Contract for any coding agent working in this repository. Read [README.md](README.md) for what this
project is, then [PLAN.md](PLAN.md) — PLAN.md is the specification. This file is the rules of
engagement. Where this file and the plan disagree, the plan wins and this file gets fixed.

---

## 1. The one absolute rule

`../VoiceAssistant` — the upstream Android app — is **READ-ONLY**.

Never write, move, rename, delete, `chmod`, reformat or "tidy" anything under it. You read from it
and copy out of it, and that is all. Every command you run that targets it must be a read
(`cat`, `grep`, `diff`, `find`, `wc`). If a task appears to require an edit upstream, **stop and ask
the user** — the correct answer is always that the change belongs in this repository, not there.

`tools/sync-from-android.sh` is the only sanctioned way to move files across. Use it. Do not invent
your own copy commands; the list of files that may be copied lives in that script.

Nothing outside this directory is yours either: not `$HOME/ComfyUI`, not `other mounts`, not
the user's `~/.config`. Do not start, stop or restart systemd services, and in particular do not
touch `comfyui`: the user cannot restart it without interactive authentication.

## 2. Shape of the project — decided, not open

From [PLAN.md](PLAN.md) §3:

- **D1** One JVM process. The service core and the UI run together; there is no IPC, no daemon
  protocol, no second executable to design. `--headless` is a flag, not a separate program.
- **D2** Compose Multiplatform for the UI. Not a new UI toolkit, not a web front end.
- **D3** Anything that comes from upstream keeps its **package** `com.nikosm.voiceassistant` and its
  **file name**. Do not rename, do not "improve" the package layout.
- **D4** All ported and shared code lives in **one** module, `:core`. Upstream uses `internal`
  visibility heavily; splitting the shared code across modules will not compile.
- **D5** Android's `Context` becomes `AppEnv` (paths, logger, process launcher, device info).
  `Log` becomes the project logger. Nothing else replaces `Context`.
- **D6** Audio goes through the `platform` implementations, which use helper processes.
- **D11** The build is a plain JVM Gradle project plus the Compose plugin. Do not introduce Kotlin
  Multiplatform targets.

Module boundaries:

```
:core      ported upstream files + done-and-tested logic. MUST NOT import from :platform or :desktop.
:platform  Linux implementations of the seams. May import :core.
:desktop   Compose UI, window, tray, packaging. May import both.
```

If you find yourself wanting `:core` to know about PipeWire, `notify-send`, XDG paths or the
filesystem layout, the answer is a new interface in `:core` and its implementation in `:platform`.

## 3. File verdicts are binding

**[2026-10-15, PLAN.md §7a.]** The verdicts stand, but every COPY file also has an *arrival milestone* (`m0`/`m1`) — take a file only when its dependencies are ported. And `android.util.Log` is handled by a shim at `core/src/main/kotlin/android/util/Log.kt`, the only file under an `android.*` package: **never "fix" a fully-qualified `android.*` call inside a COPY file** — extend the shim, or stop and ask.
[Same date: a declaration borrowed from a m1 file lives in its own file, with a header carrying the exact line `BORROWED FROM M1` and the name of the upstream file that will absorb it. `grep -rl BORROWED FROM M1 core/` is the M1 checklist, and a borrowed declaration is never left behind.]
[Tests follow the same discipline: a pure upstream test moves through `tools/sync-from-android.sh` byte-identical (it is on the `TEST_SOURCES` list), and a test that needs retargeting is adapted on purpose and recorded in `docs/TEST-MAP.md` — never quietly edited in place.]

[PLAN.md](PLAN.md) §7 classifies every upstream file as **COPY**, **ADAPT**, **PORT+** or **NEW**.
That classification is part of the specification:

- **COPY** files must remain byte-identical to upstream, forever. `tools/sync-from-android.sh --check`
  must report no differences for them before every commit.
- **If you must modify a COPY file**, that is a deliberate, recorded demotion: change its row in
  PLAN.md §7 *and* add an entry to `docs/PROVENANCE.md` saying what and why, **in the same commit**.
  Never edit a COPY file quietly.
- New behaviour belongs in **NEW** files, not in the body of a COPY file.
- When you adapt an upstream file, the ported logic keeps its semantics. The comments in upstream
  explain behaviour that was tuned against real use (the VAD pre-roll buffer is the clearest case —
  see PLAN.md §6). Those constants and invariants are not yours to change; keep them.

## 4. The seams

The fourteen platform seams are listed in [PLAN.md](PLAN.md) §5. Rules:

- The interface lives in `:core`; the Linux implementation lives in `:platform/…/linux`.
- `ProcessBuilder` / `Runtime.exec` appears **only** in `:platform`.
- Absolute system paths, `$XDG_*`, `~` appear **only** in `:platform`. `:core` receives paths from
  `AppEnv`.
- Tests never touch the real `~/.config`, `~/.local` or the real microphone: `AppEnv` is injectable
  precisely so a test can point at a temp directory and a fake capture source.

## 5. Secrets and privacy

- Never commit a credential, token, password or API key — and never put one in the repository,
  a test fixture or a config template.
- Credentials go through the `SecretStore` seam (libsecret) or the settings file, never in code.
- The upstream project is used with private, adult content; its prompts and images are the user's.
  Do not commit a real prompt, a real output image, or a transcript of either, as a test fixture.
  Fabricate fixtures.
- No telemetry. No analytics. No update check (PLAN.md §8 marks it dropped). No network call that
  the user did not configure, except the build's own dependency resolution.

## 6. Build and test

Once M0 has created the build:

```sh
./gradlew :core:test          # must pass before every commit that touches :core
./gradlew :platform:test
./gradlew :desktop:test
./gradlew build               # before every milestone commit
./gradlew :desktop:packageDistribution   # the jpackage app image
```

- Keep exactly one Gradle daemon; do not run builds in parallel or in the background. This machine
  also hosts a GPU service, and memory is shared with it.
- A build that leaves a Gradle daemon, a JDK, a database or an audio subprocess running after the
  session is a bug. Stop what you start.
- Tests must not depend on a real microphone, a real PipeWire server, a real Voice Gateway or
  network access. Use the fakes described in PLAN.md §11.

## 7. Commits

Commit as you go, before you report. Another committer works in this repository from outside it, so an untracked half-finished file left lying in the tree can be swept into an unrelated commit by a git add run elsewhere. A clean tree and an honest attribution are worth more than saving one commit.
Never leave a module in a non-compiling state. If an experiment fails, revert it before moving on, in its own commit, rather than leaving a red build while you go and read an API - a broken build hides the next real error.

**One exception, earned by the M1 service cluster (2026-10-15).** Where mutual references make a set of files compilable only together - the four AssistantService files reference each other cyclically, and the six COPY files are extension functions on the class they extend - that set lands as **one** commit, with the tree green immediately before and after it. Two conditions: the commit body declares the exception and enumerates what landed, and the tests that arrive with it are the review, because a seven-thousand-line diff is not reviewable by anyone. Name in the body which behaviour each new test proves, not just how many passed.

- One concern per commit. Prefix with the milestone: `M0:`, `M1:`, … `M7:`.
- Subject in the imperative, ≤ 72 characters. The **body explains why**, not what — this project's
  upstream house style is a long, reasoning-dense commit message, and matching it is deliberate:
  it is how the port stays reviewable by its author months later.
- Reference the plan section being implemented (`PLAN.md §7`).
- Never commit: `build/`, `.gradle/`, `local.properties`, IDE files, generated packaging output,
  downloaded models.
- Do not `git push` anywhere unless the user asks. There is no CI to add.

## 8. Context discipline

M0 measured 0.22 USD for 15.5M tokens, about 96% cache-hit input, so the whole port projects to single-digit dollars. Cost is NOT the binding constraint - correctness is - and the rules below exist to keep the work honest, not to save money.

- PLAN.md §7 is a map, not a substitute for the source: it says which file and which group, then **read the upstream file itself** before porting it. Port **one file at a time** - for correctness rather than thrift, so take as much upstream context as you need to port one file right.
- Run the affected tests after each change rather than batching them to the end. Every defect found so far came from compiling and executing, never from reasoning about the plan - and a plan paragraph describes a file, it does not replace it.
- Edit files in place. Do not paste a whole file back into the conversation to show a change.
- Prefer the plan's recorded decisions over re-deriving them, and say so if you think one is wrong
  rather than quietly working around it.
- Do not enable or use web search for this work unless the user asks; it bills extra requests.

## 9. Definition of done

A task is done when:

1. Upstream is untouched (`git status` in `../VoiceAssistant` is clean — check it, do not
   change it).
2. COPY files are still byte-identical (`tools/sync-from-android.sh --check`).
3. Behaviour changes have a test that fails before and passes after.
4. The ported upstream JVM tests still pass — they are the executable specification (PLAN.md §11).
5. Any deviation from the plan is written into the plan, in the same commit.
6. No new dependency was added without naming it in the commit body and pinning it in
   `gradle/libs.versions.toml`.

## 10. When the plan does not cover something

Ask the user. The upstream Android app is the **behavioural** reference for anything the plan omits:
read it, and port what it does. Do not invent requirements, do not redesign a feature because a
nicer approach occurs to you, and do not add features the plan lists as out of scope (PLAN.md §2).

## 11. First task

M0 is specified in [docs/BOOTSTRAP.md](docs/BOOTSTRAP.md). Start there, and stop at its acceptance
criterion before beginning M1.
