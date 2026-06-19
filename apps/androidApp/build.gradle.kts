import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  id("com.android.application") version "8.7.2"
  id("org.jetbrains.kotlin.android") version "2.2.20"
  id("org.jetbrains.kotlin.plugin.compose") version "2.2.20"
  id("org.jetbrains.kotlin.plugin.serialization") version "2.2.20"
}

// Keep the whole Kotlin stdlib family pinned to the module's compiler version —
// a transitive kotlin-stdlib:2.3.20 (metadata 2.3) is unreadable by 2.2.20.
configurations.all {
  resolutionStrategy.eachDependency {
    if (requested.group == "org.jetbrains.kotlin" && requested.name.startsWith("kotlin-stdlib")) {
      useVersion("2.2.20")
    }
  }
}

android {
  namespace = "com.familyai.client.android"
  compileSdk = 35

  defaultConfig {
    applicationId = "com.familyai.client"
    minSdk = 34 // java.net.http.HttpClient available (reuse the shared SyncClient)
    targetSdk = 35
    versionCode = 1
    versionName = "0.0.0-M0"
    // dev config baked at build time (emulator → host = 10.0.2.2)
    buildConfigField("String", "FAMILYAI_API", "\"${System.getenv("FAMILYAI_API") ?: "http://10.0.2.2:8799"}\"")
    buildConfigField("String", "FAMILY_ID", "\"${System.getenv("FAMILY_ID") ?: ""}\"")
    buildConfigField("String", "HOUSEHOLD_SECRET", "\"${System.getenv("HOUSEHOLD_SECRET") ?: ""}\"")
  }

  buildFeatures { compose = true; buildConfig = true }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlin { jvmToolchain(17) }
}

// Reuse the shared client logic from apps/client (single source) — exclude the
// desktop-only Main.kt (uses compose.ui.window, not on Android).
android.sourceSets["main"].kotlin.srcDir("../client/src/main/kotlin")
tasks.withType<KotlinCompile>().configureEach { exclude("**/Main.kt") }

dependencies {
  implementation("org.reduxkotlin:redux-kotlin-threadsafe-jvm:0.6.2")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
  val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
  implementation(composeBom)
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.ui:ui-tooling-preview")
  implementation("androidx.activity:activity-compose:1.9.3")
  implementation("androidx.core:core-ktx:1.15.0")
}
