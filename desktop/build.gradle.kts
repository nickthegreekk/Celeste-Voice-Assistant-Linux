// :desktop — the Compose Multiplatform UI, the window, the tray, and the packaging.
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
//
// One process, not two (PLAN.md §3, D1): the service core and the UI live together and
// talk through the StateFlows the ported code already exposes. `--headless` is a flag
// handled by the same entry point, not a second program.
//
// The Compose *compiler* plugin is pinned to the Kotlin version (libs.versions.toml),
// the Compose *runtime* to its own release line. `compose.desktop.currentOs` resolves to
// the linux-x64 artifacts and brings Skiko with it.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    // JDK 25, not the 21 PLAN.md first pinned — rationale in the root build.gradle.kts.
    jvmToolchain(25)
}

// The icon as a *resource*, so the window can hand it to AWT: `Window(icon = …)` is what fills in
// `_NET_WM_ICON`, which is what a taskbar draws and what an alt-tab list shows. The directory is
// `packaging/icons` rather than a copy under `src/main/resources`, so the one PNG is the file
// jpackage embeds, the file the packages install into hicolor, and the file the taskbar draws.
sourceSets {
    main {
        resources.srcDir(rootProject.file("packaging/icons"))
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":platform"))

    implementation(compose.desktop.currentOs)

    // material3, the icon set and Coil are NOT declared here. They went to :core, which holds the
    // ported screens; this module is the desktop shell — the window, the tray, packaging and
    // `Main.kt` — and a `Main.kt` that only opens a window does not need any of them. The Compose
    // artifacts :desktop does need arrive transitively, because :core exposes them as `api`.
    //
    // `compose.desktop.currentOs` stays: it is what brings the Skiko runtime and the desktop
    // window toolkit, which is desktop-specific and has no counterpart in :core.
    // Compose Desktop runs on the Swing EDT; without this, `Dispatchers.Swing` and the
    // Main dispatcher that the desktop UI needs are missing at runtime.
    implementation(libs.kotlinx.coroutines.swing)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    useJUnit()
}

