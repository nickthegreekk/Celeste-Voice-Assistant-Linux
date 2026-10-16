package com.nikosm.voiceassistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.nio.file.Files
import java.nio.file.Path

/**
 * The audio file, ported from the bottom up.
 *
 * Upstream's `AssistantServiceAudio.kt` is 1,379 lines fusing three different things, and the
 * port's whole job on it is separating them. The ledger, because a reviewer cannot diff 1,379 lines
 * against 600:
 *
 * | Upstream mechanism | Lines | What became of it |
 * |---|---:|---|
 * | `requestAssistantFocus`, `retryDelayedAudioFocus`, `abandonAssistantFocus` | ~106 | **deleted** (§9): audio focus has no PipeWire counterpart, so the port stops its own playback and nothing else |
 * | `audioManager.mode` / `setCommunicationDevice` / `isSpeakerphoneOn` calls scattered through every teardown | ~40 | **deleted** (§9): routing is a sink choice, and it belongs to the capture and playback seams rather than to each teardown |
 * | `AcousticEchoCanceler` in the recorder | ~10 | **deleted** (§3/D8): headphones or push-to-talk, and a PipeWire echo-cancel sink if it is ever wanted — no application code |
 * | `AudioFocusRequest` field, the focus branch of the state watcher, `audioFocusRequester` test seam | ~25 | **deleted** with the focus stack |
 * | `speakTextOnDevice` | 78 | **replaced** by seam 6's `speakSystem` — same utterance-id gate, same no-play bail |
 * | `speakWithEspeak` | 150 | **replaced** by seam 6's `synthesizeLocal` + seam 2's `playPcm`; the `AudioTrack` buffer sizing and marker listener are the seam's business |
 * | `playAudioFile` | 82 | **replaced** by seam 2's `play`; `MediaPlayer`'s prepare/error/completion triple is the handle's contract now |
 * | `startChunkPlayback` | 238 | **replaced** by [TtsChunkSequencer] — extracted, because upstream's shape is untestable (see that file) |
 * | `stopAudio` | 48 | **ported** (~35): the generation bump, the state reset and the fraction clearing are policy; the engine teardown is one `stopAll()` |
 * | `stopEverything` | 50 | **ported** (~40): the Stop semantics, the Comfy cancel and the room-round flag are all policy |
 * | `playResponse` | 58 | **ported** unchanged — the voice-mode routing |
 * | `playChunkedTtsGateway` | 130 | **ported** (~60) onto the sequencer: the chunk loop, the timestamp accumulation and the chunk-file sweep stay, the playback machinery does not |
 * | `synthesizeGatewayTextForPlayback`, `replayMessageAudio`, `requestRevealRestart` | 88 | **ported** unchanged |
 * | `cleanTextForTts`, `splitIntoTtsChunks`, `wavDurationSeconds` | ~200 | **ported verbatim** — with one fix, an astral-emoji class that matches nothing on the JVM |
 *
 * The one deletion that carries no marker is the echo canceller. `DEFERRED TO M<n>` means "a later
 * milestone restores this", and nothing will restore AEC — §3/D8 decides the desktop does not have
 * it, and a marker would promise a restore that is never coming. A decision and a deferral are
 * different things and this is the first deletion of the former kind.
 */

/**
 * Strips markdown formatting from text before on-device TTS synthesis.
 * Mirrors the server-side clean_text_for_tts() in tts_server.py so that the
 * eSpeak and System TTS engines don't read markup symbols aloud (**bold**,
 * *italic*, # headers, `backticks`). The Gateway/Kokoro path already gets this
 * treatment server-side, but on-device engines never did — until now.
 *
 * - **bold** / *italic* → keep enclosed text, strip the asterisks
 * - # / ## / etc. heading markers → removed
 * - ` / `` code markers → removed
 * - Excessive blank lines (3+) → collapsed to a single blank line
 */
fun cleanTextForTts(text: String): String {
    var cleaned = text
    // **bold** markers — keep the enclosed text, strip the asterisks
    cleaned = cleaned.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
    // *italic* markers — keep the enclosed text, strip the asterisks
    cleaned = cleaned.replace(Regex("\\*(.+?)\\*"), "$1")
    // # / ## / ### heading markers (line-start, 1–6 hashes followed by whitespace)
    cleaned = cleaned.replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "")
    // Backtick code markers (single and triple)
    cleaned = cleaned.replace(Regex("`+"), "")
    // ASCII arrows — eSpeak/System TTS read "->" literally ("hyphen greater
    // than"). Space replacement so "A -> B" becomes "A B". Mirrors the
    // server-side clean_text_for_tts() arrow strip.
    cleaned = cleaned.replace(Regex("-->|->|=>|<-"), " ")
    // Unicode arrows (→ ← ↔ ⟶ etc., U+2190–21FF)
    cleaned = cleaned.replace(Regex("[\\u2190-\\u21FF]"), " ")
    // Emoji + misc symbols/dingbats + variation selectors + ZWJ + keycap +
    // invisible formatting (U+200B–200F). Kokoro and the on-device engines read
    // these as garbage or skip them awkwardly. Mirrors the server-side
    // clean_text_for_tts() emoji strip.
    //
    // NOT US: upstream writes the astral half as the surrogate-pair class
    // `[\uD83C-\uD83E][\uDC00-\uDFFF]`. That works on Android, whose regex engine is ICU-backed,
    // and matches NOTHING on the JVM: OpenJDK pairs a well-formed surrogate pair into a single
    // code point before the class is applied, so neither half of the class ever sees a value it
    // contains. The failure is silent — the strip simply does nothing and emoji reach eSpeak and
    // speech-dispatcher, which is the exact symptom this line exists to prevent.
    //
    // The replacement is the same set, written as the code points the surrogate class denotes:
    // high D83C–D83E over low DC00–DFFF is U+1F000–U+1FBFF, which is the emoji, symbol, transport
    // and enclosed-alphanumeric blocks including the regional indicators that make flags. The BMP
    // half needs no change — those are single code units and match identically on both engines.
    cleaned = cleaned.replace(
        Regex(
            "[\\x{1F000}-\\x{1FBFF}]" +
                "|[\\u2600-\\u27BF\\u2B00-\\u2BFF\\uFE0E\\uFE0F\\u200B-\\u200F\\u20E3]"
        ),
        ""
    )
    // Collapse leftover multi-spaces from the removals above
    cleaned = cleaned.replace(Regex(" {2,}"), " ")
    // Collapse 3+ consecutive newlines to a single blank line
    cleaned = cleaned.replace(Regex("\\n{3,}"), "\n\n")
    return cleaned
}

