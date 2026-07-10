package com.sloopworks.dayfold.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ADR 0052 — the membership cache backing the DB-first cold-start route gate.
class ContentStoreMembershipTest {
  private fun cs() = ContentStore.create(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))

  @Test fun `replaceMemberships round-trips through cachedMemberships`() {
    val cs = cs()
    cs.replaceMemberships(listOf(FamilyMembership("f1", "Fam One", "owner", "active")))
    val got = cs.cachedMemberships()
    assertEquals(listOf("f1"), got.map { it.familyId })
    assertEquals("Fam One", got.first().name)
    assertEquals("owner", got.first().role)
    assertEquals("active", got.first().status)
  }

  @Test fun `replaceMemberships replaces the full set rather than appending`() {
    val cs = cs()
    cs.replaceMemberships(listOf(FamilyMembership("f1")))
    cs.replaceMemberships(listOf(FamilyMembership("f2")))
    assertEquals(listOf("f2"), cs.cachedMemberships().map { it.familyId })
  }

  @Test fun `wipe clears the membership cache (tenancy-revocation boundary)`() {
    val cs = cs()
    cs.replaceMemberships(listOf(FamilyMembership("f1")))
    cs.wipe()
    assertTrue(cs.cachedMemberships().isEmpty())
  }

  @Test fun `reconcileSchemaVersion preserves the membership cache (content-heal must not wipe it)`() {
    val cs = cs()
    cs.replaceMemberships(listOf(FamilyMembership("f1")))
    cs.reconcileSchemaVersion(current = 999L)   // force the content-schema heal wipe
    assertEquals(listOf("f1"), cs.cachedMemberships().map { it.familyId })
  }
}
