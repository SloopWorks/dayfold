import org.jetbrains.compose.ExperimentalComposeLibrary

// TASK-KMP: the shared client as a true Compose-Multiplatform module.
// commonMain = ALL shared logic + UI (Model/Reducer/Selectors/CardRender/
// FeedScreen/FeedApp/ContentStore/SyncClient). Platform source sets only hold
// the SQLDelight driver actual + the desktop entrypoint. android/iOS shells wrap
// this core. (iOS target added in a follow-up slice — ktor-common + driver
// expect/actual leave it a small additive step.)
plugins {
  kotlin("multiplatform")
  kotlin("plugin.serialization")
  kotlin("plugin.compose")
  id("org.jetbrains.compose")
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
  listOf(iosArm64(), iosSimulatorArm64()).forEach {
    it.binaries.framework { baseName = "client"; isStatic = true }
  }

  sourceSets {
    val commonMain by getting {
      dependencies {
        // redux-kotlin KMP coordinates (unsuffixed → per-target variant resolved
        // by Gradle). api() for the types the platform shells touch (Store etc.).
        api("org.reduxkotlin:redux-kotlin-concurrent:1.0.0-alpha03")   // -threadsafe is deprecated → concurrent (same contract, lock-free reads)
        implementation("org.reduxkotlin:redux-kotlin-compose:1.0.0-alpha03")
        implementation("org.reduxkotlin:redux-kotlin-granular:1.0.0-alpha03")
        api("org.reduxkotlin:redux-kotlin-devtools-core:1.0.0-alpha03")
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        implementation("app.cash.sqldelight:runtime:2.3.2")
        implementation("app.cash.sqldelight:coroutines-extensions:2.3.2")
        implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
        implementation("io.ktor:ktor-client-core:3.5.0")
        implementation(compose.runtime)
        implementation(compose.foundation)
        implementation(compose.material3)
        implementation(compose.ui)
        // CL-0b: bundled brand fonts (Outfit/Figtree) via Compose Resources (Res.font.*).
        implementation(compose.components.resources)
        // ADR 0036 visual enrichment: curated icon glyphs (name→ImageVector map) +
        // Coil3 async image (KMP) with the ktor3 network fetcher (reuses the
        // project's ktor 3.5.0). Per-platform engines: cio desktop / okhttp android /
        // darwin iOS (already declared per source set).
        implementation(compose.materialIconsExtended)
        implementation("io.coil-kt.coil3:coil-compose:3.2.0")
        implementation("io.coil-kt.coil3:coil-network-ktor3:3.2.0")
        // CL-7: BackHandler / PredictiveBackHandler (separate artifact, not pulled
        // by compose.ui transitively) — enables hardware/gesture back → NavBack.
        implementation("org.jetbrains.compose.ui:ui-backhandler:1.11.1")
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
        // S6-D Tier 2 — in-app QR scanner (CameraX preview/analysis + ML Kit
        // on-device barcode; runtime CAMERA permission via activity-compose).
        implementation("androidx.camera:camera-core:1.4.1")
        implementation("androidx.camera:camera-camera2:1.4.1")
        implementation("androidx.camera:camera-lifecycle:1.4.1")
        implementation("androidx.camera:camera-view:1.4.1")
        implementation("com.google.mlkit:barcode-scanning:17.3.0")
        implementation("androidx.activity:activity-compose:1.9.3")
        implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
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
        implementation(compose.desktop.currentOs)
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
        @OptIn(ExperimentalComposeLibrary::class)
        implementation(compose.uiTest)
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

compose.desktop { application { mainClass = "com.sloopworks.dayfold.client.MainKt" } }

// Generated Res accessor for the bundled fonts (src/commonMain/composeResources/font/).
compose.resources {
  publicResClass = false
  packageOfResClass = "com.sloopworks.dayfold.client.generated"
  generateResClass = auto
}

// desktopTest reuses the JVM JUnit-platform setup the old jvm module had.
tasks.named<Test>("desktopTest") { useJUnitPlatform() }
