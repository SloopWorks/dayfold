package com.sloopworks.dayfold.client.snapshot

import org.reduxkotlin.snapshot.SnapshotInput
import org.reduxkotlin.snapshot.demoSnapshots
import kotlin.test.Test
import kotlin.test.assertTrue

// Proves the redux-kotlin-snapshot dep resolves and its headless ImageComposeScene
// backend renders on this JVM (the lib ships a self-contained `demo` scene).
class SnapshotLibSmokeTest {
  @Test fun demoSceneRendersPngBytes() {
    val result = demoSnapshots.render("demo", SnapshotInput.Preset("default"))
    assertTrue(result.png.size > 100, "expected PNG bytes, got ${result.png.size}")
  }
}