/**
 * Parses the duration (seconds) of a WAV file from its raw bytes by reading
 * the canonical 44-byte RIFF header: sample rate at offset 24, data-subchunk
 * size at offset 40, channels at offset 22, bits-per-sample at offset 34.
 * Duration = dataSize / (sampleRate * bytesPerSample). Falls back to the
 * header's byteRate (offset 28) when present. Returns 0.0 for anything that
 * isn't a standard WAV.
 *
 * Used to keep the accumulated word-timestamp offset on the same clock as
 * the playback durations that drive ttsPlaybackFraction. Upstream compares
 * against `MediaPlayer.duration` directly; here the same number arrives as a
 * playback handle's duration, and the reason the two must share a clock is
 * unchanged — the word positions and the progress fraction are both plotted
 * against it.
 *
 * Ported unchanged: it reads bytes and does arithmetic, and it has no platform
 * dependency to substitute.
 */
fun wavDurationSeconds(wavBytes: ByteArray): Double {
    if (wavBytes.size < 44) return 0.0
    // "RIFF" magic
    if (wavBytes[0] != 0x52.toByte() || wavBytes[1] != 0x49.toByte()) return 0.0
    if (wavBytes[2] != 0x46.toByte() || wavBytes[3] != 0x46.toByte()) return 0.0
    // "WAVE" magic
    if (wavBytes[8] != 0x57.toByte() || wavBytes[9] != 0x41.toByte()) return 0.0
    if (wavBytes[10] != 0x56.toByte() || wavBytes[11] != 0x45.toByte()) return 0.0
    var sampleRate = 0
    for (i in 24 until 28) {
        sampleRate = sampleRate or ((wavBytes[i].toInt() and 0xFF) shl (8 * (i - 24)))
    }
    if (sampleRate <= 0) return 0.0
    var byteRate = 0L
    for (i in 28 until 32) {
        byteRate = byteRate or ((wavBytes[i].toInt() and 0xFF).toLong() shl (8 * (i - 28)))
    }
    var dataSizeLong = 0L
    for (i in 40 until 44) {
        dataSizeLong = dataSizeLong or (((wavBytes[i].toInt() and 0xFF).toLong()) shl (8 * (i - 40)))
    }
    if (dataSizeLong <= 0) return 0.0
    // dataSize is in BYTES. Prefer the header's byteRate (bytes/sec); fall back
    // to channels*bitsPerSample/8 when byteRate is absent.
    val bytesPerSec = if (byteRate > 0) byteRate.toDouble()
        else 1.0 * (wavBytes[22].toInt() and 0xFF) * ((wavBytes[34].toInt() and 0xFF) or ((wavBytes[35].toInt() and 0xFF) shl 8)) / 8.0
    if (bytesPerSec <= 0) return 0.0
    return dataSizeLong.toDouble() / bytesPerSec
}

// ─── Stage-2 streaming TTS: sentence-boundary splitter ─────────────────────
// Splits accumulated LLM output into TTS-sized chunks at natural sentence
// boundaries. Runs BEFORE cleanTextForTts() per chunk (cleaning collapses
// newlines and can join sentences; splitting first preserves boundaries).
//
// Guards:
//   - Decimal points: "3.14" — period followed by a digit, not a sentence end
//   - Abbreviations: Dr., Mr., Mrs., vs., e.g., i.e., etc., St., Inc., U.S.
//     (checked against a small blocklist before splitting at a period)
//   - Double newlines (paragraph breaks) are also valid split points
//
// Chunks shorter than minChunkChars are accumulated until they exceed it —
// avoids the HTTP round-trip overhead of 5-word chunks. The last chunk is
// always emitted even if short (it's the tail of the response).
internal fun splitIntoTtsChunks(text: String, minChunkChars: Int = 80): List<String> {
    if (text.isBlank()) return emptyList()

    // Abbreviations that end in a period but are NOT sentence boundaries.
    val abbreviations = setOf(
        "dr", "mr", "mrs", "ms", "vs", "e.g", "i.e", "etc", "st", "inc",
        "u.s", "no", "vol", "approx", "dept", "est", "fig", "gen", "gov",
        "sen", "rep", "prof", "rev", "hon", "lt", "col", "sgt", "capt"
    )

    // Sentence-ending punctuation followed by whitespace and a capital letter
    // or digit (heuristic for "actually the next sentence"). The lookbehind
    // allows . ! ? … and the lookahead checks it's a real boundary.
    val sentenceEnd = Regex(
        """(?<=[.!?…])\s+(?=[A-Z0-9"'(])""",
        setOf(RegexOption.MULTILINE)
    )

    // First: split into candidate sentences (naive split on sentence-end regex)
    val rawSentences = text.split(sentenceEnd).map { it.trim() }.filter { it.isNotBlank() }
    if (rawSentences.isEmpty()) return listOf(text.trim())

    // Second: merge adjacent sentences that were falsely split (abbreviation
    // or decimal guard — if the fragment before the split ends with a known
    // abbreviation pattern or a digit, it was NOT a sentence boundary)
    val sentences = mutableListOf<String>()
    var pending = rawSentences[0]
    for (i in 1 until rawSentences.size) {
        val lastWord = pending.substringAfterLast(" ").substringBeforeLast(".")
        val isAbbrev = lastWord.lowercase() in abbreviations ||
            pending.endsWith(".") && pending.lastOrNull()?.isDigit() == true
        if (isAbbrev) {
            pending = pending + " " + rawSentences[i]
        } else {
            sentences.add(pending)
            pending = rawSentences[i]
        }
    }
    sentences.add(pending)

    // Third: accumulate into chunks of at least minChunkChars
    val chunks = mutableListOf<String>()
    var chunk = ""
    for (sentence in sentences) {
        val candidate = if (chunk.isEmpty()) sentence else "$chunk $sentence"
        if (candidate.length >= minChunkChars) {
            chunks.add(candidate)
            chunk = ""
        } else {
            chunk = candidate
        }
    }
    // Emit the tail chunk even if below minimum (it's the end of the response)
    if (chunk.isNotBlank()) chunks.add(chunk)

    return chunks.ifEmpty { listOf(text.trim()) }
}

