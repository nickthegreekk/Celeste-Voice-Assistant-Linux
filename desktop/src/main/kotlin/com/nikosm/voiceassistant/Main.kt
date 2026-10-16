package com.nikosm.voiceassistant

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.nikosm.voiceassistant.linux.AudioDeviceProbe
import com.nikosm.voiceassistant.linux.AwtFileDialogs
import com.nikosm.voiceassistant.linux.ImageIoCodec
import com.nikosm.voiceassistant.linux.JsonSettingsStore
import com.nikosm.voiceassistant.linux.LinuxAppEnv
import com.nikosm.voiceassistant.linux.StubAudioCapture
import com.nikosm.voiceassistant.linux.StubAudioDevices
import com.nikosm.voiceassistant.linux.PwPlayback
import com.nikosm.voiceassistant.linux.StubTextToSpeech
import com.nikosm.voiceassistant.ui.theme.VoiceAssistantTheme
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * The one entry point. `--headless` is a flag on this program, not a second program — the service
 * core and the UI are the same process and talk through the same `StateFlow`s (PLAN.md §3, D1).
 *
 * At M0 there is nothing to run yet, so argument handling is exactly one recognised flag. The
 * window, the tray and the real flag set (`--headless`, `--listen`, `--send-text`) arrive with the
 * milestones that build them; guessing at them now would be inventing a CLI.
 *
 * `--selftest` is the milestone's acceptance criterion and the shape M6's packaging smoke test
 * reuses (PLAN.md §11 runs `celeste-linux --selftest` against an installed artefact). It prints
 * what the application resolved about its environment and exits:
 *
 *   - **0** when the settings directory is writable — the only condition that is ever a failure;
 *   - **1** when it is not.
 *
 * A missing upstream pin is *not* a failure. It is printed as a warning, because a build with no
 * pin can still be perfectly usable, and a smoke test that fails for a reason unrelated to the
 * machine it is testing is worse than no smoke test.
 */
fun main(args: Array<String>) {
    if (args.contains("--selftest")) exitProcess(selfTest())

    // Everything the service needs, built here and nowhere else. This is the one place in the
    // project that knows what a Linux machine is: the seams' implementations are chosen here, and
    // `:core` sees only their interfaces. D1 says one process, so there is no binder, no intent and
    // no `onCreate` — the service exists before the first frame and the UI reads it directly.
    val appEnv = LinuxAppEnv.resolve().installLogging()

    val settingsManager = SettingsManager(
        appEnv = appEnv,
        store = JsonSettingsStore(appEnv.configDir.resolve("settings.json"), appEnv.logger),
        imageCodec = ImageIoCodec(),
    )

    val service = AssistantService(
        appEnv = appEnv,
        // The four audio seams are M2's, and these are the stubs that say so once, at INFO, naming
        // the milestone that replaces them. A build in this state starts, converses and persists;
        // it does not hear or speak. `grep -rn 'Stub' platform/src/main` is the whole list.
        audioPlayback = PwPlayback(appEnv.logger),
        audioDevices = StubAudioDevices(appEnv.logger),
        tts = StubTextToSpeech(appEnv.logger),
        audioCapture = StubAudioCapture(appEnv.logger),
        fileDialogs = AwtFileDialogs(appEnv.logger),
        settingsManager = settingsManager,
    )
    service.start()

    application {
        Window(
            onCloseRequest = {
                // Persist and tear down before the process goes: the settings write is debounced,
                // so an unflushed window close would lose the last edit. The same call has to be
                // the one Stop uses, so there is one teardown path rather than two that can drift.
                service.shutdown()
                exitApplication()
            },
            title = "Celeste",
            state = rememberWindowState(width = 460.dp, height = 880.dp),
        ) {
            VoiceAssistantTheme {
                MainScreen(service = service)
            }
        }
    }
}

/** The pin the sync script writes on every successful copy (PLAN.md §7a). */
private const val PIN_RESOURCE = "upstream-commit.txt"

private fun selfTest(): Int {
    // The environment the rest of the process would run on, with its logger installed first —
    // so anything logged from here on lands where it will land in a real session, rather than in
    // a default that only `--selftest` ever sees.
    val appEnv = LinuxAppEnv.resolve().installLogging()

    println("celeste-linux self-test")
    println()
    println("upstream commit")
    println("  ${upstreamPin()}")
    println()
    println("XDG paths")
    println("  config : ${appEnv.configDir}")
    println("  data   : ${appEnv.dataDir}")
    println("  state  : ${appEnv.stateDir}")
    println("  cache  : ${appEnv.cacheDir}")
    println()
    // Printed rather than assumed because it is a privacy switch, not a verbosity one: it gates
    // logging of prompts, model output and message history (AppEnv.debugLogging). A run that
    // believes it is quiet and is not is the failure this line exists to make visible.
    println("debug logging: ${if (appEnv.debugLogging) "ON — conversation content will be logged" else "off"}")
    println()
    println("audio devices")
    println("  ${AudioDeviceProbe.describe()}")
    println()

    return reportSettingsDir(appEnv.configDir)
}

/**
 * Reads the upstream revision the ported files were copied from. The resource lives in `:core`
 * beside the copied sources rather than here, so that the pin travels with the files it describes
 * and cannot be shipped without them.
 */
private fun upstreamPin(): String {
    val pin = AudioDeviceProbe::class.java.classLoader
        .getResourceAsStream(PIN_RESOURCE)
        ?.use { it.readBytes().decodeToString() }
        ?.trim()

    if (pin.isNullOrEmpty()) {
        // Deliberately still exit 0. See the note on main.
        return "UNKNOWN — $PIN_RESOURCE missing from the classpath (warning; does not affect the exit code)"
    }
    return pin
}

/**
 * Creates the application's own config directory if it is not there yet and writes a temporary
 * file into it. That directory is one the application creates and writes on its own account, so
 * probing it leaves nothing behind that was not going to exist anyway; the probe file is deleted
 * immediately, and on a failure the `catch` below is the only thing that runs.
 */
private fun reportSettingsDir(configDir: Path): Int {
    println("settings directory")
    val problem = try {
        Files.createDirectories(configDir)
        Files.deleteIfExists(Files.createTempFile(configDir, ".selftest-", ".tmp"))
        null
    } catch (e: IOException) {
        e.message ?: e::class.java.name
    } catch (e: SecurityException) {
        e.message ?: e::class.java.name
    }

    // Only IOException and SecurityException are caught on purpose. A bare `catch (Exception)`
    // would turn a bug in this file into "the directory is not writable", which is a wrong answer
    // to the one question this command exists to answer.
    if (problem == null) {
        println("  writable : $configDir")
        return 0
    }
    println("  NOT WRITABLE : $configDir")
    println("  $problem")
    return 1
}
