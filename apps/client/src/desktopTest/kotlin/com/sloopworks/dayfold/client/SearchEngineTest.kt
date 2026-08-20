package com.sloopworks.dayfold.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

class SearchEngineTest {
  @Test fun `card tap publishes the canonical DB projection before opening detail`() = runBlocking {
    val fixture = fixture()
    try {
      fixture.content.applyDelta(
        changedCards = listOf(Card("card", "info", "Soccer schedule", provenance = Provenance("member"))),
        changedHubs = emptyList(), tombstones = emptyList(), nextCursor = "cursor", nowIso = NOW,
      )
      fixture.engine.bindFamilyWork(fixture.family, fixture.familyScope, fixture.publication)
      withTimeout(5_000) { fixture.engine.status.first { it.readiness == SearchReadiness.READY } }
      val handle = fixture.engine.search("soccer").results.single().handle
      val store = createAppStore(
        notificationContext = org.reduxkotlin.concurrent.NotificationContext.Inline,
        initial = AppState(
          session = SessionState(
            session = Session("access", "refresh", "member"),
            families = listOf(FamilyMembership(FAMILY_ID, "Family", "owner", "active")),
            activeFamilyId = FAMILY_ID,
          ),
          navigation = NavigationState(route = Route.Search),
        ),
        debug = false,
      )
      val commands = DayfoldCommands(
        store = store,
        scope = fixture.rootScope,
        searchEngine = fixture.engine,
        sessionCoordinator = fixture.coordinator,
      )

      commands.openSearchResult(handle)

      withTimeout(5_000) {
        while (store.state.navigation.detailStack != listOf("card")) yield()
      }
      assertEquals(listOf("card"), store.state.content.cards.map { it.id })
      assertEquals(Route.Feed, store.state.navigation.route)
      assertEquals(DetailReturnDestination.SEARCH, store.state.navigation.detailReturnDestination)
    } finally {
      fixture.close()
    }
  }

  @Test fun `family replacement between resolve and navigation rejects a colliding id`() = runBlocking {
    val fixture = fixture()
    try {
      fixture.content.applyDelta(
        changedCards = listOf(Card("same-id", "info", "Private soccer", provenance = Provenance("member"))),
        changedHubs = emptyList(), tombstones = emptyList(), nextCursor = "cursor", nowIso = NOW,
      )
      fixture.engine.bindFamilyWork(fixture.family, fixture.familyScope, fixture.publication)
      withTimeout(5_000) { fixture.engine.status.first { it.readiness == SearchReadiness.READY } }
      val handle = fixture.engine.search("soccer").results.single().handle
      val store = createAppStore(
        notificationContext = org.reduxkotlin.concurrent.NotificationContext.Inline,
        initial = AppState(
          session = SessionState(
            session = Session("access", "refresh", "member"),
            families = listOf(FamilyMembership(FAMILY_ID, "Family", "owner", "active")),
            activeFamilyId = FAMILY_ID,
          ),
          navigation = NavigationState(route = Route.Search),
        ),
        debug = false,
      )
      val resolved = CompletableDeferred<Unit>()
      val continueCommit = CompletableDeferred<Unit>()
      val commands = DayfoldCommands(
        store = store,
        scope = fixture.rootScope,
        searchEngine = fixture.engine,
        sessionCoordinator = fixture.coordinator,
        beforeSearchNavigationCommit = {
          resolved.complete(Unit)
          continueCommit.await()
        },
      )

      commands.openSearchResult(handle)
      withTimeout(5_000) { resolved.await() }
      fixture.coordinator.selectFamily(fixture.auth, null)
      requireNotNull(fixture.coordinator.selectFamily(fixture.auth, FAMILY_ID))
      continueCommit.complete(Unit)
      repeat(20) { yield() }

      assertEquals(Route.Search, store.state.navigation.route)
      assertTrue(store.state.navigation.detailStack.isEmpty())
      assertTrue(store.state.content.cards.isEmpty())
    } finally {
      fixture.close()
    }
  }

