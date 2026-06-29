package com.sloopworks.dayfold.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ADR 0038 — `dayfold push` stamps a client-minted ULID `id` (+ `ord`) onto checklist
// items that lack one, and PRESERVES ids that came from `pull` (so re-push is
// idempotent and concurrent toggles keep a stable per-item key). Hub-tree resources
// only (cards carry no checklist payload).
class ChecklistStampTest {
  private val J = Json { ignoreUnknownKeys = true }

  // Deterministic, schema-valid mint (26 digits — digits satisfy the ulid pattern).
  private fun counterMint(): () -> String { var n = 0; return { "%026d".format(n++) } }

  private fun items(json: String): JsonArray =
    J.parseToJsonElement(json).jsonObject["payload"]!!.jsonObject["items"]!!.jsonArray

  @Test fun `block - id-less checklist items get a minted id and sequential ord`() {
    val block = """{"type":"checklist","sectionId":"s1","payload":{"items":[{"text":"Pack jackets","done":false},{"text":"Snacks","done":false}]}}"""
    val out = stampChecklistIds("blocks", block, counterMint())
    val its = items(out)
    assertEquals("00000000000000000000000000", its[0].jsonObject["id"]!!.jsonPrimitive.content)
    assertEquals("00000000000000000000000001", its[1].jsonObject["id"]!!.jsonPrimitive.content)
    assertEquals(0, its[0].jsonObject["ord"]!!.jsonPrimitive.int)
    assertEquals(1, its[1].jsonObject["ord"]!!.jsonPrimitive.int)
    // member-mutable + loop fields untouched
    assertEquals("Pack jackets", its[0].jsonObject["text"]!!.jsonPrimitive.content)
  }

  @Test fun `existing ids and ord are preserved (idempotent re-push)`() {
    val block = """{"type":"checklist","sectionId":"s1","payload":{"items":[{"id":"AAAAAAAAAAAAAAAAAAAAAAAAAA","text":"x","done":true,"ord":5}]}}"""
    val out = stampChecklistIds("blocks", block, counterMint())
    val it0 = items(out)[0].jsonObject
    assertEquals("AAAAAAAAAAAAAAAAAAAAAAAAAA", it0["id"]!!.jsonPrimitive.content)
    assertEquals(5, it0["ord"]!!.jsonPrimitive.int)
    assertEquals(true, it0["done"]!!.jsonPrimitive.content.toBoolean())
  }

  @Test fun `mixed - only the id-less item is minted, present id kept`() {
    val block = """{"type":"checklist","sectionId":"s1","payload":{"items":[{"id":"BBBBBBBBBBBBBBBBBBBBBBBBBB","text":"kept"},{"text":"fresh"}]}}"""
    val out = stampChecklistIds("blocks", block, counterMint())
    val its = items(out)
    assertEquals("BBBBBBBBBBBBBBBBBBBBBBBBBB", its[0].jsonObject["id"]!!.jsonPrimitive.content)
    assertEquals("00000000000000000000000000", its[1].jsonObject["id"]!!.jsonPrimitive.content)
  }

  @Test fun `non-checklist block is untouched`() {
    val block = """{"type":"link","sectionId":"s1","payload":{"url":"https://example.com"}}"""
    val out = stampChecklistIds("blocks", block, counterMint())
    assertEquals(J.parseToJsonElement(block), J.parseToJsonElement(out))
  }

  @Test fun `hub tree - nested checklist items across sections are all stamped`() {
    val hub = """{"type":"party-event","title":"Party","sections":[{"id":"s1","blocks":[{"type":"checklist","payload":{"items":[{"text":"a"}]}},{"type":"text","body_md":"hi"}]},{"id":"s2","blocks":[{"type":"checklist","payload":{"items":[{"text":"b"},{"text":"c"}]}}]}]}"""
    val out = stampChecklistIds("hubs", hub, counterMint())
    val root = J.parseToJsonElement(out).jsonObject
    val s1Blocks = root["sections"]!!.jsonArray[0].jsonObject["blocks"]!!.jsonArray
    val s2Items = root["sections"]!!.jsonArray[1].jsonObject["blocks"]!!.jsonArray[0].jsonObject["payload"]!!.jsonObject["items"]!!.jsonArray
    // s1 first checklist item stamped
    assertEquals("00000000000000000000000000", s1Blocks[0].jsonObject["payload"]!!.jsonObject["items"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
    // the text block is left alone (no payload injected)
    assertNull(s1Blocks[1].jsonObject["payload"])
    // s2 items continue the same mint sequence
    assertEquals("00000000000000000000000001", s2Items[0].jsonObject["id"]!!.jsonPrimitive.content)
    assertEquals("00000000000000000000000002", s2Items[1].jsonObject["id"]!!.jsonPrimitive.content)
  }

  @Test fun `section resource stamps its blocks`() {
    val section = """{"hubId":"h1","blocks":[{"type":"checklist","payload":{"items":[{"text":"a"}]}}]}"""
    val out = stampChecklistIds("sections", section, counterMint())
    val it0 = J.parseToJsonElement(out).jsonObject["blocks"]!!.jsonArray[0].jsonObject["payload"]!!.jsonObject["items"]!!.jsonArray[0].jsonObject
    assertEquals("00000000000000000000000000", it0["id"]!!.jsonPrimitive.content)
  }

  @Test fun `malformed json is returned unchanged`() {
    val bad = "{not json"
    assertEquals(bad, stampChecklistIds("blocks", bad, counterMint()))
  }

  @Test fun `cards resource is never stamped`() {
    val card = """{"kind":"info","title":"x","payload":{"items":[{"text":"a"}]}}"""
    val out = stampChecklistIds("cards", card, counterMint())
    assertEquals(J.parseToJsonElement(card), J.parseToJsonElement(out))
  }
}
