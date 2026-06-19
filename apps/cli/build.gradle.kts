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

application { mainClass.set("com.familyai.cli.MainKt") }

tasks.test { useJUnitPlatform() }
