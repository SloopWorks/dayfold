package com.sloopworks.dayfold.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ADR 0064 — the authoring path's pre-flight. The write boundary enforces suppression anyway;
// filtering here is what turns a rejection into a reportable outcome ("skipped N muted
// subjects") instead of N failed writes.
class ResponsesTest {

  private fun op(id: String, subjectRef: String, kind: String? = null, source: String? = null) =
    CliChangesetOp(id = id, subjectRef = subjectRef, kind = kind, source = source)

  private fun rule(
    subjectRef: String,
    matchScope: CliMatchScope,
    kind: CliResponseKind = CliResponseKind.MUTE,
    id: String = "r1",
  ) = CliResponseRule(id = id, kind = kind, subjectRef = subjectRef, matchScope = matchScope)

  @Test
  fun mutedOpsAreSkippedNotFailed() {
    val ops = listOf(
      op("c1", "card:c1", kind = "weather"),
      op("c2", "card:c2", kind = "action"),
    )
    val r = filterMutedOps(ops, listOf(rule("kind:weather", CliMatchScope.KIND)))
    assertEquals(listOf("c2"), r.kept.map { it.id })
    assertEquals(listOf("c1"), r.skipped.map { it.id })
  }

  @Test
  fun theSkipReasonAndRuleAreReportable() {
    val r = filterMutedOps(
      listOf(op("c1", "card:c1", kind = "weather")),
      listOf(rule("kind:weather", CliMatchScope.KIND, id = "r_weather")),
    )
    assertEquals("muted", r.skipped.single().reason)
    assertEquals("r_weather", r.skipped.single().ruleId)
  }

  // A done subject is resolved; re-minting it is precisely the re-extraction Done exists to
  // stop, so it reports as "done" rather than "muted".
  @Test
  fun aDoneSubjectIsSkippedWithItsOwnReason() {
    val r = filterMutedOps(
      listOf(op("c1", "card:c1", kind = "action")),
      listOf(rule("card:c1", CliMatchScope.SUBJECT, kind = CliResponseKind.DONE)),
    )
    assertEquals(1, r.skipped.size)
    assertEquals("done", r.skipped.single().reason)
  }

  @Test
  fun sourceScopeMatchesTheOpsProvenance() {
    val ops = listOf(
      op("c1", "card:c1", source = "morning-briefing"),
      op("c2", "card:c2", source = "other"),
    )
    val r = filterMutedOps(ops, listOf(rule("source:morning-briefing", CliMatchScope.SOURCE)))
    assertEquals(listOf("c2"), r.kept.map { it.id })
  }

  @Test
  fun subjectScopeIsNotAPrefixMatch() {
    val ops = listOf(op("b1", "hub:h1/block:b1"), op("h1", "hub:h1"))
    val r = filterMutedOps(ops, listOf(rule("hub:h1", CliMatchScope.SUBJECT)))
    assertEquals(listOf("b1"), r.kept.map { it.id })
    assertEquals(listOf("h1"), r.skipped.map { it.id })
  }

  @Test
  fun aNullKindOrSourceNeverMatchesAClassRule() {
    val ops = listOf(op("c1", "card:c1"))
    assertEquals(1, filterMutedOps(ops, listOf(rule("kind:weather", CliMatchScope.KIND))).kept.size)
    assertEquals(1, filterMutedOps(ops, listOf(rule("source:mb", CliMatchScope.SOURCE))).kept.size)
  }

  @Test
  fun noRulesMeansNoFiltering() {
    val ops = listOf(op("c1", "card:c1", kind = "weather"))
    assertEquals(ops, filterMutedOps(ops, emptyList()).kept)
    assertTrue(filterMutedOps(ops, emptyList()).skipped.isEmpty())
  }

