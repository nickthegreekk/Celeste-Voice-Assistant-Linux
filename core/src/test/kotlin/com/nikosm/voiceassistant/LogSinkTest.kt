package com.nikosm.voiceassistant

import android.util.Log as AndroidLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The `android.util.Log` shim is the one piece of this project that exists purely so three COPY
 * files can stay byte-identical (PLAN.md §7a), and its whole contract is a translation: Android's
 * twelve static methods, onto [AppLogger], without dropping the tag, the message or the throwable.
 *
 * So these tests call the shim exactly as a COPY file does — `android.util.Log.d(tag, msg)`, no
 * receiver to inject — and assert what came out the other side. A shim that silently swallowed a
 * level would be invisible in production and is the thing worth pinning.
 */
class LogSinkTest {

    /** Records rather than prints, so an assertion can be made about what was logged. */
    private class RecordingLogger : AppLogger {
        val entries = mutableListOf<Triple<LogLevel, String, String>>()
        var lastError: Throwable? = null

        override fun log(level: LogLevel, tag: String, message: String, error: Throwable?) {
            entries += Triple(level, tag, message)
            lastError = error
        }
    }

    // LogSink is process-wide by necessity, so every test here restores it. Without this a
    // leftover recording logger would capture another test's output and the failure would appear
    // somewhere else entirely.
    private val original = LogSink.logger

    @After
    fun restoreSink() {
        LogSink.logger = original
    }

    @Test
    fun `every Android level reaches the installed logger, mapped as documented`() {
        val recording = RecordingLogger()
        LogSink.logger = recording

        AndroidLog.v("T", "verbose")
        AndroidLog.d("T", "debug")
        AndroidLog.i("T", "info")
        AndroidLog.w("T", "warn")
        AndroidLog.e("T", "error")
        AndroidLog.wtf("T", "assert")

        assertEquals(
            listOf(
                LogLevel.VERBOSE, LogLevel.DEBUG, LogLevel.INFO,
                LogLevel.WARN, LogLevel.ERROR, LogLevel.ASSERT,
            ),
            recording.entries.map { it.first },
        )
    }

    @Test
    fun `the tag and the message survive the trip`() {
        val recording = RecordingLogger()
        LogSink.logger = recording

        AndroidLog.i("AssistantService", "Hands-free stopped while a turn owns THINKING")

        assertEquals(Triple(LogLevel.INFO, "AssistantService", "Hands-free stopped while a turn owns THINKING"), recording.entries.single())
    }

    @Test
    fun `the three-argument form carries the throwable through`() {
        val recording = RecordingLogger()
        LogSink.logger = recording
        val boom = IllegalStateException("boom")

        AndroidLog.e("SettingsManager", "Corrupted/unreadable JSON in personas_v2", boom)

        assertEquals(LogLevel.ERROR, recording.entries.single().first)
        assertSame(boom, recording.lastError)
    }

    @Test
    fun `the two-argument form passes no throwable`() {
        val recording = RecordingLogger()
        LogSink.logger = recording

        AndroidLog.w("T", "no stack trace here")

        assertNull(recording.lastError)
    }

    @Test
    fun `a logger installed after the shim is already in use still takes effect`() {
        // The sink is read per call rather than captured at class-initialisation time. A COPY
        // file's object may be initialised long before the application installs its logger, and
        // capturing the default there would send everything to stderr forever.
        LogSink.logger = StderrLogger
        val recording = RecordingLogger()

        AndroidLog.d("T", "before")
        LogSink.logger = recording
        AndroidLog.d("T", "after")

        assertEquals(listOf("after"), recording.entries.map { it.third })
    }

    @Test
    fun `the shim keeps Android's int return`() {
        // Every call site in this project discards it, but a future verbatim file that used the
        // result would fail to compile if the signature had been narrowed to Unit.
        LogSink.logger = RecordingLogger()

        assertEquals(0, AndroidLog.d("T", "message"))
        assertEquals(0, AndroidLog.wtf("T", "message", RuntimeException("x")))
    }
}
