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
| `ServerIdentityTest.kt` | m7 | 15 | unchanged; Color + JUnit only. An upstream JVM test that arrived after the M0/M1 harvest and was missed until the instrumented triage pointed at its service-side sibling; its subject (`ServerIdentity.kt`) has been a COPY file since m0. Added to `TEST_SOURCES` in the m7 group, so `--check` covers it. |

## Ported JVM tests — retargeted

| Upstream test | Now | Upstream at port time | Why it could not be copied |
|---|---|---|---|
| `SettingsManagerCorruptionTest.kt` | `core/src/test/…/SettingsManagerCorruptionTest.kt`, 41 tests | `988a023bd3ae781ae2166f25826a14db098a39913fd5c4c5a09f8955dc3e346c`, 789 lines | The premises are Android's. Upstream's harness fakes `Context` and `SharedPreferences` and relies on the constructor landing in its plaintext-fallback branch when `EncryptedSharedPreferences` cannot initialise; the port has no encrypted store to fall back from (PLAN.md §5a), so there is no such branch and nothing to fake at that level. Its `FakeAppContext` becomes a fake `AppEnv` and its `FakeSharedPreferences` an in-memory `SettingsStore`. **Every assertion is upstream's, unchanged** — including the payload-level assertions on the backup format, which are what pins the Android-backup interop guarantee. |
| `PersonaNameGuardTest.kt` | `core/src/test/…/PersonaNameGuardTest.kt`, 7 tests | `34fb385e04eaf9fda1177f897d8a2cda540113ba336d8e7c8e24f9218feeaf31`, 176 lines | Arrived upstream after this port's M0/M1 harvest and was missed until the instrumented triage pointed at its service-side sibling. The premises are Android's: `FakeSharedPreferences`/`FakeAppContext` and the `EncryptedSharedPreferences` fallback that the port does not have. They become `InMemoryStore` + `FakeAppEnv`, the same substitution the corruption test makes. **Every assertion is upstream's, unchanged.** |

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

## Instrumented tests — triage (2026-10-17)

All 59 files (20,889 lines) now have a verdict. The categories PLAN.md §11 names are applied per
file below; the counts are 6 to the desktop Compose runner, 43 rewritten against the port's seams
(38 tests plus 4 portable loopback fixtures), and 11 dropped with a written reason.

The moved-and-rewritten tests are **not ported yet** — this table decides their fate, it does not
land them. Each belongs with the milestone that reaches its area, and the seam-based ones become
`:core` JVM tests against the fakes that already exist (`FakeProviderServer`, `ServiceFakes`) or the
four socket fixtures this triage found portable.

### Move to the desktop Compose runner (6)

| Upstream file | What it tests | Note | Lines |
|---|---|---|---:|
| `AdaptiveThemeInstrumentedTest.kt` | Dynamic wallpaper schemes, fallback, persistence | **Ported** as `AdaptiveThemeTest` (4): static schemes, the closed dynamic gate, distinct accents, the service flow. Dynamic-wallpaper cases dropped (no desktop `WallpaperManager`) | 247 |
| `FullScreenImageViewerInstrumentedTest.kt` | Image opens full-screen; close/tap dismisses | **Ported in part** as `ChatMessageImageTapTest` (3): both image kinds are clickable nodes. The cross-root dialog half is dropped — the port's viewer is a `DialogWindow`, a separate OS window the runner cannot see into | 133 |
| `HeaderPersonaTapComposeTest.kt` | Header icon vs. name are separate tap targets | **Ported** as `HeaderPersonaTapComposeTest` (2) | 115 |
| `ModelPickerComposeTest.kt` | Picker favourites, chips, selection, loading, failure | **Ported** as `ModelPickerComposeTest` (27) | 554 |
| `ModelsScreenComposeTest.kt` | Model rows, filters, favourites, empty/error, back | **Ported** as `ModelsScreenComposeTest` (23) | 405 |
| `SettingsCategoryListComposeTest.kt` | Every category title/summary, accessible tap | **Ported** as `SettingsCategoryListComposeTest` (6) | 177 |

### Rewrite against the seams (39 tests)

