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

    // material3 and the icon set, which `compose.desktop.currentOs` does not bring. Both come
    // through the Compose *plugin's* accessors rather than as coordinates here: Compose
    // Multiplatform versions material3 on its own release line, independently of `compose`
    // (libs.versions.toml), so writing a version would be a guess that drifts silently. The
    // plugin is pinned, and these follow it.
    //
    // The upstream app gets material3 from the AndroidX Compose BOM and its icons from
    // `androidx.compose.material:material-icons-extended:1.7.8`. Neither coordinate has a
    // desktop variant — the AndroidX artifacts are Android-only — so the Android *version* is
    // not transferable here and the plugin's own version is the correct pin.
    //
    // Measured rather than assumed: tools/compose-probe.sh reports 81 unresolved imports
    // without these two lines and 9 with them (PLAN.md D2).
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    // Coil 3, for persona artwork, message attachments and generated images — the same
    // coordinate the upstream app declares. See libs.versions.toml for why no network fetcher
    // accompanies it.
    implementation(libs.coil.compose)

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