// ─── Playback ──────────────────────────────────────────────────────────────

/**
 * A3: monotonically increasing utterance counter — two calls can never share an ID
 * (a time-based ID could collide for two calls in the same millisecond).
 */
private var ttsUtteranceCounter: Long = 0

/**
 * The output a playback should target right now: the persona's chosen sink, else the earpiece when
 * the user has asked for it, else the default.
 *
 * Upstream recomputed Android's routing on every playback entry point — `audioManager.mode`,
 * `setCommunicationDevice`, `isSpeakerphoneOn` — which is where the ~40 deleted lines went. On
 * this platform the whole decision is one lookup that may legitimately answer null, and null
 * means the sound server's default, which is the right answer far more often here.
 *
 * The chosen sink is read from [currentPersona] rather than threaded in: every playback entry
 * point already speaks for the current persona, and the chunked path resolves its persona at
 * speech time, so a parameter would only offer a second way to disagree with the transcript.
 */
internal fun AssistantService.playbackTarget(): AudioDevice? =
    sinkForPersona(currentPersona) ?: if (earpieceMode.value) audioDevices.earpiece() else null

/**
 * The output [persona] asked for, or null for the default — PLAN.md §9's sink selection, and the
 * replacement for what the phone's earpiece toggle meant.
 *
 * A stored id whose device is no longer there logs once and falls back; see [pickDevice] for why
 * that is the right answer for a preference (a per-turn choice is different, and
 * `AudioCapture.open` still refuses to substitute one).
 */
internal fun AssistantService.sinkForPersona(persona: Persona?): AudioDevice? {
    val wanted = persona?.let { settingsManager.getPersonaOutputDevice(it.name) } ?: return null
    val device = pickDevice(audioDevices.outputs(), wanted)
    if (device == null) {
        logger.w(
            "AssistantService",
            "'${persona.name}' is set to play to an output that is gone ($wanted) — using the default. " +
                "Re-pick it in the persona's settings.",
        )
    }
    return device
}

/**
 * The microphone to capture from: the one the user selected in Settings, or null for the default.
 *
 * Used by **both** capture paths. Push-to-talk used to open with `playbackTarget()`, which is a
 * port artefact rather than upstream's behaviour — upstream's `MediaRecorder` named no device at
 * all (`AudioSource.MIC`) and relied on Android's process-wide route. That was harmless while
 * `earpiece()` answered null on every desktop, and would have started handing `pw-record` a *sink*
 * name the moment a persona could choose one.
 */
internal fun AssistantService.inputDeviceForCapture(): AudioDevice? {
    val wanted = settingsManager.getAudioInputDevice() ?: return null
    val device = pickDevice(audioDevices.inputs(), wanted)
    if (device == null) {
        logger.w(
            "AssistantService",
            "the selected microphone is gone ($wanted) — using the default input. Re-pick it in Settings.",
        )
    }
    return device
}

fun AssistantService.toggleEarpieceMode() {
    _earpieceMode.value = !earpieceMode.value
}

/**
 * The one landing every no-play path takes: release the turn, return to IDLE, restore the status
 * line.
 *
 * Upstream writes the same three lines at about ten bail-outs, each with its own comment
 * explaining *why* it bailed. The comments stay at the call sites; only the body is here, because
 * with the audio-focus release deleted (see the ledger) the three lines became identical and ten
 * copies of an identical sequence is how a fourth line gets added to nine of them. Two of the
 * deleted calls were the focus abandonment, which is why this is now purely state.
 */
internal fun AssistantService.landInIdle() {
    _state.value = AssistantState.IDLE
    updateNotification("Ready to help")
}

