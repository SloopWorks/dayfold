package com.sloopworks.dayfold.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ADR 0064 — the device half of the Tier-1 contract: rules land from /sync, optimistic writes
// are pending until the echo, undo drops the queued op, and resync wipes replace acknowledged
// rules without separating an offline optimistic rule from its preserved outbox operation.
class ResponseStoreTest {

  private fun store() = ContentStore.create(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))

  private fun wire(id: String, label: String = "Weather cards", version: Long = 1L) =
    ContentResponseWire(
      id = id, kind = "mute", subjectRef = "kind:weather", matchScope = "kind",
      audienceScope = "family", userId = null, createdBy = "u_mom", label = label,
      createdAt = "2026-08-19T14:30:00Z",
      version = version,
    )

  private fun local(id: String, pending: Boolean = true) = ContentResponse(
    id = id, kind = ResponseKind.MUTE, subjectRef = "kind:weather",
    matchScope = MatchScope.KIND, audienceScope = AudienceScope.PERSONAL,
    userId = "u_dad", createdBy = "u_dad", label = "Weather cards", pending = pending,
  )

  private fun ContentStore.applyResponses(
    responses: List<ContentResponseWire> = emptyList(),
    tombstones: List<Tombstone> = emptyList(),
  ) = applyDelta(
    changedCards = emptyList(), changedHubs = emptyList(),
    tombstones = tombstones, nextCursor = "c", nowIso = "2026-08-08T09:00:00Z",
    changedResponses = responses.map { it.toDomain() },
  )

  @Test
  fun applyDeltaUpsertsAResponseRow() {
    val s = store()
    s.applyResponses(listOf(wire("r1")))
    val rows = s.allResponses()
    assertEquals(listOf("r1"), rows.map { it.id })
    assertEquals(ResponseKind.MUTE, rows.single().kind)
    assertEquals(MatchScope.KIND, rows.single().matchScope)
    assertEquals(AudienceScope.FAMILY, rows.single().audienceScope)
    assertEquals("2026-08-19T14:30:00Z", rows.single().createdAt)
  }

  @Test
  fun aTombstoneRemovesIt() {
    val s = store()
    s.applyResponses(listOf(wire("r1")))
    s.applyResponses(tombstones = listOf(Tombstone("response", "r1")))
    assertTrue(s.allResponses().isEmpty())
  }

  // The echo is what ends the optimistic state, exactly as it does for a block's local_state.
  @Test
  fun anOptimisticWriteIsPendingUntilTheEchoClearsIt() {
    val s = store()
    s.upsertResponseLocal(local("r1"))
    assertTrue(s.allResponses().single().pending)
    s.applyResponses(listOf(wire("r1", version = 2L)))
    assertFalse(s.allResponses().single().pending)
    assertEquals(2L, s.allResponses().single().version)
  }

  @Test
  fun aSuccessfulPutClearsPendingBeforeTheNextEcho() {
    val s = store()
    s.upsertResponseLocal(local("r1"))
    s.enqueueResponseOp("op1", "r1", "upsert", "{}", "2026-08-08T09:00:00Z")
    s.claimNextPendingOp()
    s.ackResponseOp("op1", "r1", 2L)
    assertFalse(s.allResponses().single().pending)
    assertEquals(2L, s.allResponses().single().version)
  }

  @Test
  fun aRejectedOptimisticPutRollsBackItsLocalResponse() {
    val s = store()
    s.upsertResponseLocal(local("r1"))
    s.enqueueResponseOp("op1", "r1", "upsert", "{}", "2026-08-08T09:00:00Z")
    s.claimNextPendingOp()
    s.rejectResponseOp("op1", "r1", isDelete = false, failed = false)
    assertTrue(s.allResponses().isEmpty())
    assertEquals(0L, s.outboxSize())
  }

  @Test
  fun aRejectedDeleteRestoresItsExactRollbackSnapshot() {
    val s = store()
    s.applyResponses(listOf(wire("r1", version = 3L)))
    assertTrue(s.enqueueResponseDelete("del1", "r1", "2026-08-19T15:00:00Z"))
    assertTrue(s.allResponses().isEmpty())
    val op = s.claimNextPendingOp()!!

    s.rejectResponseOp(
      op.opId, op.targetId, isDelete = true, failed = false, rollbackPayload = op.payload,
    )

    val restored = s.allResponses().single()
    assertEquals("r1", restored.id)
    assertEquals(3L, restored.version)
    assertEquals("2026-08-19T14:30:00Z", restored.createdAt)
    assertFalse(restored.pending)
    assertEquals(0L, s.outboxSize())
  }

  @Test
  fun responseUndoCannotOvertakeItsPendingPutWithEqualTimestampAndReverseIds() {
    val s = store()
    s.upsertResponseLocal(local("r1"))
    // Lexicographically, the DELETE would win the old created_at/op_id ordering.
    s.enqueueResponseOp("z-put", "r1", "upsert", "{}", "2026-08-19T15:00:00Z")
    assertTrue(s.enqueueResponseDelete("a-delete", "r1", "2026-08-19T15:00:00Z"))

    val put = s.claimNextPendingOp()!!
    assertEquals("z-put", put.opId)
    assertEquals(null, s.claimNextPendingOp(), "DELETE must wait while its PUT is inflight")

    s.ackOp(put.opId, 1L)
    val delete = s.claimNextPendingOp()!!
    assertEquals("a-delete", delete.opId)
  }

  @Test
  fun aSuccessfulDeleteRemovesAResponseRehydratedByFullResync() {
    val s = store()
    val original = wire("r1", version = 3L)
    s.applyResponses(listOf(original))
    assertTrue(s.enqueueResponseDelete("del1", "r1", "2026-08-19T15:00:00Z"))

    // A from-infinity pull can race the queued DELETE and replay server truth before egress drains.
    s.applyResponses(listOf(original))
    assertEquals("r1", s.allResponses().single().id)
    val op = s.claimNextPendingOp()!!

    s.ackResponseOp(op.opId, op.targetId, resultVersion = null, isDelete = true)

    assertTrue(s.allResponses().isEmpty(), "successful DELETE must win over the earlier full-sync row")
  }

  @Test
  fun aCanonicalDonePulledBeforeDrainRemovesItsPendingDuplicateAndOp() {
    val s = store()
    val optimistic = ContentResponse(
      id = "local-done", kind = ResponseKind.DONE, subjectRef = "card:c1",
      matchScope = MatchScope.SUBJECT, audienceScope = AudienceScope.FAMILY,
      userId = null, createdBy = "u_dad", label = "Call caterer", pending = true,
    )
    s.upsertResponseLocal(optimistic)
    s.enqueueResponseOp("local-done", "local-done", "upsert", "{}", "2026-08-19T15:00:00Z")

    s.applyResponses(
      listOf(
        ContentResponseWire(
          id = "canonical-done", kind = "done", subjectRef = "card:c1",
          matchScope = "subject", audienceScope = "family", createdBy = "u_mom",
          label = "Call caterer", createdAt = "2026-08-19T14:59:59Z",
        ),
      ),
    )

    assertEquals(listOf("canonical-done"), s.allResponses().map { it.id })
    assertEquals(0L, s.outboxSize())
  }

  @Test
  fun enqueueingAResponseOpTargetsTheResponseLane() {
    val s = store()
    s.upsertResponseLocal(local("r1"))
    s.enqueueResponseOp("op1", "r1", "upsert", "{}", "2026-08-08T09:00:00Z")
    val op = s.nextPendingOp()!!
    assertEquals("response", op.targetKind)
    assertEquals("r1", op.targetId)
    assertEquals("upsert", op.type)
  }

  // A response DELETE acks with a null version and its echo is a tombstone, not a row, so the
  // version-keyed block suppression can never fire — without this the acked op would linger.
  @Test
  fun anAckedResponseOpIsDroppedOnItsEcho() {
    val s = store()
    s.upsertResponseLocal(local("r1"))
    s.enqueueResponseOp("op1", "r1", "upsert", "{}", "2026-08-08T09:00:00Z")
    s.claimNextPendingOp()
    s.ackOpAndAdvanceSuccessor("op1", "r1", 2L, "2026-08-08T09:00:01Z")
    assertEquals(1, s.outboxSize())
    s.applyResponses(listOf(wire("r1", version = 2L)))
    assertEquals(0, s.outboxSize())
  }

  @Test
  fun anAckedDeleteOpIsDroppedOnItsTombstone() {
    val s = store()
    s.upsertResponseLocal(local("r1"))
    s.enqueueResponseOp("op1", "r1", "delete", "", "2026-08-08T09:00:00Z")
    s.claimNextPendingOp()
    s.ackOpAndAdvanceSuccessor("op1", "r1", null, "2026-08-08T09:00:01Z")
    s.applyResponses(tombstones = listOf(Tombstone("response", "r1")))
    assertEquals(0, s.outboxSize())
  }

  // Rules are family content: tenancy revocation drops them, and so does a full resync.
  @Test
  fun bothWipeBoundariesDropRules() {
    val s = store()
    s.applyResponses(listOf(wire("r1")))
    s.wipeForResync()
    assertTrue(s.allResponses().isEmpty())

    s.applyResponses(listOf(wire("r2")))
    s.wipe()
    assertTrue(s.allResponses().isEmpty())
  }

  @Test
  fun aSchemaHealPreservesPendingResponsesWithTheirOutboxOps() {
    val s = store()
    s.applyResponses(listOf(wire("synced")))
    s.upsertResponseLocal(local("pending"))
    s.enqueueResponseOp("pending", "pending", "upsert", "{}", "2026-08-19T15:00:00Z")

    s.reconcileSchemaVersion(CLIENT_SCHEMA_VERSION + 1)

    val retained = s.allResponses().single()
    assertEquals("pending", retained.id, "acknowledged response is rebuilt, optimistic row remains")
    assertTrue(retained.pending)
    assertEquals(1L, s.outboxSize(), "queued write and its optimistic row remain paired")
  }

  // Tier 0 — the once-ever offer. Device-local anti-nag history, so a staleness reset must not
  // re-offer an escalation the member already declined; tenancy revocation still clears it.
  @Test
  fun theOfferFlagIsOnceEverAndSurvivesAResync() {
    val s = store()
    assertFalse(s.wasResponseOffered("card:c1"))
    s.recordResponseOffer("card:c1", "2026-08-08T09:00:00Z")
    assertTrue(s.wasResponseOffered("card:c1"))
    s.recordResponseOffer("card:c1", "2026-08-09T09:00:00Z")   // idempotent — still one offer
    assertTrue(s.wasResponseOffered("card:c1"))

    s.wipeForResync()
    assertTrue(s.wasResponseOffered("card:c1"))   // preserved, like `hidden` and surfacing_state
    s.wipe()
    assertFalse(s.wasResponseOffered("card:c1"))  // tenancy revocation clears it
  }
}
