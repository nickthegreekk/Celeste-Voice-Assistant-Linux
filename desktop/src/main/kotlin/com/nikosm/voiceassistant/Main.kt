package com.nikosm.voiceassistant

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.rememberWindowState
import com.nikosm.voiceassistant.linux.AwtFileDialogs
import com.nikosm.voiceassistant.linux.AwtTray
import com.nikosm.voiceassistant.linux.ImageIoCodec
import com.nikosm.voiceassistant.linux.JsonSettingsStore
import com.nikosm.voiceassistant.linux.LinuxAppEnv
import com.nikosm.voiceassistant.linux.LinuxMcpTransportFactory
import com.nikosm.voiceassistant.linux.ProcessAudioCapture
import com.nikosm.voiceassistant.linux.LinuxAudioDevices
import com.nikosm.voiceassistant.linux.PwPlayback
import com.nikosm.voiceassistant.linux.ProcessTextToSpeech
import com.nikosm.voiceassistant.linux.ShutdownWatchdog
import com.nikosm.voiceassistant.linux.SingleInstance
import com.nikosm.voiceassistant.linux.SystemLookAndFeel
import com.nikosm.voiceassistant.ui.theme.VoiceAssistantTheme
import java.awt.Toolkit
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The one entry point. `--headless` is a flag on this program, not a second program — the service
 * core and the UI are the same process and talk through the same `StateFlow`s (PLAN.md §3, D1).
 *
 * `--listen` arms hands-free listening at launch, and it is seam 12's whole Linux implementation:
 * the plan's answer to Android's proximity barge-in is a desktop hotkey bound to this command
 * (PLAN.md §5), so what the application owes that hotkey is a flag that comes up listening. The
 * window opens with it — D1 keeps the service and the UI in one process, and `--headless` is the
 * separate question of running without one — and the bound key is documented per desktop in
 * `docs/DEVICES.md`.
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
 *
 * The flags: `--selftest`, `--listen` (M2), `--send-text` / `--send-file` (M3), `--headless` and
 * `--persona` (M4). There is no flag here that only pretends to work.
 */