fun AssistantService.speakTextOnDevice(text: String) {
    // Fix #1b: System TTS is the ONE playback entry point that never routes through
    // stopAudio() — no live flow calls a teardown beforehand. The per-sequence Stage-2 state
    // therefore has to be cleared here, or an unrelated earlier turn's values satisfy the UI's
    // karaoke gate during THIS playback (`SPEAKING && ttsPlaybackFraction != null &&
    // voiceDuration > 0`): the chunked pipeline deliberately leaves `_ttsPlaybackFraction` at 1f
    // when a Gateway turn ends, and `_voiceDuration` keeps an earlier eSpeak turn's length, so the
    // new response would be pinned to full text (reveal suppressed) with a nonsense highlight
    // painted from the old fraction. Cleared before the no-play bail: a muted/unready engine must
    // land clean too.
    _ttsPlaybackFraction.value = null
    _ttsWordTimestamps.value = null
    _voiceDuration.value = 0
    if (!tts.systemReady || silenced.value) {
        // No-play bail: upstream also released the focus it had requested here.
        landInIdle()
        return
    }

    _state.value = AssistantState.SPEAKING
    updateNotification("Speaking (Device)...")

    // A3: unique ID per call (an incrementing counter can never collide, unlike a
    // time-based ID within the same millisecond).
    val utteranceId = "assistant_msg_${++ttsUtteranceCounter}"
    currentUtteranceId = utteranceId

    // A3: only the most recently issued utterance may clean up. The engine reports through the
    // same id it was given, and a stale callback from an older utterance — which a stop or a new
    // utterance can produce — must not tear down the newer one. The re-check hops to the state's
    // own dispatcher before touching anything, because the callback arrives on whatever thread
    // the implementation uses.
    val queued = tts.speakSystem(text, utteranceId) { end ->
        serviceScope.launch(Dispatchers.Main) {
            if (utteranceId != currentUtteranceId) return@launch
            currentUtteranceId = null
            if (end !is PlaybackEnd.Finished) {
                logger.w("AssistantService", "Device speech ended without finishing: $end")
            }
            landInIdle()
        }
    }

    // A4: if the engine refuses the call, no utterance is queued and no callback will ever fire —
    // clean up immediately instead of staying stuck in SPEAKING with nothing to clear it.
    if (!queued) {
        currentUtteranceId = null
        landInIdle()
    }
}

internal fun AssistantService.speakWithEspeak(text: String, persona: Persona) {
    // Upstream called stopAudio(abandonFocus = false) here: a new playback starts right after the
    // teardown, so the audio focus it had retained was deliberately kept. There is no focus to
    // keep, so this is an ordinary stop.
    stopAudio()
    // C4: check silence BEFORE any synthesis work — when silent mode is on, skip the
    // entire pipeline (engine initialization, voice selection, setVoice, synthesize)
    // since the result would be discarded anyway.
    if (silenced.value) {
        landInIdle()
        return
    }

    serviceScope.launch(Dispatchers.IO) {
        val voiceName = when (persona.targetLanguage.lowercase()) {
            "greek" -> "el"
            "french" -> "fr"
            "german" -> "de"
            "spanish" -> "es"
            "italian" -> "it"
            "japanese" -> "ja"
            "korean" -> "ko"
            "chinese" -> "cmn"
            "russian" -> "ru"
            "hebrew" -> "he"
            "dutch" -> "nl"
            "turkish" -> "tr"
            "arabic" -> "ar"
            "hindi" -> "hi"
            "portuguese" -> "pt"
            else -> "en"
        }
        // Upstream logged a failed `setVoice` and synthesised anyway, because eSpeak keeps
        // whatever voice was previously active and there is no user-facing surface for it. That
        // log now lives in the seam's implementation, which is where the voice is actually set;
        // a null result below means no audio at all, which is a different thing.
        val speech = tts.synthesizeLocal(text, voiceName)

        withContext(Dispatchers.Main) {
            if (speech == null || speech.pcm.isEmpty() || speech.sampleRate <= 0) {
                landInIdle()
                return@withContext
            }
            _state.value = AssistantState.SPEAKING
            updateNotification("Speaking (eSpeak)...")

            // Set the voice duration UP FRONT (before playback) so the UI reveal tickers have a
            // target to animate against from the first frame. Upstream had the same rule and for
            // the same reason: setting it only after the blocking write made the classic box dump
            // the full text at once, because the fallback ticker saw durationMs <= 0 and pinned
            // the reveal to 1f. `SpeechSamples` computes it from the sample count and rate, which
            // is the arithmetic upstream did inline.
            _voiceDuration.value = speech.durationMs

            playSynthesisedSpeech(speech)
        }
    }
}

/**
 * Plays raw PCM through seam 2 and owns the completion.
 *
 * Upstream built an `AudioTrack` here, sized it from `getMinBufferSize`, set a
 * `setNotificationMarkerPosition` so completion came from real playback position rather than a
 * sleep-based estimate, and released the track from the marker callback. All of that is seam 2's
 * contract now — "finished" is the process exiting — so what is left is the state work that has to
 * happen on the service's side of the boundary.
 */
private fun AssistantService.playSynthesisedSpeech(speech: SpeechSamples) {
    audioPlayback.playPcm(speech.pcm, speech.sampleRate, playbackTarget()) { end ->
        serviceScope.launch(Dispatchers.Main) {
            if (end !is PlaybackEnd.Finished) {
                logger.d("AssistantService", "Speak playback ended: $end")
            }
            if (assistantState.value == AssistantState.SPEAKING) {
                landInIdle()
            }
        }
    }
}

internal fun AssistantService.playAudioFile(file: Path) {
    // Upstream called stopAudio(abandonFocus = false) — see speakWithEspeak.
    stopAudio()
    if (silenced.value) {
        landInIdle()
        return
    }
    _state.value = AssistantState.SPEAKING
    updateNotification("Speaking...")

    audioPlayback.play(file, playbackTarget()) { end ->
        serviceScope.launch(Dispatchers.Main) {
            when (end) {
                is PlaybackEnd.Failed -> logger.e("AssistantService", "playAudioFile: playback failed — ${end.reason}")
                is PlaybackEnd.Stopped -> logger.d("AssistantService", "playAudioFile: stopped before the end")
                is PlaybackEnd.Finished -> Unit
            }
            if (assistantState.value == AssistantState.SPEAKING) {
                _voiceDuration.value = 0
                landInIdle()
            }
        }
    }
}