| Upstream file | What it tests | Port-side target | Lines |
|---|---|---|---:|
| `AssistantReplayRoutingInstrumentedTest.kt` | Replay re-synthesises through the gateway voice | **Ported** as `AssistantReplayRoutingTest` (1): a loopback `/synthesize` proves the persona's own voice is asked and its bytes become chunk 0 | 346 |
| `AssistantServiceConcurrencyInstrumentedTest.kt` | Overlapping model fetches preserve live statuses | **Ported** as `AssistantServiceConcurrencyTest` (3): live-map read-modify-write, the Cline double-decrement, and the Main hop for health writes | 427 |
| `CloudVoiceHistoryInstrumentedTest.kt` | Cloud voice turns record the transcript and carry history to all three providers | **Ported** as `CloudVoiceHistoryTest` (3): the second turn's body carries the previous question and answer in alternating roles on OpenAI, Anthropic and Gemini | 480 |
| `ComfyImageInstrumentedTest.kt` | ComfyUI submit → poll → download, cancel, no file on failure | **Ported** as `ComfyImageTest` (24) over `ComfyUiStub`, including the character precedence and queue-first cancel cases | 817 |
| `Fix4StreamingDotsInstrumentedTest.kt` | Thinking dots hide once streamed text exists | **Ported** as `Fix4StreamingDotsTest` (2): both the mini box and text mode, with the stream held open between phases | 547 |
| `Fix6NumCtxOverflowInstrumentedTest.kt` | Over-budget requests refused before sending | **Ported** as `Fix6OverflowTest` (4): long prompt, large attachment, the cloud builder, and the under-budget control, with the token count recomputed independently | 837 |
| `GatewayCredentialResolutionInstrumentedTest.kt` | Trailing-slash/name backend URLs resolve credentials; unresolvable sends nothing | **Ported** as `GatewayCredentialResolutionTest` (4): the header proves /transcribe and /synthesize carry it; the stray gateway receives nothing and the failure is visible | 551 |
| `GatewayTestVoiceSupersedeInstrumentedTest.kt` | A superseded test-voice turn does not force IDLE or leak focus | **Ported in part** as `GatewayTestVoiceSupersedeTest` (2): the state-ownership case and its control. The focus case is dropped with its subject — §9 deletes the audio-focus stack, so there is no retained focus to leak | 499 |
| `H3StaleStreamUnwindInstrumentedTest.kt` | A stale stream unwind does not blank the newer placeholder | **Ported** as `H3StaleStreamUnwindTest` (2) over `SteppedNdjsonServer`: the stale unwind and the lone-turn control | 540 |
| `ImageGenAuthInstrumentedTest.kt` | Basic auth on generate/regenerate/test-connection | **Ported** as `ImageGenAuthTest` (8) over `ImageGenStub`; the A1111 body is asserted field-by-field (JVM `org.json` is hash-ordered) | 416 |
| `ImageGenBackendMismatchInstrumentedTest.kt` | 404/405 diagnosed as the other backend only when its probe succeeds | **Ported** as `ImageGenBackendMismatchTest` (7): both directions on Test Connection and generation, plus the 404/401/right-type negatives | 355 |
| `ImageGenSaveProbeInstrumentedTest.kt` | Save probes the backend-appropriate endpoint | **Ported** as `ImageGenSaveProbeTest` (2) on the desktop Compose runner: the real dialog is the call site under test | 210 |
| `ImageGenSettingsComposeTest.kt` | Image-gen switch/sections; state restoration | **Ported in part** as `ImageGenSettingsTest` (1): both sections' entries, their own "+" buttons, and the hint following the switch. The rotation case drops — a desktop window is not recreated, and the active choice lives on the service anyway | 167 |
| `M2HandsFreeToggleFocusInstrumentedTest.kt` | Hands-free off mid-turn does not force IDLE or leak focus | **Ported in part** as `HandsFreeToggleTest` (2): the ownership contract and the unowned-THINKING control. The focus assertions are dropped with §9's focus stack | 621 |
| `MessageStampEchoInstrumentedTest.kt` | Echoed timestamp stripped; injected exactly once next request | **Ported** as `MessageStampEchoTest` (1) over a body-capturing `SteppedNdjsonServer` | 249 |
| `MessageTimestampInstrumentedTest.kt` | Stamps in local history, not cloud; budgeted and rendered | **Ported** as `MessageTimestampServiceTest` (5): prefixes on the wire for local, verbatim for cloud, counted by the budget, and both UI stamp forms | 634 |
| `ModelCatalogCacheInstrumentedTest.kt` | Cache states, refresh, merge, stale-on-failure | **Ported** as `ModelCatalogCacheTest` (18) over `ModelCatalogStub`, including the "what no models means" distinctions | 512 |
| `ModelPickerInstrumentedTest.kt` | Favourites and selection through the service | **Ported** as `ModelPickerServiceTest` (4); the composable half is `ModelPickerComposeTest` (27) | 304 |
| `PersonaEditorCommitInstrumentedTest.kt` | Closing inside the debounce still commits a cloud identity | **Ported** as `PersonaEditorCommitTest` (1) on the desktop runner; read back from the store | 177 |
| `PersonaEditorEmptyListsInstrumentedTest.kt` | First-frame close does not rewrite identity from empty lists | **Ported** as `PersonaEditorEmptyListsTest` (1), frozen clock and all | 201 |
| `PersonaNameUniquenessInstrumentedTest.kt` | Duplicate add/rename refused; victim's history preserved | **Ported** as `PersonaNameUniquenessTest` (7), with `PersonaNameGuardTest` (7) covering the pure half | 265 |
| `PersonaSelectionInstrumentedTest.kt` | Selection follows switch/edit/rename/delete/deep link/room exit | **Ported** as `PersonaSelectionTest` (15): every path, plus the picker leaving room mode | 430 |
| `PersonaSwitchStopsPlaybackInstrumentedTest.kt` | Switch/clear stops chunked playback | **Ported** as `PersonaSwitchStopsPlaybackTest` (3) over `FakeAudioPlayback`; the republish window advances the virtual clock | 654 |
| `RoomAutoScrollInstrumentedTest.kt` | Opens at newest, follows streaming, stable identity | **Ported** as `RoomAutoScrollTest` (5); the screenshot half drops. The grow-in-place case found a port timing defect — the refine measured the pre-growth layout — fixed with a frame wait | 377 |
| `RoomEditorUiPersistenceInstrumentedTest.kt` | Room name/cap/reorder persist on close/Done/back | **Ported** as `RoomEditorUiTest` (5) on the desktop runner, read back from the store | 387 |
| `RoomPersistenceInstrumentedTest.kt` | Room edits survive a write-read cycle | **Ported** as `RoomPersistenceTest` (5) | 238 |
| `RoomRoundLoopInstrumentedTest.kt` | Order, cap, local-only autonomy, stop, supersede | **Ported** as `RoomRoundLoopTest` (8) over `RoomTurnServer`: every property above plus the editor-parser cap cases | 919 |
| `ServerIdentityInstrumentedTest.kt` | Server rename rewrites favourites/personas/cache | **Ported** as `ServerIdentityServiceTest` (10), with the pure half as the byte-identical `ServerIdentityTest` (15) | 351 |
| `SkillInjectionInstrumentedTest.kt` | Skills reach the prompt in order, do not persist, count against budget | **Ported** as `SkillInjectionTest` (7): text and voice routes, library order, the one-turn guarantee, the budget refusal, and both non-matching controls | 699 |
| `SkillUiInstrumentedTest.kt` | Toggle survives edit+rename; screenshots | **Ported** as `SkillUiTest` (1); the screenshot halves drop with the instrumentation host | 369 |
| `SpontaneousDeepLinkInstrumentedTest.kt` | Deep link selects the persona, ignores a missing one, leaves a room | **Ported** as `SpontaneousDeepLinkTest` (3) at the service layer | 178 |
| `SpontaneousEditorUiInstrumentedTest.kt` | Gate reads live lists on the first frame; switch reaches the service | **Ported** as `SpontaneousEditorUiTest` (4), frozen clock and all | 289 |
| `SpontaneousTurnInstrumentedTest.kt` | Output lands in that persona's history; silence writes nothing | **Ported** as `SpontaneousTurnServiceTest` (4) over `OllamaSuggestionStub` | 258 |
| `SpontaneousWorkerInstrumentedTest.kt` | WorkManager arming/disarming, state, date rollover | **Ported in part** as `SpontaneousDeliveryTest` (10): the run's cancel/not-due paths, delivery-state rollover, deletion/rename cleanup and import reconciliation. Unique-work replacement, chain ids and the null-input run are dropped with WorkManager (the in-process scheduler has no work names) | 601 |
| `SpontaneousWriteGuardInstrumentedTest.kt` | Cloud personas stripped; eligible local ones armed | **Ported** as `SpontaneousWriteGuardTest` (8) with `RecordingScheduler` | 311 |
| `Stage2RevealStateInstrumentedTest.kt` | Playback fraction/timestamps clear and arm per path | **Ported** as `Stage2RevealStateTest` (4): stopAudio clears, the device-voice replay arms a fresh token, System TTS clears, the gateway replay arms nothing | 282 |
| `SuggestedRepliesDirectOllamaAuthInstrumentedTest.kt` | Suggested replies reach `/api/chat` with Basic auth | **Ported** as `SuggestedRepliesDirectOllamaAuthTest` (1) with the ported `OllamaSuggestionStub` | 190 |
| `SuggestedRepliesInstrumentedTest.kt` | Default translator/image flags filter correctly | **Ported** as `SuggestedRepliesTest` (1); the unused Compose rule dropped | 31 |

