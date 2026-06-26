package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals

// hubTypeLabel — the hub-list chip used to show the raw catalog slug
// ("starting-college") for a live authored hub; it should title-case to a label.
class HubTypeLabelTest {
  @Test fun `catalog slugs title-case into human labels`() {
    assertEquals("Starting College", hubTypeLabel("starting-college"))
    assertEquals("Party Event", hubTypeLabel("party-event"))
    assertEquals("New Baby", hubTypeLabel("new-baby"))
    assertEquals("School Year", hubTypeLabel("school-year"))
    assertEquals("Vacation", hubTypeLabel("vacation"))
    assertEquals("Medical", hubTypeLabel("medical"))
  }

  @Test fun `null or blank is empty (no chip text)`() {
    assertEquals("", hubTypeLabel(null))
    assertEquals("", hubTypeLabel(""))
    assertEquals("", hubTypeLabel("   "))
  }

  @Test fun `an unknown future slug still title-cases (no map to update)`() {
    assertEquals("Home Reno", hubTypeLabel("home-reno"))
  }
}
