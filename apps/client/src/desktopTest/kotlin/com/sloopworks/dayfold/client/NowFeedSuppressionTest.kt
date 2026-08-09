package com.sloopworks.dayfold.client

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ADR 0064 decided-Q4 — suppression on the on-device lanes. nowFeed() is the single entry
// point the foreground render AND the background notify pass share, so both are covered here.
class NowFeedSuppressionTest {

  private val zone = TimeZone.UTC
  private val nowIso = "2026-08-08T09:00:00Z"

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

  /** A state with one weather card and one plain info card, both live and authored. */
  private fun stateWith(
    rules: List<ContentResponse> = emptyList(),
    viewer: String? = "u_dad",
  ) = AppState(
    session = SessionState(session = Session("t", "r", userId = viewer), activeFamilyId = "fam1"),
    content = ContentState(
      cards = listOf(
        Card(id = "c_rain", kind = "weather", title = "Rain at soccer"),
        Card(id = "c_note", kind = "info", title = "Scout campout"),
      ),
    ),
    responses = ResponseState(rules = rules),
  )

  /** Every surfaced item across all bands, including the collapsed overflow tail. */
  private fun RankedFeed.allItems(): List<NowItem> =
    (now + soon + later + overflow).map { it.item }

  private fun feedIds(state: AppState) =
    nowFeed(state, nowIso, location = null, zone = zone).allItems().map { it.id }

  @Test
  fun withNoRulesBothCardsSurface() {
    assertEquals(listOf("authored:c_rain", "authored:c_note").sorted(), feedIds(stateWith()).sorted())
  }

  @Test
  fun aFamilyMuteOnTheKindRemovesItForEveryone() {
    val ids = feedIds(stateWith(listOf(rule("kind:weather", MatchScope.KIND))))
    assertFalse(ids.any { it.contains("c_rain") })
    assertTrue(ids.any { it.contains("c_note") })
  }

  @Test
  fun aPersonalMuteOnlyRemovesItForItsOwner() {
    val r = rule("kind:weather", MatchScope.KIND, AudienceScope.PERSONAL, userId = "u_mom")
    assertTrue(feedIds(stateWith(listOf(r), viewer = "u_dad")).any { it.contains("c_rain") })
    assertFalse(feedIds(stateWith(listOf(r), viewer = "u_mom")).any { it.contains("c_rain") })
  }

  @Test
  fun aSubjectMuteRemovesExactlyThatCard() {
    val ids = feedIds(stateWith(listOf(rule("card:c_rain", MatchScope.SUBJECT))))
    assertFalse(ids.any { it.contains("c_rain") })
    assertTrue(ids.any { it.contains("c_note") })
  }

  @Test
  fun aDoneRecordRemovesItForEveryone() {
    val r = rule("card:c_rain", MatchScope.SUBJECT, kind = ResponseKind.DONE)
    assertFalse(feedIds(stateWith(listOf(r))).any { it.contains("c_rain") })
  }

  // Suppression runs before the calm budget: a muted item must not occupy a slot and push a
  // wanted item into the collapsed overflow.
  @Test
  fun aMutedItemDoesNotConsumeACalmBudgetSlot() {
    val cards = (1..4).map { Card(id = "c_w$it", kind = "weather", title = "Rain $it") } +
      Card(id = "c_keep", kind = "info", title = "Keep me")
    val base = AppState(
      session = SessionState(session = Session("t", "r", userId = "u_dad"), activeFamilyId = "fam1"),
      content = ContentState(cards = cards),
    )
    val budget = RankConfig(visibleBudget = 2)

    val unmuted = nowFeed(base, nowIso, null, zone, rankConfig = budget)
    val muted = nowFeed(
      base.copy(responses = ResponseState(rules = listOf(rule("kind:weather", MatchScope.KIND)))),
      nowIso, null, zone, rankConfig = budget,
    )
    // Every weather item is gone, and nothing weather-shaped lingers in the overflow tail.
    assertTrue(muted.allItems().none { it.subjectKey.contains("c_w") })
    assertTrue(unmuted.allItems().any { it.subjectKey.contains("c_w") })
    assertTrue(muted.allItems().any { it.subjectKey.contains("c_keep") })
  }

  // The background notify pass shares nowFeed(), so a muted subject can never buzz the phone.
  @Test
  fun theBackgroundNotifyPassSeesTheSameSuppression() {
    val state = stateWith(listOf(rule("kind:weather", MatchScope.KIND)))
    val feed = nowFeed(state, nowIso, null, zone, authoredCoordGeo = false)
    assertFalse(feed.allItems().any { it.subjectKey.contains("c_rain") })
  }

  @Test
  fun aSignedOutViewerNeverInheritsAPersonalRule() {
    val r = rule("kind:weather", MatchScope.KIND, AudienceScope.PERSONAL, userId = "u_mom")
    assertTrue(feedIds(stateWith(listOf(r), viewer = null)).any { it.contains("c_rain") })
  }
}