  @Test fun `content invalidation refreshes results and rejects a stale tap immediately`() = runBlocking {
    val fixture = fixture()
    try {
      fixture.content.applyDelta(
        changedCards = listOf(Card("card", "info", "Soccer schedule", provenance = Provenance("member"))),
        changedHubs = emptyList(), tombstones = emptyList(), nextCursor = "cursor", nowIso = NOW,
      )
      fixture.engine.bindFamilyWork(fixture.family, fixture.familyScope, fixture.publication)
      withTimeout(5_000) { fixture.engine.status.first { it.readiness == SearchReadiness.READY } }

      val first = fixture.engine.search("soccer")
      assertEquals(listOf("card"), first.results.mapNotNull { it.handle.destination.cardId })
      val handle = first.results.single().handle

      fixture.content.hide("card", NOW)
      assertNull(fixture.engine.resolve(handle), "revision fence must reject before rebuild publication")
      withTimeout(5_000) {
        fixture.engine.status.first { it.corpusGeneration > first.corpusGeneration }
      }
      assertTrue(fixture.engine.search("soccer").results.isEmpty())
    } finally {
      fixture.close()
    }
  }

  @Test fun `same family id rebound under a new context advances the opaque binding`() = runBlocking {
    val fixture = fixture()
    try {
      fixture.content.applyDelta(
        changedCards = listOf(Card("card", "info", "Permission slip", provenance = Provenance("member"))),
        changedHubs = emptyList(), tombstones = emptyList(), nextCursor = "cursor", nowIso = NOW,
      )
      fixture.engine.bindFamilyWork(fixture.family, fixture.familyScope, fixture.publication)
      val first = withTimeout(5_000) {
        fixture.engine.status.first { it.readiness == SearchReadiness.READY }
      }
      fixture.engine.closeFamilyAdmission()
      fixture.publication.close()
      fixture.familyScope.cancel()

      fixture.coordinator.selectFamily(fixture.auth, null)
      val rebound = requireNotNull(fixture.coordinator.selectFamily(fixture.auth, FAMILY_ID))
      val reboundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      val reboundPublication = PublicationBoundary()
      try {
        fixture.engine.bindFamilyWork(rebound, reboundScope, reboundPublication)
        val second = withTimeout(5_000) {
          fixture.engine.status.first { it.readiness == SearchReadiness.READY }
        }
        assertNotEquals(first.bindingGeneration, second.bindingGeneration)
        assertTrue(second.bindingGeneration > first.bindingGeneration)
      } finally {
        fixture.engine.closeFamilyAdmission()
        reboundPublication.close()
        reboundScope.cancel()
      }
    } finally {
      fixture.rootScope.cancel()
    }
  }

  @Test fun `closing admission clears corpus synchronously`() = runBlocking {
    val fixture = fixture()
    try {
      fixture.content.applyDelta(
        changedCards = listOf(Card("card", "info", "Immunization", provenance = Provenance("member"))),
        changedHubs = emptyList(), tombstones = emptyList(), nextCursor = "cursor", nowIso = NOW,
      )
      fixture.engine.bindFamilyWork(fixture.family, fixture.familyScope, fixture.publication)
      withTimeout(5_000) { fixture.engine.status.first { it.readiness == SearchReadiness.READY } }
      val live = fixture.engine.search("immunization")
      val handle = live.results.single().handle

      fixture.engine.closeFamilyAdmission()

      assertEquals(SearchReadiness.WAITING_FOR_SYNC, fixture.engine.status.value.readiness)
      assertNull(fixture.engine.resolve(handle))
      assertEquals(SearchFailure.UNAVAILABLE, fixture.engine.search("immunization").failure)
    } finally {
      fixture.close()
    }
  }

  private fun fixture(): Fixture {
    val rootScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val coordinator = SessionCoordinator(
      refreshScope = rootScope,
      refreshSession = { error("refresh not expected") },
      commitRotation = {},
    )
    val auth = coordinator.install(Session("access", "refresh", "member"))
    val family = requireNotNull(coordinator.selectFamily(auth, FAMILY_ID))
    val content = ContentStore.create(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
    return Fixture(
      rootScope = rootScope,
      familyScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
      coordinator = coordinator,
      auth = auth,
      family = family,
      content = content,
      engine = SearchEngine(content, coordinator),
      publication = PublicationBoundary(),
    )
  }

  private data class Fixture(
    val rootScope: CoroutineScope,
    val familyScope: CoroutineScope,
    val coordinator: SessionCoordinator,
    val auth: AuthSessionContext,
    val family: FamilySessionContext,
    val content: ContentStore,
    val engine: SearchEngine,
    val publication: PublicationBoundary,
  ) {
    fun close() {
      engine.closeFamilyAdmission()
      publication.close()
      familyScope.cancel()
      rootScope.cancel()
    }
  }

  private companion object {
    const val FAMILY_ID = "family"
    const val NOW = "2026-08-19T12:00:00Z"
  }
}
