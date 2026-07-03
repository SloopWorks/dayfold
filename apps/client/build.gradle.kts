// TASK-KMP: the shared client as a true Compose-Multiplatform module.
// commonMain = ALL shared logic + UI (Model/Reducer/Selectors/CardRender/
// FeedScreen/FeedApp/ContentStore/SyncClient). Platform source sets only hold
// the SQLDelight driver actual + the desktop entrypoint. android/iOS shells wrap
// this core. (iOS target added in a follow-up slice — ktor-common + driver
// expect/actual leave it a small additive step.)
plugins {
  kotlin("multiplatform")
  kotlin("plugin.serialization")
  id("com.android.library")
  id("app.cash.sqldelight")
}

kotlin {
  jvmToolchain(17)
  androidTarget()
  jvm("desktop")
  // iosArm64 = device, iosSimulatorArm64 = Apple-Silicon sim. iosX64 (intel sim)
  // dropped: redux-kotlin-granular alpha01 has no iosX64 publication, and intel
  // Macs are EOL. (Operator owns reduxkotlin — add iosX64 granular to restore it.)
  listOf(iosArm64(), iosSimulatorArm64())

  sourceSets {
    val commonMain by getting {
      dependencies {
        // redux-kotlin KMP coordinates (unsuffixed → per-target variant resolved
        // by Gradle). api() for the types the platform shells touch (Store etc.).
        api("org.reduxkotlin:redux-kotlin-concurrent:1.0.0-alpha03")   // -threadsafe is deprecated → concurrent (same contract, lock-free reads)
        implementation("org.reduxkotlin:redux-kotlin-granular:1.0.0-alpha03")
        api("org.reduxkotlin:redux-kotlin-devtools-core:1.0.0-alpha03")
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        implementation("app.cash.sqldelight:runtime:2.3.2")
        implementation("app.cash.sqldelight:coroutines-extensions:2.3.2")
        implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
        implementation("io.ktor:ktor-client-core:3.5.0")
      }
      // Shared link rules (scheme allowlist + vetting + linkify) — stdlib-only source
      // srcDir'd into commonMain AND the CLI so author-side and render-side never drift.
      kotlin.srcDir("../../packages/linkrules")
    }
    val androidMain by getting {
      dependencies {
        implementation("app.cash.sqldelight:android-driver:2.3.2")
        implementation("io.ktor:ktor-client-okhttp:3.5.0")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
        // ADR 0044 Phase B — LOCAL notifications (NotificationCompat: BigText, group/summary,
        // deep-link action, on-device subtext). androidx.core, no FCM/APNs (dumb-server invariant).
        implementation("androidx.core:core-ktx:1.13.1")
        // ADR 0044 Phase B — background geofencing (GeofencingClient). On-device proximity only; the
        // live position never leaves the device — only saved-place coords (already family content) are
        // handed to the OS. No Maps SDK, no network — just the Location API.
        implementation("com.google.android.gms:play-services-location:21.3.0")
      }
    }
    val desktopMain by getting {
      dependencies {
        implementation("app.cash.sqldelight:sqlite-driver:2.3.2")
        implementation("io.ktor:ktor-client-cio:3.5.0")
        // Dev-only fake backend (debug UI testing). Desktop has no release variant,
        // so the MockEngine dep is acceptable here; on Android it's debug-only.
        implementation("io.ktor:ktor-client-mock:3.5.0")
      }
    }
    iosMain.dependencies {
      implementation("app.cash.sqldelight:native-driver:2.3.2")
      implementation("io.ktor:ktor-client-darwin:3.5.0")
    }
    val desktopTest by getting {
      dependencies {
        implementation(kotlin("test"))
        implementation("app.cash.sqldelight:sqlite-driver:2.3.2")
        implementation("io.ktor:ktor-client-mock:3.5.0")
        implementation("app.cash.turbine:turbine:1.2.1")
      }
    }
  }
}

android {
  namespace = "com.sloopworks.dayfold.client"
  compileSdk = 37
  defaultConfig { minSdk = 33 }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

sqldelight {
  databases {
    create("ContentDb") {
      packageName.set("com.sloopworks.dayfold.client.db")
      dialect("app.cash.sqldelight:sqlite-3-38-dialect:2.3.2") // UPSERT
      // Schema version is derived from migrations: 1.sqm → version 2.
      // verifyMigrations checks 1.sqm matches the v1→v2 schema diff.
      verifyMigrations.set(true)
    }
  }
}

// desktopTest reuses the JVM JUnit-platform setup the old jvm module had.
tasks.named<Test>("desktopTest") { useJUnitPlatform() }
