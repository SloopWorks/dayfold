package com.sloopworks.dayfold.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class TemporalTest {
  private val id = "01K45ABCDEF0123456789GHJKM"

  private fun resource(occurrence: String, trigger: String = ""): String =
    """{"type":"markdown","body_md":"Schedule","provenance":{"source":"cli","at":"2026-09-01T12:00:00Z"},
      "temporal":{"occurrences":[$occurrence]}${if (trigger.isEmpty()) "" else ",\"triggers\":[$trigger]"}}"""

  @Test fun `shared fixture corpus stays aligned with CLI temporal validation`() {
    val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
      .map { File(it, "specs/domain-model/examples/temporal-v1") }
      .first { it.isDirectory }
    root.listFiles { file -> file.extension == "json" }!!.sortedBy { it.name }.forEach { file ->
      val problems = temporalProblems(Json.parseToJsonElement(file.readText()).jsonObject)
      if (file.name.startsWith("valid-")) assertEquals(emptyList(), problems, file.name)
      else assertTrue(problems.isNotEmpty(), file.name)
    }
  }

  @Test fun `validates all-day and timed occurrence families`() {
    val allDay = """{"id":"$id","role":"event","label":"Camp","start":"2026-08-28","end":"2026-08-30","status":"confirmed"}"""
    assertEquals(emptyList(), temporalValidationErrors(resource(allDay)))
    val timed = """{"id":"$id","role":"window","label":"Show","start":"2026-08-28T21:00:00-07:00","end":"2026-08-28T23:00:00-07:00","zone":"America/Los_Angeles","status":"confirmed"}"""
    assertEquals(emptyList(), temporalValidationErrors(resource(timed)))
  }

  @Test fun `timezone validation catches a DST gap and accepts either matching fold offset`() {
    val gap = """{"id":"$id","role":"event","label":"Gap","start":"2026-03-08T02:30:00-08:00","zone":"America/Los_Angeles","status":"confirmed"}"""
    assertTrue(temporalValidationErrors(resource(gap)).any { it.contains("zone-offset-mismatch") })
    for (offset in listOf("-07:00", "-08:00")) {
      val fold = """{"id":"$id","role":"event","label":"Fold","start":"2026-11-01T01:30:00$offset","zone":"America/Los_Angeles","status":"confirmed"}"""
      assertEquals(emptyList(), temporalValidationErrors(resource(fold)))
    }
  }

  @Test fun `fact refs are same-item eligible and offset bounded`() {
    val timed = """{"id":"$id","role":"event","label":"Show","start":"2026-08-28T21:00:00-07:00","zone":"America/Los_Angeles","status":"confirmed"}"""
    val good = """{"when":{"fact_ref":"temporal:$id","alert_offset":"-PT30M"}}"""
    assertEquals(emptyList(), temporalValidationErrors(resource(timed, good)))
    val bad = """{"when":{"fact_ref":"temporal:$id","alert_offset":"P31D"}}"""
    assertTrue(temporalValidationErrors(resource(timed, bad)).any { it.contains("invalid-alert-offset") })
  }

  @Test fun `mention detector avoids years versions phones and names`() {
    for (text in listOf("Class of 2026", "v2.6.1", "+1 415 555 0123", "May family archive"))
      assertFalse(containsTemporalMention(text), text)
    for (text in listOf(
      "Fri Aug 28", "August 28th", "meet at 6:30 pm", "starts 9pm",
      "tomorrow", "next Friday", "August 2026",
    ))
      assertTrue(containsTemporalMention(text), text)
  }
}
