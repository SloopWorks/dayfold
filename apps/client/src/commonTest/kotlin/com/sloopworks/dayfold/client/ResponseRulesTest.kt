package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ADR 0064 — the on-device half of suppression. These expectations mirror
// apps/api/test/responses-match.test.ts: the two predicates must agree, because a member who
// mutes something expects it gone from BOTH lanes and a divergence shows as a card that only
// half disappears.
class ResponseRulesTest {

  private fun rule(
    subjectRef: String,
    matchScope: MatchScope,
    audience: AudienceScope = AudienceScope.FAMILY,
    userId: String? = null,
    kind: ResponseKind = ResponseKind.MUTE,
  ) = ContentResponse(
    id = "r", kind = kind, subjectRef = subjectRef, matchScope = matchScope,
    audienceScope = audience, userId = userId, createdBy = "u_mom", label = "l",
  )

  private fun item(id: String, subjectKey: String, reasonKind: String, source: String? = null) =
    NowItem(
      id = id, origin = Origin.DERIVED, reasonKind = reasonKind, title = "t", why = "w",
      subjectKey = subjectKey, target = null, authoredSource = source,
    )

  @Test
  fun subjectScopeMatchesExactly() {
    val r = rule("hub:h/block:b", MatchScope.SUBJECT)
    assertTrue(ResponseRules.matches(r, "hub:h/block:b", "countdown", null))
    assertFalse(ResponseRules.matches(r, "hub:h/block:c", "countdown", null))
  }

  @Test
  fun kindScopeMatchesTheReasonKind() {
    val r = rule("kind:weather", MatchScope.KIND)
    assertTrue(ResponseRules.matches(r, "card:c1", "weather", null))
    assertFalse(ResponseRules.matches(r, "card:c1", "countdown", null))
  }

  @Test
  fun sourceScopeMatchesTheAuthoredSource() {
    val r = rule("source:morning-briefing", MatchScope.SOURCE)
    assertTrue(ResponseRules.matches(r, "card:c1", "email", "morning-briefing"))
    assertFalse(ResponseRules.matches(r, "card:c1", "email", "other"))
    assertFalse(ResponseRules.matches(r, "card:c1", "email", null))
  }

  @Test
  fun familyMuteSuppressesForEveryone() {
    val items = listOf(item("i1", "card:c1", "weather"), item("i2", "card:c2", "countdown"))
    val out = ResponseRules.suppress(items, listOf(rule("kind:weather", MatchScope.KIND)), "u_dad")
    assertEquals(listOf("i2"), out.map { it.id })
  }

  @Test
  fun personalMuteSuppressesOnlyForItsOwner() {
    val r = rule("kind:weather", MatchScope.KIND, AudienceScope.PERSONAL, userId = "u_mom")
    val items = listOf(item("i1", "card:c1", "weather"))
    assertEquals(emptyList(), ResponseRules.suppress(items, listOf(r), "u_mom").map { it.id })
    assertEquals(listOf("i1"), ResponseRules.suppress(items, listOf(r), "u_dad").map { it.id })
  }

  // A signed-out / unknown viewer must not inherit somebody's personal rule.
  @Test
  fun aNullViewerNeverMatchesAPersonalRule() {
    val r = rule("kind:weather", MatchScope.KIND, AudienceScope.PERSONAL, userId = "u_mom")
    assertEquals(listOf("i1"), ResponseRules.suppress(listOf(item("i1", "card:c1", "weather")), listOf(r), null).map { it.id })
  }

  @Test
  fun doneSuppressesForEveryone() {
    val r = rule("card:c1", MatchScope.SUBJECT, kind = ResponseKind.DONE)
    assertEquals(
      emptyList(),
      ResponseRules.suppress(listOf(item("i1", "card:c1", "checklist")), listOf(r), "u_dad").map { it.id },
    )
  }

  // A hub-level key is a prefix of every block under it, so a prefix match would silently
  // mute the whole hub. The ranker rejected prefix containment for dedup for this reason.
  @Test
  fun subjectMatchIsNotAPrefixMatch() {
    val r = rule("hub:h1", MatchScope.SUBJECT)
    val items = listOf(item("i1", "hub:h1/block:b", "countdown"), item("i2", "hub:h10", "countdown"))
    assertEquals(listOf("i1", "i2"), ResponseRules.suppress(items, listOf(r), "u").map { it.id })
  }

  @Test
  fun anEmptyRuleListChangesNothing() {
    val items = listOf(item("i1", "card:c1", "weather"))
    assertEquals(items, ResponseRules.suppress(items, emptyList(), "u"))
  }

  @Test
  fun suppressPreservesOrder() {
    val items = listOf(
      item("i1", "card:c1", "countdown"),
      item("i2", "card:c2", "weather"),
      item("i3", "card:c3", "checklist"),
    )
    val out = ResponseRules.suppress(items, listOf(rule("kind:weather", MatchScope.KIND)), "u")
    assertEquals(listOf("i1", "i3"), out.map { it.id })
  }

  @Test
  fun isSuppressedAgreesWithSuppress() {
    val r = rule("kind:weather", MatchScope.KIND)
    assertTrue(ResponseRules.isSuppressed("card:c1", "weather", null, listOf(r), "u"))
    assertFalse(ResponseRules.isSuppressed("card:c1", "countdown", null, listOf(r), "u"))
  }
}
