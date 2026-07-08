package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MemberNamesTest {
  private val roster = listOf(
    FamilyMember(uid = "u1", displayName = "Patrick Jackson"),
    FamilyMember(uid = "u2", displayName = "Lillian"),
    FamilyMember(uid = "u3", displayName = null),
  )

  @Test fun `firstNameOf takes the first token`() {
    assertEquals("Patrick", firstNameOf("Patrick Jackson"))
    assertEquals("Lillian", firstNameOf("Lillian"))
    assertEquals("Pat", firstNameOf("  Pat  Q  "))
  }

  @Test fun `self resolves to You`() {
    assertEquals("You", displayNameFor("u1", roster, selfId = "u1"))
  }

  @Test fun `a known other member resolves to their first name`() {
    assertEquals("Patrick", displayNameFor("u1", roster, selfId = "u2"))
    assertEquals("Lillian", displayNameFor("u2", roster, selfId = "u1"))
  }

  @Test fun `unknown, null, or nameless member is null (caller falls back)`() {
    assertNull(displayNameFor("u9", roster, selfId = "u1"))   // departed / not synced
    assertNull(displayNameFor(null, roster, selfId = "u1"))
    assertNull(displayNameFor("u3", roster, selfId = "u1"))   // member exists but no display_name
  }
}
