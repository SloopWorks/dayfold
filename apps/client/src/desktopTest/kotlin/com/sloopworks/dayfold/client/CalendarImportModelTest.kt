package com.sloopworks.dayfold.client

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * spec §1.2/§4.1 non-goal 1 proof: "assert the serialized body of every import op against an exact
 * allowed key set" starts with the PROPOSAL TYPE itself — attendees/guests, meeting/conferencing
 * links, organizer identity, calendar account/provider identifiers, reminder config, the platform
 * calendar/event id, recurrence id, and the reconciler's fingerprint must be structurally ABSENT
 * (a compile error to add), not merely unpopulated at runtime. Java reflection over the declared
 * field set proves this directly — the strongest form of "the type cannot express them".
 */
class CalendarImportModelTest {
  private val EXPECTED_PROPOSAL_FIELDS = setOf(
    "proposalId", "title", "start", "end", "timezone", "location", "description", "destination",
  )

  private val BANNED_SUBSTRINGS = listOf(
    "attendee", "guest", "organizer", "reminder", "recurrence", "fingerprint",
    "account", "calendarId", "eventId", "platformEventId", "conferenc",
  )

  /** Serialization generates static companion/cache fields; only instance fields can carry data. */
  private fun instanceFields(type: Class<*>) = type.declaredFields
    .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
    .map { it.name }

  @Test fun `CalendarImportProposal's field set is exactly the normalization boundary`() {
    val fields = instanceFields(CalendarImportProposal::class.java).toSet()
    assertEquals(EXPECTED_PROPOSAL_FIELDS, fields)
  }

  @Test fun `no proposal field name resembles a banned calendar-identifying concept`() {
    val fields = instanceFields(CalendarImportProposal::class.java).map { it.lowercase() }
    for (banned in BANNED_SUBSTRINGS) {
      assertEquals(emptyList<String>(), fields.filter { it.contains(banned.lowercase()) }, "leaked via '$banned'")
    }
  }

  @Test fun `ImportOpIds carries only client-minted target ids, never a calendar-derived value`() {
    val fields = instanceFields(ImportOpIds::class.java).toSet()
    assertEquals(setOf("hubId", "sectionId", "blockIds"), fields)
  }

  @Test fun `StructuredLocation is label+address only — no lat, lng, or map link`() {
    val fields = instanceFields(StructuredLocation::class.java).toSet()
    assertEquals(setOf("label", "address"), fields)
  }
}
