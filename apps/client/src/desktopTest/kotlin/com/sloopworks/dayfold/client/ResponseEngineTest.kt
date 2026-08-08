package com.sloopworks.dayfold.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ADR 0064 — the effect layer. Clock, id minting, and online-ness are injected so the offline
// vocabulary and the undo path are testable without a network or a wall clock.
class ResponseEngineTest {

  private class Harness(online: Boolean = true) {
    val contentStore = ContentStore.create(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
    val store = createTestAppStore(
      AppState(
        session = SessionState(
          session = Session("tok", "refresh", userId = "u_dad"),
          activeFamilyId = "fam1",
        ),
      ),
      debug = false,
    )
    private var ids = 0
    var online = online
    // A MockEngine that answers /sync with an empty page: the engine kicks a sync after each
    // write, and it must not be the thing under test.
    private val syncClient = SyncClient(
      "https://api.test",
      HttpClient(MockEngine {
        respond(
          """{"changes":{},"tombstones":[],"has_more":false}""",
          HttpStatusCode.OK,
          headersOf("content-type", listOf("application/json")),
        )
      }),
    )
    val engine = ResponseEngine(
      store = store,
      contentStore = contentStore,
      syncEngine = SyncEngine(store, contentStore, syncClient, nowProvider = { "2026-08-08T09:00:00Z" }),
      isOnline = { this.online },
      nowProvider = { "2026-08-08T09:00:00Z" },
      idProvider = { "op${++ids}" },
    )
  }

  @Test
  fun muteWritesOptimisticallyAndEnqueuesOneOp() = runBlocking {
    val h = Harness()
    h.engine.mute("kind:weather", MatchScope.KIND, AudienceScope.PERSONAL, "Weather cards", "From Morning briefing")

    val row = h.contentStore.allResponses().single()
    assertEquals(ResponseKind.MUTE, row.kind)
    assertEquals(AudienceScope.PERSONAL, row.audienceScope)
    assertEquals("u_dad", row.userId)          // personal rules are owned by their author
    assertTrue(row.pending)

    val op = h.contentStore.nextPendingOp()!!
    assertEquals("response", op.targetKind)
    assertEquals("upsert", op.type)
    // `pending` is local UI state and must never reach the wire.
    assertFalse(op.payload.contains("pending"))
    assertTrue(op.payload.contains("\"audience_scope\":\"personal\""))
  }

  @Test
  fun aFamilyMuteHasNoOwner() = runBlocking {
    val h = Harness()
    h.engine.mute("kind:weather", MatchScope.KIND, AudienceScope.FAMILY, "Weather in Now")
    val row = h.contentStore.allResponses().single()
    assertNull(row.userId)
    assertEquals("u_dad", row.createdBy)       // still attributed (decided Q2)
  }

  // A rule cannot stop a run that already happened — the copy says so rather than implying
  // instant effect.
  @Test
  fun offlineMuteStillAppliesLocallyAndSaysSo() = runBlocking {
    val h = Harness(online = false)
    h.engine.mute("kind:weather", MatchScope.KIND, AudienceScope.PERSONAL, "Weather cards")
    assertEquals(1, h.contentStore.allResponses().size)
    val receipt = h.store.state.responses.lastReceipt!!
    assertTrue(receipt.offline)
    assertEquals("Muted — will sync when you're online", receipt.message)
  }

  @Test
  fun undoDropsTheQueuedWriteAndTheLocalRow() = runBlocking {
    val h = Harness(online = false)
    h.engine.mute("kind:weather", MatchScope.KIND, AudienceScope.PERSONAL, "Weather cards")
    h.engine.undoLastResponse()
    assertTrue(h.contentStore.allResponses().isEmpty())
    assertNull(h.contentStore.nextPendingOp())
    assertNull(h.store.state.responses.lastReceipt)
  }

  @Test
  fun undoWithNoReceiptIsANoOp() = runBlocking {
    val h = Harness()
    h.engine.undoLastResponse()
    assertTrue(h.contentStore.allResponses().isEmpty())
  }

  @Test
  fun markDoneIsAlwaysFamilyScopedOnAConcreteSubject() = runBlocking {
    val h = Harness()
    h.engine.markDone("card:c1", "Verify emergency contact", "Confirmed — used Grandma's new number.")
    val row = h.contentStore.allResponses().single()
    assertEquals(ResponseKind.DONE, row.kind)
    assertEquals(AudienceScope.FAMILY, row.audienceScope)
    assertEquals(MatchScope.SUBJECT, row.matchScope)
    assertEquals("Confirmed — used Grandma's new number.", row.note)
    assertEquals("u_dad", row.createdBy)       // the byline is the only signal (§5)
  }

  // Notes are free user text and will contain quotes and apostrophes.
  @Test
  fun aNoteWithQuotesSurvivesTheWire() = runBlocking {
    val h = Harness()
    h.engine.markDone("card:c1", "Task", "She said \"done\"\nreally")
    val payload = h.contentStore.nextPendingOp()!!.payload
    assertTrue(payload.contains("\\\"done\\\""))
    assertTrue(payload.contains("\\n"))
  }

  @Test
  fun removeResponseQueuesADeleteOp() = runBlocking {
    val h = Harness()
    h.engine.mute("kind:weather", MatchScope.KIND, AudienceScope.PERSONAL, "Weather cards")
    val ruleId = h.contentStore.allResponses().single().id
    h.engine.removeResponse(ruleId)
    assertTrue(h.contentStore.allResponses().isEmpty())
    assertTrue(h.contentStore.outboxSize() > 0)
  }

  @Test
  fun theOfferFlagIsRecordedOncePerSubject() = runBlocking {
    val h = Harness()
    assertFalse(h.engine.wasResponseOffered("card:c1"))
    h.engine.recordResponseOffer("card:c1")
    assertTrue(h.engine.wasResponseOffered("card:c1"))
  }

  @Test
  fun reloadIsTheSoleWriterOfTheRulesSlice() = runBlocking {
    val h = Harness()
    h.engine.mute("kind:weather", MatchScope.KIND, AudienceScope.PERSONAL, "Weather cards")
    assertEquals(1, h.store.state.responses.rules.size)
  }
}
