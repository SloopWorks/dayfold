// No-op twin of :debugdrawer — IDENTICAL public consumer API, inert bodies. Wired
// as releaseImplementation so Android release links this and strips the real drawer
// (R12). Dependency-light: only compose.runtime (@Composable passthrough) + compose.ui
// (Color in the mirrored theme types).
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
    it.binaries.framework { baseName = "debugdrawer"; isStatic = true }
  }
  sourceSets {
    val commonMain by getting {
      dependencies {
        implementation(compose.runtime)
        implementation(compose.ui)
      }
    }
    val commonTest by getting {
      dependencies { implementation(kotlin("test")) }
    }
  }
}

android {
  namespace = "com.sloopworks.debugdrawer.noop"
  compileSdk = 35
  defaultConfig { minSdk = 34 }
}
