package com.sloopworks.dayfold.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ADR 0064 — the device half of the Tier-1 contract: rules land from /sync, optimistic writes
// are pending until the echo, undo drops the queued op, and the wipe boundaries put rules on
// the SYNCED side while the once-ever offer flag stays device-local.
class ResponseStoreTest {

  private fun store() = ContentStore.create(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))

  private fun wire(id: String, label: String = "Weather cards", version: Long = 1L) =
    ContentResponseWire(
      id = id, kind = "mute", subjectRef = "kind:weather", matchScope = "kind",
      audienceScope = "family", userId = null, createdBy = "u_mom", label = label,
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
  fun enqueueingAResponseOpTargetsTheResponseLane() {
    val s = store()
    s.upsertResponseLocal(local("r1"))
    s.enqueueResponseOp("op1", "r1", "upsert", "{}", "2026-08-08T09:00:00Z")
    val op = s.nextPendingOp()!!
    assertEquals("response", op.targetKind)
    assertEquals("r1", op.targetId)
    assertEquals("upsert", op.type)
  }

  // Undo works offline precisely because the write has not left the device.
  @Test
  fun undoDropsTheQueuedOpAndTheLocalRow() {
    val s = store()
    s.upsertResponseLocal(local("r1"))
    s.enqueueResponseOp("op1", "r1", "upsert", "{}", "2026-08-08T09:00:00Z")
    s.dropQueuedOpsFor("r1")
    s.deleteResponseLocal("r1")
    assertTrue(s.allResponses().isEmpty())
    assertEquals(null, s.nextPendingOp())
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
