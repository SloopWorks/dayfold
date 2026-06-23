// TASK-KMP: single Gradle build for the client multiplatform module + the thin
// Android application that depends on it. (api = TS, cli = its own Kotlin build —
// both are sibling dirs, intentionally NOT included here.)
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
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
  }
}

rootProject.name = "dayfold-apps"
include(":client", ":androidApp", ":debugdrawer", ":debugdrawer-noop")
