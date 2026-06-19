plugins {
  kotlin("jvm") version "2.2.20"
  application
}

repositories { mavenCentral() }

// JDK-only (java.net.http for the API client) — keeps the build small/fast.
dependencies {
  testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(17) }

application { mainClass.set("com.familyai.cli.MainKt") }

tasks.test { useJUnitPlatform() }
