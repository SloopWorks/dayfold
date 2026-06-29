package com.sloopworks.dayfold.cli

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

// ADR 0038 — client-minted ULID for checklist item ids. The minted id MUST satisfy
// the schema's `ulid` pattern (Crockford base32, 26 chars) so it round-trips through
// the same validation the loop-authored ids do.
class UlidTest {
  // The schema's $defs.ulid pattern (specs/domain-model/schemas/content.schema.json).
  private val ULID = Regex("^[0-9A-HJKMNP-TV-Z]{26}$")

  @Test fun `minted ulid matches the schema ulid pattern`() {
    repeat(500) {
      val u = Ulid.next()
      assertTrue(ULID.matches(u), "not a valid ulid: $u")
    }
  }

  @Test fun `encode is 26 chars and deterministic for a fixed time + seed`() {
    val a = Ulid.encode(1_700_000_000_000L, Random(42))
    val b = Ulid.encode(1_700_000_000_000L, Random(42))
    assertEquals(26, a.length)
    assertEquals(a, b)
    assertTrue(ULID.matches(a))
  }

  @Test fun `different randomness yields different ids at the same instant`() {
    val a = Ulid.encode(1_700_000_000_000L, Random(1))
    val b = Ulid.encode(1_700_000_000_000L, Random(2))
    assertNotEquals(a, b)
    // The 10-char time prefix is identical (same instant); only the random tail differs.
    assertEquals(a.substring(0, 10), b.substring(0, 10))
  }

  @Test fun `later timestamps sort lexicographically after earlier ones`() {
    val early = Ulid.encode(1_700_000_000_000L, Random(7))
    val late = Ulid.encode(1_700_000_001_000L, Random(7))
    assertTrue(early < late, "$early should sort before $late")
  }
}