  @Test
  fun theReceiptLineReadsAsAnExpectedOutcome() {
    assertEquals("no muted subjects", filterMutedOps(emptyList(), emptyList()).render())
    assertEquals(
      "skipped 1 muted subject",
      filterMutedOps(listOf(op("c1", "card:c1", kind = "weather")), listOf(rule("kind:weather", CliMatchScope.KIND))).render(),
    )
    assertEquals(
      "skipped 2 muted subjects",
      filterMutedOps(
        listOf(op("c1", "card:c1", kind = "weather"), op("c2", "card:c2", kind = "weather")),
        listOf(rule("kind:weather", CliMatchScope.KIND)),
      ).render(),
    )
  }
}

// ── the READ side: what an authoring agent is told before it composes ─────────────────────
class ResponsesBriefTest {

  private val json = """
    {"responses":[
      {"id":"r1","kind":"mute","subject_ref":"kind:weather","match_scope":"kind",
       "audience_scope":"family","user_id":null,"created_by":"u1",
       "label":"Weather cards","sublabel":"From Morning briefing","note":null,"version":1},
      {"id":"r2","kind":"mute","subject_ref":"source:morning-briefing","match_scope":"source",
       "audience_scope":"family","user_id":null,"created_by":"u1",
       "label":"Morning briefing","sublabel":null,"note":null,"version":1},
      {"id":"d1","kind":"done","subject_ref":"card:c_task","match_scope":"subject",
       "audience_scope":"family","user_id":null,"created_by":"u2",
       "label":"Verify emergency contact","sublabel":null,"note":"Confirmed","version":1}
    ],"personal_rules_not_visible":2}
  """.trimIndent()

  @Test
  fun parsesEveryRuleWithItsLabel() {
    val rules = parseResponses(json)
    assertEquals(listOf("r1", "r2", "d1"), rules.map { it.id })
    assertEquals("Weather cards", rules[0].label)
    assertEquals("From Morning briefing", rules[0].sublabel)
    assertEquals(CliMatchScope.KIND, rules[0].matchScope)
    assertEquals(CliMatchScope.SOURCE, rules[1].matchScope)
    assertEquals(CliResponseKind.DONE, rules[2].kind)
  }

  @Test
  fun aNullSublabelParsesAsNullNotTheStringNull() {
    assertEquals(null, parseResponses(json)[1].sublabel)
  }

  // The brief is what a model reads. It has to say what NOT to make, in words — a subject_ref
  // alone does not tell it that "rain at soccer" is a weather card.
  @Test
  fun theBriefNamesWhatNotToAuthor() {
    val brief = renderResponsesBrief(parseResponses(json), hiddenPersonal = 2)
    assertTrue(brief.contains("DO NOT AUTHOR"))
    assertTrue(brief.contains("Weather cards — any card of this kind"))
    assertTrue(brief.contains("context: From Morning briefing"))
    assertTrue(brief.contains("ALREADY DONE"))
    assertTrue(brief.contains("Verify emergency contact"))
    assertTrue(brief.contains("Write nothing similar"))
  }

  // Other members' personal rules are counted, never named — the author learns suppression
  // exists without learning who muted what.
  @Test
  fun theBriefCountsHiddenPersonalRulesWithoutNamingThem() {
    val brief = renderResponsesBrief(parseResponses(json), hiddenPersonal = 2)
    assertTrue(brief.contains("2 personal rules"))
    assertTrue(!brief.contains("u1") || !brief.contains("belonging to u"))
  }

  @Test
  fun anEmptyRuleSetSaysSoPlainly() {
    assertEquals(
      "No response rules — nothing is muted or marked done.",
      renderResponsesBrief(emptyList(), hiddenPersonal = 0),
    )
  }

  @Test
  fun malformedJsonYieldsNoRulesRatherThanThrowing() {
    assertTrue(parseResponses("not json").isEmpty())
    assertTrue(parseResponses("""{"responses":[]}""").isEmpty())
  }

  @Test
  fun theCardKindAndSourceAreReadableForThePreflight() {
    val card = """{"kind":"weather","title":"Rain at soccer","source":"morning-briefing"}"""
    assertEquals("weather", jsonStringField(card, "kind"))
    assertEquals("morning-briefing", jsonStringField(card, "source"))
    assertEquals(null, jsonStringField(card, "absent"))
  }
}
