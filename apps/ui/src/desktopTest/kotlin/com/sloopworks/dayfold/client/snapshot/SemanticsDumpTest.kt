package com.sloopworks.dayfold.client.snapshot

import org.reduxkotlin.snapshot.SnapshotInput
import org.reduxkotlin.snapshot.demoSnapshots
import kotlin.test.Test
import kotlin.test.assertTrue

// alpha04 gate: the `demo` scene renders the text "snapshot ok"; its semantics dump
// MUST contain that string. If empty -> semantics is not wired in this build -> fall
// back to compose-ui-test onNodeWithText for content asserts (see plan Task 2 note).
class SemanticsDumpTest {
  @Test fun demoSceneExposesRenderedText() {
    val dump = demoSnapshots.render("demo", SnapshotInput.Preset("default")).semantics
    assertTrue(dump.texts.any { it.contains("snapshot ok") },
      "semantics.texts was ${dump.texts} — expected to contain 'snapshot ok'")
  }
}
