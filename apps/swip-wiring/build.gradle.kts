// SWIP bug-reporter wiring: dayfold's slice registry + StateSanitizer + the
// docs/12 §6 MANDATORY product-owned leak test (salted real AppState). Tiny KMP
// module because androidApp has no JVM test source set — desktopTest here is the
// hermetic home for the leak gate, and it runs in CI next to :client/:ui.
// Consumed by androidApp as debugImplementation ONLY — never on the release
// classpath.
plugins {
  kotlin("multiplatform")
  id("com.android.library") // AGP 9 requires it with androidTarget()
}

kotlin {
  jvmToolchain(17)
  androidTarget()
  jvm("desktop")

  sourceSets {
    val commonMain by getting {
      dependencies {
        api(project(":client"))
        api("works.sloop.swip:swip-rk-recorder:0.1.0")
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
      }
    }
    val desktopTest by getting {
      dependencies {
        implementation(kotlin("test"))
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
      }
    }
  }
}

android {
  namespace = "com.sloopworks.dayfold.swip"
  compileSdk = 37
  defaultConfig { minSdk = 33 }
}