/**
 * Stops playback and returns the state to IDLE.
 *
 * Upstream took an `abandonFocus` parameter, because its play-entry callers tore down the previous
 * playback and then immediately re-requested focus — abandoning first would bounce the focus the
 * recorder had retained through THINKING and make other applications' audio flicker. There is no
 * focus stack on this platform (§9), so the parameter, the branch and the reason are all gone
 * together; that is roughly a third of what this function was.
 */
fun AssistantService.stopAudio() {
    // Stage-2: bump the TTS generation so all pending chunked-playback
    // onCompletion callbacks see a stale generation and halt atomically.
    ttsGeneration++
    // One call, and the seam's own guard does the rest: a playback that has already ended is not
    // stopped twice, and every stopped playback reports `Stopped` to its caller rather than
    // falling silent — which is what lets a caller tell "a newer turn took over" from "it failed".
    audioPlayback.stopAll()
    if (assistantState.value == AssistantState.SPEAKING) {
        landInIdle()
    }
    _voiceDuration.value = 0
    // Stage-2 fraction/timestamps are PER-SEQUENCE state, not per-session state, so
    // they belong in this teardown next to _voiceDuration. Without this, a value left
    // over from an earlier Gateway (chunked) turn survived into an unrelated later
    // playback in the same session: the UI's karaoke gate is
    // `SPEAKING && ttsPlaybackFraction != null && voiceDuration > 0`, so a stale
    // fraction could (a) wrongly suppress the typewriter reveal of a device-voice
    // (eSpeak/System TTS) response and (b) paint a nonsense estimated word highlight
    // from a fraction that belongs to a response that finished playing long ago.
    // Redundant-but-harmless for the chunked path: playChunkedTtsGateway() nulls both
    // at sequence start anyway, and this teardown has just bumped ttsGeneration, so
    // any sequence that was mid-flight is dead and cannot republish.
    _ttsPlaybackFraction.value = null
    _ttsWordTimestamps.value = null
}

fun AssistantService.stopEverything() {
    currentCall?.cancel()
    currentCall = null
    _micAmplitude.value = 0f
    // Stop-semantics fix: invalidate any in-flight turn through the same generation
    // mechanism used for superseded requests. currentCall only tracks the main chat
    // request — standalone transcription/synthesis/RAG calls either don't set it or
    // run in phases where it is null. Bumping the sequence makes every existing
    // isChatRequestCurrent/isChatContextCurrent apply-gate (sendAudioToServer,
    // sendTextToMessageToServer, performCloudChat) discard whatever the in-flight turn
    // eventually produces — no history append and, critically, no playResponse —
    // even when the underlying network work completes after Stop was pressed.
    nextChatRequestSeq()
    // ComfyUI is submit-then-poll, so a Stop must also cancel the poll (no file is written once the
    // coroutine dies) and, best-effort, tell the server to drop that one job. Deliberately after the
    // sequence bump so the turn is already invalidated, and a no-op for A1111 and an idle app.
    stopComfyGeneration()
    // Fix: stop an active manual recording — the red STOP button is reachable during
    // LISTENING, and this used to leave the MediaRecorder running: the mic stayed hot
    // after an explicit Stop, the partial file grew unbounded, and the next
    // startRecording() replaced the recorder reference without releasing the
    // orphaned one. The partial file is DELETED here (an explicit user-initiated
    // stop is not a completed turn — it must never be transcribed or sent).
    stopActiveRecording()
    outputFile?.let { partial -> runCatching { Files.deleteIfExists(partial) } }
    outputFile = null
    stopAudio()
    // Fix: SPEAKING belongs in the reset set — Stop from an active playback must
    // return to IDLE. stopAudio() above already resets SPEAKING when it observes
    // it, but the eSpeak path flips _state to SPEAKING from a background synthesis
    // coroutine whose only exit is the playback callback — a callback that never
    // fires once the playback has been stopped. A state flip landing after
    // stopAudio()'s internal check used to strand the UI on SPEAKING, where the mic
    // tap is a dead no-op (it only acts on IDLE/LISTENING). Re-checking here, after
    // teardown, closes that window: Stop from ANY active state deterministically
    // lands on IDLE.
    if (assistantState.value == AssistantState.THINKING ||
        assistantState.value == AssistantState.LISTENING ||
        assistantState.value == AssistantState.SPEAKING
    ) {
        landInIdle()
    }
    // Phase 2: Stop also ends any room round — the round's loop checks this flag before
    // starting each participant turn, and the generation bump above already invalidates the
    // turn that is in flight. Without this, the global Stop button (the mic's STOP, which is
    // reachable in room mode too) would leave the room's loop running to its cap.
    markRoomRoundStopped()
    _voiceDuration.value = 0
}

fun AssistantService.playResponse(persona: Persona, file: Path? = null, deviceText: String? = null) {
    // deviceText arrives PRE-CLEANED: cleanTextForTts() runs in the background parse blocks
    // (Dispatchers.IO) right after the LLM response is received, so the Main thread never runs the
    // regex passes (they used to jank on long responses). The Gateway/Kokoro path is cleaned
    // server-side and passes through here with file != null, so it's unaffected.
    when (persona.voiceMode) {
        VoiceMode.NONE -> {
            // No-play bail: stopRecording() never retains anything for a NONE persona, so this is
            // normally a no-op landing kept for uniformity of the no-play contract.
            landInIdle()
        }
        VoiceMode.SYSTEM_TTS -> {
            if (deviceText != null) speakTextOnDevice(deviceText)
            else if (file != null) playAudioFile(file)
            else landInIdle()
        }
        VoiceMode.BUNDLED_ESPEAK -> {
            if (deviceText != null) speakWithEspeak(deviceText, persona)
            else if (file != null) playAudioFile(file)
            else landInIdle()
        }
        VoiceMode.GATEWAY -> {
            if (file != null) playAudioFile(file)
            else if (deviceText != null) {
                // No persisted audio for this text (streaming/chunked Gateway turns synthesize
                // per chunk and never write a .wav). Re-synthesize through the Gateway instead of
                // silently substituting System TTS, which would misrepresent the persona's
                // configured voice. deviceText arrives pre-cleaned; the chunked pipeline cleans
                // per chunk anyway.
                synthesizeGatewayTextForPlayback(deviceText, persona) {
                    updateNotification("Voice unavailable - check TTS server")
                    landInIdle()
                }
            } else landInIdle()
        }
    }
}

