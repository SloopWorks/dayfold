package com.sloopworks.dayfold.client.cards

import com.sloopworks.dayfold.client.Card
import com.sloopworks.dayfold.client.ContactPayload
import com.sloopworks.dayfold.client.EmailPayload
import com.sloopworks.dayfold.client.FilePayload
import com.sloopworks.dayfold.client.GeoPayload
import com.sloopworks.dayfold.client.InvitePayload
import com.sloopworks.dayfold.client.LinkPayload
import com.sloopworks.dayfold.client.Payload
import com.sloopworks.dayfold.client.Provenance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DetailMetaTest {
  private fun card(type: String, payload: Payload) =
    Card(id = "x", kind = "action", title = "T", provenance = Provenance("email"), type = type, payload = payload)

  @Test fun `meta rows derive from the payload, only present fields`() {
    val rows = detailMeta(card("file", Payload(file = FilePayload(filename = "p.pdf", pages = 2, size = 240000))))
    assertEquals("p.pdf", rows.first { it.label == "File" }.value)
    assertTrue(rows.any { it.label == "Size" && it.value.contains("pages") })
    // absent fields produce no row
    assertTrue(detailMeta(card("file", Payload(file = FilePayload(filename = "a")))).none { it.label == "Owner" })
  }

  @Test fun `meta covers all 6 types without NPE on sparse payloads`() {
    listOf(
      card("file", Payload(file = FilePayload())),
      card("link", Payload(link = LinkPayload())),
      card("invite", Payload(invite = InvitePayload())),
      card("contact", Payload(contact = ContactPayload())),
      card("geo", Payload(geo = GeoPayload())),
      card("email", Payload(email = EmailPayload())),
    ).forEach { detailMeta(it); detailActions(it) } // must not throw
  }

  @Test fun `detailActions are SAFE handoffs only - never a backend mutation`() {
    val all = listOf(
      card("file", Payload(file = FilePayload(docRef = "https://d/x"))),
      card("link", Payload(link = LinkPayload(url = "https://f", kind = "form"))),
      card("invite", Payload(invite = InvitePayload(place = "Home"))),
      card("contact", Payload(contact = ContactPayload(address = "14 Mill St", phone = "+15550142"))),
      card("geo", Payload(geo = GeoPayload(address = "200 Riverside Dr"))),
      card("email", Payload(email = EmailPayload(fromAddr = "a@x.com"))),
    ).flatMap { detailActions(it) }
    // every action maps to an OS-handoff / nav CardAction — no mutating variant exists in the union,
    // so this asserts the set is non-empty and uses only the safe constructors.
    assertTrue(all.isNotEmpty())
    all.forEach { a ->
      val ok = a.action is CardAction.OpenUrl || a.action is CardAction.Navigate ||
        a.action is CardAction.Call || a.action is CardAction.Message || a.action is CardAction.Email ||
        a.action is CardAction.Copy || a.action is CardAction.Share
      assertTrue(ok, "unexpected action ${a.action}")
    }
  }

  @Test fun `file action falls back away from Open when docRef is not http`() {
    val actions = detailActions(card("file", Payload(file = FilePayload(docRef = "ref://opaque"))))
    assertTrue(actions.none { it.label == "Open" }) // non-http docRef → no Open handoff
  }
}
