// Optional, debug-only adapter: embeds reduxkotlin's DevTools inspector
// (ReduxDevToolsPanel, redux-kotlin-devtools-inapp 1.0.0-alpha03+) as a SloopWorks
// debug-drawer panel. Apps add this debugImplementation only; it is never in release.
// Depends on :debugdrawer (the plugin API) — keeping the core drawer redux-agnostic.
plugins {
  kotlin("multiplatform")
  kotlin("plugin.compose")
  id("org.jetbrains.compose")
  id("com.android.library")
}

group = "com.sloopworks.debugdrawer"
version = "0.1.0-SNAPSHOT"

kotlin {
  jvmToolchain(17)
  androidTarget()
  jvm("desktop")
  listOf(iosArm64(), iosSimulatorArm64()).forEach {
    it.binaries.framework { baseName = "debugdrawerredux"; isStatic = true }
  }

  sourceSets {
    val commonMain by getting {
      dependencies {
        implementation(project(":debugdrawer"))
        // The real (debug) in-app DevTools — provides ReduxDevToolsPanel. This module
        // is itself debug-only (app wires it debugImplementation), so no noop is needed.
        implementation("org.reduxkotlin:redux-kotlin-devtools-inapp:1.0.0-alpha03")
        implementation(compose.runtime)
      }
    }
    val commonTest by getting {
      dependencies { implementation(kotlin("test")) }
    }
  }
}

android {
  namespace = "com.sloopworks.debugdrawer.redux"
  compileSdk = 35
  defaultConfig { minSdk = 34 }
}
