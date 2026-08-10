package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * CAL-9 — the pure pieces of IosCalendarPort (ADR 0063 §1/§3). Kept in commonMain/desktopTest
 * because iosMain can't be exercised by a JVM test; IosCalendarPort itself stays thin/untested.
 */
class CalendarPortMappingTest {

  // ── calendarPermissionFromEventKitStatus — mirrors EKAuthorizationStatus raw values ──

  @Test fun `fullAccess (3) maps to Granted`() {
    assertEquals(CalendarPermission.Granted, calendarPermissionFromEventKitStatus(3L))
  }

  @Test fun `restricted (1) maps to Restricted`() {
    assertEquals(CalendarPermission.Restricted, calendarPermissionFromEventKitStatus(1L))
  }

  @Test fun `denied (2) maps to Denied`() {
    assertEquals(CalendarPermission.Denied, calendarPermissionFromEventKitStatus(2L))
  }

  @Test fun `notDetermined (0) maps to NotRequested`() {
    assertEquals(CalendarPermission.NotRequested, calendarPermissionFromEventKitStatus(0L))
  }

  @Test fun `writeOnly (4) maps to NotRequested — the add-only rung doesn't unlock observeEvents`() {
    assertEquals(CalendarPermission.NotRequested, calendarPermissionFromEventKitStatus(4L))
  }

  // ── maskCalendarAccountLabel — ADR 0063 §3, the raw account title never crosses the seam ──

  @Test fun `an email-shaped account title keeps the first character and the domain`() {
    assertEquals("p•••@gmail.com", maskCalendarAccountLabel("patrick@gmail.com"))
  }

  @Test fun `a non-email account title keeps only the first character`() {
    assertEquals("F•••", maskCalendarAccountLabel("Family iCloud"))
  }

  @Test fun `blank input masks to blank`() {
    assertEquals("", maskCalendarAccountLabel("   "))
  }

  @Test fun `a leading at-sign is not treated as an email split point`() {
    assertEquals("@•••", maskCalendarAccountLabel("@weird"))
  }

  // ── rgbComponentsToHex ──

  @Test fun `full red renders as FF0000`() {
    assertEquals("#FF0000", rgbComponentsToHex(1.0, 0.0, 0.0))
  }

  @Test fun `out-of-range components are clamped rather than wrapping`() {
    assertEquals("#FF00FF", rgbComponentsToHex(1.5, -0.5, 2.0))
  }

  @Test fun `mid-gray rounds down consistently`() {
    assertEquals("#7F7F7F", rgbComponentsToHex(0.5, 0.5, 0.5))
  }

  // ── calendarLocationFromTitle — nil vs empty-string EKStructuredLocation.title ──

  @Test fun `a null title has no location`() {
    assertNull(calendarLocationFromTitle(null))
  }

  @Test fun `an empty or blank title has no location`() {
    assertNull(calendarLocationFromTitle(""))
    assertNull(calendarLocationFromTitle("   "))
  }

  @Test fun `a real title becomes a label-only CandidateLocation`() {
    assertEquals(CandidateLocation(label = "Cascade Community Theater"), calendarLocationFromTitle("Cascade Community Theater"))
  }

  @Test fun `surrounding whitespace is trimmed`() {
    assertEquals(CandidateLocation(label = "The Theater"), calendarLocationFromTitle("  The Theater  "))
  }
}