// ─── Stage-2 streaming TTS: chunked sequential synthesis + playback ────────
//
// Sequential pipeline (confirmed decision): synthesize chunk 0, play it while
// synthesizing chunk 1, play chunk 1 while synthesizing chunk 2, etc.
// Ordering is inherently guaranteed - each chunk is played immediately after
// synthesis, before the next one starts.
//
// SPEAKING lifetime: entered ONCE before chunk 0 starts, exited ONCE after
// the last chunk finishes. No SPEAKING→IDLE→SPEAKING flickering between
// chunks - the VAD collector sees a single continuous window. That is
// [TtsChunkSequencer]'s `onStarted` contract, and the reason it fires once.
//
// The playback machinery itself - swapping the data source on the same player,
// the position polling, the generation checks in every callback - is the
// sequencer's now. This function keeps what is the service's: which chunks, what
// they sound like, and what the state machine does around them.

/**
 * Transient chunk files (`tts_chunk_<generation>_<chunkIdx>.wav`) are playback data sources for
 * ONE sequence: each sequence writes its own set, nothing deleted them, and over a long session
 * they silently accumulated in the cache directory (a ~12s 22kHz mono WAV is ~500KB, so a chatty
 * day of chunked replies is real space). The sweep deletes files of strictly OLDER generations
 * only: a live player always belongs to the current generation, and every generation-mismatch path
 * releases and gives up, so anything older is provably unread. After every completed sequence, at
 * most one generation's files exist.
 */
private fun AssistantService.sweepCompletedTtsChunkFiles(beforeGeneration: Long) {
    val files = runCatching { Files.list(cacheDir.toPath()).use { it.toList() } }.getOrNull() ?: return
    for (file in files) {
        val name = file.fileName?.toString() ?: continue
        // tts_chunk_<generation>_<chunkIdx>.wav
        if (!name.startsWith("tts_chunk_") || !name.endsWith(".wav")) continue
        val generation = name.removePrefix("tts_chunk_").substringBefore('_').toLongOrNull() ?: continue
        if (generation < beforeGeneration) runCatching { Files.deleteIfExists(file) }
    }
}

/**
 * Synthesises and plays [fullText] one chunk at a time, in order.
 *
 * Returns the chunk files written, for the same reason upstream returned them: they are the only
 * record of what was actually spoken after a partial failure, and none of them is persisted as a
 * message.
 */
