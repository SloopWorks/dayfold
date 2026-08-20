package com.sloopworks.dayfold.client

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.inMemoryDriver
import com.sloopworks.dayfold.client.db.ContentDb
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosContentStoreHolderTest {
  @Test fun notification_post_fence_rejects_a_batch_invalidated_by_teardown() {
    val outgoing = IosNotificationPostFence.begin()!!
    var recorded = false

    IosNotificationPostFence.invalidate()

    assertFalse(IosNotificationPostFence.isCurrent(outgoing))
    assertFalse(IosNotificationPostFence.completeIfCurrent(outgoing) { recorded = true })
    assertFalse(recorded)

    val current = IosNotificationPostFence.begin()!!
    assertTrue(IosNotificationPostFence.completeIfCurrent(current) { recorded = true })
    assertTrue(recorded)
  }

  @Test fun notification_post_fence_keeps_admission_closed_through_cache_cleanup() {
    val outgoing = IosNotificationPostFence.begin()!!

    IosNotificationPostFence.closeAdmission()
    try {
      assertEquals(null, IosNotificationPostFence.begin())
      assertFalse(IosNotificationPostFence.completeIfCurrent(outgoing) { error("stale") })
    } finally {
      IosNotificationPostFence.openAdmission()
    }

    assertTrue(IosNotificationPostFence.begin() != null)
  }

  @Test fun notification_post_fence_prepares_expiration_before_invalidating() {
    val outgoing = IosNotificationPostFence.begin()!!
    var preparedGeneration: Long? = null

    val invalidated = IosNotificationPostFence.invalidateIf { nextGeneration ->
      preparedGeneration = nextGeneration
      true
    }

    assertEquals(invalidated, preparedGeneration)
    assertFalse(IosNotificationPostFence.completeIfCurrent(outgoing) { error("stale") })
  }

  @Test fun concurrent_get_invokes_factory_once_and_returns_one_instance() = runBlocking<Unit> {
    val drivers = mutableListOf<SqlDriver>()
    var factoryCalls = 0
    val holder = SynchronizedContentStoreHolder {
      factoryCalls += 1
      val driver = inMemoryDriver(ContentDb.Schema)
      drivers += driver
      ContentStore(driver)
    }

    try {
      val ready = Channel<Unit>(capacity = CALLER_COUNT)
      val start = CompletableDeferred<Unit>()
      val stores = List(CALLER_COUNT) {
        async(Dispatchers.Default) {
          ready.send(Unit)
          start.await()
          holder.get()
        }
      }

      repeat(CALLER_COUNT) { ready.receive() }
      start.complete(Unit)
      val resolvedStores = stores.awaitAll()

      assertEquals(1, factoryCalls)
      assertTrue(resolvedStores.all { it === resolvedStores.first() })
    } finally {
      drivers.forEach(SqlDriver::close)
    }
  }

  @Test fun independent_holders_do_not_share_instances_or_factory_state() {
    val firstDriver = inMemoryDriver(ContentDb.Schema)
    val secondDriver = inMemoryDriver(ContentDb.Schema)
    var firstFactoryCalls = 0
    var secondFactoryCalls = 0
    val firstHolder = SynchronizedContentStoreHolder {
      firstFactoryCalls += 1
      ContentStore(firstDriver)
    }
    val secondHolder = SynchronizedContentStoreHolder {
      secondFactoryCalls += 1
      ContentStore(secondDriver)
    }

    try {
      val first = firstHolder.get()

      assertTrue(first === firstHolder.get())
      assertTrue(first !== secondHolder.get())
      assertEquals(1, firstFactoryCalls)
      assertEquals(1, secondFactoryCalls)
    } finally {
      firstDriver.close()
      secondDriver.close()
    }
  }

  @Test fun selected_holder_routes_callbacks_to_the_foreground_store() {
    val fallbackDriver = inMemoryDriver(ContentDb.Schema)
    val selectedDriver = inMemoryDriver(ContentDb.Schema)
    val fallback = ContentStore(fallbackDriver)
    val selected = ContentStore(selectedDriver)
    val holder = SelectableContentStoreHolder { fallback }

    try {
      assertTrue(holder.get() === fallback)
      holder.select(selected)
      assertTrue(holder.get() === selected)
    } finally {
      fallbackDriver.close()
      selectedDriver.close()
    }
  }

  private companion object {
    const val CALLER_COUNT = 32
  }
}
