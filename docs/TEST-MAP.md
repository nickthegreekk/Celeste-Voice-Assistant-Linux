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
| `ScriptLanguageDetectionTest.kt` | m0 (arrived in M1) | 12 | unchanged; JUnit 4 only. The group is about what compiles together, not which milestone it belongs to: this one tests a pure function and needs no service. It is the executable specification for the detector that decides which voice a reply is spoken in. |

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

The port-side changes since it was retargeted, so they are not mistaken for upstream drift:
`FakeAppEnv` gained `notifier` and `scheduler` when those two seams were forced onto `AppEnv` by the
m1 COPY files, and both throw `UnsupportedOperationException` rather than returning something
plausible — no settings test posts a notification or arms a timer, so a fake that answered would be
a fake hiding a call. **No assertion changed.**

## Hand-written tests

Not from upstream — these exist because the port changed something, or because the thing being
tested had no test upstream at all. Their job is different from the two tables above: a ported test
says "the behaviour is still what it was", a hand-written one says "the *change* is what is
written down", and each name below states which behaviour it proves rather than how many assertions
it makes.

| Test | Tests | What it proves |
|---|---:|---|
| `TtsChunkSequencerTest.kt` | 11 | The chunked-TTS sequencing, with no service, no audio hardware and no M2 implementation — which is what PLAN.md §6 requires and why the policy was extracted. Named behaviours: chunks play one at a time in order and the utterance ends `Finished`; **a completion arriving after a cancel does not advance the sequence**; **a second utterance cancels the first and the superseded one cannot resume alongside its replacement**; **a chunk that fails aborts the utterance rather than skipping ahead** (upstream's abort is a decision; its synthesis-failure `continue` is a quirk — the test name records that this is a choice); the estimate extrapolates from the measured chunks to the whole reply, and a chunk whose duration is not yet known reports *no* total rather than a wrong one; `awaitEnd` settles only when the last chunk finishes and reports `Stopped`, not `Finished`, for a cancelled sequence; and the `onStarted` hook fires once, as the first chunk begins, and never again. |
| `AssistantServiceAudioTest.kt` | 22 | The genuinely pure third of the audio engine: `cleanTextForTts`'s three emoji shapes (face, symbol, flag) and that ordinary prose and Greek survive untouched — a class widened too far is a worse bug than the one it fixes; `splitIntoTtsChunks`'s boundaries; `wavDurationSeconds` reading back the header `WavFile` writes. |
| `ScriptRangeBoundaryTest.kt` | 11 | Where each script range starts and stops, to the code point, because `detectScriptLanguage` decides which voice a reply is spoken in and several of its boundaries are *unassigned* code points. A tidy-up that replaced the escapes with the characters they denote, or a paste that picked up a lookalike, would move a boundary without the line looking any different — and a wrong-language utterance is diagnosed by ear, months later. |
| `FreshStartNetworkTest.kt` | 4 | **A first run must not reach the network by itself** (AGENTS.md §5). Written because the rule was broken once and nothing noticed: `syncOpenRouterPricing()` fired from `start()` on every launch, and on a fresh install its throttle timestamp is zero, so the first launch always called `openrouter.ai`. Two assertions, because either alone is defeatable — the store must gain no pricing key (what the first launch would have failed in a networked environment), and a JVM-wide `ProxySelector` must observe **no request to any host** (what makes it hermetic: the store assertion alone would pass on an airgapped CI box even with the call restored, because the call would fail and write nothing). The second test is the one that keeps the first honest: it calls the sync deliberately and asserts the witness sees `openrouter.ai`, so a first test that passes because the feature was accidentally deleted — or because the witness stopped observing — fails instead. **Verified by restoring the bug**: with `syncOpenRouterPricing()` put back, the test fails; without it, it passes. Three more cover the second instance of the same class — `the second run is silent too, which is where the seeded provider template lives` runs launch → seed the templates → launch, because a first run has nothing to fetch and it is the second that reads the seeded `"OpenAI-Compatible"` entry back and walks it; `configuring a provider does fetch, which is what makes the silence above a choice` points that entry at a URL of the test's own and asserts the request arrives, so the silence cannot be the feature being dead; and the price-sync test above. **Verified the same way**: with the old `icon == "C"` guard restored, the second-run test fails and the other three pass, which is the check that it is testing the second run and not the first. The first version of it did *not*, for the reason its own KDoc warns about — `start()` launches on `Dispatchers.IO`, which the test scheduler does not drive, so the assertion ran before any request could have been made. Proving a negative needs a window, not a signal. |
| `LogSinkTest.kt` | 6 | The shim's contract: a COPY file's `android.util.Log` call reaches the configured logger, at the right level, with the throwable form intact. |
| `FakeAudioPlayback.kt` | — | Not a test class. The double for seam 2, which **enforces** the one-playback-at-a-time contract rather than tolerating violations — a fake that allowed two would let the sequencer be wrong without saying so. |

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