### Portable fixtures (4)

| Upstream file | What it is | Port-side target | Lines |
|---|---|---|---:|
| `ComfyUiStub.kt` | Loopback ComfyUI (`system_stats`, `prompt`, `history`, `queue`, `view`) | **Ported** as a JVM fixture with explicit credentials | 310 |
| `ImageGenStub.kt` | Loopback A1111 requiring Basic auth | **Ported** as a JVM fixture with explicit credentials | 146 |
| `ModelCatalogStub.kt` | Loopback Ollama catalogue (`/api/tags`, `/api/show`, `/v1/models`) | **Ported** as a JVM fixture | 156 |
| `OllamaSuggestionStub.kt` | Loopback Ollama `/api/chat` for suggestions | **Ported** as a JVM fixture; the credentials are constructor parameters, not hardcoded | 136 |

### Drop with a written reason (11)

| Upstream file | What it tests | Reason | Lines |
|---|---|---|---:|
| `AudioFocusDelayedRetryInstrumentedTest.kt` | Delayed audio focus retried within a bound | The whole subject is Android's `AudioManager` focus request/retry, deleted by PLAN.md §9 — there is no focus stack on this platform to retry against | 345 |
| `ExampleInstrumentedTest.kt` | The instrumentation target's package name | Template test; asserts no behaviour | 23 |
| `ImageGenScreenshotsInstrumentedTest.kt` | Screenshots of image-gen surfaces | Capture only, no behavioural assertion | 177 |
| `PersonaShortcutInstrumentedTest.kt` | Launcher shortcut intent, icons, pin probe | Launcher shortcuts have no desktop counterpart; per-persona `.desktop` entries are a different surface | 136 |
| `PersonaShortcutLaunchInstrumentedTest.kt` | Launching a pinned shortcut opens that chat | Android-launcher-only | 192 |
| `PhoneCallGuardInstrumentedTest.kt` | Call-state detection and proximity barge-in | Telephony permission, call state and proximity sensor are Android-only | 479 |
| `ServiceIsolationRule.kt` | Process-global service/WorkManager/notification isolation | Android harness; the desktop suite needs its own fixture | 447 |
| `ShareTargetHarness.kt` | ACTION_SEND Activity harness | Android share-target plumbing | 391 |
| `ShareTargetImageInstrumentedTest.kt` | Shared MediaStore image becomes an attachment | `content://`/MediaStore flow has no desktop counterpart (`--send-file` is the substitute) | 229 |
| `ShareTargetInstrumentedTest.kt` | Shared text lands once in the draft | Android ACTION_SEND flow (`--send-text` is the substitute) | 73 |
| `SpontaneousDeepLinkActivityInstrumentedTest.kt` | Activity singleTop, PendingIntent extra, recreation | Activity/manifest/PendingIntent semantics; the selection half is covered service-side | 300 |

