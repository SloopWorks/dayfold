package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ADR 0036 — client-side mirror of the server/CLI validator + the accent-harmonize
// math. Keeping these three in lock-step is the whole point (no parser differential).
class EnrichmentValidationTest {
  private val HERO = "https://upload.wikimedia.org/wikipedia/commons/0/0c/Logo.png"

  @Test fun acceptsAllowlistedAndNormalizedForms() {
    for (u in listOf(HERO, "https://UPLOAD.WIKIMEDIA.ORG/x.png", "https://upload.wikimedia.org:443/x.png", "https://upload.wikimedia.org./x.jpg"))
      assertNull(MediaValidation.imageUrlError(u), u)
    assertNotNull(MediaValidation.safeImageUrl(HERO))
  }

  @Test fun rejectsEvasionVectors() {
    val bad = listOf(
      "http://upload.wikimedia.org/x.png", "data:image/png;base64,AAAA", "javascript:alert(1)",
      "https://upload.wikimedia.org@evil.com/x.png", "https://evil.com/upload.wikimedia.org/x.png",
      "https://upload.wikimedia.org.evil.com/x.png", "https://commons.wikimedia.org/x.png",
      "https://upload.wikimedia.org:8443/x.png", "https://upload.wikіmedia.org/x.png",
      "https://upload.wikimedia.org/logo.svg", "https://upload.wikimedia.org/a b.png",
      // vectors the hand-rolled commonMain parser is uniquely exposed to (vs WHATWG/java.net):
      "https://notupload.wikimedia.org/x.png",             // prefix evasion
      "https://upload.wikimedia.org/LOGO.SVG",             // SVG, uppercase
      "https://upload.wikimedia.org/a\tb.png",             // tab (control) in path
      "https://upload.wikimedia.org/" + "a".repeat(2100),  // over-long
      "blob:https://upload.wikimedia.org/abc",             // scheme test must see "blob:https", not "https"
      "https://[::1]/x.png", "https://[fd00::1]/x.png",    // ip-literal hosts
      "https://upload.wikimedia.org\\@evil.com/x.png",     // backslash smuggling
      "https:///x.png",                                    // empty authority
      "https://up\tload.wikimedia.org/x.png",              // control char inside host
      "https://upload.wikimedia.org%2eevil.com/x.png",     // percent-encoded dot in host
      "",                                                  // empty
    )
    for (u in bad) { assertNotNull(MediaValidation.imageUrlError(u), "reject: $u"); assertNull(MediaValidation.safeImageUrl(u), u) }
    assertNotNull(MediaValidation.imageUrlError(null))     // null → error
    assertNull(MediaValidation.safeImageUrl(null))
  }

  @Test fun curatedIconSetMatchesAcrossLayers() {
    assertEquals(18, MediaValidation.CURATED_ICONS.size)
    // every curated NAME resolves to a glyph; unknowns don't.
    for (n in MediaValidation.CURATED_ICONS) assertNotNull(CuratedIcons.get(n), n)
    assertNull(CuratedIcons.get("medical_services"))
    assertNull(CuratedIcons.get("nuke"))
    assertEquals(MediaValidation.CURATED_ICONS, CuratedIcons.byName.keys)
  }

  @Test fun accentHarmonizeProducesSafeRoles() {
    assertNull(accentRolesFor("not-a-hex", false))
    assertNull(accentRolesFor(null, true))
    // saturated, near-white, near-black all yield non-null roles in light + dark.
    for (hex in listOf("#3B5BDB", "#ECE9E1", "#20212A")) for (dark in listOf(false, true)) {
      val r = accentRolesFor(hex, dark); assertNotNull(r, "$hex dark=$dark")
      // onTile is a high-contrast ink or white — never the raw hex.
      assertTrue(r.onTile == androidx.compose.ui.graphics.Color.White || r.onTile.red < 0.2f)
    }
    // a near-white accent in light mode is clamped DOWN to a visible edge — the raw
    // #ECE9E1 is ~0.91 luminance; the edge is pinned to HSL-L≤0.52 (well below it).
    assertTrue(accentRolesFor("#ECE9E1", false)!!.edge.luminanceApprox() < 0.62f)
    // different brands → different edges.
    assertNotEquals(accentRolesFor("#3B5BDB", false)!!.edge, accentRolesFor("#1F8A6D", false)!!.edge)
  }

  @Test fun isEnrichedGuard() {
    assertTrue(HubMedia(icon = "school").isEnriched())
    assertTrue(HubMedia(accentColor = "#112233").isEnriched())
    assertTrue(!(null as HubMedia?).isEnriched())
    assertTrue(!HubMedia().isEnriched())
  }

  private fun androidx.compose.ui.graphics.Color.luminanceApprox(): Float =
    0.2126f * red + 0.7152f * green + 0.0722f * blue
}
