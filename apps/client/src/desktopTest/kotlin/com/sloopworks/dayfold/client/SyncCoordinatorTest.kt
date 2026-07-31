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
import kotlinx.coroutines.delay
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

  // ADR 0020 R3 — a headless background wake finds the runtime already live but PAUSED (the
  // common case: app backgrounded, no Activity foreground) and must still get its one pass run.
  // pause() only ever stopped the 45s poll loop for battery, never refused work outright.
  @Test fun `requestSyncOnce runs a pass while paused`() = runBlocking<Unit> {
    val owner = SupervisorJob()
    val ownerScope = CoroutineScope(owner + Dispatchers.Default)
    val passCount = AtomicInteger()
    val started = Channel<SyncReason>(Channel.UNLIMITED)
    val releases = Channel<Unit>(Channel.UNLIMITED)
    val finished = Channel<Unit>(Channel.UNLIMITED)
    val coordinator = SyncCoordinator(syncPass = { reason, _ ->
      started.send(reason)
      passCount.incrementAndGet()
      releases.receive()
      finished.send(Unit)
    }, pollIntervalMs = Long.MAX_VALUE)

    coordinator.resume(ownerScope)
    assertEquals(SyncReason.RESUME, withTimeout(2_000) { started.receive() })
    releases.send(Unit)
    withTimeout(2_000) { finished.receive() }   // worker back to idle/parked before pausing
    coordinator.pause()

    assertTrue(coordinator.requestSyncOnce(SyncReason.BACKGROUND))
    assertEquals(SyncReason.BACKGROUND, withTimeout(2_000) { started.receive() })
    assertEquals(2, passCount.get())

    releases.send(Unit)
    withTimeout(2_000) { finished.receive() }
    coordinator.close()
    owner.cancelAndJoin()
  }

  // The realistic race the CRITICAL review flagged: the app is paused WHILE its resume-triggered
  // pass is still running, and 100 concurrent background wakes land during that window. None may
  // start a second, concurrent syncPass call (ADR 0058's one-caller invariant) — they must all
  // conflate into the single pending slot the in-flight pass's own rerun loop drains afterward.
  @Test fun `requestSyncOnce conflates into a pass already in flight rather than running twice`() = runBlocking<Unit> {
    val owner = SupervisorJob()
    val ownerScope = CoroutineScope(owner + Dispatchers.Default)
    val passCount = AtomicInteger()
    val started = Channel<SyncReason>(Channel.UNLIMITED)
    val releases = Channel<Unit>(Channel.UNLIMITED)
    val coordinator = SyncCoordinator(syncPass = { reason, _ ->
      started.send(reason)
      passCount.incrementAndGet()
      releases.receive()
    }, pollIntervalMs = Long.MAX_VALUE)

    coordinator.resume(ownerScope)
    assertEquals(SyncReason.RESUME, withTimeout(2_000) { started.receive() })   // pass #1 in flight
    coordinator.pause()   // paused mid-pass — the exact window the review called out

    coroutineScope {
      List(100) {
        launch(Dispatchers.Default) { assertTrue(coordinator.requestSyncOnce(SyncReason.BACKGROUND)) }
      }.joinAll()
    }
    assertEquals(1, passCount.get())   // none of the 100 concurrent one-shots started a second pass

    releases.send(Unit)   // let pass #1 finish
    assertEquals(SyncReason.BACKGROUND, withTimeout(2_000) { started.receive() })   // one conflated rerun
    assertEquals(2, passCount.get())

    releases.send(Unit)
    coordinator.pause()
    assertEquals(2, passCount.get())   // no third pass — 100 one-shots collapsed into one pending slot

    coordinator.close()
    owner.cancelAndJoin()
  }

  // requestSyncOnce must not leave the coordinator polling: it exists to run ONE pass without
  // undoing pause()'s battery-saving effect. A short interval + a bounded wait with no further
  // pass proves the poller was never restarted (a restarted poller would have fired several times
  // in the wait window).
  @Test fun `requestSyncOnce does not restart the poller`() = runBlocking<Unit> {
    val owner = SupervisorJob()
    val ownerScope = CoroutineScope(owner + Dispatchers.Default)
    val passCount = AtomicInteger()
    val started = Channel<SyncReason>(Channel.UNLIMITED)
    val coordinator = SyncCoordinator(syncPass = { reason, _ ->
      started.send(reason)
      passCount.incrementAndGet()
    }, pollIntervalMs = 20L)

    coordinator.resume(ownerScope)
    assertEquals(SyncReason.RESUME, withTimeout(2_000) { started.receive() })
    coordinator.pause()

    assertTrue(coordinator.requestSyncOnce(SyncReason.BACKGROUND))
    assertEquals(SyncReason.BACKGROUND, withTimeout(2_000) { started.receive() })
    assertEquals(2, passCount.get())

    // 10x the poll interval: if requestSyncOnce had restarted the poller, several more POLL
    // passes would have fired by now. pause() must still mean "polling is off."
    delay(200)
    assertEquals(2, passCount.get())

    coordinator.close()
    owner.cancelAndJoin()
  }

  // A subsequent resume() must behave exactly as it always has — oneShotArmed must not leave any
  // residue that changes resume()'s own pending/pendingReason or poller-restart logic.
  @Test fun `resume after a paused one-shot still runs its own resume pass`() = runBlocking<Unit> {
    val owner = SupervisorJob()
    val ownerScope = CoroutineScope(owner + Dispatchers.Default)
    val passCount = AtomicInteger()
    val started = Channel<SyncReason>(Channel.UNLIMITED)
    val releases = Channel<Unit>(Channel.UNLIMITED)
    val coordinator = SyncCoordinator(syncPass = { reason, _ ->
      started.send(reason)
      passCount.incrementAndGet()
      releases.receive()
    }, pollIntervalMs = Long.MAX_VALUE)

    coordinator.resume(ownerScope)
    assertEquals(SyncReason.RESUME, withTimeout(2_000) { started.receive() })
    releases.send(Unit)
    coordinator.pause()

    assertTrue(coordinator.requestSyncOnce(SyncReason.BACKGROUND))
    assertEquals(SyncReason.BACKGROUND, withTimeout(2_000) { started.receive() })
    releases.send(Unit)

    coordinator.resume(ownerScope)
    assertEquals(SyncReason.RESUME, withTimeout(2_000) { started.receive() })
    assertEquals(3, passCount.get())
    releases.send(Unit)

    coordinator.pause()
    coordinator.close()
    owner.cancelAndJoin()
  }

  // Mirrors "close cancels an active pass and rejects late requests" for the new entry point:
  // a closed coordinator must reject requestSyncOnce exactly like requestSync.
  @Test fun `requestSyncOnce is rejected after close`() = runBlocking<Unit> {
    val owner = SupervisorJob()
    val ownerScope = CoroutineScope(owner + Dispatchers.Default)
    val coordinator = SyncCoordinator(syncPass = { _, _ -> }, pollIntervalMs = Long.MAX_VALUE)

    coordinator.resume(ownerScope)
    coordinator.close()

    assertFalse(coordinator.requestSyncOnce(SyncReason.BACKGROUND))
    owner.cancelAndJoin()
  }
}