## Adapted rather than copied (one file so far)

`SharedContentTest.kt` is the first upstream JVM test to need retargeting, which is why it is not on
`TEST_SOURCES` and why this section exists. Eleven of its sixteen cases drove `parseSharedIntent` with
an `ACTION_SEND` action, a MIME type and an `EXTRA_SUBJECT` — Android's incoming-share mechanism, and
PLAN.md §7 substitutes it with `--send-text` / `--send-file`. Those eleven became CLI cases in the
port's copy: what counts as a share, what does not, and which one wins when two are given. The five
`appendSharedTextToDraft` cases came across unchanged in substance, because that function is
byte-identical ported code; the file is an adaptation, not a copy, and the ledger counts it as
neither.

## Adapted, not copied (two files)

`SharedContentTest.kt` — upstream's sixteen cases drove `parseSharedIntent` with an `ACTION_SEND` action, a MIME type
and an `EXTRA_SUBJECT`, which PLAN.md §7 substitutes with `--send-text` / `--send-file`. Eleven became CLI cases; the
five `appendSharedTextToDraft` cases came across in substance.

`SpontaneousSchedulerTest.kt` — 35 cases, 30 kept unchanged and 5 dropped, and the dropped five all assert the
*WorkManager* side of spontaneous delivery: the two-slot work-name scheme and its enumerability, the channel and
summary notification ids, `ExistingWorkPolicy`. PLAN.md §10 puts the scheduler in the core as a coroutine timer,
which needs one line of that scheme (`alternateSpontaneousSlot`, pinned by `SpontaneousTimerTest`) and has no work
names to check. What the port gained is the policy itself — windows, gap, caps, counters, the next-attempt arithmetic
and the preview's flattening — which is the decision the delivery glue hands to `evaluateSpontaneousCycle`. Upstream's
own header comment and helper (`fail(decision, expected)`) came across with it.


