// :platform — the Linux implementations of the fourteen seams (PLAN.md §5).
//
// This is the only module allowed to know about the machine: ProcessBuilder, absolute
// system paths, $XDG_*, the PipeWire helpers, secret-tool, notify-send (AGENTS.md §4).
// Everything it exposes is an interface :core declared.
//
// `api` rather than `implementation` on :core: seam implementations return and accept
// :core types, so a consumer of :platform necessarily sees them too.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    // JDK 25, not the 21 PLAN.md first pinned — rationale in the root build.gradle.kts.
    jvmToolchain(25)
}

dependencies {
    api(project(":core"))
    implementation(libs.kotlinx.coroutines.core)

    // Declared here rather than inherited from :core, even though :core already has it: the
    // settings store parses and writes JSON itself, and `:core` keeps it as an `implementation`
    // dependency precisely so that it does not leak outward. A module that uses a library says so.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    useJUnit()
}
