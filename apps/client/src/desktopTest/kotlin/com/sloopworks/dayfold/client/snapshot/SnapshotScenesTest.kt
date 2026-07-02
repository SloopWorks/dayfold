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
        "auth", "account", "join", "members", "devices", "device-approval", "scan",
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
}
