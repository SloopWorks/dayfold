package com.sloopworks.dayfold.client.cards

import com.sloopworks.dayfold.client.Card
import com.sloopworks.dayfold.client.ContactPayload
import com.sloopworks.dayfold.client.GeoPayload
import com.sloopworks.dayfold.client.InvitePayload
import com.sloopworks.dayfold.client.LinkPayload
import com.sloopworks.dayfold.client.Payload
import com.sloopworks.dayfold.client.Provenance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedTransitionKeysTest {
  private fun card(type: String, payload: Payload) =
    Card(id = "x", kind = "action", title = "T", provenance = Provenance("email"), type = type, payload = payload)

  @Test fun `link with url shares all four`() {
    val k = sharedTransitionKeys(card("link", Payload(link = LinkPayload(url = "https://x.com", kind = "page"))))
    assertTrue(k.title); assertTrue(k.kicker); assertTrue(k.tile); assertTrue(k.button)
  }

  @Test fun `link without url does not share button`() {
    val k = sharedTransitionKeys(card("link", Payload(link = LinkPayload(kind = "page"))))
    assertTrue(k.tile)     // "L" == "L"
    assertFalse(k.button)  // card falls back to Details/OpenDetail; detail drops Open
  }

  @Test fun `contact shares title and kicker only`() {
    val k = sharedTransitionKeys(card("contact", Payload(contact = ContactPayload(name = "Chris Jackson", phone = "+15551234567", address = "1 Main St"))))
    assertTrue(k.title); assertTrue(k.kicker)
    assertFalse(k.tile)    // card "CJ" != detail "C"
    assertFalse(k.button)  // card OpenDetail != detail Navigate(Directions)
  }

  @Test fun `invite shares tile but not button`() {
    val k = sharedTransitionKeys(card("invite", Payload(invite = InvitePayload())))
    assertTrue(k.tile)     // "!" == "!"
    assertFalse(k.button)  // OpenDetail != Directions/Share
  }

  @Test fun `geo with address shares button`() {
    val k = sharedTransitionKeys(card("geo", Payload(geo = GeoPayload(address = "Riverside Park"))))
    assertTrue(k.tile); assertTrue(k.button)  // Navigate == Navigate
  }

  @Test fun `monograms differ only for contact`() {
    assertEquals("L", cardMonogram(card("link", Payload(link = LinkPayload()))))
    assertEquals("L", detailMonogram(card("link", Payload(link = LinkPayload()))))
    assertEquals("CJ", cardMonogram(card("contact", Payload(contact = ContactPayload(name = "Chris Jackson")))))
    assertEquals("C", detailMonogram(card("contact", Payload(contact = ContactPayload(name = "Chris Jackson")))))
  }
}