## M8 — new tests, none of them ported (2026-10-16)

The filesystem tools have no upstream counterpart, so nothing here is a ported or adapted test. Five files
cover the policy and the plumbing: `FsWorkspaceTest` (containment and its escape shapes),
`FsToolSettingsTest` (the settings family's three lifecycle legs), `FsToolProtocolTest` (the four provider
dialects in both directions), and `FsToolExecutorTest` (the caps, the atomic write, the framing, and what a
read may not touch).

`FsToolTurnTest` (13) is the one that runs a **turn**, and it exists because the review found three defects
that no test beside it could see. It drives both dialects end to end against `FakeProviderServer` —
loopback, `com.sun.net.httpserver`, no new dependency — so `fsToolCallsFrom` parses a body the test did not
write, the executor runs against a real temporary folder, and the next request carries the result back:
tools declared on the wire and only when a workspace exists, the call carried out and audited, the result
in the next request, no bubble for the call, THINKING held across the rounds, a superseded round not
carried out, a turn past the byte cap refused, a turn past the persona's window refused rather than sent,
and a tool turn with no prose answered with a sentence. Each was verified by restoring the defect it covers.
The fixtures beside it (`ServiceFakes.kt`, `FakeProviderServer.kt`) are shared rather than copied.

The gateway route, and the voice route with it, came the day after: `FsToolTurnTest` now also covers a persona with a
workspace being **refused in words** on the route the app cannot build a request for — text and spoken, the spoken one
before it transcribes — with the control that keeps it honest (the same persona with no workspace still answers through
the gateway), and a **spoken turn on the direct-Ollama route carrying the tools**. `FakeProviderServer` gained
`/transcribe` for the last two, which is the first end-to-end test the voice path has had.

**What still has no test, named where it lives**: the two *screens* (`FsToolScreens`, and the section in the
persona editor) need a display — M8's live run is their verification, and M7 owns the desktop UI tests that
would cover them. The editor's fs callbacks are the same case: `PersonaFsSection`'s wiring is a Compose
callback, so the policy half is tested in `FsToolSettingsTest` and the name it writes under is not.

## M7 — hardening, started (2026-10-17)

Two of the milestone's four deliverables have a test now; the other two are named here with their
blockers rather than left implied.

