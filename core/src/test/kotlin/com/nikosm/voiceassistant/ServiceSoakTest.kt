package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The M7 soak: start and shut a service down repeatedly and prove nothing accumulates.
 *
 * The defects this exists for were both real: a windowed exit left an orphan process with 61
 * threads and no window, and a `runTest` that forgot to shut its service produced an uncaught
 * exception in *whichever test ran next*. A single start/shutdown test would pass while leaking;
 * a cycle count is what makes growth visible.
 *
 * ## What is measured, and what deliberately is not
 *
 *  - **File descriptors** from `/proc/self/fd`. A service with the test fakes opens no sockets and
 *    starts no audio processes, so any growth is this application's own bookkeeping. The Linux-only
 *    guard skips the assertion elsewhere rather than failing on a missing procfs.
 *  - **Named threads**: anything matching the service's own helpers (`celeste`, `spontaneous`, the
 *    MCP children, the audio processes). `Dispatchers.IO`'s shared worker pool is excluded on
 *    purpose — it is JVM-wide, it grows to the load and stays, and counting it would report the
 *    test harness rather than a leak.
 *  - **Coroutine leaks** cannot be counted from outside; they surface as uncaught exceptions in a
 *    later test, which this suite already reports. Running many cycles in one test is how a leak
 *    that only occurs on the Nth start would be seen.
 *
 * ## The hour
 *
 * M7's acceptance names an hour of cycles. This test runs a bounded number by default
 * ([DEFAULT_CYCLES]) so the suite stays quick; `CELESTE_SOAK_CYCLES=3600` is a cycle-bounded
 * long run, and `CELESTE_SOAK_SECONDS=3600` is the acceptance run itself — it cycles until the
 * wall clock reaches the deadline and then applies the same assertions. Each cycle prints
 * nothing on success and fails with the cycle number when something grows.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ServiceSoakTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        // Each cycle shuts its own service down in a finally; only the dispatcher is left.
        Dispatchers.resetMain()
    }

    private fun persona() = Persona(
        name = "Soak",
        themeColor = Color(0xFF4ADE80),
        model = "[Home] model",
        systemPrompt = "You are a soak test.",
    )

    private fun openDescriptors(): Int? = runCatching {
        Files.list(Path.of("/proc/self/fd")).use { it.count().toInt() }
    }.getOrNull()

    private fun serviceThreads(): Map<String, Int> =
        Thread.getAllStackTraces().keys
            .map { it.name }
            .filter { name ->
                val n = name.lowercase()
                listOf("celeste", "spontaneous", "commandrunner", "mcp-", "pw-", "parec", "arecord", "espeak", "kokoro")
                    .any { n.contains(it) }
            }
            .groupingBy { it }
            .eachCount()

    /**
     * One service over the SAME env and store every cycle — a restart, not a fresh install. One
     * fixture root for the whole test also keeps the harness's own temp-dir bookkeeping constant:
     * 50,000 `FakeAppEnv`s would leave 50,000 trees to sweep at exit and measure the fixture
     * rather than the application.
     */
    private fun newService(env: AppEnv, settings: SettingsManager): AssistantService =
        AssistantService(
            appEnv = env,
            audioPlayback = SilentAudioPlayback,
            audioDevices = NoAudioDevices,
            tts = NoTextToSpeech,
            audioCapture = NoAudioCapture,
            fileDialogs = NoFileDialogs,
            settingsManager = settings,
        )

    @Test
    fun `repeated start and shutdown cycles leak no descriptors or service threads`() {
        // The duration run is M7's literal acceptance: cycle for an hour, then measure. `runTest`
        // times out on the wall clock, so its timeout has to cover the run rather than the default
        // minute; `CELESTE_SOAK_SECONDS` unset keeps the quick suite behaviour.
        val durationSeconds = System.getenv("CELESTE_SOAK_SECONDS")
            ?.toLongOrNull()
            ?.coerceIn(1, 6 * 60 * 60L)
        val timeout = if (durationSeconds != null) {
            kotlin.time.Duration.parse("${durationSeconds + 600}s")
        } else {
            kotlin.time.Duration.parse("60s")
        }
        runTest(timeout = timeout) {
        // Gradle's test JVM does not inherit the CLI's -D by default; the environment variable is
        // inherited, so it is the one the manual long run uses.
        val cycles = (System.getenv("CELESTE_SOAK_CYCLES") ?: System.getProperty("celeste.soak.cycles"))
            ?.toIntOrNull()
            ?.coerceIn(1, 100_000) ?: DEFAULT_CYCLES
        val env = FakeAppEnv(notifier = RecordingNotifier())
        val settings = SettingsManager(env, InMemoryStore(), NoImageCodec)
        settings.savePersonas(listOf(persona()))
        val fdsSupported = openDescriptors() != null

        // One warm-up cycle: class loading, the coroutine machinery and the JVM's own lazy pools
        // do their growth here, so the baseline measures the service rather than the JVM.
        newService(env, settings).let { service ->
            try {
                service.start()
                runCurrent()
            } finally {
                service.shutdown()
                runCurrent()
            }
        }
        Thread.sleep(30)

        val threadsBefore = serviceThreads()
        val fdsBefore = openDescriptors()

        fun runOneCycle() {
            val service = newService(env, settings)
            try {
                service.start()
                runCurrent()
            } finally {
                service.shutdown()
                runCurrent()
            }
        }

        var completed = 0
        if (durationSeconds != null) {
            val deadline = System.currentTimeMillis() + durationSeconds * 1_000
            while (System.currentTimeMillis() < deadline) {
                runOneCycle()
                completed++
                // Let any IO-dispatched cancellation unwind before the next cycle reuses the pools.
                if (completed % 10 == 0) Thread.sleep(5)
            }
        } else {
            repeat(cycles) { cycle ->
                runOneCycle()
                completed = cycle + 1
                if (cycle % 10 == 9) Thread.sleep(5)
            }
        }
        Thread.sleep(50)

        val leakedThreads = serviceThreads().filter { (name, count) ->
            count > (threadsBefore[name] ?: 0)
        }.keys
        assertTrue("service threads leaked after $completed cycles: $leakedThreads", leakedThreads.isEmpty())

        if (fdsSupported) {
            val fdsAfter = openDescriptors()
            if (fdsAfter != null && fdsBefore != null) {
                val delta = fdsAfter - fdsBefore
                assertTrue(
                    "descriptors grew by $delta over $completed start/shutdown cycles " +
                        "($fdsBefore -> $fdsAfter) — something is not being closed",
                    delta <= FD_DELTA_ALLOWANCE,
                )
            }
        }
        }
    }

    private companion object {
        /** Enough cycles to make per-cycle growth obvious without making the suite slow. */
        const val DEFAULT_CYCLES = 25

        /** JVM-level descriptors can move by a few (logging, class data); a leak grows per cycle. */
        const val FD_DELTA_ALLOWANCE = 5
    }
}
