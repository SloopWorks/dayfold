package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class BackgroundRefreshTest {

  // No cached family (fresh install, or signed out) → the pass does nothing at all.
  // It must NOT attempt a network call it cannot authorize.
  @Test fun `no cached family is a clean no-op`() = runBlocking {
    var synced = false
    val outcome = backgroundRefreshPass(
      deps = deps(memberships = emptyList(), sync = { synced = true }),
      budget = 30.seconds,
    )

    assertFalse(synced)
    assertFalse(outcome.synced)
    assertEquals("no-family", outcome.skippedReason)
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
    val outcome = backgroundRefreshPass(
      deps = deps(delegate = { delegated = true }, sync = { headless = true }),
      budget = 30.seconds,
    )

    assertTrue(delegated)
    assertFalse(headless)
    assertTrue(outcome.delegated)
    assertTrue(outcome.synced)
  }

  private fun deps(
    memberships: List<FamilyMembership> = listOf(FamilyMembership(familyId = "f1")),
    session: Session? = Session(access = "a", refresh = "r"),
    delegate: (suspend () -> Unit)? = null,
    sync: suspend () -> Unit = {},
    reconcile: () -> Unit = {},
  ) = RefreshDeps(
    memberships = { memberships },
    session = { session },
    delegateToRuntime = delegate,
    syncOnce = { _, _ -> sync() },
    reconcile = reconcile,
  )
}
