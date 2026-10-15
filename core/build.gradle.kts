// :core — the ported upstream files and the logic that is already platform-independent.
//
// This module must not depend on :platform or :desktop (AGENTS.md §2). When something
// here needs the desktop — PipeWire, notify-send, XDG paths, the filesystem layout — the
// answer is a new interface declared here and implemented in :platform, never an import.
//
// The dependencies are the upstream app's, minus everything Android supplied:
//   serialization  the settings blob and the persona/server/skill model are @Serializable
//   okhttp         every backend (chat, gateway, ComfyUI, RAG, SearXNG) is HTTP
//   coroutines     the service core is Flow-based
//   onnxruntime    Silero VAD; the JVM artifact here, the Android one upstream (seam 4)
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    // JDK 25, not the 21 PLAN.md first pinned — rationale in the root build.gradle.kts.
    jvmToolchain(25)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.onnxruntime)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    useJUnit()
}
