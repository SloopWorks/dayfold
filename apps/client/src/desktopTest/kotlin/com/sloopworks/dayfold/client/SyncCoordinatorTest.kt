package com.sloopworks.dayfold.client

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncCoordinatorTest {
  @Test fun `100 concurrent requests produce one active pass and one rerun`() = runBlocking<Unit> {
    val owner = SupervisorJob()
    val ownerScope = CoroutineScope(owner + Dispatchers.Default)
    val passCount = AtomicInteger()
    val started = Channel<Int>(Channel.UNLIMITED)
    val releases = Channel<Unit>(Channel.UNLIMITED)
    val coordinator = SyncCoordinator(syncPass = { _, _ ->
      started.send(passCount.incrementAndGet())
      releases.receive()
    }, pollIntervalMs = Long.MAX_VALUE)

    coordinator.resume(ownerScope)
    assertEquals(1, withTimeout(2_000) { started.receive() })
    coroutineScope {
      List(100) {
        launch(Dispatchers.Default) {
          assertTrue(coordinator.requestSync(SyncReason.MANUAL_REFRESH))
        }
      }.joinAll()
    }

    releases.send(Unit)
    assertEquals(2, withTimeout(2_000) { started.receive() })
    releases.send(Unit)
    coordinator.pause()
    assertEquals(2, passCount.get())

    coordinator.close()
    owner.cancelAndJoin()
  }

  @Test fun `requests during the rerun schedule only one further pass`() = runBlocking<Unit> {
    val owner = SupervisorJob()
    val ownerScope = CoroutineScope(owner + Dispatchers.Default)
    val passCount = AtomicInteger()
    val started = Channel<Int>(Channel.UNLIMITED)
    val releases = Channel<Unit>(Channel.UNLIMITED)
    val coordinator = SyncCoordinator(syncPass = { _, _ ->
      started.send(passCount.incrementAndGet())
      releases.receive()
    }, pollIntervalMs = Long.MAX_VALUE)

    coordinator.resume(ownerScope)
    assertEquals(1, withTimeout(2_000) { started.receive() })
    repeat(100) { coordinator.requestSync(SyncReason.OUTBOX_MUTATION) }
    releases.send(Unit)
    assertEquals(2, withTimeout(2_000) { started.receive() })
    repeat(100) { coordinator.requestSync(SyncReason.PUSH) }
    releases.send(Unit)
    assertEquals(3, withTimeout(2_000) { started.receive() })
    releases.send(Unit)
    coordinator.pause()
    assertEquals(3, passCount.get())

    coordinator.close()
    owner.cancelAndJoin()
  }

  @Test fun `pause holds a pending rerun and resume releases it`() = runBlocking<Unit> {
    val owner = SupervisorJob()
    val ownerScope = CoroutineScope(owner + Dispatchers.Default)
    val started = Channel<Int>(Channel.UNLIMITED)
    val releases = Channel<Unit>(Channel.UNLIMITED)
    val finished = Channel<Unit>(Channel.UNLIMITED)
    val passCount = AtomicInteger()
    val coordinator = SyncCoordinator(syncPass = { _, _ ->
      started.send(passCount.incrementAndGet())
      releases.receive()
      finished.send(Unit)
    }, pollIntervalMs = Long.MAX_VALUE)

    coordinator.resume(ownerScope)
    assertEquals(1, withTimeout(2_000) { started.receive() })
    coordinator.requestSync(SyncReason.BACKGROUND)
    coordinator.pause()
    releases.send(Unit)
    withTimeout(2_000) { finished.receive() }
    assertEquals(1, passCount.get())

    coordinator.resume(ownerScope)
    assertEquals(2, withTimeout(2_000) { started.receive() })
    releases.send(Unit)
    coordinator.close()
    owner.cancelAndJoin()
  }

  @Test fun `close cancels an active pass and rejects late requests`() = runBlocking<Unit> {
    val owner = SupervisorJob()
    val ownerScope = CoroutineScope(owner + Dispatchers.Default)
    val started = CompletableDeferred<Unit>()
    val cancelled = CompletableDeferred<Unit>()
    var mappedToFailure = false
    val coordinator = SyncCoordinator(syncPass = { _, _ ->
      started.complete(Unit)
      try {
        CompletableDeferred<Unit>().await()
      } catch (error: CancellationException) {
        cancelled.complete(Unit)
        throw error
      } catch (error: Exception) {
        mappedToFailure = true
      }
    }, pollIntervalMs = Long.MAX_VALUE)

    coordinator.resume(ownerScope)
    withTimeout(2_000) { started.await() }
    coordinator.close()
    withTimeout(2_000) { cancelled.await() }

    assertFalse(mappedToFailure)
    assertFalse(coordinator.requestSync(SyncReason.MANUAL_REFRESH))
    owner.cancelAndJoin()
  }

  @Test fun `resume recovers a worker after unexpected termination`() = runBlocking<Unit> {
    val owner = SupervisorJob()
    val failureObserved = CompletableDeferred<Unit>()
    val ownerScope = CoroutineScope(
      owner + Dispatchers.Default + CoroutineExceptionHandler { _, _ ->
        failureObserved.complete(Unit)
      },
    )
    val passCount = AtomicInteger()
    val recovered = CompletableDeferred<Unit>()
    val coordinator = SyncCoordinator(syncPass = { _, _ ->
      if (passCount.incrementAndGet() == 1) error("unexpected worker failure")
      recovered.complete(Unit)
    }, pollIntervalMs = Long.MAX_VALUE)

    coordinator.resume(ownerScope)
    withTimeout(2_000) { failureObserved.await() }
    coordinator.requestSync(SyncReason.PUSH)
    coordinator.resume(ownerScope)
    withTimeout(2_000) { recovered.await() }

    assertEquals(2, passCount.get())
    coordinator.close()
    owner.cancelAndJoin()
  }
}
