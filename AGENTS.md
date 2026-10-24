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
It carries three modes with different jobs: `--group m0|m1` copies a COPY or test file byte-identically; `--seed <name>` places one PORT+ file as a verbatim starting copy, so the port is reviewable as a `diff -u` against upstream instead of being retyped; and `--check` reports drift for copies only. `--seed` refuses a name that is not on `PORT_SOURCES`, refuses a destination that already exists, and never counts what it placed as drift — a PORT+ file is expected to differ, and a COPY file must never be on that list.

Nothing outside this directory is yours either: not `$HOME/ComfyUI`, not other mounts, not
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
:desktop   the window, the tray, packaging and `Main.kt` — the parts that were never ported. May import both.
           It does NOT hold the ported screens: upstream declares 301 members `internal` in a single-module
           app and Kotlin does not share `internal` across modules, so every ported file stays in `:core`.
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
  Fabricate fixtures. A **real backup file** is never committed either — `voice_assistant_backup*.json` is in
  `.gitignore` because one carries API keys, server passwords and the user's own persona artwork. A
  fabricated backup, with invented values, is what the backup-interop fixture needs.
- No telemetry. No analytics. **No automatic update check.** The About window's **Check for
  updates** button is the one manual check (PLAN.md §5 [4]); it is user-initiated, sends no user
  data beyond the application's version, and never downloads or installs. No other network call
  that the user did not configure, except the build's own dependency resolution.
- **An automatic model fetch requires a provider the user has configured (2026-10-16).** Configured
  means a key, or a base URL that differs from the template the application seeds. An unconfigured
  template waits for the button. The rule exists because the old guard tested the provider's *kind*
  rather than the user's intent — `icon == "C"` — and the seeded `"OpenAI-Compatible"` template has
  that icon, so from the second launch onward the application called `http://0.0.0.0:11434/v1/models`.
  **Loopback is not a carve-out:** a rule with one stops being greppable, and a seeded `0.0.0.0` means
  the host is whatever answers there on the installed machine. The two user-initiated triggers are the
  connection test and saving a provider; both reach `fetchCloudModels`.
- **That last rule is enforced by auditing, not by memory (2026-10-15).** It was being read as a list of dropped
  features, until a run made a real request to `openrouter.ai` on a fresh install, and a second call of the same
  shape was found behind a default-seeded provider entry. So: grep every hardcoded URL and every HTTP call site in
  `:core` and `:platform` before a milestone closes, and ask of each whether it can fire *without* a user action.
  `FreshStartNetworkTest` witnesses JVM-wide requests for a first run and for a second, because a
  seeded default entry only exists on the second. `tools/network-audit.sh` does the grep and fails on any URL
  literal not recorded in PLAN.md §5's table, where each one carries what makes it safe.

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
- **A running application and a build do not share the machine (2026-10-16).** `:desktop:run` loads
  `:core` from its jar, and a build that rewrites that jar under a running application breaks the
  application at the *next* class it has not loaded yet — measured: a launch at 18:51:59, `:core:jar`
  rewritten at 18:55:26 by a verification build, and a click at 18:56 that opened an image and died
  with `NoClassDefFoundError: FullScreenImageViewerKt` inside a composition. It reads like a defect
  in the code that never ran, which is what makes it expensive: the first diagnosis blamed the image
  viewer. So check before building — `pgrep -x celeste-linux` — and if the application is up, ask
  before rebuilding. After any build, a running application must be restarted rather than trusted.
  **[2026-10-17: the check used to say `pgrep -f voiceassistant`, and that misses the installed
  package entirely — its launcher and process are named `celeste-linux`. That is how a full build
  ran beside a running application and reported "not running"; it was harmless only because the
  installed package loads `/usr/lib/celeste-linux`, not the build tree. The rule's failure mode is
  the `:desktop:run` case above, and that case would have been broken.]**
- A build that leaves a Gradle daemon, a JDK, a database or an audio subprocess running after the
  session is a bug. Stop what you start.
- Tests must not depend on a real microphone, a real PipeWire server, a real Voice Gateway or
  network access. Use the fakes described in PLAN.md §11.
- **Every test that builds a service shuts it down in teardown (2026-10-15).** They did not, so the state
  collector and the periodic health check outlived each `runTest` and resumed on a torn-down dispatcher. Kotlin
  reports that as an uncaught exception in *whichever test runs next*: four passed, the fifth failed with
  `UncaughtExceptionsBeforeTest` and no clue where it came from. Register what you build and tear it down — a
  leaked coroutine is never its own problem.

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

## 10. Deciding without stopping

**Decide, record, keep going.** A fork the plan's existing rules already answer, or one that reverses in a single
commit, is yours to call: pick the option you would recommend, implement it, and record it in
[docs/DECISIONS.md](docs/DECISIONS.md) in the same commit — what the fork was, what you chose, what you rejected
and why. That record is what makes a decision reviewable *afterwards*, in the diff, instead of blocking
*beforehand*.

**Stop and ask for exactly four things:**

1. changing a COPY file's verdict, or deleting an upstream call (either way the marker goes in the same commit);
2. adding a dependency;
3. contradicting something the plan records — the seam list, the module boundaries, or a decision in
   [docs/DECISIONS.md](docs/DECISIONS.md);
4. anything you cannot reverse in one commit.

**Otherwise proceed and flag it.** Name the decision in your report so it can be disputed, but do not wait for an
answer before continuing.

**Batch the stops.** If more than one of those four applies, collect them and raise them together at the end of a
file or a session. A question per fork costs the project a round trip per fork.

A plan that is approximate is not a contradiction: "§7a says three to six files and the compiler named eight" is
the plan being approximate, and the rules already say what to do. The upstream Android app stays the
**behavioural** reference for anything the plan omits — read it and port what it does. Do not invent
requirements, do not redesign a feature because a nicer approach occurs to you, and do not add features the plan
lists as out of scope (PLAN.md §2).

## 11. First task

M0 is specified in [docs/BOOTSTRAP.md](docs/BOOTSTRAP.md). Start there, and stop at its acceptance
criterion before beginning M1.