fun main(args: Array<String>) {
    if (args.contains("--selftest")) exitProcess(selfTest())

    // Everything the service needs, built here and nowhere else. This is the one place in the
    // project that knows what a Linux machine is: the seams' implementations are chosen here, and
    // `:core` sees only their interfaces. D1 says one process, so there is no binder, no intent and
    // no `onCreate` — the service exists before the first frame and the UI reads it directly.
    val appEnv = LinuxAppEnv.resolve().installLogging()
    // Before the first Swing component exists — the file pickers and the tray menu are Swing, and
    // a look and feel installed after a component is created does not reach it. On a GTK desktop
    // this puts both on the system theme, dark variant included; on anything else it is one log
    // line and a fallback, never a failure.
    SystemLookAndFeel.install(appEnv.logger)
    val audioDevices = LinuxAudioDevices(appEnv.logger)

    // One copy of the program, because `--listen` makes a second launch easy: a hotkey fires
    // whether or not the window is already open, and two copies are two VAD loops on one
    // microphone — both transcribing the user and both answering. A second launch says who holds
    // the lock and exits non-zero, which is a real answer to "why did my hotkey do nothing".
    //
    // Held for the life of the process and held *reachably*: `FileLock` is released when its
    // channel is garbage-collected, so a guard that lived only in a local nothing reads again is a
    // guard the JIT may drop — and the second launch would then succeed at exactly the moment it
    // matters. The shutdown hook is what keeps it reachable; the close is tidiness, since the
    // kernel releases the lock at exit regardless.
    val instance = SingleInstance.acquire(appEnv.stateDir, appEnv.logger)
    if (instance == null) exitProcess(1)
    Runtime.getRuntime().addShutdownHook(Thread({ instance.close() }, "instance-lock-release"))
    // A shutdown hook that never returns used to leave a wedged process with no window, no lock and
    // its service still alive — see ShutdownWatchdog's KDoc for the measured instance. Armed here,
    // before either the windowed path or `--headless` branches, so both are covered.
    ShutdownWatchdog.install()

    val settingsManager = SettingsManager(
        appEnv = appEnv,
        store = JsonSettingsStore(appEnv.configDir.resolve("settings.json"), appEnv.logger),
        imageCodec = ImageIoCodec(),
    )

    val service = AssistantService(
        appEnv = appEnv,
        // The four audio seams are real as of M2 — `PwPlayback`, `LinuxAudioDevices`,
        // `ProcessTextToSpeech` and `ProcessAudioCapture` — so there is no `Stub*` left here to
        // name a milestone. `grep -rn 'Stub' platform/src/main` found one file — the scheduler —
        // until M4 replaced it with a real timer, and now finds none.
        audioPlayback = PwPlayback(appEnv.logger),
        audioDevices = audioDevices,
        tts = ProcessTextToSpeech(appEnv.logger),
        audioCapture = ProcessAudioCapture(audioDevices, appEnv.logger),
        fileDialogs = AwtFileDialogs(appEnv.logger),
        settingsManager = settingsManager,
        mcpTransportFactory = LinuxMcpTransportFactory(appEnv.logger),
    )
    service.start()

    // After `start()`, and that ordering is load-bearing rather than tidy: `startVadListening`
    // refuses to arm when no server is configured, and the server list is filled by
    // `loadSettings()`, which `start()` runs synchronously. Arming first would ask the question
    // before the answer had been read.
    if (args.contains("--listen")) {
        appEnv.logger.i("Main", "--listen: arming hands-free listening")
        service.startVadListening()
        // Checked here rather than trusted, because the hotkey's most likely misconfiguration has
        // to be legible from outside the application: a bound key is pressed while looking at
        // something else, and the service's own refusal ("No server configured") goes out through
        // `updateNotification`, which is a DEBUG line — below the default threshold, so it reaches
        // neither the terminal nor journald. Measured on the development box, whose settings have
        // no gateway: `--listen` armed nothing and said nothing.
        //
        // Hands-free transcribes through the Voice Gateway, so a server is a prerequisite here and
        // not a preference; the Ollama entries are for chat and cannot hear. The tray's status line
        // is where this belongs once M4 builds it.
        if (service.serverBases.value.isEmpty()) {
            appEnv.logger.w(
                "Main",
                "--listen: no Voice Gateway server is configured, so nothing was armed — " +
                    "hands-free listening transcribes through the gateway (Settings → Servers). " +
                    "The window is open and push-to-talk needs a server for the same reason.",
            )
        }
    }

    // `--headless`: the same program with no window — D1's "a flag, not a separate program". This
    // is the half of M4's acceptance that reads "while only the tray process runs": the service is
    // up, seam 10 arms, and a spontaneous message arrives as a desktop notification with nothing on
    // screen. It is deliberately *not* a Compose application with an empty composition — AWT's
    // event thread exits when no window is displayable, which would take the process with it — but
    // the same JVM with the service running and a latch holding the main thread.
    //
    // Teardown is the JVM's shutdown hook, so Ctrl-C and `systemctl --user stop` run the very same
    // `shutdown()` the window's close button does: the settings flush and the audio teardown happen
    // on all three paths rather than only the one with a UI.
    // The tray is built here because its actions need the service, and the service needs `AppEnv`:
    // the same ordering problem seam 10 had, solved the same way — the surface is created by the
    // one place that can see both. In headless mode it is the *only* way to reach the application,
    // which is why Quit is never disabled (see `trayMenu`).
    fun trayState(windowAvailable: Boolean) = TrayMenuState(
        status = service.statusLine.value,
        listening = service.handsFreeMode.value,
        muted = service.micMuted.value,
        windowAvailable = windowAvailable,
    )

    fun handleTrayAction(action: TrayAction, onOpen: () -> Unit, onQuit: () -> Unit) {
        when (action) {
            TrayAction.LISTEN ->
                if (service.handsFreeMode.value) service.stopVadListening() else service.startVadListening()
            TrayAction.MUTE -> service.toggleMicMute()
            TrayAction.OPEN -> onOpen()
            TrayAction.QUIT -> {
                service.shutdown()
                onQuit()
            }
        }
    }

    if (args.contains("--headless")) {
        appEnv.logger.i(
            "Main",
            "--headless: no window; the service is up and spontaneous messages arrive as " +
                "notifications. Ctrl-C stops it, and so will the systemd unit M4 still owes."
        )
        Runtime.getRuntime().addShutdownHook(Thread({ service.shutdown() }, "service-teardown"))

        val tray = AwtTray(
            state = { trayState(windowAvailable = false) },
            onAction = { action -> handleTrayAction(action, onOpen = {}, onQuit = { exitProcess(0) }) },
            logger = appEnv.logger,
        )
        if (tray.install()) {
            val trayScope = CoroutineScope(Dispatchers.Default)
            trayScope.launch {
                // The menu's labels depend on two flows that do not all change the status string,
                // so every change refreshes the tray rather than only the status ones.
                combine(service.statusLine, service.handsFreeMode, service.micMuted) { status, _, _ -> status }
                    .collect { tray.setStatus(it) }
            }
        }
        CountDownLatch(1).await()
        return
    }

    // SIGTERM is how an upgrade, a terminal kill or a launcher restart ends this process, and it
    // does not call `onCloseRequest`. Without this hook the service would keep the microphone and
    // the ONNX session while the JVM shuts down — measured 2026-10-17: a SIGTERM'd windowed
    // instance was alive forty minutes later, with no window and no lock, while a relaunch was
    // accepted. The headless path has had the same teardown since M4; `shutdown()` is idempotent,
    // so the ordinary close (which calls it too) is unaffected.
    Runtime.getRuntime().addShutdownHook(Thread({ service.shutdown() }, "service-teardown"))

    // Before any window exists, and only on the path that opens one: tell AWT what to call this
    // application in X11's `WM_CLASS`, which is the name a dock matches a window against to find the
    // menu entry that owns it — and therefore which icon a running Celeste wears.
    claimTheWindowClass(appEnv.logger)

    application {
        // `--send-text` / `--send-file`: parsed once, before the window exists, and handed to the
        // composition. Deliberately a *cold start* affordance — a second launch while one is running
        // is refused by the instance lock and names the holding pid (seam 12, 2026-10-16), so a
        // payload that arrived afterwards would have nowhere to land and no way to say so.
        // `user.home` rather than anything from `AppEnv`: the CLI is where a user types `~`, and a
        // `~` is a shell convention the application has to expand for itself — see parseShareArgs.
        val sharedRequest = parseShareArgs(args.toList(), Path.of(System.getProperty("user.home")))
        // `--persona <name>`: what a per-persona `.desktop` entry runs (see PersonaShortcuts.kt).
        // It feeds the same one-shot deep link the spontaneous notifications use, so a shortcut
        // inherits that path's behaviour — including consuming a persona that has since been
        // deleted without crashing.
        val personaArg = args.toList().let { list ->
            val index = list.indexOf("--persona")
            list.getOrNull(index + 1)?.takeIf { index >= 0 && it.isNotBlank() }
        }
        if (sharedRequest != null) {
            appEnv.logger.i("Main", "sharing into the composer: ${sharedRequest::class.simpleName}")
        }

        // Hoisted so the tray's Open can raise the same window the composition draws: two
        // `rememberWindowState()` calls would be two states, and the tray would be nudging one
        // nothing is listening to.
        //
        // **The size is remembered** (Settings → General, 2026-10-17, by request): restored in dp —
        // the unit `rememberWindowState` takes — and read through a tolerant parser, so a stored value
        // this build cannot read is the default window rather than a crash. The *placement* is not
        // stored: a maximized window saves nothing, so un-maximizing later still finds the last
        // normal size.
        val savedWindowSize = if (settingsManager.getRememberWindowSize()) settingsManager.getWindowSize() else null
        val windowState = rememberWindowState(
            width = (savedWindowSize?.first ?: 460).dp,
            height = (savedWindowSize?.second ?: 880).dp,
        )

        /** What Close and the tray's Quit both do: remember the size, then tear down. */
        fun closeTheApplication() {
            if (settingsManager.getRememberWindowSize() && windowState.placement == WindowPlacement.Floating) {
                val width = windowState.size.width.value.toInt()
                val height = windowState.size.height.value.toInt()
                if (width > 0 && height > 0) settingsManager.saveWindowSize(width, height)
            }
            // Persist and tear down before the process goes: the settings write is debounced, so an
            // unflushed window close would lose the last edit. The same call has to be the one Stop
            // uses, so there is one teardown path rather than two that can drift. The tray's Quit
            // used to skip it and could lose whatever the debounce had not written.
            service.shutdown()
            exitApplication()
        }

        Window(
            onCloseRequest = ::closeTheApplication,
            title = "Celeste",
            state = windowState,
            // The taskbar and alt-tab icon, from the same PNG the packages install into hicolor and
            // jpackage embeds as the launcher's (`desktop/build.gradle.kts` puts `packaging/icons`
            // on the resource path). See `windowIcon` for why this is not `painterResource`.
            icon = remember { windowIcon() },
        ) {
            // The application's own dark/light choice, not the desktop's: Compose's
            // `isSystemInDarkTheme()` cannot see an Xfce theme (Skiko returns UNKNOWN, mapped to
            // light), so the setting is the source of truth. Collected here, at the root, so the
            // Settings switch repaints the whole window immediately.
            val darkTheme by service.darkTheme.collectAsState()
            VoiceAssistantTheme(darkTheme = darkTheme) {
                // Held in composition state so the landing can clear it. Nothing else clears it, and
                // nothing needs to: the payload is a start-up argument, and this window is the only
                // one there will be.
                // The window's own tray: same menu, with Open (which has somewhere to go here)
                // and a Quit that closes the application rather than the process.
                val tray = remember {
                    AwtTray(
                        state = { trayState(windowAvailable = true) },
                        onAction = { action ->
                            handleTrayAction(
                                action,
                                onOpen = {
                                    windowState.isMinimized = false
                                    windowState.placement = WindowPlacement.Floating
                                },
                                onQuit = ::closeTheApplication,
                            )
                        },
                        logger = appEnv.logger,
                    )
                }
                DisposableEffect(Unit) {
                    tray.install()
                    onDispose { tray.remove() }
                }
                LaunchedEffect(service) {
                    combine(service.statusLine, service.handsFreeMode, service.micMuted) { status, _, _ -> status }
                        .collect { tray.setStatus(it) }
                }

                var pendingShare by remember { mutableStateOf(sharedRequest) }
                var pendingPersona by remember { mutableStateOf(personaArg) }
                MainScreen(
                    service = service,
                    sharedRequest = pendingShare,
                    onSharedHandled = { pendingShare = null },
                    deepLinkPersona = pendingPersona,
                    onDeepLinkHandled = { pendingPersona = null },
                )
            }
        }
    }
}

