package com.sloopworks.dayfold.android

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sloopworks.dayfold.client.AppState
import com.sloopworks.dayfold.client.DayfoldCommands
import com.sloopworks.dayfold.client.RestoreDetailStack
import com.sloopworks.dayfold.client.createAppStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.reduxkotlin.Store
import org.reduxkotlin.concurrent.NotificationContext
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class RuntimeRecreationTest {
  @Test fun retained_runtime_has_one_bridge_and_poller_and_ignores_stale_owner_pause() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val runtime = FakeRuntimeHandle()
    val creates = AtomicInteger()
    val viewModelStore = ViewModelStore()
    val factory = DayfoldRuntimeViewModel.Factory {
      creates.incrementAndGet()
      RetainedDayfoldRuntime(runtime, isFakeBackend = false)
    }

    lateinit var first: DayfoldRuntimeViewModel
    instrumentation.runOnMainSync {
      first = ViewModelProvider(viewModelStore, factory)[DayfoldRuntimeViewModel::class.java]
      first.store.dispatch(RestoreDetailStack(listOf("restored-detail")))
      first.start()
    }
    runBlocking { withTimeout(5_000L) { runtime.started.await() } }

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
  }
}
