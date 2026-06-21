// TASK-KMP: thin Android application. All shared logic/UI comes from :client
// (the KMP module) — no more srcDir borrow, no Main.kt/ContentStore excludes, no
// duplicated SQLDelight setup. This module only owns the Android entrypoint
// (MainActivity), the manifest, and the in-app redux devtools drawer.
plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.compose")
}

android {
  namespace = "com.sloopworks.dayfold.android"
  compileSdk = 35

  defaultConfig {
    applicationId = "com.sloopworks.dayfold"
    minSdk = 34 // matches :client
    targetSdk = 35
    versionCode = 1
    versionName = "0.0.0-M0"
    // dev config baked at build time (emulator → host = 10.0.2.2)
    buildConfigField("String", "DAYFOLD_API", "\"${System.getenv("DAYFOLD_API") ?: "http://10.0.2.2:8799"}\"")
    buildConfigField("String", "FAMILY_ID", "\"${System.getenv("FAMILY_ID") ?: ""}\"")
    buildConfigField("String", "HOUSEHOLD_SECRET", "\"${System.getenv("HOUSEHOLD_SECRET") ?: ""}\"")
    // S5 dev sign-in (local only; the server hard-refuses dev-token in prod/preview).
    buildConfigField("String", "DEV_AUTH_SECRET", "\"${System.getenv("DEV_AUTH_SECRET") ?: ""}\"")
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  buildFeatures { compose = true; buildConfig = true }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlin { jvmToolchain(17) }
}

// keep the documented APK name stable across the KMP restructure
base.archivesName.set("dayfold-android")

dependencies {
  implementation(project(":client"))
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

  // In-app redux devtools (debug build = real drawer; release = no-op facade).
  debugImplementation("org.reduxkotlin:redux-kotlin-devtools-inapp:1.0.0-alpha01")
  releaseImplementation("org.reduxkotlin:redux-kotlin-devtools-inapp-noop:1.0.0-alpha01")

  val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
  implementation(composeBom)
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.ui:ui-tooling-preview")
  implementation("androidx.activity:activity-compose:1.9.3")
  implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
  implementation("androidx.core:core-ktx:1.15.0")

  // Instrumented e2e (Slice B): drive the real route gate + screens on the
  // emulator. Hermetic — callbacks dispatch actions (no network); AuthEngine
  // logic is covered by desktop unit tests.
  androidTestImplementation(composeBom)
  androidTestImplementation("androidx.compose.ui:ui-test-junit4")
  androidTestImplementation("androidx.test.ext:junit:1.2.1")
  androidTestImplementation("androidx.test:runner:1.6.2")
  // espresso 3.6.1 has the API 34/35 InputManager.getInstance() fix (older
  // espresso throws NoSuchMethodException via the compose idling bridge).
  // ⚠ API 37 (Android 16 preview) removed getInstance() entirely → espresso
  // breaks there AND Compose's test rule hard-needs espresso, so the instrumented
  // AuthFlowE2ETest can't run on an API-37 emulator. Run it on a standard-API
  // (≤36) emulator — e.g. the AOSP ATD AVD used here:
  //   sdkmanager "system-images;android-35;aosp_atd;arm64-v8a"
  //   avdmanager create avd -n fad_atd35 -k "system-images;android-35;aosp_atd;arm64-v8a" -d pixel
  //   emulator -avd fad_atd35 -no-window -no-audio -no-snapshot -gpu swiftshader_indirect &
  //   ANDROID_SERIAL=emulator-5558 ./gradlew :androidApp:connectedDebugAndroidTest
  // ✅ Verified PASS on fad_atd35 (API 35). The desktop AuthFlowUiTest covers the
  // same flow headlessly (JVM, no espresso) for the default test loop.
  androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
  debugImplementation("androidx.compose.ui:ui-test-manifest")
}
