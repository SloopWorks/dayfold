// Plugin versions declared once for the whole build (no version catalog yet).
// Subprojects apply these without a version. Matrix (don't drift — see
// processes/agent-dev-loop.md): Kotlin 2.3.20 · Compose-MP 1.11.1 · AGP 9.2.1 ·
// Gradle 9.4.1 · SQLDelight 2.3.2.
plugins {
  kotlin("multiplatform") version "2.3.20" apply false
  kotlin("android") version "2.3.20" apply false
  kotlin("plugin.serialization") version "2.3.20" apply false
  kotlin("plugin.compose") version "2.3.20" apply false
  id("org.jetbrains.compose") version "1.11.1" apply false
  id("com.android.application") version "9.2.1" apply false
  id("com.android.library") version "9.2.1" apply false
  id("app.cash.sqldelight") version "2.3.2" apply false
  id("com.google.gms.google-services") version "4.4.2" apply false  // S2: Firebase (Google sign-in)
}
