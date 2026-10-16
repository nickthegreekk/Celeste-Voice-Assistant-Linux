package com.nikosm.voiceassistant.linux

import com.nikosm.voiceassistant.AppLogger
import com.nikosm.voiceassistant.LogLevel

/**
 * Keeps what was logged, so a test can assert on the message rather than only on the return value.
 *
 * Two of the platform seams communicate their failure *through* the log — `parseSpeechWav` explains
 * which part of a WAV it refused, and `SingleInstance` says who already holds the lock — and for
 * both of those the message is the user-facing behaviour. Capturing them is how a test reaches it
 * without reading a terminal.
 */
class RecordingLogger : AppLogger {
    val errors = mutableListOf<String>()
    val warnings = mutableListOf<String>()

    override fun log(level: LogLevel, tag: String, message: String, error: Throwable?) {
        when (level) {
            LogLevel.ERROR -> errors.add(message)
            LogLevel.WARN -> warnings.add(message)
            else -> {}
        }
    }
}
