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
        "auth", "account", "smart-briefings", "avatar-picker", "hub-people", "join", "members", "devices", "device-approval", "scan",
        "notif", "privacy", "places", "proximity", "permission", "offline-banner", "kit",
        "timeline-card", "timeline-detail",
        "smart-content", "response-sheet",
        "calendar-settings", "calendar-primer", "calendar-chooser", "calendar-denied",
        "calendar-no-calendars", "calendar-prefill", "calendar-return", "calendar-reset",
        "calendar-now", "calendar-review", "calendar-match", "calendar-differ", "calendar-matched",
        "calendar-import",
      ),
      clientSnapshots.scenes.map { it.name }.toSet(),
    )
  }
  @Test fun everySceneRendersPixels() {
    assertTrue(clientSnapshots.render("feed", SnapshotInput.Preset("busy")).png.size > 100)
    assertTrue(clientSnapshots.render("hub-detail", SnapshotInput.Preset("canonical")).png.size > 100)
    assertTrue(clientSnapshots.render("detail", SnapshotInput.Preset("invite")).png.size > 100)
    assertTrue(clientSnapshots.render("account", SnapshotInput.Preset("smart-briefings")).png.size > 100)
    listOf(
      "entry", "adult", "provider", "sources", "access", "schedule", "privacy", "handoff",
      "waiting", "active", "partial", "draft", "conflict", "offline", "revoke", "revoke-pending", "revoke-failed", "revoked",
    ).forEach { preset ->
      assertTrue(
        clientSnapshots.render("smart-briefings", SnapshotInput.Preset(preset)).png.size > 100,
        "smart-briefings/$preset did not render pixels",
      )
    }
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