/**
 * What this machine's audio layer actually offers, through seam 3 rather than around it.
 *
 * `AudioDeviceProbe` used to print a line saying the seam was a stub, and its own KDoc said the two
 * would collapse "in M2, when there is a real device list for the selftest to describe". This is
 * that collapse. The ids are printed beside the labels because the id is the string the pickers
 * store and the players are pointed at — a label alone would tell a reader what was found and not
 * whether it could be used.
 *
 * An empty list is reported as empty rather than as silence, which is the distinction the stub
 * existed to preserve: "no devices were found" and "nothing was asked" must not look the same.
 */
private fun reportAudio(devices: AudioDevices) {
    fun line(prefix: String, list: List<AudioDevice>) {
        if (list.isEmpty()) {
            println("  ${prefix}: none found")
            return
        }
        list.forEachIndexed { i, d ->
            println("  ${if (i == 0) prefix else " ".repeat(prefix.length)}: ${d.label}")
            println("           ${d.id}")
        }
    }
    line("output", devices.outputs())
    line("input ", devices.inputs())
    val earpiece = devices.earpiece()
    println("  earpiece: ${earpiece?.label ?: "none - the ordinary desktop answer, and not a failure (seam 3)"}")
}

/** The pin the sync script writes on every successful copy (PLAN.md §7a). */
/**
 * The window's taskbar and alt-tab icon, or null when it cannot be loaded.
 *
 * **Why not `painterResource`:** that resolves through Compose's *resources* mechanism, which looks
 * for a generated resource path rather than a plain classpath entry, and it null-dereferences when
 * it finds nothing — inside the composition that opens the window, so the failure is an exception
 * before the first frame. Measured on the installed package: `NullPointerException` at
 * `ClassLoaderResourceLoader.load`, from this exact line, in the first build that carried the icon.
 * The PNG *was* in the jar; the lookup was the thing that was wrong, which is a distinction worth
 * keeping — verifying the file is present is not verifying that anything can find it.
 *
 * So the lookup is the plain classloader one and every failure is survivable: an absent icon is a
 * generic taskbar icon, which is where this started. The half that decides *which entry* a window
 * belongs to is the `WM_CLASS` claim above and the `StartupWMClass` line in the `.desktop` files,
 * and neither of those depends on this.
 */