internal suspend fun AssistantService.playChunkedTtsGateway(
    fullText: String,
    persona: Persona,
    myTtsGeneration: Long
): List<Path> {
    val chunks = splitIntoTtsChunks(fullText)
    if (chunks.isEmpty()) {
        // No-play bail: the sequence cannot start.
        landInIdle()
        return emptyList()
    }

    // Fresh-sequence state reset: the previous response's stale fraction (1f)
    // and timestamps must not leak into this turn's UI window before the first
    // chunk republishes them — a stale 1f fraction would misplace the karaoke
    // highlight (or pin it pre-speech), and stale timestamps could mis-align
    // against the new text.
    _ttsPlaybackFraction.value = null
    _ttsWordTimestamps.value = null

    val accumulatedTimestamps = mutableListOf<Map<String, Any>>()  // [{word, start, end}] absolute
    var cumulativeAudioS = 0.0  // full AUDIO duration of prior chunks

    val sequence = chunkSequencer.start(
        chunks = chunks,
        scope = serviceScope,
        // The label is explicit: `return@start` would be ambiguous, and every one of these early
        // returns means the same thing to the sequencer — this chunk could not be produced. What
        // it no longer means is *nothing*: each one carries the reason into `PlaybackEnd.Failed`,
        // which is the line the user and the log actually see. "chunk 0 could not be synthesised"
        // on its own was the 2026-10-16 report, three times, and it could not be acted on.
        synthesize = synth@{ index, chunkText ->
            // Generation check BEFORE each synthesis: if stopAudio() bumped the
            // counter, the entire sequence is stale - produce nothing further.
            if (ttsGeneration != myTtsGeneration) {
                return@synth Synthesis.Unavailable("chunk $index was never asked for — the turn was stopped first")
            }

            // Clean the chunk text (strip markdown that Kokoro reads literally).
            val cleaned = cleanTextForTts(chunkText)
            if (cleaned.isBlank()) {
                return@synth Synthesis.Unavailable("chunk $index is empty once markdown is stripped")
            }

            // Sequential synthesis: one chunk at a time, via the existing
            // single-text synthesizeWithGateway (tracks currentCall for Stop).
            val speech = synthesizeWithGateway(cleaned, persona)
            val audioBytes = speech.audio
            val tsJson = speech.timestamps
            if (audioBytes == null || audioBytes.isEmpty()) {
                // The gateway's own reason, already framed with the route, engine, voice and
                // language where it refused (synthesizeWithGateway). The fallback covers a
                // 200 with an empty body, which no gateway in this project has produced.
                return@synth Synthesis.Unavailable(
                    speech.noAudioReason ?: "gateway TTS returned no audio for chunk $index"
                )
            }

            // Parse and adjust word timestamps: Kokoro returns them relative to
            // the chunk start; add the cumulative AUDIO duration offset for
            // absolute position. The offset is the full WAV duration of prior
            // chunks (parsed from the RIFF header) — NOT the prior chunk's last
            // word .end, which excludes Kokoro's trailing pad (~0.1s per chunk)
            // and would drift the reveal progressively early across chunks.
            // Using full audio durations keeps the timestamp timeline identical
            // to the fraction timeline, so word positions and playback position
            // stay on the same clock.
            if (tsJson != null) {
                try {
                    val chunkTimestamps = JSONArray(tsJson)
                    for (i in 0 until chunkTimestamps.length()) {
                        val t = chunkTimestamps.getJSONObject(i)
                        accumulatedTimestamps.add(
                            mapOf(
                                "word" to t.optString("word", ""),
                                "start" to (t.optDouble("start", 0.0) + cumulativeAudioS),
                                "end" to (t.optDouble("end", 0.0) + cumulativeAudioS)
                            )
                        )
                    }
                    // Publish the accumulated timestamps to the service StateFlow
                    _ttsWordTimestamps.value = accumulatedTimestamps.joinToString(",", "[", "]") { m ->
                        """{"word":"${m["word"]}","start":${m["start"]},"end":${m["end"]}}"""
                    }
                } catch (e: Exception) {
                    logger.w("AssistantService", "Failed to parse word timestamps: ${e.message}")
                }
            }

            // Advance the audio clock by THIS chunk's full WAV duration so the
            // next chunk's words land at the correct absolute position.
            cumulativeAudioS += wavDurationSeconds(audioBytes)

            // Generation check AFTER synthesis (it may have taken seconds -
            // Stop could have fired while we were waiting for Kokoro).
            if (ttsGeneration != myTtsGeneration) {
                return@synth Synthesis.Unavailable("chunk $index was discarded — the turn was stopped mid-synthesis")
            }

            val chunkFile = cacheDir.toPath().resolve("tts_chunk_${myTtsGeneration}_${index}.wav")
            runCatching {
                Files.createDirectories(cacheDir.toPath())
                Files.write(chunkFile, audioBytes)
            }.getOrElse { e ->
                // No log line here any more: the reason now travels to the one place that reports
                // this sequence's failure, and two lines saying the same thing is how a log gets
                // skimmed rather than read.
                return@synth Synthesis.Unavailable("chunk $index could not be written to $chunkFile: ${e.message}")
            }
            Synthesis.Ready(chunkFile)
        },
        onStarted = {
            // Once, before chunk 0 plays — one SPEAKING window for the whole sequence.
            _state.value = AssistantState.SPEAKING
            updateNotification("Speaking (streaming TTS)...")
        },
        onProgress = { fraction, estimatedTotalMs ->
            _ttsPlaybackFraction.value = fraction
            // The refined estimate is published as the voice duration on every chunk, which is
            // what makes the reveal cover the full response rather than chunk 0's few seconds.
            // A zero means "not measured yet" and must not overwrite a real duration with it.
            if (estimatedTotalMs > 0) _voiceDuration.value = estimatedTotalMs
        },
        onEnd = { end ->
            // Only this generation may touch the state machine: a superseded sequence unwinding
            // late must not blank a newer turn's SPEAKING.
            if (ttsGeneration == myTtsGeneration) {
                // Upstream leaves the fraction at 1f on a natural end — the UI reads it as "fully
                // revealed" — and clears it when the sequence was stopped, where a full reveal
                // would be a lie about audio that never finished.
                _ttsPlaybackFraction.value = if (end is PlaybackEnd.Finished) 1f else null
                _ttsWordTimestamps.value = null
                when (end) {
                    is PlaybackEnd.Failed -> logger.e("AssistantService", "Chunked playback failed — ${end.reason}")
                    is PlaybackEnd.Stopped -> logger.d("AssistantService", "Chunked playback stopped")
                    is PlaybackEnd.Finished -> Unit
                }
                landInIdle()
            }
            // Housekeeping: this sequence completed, so its own player is done — every generation
            // older than this one is unreferenced now.
            sweepCompletedTtsChunkFiles(myTtsGeneration)
        },
        // Upstream re-set `AudioAttributes` from `earpieceMode.value` on every chunk it swapped
        // into the recycled player. Read once here instead: the setting cannot change the sink
        // mid-reply in a way that would matter, and reading it per chunk is how the two halves of
        // one utterance end up on different devices.
        target = playbackTarget(),
    )

    // The sequence was *started*, not run: start() hands it to serviceScope and returns, so
    // `played` read here would always be empty and the no-play report below would fire on every
    // replay. Awaiting is what upstream's shape gave for free — its version ran the loop inline and
    // only returned to the turn once it was done with the reply — and it is why this function is
    // `suspend` at all. Note that it is *playback* that is awaited, not just synthesis: upstream's
    // loop ended once every chunk was written and then handed the tail of the utterance to the
    // player's callbacks, but the answer this function's callers need ("did the reply reach the
    // speakers?") is only settled when the audio is.
    sequence.awaitEnd()

    // No-play guard: the sequence may have ended without ever starting a chunk (chunk 0's synthesis
    // failed, or every chunk cleaned to blank) — playback will never start, so land the turn rather
    // than leaving it in THINKING with nothing coming. The sequencer's `onEnd` above reports the
    // failure and lands it already; this covers the remaining path, where the sequence was
    // cancelled before it began and the generation guard in `onEnd` suppressed it.
    if (sequence.played.isEmpty() && ttsGeneration == myTtsGeneration &&
        assistantState.value != AssistantState.IDLE
    ) {
        landInIdle()
    }

    return sequence.played
}

