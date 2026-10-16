// :desktop — the Compose Multiplatform UI, the window, the tray, and the packaging.
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
        // Gives the module `run` and `packageDistribution` (PLAN.md §6). The
        // `nativeDistributions` block — target formats, icons, the bundled runtime — is
        // M6 packaging work and is deliberately not guessed at here.
        mainClass = "com.nikosm.voiceassistant.MainKt"

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
        javaHome = javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(25)
        }.get().metadata.installationPath.asFile.absolutePath
    }
}
