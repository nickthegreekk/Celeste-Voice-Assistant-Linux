# Test map

Which upstream test becomes what.

Upstream has two test trees, and they port differently. The **JVM tests** (`app/src/test`, 45 files)
are the executable specification for the logic the port shares, and PLAN.md §11 says they are the
reason the port can be trusted at all. The **instrumented tests** (`app/src/androidTest`, 59 files)
mostly drive Android itself — a device, an Activity, a WorkManager — and their fate is decided one
by one rather than by a rule.

A test that can run unchanged is a copy: it goes through `tools/sync-from-android.sh`'s
`TEST_SOURCES` list and stays byte-identical, so an upstream improvement arrives as a diff and the
assertions cannot drift. **A test that has to be retargeted is deliberately not on that list**, it
is adapted on purpose, and it is recorded here — the same demotion rule that applies to a COPY file
(AGENTS.md §3), pointed at tests.

## Ported JVM tests — byte-identical (`TEST_SOURCES`)

| Test | Group | Tests | Notes |
|---|---|---:|---|
| `ComfyWorkflowValidationTest.kt` | m0 | 13 | unchanged; JUnit 4 only |
| `ComfyPromptFillingTest.kt` | m0 | 10 | unchanged; JUnit 4 + kotlinx-serialization |
| `ComfyFixtures.kt` | m0 | — | not a test class; the shared fixture both of the above build from |

## Ported JVM tests — retargeted

| Upstream test | Now | Upstream at port time | Why it could not be copied |
|---|---|---|---|
| `SettingsManagerCorruptionTest.kt` | `core/src/test/…/SettingsManagerCorruptionTest.kt`, 41 tests | `988a023bd3ae781ae2166f25826a14db098a39913fd5c4c5a09f8955dc3e346c`, 789 lines | The premises are Android's. Upstream's harness fakes `Context` and `SharedPreferences` and relies on the constructor landing in its plaintext-fallback branch when `EncryptedSharedPreferences` cannot initialise; the port has no encrypted store to fall back from (PLAN.md §5a), so there is no such branch and nothing to fake at that level. Its `FakeAppContext` becomes a fake `AppEnv` and its `FakeSharedPreferences` an in-memory `SettingsStore`. **Every assertion is upstream's, unchanged** — including the payload-level assertions on the backup format, which are what pins the Android-backup interop guarantee. |

**The "upstream at port time" column is the guard, and it is worth being honest about what
kind.** A COPY test is protected by `tools/sync-from-android.sh --check`, which fails the build
the moment upstream's bytes move. A retargeted test has no such protection and never will — it
is deliberately not byte-identical, so there is nothing to compare byte-for-byte. What the hash
buys is that drift is *detectable* rather than invisible: the planned `tools/check-upstream.sh`
(PLAN.md §4, §13.4) takes these hashes as the baselines it diffs against, and until that exists
the comparison is one command run by hand. Without a baseline it is not a command at all.

So: **every row added to this table records the upstream file's sha256 and line count at the
moment it was ported.** That is the whole point of the column, and a row without it is a test
nobody can ever re-check.

## Instrumented tests — triage

Not yet done. PLAN.md §11 says the 59 files (20,889 lines) are triaged here, and the triage belongs
with the milestone that reaches each area: a Compose-device test cannot be judged before the screen
it drives exists, and a `WorkManager` test cannot be judged before the scheduler does. Filling this
in early would be guessing at what a test needs rather than reading it.

The categories PLAN.md §11 already names, so the shape is on the record:

| Upstream kind | Fate |
|---|---|
| Compose UI tests | move to the desktop `runComposeUiTest` runner |
| `AndroidJUnit4` / `WorkManager` / `TestWorkerBuilder` | rewritten against the seams (M4, with the scheduler) |
| shortcut and permission tests | retargeted or deleted with a written reason (M4: `.desktop` entries; permissions have no counterpart) |
| service-lifecycle / isolation rules | re-expressed against the ported service core, which is an ordinary object here rather than an Android `Service` |

Each row above becomes a per-file line as the milestone that owns it lands, with the reason
recorded whether the test was kept, rewritten or dropped.