| Test | Tests | What it proves |
|---|---:|---|
| `ServiceSoakTest` | 1 | Repeated `start()`/`shutdown()` over one fixture leaks no service-named threads and no file descriptors. Default 25 cycles in the suite; `CELESTE_SOAK_CYCLES=50000` ran 50,000 cycles green on 2026-10-17 (40 s), which is far more start/stop cycles than the acceptance's hour would exercise at human speed. The descriptor check reads `/proc/self/fd` and skips on systems without procfs; the thread check counts only names the service owns (`celeste`, `spontaneous`, `commandrunner`, `mcp-`, `pw-`, audio helpers) and deliberately excludes the JVM-wide `Dispatchers.IO` pool, which grows to load and stays. |
| `VoiceErrorPathTest` | 1 | The gateway being down when the user speaks produces a **readable error bubble** in the transcript and leaves the application running — the failure a user meets first. The URL is a closed loopback port, so the refusal is immediate and no real service is involved. The exact sentence is deliberately not pinned beyond "it names the connection": which layer reports the refusal depends on whether the transcription call throws or returns null, and both are honest. |
| `NoMicrophoneTest` | 1 | The capture seam refusing to open: the recorder reports it **exactly once**, stops monitoring, never asks the detector about audio that does not exist, and calls neither speech callback. The second log line this test denies is the hot loop the recorder's own comment records from before the fix. The detector is a fake, which is why `SpeechDetector` was extracted — the real `VADDetector` loads ONNX in its constructor. |
| `ComfyRestartMidJobTest` | 1 | ComfyUI dies between the submit and the history poll (a real loopback server stopped mid-run): the run fails with "stopped responding" at once instead of polling a dead socket for the ten-minute budget. The four-failure streak is the loop's own contract; the refusal is a real connection where a scripted 500 would have tested a different branch. |
| `GatewaySynthesisTest` | 3 | The gateway's `/synthesize` leg over real HTTP: audio plus the `X-Word-Timestamps-B64` header on a non-JSON response; a 200 `application/json` "no voice for this language" note reported with its note, engine and detected language; and empty text sending nothing. The content-type branch is the point — a canned `GatewaySpeech` would not exercise the decision between a WAV body and a JSON decision. |

Since that table, M7 has landed the desktop Compose runner and its six ports (65 UI tests:
`ComposeUiSmokeTest`, `SettingsCategoryListComposeTest`, `HeaderPersonaTapComposeTest`,
`AdaptiveThemeTest`, `ChatMessageImageTapTest`, `ModelPickerComposeTest`,
`ModelsScreenComposeTest`), and the first of the seam rewrites from the triage table: the name
identity group (`PersonaNameGuardTest`, `PersonaNameUniquenessTest`), room persistence
(`RoomPersistenceTest`), server identity (`ServerIdentityTest` byte-identical plus
`ServerIdentityServiceTest`), the conversation-state group (`H3StaleStreamUnwindTest`,
`AssistantServiceConcurrencyTest`, `GatewayTestVoiceSupersedeTest`), the provider/credential
group (`GatewayCredentialResolutionTest` (4), `MessageStampEchoTest`, the ported
`OllamaSuggestionStub` behind `SuggestedRepliesDirectOllamaAuthTest`, and the pure
`SuggestedRepliesTest`), the room loop (`RoomRoundLoopTest`, 8), the image-gen group
(`ComfyImageTest` (24) over `ComfyUiStub`, `ImageGenAuthTest` (8) over `ImageGenStub`,
`ImageGenBackendMismatchTest` (7), `ImageGenSaveProbeTest` (2) on the runner, and the catalog
pair `ModelCatalogCacheTest` (18) over `ModelCatalogStub` and `ModelPickerServiceTest` (4)), the
persona/editor group (`PersonaEditorCommitTest`, `PersonaEditorEmptyListsTest`,
`PersonaSelectionTest` (15), `PersonaSwitchStopsPlaybackTest` (3) over `FakeAudioPlayback`, and
`HandsFreeToggleTest` (2)), the replay/reveal pair (`AssistantReplayRoutingTest`,
`Stage2RevealStateTest` (4)), the spontaneous group (`SpontaneousWriteGuardTest` (8),
`SpontaneousTurnServiceTest` (4), `SpontaneousDeepLinkTest` (3), `SpontaneousEditorUiTest` (4),
`SpontaneousDeliveryTest` (10)), the skills pair (`SkillInjectionTest` (7),
`SkillUiTest`) and the room UI pair (`RoomEditorUiTest` (5), `RoomAutoScrollTest` (5)), and the
closing five (`ImageGenSettingsTest` (1), `Fix4StreamingDotsTest` (2), `Fix6OverflowTest` (4),
`MessageTimestampServiceTest` (5), `CloudVoiceHistoryTest` (3)). Their fixtures (`SteppedNdjsonServer`, `RoomTurnServer`,
`OllamaSuggestionStub`, `ComfyUiStub`, `ImageGenStub`, `ModelCatalogStub`) are JVM loopback
stubs; no instrumented runner is involved beyond the Compose one.

**Still open in M7, with the reason:**

- **The triaged instrumented tests are done.** Every row above is **Ported**, **Ported in
  part** (with the dropped half named) or **Dropped** with a reason; no seam file remains
  unported.
- **No Secret Service**: moot rather than missing — seam 8 is not in v1 (PLAN.md §5a), so
  credentials are the 0600 settings file and there is no daemon whose absence can fail a start.
