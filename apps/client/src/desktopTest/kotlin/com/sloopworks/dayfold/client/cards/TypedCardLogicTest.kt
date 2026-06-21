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

// Pure derivation logic (no Compose) — fast, golden-stable (no PNG diffing).
class TypedCardLogicTest {
  private fun card(type: String, payload: Payload, body: String? = null) =
    Card(id = "x", kind = "action", title = "T", provenance = Provenance("email"), type = type, payload = payload, bodyMd = body)

  @Test fun `accent per type matches the mockup roles`() {
    assertEquals(CardAccent.Primary, accentFor("invite"))   // coral
    assertEquals(CardAccent.Tertiary, accentFor("link"))    // violet
    assertEquals(CardAccent.Tertiary, accentFor("email"))   // violet
    assertEquals(CardAccent.Secondary, accentFor("file"))   // teal
    assertEquals(CardAccent.Secondary, accentFor("contact"))
    assertEquals(CardAccent.Secondary, accentFor("geo"))
    assertEquals(CardAccent.Secondary, accentFor("poll"))   // unknown → safe default
  }

  @Test fun `kicker per type`() {
    assertEquals("FILE", kickerFor(card("file", Payload(file = FilePayload()))))
    assertEquals("FORM", kickerFor(card("link", Payload(link = LinkPayload(kind = "form")))))
    assertEquals("LINK", kickerFor(card("link", Payload(link = LinkPayload(kind = "page")))))
    assertEquals("INVITATION · RSVP", kickerFor(card("invite", Payload(invite = InvitePayload()))))
    assertEquals("OUTING", kickerFor(card("geo", Payload(geo = GeoPayload()))))
    assertEquals("POLL", kickerFor(card("poll", Payload())))  // unknown → uppercased type
  }

  @Test fun `primary action per type emits the right CardAction`() {
    assertEquals(
      "Open" to CardAction.OpenUrl("https://drive/x"),
      primaryActionFor(card("file", Payload(file = FilePayload(docRef = "https://drive/x")))),
    )
    assertEquals(
      "Open form" to CardAction.OpenUrl("https://form"),
      primaryActionFor(card("link", Payload(link = LinkPayload(url = "https://form", kind = "form")))),
    )
    // contact primary = OpenDetail; Call/Text live as the inline quick-action row (no dup)
    assertEquals(
      CardAction.OpenDetail("x"),
      primaryActionFor(card("contact", Payload(contact = ContactPayload(phone = "+15551234567")))).second,
    )
    assertEquals(
      "Navigate" to CardAction.Navigate("Riverside Park"),
      primaryActionFor(card("geo", Payload(geo = GeoPayload(address = "Riverside Park")))),
    )
    assertEquals(
      "Reply" to CardAction.Email("mailto:coach@school.edu"),
      primaryActionFor(card("email", Payload(email = EmailPayload(fromAddr = "coach@school.edu")))),
    )
    // invite primary = OpenDetail (RSVP is display-only at M0, no write path)
    assertEquals(CardAction.OpenDetail("x"), primaryActionFor(card("invite", Payload(invite = InvitePayload()))).second)
  }

  @Test fun `missing target falls back to OpenDetail, never a bad scheme`() {
    assertEquals(CardAction.OpenDetail("x"), primaryActionFor(card("file", Payload(file = FilePayload()))).second)
    // non-http docRef (opaque storage ref) must NOT become an OpenUrl
    assertEquals(CardAction.OpenDetail("x"),
      primaryActionFor(card("file", Payload(file = FilePayload(docRef = "ref://opaque")))).second)
    assertEquals(CardAction.OpenDetail("x"), primaryActionFor(card("contact", Payload(contact = ContactPayload()))).second)
  }

  @Test fun `body summary prefers body_md, else derives from payload`() {
    assertEquals("Authored prose", bodySummaryFor(card("file", Payload(file = FilePayload(filename = "a.pdf")), body = "Authored prose")))
    assertEquals("a.pdf · 2 pages", bodySummaryFor(card("file", Payload(file = FilePayload(filename = "a.pdf", pages = 2)))))
    assertTrue(bodySummaryFor(card("link", Payload(link = LinkPayload(domain = "x.org"))))!!.contains("x.org"))
  }
}
