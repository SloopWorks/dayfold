plugins {
  kotlin("jvm") version "2.3.20"
  kotlin("plugin.serialization") version "2.3.20"
  application
}

repositories { mavenCentral() }

dependencies {
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
  implementation("com.google.zxing:core:3.5.3") // [S3] QR encode for `dayfold login`
  testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(17) }

// CL-3: consume the generated schema types (com.sloopworks.dayfold.schema.*) — the single
// source of truth — for local typed-card validation. No hand-dup, no drift.
sourceSets["main"].kotlin.srcDir("../../packages/schema/kotlin-gen")

// Distribution: the `application` plugin's installDist/distTar/distZip ship a
// runnable tree (bin/<name> launcher + lib/*.jar). applicationName = "dayfold" so
// the launcher is `bin/dayfold` (not the project name `cli`) — the Homebrew formula
// links that into the prefix. Version comes from the release tag (-PcliVersion=…),
// defaulting to a dev marker for local builds. See the homebrew distribution spike.
version = (findProperty("cliVersion") as String?) ?: "0.0.0-dev"
application {
  mainClass.set("com.sloopworks.dayfold.cli.MainKt")
  applicationName = "dayfold"
}

// Embed the build version as a resource so `dayfold --version` reports it at runtime
// (the same version the release tag sets). Wired into main resources → jar + test cp.
val generateVersionResource by tasks.registering {
  val outDir = layout.buildDirectory.dir("generated/version")
  val v = version.toString()
  inputs.property("version", v)
  outputs.dir(outDir)
  doLast {
    outDir.get().file("dayfold-version.txt").asFile.apply { parentFile.mkdirs(); writeText(v) }
  }
}
sourceSets["main"].resources.srcDir(generateVersionResource)

tasks.test { useJUnitPlatform() }