// The Compose plugin's `run` task is a JavaExec and does **not** inherit the Kotlin toolchain the
// way `compileKotlin` and `test` do. Without this it launches the application on whatever `java`
// is first on PATH — on the build box that is JDK 17, while these classes are compiled to
// class-file version 69 (JDK 25), and the result is an UnsupportedClassVersionError from
// `./gradlew :desktop:run`. That is precisely the command M0.5 is accepted against, so this is not
// cosmetic: a build whose toolchain is never exercised at *run* time is a build that only looks
// pinned.
//
// Point the launcher at the same JDK the toolchain compiles with, not at the machine default, so
// the app runs on the JDK M6 bundles into the package.
compose.desktop {
    application {
        // Gives the module `run` and `packageDistribution` (PLAN.md §6), and the
        // `nativeDistributions` block below is M6's work — written now that the milestone that
        // owns it has arrived, rather than guessed at earlier.
        mainClass = "com.nikosm.voiceassistant.MainKt"

        nativeDistributions {
            // The packaged application is `celeste-linux`, which is the name the unit file, the
            // per-persona `.desktop` entries and the documentation all already use.
            packageName = "celeste-linux"
            packageVersion = project.version.toString()
            description = "Celeste — the assistant, ported to Linux"
            vendor = "VoiceAssistant-Linux"
            // Upstream is GPL-3.0 and LICENSE travels with the port; the package says the same.
            copyright = "GPL-3.0"
            // The `.deb` is what the containers build (PLAN.md §6: a jpackage runtime carries the
            // build host's glibc, so a Debian artefact has to be made on Debian). Declared here so
            // the same project can produce it there; building it *here* needs dpkg-deb, and
            // `createDistributable` — the app image — needs nothing but the JDK.
            targetFormats(TargetFormat.Deb)
            linux {
                // A placeholder drawn for the purpose: the repository had no icon, and jpackage
                // wants a real file. Same shape as the tray's drawn icon, and the same caveat —
                // M6 packaging is where a real asset would land, not a second source of truth.
                iconFile.set(project.file("../packaging/icons/celeste-linux.png"))
                appCategory = "Utility"
                debMaintainer = "nikos@localhost"
            }
            // A module list rather than `includeAllModules = true`, which measured 354 MB with
            // 165 MB of runtime. Every entry here is a *call site's* requirement rather than a
            // guess, and the reasoning is the list:
            //   java.desktop      AWT and Swing — the window, the tray, the file dialogs, Skiko
            //   jdk.unsupported   sun.misc.Unsafe, which Skiko and Netty-style code still reach for
            //   java.instrument   what Compose's runtime and OkHttp ask for on the JVM
            //   java.management   ONNX Runtime's and OkHttp's JMX usage
            //   java.naming       TLS and DNS resolution paths inside the JDK's own clients
            //   java.xml          org.json's and the ported code's XML-adjacent helpers
            //   jdk.crypto.ec     the elliptic-curve half of TLS — every backend here is HTTPS
            // What this list cannot promise is that it is complete: the self-test exercises paths,
            // a graphics stack and subprocess spawning, and it does *not* exercise TLS, an ONNX
            // inference or an audio device. Those are verified by running the packaged application
            // for real, which is what M6's acceptance does in its containers.
            modules(
                "java.desktop",
                "jdk.unsupported",
                "java.instrument",
                "java.management",
                "java.naming",
                "java.xml",
                "jdk.crypto.ec",
            )
            // JDK 25 warns that Skiko loads its native library without declaring native access,
            // and PLAN.md §10/§6 asks for this flag in the packaged application's options.
            jvmArgs += listOf("--enable-native-access=ALL-UNNAMED")
            // `Main.kt` reaches `sun.awt.X11.XToolkit.awtAppClassName` by reflection to give the
            // window a `WM_CLASS` a dock can match to `celeste-linux.desktop` — without it the
            // window reports `java-lang-Thread` and wears a generic icon. The package is not
            // exported, so the reflection needs this open; it is the application's own process and
            // the one field it touches, and the failure without it is a logged warning rather than
            // a crash (`claimTheWindowClass`).
            jvmArgs += listOf("--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED")
        }

        // The plugin's `run` task derives its `executable` from this and **not** from the Kotlin
        // toolchain, the way `compileKotlin` and `test` do. Left unset it launches the application
        // on whatever JDK started the Gradle daemon — on the build box, JDK 17 — while these
        // classes are compiled to class-file version 69 (JDK 25), and `./gradlew :desktop:run`
        // dies with an UnsupportedClassVersionError. That is exactly the command M0.5 is accepted
        // against, so this is not cosmetic: a build whose toolchain is never exercised at *run*
        // time is a build that only looks pinned.
        //
        // Point it at the same JDK the toolchain compiles with, so the app runs on the JDK M6
        // bundles into the package rather than on the machine's default. Setting it here and not
        // through `tasks.withType<JavaExec>` on purpose: the plugin writes `executable` itself,
        // after any task configuration a build script adds, so a `setExecutable` call is silently
        // overwritten. `javaHome` is the plugin's own knob and it wins.
        // **The packaging JDK, and it has to be one with `jmods`.** Measured on this box: the
        // Gradle-provisioned Adoptium 25 has no `jmods/` directory, and `jlink` refuses it
        // outright — "This JDK does not contain packaged modules and cannot be used to create
        // another image" — so the app image could not be built with it at all. The system JDK 27
        // does have them, and a 27 runtime runs the JVM-25 classes this project compiles (a newer
        // runtime is backward compatible; the *target* is still 25, set by the toolchain above).
        //
        // This one knob also decides which JDK `./gradlew :desktop:run` uses. That is the trade:
        // packaging needs a complete JDK, and the alternative is provisioning another 25 that may
        // or may not carry `jmods`. PLAN.md §10's M6 records the requirement.
        javaHome = file("/usr/lib/jvm/java-27-openjdk").takeIf { it.isDirectory }?.absolutePath
            ?: javaToolchains.launcherFor {
                languageVersion = JavaLanguageVersion.of(25)
            }.get().metadata.installationPath.asFile.absolutePath
    }
}
