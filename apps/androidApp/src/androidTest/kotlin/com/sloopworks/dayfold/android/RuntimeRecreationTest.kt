package com.sloopworks.dayfold.android

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sloopworks.dayfold.client.RuntimeHandleHolder
import com.sloopworks.dayfold.client.AppState
import com.sloopworks.dayfold.client.DayfoldCommands
import com.sloopworks.dayfold.client.RestoreDetailStack
import com.sloopworks.dayfold.client.createAppStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.reduxkotlin.Store
import org.reduxkotlin.concurrent.NotificationContext
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class RuntimeRecreationTest {
  // RuntimeHandleHolder is process-wide static state (by design — it must be reachable from a
  // WorkManager worker with no injected graph). Tests share the instrumented app process, so start
  // each test from a known-clear slate rather than trusting whatever a previous test left behind.
  // clear() is compare-and-clear, so the reset passes back whatever is currently registered.
  @Before fun clearRuntimeHandleHolder() {
    RuntimeHandleHolder.clear(RuntimeHandleHolder.get())
  }

  @Test fun retained_runtime_has_one_bridge_and_poller_and_ignores_stale_owner_pause() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val runtime = FakeRuntimeHandle()
    val creates = AtomicInteger()
    val viewModelStore = ViewModelStore()
    val factory = DayfoldRuntimeViewModel.Factory {
      creates.incrementAndGet()
      RetainedDayfoldRuntime(runtime, isFakeBackend = false)
    }

    assertNull("no runtime retained yet — the holder must start clear", RuntimeHandleHolder.get())

    lateinit var first: DayfoldRuntimeViewModel
    instrumentation.runOnMainSync {
      first = ViewModelProvider(viewModelStore, factory)[DayfoldRuntimeViewModel::class.java]
      first.store.dispatch(RestoreDetailStack(listOf("restored-detail")))
      first.start()
    }
    runBlocking { withTimeout(5_000L) { runtime.started.await() } }

    // ADR 0020 R3 — registered as soon as the retained runtime exists, before start()/resume()
    // are ever called by an Activity. A headless worker delegating through this handle must reach
    // THIS runtime, not a stand-in.
    val delegate = RuntimeHandleHolder.get()
    assertNotNull("the retained runtime must register itself on construction", delegate)
    runBlocking { delegate!!.invoke() }
    assertEquals(
      "the registered delegate must call through to the SAME runtime's requestBackgroundSync",
      1,
      runtime.requestBackgroundSyncCalls.get(),
    )

    lateinit var recreated: DayfoldRuntimeViewModel
    instrumentation.runOnMainSync {
      recreated = ViewModelProvider(
        viewModelStore,
        DayfoldRuntimeViewModel.Factory { error("recreation must reuse the retained runtime") },
      )[DayfoldRuntimeViewModel::class.java]
    }

    assertSame(first, recreated)
    assertEquals(1, creates.get())
    assertEquals("one runtime means one device bridge subscription", 1, runtime.startCalls.get())
    assertEquals(listOf("restored-detail"), runtime.detailStackAtStart)
    assertTrue(first.consumeInitialStateRestore())
    assertFalse(recreated.consumeInitialStateRestore())

    val oldHost = first.attachHost()
    runBlocking { first.resume(oldHost) }
    val newHost = recreated.attachHost()
    runBlocking {
      recreated.resume(newHost)
      first.pause(oldHost)
    }

    assertEquals(1, runtime.activePollers.get())
    assertEquals(1, runtime.maxActivePollers.get())
    runBlocking { recreated.pause(newHost) }
    assertEquals(0, runtime.activePollers.get())

    instrumentation.runOnMainSync { viewModelStore.clear() }
    runBlocking { withTimeout(5_000L) { first.close().join() } }
    assertEquals(1, runtime.cancelCalls.get())
    assertEquals(1, runtime.awaitClosedCalls.get())
    // Clear-on-teardown is the property the whole delegation design rests on (ADR 0020 R3): a
    // stale handle would make a later worker wake delegate into a runtime that will never finish
    // the pass — worse than no delegate at all.
    assertNull("teardown must clear the handle, not just cancel the runtime", RuntimeHandleHolder.get())
  }

  // Exercises teardown paths beyond the single happy-path close() above: clearing must be
  // idempotent (a second close() must not throw or resurrect the handle), and a brand-new
  // runtime constructed after teardown must register cleanly with no interference from the one
  // that was just cleared.
  @Test fun `close clears the handle idempotently and a later runtime re-registers cleanly`() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val firstRuntime = FakeRuntimeHandle()
    val firstStore = ViewModelStore()
    val firstFactory = DayfoldRuntimeViewModel.Factory { RetainedDayfoldRuntime(firstRuntime, isFakeBackend = false) }

    lateinit var first: DayfoldRuntimeViewModel
    instrumentation.runOnMainSync {
      first = ViewModelProvider(firstStore, firstFactory)[DayfoldRuntimeViewModel::class.java]
    }
    assertNotNull(RuntimeHandleHolder.get())

    // Idempotent: close() twice (mirrors onCleared() being invoked more than once, or a caller
    // calling close() directly ahead of the ViewModelStore's own teardown) must not throw and
    // must leave the handle cleared, not resurrect or double-register it.
    runBlocking { withTimeout(5_000L) { first.close().join() } }
    assertNull(RuntimeHandleHolder.get())
    runBlocking { withTimeout(5_000L) { first.close().join() } }
    assertNull(RuntimeHandleHolder.get())

    // A second, unrelated runtime constructed afterward must register itself with no leftover
    // state from the first — the holder is a single global slot, not a stack.
    val secondRuntime = FakeRuntimeHandle()
    val secondStore = ViewModelStore()
    val secondFactory = DayfoldRuntimeViewModel.Factory { RetainedDayfoldRuntime(secondRuntime, isFakeBackend = false) }
    lateinit var second: DayfoldRuntimeViewModel
    instrumentation.runOnMainSync {
      second = ViewModelProvider(secondStore, secondFactory)[DayfoldRuntimeViewModel::class.java]
    }

    val delegate = RuntimeHandleHolder.get()
    assertNotNull(delegate)
    runBlocking { delegate!!.invoke() }
    assertEquals(1, secondRuntime.requestBackgroundSyncCalls.get())
    assertEquals("the first (cleared) runtime must never receive a late delegate call", 0, firstRuntime.requestBackgroundSyncCalls.get())

    instrumentation.runOnMainSync { secondStore.clear() }
    runBlocking { withTimeout(5_000L) { second.close().join() } }
    assertNull(RuntimeHandleHolder.get())
  }

  private class FakeRuntimeHandle : DayfoldRuntimeHandle {
    override val store: Store<AppState> = createAppStore(
      notificationContext = NotificationContext { block -> block() },
      debug = false,
    )
    override val commands: DayfoldCommands = DayfoldCommands.navigationOnly(store)
    val started = CompletableDeferred<Unit>()
    val startCalls = AtomicInteger()
    val activePollers = AtomicInteger()
    val maxActivePollers = AtomicInteger()
    val cancelCalls = AtomicInteger()
    val awaitClosedCalls = AtomicInteger()
    val requestBackgroundSyncCalls = AtomicInteger()
    var detailStackAtStart: List<String> = emptyList()
    private val closed = CompletableDeferred<Unit>()

    override suspend fun start() {
      detailStackAtStart = store.state.navigation.detailStack
      startCalls.incrementAndGet()
      started.complete(Unit)
    }

    override suspend fun resume() {
      if (activePollers.compareAndSet(0, 1)) {
        maxActivePollers.updateAndGet { current -> maxOf(current, 1) }
      }
    }

    override suspend fun pause() {
      activePollers.compareAndSet(1, 0)
    }

    override fun cancel() {
      cancelCalls.incrementAndGet()
      closed.complete(Unit)
    }

    override suspend fun awaitClosed() {
      awaitClosedCalls.incrementAndGet()
      closed.await()
    }

    override suspend fun requestBackgroundSync() {
      requestBackgroundSyncCalls.incrementAndGet()
    }
  }
}
