package com.sloopworks.dayfold.cli

import kotlin.test.Test
import kotlin.test.assertTrue

// `dayfold --version` reads the version Gradle embedded at build time
// (generateVersionResource → /dayfold-version.txt). The generated resource is on the
// test classpath, so this proves the wiring end to end.
class VersionTest {
  @Test fun `cliVersion reads the embedded build version, not "unknown"`() {
    val v = cliVersion()
    assertTrue(v.isNotBlank() && v != "unknown", "version resource not wired; got: \"$v\"")
    // semver or the local dev marker (0.0.0-dev) — always starts MAJOR.MINOR.PATCH
    assertTrue(Regex("""^\d+\.\d+\.\d+""").containsMatchIn(v), "unexpected version: \"$v\"")
  }
}
