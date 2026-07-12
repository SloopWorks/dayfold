// Optional, debug-only adapter: renders the SWIP Phase-1 capture engine
// (works.sloop.swip:swip-debug RingDebugSink.entries) as a SloopWorks debug-drawer
// panel. Apps add this debugImplementation only; it is never in release. Depends on
// :debugdrawer (the plugin API) — keeping the core drawer swip-agnostic.
plugins {
  kotlin("multiplatform")
  kotlin("plugin.compose")
  id("org.jetbrains.compose")
  id("com.android.library")
}

group = "com.sloopworks.dayfold.swip"
version = "0.1.0-SNAPSHOT"

kotlin {
  jvmToolchain(17)
  androidTarget()
  jvm("desktop")
  listOf(iosArm64(), iosSimulatorArm64()).forEach {
    it.binaries.framework { baseName = "debugdrawerswip"; isStatic = true }
  }

  sourceSets {
    val commonMain by getting {
      dependencies {
        implementation(project(":debugdrawer"))
        implementation("works.sloop.swip:swip-debug:0.1.0")
        implementation(compose.runtime)
        implementation(compose.foundation)
        implementation(compose.material3)
      }
    }
    val commonTest by getting {
      dependencies { implementation(kotlin("test")) }
    }
  }
}

android {
  namespace = "com.sloopworks.dayfold.swip.inspector"
  compileSdk = 35
  defaultConfig { minSdk = 33 }
}
