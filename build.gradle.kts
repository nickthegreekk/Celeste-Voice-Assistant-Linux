// Root build. Deliberately thin: each module declares its own dependencies so that the
// :core -> :platform -> :desktop direction (AGENTS.md §2) stays readable in one file per
// module rather than being spread over a `subprojects { }` block here.
//
// A plain JVM build plus the Compose plugin. No Android plugin anywhere, and no Kotlin
// Multiplatform targets — Linux is the only target for v1 (PLAN.md §3, D11).
//
// All three modules pin `jvmToolchain(25)`, which is a recorded deviation from the JDK 21
// this plan originally specified. Measured on the build box (`./gradlew javaToolchains`):
// JDK 17 is the arch default, JDK 27 is installed, and a Temurin 25 LTS is already
// extracted under ~/.gradle/jdks. 21 is absent. Of the three, 27 cannot be a target at
// all — Kotlin 2.4.10's compiler stops at JVM_26, and jvmTarget 26 under a 27 toolchain
// would need the Java/Kotlin target-consistency check unpicked for no gain — so the real
// choice was 25 (LTS, present, no install, no download) against adding jdk21-openjdk to
// the machine. 25 wins because nothing about the design depended on 21: the toolchain is
// also the JDK jpackage builds the M6 runtime image from, and 25 is LTS like 21 was.
// PLAN.md §10, README.md and docs/BOOTSTRAP.md §1 have been updated to match.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose) apply false
}

allprojects {
    group = "com.nikosm.voiceassistant"
    version = "0.2.1"
}
