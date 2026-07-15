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
import kotlin.test.assertTrue

class IosContentStoreHolderTest {
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

  private companion object {
    const val CALLER_COUNT = 32
  }
}
