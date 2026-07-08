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

  @Test fun `folded hero-only fields appear as labeled DETAILS rows (dedup)`() {
    fun rows(type: String, payload: Payload) = detailMeta(card(type, payload)).associate { it.label to it.value }
    assertEquals("The Garcias", rows("invite", Payload(invite = InvitePayload(host = "The Garcias")))["Host"])
    assertEquals("Firestone Poulsbo", rows("geo", Payload(geo = GeoPayload(label = "Firestone Poulsbo")))["Place"])
    assertEquals("Please reply by Thu", rows("email", Payload(email = EmailPayload(bodyExcerpt = "Please reply by Thu")))["Preview"])
    val link = rows("link", Payload(link = LinkPayload(title = "Fall Soccer", ogDesc = "Register your player")))
    assertEquals("Fall Soccer", link["Title"]); assertEquals("Register your player", link["About"])
    assertEquals("application/pdf", rows("file", Payload(file = FilePayload(mime = "application/pdf")))["Type"])
    val contact = rows("contact", Payload(contact = ContactPayload(company = "Jake's Rentals", role = "Party equipment")))
    assertEquals("Jake's Rentals", contact["Company"]); assertEquals("Party equipment", contact["Role"])
    // omitted when the field is null
    assertTrue(detailMeta(card("invite", Payload(invite = InvitePayload(place = "Home")))).none { it.label == "Host" })
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