private fun windowIcon(): Painter? = runCatching {
    val bytes = object {}.javaClass.getResourceAsStream("/celeste-linux.png")?.use { it.readBytes() }
        ?: return null
    BitmapPainter(loadImageBitmap(bytes.inputStream()))
}.getOrNull()

/**
 * The name X11's `WM_CLASS` reports for this application's window, and the name the `.desktop`
 * entries claim with `StartupWMClass=` — see [claimTheWindowClass] for why it takes reflection to
 * make the window say it.
 */
private const val WINDOW_CLASS = "celeste-linux"

/**
 * Tell AWT what this application is called in X11's `WM_CLASS`, which is what a dock matches a
 * window against to find the menu entry that owns it — and therefore which icon a running Celeste
 * wears next to its window.
 *
 * ## The derivation this overrides, and the bug it caused
 *
 * `sun.awt.X11.XToolkit`'s constructor takes the **bottom frame of the stack trace that constructed
 * it** and sanitizes it into `awtAppClassName`; `XWindow.postInit` then hands that to
 * `XSetClassHint`. Compose starts AWT from a coroutine on a Swing thread, so the bottom frame is
 * `java.lang.Thread` — measured on the installed application, `WM_CLASS(STRING) =
 * "java-lang-Thread", "java-lang-Thread"` — and no dock can match that to `celeste-linux.desktop`.
 * The window came up with a generic taskbar icon while the menu entry beside it had the right one.
 *
 * ## Why this is reflection and not a system property
 *
 * The obvious fix is `-Dsun.awt.X11.XWMClassName=…`. **That property does not exist in stock
 * OpenJDK**: it is a JetBrains Runtime feature, and grepping the JDK's own `java.desktop` X11
 * sources for it finds nothing — which is how this was caught, after a first version of this
 * function set the property and would have changed nothing. The *field* is what exists, it
 * is read when a window is created rather than when the toolkit is, and
 * `--add-opens java.desktop/sun.awt.X11=ALL-UNNAMED` (declared in `desktop/build.gradle.kts` beside
 * the native-access flag) is what makes it reachable.
 *
 * Order matters twice: the toolkit is forced into existence first, because its constructor is what
 * *writes* the field — set it earlier and the constructor would overwrite it — and the window has
 * yet to exist, which is why this runs before `application { }`.
 *
 * Failure is logged and survivable: a JDK that renames the field leaves the window with whatever
 * class AWT chose, which is the behaviour before this function existed, and the `Window(icon = …)`
 * icon still reaches the taskbar through `_NET_WM_ICON`.
 */
