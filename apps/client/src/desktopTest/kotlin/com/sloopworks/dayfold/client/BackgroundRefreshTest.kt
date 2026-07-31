package com.sloopworks.dayfold.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class BackgroundRefreshTest {

  private val NOW = "2026-07-31T12:00:00Z"

  // No cached family (fresh install, or signed out) → the pass does nothing at all.
  // It must NOT attempt a network call it cannot authorize.
  @Test fun `no cached family is a clean no-op`() = runBlocking {
    var synced = false
    var reconciled = false
    val outcome = backgroundRefreshPass(
      deps = deps(memberships = emptyList(), sync = { synced = true }, reconcile = { reconciled = true }),
      budget = 30.seconds,
    )

    assertFalse(synced)
    assertFalse(outcome.synced)
    assertEquals("no-family", outcome.skippedReason)
    // Reconcile is load-bearing on every exit path, including this early return
    // (schedules going stale is a worse failure than content going stale).
    assertTrue(reconciled)
    assertTrue(outcome.reconciled)
  }

  // Reconcile MUST still run when sync overruns the budget: schedules going stale is a
  // worse failure than content going stale, and reconcile is cheap and local.
  //
  // Uses real time (no kotlinx-coroutines-test / virtual clock in this module's desktopTest):
  // a 50ms budget against a 5s delay proves the timeout fires without the suite sitting for
  // a real 60 seconds.
  @Test fun `reconcile still runs when sync exhausts the budget`() = runBlocking {
    var reconciled = false
    val outcome = backgroundRefreshPass(
      deps = deps(sync = { delay(5.seconds) }, reconcile = { reconciled = true }),
      budget = 50.milliseconds,
    )

    assertTrue(outcome.budgetExhausted)
    assertFalse(outcome.synced)
    assertTrue(reconciled)
  }

  // The happy path reports what it did, for the Log line.
  @Test fun `reports a completed pass`() = runBlocking {
    var reconciled = false
    val outcome = backgroundRefreshPass(
      deps = deps(reconcile = { reconciled = true }),
      budget = 30.seconds,
    )

    assertTrue(outcome.synced)
    assertTrue(reconciled)
    assertFalse(outcome.budgetExhausted)
    assertEquals(null, outcome.skippedReason)
  }

  // A live runtime must be delegated to — never bypassed. Two independent refreshers
  // race token rotation and the server's reuse detection signs the user out.
  @Test fun `delegates to the live runtime instead of syncing headlessly`() = runBlocking {
    var delegated = false
    var headless = false
    var reconciled = false
    val outcome = backgroundRefreshPass(
      deps = deps(
        delegate = { delegated = true; true },
        sync = { headless = true },
        reconcile = { reconciled = true },
      ),
      budget = 30.seconds,
    )

    assertTrue(delegated)
    assertFalse(headless)
    assertTrue(outcome.delegated)
    assertTrue(outcome.synced)
    // Reconcile is load-bearing on every exit path, including the delegate return.
    assertTrue(reconciled)
    assertTrue(outcome.reconciled)
  }

  // CRITICAL (final review) — the headless writer must not disarm the client cache heal.
  // applyDelta stamps CLIENT_SCHEMA_VERSION unconditionally and reconcileSchemaVersion skips its
  // wipe once the stored tag is >= current, so a drain onto an un-healed cache permanently strands
  // the old-model rows. This test pins the mechanism the fix exists to prevent: it asserts the
  // WRONG (pre-fix) end state, so it fails the moment anyone routes a headless writer through
  // backgroundRefreshPass directly again.
  @Test fun `an unhealed headless drain stamps the version and strands stale rows forever`() = runBlocking {
    val (store, driver) = staleSchemaStore()

    backgroundRefreshPass(
      deps = deps(sync = { store.applyDelta(listOf(card("fresh")), emptyList(), emptyList(), emptyList(), emptyList(), "c2", NOW) }),
      budget = 30.seconds,
    )

    // The drain stamped the running build's version over a cache written by the older model...
    assertEquals(CLIENT_SCHEMA_VERSION, store.schemaVersion())
    // ...so the foreground heal on the next app open is now a no-op and the stale row survives.
    store.reconcileSchemaVersion()
    assertTrue(store.activeCards().any { it.id == "stale" }, "the heal never gets a second chance")
    driver.close()
  }

  // The fix: the headless entry point heals FIRST, so the drain rebuilds from -∞ instead of
  // stamping over old-model rows.
  @Test fun `headlessRefreshPass heals an older-schema cache before the drain writes`() = runBlocking {
    val (store, driver) = staleSchemaStore()
    var cursorSeenBySync: String? = "not-called"

    headlessRefreshPass(
      contentStore = store,
      databaseDispatcher = Dispatchers.Unconfined,
      deps = deps(
        sync = {
          cursorSeenBySync = store.cursor()
          store.applyDelta(listOf(card("fresh")), emptyList(), emptyList(), emptyList(), emptyList(), "c2", NOW)
        },
      ),
      budget = 30.seconds,
    )

    // Heal ran BEFORE the sync: the cursor was reset, so the drain asked for everything again.
    assertNull(cursorSeenBySync)
    assertFalse(store.activeCards().any { it.id == "stale" }, "old-model row wiped by the heal")
    assertTrue(store.activeCards().any { it.id == "fresh" }, "the drain still applied after the heal")
    assertEquals(CLIENT_SCHEMA_VERSION, store.schemaVersion())
    driver.close()
  }

  /** A cache seeded by an "older build": real rows + cursor, tagged one schema version behind. */
  private fun staleSchemaStore(): Pair<ContentStore, JdbcSqliteDriver> {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    val store = ContentStore.create(driver)
    store.applyDelta(listOf(card("stale")), emptyList(), emptyList(), emptyList(), emptyList(), "c1", NOW)
    // applyDelta stamps the CURRENT version; rewrite the tag to what the previous build would have
    // left, which is the only state reconcileSchemaVersion is supposed to heal.
    driver.execute(null, "UPDATE sync_meta SET client_schema_version = ${CLIENT_SCHEMA_VERSION - 1} WHERE id = 0", 0)
    return store to driver
  }

  private fun card(id: String) = Card(id = id, kind = "info", title = id, provenance = Provenance("claude"))

  // MINOR (final review) — `synced` used to be set from "the delegate returned without throwing",
  // which is true even when the delegate short-circuits (no worker bound yet) or its pass finds no
  // family and no-ops. The Log.i("refresh") line is this feature's only on-device observability, so
  // it must carry the delegate's own answer.
  @Test fun `a delegate that did not sync is not reported as synced`() = runBlocking {
    var reconciled = false
    val outcome = backgroundRefreshPass(
      deps = deps(delegate = { false }, reconcile = { reconciled = true }),
      budget = 30.seconds,
    )

    assertTrue(outcome.delegated)
    assertFalse(outcome.synced, "the delegate reported it did not sync")
    assertFalse(outcome.budgetExhausted, "it returned in time — it just had nothing to do")
    assertTrue(reconciled)
  }

  // A delegate that throws is also not a sync, and must not be reported as one.
  @Test fun `a delegate that throws is not reported as synced`() = runBlocking {
    val outcome = backgroundRefreshPass(
      deps = deps(delegate = { error("delegate blew up") }),
      budget = 30.seconds,
    )

    assertTrue(outcome.delegated)
    assertFalse(outcome.synced)
    assertTrue(outcome.reconciled)
  }

  private fun deps(
    memberships: List<FamilyMembership> = listOf(FamilyMembership(familyId = "f1")),
    session: Session? = Session(access = "a", refresh = "r"),
    delegate: (suspend () -> Boolean)? = null,
    sync: suspend () -> Unit = {},
    reconcile: () -> Unit = {},
  ) = RefreshDeps(
    memberships = { memberships },
    session = { session },
    delegateToRuntime = delegate,
    syncOnce = { _, _ -> sync() },
    reconcile = reconcile,
  )

  // The headless path must use the SAME drainer as the foreground: pages applied in order,
  // cursor advanced per page, no Redux involvement. This is deliberately NOT a TDD red step —
  // it exercises SyncDrainer (Task 1) directly and must pass immediately. Its job is to fail
  // later if anyone gives the background path its own paging loop.
  @Test fun `headless sync drains pages through the shared drainer`() = runBlocking {
    val store = inMemoryContentStore()
    var page = 0
    SyncDrainer(
      contentStore = store,
      databaseDispatcher = Dispatchers.Unconfined,
      nowIso = { "2026-07-31T12:00:00Z" },
      fetch = {
        page++
        SyncResponse(
          changes = Changes(), tombstones = emptyList(),
          nextCursor = "c$page", hasMore = page < 2, fullResync = false,
        )
      },
      commit = { block -> block(); true },
      onActivity = {},
    ).drain()

    // Both pages applied through the SHARED drainer, cursor left at the last one.
    assertEquals(2, page)
    assertEquals("c2", store.cursor())
  }

  // No 401 anywhere in the drain: refreshAccess must never be invoked. Proves the happy
  // path doesn't spend a refresh it doesn't need.
  @Test fun `headless sync drains cleanly with no refresh when nothing is unauthorized`() = runBlocking {
    val store = inMemoryContentStore()
    var refreshCalls = 0
    val client = syncClient(MockEngine {
      respond(
        """{"changes":{"cards":[]},"tombstones":[],"next_cursor":"c1","has_more":false}""",
        HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
      )
    })

    headlessSync(
      contentStore = store,
      syncClient = client,
      databaseDispatcher = Dispatchers.Unconfined,
      familyId = "f1",
      session = Session(access = "old-access", refresh = "old-refresh"),
      refreshAccess = { refreshCalls++; Session(access = "new-access", refresh = "new-refresh") },
      nowIso = { "2026-07-31T12:00:00Z" },
    )

    assertEquals(0, refreshCalls)
    assertEquals("c1", store.cursor())
  }

  // A single 401 refreshes exactly once, then retries with the rotated credential and
  // succeeds. This is the governing correctness point: a background wake has no live
  // runtime to fence a refresh race, so it gets exactly one attempt.
  @Test fun `headless sync refreshes once on a 401 and retries with the new access token`() = runBlocking {
    val store = inMemoryContentStore()
    var refreshCalls = 0
    var seenRefreshToken: String? = null
    val client = syncClient(MockEngine { req ->
      when (req.headers[HttpHeaders.Authorization]) {
        "Bearer old-access" -> respond("", HttpStatusCode.Unauthorized)
        "Bearer new-access" -> respond(
          """{"changes":{"cards":[]},"tombstones":[],"next_cursor":"c1","has_more":false}""",
          HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
        )
        else -> respond("", HttpStatusCode.Unauthorized)
      }
    })

    headlessSync(
      contentStore = store,
      syncClient = client,
      databaseDispatcher = Dispatchers.Unconfined,
      familyId = "f1",
      session = Session(access = "old-access", refresh = "old-refresh"),
      refreshAccess = { rt ->
        refreshCalls++
        seenRefreshToken = rt
        Session(access = "new-access", refresh = "new-refresh")
      },
      nowIso = { "2026-07-31T12:00:00Z" },
    )

    assertEquals(1, refreshCalls)
    assertEquals("old-refresh", seenRefreshToken)
    assertEquals("c1", store.cursor())
  }

  // Second 401 after the one refresh already spent MUST give up rather than refresh again —
  // a second independent refresh races token rotation and the server's reuse detection
  // signs the user out. The failure must propagate so the caller can no-op this wake and
  // let the next foreground open handle re-auth.
  @Test fun `headless sync gives up after a second 401 without refreshing again`() = runBlocking<Unit> {
    val store = inMemoryContentStore()
    var refreshCalls = 0
    val client = syncClient(MockEngine { respond("", HttpStatusCode.Unauthorized) })

    val error = assertFailsWith<SyncHttpException> {
      headlessSync(
        contentStore = store,
        syncClient = client,
        databaseDispatcher = Dispatchers.Unconfined,
        familyId = "f1",
        session = Session(access = "old-access", refresh = "old-refresh"),
        refreshAccess = { refreshCalls++; Session(access = "new-access", refresh = "new-refresh") },
        nowIso = { "2026-07-31T12:00:00Z" },
      )
    }

    assertEquals(401, error.status)
    assertEquals(1, refreshCalls)
  }

  // A refresh failure (revoked lineage) must surface the ORIGINAL 401 rather than the
  // refresh's own error, and still must not be retried.
  @Test fun `headless sync gives up when the refresh itself fails`() = runBlocking<Unit> {
    val store = inMemoryContentStore()
    var refreshCalls = 0
    val client = syncClient(MockEngine { respond("", HttpStatusCode.Unauthorized) })

    val error = assertFailsWith<SyncHttpException> {
      headlessSync(
        contentStore = store,
        syncClient = client,
        databaseDispatcher = Dispatchers.Unconfined,
        familyId = "f1",
        session = Session(access = "old-access", refresh = "old-refresh"),
        refreshAccess = { refreshCalls++; null },
        nowIso = { "2026-07-31T12:00:00Z" },
      )
    }

    assertEquals(401, error.status)
    assertEquals(1, refreshCalls)
  }

  private fun inMemoryContentStore(): ContentStore =
    ContentStore.create(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))

  private fun syncClient(engine: MockEngine): SyncClient =
    SyncClient("https://api.test", HttpClient(engine))
}
