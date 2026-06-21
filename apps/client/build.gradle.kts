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
        api("org.reduxkotlin:redux-kotlin-threadsafe:1.0.0-alpha01")
        implementation("org.reduxkotlin:redux-kotlin-compose:1.0.0-alpha01")
        implementation("org.reduxkotlin:redux-kotlin-granular:1.0.0-alpha01")
        api("org.reduxkotlin:redux-kotlin-devtools-core:1.0.0-alpha01")
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        implementation("app.cash.sqldelight:runtime:2.3.2")
        implementation("app.cash.sqldelight:coroutines-extensions:2.3.2")
        implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
        implementation("io.ktor:ktor-client-core:3.1.1")
        implementation(compose.runtime)
        implementation(compose.foundation)
        implementation(compose.material3)
        implementation(compose.ui)
        // CL-7: BackHandler / PredictiveBackHandler (separate artifact, not pulled
        // by compose.ui transitively) — enables hardware/gesture back → NavBack.
        implementation("org.jetbrains.compose.ui:ui-backhandler:1.9.3")
      }
    }
    val androidMain by getting {
      dependencies {
        implementation("app.cash.sqldelight:android-driver:2.3.2")
        implementation("io.ktor:ktor-client-okhttp:3.1.1")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
      }
    }
    val desktopMain by getting {
      dependencies {
        implementation(compose.desktop.currentOs)
        implementation("app.cash.sqldelight:sqlite-driver:2.3.2")
        implementation("io.ktor:ktor-client-cio:3.1.1")
      }
    }
    iosMain.dependencies {
      implementation("app.cash.sqldelight:native-driver:2.3.2")
      implementation("io.ktor:ktor-client-darwin:3.1.1")
    }
    val desktopTest by getting {
      dependencies {
        implementation(kotlin("test"))
        @OptIn(ExperimentalComposeLibrary::class)
        implementation(compose.uiTest)
        implementation("app.cash.sqldelight:sqlite-driver:2.3.2")
        implementation("io.ktor:ktor-client-mock:3.1.1")
        implementation("app.cash.turbine:turbine:1.2.0")
      }
    }
  }
}

android {
  namespace = "com.sloopworks.dayfold.client"
  compileSdk = 35
  defaultConfig { minSdk = 34 }
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
    }
  }
}

compose.desktop { application { mainClass = "com.sloopworks.dayfold.client.MainKt" } }

// desktopTest reuses the JVM JUnit-platform setup the old jvm module had.
tasks.named<Test>("desktopTest") { useJUnitPlatform() }
