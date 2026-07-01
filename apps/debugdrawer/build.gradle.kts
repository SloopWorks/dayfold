// SloopWorks Debug Drawer — standalone, reusable, debug-only Compose-MP library.
// ZERO Dayfold deps (no :client, no redux/sqldelight/ktor). Publish-ready coords
// (group com.sloopworks.debugdrawer), extractable to its own repo later.
// Plugin versions come from apps/build.gradle.kts (applied version-less here).
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
  // iOS compile-only at foundation (no device run needed).
  listOf(iosArm64(), iosSimulatorArm64()).forEach {
    it.binaries.framework { baseName = "debugdrawer"; isStatic = true }
  }

  sourceSets {
    val commonMain by getting {
      dependencies {
        implementation(compose.runtime)
        implementation(compose.foundation)
        implementation(compose.material3)
        implementation(compose.ui)
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        // atomicfu's locks (SynchronizedObject) give a multiplatform lock for the
        // thread-safe LogBuffer ring (R4). Runtime-only use; no gradle plugin needed.
        implementation("org.jetbrains.kotlinx:atomicfu:0.27.0")
      }
    }
    val commonTest by getting {
      dependencies {
        implementation(kotlin("test"))
      }
    }
  }
}

android {
  namespace = "com.sloopworks.debugdrawer"
  compileSdk = 35
  defaultConfig { minSdk = 33 }
}
