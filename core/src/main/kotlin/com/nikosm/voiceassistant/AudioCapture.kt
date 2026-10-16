package com.nikosm.voiceassistant

/**
 * Seam 1 (PLAN.md §5): microphone capture, without Android's audio stack.
 *
 * Android reached the microphone through `AudioRecord` — open, `startRecording`, blocking `read`
 * into a `ShortArray`, stop, release — plus `AcousticEchoCanceler` bolted onto the same session.
 * On Linux the first four become a `pw-record` process (`parec`, then `arecord`) emitting raw
 * s16le mono 16 kHz on stdout, and the fifth is deleted outright: PLAN.md §3/D8 decides there is no
 * echo cancellation, because the desktop's answer is headphones or push-to-talk and, if it is ever
 * wanted, a PipeWire `module-echo-cancel` sink selected as an input device.
 *
 * ## Why the interface has two types
 *
 * A stream is opened once and read many times, and that is exactly the shape upstream's loop has:
 * everything between `startRecording` and the `finally` block is a sequence of chunk reads. Making
 * `read` a method on a stream rather than a call on the seam mirrors it, and it is what lets the
 * implementation own the subprocess for the whole utterance — spawning one process per 32 ms chunk
 * would be absurd and is the mistake the shape prevents.
 *
 * ## The four values the caller must be able to insist on
 *
 * [open] takes the sample rate and the chunk size rather than deciding them, because PLAN.md §6
 * fixes both as behaviour: 16 kHz, mono, s16le, 512-frame (32 ms) chunks. They are tuned against
 * the Silero model and against the pre-roll ring, so they belong to the caller that knows why,
 * not to a capture implementation that would have to guess.
 *
 * [AudioDevice] is the `--target` of §5's table: which input to listen to. Null means the sound
 * server's default, which is the right answer far more often on a desktop than on a phone.
 */
interface AudioCapture {

    /**
     * Opens a capture stream, or returns **null when no device could be opened** — no server, no
     * microphone, a helper binary that is not installed.
     *
     * Null rather than an exception because the caller already has the right response and had it
     * upstream: it logs once and stops, leaving monitoring off rather than retrying into a hot
     * loop. A phone with no microphone is a broken phone; a desktop with no PipeWire session, or
     * one run over SSH, is an ordinary thing to be.
     */
    fun open(sampleRate: Int, chunkFrames: Int, target: AudioDevice? = null): CaptureStream?
}

/**
 * An open capture stream. Reading blocks until [frames] have been captured or the stream ends.
 */
interface CaptureStream {

    /**
     * Reads up to [frames] samples into [buffer], returning how many were read, or a negative
     * number when the stream has ended or failed.
     *
     * The caller treats a short read as a warning and keeps going — upstream logs "Unexpected read
     * size" and only acts on a full chunk — so an implementation must not turn a partial read into
     * a stream failure.
     */
    fun read(buffer: ShortArray, frames: Int): Int

    /**
     * Discards whatever audio is already buffered, without waiting for more.
     *
     * One moment needs this: the resume after a reply. The VAD is paused while the assistant
     * speaks, and a paused reader is a pipe that fills — the recorder process writes until the pipe
     * is full and then blocks — so up to ~2 s of the room as it was *before* the reply is sitting
     * there when listening resumes. Reading it is not neutral twice over: the VAD would judge the
     * present by audio from the past, and "how long until the mic is back" — the span a user feels,
     * and the one PLAN.md §6 never named — would be measured against audio that was already old
     * before the question was asked.
     *
     * Non-blocking by contract: it discards what is buffered and returns, because a no-op on an
     * empty stream is what keeps it usable from the loop that owns the read. It must not consume a
     * byte that arrived after the call, so an implementation asks what is available and takes only
     * that — the difference between clearing a backlog and eating live speech.
     */
    fun drain()

    /** Ends capture and releases the device. Idempotent: teardown paths call it defensively. */
    fun close()
}
