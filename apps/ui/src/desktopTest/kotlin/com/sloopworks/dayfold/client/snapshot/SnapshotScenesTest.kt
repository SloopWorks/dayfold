package com.sloopworks.dayfold.client.snapshot

import org.reduxkotlin.snapshot.SnapshotInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SnapshotScenesTest {
  @Test fun registeredScenes() {
    assertEquals(
      setOf(
        "feed", "hub-detail", "hub-list", "detail",
        "auth", "account", "avatar-picker", "hub-people", "join", "members", "devices", "device-approval", "scan",
        "notif", "privacy", "places", "proximity", "permission", "offline-banner", "kit",
        "timeline-card", "timeline-detail",
      ),
      clientSnapshots.scenes.map { it.name }.toSet(),
    )
  }
  @Test fun everySceneRendersPixels() {
    assertTrue(clientSnapshots.render("feed", SnapshotInput.Preset("busy")).png.size > 100)
    assertTrue(clientSnapshots.render("hub-detail", SnapshotInput.Preset("canonical")).png.size > 100)
    assertTrue(clientSnapshots.render("detail", SnapshotInput.Preset("invite")).png.size > 100)
  }

  // The committed batch manifest (client/snapshot-shots.json — feeds the `--dashboard`
  // report and the CI failure artifact) must cover exactly the committed goldens.
  @Test fun batchManifestMatchesGoldens() {
    val manifestIds = Regex("\"id\": \"([^\"]+)\"")
      .findAll(java.io.File("snapshot-shots.json").readText())
      .map { it.groupValues[1] }.toSet()
    val goldenIds = java.io.File("src/desktopTest/resources/snapshots/macos")
      .listFiles { f -> f.extension == "png" }!!.map { it.nameWithoutExtension }.toSet()
    assertEquals(goldenIds, manifestIds)
  }
}
