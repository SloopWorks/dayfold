package com.sloopworks.dayfold.client

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class SessionCoordinatorTest {
  @Test fun `contexts are opaque redacted snapshots with an atomic family boundary`() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      val coordinator = coordinator(scope)
      val auth = coordinator.install(Session("access-secret", "refresh-secret", "user-secret"))

      assertSame(auth, coordinator.authSnapshot())
      assertTrue(coordinator.isCurrent(auth))
      assertNull(coordinator.familySnapshot("family-secret"))

      val family = coordinator.selectFamily(auth, "family-secret")!!
      assertTrue(coordinator.isCurrent(family))
      assertTrue(coordinator.familySnapshot("family-secret") != null)
      assertNull(coordinator.familySnapshot("another-family"))

      val rendered = "$auth $family"
      assertFalse(rendered.contains("access-secret"))
      assertFalse(rendered.contains("refresh-secret"))
      assertFalse(rendered.contains("user-secret"))
      assertFalse(rendered.contains("family-secret"))

      val rotated = coordinator.rotate(auth, Session("access-2", "refresh-2"))!!
      assertTrue(coordinator.isCurrent(auth))
      assertTrue(coordinator.isCurrent(family))
      assertTrue(coordinator.isCurrent(rotated))
      assertTrue(coordinator.familySnapshot("family-secret") != null)
      assertNull(coordinator.rotate(auth, Session("stale-access", "stale-refresh")))

      // An operation holding the original same-epoch auth snapshot can still select the family,
      // but the returned family context must carry the current credential revision.
      val currentFamily = coordinator.selectFamily(auth, "family-secret")!!
      assertTrue(coordinator.isCurrent(family), "confirming the same family must preserve its generation")
      assertEquals(
        "family-secret:access-2",
        currentFamily.withFamilyAndAccessToken { familyId, access -> "$familyId:$access" },
      )

      val replacement = coordinator.selectFamily(auth, "replacement-family")!!
      assertFalse(coordinator.isCurrent(family))
      assertFalse(coordinator.isCurrent(currentFamily))
      assertTrue(coordinator.isCurrent(replacement))
      val selectedAgain = coordinator.selectFamily(auth, "family-secret")!!
      assertFalse(coordinator.isCurrent(family), "A to B to A must not revive the original A context")
      assertFalse(coordinator.isCurrent(replacement))
      assertTrue(coordinator.isCurrent(selectedAgain))

      assertSame(rotated, coordinator.invalidate(rotated.identityEpoch))
      assertNull(coordinator.authSnapshot())
      assertNull(coordinator.familySnapshot("family-secret"))
      assertNull(coordinator.invalidate(rotated.identityEpoch))
    } finally {
      scope.cancel()
    }
  }

  @Test fun `replaceable auth operation rejects blocked stale installs`() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      val coordinator = coordinator(scope)
      val stale = coordinator.beginAuthOperation()
      val current = coordinator.beginAuthOperation()

      assertFalse(coordinator.isCurrent(stale))
      assertTrue(coordinator.isCurrent(current))
      assertNull(coordinator.install(stale, Session("stale", "stale")))

      val installed = coordinator.install(current, Session("current", "current"))!!
      assertTrue(coordinator.isCurrent(installed))
      assertFalse(coordinator.isCurrent(current))

      val blocked = coordinator.beginAuthOperation()
      coordinator.invalidate(installed.identityEpoch)
      assertFalse(coordinator.isCurrent(blocked))
      assertNull(coordinator.install(blocked, Session("late", "late")))
      assertNull(coordinator.authSnapshot())
    } finally {
      scope.cancel()
    }
  }

  @Test fun `family invalidation rejects a stale generation and accepts the current family`() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      val coordinator = coordinator(scope)
      val auth = coordinator.install(Session("access", "refresh"))
      val stale = coordinator.selectFamily(auth, "family-a")!!
      val current = coordinator.selectFamily(auth, "family-b")!!

      assertNull(coordinator.invalidate(stale))
      assertSame(auth, coordinator.authSnapshot())
      assertTrue(coordinator.isCurrent(current))

      assertSame(auth, coordinator.invalidate(current))
      assertNull(coordinator.authSnapshot())
      assertFalse(coordinator.isCurrent(current))
    } finally {
      scope.cancel()
    }
  }

  @Test fun `family ABA is rejected and commit is atomic with invalidation`() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      val coordinator = coordinator(scope)
      val auth = coordinator.install(Session("access", "refresh"))
      val originalA = coordinator.selectFamily(auth, "family-a")!!
      coordinator.selectFamily(auth, "family-b")
      val currentA = coordinator.selectFamily(auth, "family-a")!!
      var staleCommitRan = false

      assertFalse(coordinator.commitIfCurrent(originalA) { staleCommitRan = true })
      assertFalse(staleCommitRan)
      assertFailsWith<CancellationException> {
        coordinator.authorizedCall(originalA) { "must-not-run" }
      }

      val commitEntered = CompletableDeferred<Unit>()
      val releaseCommit = CountDownLatch(1)
      val authBeforeInvalidation = coordinator.authSnapshot()
      val publication = async(Dispatchers.Default) {
        coordinator.commitIfCurrent(currentA) {
          commitEntered.complete(Unit)
          releaseCommit.await()
        }
      }
      commitEntered.await()

      val invalidationStarted = CompletableDeferred<Unit>()
      val invalidation = async(Dispatchers.Default) {
        invalidationStarted.complete(Unit)
        coordinator.invalidate(auth.identityEpoch)
      }
      invalidationStarted.await()
      assertFalse(invalidation.isCompleted, "invalidation must not split validation from commit")

      releaseCommit.countDown()
      assertTrue(publication.await())
      assertSame(authBeforeInvalidation, invalidation.await())
      assertFalse(coordinator.isCurrent(currentA))
    } finally {
      scope.cancel()
    }
  }

  @Test fun `simultaneous 401s share one refresh and one rotation commit`() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val refreshStarted = CompletableDeferred<Unit>()
    val releaseRefresh = CompletableDeferred<Unit>()
    val refreshCalls = AtomicInteger()
    val commits = AtomicInteger()
    try {
      val coordinator = SessionCoordinator(
        refreshScope = scope,
        refreshSession = {
          refreshCalls.incrementAndGet()
          refreshStarted.complete(Unit)
          releaseRefresh.await()
          Session("fresh-access", "fresh-refresh")
        },
        commitRotation = { commits.incrementAndGet() },
      )
      val initial = coordinator.install(Session("expired", "refresh"))
      val oldAttempts = AtomicInteger()
      val secondOldAttempt = CompletableDeferred<Unit>()
      suspend fun request(context: AuthSessionContext): String = context.withAccessToken { token ->
        if (token == "expired") {
          if (oldAttempts.incrementAndGet() == 2) secondOldAttempt.complete(Unit)
          throw AuthHttpException(401, "test")
        }
        token
      }

      val first = async(Dispatchers.Default) { coordinator.authorizedCall(initial, ::request) }
      val second = async(Dispatchers.Default) { coordinator.authorizedCall(initial, ::request) }
      refreshStarted.await()
      secondOldAttempt.await()
      assertEquals(1, refreshCalls.get())

      releaseRefresh.complete(Unit)
      assertEquals("fresh-access", first.await())
      assertEquals("fresh-access", second.await())
      assertEquals(1, refreshCalls.get())
      assertEquals(1, commits.get())
    } finally {
      scope.cancel()
    }
  }

  @Test fun `cancelling first waiter does not cancel coordinator refresh`() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val refreshStarted = CompletableDeferred<Unit>()
    val releaseRefresh = CompletableDeferred<Unit>()
    val oldAttempts = AtomicInteger()
    val bothWaiting = CompletableDeferred<Unit>()
    val refreshCalls = AtomicInteger()
    try {
      val coordinator = SessionCoordinator(
        refreshScope = scope,
        refreshSession = {
          refreshCalls.incrementAndGet()
          refreshStarted.complete(Unit)
          releaseRefresh.await()
          Session("fresh", "refresh-2")
        },
        commitRotation = {},
      )
      val initial = coordinator.install(Session("expired", "refresh"))
      suspend fun request(context: AuthSessionContext): String = context.withAccessToken { token ->
        if (token == "expired") {
          if (oldAttempts.incrementAndGet() == 2) bothWaiting.complete(Unit)
          throw AuthHttpException(401, "test")
        }
        token
      }

      val first = async(Dispatchers.Default) { coordinator.authorizedCall(initial, ::request) }
      val second = async(Dispatchers.Default) { coordinator.authorizedCall(initial, ::request) }
      refreshStarted.await()
      bothWaiting.await()
      first.cancel()
      releaseRefresh.complete(Unit)

      assertEquals("fresh", second.await())
      assertEquals(1, refreshCalls.get())
      assertTrue(coordinator.authSnapshot()?.let(coordinator::isCurrent) == true)
    } finally {
      scope.cancel()
    }
  }

  @Test fun `invalidation cancels refresh and rejects a late non-cancellable result`() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val refreshStarted = CompletableDeferred<Unit>()
    val releaseRefresh = CompletableDeferred<Unit>()
    val commits = AtomicInteger()
    try {
      val coordinator = SessionCoordinator(
        refreshScope = scope,
        refreshSession = {
          refreshStarted.complete(Unit)
          withContext(NonCancellable) { releaseRefresh.await() }
          Session("must-not-commit", "must-not-commit")
        },
        commitRotation = { commits.incrementAndGet() },
      )
      val initial = coordinator.install(Session("expired", "refresh"))
      val caller = async(Dispatchers.Default) {
        coordinator.authorizedCall(initial) {
          throw AuthHttpException(401, "test")
        }
      }

      refreshStarted.await()
      assertSame(initial, coordinator.invalidate(initial.identityEpoch))
      releaseRefresh.complete(Unit)
      assertFailsWith<CancellationException> { caller.await() }

      assertEquals(0, commits.get())
      assertNull(coordinator.authSnapshot())
    } finally {
      scope.cancel()
    }
  }

  @Test fun `invalidated auth and family contexts make no transport call`() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      val coordinator = coordinator(scope)
      val auth = coordinator.install(Session("access", "refresh"))
      val family = coordinator.selectFamily(auth, "family")!!
      coordinator.invalidate(auth.identityEpoch)
      val calls = AtomicInteger()

      assertFailsWith<CancellationException> {
        coordinator.authorizedCall(auth) {
          calls.incrementAndGet()
          Unit
        }
      }
      assertFailsWith<CancellationException> {
        coordinator.authorizedCall(family) {
          calls.incrementAndGet()
          Unit
        }
      }
      assertEquals(0, calls.get())
    } finally {
      scope.cancel()
    }
  }

  @Test fun `stale 401 retries current same-epoch credentials without refreshing`() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val oldCallStarted = CompletableDeferred<Unit>()
    val releaseOldCall = CompletableDeferred<Unit>()
    val refreshCalls = AtomicInteger()
    try {
      val coordinator = SessionCoordinator(
        refreshScope = scope,
        refreshSession = {
          refreshCalls.incrementAndGet()
          Session("unexpected", "unexpected")
        },
        commitRotation = {},
      )
      val initial = coordinator.install(Session("expired", "refresh"))
      val caller = async(Dispatchers.Default) {
        coordinator.authorizedCall(initial) { context ->
          context.withAccessToken { token ->
            if (token == "expired") {
              oldCallStarted.complete(Unit)
              releaseOldCall.await()
              throw AuthHttpException(401, "test")
            }
            token
          }
        }
      }

      oldCallStarted.await()
      val current = coordinator.rotate(initial, Session("already-fresh", "refresh-2"))!!
      releaseOldCall.complete(Unit)

      assertEquals("already-fresh", caller.await())
      assertEquals(0, refreshCalls.get())
      assertTrue(coordinator.isCurrent(current))
    } finally {
      scope.cancel()
    }
  }

  @Test fun `sync 401 enters the same refresh path`() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val refreshCalls = AtomicInteger()
    try {
      val coordinator = SessionCoordinator(
        refreshScope = scope,
        refreshSession = {
          refreshCalls.incrementAndGet()
          Session("fresh", "refresh-2")
        },
        commitRotation = {},
      )
      val initial = coordinator.install(Session("expired", "refresh"))
      val family = coordinator.selectFamily(initial, "family-1")!!

      val result = coordinator.authorizedCall(family) { context ->
        context.withFamilyAndAccessToken { familyId, token ->
          if (token == "expired") throw SyncHttpException(401)
          "$familyId:$token"
        }
      }

      assertEquals("family-1:fresh", result)
      assertEquals(1, refreshCalls.get())
      assertTrue(coordinator.isCurrent(family), "token rotation must preserve family generation")
    } finally {
      scope.cancel()
    }
  }

  private fun coordinator(scope: CoroutineScope): SessionCoordinator = SessionCoordinator(
    refreshScope = scope,
    refreshSession = { error("refresh not expected") },
    commitRotation = {},
  )
}
