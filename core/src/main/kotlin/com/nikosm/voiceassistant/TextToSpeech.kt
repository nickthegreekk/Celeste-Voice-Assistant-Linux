package com.nikosm.voiceassistant

/**
 * Raw speech, ready to be played.
 *
 * Signed 16-bit mono PCM at [sampleRate], matching what [AudioPlayback.playPcm] takes — the two
 * seams meet here and nowhere else, so there is exactly one representation of "synthesised audio"
 * in the port rather than one per engine.
 */
data class SpeechSamples(val pcm: ByteArray, val sampleRate: Int) {
    /** Length in milliseconds, from the byte count — the source of `voiceDuration`'s value. */
    val durationMs: Int get() = if (sampleRate <= 0) 0 else pcm.size / 2 * 1000 / sampleRate
}

/**
 * Seam 6 (PLAN.md §5): the two on-device speech routes.
 *
 * The third route upstream has — the gateway's `/synthesize` — is not a seam and never was: it is
 * HTTP, it already worked on Linux, and it stays in `AssistantServiceGateway.kt` untouched. What
 * needs replacing is the two Android supplied:
 *
 *  - the **system** engine, Android's `TextToSpeech` → speech-dispatcher. It owns its own playback,
 *    so the seam reports completion rather than returning audio.
 *  - the **local** engine, the app's *bundled* eSpeak — a JNI shim over `arm64-v8a/libespeak-ng.so`
 *    plus a 439-file asset tree copied out of the APK. Neither comes across (§5), and on Linux
 *    eSpeak-NG is a distro package, so this becomes `espeak-ng` (CLI or JNA to the distro
 *    `libespeak_ng.so`) and the asset copier is deleted rather than ported.
 *
 * The split is not cosmetic — the two routes are shaped differently and the ported code depends on
 * the difference. The system route hands text over and gets told when it finished; the local route
 * *returns samples*, because `speakWithEspeak` computes `voiceDuration` from them and plays them
 * through [AudioPlayback] so the app keeps control of playback. Flattening the two into one
 * "speak(text)" method would lose the duration the UI reveal is built on.
 *
 * ## What an implementation owes the caller
 *
 * **The local engine is process-global and must be initialised exactly once.** Upstream says why,
 * in a comment worth keeping: eSpeak keeps its state in process-global natives, and two concurrent
 * constructors call `espeak_Initialize` twice on different threads — the native side then aborts
 * with "pthread_mutex_lock called on a destroyed mutex", observed on a real device. Upstream
 * solved it with a double-checked lock around a nullable field; the seam inherits the requirement
 * and the implementation is where the lock belongs.
 *
 * **A synthesis failure is not an exception.** Both routes return null (local) or false (system)
 * when the engine is missing or refuses, because the caller's response is the same either way:
 * release the audio focus this turn retained and land in IDLE.
 */
interface TextToSpeech {

    /** True once the system speech service has answered and can be used. */
    val systemReady: Boolean

    /**
     * Speaks [text] through the system service.
     *
     * [utteranceId] is the caller's token, not the implementation's: upstream generates one per
     * call because `onDone`/`onError` may arrive out of order after a stop, and a stale callback
     * must not tear down a newer playback. The implementation must pass it back in [onEnd].
     *
     * @return false when nothing was queued — no engine, or the service refused — in which case
     * [onEnd] is never called and the caller cleans up immediately rather than waiting for a
     * callback that will not come.
     */
    fun speakSystem(text: String, utteranceId: String, onEnd: (PlaybackEnd) -> Unit): Boolean

    /** Stops the system route. Idempotent, and safe when nothing is speaking. */
    fun stopSystem()

    /**
     * Synthesises [text] with [voice] and returns the samples, or null when the local engine is
     * unavailable or the synthesis failed.
     *
     * [voice] is an eSpeak voice identifier — the ported language mapping (`"greek" -> "el"`,
     * `"japanese" -> "ja"`, …) stays in the caller, where it is, because it is a lookup table
     * about the user's languages rather than anything the engine knows.
     *
     * Returns mono PCM at the engine's own sample rate, which is not fixed and is why
     * [SpeechSamples] carries it.
     */
    fun synthesizeLocal(text: String, voice: String): SpeechSamples?

    /** Releases the local engine, if it was built. Idempotent. */
    fun releaseLocal()
}
