package com.sloopworks.dayfold.cli

import com.sloopworks.dayfold.client.MediaValidation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MediaValidationTest {
  private val HERO = "https://upload.wikimedia.org/wikipedia/commons/0/0c/Logo.png"

  @Test fun acceptsAllowlistedHttps() = assertNull(MediaValidation.imageUrlError(HERO))
  @Test fun acceptsCaseInsensitiveHost() = assertNull(MediaValidation.imageUrlError("https://UPLOAD.WIKIMEDIA.ORG/x.png"))
  @Test fun acceptsExplicit443() = assertNull(MediaValidation.imageUrlError("https://upload.wikimedia.org:443/x.png"))
  @Test fun acceptsTrailingDot() = assertNull(MediaValidation.imageUrlError("https://upload.wikimedia.org./x.jpg"))

  @Test fun rejectsEvasionVectors() {
    val bad = listOf(
      "http://upload.wikimedia.org/x.png",                 // not https
      "data:image/png;base64,iVBORw0KGgo=",                // data:
      "javascript:alert(1)",                               // javascript:
      "https://upload.wikimedia.org@evil.com/x.png",       // userinfo smuggling
      "https://evil.com/upload.wikimedia.org/x.png",       // path-as-host
      "https://upload.wikimedia.org.evil.com/x.png",       // suffix evasion
      "https://notupload.wikimedia.org/x.png",             // prefix evasion
      "https://commons.wikimedia.org/x.png",               // sibling subdomain (exact-host)
      "https://upload.wikimedia.org:8443/x.png",           // alt port
      "https://upload.wikіmedia.org/x.png",           // cyrillic homograph → illegal host
      "https://upload.wikimedia.org/logo.svg",             // SVG
      "https://upload.wikimedia.org/a b.png",              // whitespace
      "https://upload.wikimedia.org/" + "a".repeat(2100),  // over-long
    )
    for (u in bad) assertNotNull(MediaValidation.imageUrlError(u), "should reject: $u")
  }

  @Test fun iconSetAcceptReject() {
    for (n in MediaValidation.CURATED_ICONS) assertNull(MediaValidation.iconError(n))
    assertNotNull(MediaValidation.iconError("medical_services")) // glyph, not curated NAME
    assertNotNull(MediaValidation.iconError("nuke"))
    assertEquals(18, MediaValidation.CURATED_ICONS.size)
  }

  @Test fun accentHexAcceptReject() {
    assertNull(MediaValidation.accentHexError("#1c6e8c"))
    assertNull(MediaValidation.accentHexError("#1C6E8C"))
    for (h in listOf("1c6e8c", "#1c6e8", "#zzzzzz", "red", "#fff")) assertNotNull(MediaValidation.accentHexError(h), h)
  }

  // ---- wired into the CLI validators ----

  @Test fun hubMediaWiredIntoValidateHubTree() {
    val good = validateHubTree("hubs", """{"type":"starting-college","title":"College","media":{"heroUrl":"$HERO","heroFit":"contain","icon":"school","accentColor":"#2C3E73"}}""")
    assertTrue(good.isEmpty(), good.toString())
    val bad = validateHubTree("hubs", """{"type":"vacation","title":"x","media":{"heroUrl":"https://evil.com/x.png","icon":"spaceship"}}""")
    assertTrue(bad.any { it.contains("media.heroUrl") })
    assertTrue(bad.any { it.contains("media.icon") })
  }

  @Test fun blockContactAvatarWiredIntoValidateHubTree() {
    val bad = validateHubTree("blocks", """{"sectionId":"s1","type":"contact","payload":{"name":"x","avatarUrl":"https://evil.com/a.png"}}""")
    assertTrue(bad.any { it.contains("payload.avatarUrl") }, bad.toString())
    val good = validateHubTree("blocks", """{"sectionId":"s1","type":"contact","payload":{"name":"x","avatarUrl":"$HERO","accentColor":"#aabbcc"}}""")
    assertTrue(good.isEmpty(), good.toString())
  }

  @Test fun cardMediaWiredIntoValidateCard() {
    val bad = validateCard(null, """{"id":"01J0000000000000000000000A","kind":"info","title":"x","provenance":{"source":"claude","at":"2026-06-26T00:00:00Z"},"media":{"icon":"nope"}}""")
    assertTrue(bad.any { it.contains("media.icon") }, bad.toString())
  }
}
