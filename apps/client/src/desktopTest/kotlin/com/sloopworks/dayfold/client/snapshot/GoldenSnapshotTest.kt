package com.sloopworks.dayfold.client.snapshot

import org.reduxkotlin.snapshot.assertGolden
import java.io.File
import kotlin.test.Test

// Committed-golden regression gate. Goldens recorded on dev; CI (ubuntu) verifies with a
// 2% tolerance — brand fonts are bundled, so cross-arch variance is only Skiko AA.
// Re-record after an INTENTIONAL visual change:
//   cd apps && ./gradlew :client:desktopTest --tests "*GoldenSnapshotTest" -Dsnapshot.record=true
// then EYEBALL the changed PNG before committing.
class GoldenSnapshotTest {
  private fun golden(scene: String, preset: String, theme: String? = null) =
    clientSnapshots.assertGolden(
      scene = scene, preset = preset, theme = theme,
      goldenDir = GOLDEN_DIR, maxDiffPercent = 2.0,
    )

  @Test fun feedBusy() = golden("feed", "busy")

  companion object { val GOLDEN_DIR = File("src/desktopTest/resources/snapshots") }
}
