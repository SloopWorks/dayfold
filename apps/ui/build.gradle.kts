import org.jetbrains.compose.ExperimentalComposeLibrary

plugins {
  kotlin("multiplatform")
  kotlin("plugin.compose")
  id("org.jetbrains.compose")
  id("com.android.library")
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
        implementation(compose.runtime)
        implementation(compose.foundation)
        implementation(compose.material3)
        implementation(compose.ui)
        implementation(compose.components.resources)
        implementation(compose.materialIconsExtended)
        implementation("org.jetbrains.compose.ui:ui-backhandler:1.11.1")
        implementation("io.coil-kt.coil3:coil-compose:3.2.0")
        implementation("io.coil-kt.coil3:coil-network-ktor3:3.2.0")
        implementation("org.reduxkotlin:redux-kotlin-compose:1.0.0-alpha03")
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
