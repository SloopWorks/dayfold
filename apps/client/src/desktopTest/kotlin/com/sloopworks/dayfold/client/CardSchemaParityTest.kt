package com.sloopworks.dayfold.client

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

// Card payload parity: the client model must read EVERY field of the generated
// schema (content.schema.json → BriefingCardPayload), because the CLI validates
// authored cards against that schema — so authors write those exact field names.
// Hub BLOCKS drifted from the schema (OQ-block-payload-schema) and a structured
// block silently stops rendering; cards match today. This locks them so they can't
// drift the same way: each case decodes JSON written with the SCHEMA's field names
// (ignoreUnknownKeys, as the app does), and a client rename would null the field
// and fail here.
class CardSchemaParityTest {
  private val json = Json { ignoreUnknownKeys = true }
  private fun payload(type: String, body: String): Payload =
    json.decodeFromString<Card>("""{"id":"c1","title":"X","type":"$type","payload":{"$type":$body}}""").payload!!

  @Test fun file() {
    val p = payload("file", """{"filename":"f.pdf","mime":"application/pdf","size":12,"pages":3,"source":"drive","owner":"me","modified":"2026-01-01","sharedWith":["a"],"docRef":"ref://f"}""").file
    assertNotNull(p)
    assertEquals("f.pdf", p.filename); assertEquals("application/pdf", p.mime); assertEquals(12L, p.size)
    assertEquals(3L, p.pages); assertEquals("drive", p.source); assertEquals("me", p.owner)
    assertEquals("2026-01-01", p.modified); assertEquals(listOf("a"), p.sharedWith); assertEquals("ref://f", p.docRef)
  }

  @Test fun link() {
    val p = payload("link", """{"url":"https://x","domain":"x.com","title":"T","ogDesc":"d","favicon":"fav","kind":"rsvp","fieldCount":2,"closesAt":"2026-01-01","savedAt":"2026-01-02"}""").link
    assertNotNull(p)
    assertEquals("https://x", p.url); assertEquals("x.com", p.domain); assertEquals("T", p.title)
    assertEquals("d", p.ogDesc); assertEquals("fav", p.favicon); assertEquals("rsvp", p.kind)
    assertEquals(2L, p.fieldCount); assertEquals("2026-01-01", p.closesAt); assertEquals("2026-01-02", p.savedAt)
  }

  @Test fun invite() {
    val p = payload("invite", """{"eventName":"Party","host":"Sam","startAt":"2026-07-15","place":"Park","rsvpBy":"2026-07-10","rsvpState":"yes","guestCount":8,"confirmedCount":5,"notes":"byob"}""").invite
    assertNotNull(p)
    assertEquals("Party", p.eventName); assertEquals("Sam", p.host); assertEquals("2026-07-15", p.startAt)
    assertEquals("Park", p.place); assertEquals("2026-07-10", p.rsvpBy); assertEquals("yes", p.rsvpState)
    assertEquals(8L, p.guestCount); assertEquals(5L, p.confirmedCount); assertEquals("byob", p.notes)
  }

  @Test fun contact() {
    val p = payload("contact", """{"name":"Aid","company":"Butler","role":"FinAid","phone":"888","email":"a@b.edu","address":"1 St","hours":"9-5","linkedEventId":"e1","deliveryWindow":"AM"}""").contact
    assertNotNull(p)
    assertEquals("Aid", p.name); assertEquals("Butler", p.company); assertEquals("FinAid", p.role)
    assertEquals("888", p.phone); assertEquals("a@b.edu", p.email); assertEquals("1 St", p.address)
    assertEquals("9-5", p.hours); assertEquals("e1", p.linkedEventId); assertEquals("AM", p.deliveryWindow)
  }

  @Test fun geo() {
    val p = payload("geo", """{"label":"Field","address":"2 Rd","lat":1.0,"lng":2.0,"etaMin":15,"distance":"3mi","travelMode":"car","parking":"lot","leaveBy":"3:30","linkedEventId":"e1"}""").geo
    assertNotNull(p)
    assertEquals("Field", p.label); assertEquals("2 Rd", p.address); assertEquals(1.0, p.lat); assertEquals(2.0, p.lng)
    assertEquals(15L, p.etaMin); assertEquals("3mi", p.distance); assertEquals("car", p.travelMode)
    assertEquals("lot", p.parking); assertEquals("3:30", p.leaveBy); assertEquals("e1", p.linkedEventId)
  }

  @Test fun email() {
    val p = payload("email", """{"from":"School","fromAddr":"s@x.edu","subject":"RSVP","date":"2026-01-01","threadLen":3,"bodyExcerpt":"please reply","labels":["inbox"]}""").email
    assertNotNull(p)
    assertEquals("School", p.from); assertEquals("s@x.edu", p.fromAddr); assertEquals("RSVP", p.subject)
    assertEquals("2026-01-01", p.date); assertEquals(3L, p.threadLen); assertEquals("please reply", p.bodyExcerpt)
    assertEquals(listOf("inbox"), p.labels)
  }
}
