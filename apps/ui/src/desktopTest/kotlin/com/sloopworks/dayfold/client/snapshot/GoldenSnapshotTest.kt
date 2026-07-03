package com.sloopworks.dayfold.client.snapshot

import org.reduxkotlin.snapshot.assertGolden
import java.io.File
import kotlin.test.Test

// Committed-golden regression gate. Goldens recorded on dev (macOS); CI (ubuntu) verifies
// with a 4% tolerance. The bundled brand fonts are VARIABLE fonts (wght axis) and macOS
// (CoreText) vs linux (FreeType) instantiate the >=600 weights with slightly different
// glyph advances, so bold-dense scenes measure 2.2–2.9% cross-OS (AA-only drift stays
// under 2%). A real layout/content regression lights up far more than 4%.
// Re-record after an INTENTIONAL visual change:
//   cd apps && ./gradlew :client:desktopTest --tests "*GoldenSnapshotTest" -Dsnapshot.record=true
// then EYEBALL the changed PNG before committing.
class GoldenSnapshotTest {
  private fun golden(scene: String, preset: String, theme: String? = null) {
    val name = if (theme != null) "$scene-$preset-$theme" else "$scene-$preset"
    clientSnapshots.assertGolden(
      scene = scene, preset = preset, theme = theme,
      goldenDir = GOLDEN_DIR, name = name, maxDiffPercent = 4.0,
    )
  }

  @Test fun feedBusy() = golden("feed", "busy")
  @Test fun feedTyped() = golden("feed", "typed")
  @Test fun feedEmpty() = golden("feed", "empty")
  @Test fun feedCaughtUp() = golden("feed", "caught-up")
  @Test fun feedEnriched() = golden("feed", "enriched")
  @Test fun hubCanonical() = golden("hub-detail", "canonical")
  @Test fun hubCanonicalDark() = golden("hub-detail", "canonical", theme = "dark")
  @Test fun hubEnriched() = golden("hub-detail", "enriched")
  @Test fun detailInvite() = golden("detail", "invite")
  @Test fun detailContact() = golden("detail", "contact")
  @Test fun detailFile() = golden("detail", "file")
  @Test fun detailEmail() = golden("detail", "email")

  companion object { val GOLDEN_DIR = File("src/desktopTest/resources/snapshots") }
}
