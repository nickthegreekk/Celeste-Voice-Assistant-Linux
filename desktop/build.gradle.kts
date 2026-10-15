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
    // Compose Desktop runs on the Swing EDT; without this, `Dispatchers.Swing` and the
    // Main dispatcher that the desktop UI needs are missing at runtime.
    implementation(libs.kotlinx.coroutines.swing)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    useJUnit()
}

compose.desktop {
    application {
        // Gives the module `run` and `packageDistribution` (PLAN.md §6). The
        // `nativeDistributions` block — target formats, icons, the bundled runtime — is
        // M6 packaging work and is deliberately not guessed at here.
        mainClass = "com.nikosm.voiceassistant.MainKt"
    }
}
