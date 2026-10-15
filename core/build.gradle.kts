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

    // The real org.json, which upstream also uses on the JVM (its build file adds it for tests
    // because android.jar's stub returns defaults rather than parsing). Here it is needed for
    // real: the ported service parses HTTP responses with JSONObject, and SettingsManager's
    // legacy migrations and legacy-backup reader use it so that what this port reads is
    // byte-for-byte what the phone wrote.
    implementation(libs.org.json)

    // This is not a UI dependency and it is not here by accident — do not tidy it away.
    // Models.kt is a COPY file (PLAN.md §7a) and androidx.compose.ui.graphics.Color is part of
    // its *persisted* schema, not a decoration: `object ColorSerializer` persists
    // `value.toArgb()`, three @Serializable fields are typed `Color`, and about 35 ARGB literals
    // spell out the provider palette. Keeping that file byte-identical is worth one dependency.
    // The alternative — a project-owned colour type — is a permanent diff against the most
    // upstream-volatile file in the app, and a hand-rolled Color risks silently different
    // persisted values, which would break importing an Android backup, a stated feature.
    // Nothing in :core's own logic uses it. See also the note in libs.versions.toml about why
    // this must track `compose` exactly.
    implementation(libs.compose.ui.graphics)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// `assets/silero_vad.onnx` rides on this module's classpath rather than being copied into
// core/src/main/resources: the sync script puts it at the repository root, it is byte-identical
// there, and the model is part of the seam-4 contract (PLAN.md §5 — "same classes, same
// silero_vad.onnx"). One directory reference is better than a second copy that can drift.
sourceSets.main {
    resources.srcDir(rootProject.file("assets"))
}

tasks.withType<Test>().configureEach {
    useJUnit()
}
