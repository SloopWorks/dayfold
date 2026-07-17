import org.jetbrains.compose.ExperimentalComposeLibrary

plugins {
  kotlin("multiplatform")
  kotlin("plugin.compose")
  id("org.jetbrains.compose")
  id("com.android.library")
}

composeCompiler {
  stabilityConfigurationFiles = listOf(layout.projectDirectory.file("compose-stability.conf"))
}

kotlin {
  jvmToolchain(17)
  androidTarget()
  jvm("desktop")
  listOf(iosArm64(), iosSimulatorArm64()).forEach {
    it.binaries.framework { baseName = "client"; isStatic = true }
  }

  sourceSets {
    val commonMain by getting {
      dependencies {
        api(project(":client"))
        // Moved Compose screens reference these directly; :client declares them as
        // implementation (not api) so they aren't transitive → declare here (P2.2a).
        implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        implementation("io.ktor:ktor-client-core:3.5.0")
        implementation(compose.runtime)
        implementation(compose.foundation)
        implementation(compose.material3)
        implementation(compose.ui)
        implementation(compose.components.resources)
        implementation(compose.materialIconsExtended)
        implementation("org.jetbrains.compose.ui:ui-backhandler:1.11.1")
        implementation("io.coil-kt.coil3:coil-compose:3.2.0")
        implementation("io.coil-kt.coil3:coil-network-ktor3:3.2.0")
        // SelectorStore is part of FeedApp's public host boundary, so consumers need this type.
        api("org.reduxkotlin:redux-kotlin-compose:1.0.0-alpha05")
        implementation("org.reduxkotlin:redux-kotlin-granular:1.0.0-alpha05")
        // Cross-platform QR rendering (owner invite share). KMP Compose painter —
        // publishes android/jvm/iosArm64/iosSimulatorArm64 klibs (zxing is JVM-only →
        // unusable on iOS). Display-only; encodes the invite URL.
        implementation("io.github.alexzhirkevich:qrose:1.1.2")
      }
    }
    val androidMain by getting {
      dependencies {
        implementation("androidx.camera:camera-core:1.4.1")
        implementation("androidx.camera:camera-camera2:1.4.1")
        implementation("androidx.camera:camera-lifecycle:1.4.1")
        implementation("androidx.camera:camera-view:1.4.1")
        implementation("com.google.mlkit:barcode-scanning:17.3.0")
        implementation("androidx.activity:activity-compose:1.9.3")
        implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
      }
    }
    val desktopMain by getting {
      dependencies {
        implementation(compose.desktop.currentOs)
      }
    }
    val desktopTest by getting {
      dependencies {
        implementation(kotlin("test"))
        @OptIn(ExperimentalComposeLibrary::class)
        implementation(compose.uiTest)
        // CL-SNAP: headless f(state)->UI render + golden diff + text semantics.
        // Test-scope ONLY (must not ship). JVM-only artifact (no target suffix).
        // Relocated from :client → :ui (renders Compose, which lives in :ui).
        implementation("org.reduxkotlin:redux-kotlin-snapshot:1.0.0-alpha05")
      }
    }
    val iosArm64Test by getting {
      dependencies {
        implementation(kotlin("test"))
      }
    }
    val iosSimulatorArm64Test by getting {
      dependencies {
        implementation(kotlin("test"))
      }
    }
  }
}

android {
  namespace = "com.sloopworks.dayfold.client.ui"
  compileSdk = 37
  defaultConfig { minSdk = 33 }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

compose.desktop { application { mainClass = "com.sloopworks.dayfold.client.MainKt" } }

// Generated Res accessor for the bundled fonts (src/commonMain/composeResources/font/) — relocated from :client in P2.2a.
compose.resources {
  publicResClass = false
  packageOfResClass = "com.sloopworks.dayfold.client.generated"
  generateResClass = auto
}

// desktopTest reuses the JVM JUnit-platform setup the old jvm module had.
// Forward snapshot.record ONLY when it is explicitly set, so the forked test JVM never
// sees the property in verify mode (Gradle doesn't auto-forward -D flags to forked JVMs).
tasks.named<Test>("desktopTest") {
  useJUnitPlatform()
  System.getProperty("snapshot.record")?.let { systemProperty("snapshot.record", it) }
}

// CL-SNAP: run the snapshot scene registry's CLI against the desktopTest classpath.
// Relocated from :client → :ui (renders Compose, which lives in :ui).
// Usage: ./gradlew :ui:snapshotUi -PsnapshotArgs="--scene feed --preset busy --out /tmp/x.png"
// NOTE: args are OPTIONS ONLY (no leading "snapshot"); split on spaces (no spaces in paths).
tasks.register<JavaExec>("snapshotUi") {
  group = "verification"
  description = "Render/verify client snapshot scenes headlessly (agent loop)."
  val testComp = kotlin.targets.getByName("desktop").compilations.getByName("test")
  dependsOn(testComp.compileTaskProvider)
  mainClass.set("com.sloopworks.dayfold.client.snapshot.SnapshotScenesKt")
  classpath = files(testComp.output.allOutputs, testComp.runtimeDependencyFiles)
  val raw = (project.findProperty("snapshotArgs") as String?).orEmpty()
  argumentProviders.add { raw.split(" ").filter { it.isNotBlank() } }
}
