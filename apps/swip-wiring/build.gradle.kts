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
        // 0.1.1 exposes okio + coroutines-core as `api` (their types leak through
        // ReportLane / ReduxTimelineRecorder ctors), so consumers no longer redeclare them.
        api("works.sloop.swip:swip-rk-recorder:0.1.1")
        api("works.sloop.swip:swip-core:0.1.3")
        api("works.sloop.swip:schema-dayfold:0.1.2")
        api("works.sloop.swip:swip-lifecycle:0.1.0")
        api("works.sloop.swip:swip-rk:0.1.0")
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
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
