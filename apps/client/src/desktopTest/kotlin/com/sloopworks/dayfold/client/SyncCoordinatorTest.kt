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
  @Test fun `requestSyncOnceAndAwait runs a pass while paused`() = runBlocking<Unit> {
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

    // The await variant only returns once its pass has finished, so the request has to be made
    // from another coroutine to observe the pass mid-flight.
    val accepted = CompletableDeferred<Boolean>()
    val awaiter = launch(Dispatchers.Default) {
      accepted.complete(coordinator.requestSyncOnceAndAwait(SyncReason.BACKGROUND))
    }
    assertEquals(SyncReason.BACKGROUND, withTimeout(2_000) { started.receive() })
    assertEquals(2, passCount.get())

    releases.send(Unit)
    withTimeout(2_000) { finished.receive() }
    assertTrue(withTimeout(2_000) { accepted.await() })
    awaiter.join()
    coordinator.close()
    owner.cancelAndJoin()
  }

  // The realistic race the CRITICAL review flagged: the app is paused WHILE its resume-triggered
  // pass is still running, and 100 concurrent background wakes land during that window. None may
  // start a second, concurrent syncPass call (ADR 0058's one-caller invariant) — they must all
  // conflate into the single pending slot the in-flight pass's own rerun loop drains afterward.
  @Test fun `requestSyncOnceAndAwait conflates into a pass already in flight rather than running twice`() = runBlocking<Unit> {
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

    // Each of these stays suspended until the pass servicing it finishes, so they cannot be joined
    // before the assertion below — give them room to register instead, which is the state under
    // test (100 requests outstanding while a pass is in flight).
    val oneShots = List(100) {
      launch(Dispatchers.Default) { assertTrue(coordinator.requestSyncOnceAndAwait(SyncReason.BACKGROUND)) }
    }
    delay(100)
    assertEquals(1, passCount.get())   // none of the 100 concurrent one-shots started a second pass

    releases.send(Unit)   // let pass #1 finish
    assertEquals(SyncReason.BACKGROUND, withTimeout(2_000) { started.receive() })   // one conflated rerun
    assertEquals(2, passCount.get())

    releases.send(Unit)   // let the conflated rerun finish, releasing all 100 waiters
    withTimeout(2_000) { oneShots.joinAll() }
    coordinator.pause()
    assertEquals(2, passCount.get())   // no third pass — 100 one-shots collapsed into one pending slot

    coordinator.close()
    owner.cancelAndJoin()
  }

  // requestSyncOnceAndAwait must not leave the coordinator polling: it exists to run ONE pass
  // without undoing pause()'s battery-saving effect. A short interval + a bounded wait with no
  // further pass proves the poller was never restarted (a restarted poller would have fired several
  // times in the wait window).
  @Test fun `requestSyncOnceAndAwait does not restart the poller`() = runBlocking<Unit> {
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

    assertTrue(coordinator.requestSyncOnceAndAwait(SyncReason.BACKGROUND))
    assertEquals(SyncReason.BACKGROUND, withTimeout(2_000) { started.receive() })
    assertEquals(2, passCount.get())

    // 10x the poll interval: if the one-shot had restarted the poller, several more POLL
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

    val oneShot = launch(Dispatchers.Default) {
      assertTrue(coordinator.requestSyncOnceAndAwait(SyncReason.BACKGROUND))
    }
    assertEquals(SyncReason.BACKGROUND, withTimeout(2_000) { started.receive() })
    releases.send(Unit)
    withTimeout(2_000) { oneShot.join() }

    coordinator.resume(ownerScope)
    assertEquals(SyncReason.RESUME, withTimeout(2_000) { started.receive() })
    assertEquals(3, passCount.get())
    releases.send(Unit)

    coordinator.pause()
    coordinator.close()
    owner.cancelAndJoin()
  }

  // Mirrors "close cancels an active pass and rejects late requests" for the one-shot entry point:
  // a closed coordinator must reject requestSyncOnceAndAwait exactly like requestSync, and must do
  // so without suspending — a rejected request has no pass to wait for.
  @Test fun `requestSyncOnceAndAwait is rejected after close`() = runBlocking<Unit> {
    val owner = SupervisorJob()
    val ownerScope = CoroutineScope(owner + Dispatchers.Default)
    val coordinator = SyncCoordinator(syncPass = { _, _ -> }, pollIntervalMs = Long.MAX_VALUE)

    coordinator.resume(ownerScope)
    coordinator.close()

    assertFalse(withTimeout(2_000) { coordinator.requestSyncOnceAndAwait(SyncReason.BACKGROUND) })
    owner.cancelAndJoin()
  }

  // IMPORTANT review finding — DayfoldRuntimeGraph.requestBackgroundSync() delegates through this
  // method, and its `suspend` return is what a headless caller (WorkManager, BGAppRefreshTask)
  // uses to decide it's safe to report task completion. Merely ARMING a one-shot returns instantly,
  // long before the pass it armed has run — this proves this method genuinely blocks until ITS pass
  // has finished, not merely until it was armed.
  @Test fun `requestSyncOnceAndAwait suspends until the pass it triggers actually finishes`() = runBlocking<Unit> {
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

    val awaitReturned = CompletableDeferred<Unit>()
    val awaiter = launch(Dispatchers.Default) {
      coordinator.requestSyncOnceAndAwait(SyncReason.BACKGROUND)
      awaitReturned.complete(Unit)
    }
    assertEquals(SyncReason.BACKGROUND, withTimeout(2_000) { started.receive() })

    // The BACKGROUND pass is running but has not released yet — requestSyncOnceAndAwait must
    // still be suspended, exactly the bug the review flagged for the non-suspending version.
    delay(50)
    assertFalse(awaitReturned.isCompleted, "must not return before its own pass has finished")

    releases.send(Unit)
    withTimeout(2_000) { finished.receive() }
    withTimeout(2_000) { awaitReturned.await() }
    awaiter.join()

    assertEquals(2, passCount.get())
    coordinator.close()
    owner.cancelAndJoin()
  }

  // The conflation case the review specifically asked to be covered: a waiter that registers
  // while an EARLIER pass is already in flight must not be satisfied by that earlier pass — only
  // by the conflated rerun that actually claims its request.
  @Test fun `requestSyncOnceAndAwait completes only when the conflated rerun finishes, not the pass already in flight`() =
    runBlocking<Unit> {
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
      coordinator.pause()   // paused mid-pass — the same window the earlier CRITICAL review flagged

      val awaitReturned = CompletableDeferred<Unit>()
      val awaiter = launch(Dispatchers.Default) {
        coordinator.requestSyncOnceAndAwait(SyncReason.BACKGROUND)
        awaitReturned.complete(Unit)
      }
      delay(50)   // let the registration run while pass #1 is still holding releases.receive()
      assertFalse(
        awaitReturned.isCompleted,
        "must not complete from pass #1, which started before this request registered",
      )

      releases.send(Unit)   // let pass #1 finish
      assertEquals(SyncReason.BACKGROUND, withTimeout(2_000) { started.receive() })   // conflated rerun
      assertEquals(2, passCount.get())

      delay(50)
      assertFalse(
        awaitReturned.isCompleted,
        "must not complete until the conflated rerun itself finishes",
      )

      releases.send(Unit)   // let the conflated rerun (the one servicing this request) finish
      withTimeout(2_000) { awaitReturned.await() }
      awaiter.join()

      coordinator.pause()
      assertEquals(2, passCount.get())
      coordinator.close()
      owner.cancelAndJoin()
    }

  // The other race the review named explicitly: close() must not leave an outstanding
  // requestSyncOnceAndAwait caller hanging forever when the pass that would have serviced it
  // never gets to run because the coordinator shuts down first.
  @Test fun `close fails an outstanding requestSyncOnceAndAwait rather than hanging it`() = runBlocking<Unit> {
    val owner = SupervisorJob()
    val ownerScope = CoroutineScope(owner + Dispatchers.Default)
    val started = CompletableDeferred<Unit>()
    val coordinator = SyncCoordinator(syncPass = { _, _ ->
      started.complete(Unit)
      CompletableDeferred<Unit>().await()   // never releases on its own — close() must cut this off
    }, pollIntervalMs = Long.MAX_VALUE)

    coordinator.resume(ownerScope)
    withTimeout(2_000) { started.await() }   // the RESUME pass is in flight and will never finish itself
    coordinator.pause()

    val outcome = CompletableDeferred<Result<Boolean>>()
    val awaiter = launch(Dispatchers.Default) {
      outcome.complete(runCatching { coordinator.requestSyncOnceAndAwait(SyncReason.BACKGROUND) })
    }
    // Give the registration a moment, then close while the request is still unclaimed — the
    // conflated rerun that would have serviced it will now never run.
    delay(50)
    coordinator.close()

    val result = withTimeout(2_000) { outcome.await() }
    assertTrue(result.isFailure, "an outstanding waiter must fail, not hang, when close() races it")
    awaiter.join()
    owner.cancelAndJoin()
  }

  // Review round 2, finding A (regression) — RuntimeHandleHolder
  // register a delegate as soon as a runtime graph is CONSTRUCTED, before any resume() call binds
  // a family session. A delegated background wake into a live-but-signed-out (or pre-family-bind)
  // runtime therefore calls requestSyncOnceAndAwait with no worker ever created. Before this fix,
  // that state still registered a waiter and awaited it forever (until the caller's own outer
  // budget timeout), burning the ENTIRE wake budget doing nothing. A tight withTimeout here proves
  // it now returns promptly instead.
  @Test fun `requestSyncOnceAndAwait returns immediately when no worker has ever been created`() = runBlocking<Unit> {
    val passCount = AtomicInteger()
    val coordinator = SyncCoordinator(syncPass = { _, _ -> passCount.incrementAndGet() }, pollIntervalMs = Long.MAX_VALUE)

    // resume() is never called — active is null for the lifetime of this coordinator.
    val result = withTimeout(200) { coordinator.requestSyncOnceAndAwait(SyncReason.BACKGROUND) }

    assertTrue(result)
    assertEquals(0, passCount.get())   // nothing ran — there was no worker to run it, and none should
    coordinator.close()
  }
}
