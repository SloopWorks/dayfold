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
