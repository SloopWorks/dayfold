package com.sloopworks.dayfold.client

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking

// ADR 0020 R3 — the holder is a single process-global slot whose ONE job is to make a headless wake
// find the live runtime instead of becoming a second, independent refresher (which trips the server's
// refresh-token reuse detection and signs the user out). It lives in commonMain, so both hosts —
// Android's retained ViewModel and iOS's MainViewController — are covered by these tests.
class RuntimeHandleHolderTest {

  // Process-global state shared across tests in this module: start and end from a known-clear slate.
  @BeforeTest fun clearBefore() = RuntimeHandleHolder.clear(RuntimeHandleHolder.get())
  @AfterTest fun clearAfter() = RuntimeHandleHolder.clear(RuntimeHandleHolder.get())

  @Test fun `get returns the registered delegate and null once it is cleared`() = runBlocking {
    assertNull(RuntimeHandleHolder.get(), "must start clear")
    var calls = 0
    val registration = RuntimeHandleHolder.register { calls++ }

    assertNotNull(RuntimeHandleHolder.get()).invoke()
    assertEquals(1, calls, "the handle must call through to the runtime that registered it")

    RuntimeHandleHolder.clear(registration)
    assertNull(RuntimeHandleHolder.get())
  }

  // The finding this compare-and-clear exists for: registration and teardown are far apart in both
  // hosts (iOS registers in MainViewController() and clears in the composition's onDispose), so a
  // REPLACEMENT can register before the outgoing one disposes. An unconditional clear would wipe the
  // live new registration, and the next wake — finding no delegate — would sync independently.
  @Test fun `a late teardown must not clear a newer runtime's live registration`() = runBlocking {
    var firstCalls = 0
    var secondCalls = 0
    val first = RuntimeHandleHolder.register { firstCalls++ }
    val second = RuntimeHandleHolder.register { secondCalls++ }   // replacement registers first...

    RuntimeHandleHolder.clear(first)                              // ...then the outgoing one disposes

    assertSame(second, RuntimeHandleHolder.get(), "the live registration must survive a stale clear")
    assertNotNull(RuntimeHandleHolder.get()).invoke()
    assertEquals(0, firstCalls, "the torn-down runtime must never receive a delegated pass")
    assertEquals(1, secondCalls)
  }

  // Teardown is called more than once in practice (onCleared plus an explicit close, a re-entered
  // onDispose). Clearing must stay idempotent and must not resurrect anything.
  @Test fun `clearing twice is a no-op and clearing an unregistered token is safe`() {
    val registration = RuntimeHandleHolder.register { }

    RuntimeHandleHolder.clear(registration)
    RuntimeHandleHolder.clear(registration)
    assertNull(RuntimeHandleHolder.get())

    val later = RuntimeHandleHolder.register { }
    RuntimeHandleHolder.clear(registration)   // a token that was never the live one
    assertSame(later, RuntimeHandleHolder.get())
  }
}
