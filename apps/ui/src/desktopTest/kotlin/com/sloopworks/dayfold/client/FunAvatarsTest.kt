package com.sloopworks.dayfold.client

import com.sloopworks.dayfold.client.ui.FunAvatars
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FunAvatarsTest {
  @Test fun resolvesKnownId() { assertEquals("avatar:flower-01", FunAvatars.resolve("avatar:flower-01")?.id) }
  @Test fun unknownIsNull() { assertNull(FunAvatars.resolve("avatar:nope")) }
  @Test fun nullIsNull() { assertNull(FunAvatars.resolve(null)) }
  @Test fun everyEntryHasA11yName() { assertTrue(FunAvatars.all.all { it.name.isNotBlank() }) }
  @Test fun idsUnique() { assertEquals(FunAvatars.all.size, FunAvatars.all.map { it.id }.toSet().size) }
}
