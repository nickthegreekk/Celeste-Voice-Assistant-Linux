// Three modules, and the boundaries between them are load-bearing (AGENTS.md §2):
//
//   :core      ported upstream files. Must NOT import from :platform or :desktop.
//   :platform  Linux implementations of the seams. May import :core.
//   :desktop   Compose UI, window, tray, packaging. May import both.
//
// :core is a single module rather than several because upstream marks most of its
// types `internal` and Kotlin's `internal` stops at the module boundary — splitting the
// shared code would not compile (PLAN.md §3, D4).
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        // Compose Multiplatform's desktop artifacts are on Maven Central, but they pull
        // androidx.annotation and friends, which are only published to Google's Maven.
        google()
    }
}

rootProject.name = "celeste-linux"

include(":core")
include(":platform")
include(":desktop")
