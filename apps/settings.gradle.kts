// TASK-KMP: single Gradle build for the client/ui multiplatform modules, the thin
// Android application, and the debug-drawer library modules (see include() below).
// (api = TS, cli = its own Kotlin build — both are sibling dirs, intentionally NOT
// included here.)
pluginManagement {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
  }
}
dependencyResolutionManagement {
  repositories {
    // TEMPORARY (config-debug panel): swip-core 0.1.9-SNAPSHOT + schema-dayfold 0.1.5-SNAPSHOT
    // carry the SwipConfigDebug seam (swip PR #56, still OPEN — nothing published above
    // swip-core 0.1.8). Built locally with `./gradlew :swip-core:publishToMavenLocal
    // :schema-dayfold:publishToMavenLocal` from the swip `config-debug-seam` branch.
    // REMOVE this repo + repin to the released versions once PR #56 merges and publishes.
    // CI has no ~/.m2 — this branch cannot go green until then.
    mavenLocal()
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    // SWIP bug reporter + SloopWorks design tokens (private GitHub Packages).
    // Local dev: gpr.user/gpr.token in ~/.gradle/gradle.properties (read:packages PAT).
    // CI: SLOOPWORKS_PACKAGES_TOKEN secret.
    listOf(
      "https://maven.pkg.github.com/SloopWorks/swip",
      "https://maven.pkg.github.com/SloopWorks/sloopworks-ui",
    ).forEach { pkgUrl ->
      maven {
        url = uri(pkgUrl)
        credentials {
          username = System.getenv("GITHUB_ACTOR") ?: providers.gradleProperty("gpr.user").orNull ?: ""
          password = System.getenv("SLOOPWORKS_PACKAGES_TOKEN") ?: providers.gradleProperty("gpr.token").orNull ?: ""
        }
      }
    }
  }
}

rootProject.name = "dayfold-apps"
include(":client", ":ui", ":androidApp", ":debugdrawer", ":debugdrawer-noop", ":debugdrawer-redux", ":debugdrawer-swip", ":swip-wiring")
