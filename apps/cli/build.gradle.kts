plugins {
  kotlin("jvm") version "2.3.20"
  kotlin("plugin.serialization") version "2.3.20"
  application
}

repositories { mavenCentral() }

dependencies {
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
  testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(17) }

// CL-3: consume the generated schema types (com.sloopworks.dayfold.schema.*) — the single
// source of truth — for local typed-card validation. No hand-dup, no drift.
sourceSets["main"].kotlin.srcDir("../../packages/schema/kotlin-gen")

application { mainClass.set("com.sloopworks.dayfold.cli.MainKt") }

tasks.test { useJUnitPlatform() }