private fun claimTheWindowClass(logger: AppLogger) {
    val field = runCatching {
        Toolkit.getDefaultToolkit() // constructs XToolkit, which assigns the field itself
        Class.forName("sun.awt.X11.XToolkit").getDeclaredField("awtAppClassName").apply { isAccessible = true }
    }.getOrElse {
        logger.w("Main", "could not reach AWT's WM class field, so the window keeps AWT's own name: ${it.message}")
        return
    }
    runCatching { field.set(null, WINDOW_CLASS) }
        .onFailure { logger.w("Main", "could not name the window class $WINDOW_CLASS: ${it.message}") }
}

private const val PIN_RESOURCE = "upstream-commit.txt"

private fun selfTest(): Int {
    // The environment the rest of the process would run on, with its logger installed first —
    // so anything logged from here on lands where it will land in a real session, rather than in
    // a default that only `--selftest` ever sees.
    val appEnv = LinuxAppEnv.resolve().installLogging()
    val audioDevices = LinuxAudioDevices(appEnv.logger)

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
    reportAudio(audioDevices)
    println()

    return reportSettingsDir(appEnv.configDir)
}

/**
 * Reads the upstream revision the ported files were copied from. The resource lives in `:core`
 * beside the copied sources rather than here, so that the pin travels with the files it describes
 * and cannot be shipped without them.
 */
private fun upstreamPin(): String {
    val pin = AssistantService::class.java.classLoader
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
