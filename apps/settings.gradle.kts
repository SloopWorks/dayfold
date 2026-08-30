// TASK-KMP: single Gradle build for the client/ui multiplatform modules and the thin
// Android application. The reusable debug drawer is a pinned composite build from its
// standalone repository (see ../third_party/debugdrawer).
// (api = TS, cli = its own Kotlin build — both are sibling dirs, intentionally NOT
// included here.)
pluginManagement {
  // Shipyard Deploy Gradle plugin (dev-build distribution). Composite build so the
  // plugin needs no Maven publication; override with -PshipyardDeployPluginDir.
  val shipyardPluginDir = file(
    (extra.properties["shipyardDeployPluginDir"] as String?)
      ?: "${System.getProperty("user.home")}/workspace/shipyard-deploy/gradle-plugin",
  )
  if (shipyardPluginDir.resolve("settings.gradle.kts").isFile) includeBuild(shipyardPluginDir)
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
  }
}

val hasSloopworksPackagesCredential =
  !System.getenv("SLOOPWORKS_PACKAGES_TOKEN").isNullOrBlank() ||
    !providers.gradleProperty("gpr.token").orNull.isNullOrBlank()

dependencyResolutionManagement {
  repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    // SWIP and SloopWorks design tokens (private GitHub Packages).
    // Local dev: gpr.user/gpr.token in ~/.gradle/gradle.properties (read:packages PAT).
    // CI: SLOOPWORKS_PACKAGES_TOKEN secret.
    listOf(
      "https://maven.pkg.github.com/SloopWorks/swip",
      "https://maven.pkg.github.com/SloopWorks/sloopworks-ui",
    ).forEach { pkgUrl ->
      maven {
        url = uri(pkgUrl)
        credentials {
          username = System.getenv("GITHUB_ACTOR") ?: providers.gradleProperty("gpr.user").orNull ?: ""
          password = System.getenv("SLOOPWORKS_PACKAGES_TOKEN") ?: providers.gradleProperty("gpr.token").orNull ?: ""
        }
      }
    }
  }
}

rootProject.name = "dayfold-apps"
include(":client", ":ui", ":androidApp", ":swip-wiring")

val debugDrawerBuild = file("../third_party/debugdrawer")
check(debugDrawerBuild.resolve("settings.gradle.kts").isFile) {
  "Shared DebugDrawer is not initialized. Run: git submodule update --init third_party/debugdrawer"
}
includeBuild(debugDrawerBuild) {
  dependencySubstitution {
    substitute(module("com.sloopworks.debugdrawer:debugdrawer")).using(project(":debugdrawer"))
    substitute(module("com.sloopworks.debugdrawer:debugdrawer-noop")).using(project(":debugdrawer-noop"))
    substitute(module("com.sloopworks.debugdrawer:debugdrawer-redux")).using(project(":debugdrawer-redux"))
    if (hasSloopworksPackagesCredential) {
      substitute(module("com.sloopworks.debugdrawer:debugdrawer-swip")).using(project(":debugdrawer-swip"))
    }
  }
}
