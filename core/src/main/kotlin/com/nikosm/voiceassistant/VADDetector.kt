package com.nikosm.voiceassistant

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * What the recorder needs from a speech detector: a probability for one chunk, and a reset between
 * utterances. Extracted for M7 so the recorder's failure paths — no microphone above all — can be
 * driven by a fake instead of constructing the ONNX session; [VADDetector] is the only production
 * implementation.
 */
interface SpeechDetector {
    /** Probability of speech in [samples], in `[0, 1]` (or a logit — see [VADDetector]). */
    fun isSpeech(samples: FloatArray): Float

    /** Forget the recurrent state between utterances. */
    fun reset()
}

/**
 * Silero VAD through ONNX Runtime — seam 4 (PLAN.md §5).
 *
 * **This file needs no interface and no `:platform` implementation, and that is the seam's point.**
 * §5's row reads "ONNX Runtime Android (`ai.onnxruntime.*`) → ONNX Runtime JVM linux-x64 — same
 * classes, same `silero_vad.onnx`", which is an *artifact* substitution rather than an API change:
 * `OnnxTensor`, `OrtSession` and `OrtEnvironment` are the same classes in both, so the only line
 * that had to change is where the model comes from.
 *
 * Upstream reads it with `context.assets.open("silero_vad.onnx")` — the APK's asset table. Here it
 * is a classpath resource, which is why this class no longer takes a `Context`: that parameter
 * existed only to reach the asset table. `core/build.gradle.kts` puts the repository's `assets/`
 * directory on this module's classpath, so the file the sync script copied — byte-identical, with
 * the checksum `docs/PROVENANCE.md` records — is the file that loads.
 *
 * Everything else is upstream's, including the two things that are easy to mistake for noise:
 * the sigmoid branch (a model returning outside `[0, 1]` is giving a logit, so the caller must not
 * compare it against a probability threshold), and the `use {}` around `s.run` — a comment upstream
 * keeps explaining that only closing on the success path leaked native memory on the missing-output
 * return and on any cast failure.
 *
 * Logging goes through [LogSink] rather than an injected logger, for the same reason upstream used
 * a global `android.util.Log`: this is constructed by the service before anything else is wired,
 * and it has no environment to be handed.
 */
class VADDetector : SpeechDetector {

    private val env = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null

    private var state: FloatArray = FloatArray(2 * 1 * 128)

    // Guards the recurrent `state` array: isSpeech() runs on the recorder's IO thread
    // while reset() runs on Main (resume()/stop()) — an unsynchronized fill-vs-read/write
    // could tear the state tensor mid-inference, and a post-run write-back could
    // resurrect pre-reset state. Kept separate from the recorder's data lock on purpose:
    // sharing it would hold the data lock across ONNX inference. synchronized is
    // reentrant, so the recorder's own IO-loop reset() calls never self-deadlock; the
    // only cross-thread cost is a coinciding Main-side reset() briefly waiting (~1ms).
    private val stateLock = Any()

    init {
        try {
            val modelBytes = javaClass.classLoader
                ?.getResourceAsStream(MODEL_RESOURCE)
                ?.use { it.readBytes() }
            if (modelBytes == null) {
                LogSink.logger.e(TAG, "Silero VAD model '$MODEL_RESOURCE' is not on the classpath")
            } else {
                session = env.createSession(modelBytes)
                LogSink.logger.d(TAG, "Silero VAD model loaded successfully")

                // Re-adding diagnostics to see exactly what this specific file wants
                session?.inputInfo?.forEach { (name, info) ->
                    LogSink.logger.d(TAG, "Input '$name' info: ${info.info}")
                }
                session?.outputInfo?.forEach { (name, info) ->
                    LogSink.logger.d(TAG, "Output '$name' info: ${info.info}")
                }
            }
        } catch (e: Exception) {
            // Not fatal: isSpeech() returns 0f for a missing session, so a machine without the
            // model behaves as a machine that never hears speech, rather than failing to start.
            LogSink.logger.e(TAG, "Failed to load Silero VAD model: ${e.message}")
        }
    }

    override fun isSpeech(samples: FloatArray): Float = synchronized(stateLock) {
        val s = session ?: return 0f

        val container = mutableMapOf<String, OnnxTensor>()
        val inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(samples), longArrayOf(1, samples.size.toLong()))
        val stateTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, 128))
        val srTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(16000)), longArrayOf())

        container["input"] = inputTensor
        container["state"] = stateTensor
        container["sr"] = srTensor

        return try {
            // OrtSession.Result is AutoCloseable and holds native memory: wrap it in
            // use {} so EVERY path closes it — the previous code only closed it after
            // a fully successful inference, leaking the native result on the
            // missing-output early return and on any cast/class-shape exception.
            s.run(container).use { result ->
                val outputObj = result.get("output").orElse(null)
                val stateNObj = result.get("stateN").orElse(null)

                if (outputObj == null || stateNObj == null) return@use 0f

                @Suppress("UNCHECKED_CAST")
                val rawOutput = (outputObj.value as Array<FloatArray>)[0][0]

                // If the model returns a value outside [0, 1], it's likely a logit and needs sigmoid.
                val prob = if (rawOutput < 0f || rawOutput > 1f) {
                    1.0f / (1.0f + kotlin.math.exp(-rawOutput.toDouble()).toFloat())
                } else {
                    rawOutput
                }

                @Suppress("UNCHECKED_CAST")
                val newState = stateNObj.value as Array<Array<FloatArray>>
                var idx = 0
                for (i in 0 until 2) {
                    for (k in 0 until 128) {
                        state[idx] = newState[i][0][k]
                        idx++
                    }
                }

                prob
            }
        } catch (e: Exception) {
            LogSink.logger.e(TAG, "Inference failed: ${e.message}")
            0f
        } finally {
            inputTensor.close()
            stateTensor.close()
            srTensor.close()
        }
    }

    override fun reset() {
        synchronized(stateLock) { state.fill(0f) }
    }

    fun close() {
        // Also under stateLock so the session can't be closed mid-inference (e.g.
        // onDestroy's close() racing the loop's last isSpeech() after stop()).
        synchronized(stateLock) {
            session?.close()
            env.close()
        }
    }

    private companion object {
        const val TAG = "VADDetector"

        /**
         * The same file the phone runs, byte-for-byte — the model and its sha256 are recorded in
         * `docs/PROVENANCE.md`. Named here rather than derived from a path because it is a
         * classpath entry: `core/build.gradle.kts` maps the repository's `assets/` directory in.
         */
        const val MODEL_RESOURCE = "silero_vad.onnx"
    }
}