// Replay / deferred Gateway playback entry point: re-synthesizes the full text
// through the persona's configured Gateway voice, on demand.
//
// Background: the Stage-2 streaming pipeline (playChunkedTtsGateway) synthesizes
// one chunk at a time and NEVER persists a .wav to disk, so a streamed turn's
// ChatMessage.audioFilePath is null by design and there is no file to replay.
// Re-synthesizing on demand is exactly what was specified when the chunked
// pipeline was introduced. Previously this case fell through to System TTS,
// silently misrepresenting a Gateway persona's configured voice.
internal fun AssistantService.synthesizeGatewayTextForPlayback(
    text: String,
    persona: Persona,
    onNoAudio: () -> Unit
) {
    serviceScope.launch(Dispatchers.Main) {
        // Same teardown the device-voice play entries do: stopAudio() bumps ttsGeneration so any
        // in-flight chunk sequence halts atomically and the previous playback is released.
        stopAudio()
        val myGeneration = ttsGeneration
        if (silenced.value) {
            // No-play bail.
            landInIdle()
            return@launch
        }
        // Visible feedback for the synthesis latency, matching a live turn — instead
        // of the silent gap the old System-TTS substitution used to hide.
        _state.value = AssistantState.THINKING
        updateNotification("Thinking...")
        // Reuses the existing chunked pipeline verbatim: sentence chunking, per-chunk
        // synthesis via synthesizeWithGateway, gapless playback, word timestamps.
        val played = playChunkedTtsGateway(text, persona, myGeneration)
        // Empty result with a still-current generation means nothing was synthesized —
        // server unreachable, auth failure, or an HTTP error. Each is already logged
        // with its status by synthesizeWithGateway and reflected in the server status
        // dot; this makes it visible at the point of use instead of silently switching
        // to a different voice. A stale generation means the user stopped it, which
        // stopEverything() has already reflected in the UI.
        if (played.isEmpty() && ttsGeneration == myGeneration) onNoAudio()
    }
}

fun AssistantService.replayMessageAudio(message: ChatMessage, persona: Persona) {
    val storedPath = message.audioFilePath
    if (storedPath != null && Files.exists(Path.of(storedPath))) {
        // Cloud-path pre-synthesis persisted the exact bytes that were played, so
        // replay them verbatim regardless of voice mode. The reveal is re-armed here
        // too: playAudioFile() publishes its own _voiceDuration from the file, and
        // without the request the UI's "already animated" cache would keep this
        // replay visually silent (text in full, no sync).
        requestRevealRestart(message)
        playAudioFile(Path.of(storedPath))
        return
    }
    if (persona.voiceMode == VoiceMode.GATEWAY) {
        // Streamed/chunked Gateway turn: no persisted .wav exists (see
        // synthesizeGatewayTextForPlayback). Re-synthesize the stored text through the
        // persona's OWN Gateway voice. message.text is the original markdown, kept for
        // the chat bubble — the chunked pipeline cleans each chunk itself, exactly as
        // the live streaming flow does. Deliberately NO reveal request: the chunked
        // path owns the response with its karaoke highlight, and the UI's reveal gate
        // is bypassed while a fraction is live.
        synthesizeGatewayTextForPlayback(message.text, persona) {
            updateNotification("Voice unavailable - check TTS server")
            landInIdle()
        }
        return
    }
    // Unchanged: device-voice personas (System TTS / bundled eSpeak) replay through
    // their own engine, cleaning the stored markdown off-Main first.
    serviceScope.launch(Dispatchers.IO) {
        val cleaned = cleanTextForTts(message.text)
        withContext(Dispatchers.Main) {
            // Fix #1 follow-up for this entry point: System TTS does NOT route through
            // stopAudio() at all — speakTextOnDevice() never tore anything down. A
            // fraction/timestamps left over from an earlier Gateway turn would therefore
            // survive into a System-TTS replay and satisfy the UI's karaoke gate (stale
            // highlight / suppressed reveal). The stale _voiceDuration matters here too:
            // System TTS never publishes a duration of its own, so a left-over one would be
            // read by the reveal as this replay's length. Cleared before playback so the
            // reveal waits for a genuinely fresh duration (eSpeak) or falls back to its
            // heuristic (System TTS) — never a value from the previous response.
            _ttsPlaybackFraction.value = null
            _ttsWordTimestamps.value = null
            _voiceDuration.value = 0
            // Fix #3: arm the timed reveal for this replay (must be emitted BEFORE
            // playback so the UI is already waiting when the engine publishes the
            // replay's duration).
            requestRevealRestart(message)
            playResponse(persona, deviceText = cleaned)
        }
    }
}

/**
 * Fix #3: publishes a RevealRestartRequest for [message] so the classic
 * duration-synced reveal runs again over the audio the replay is about to play.
 * Called only by the NON-chunked replay paths (device-voice engine, stored file):
 * the chunked Gateway path keeps its karaoke highlight instead.
 *
 * The message's index into _messages is resolved by identity first (the caller
 * passes the very instance the UI holds, which is the instance in the list) and by
 * role+text as a fallback. A message that cannot be located is not signalled at
 * all — the UI honors a request only when its index still addresses the last
 * assistant bubble, so a wrong/absent index would be ignored anyway.
 */
private fun AssistantService.requestRevealRestart(message: ChatMessage) {
    val list = _messages.value
    var index = list.indexOfFirst { it === message }
    if (index < 0) index = list.indexOfFirst { it.role == message.role && it.text == message.text }
    if (index < 0) return
    revealRestartSeq++
    _revealRestartRequest.value = RevealRestartRequest(token = revealRestartSeq, messageIndex = index)
}
