package com.sloopworks.dayfold.swip

import com.sloopworks.dayfold.client.AppState
import com.sloopworks.dayfold.client.Session
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.reduxkotlin.createStore
import works.sloop.swip.bugreport.lane.Clock
import works.sloop.swip.rk.recorder.RecorderConfig
import works.sloop.swip.rk.recorder.ReduxTimelineRecorder
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** docs/12 §6: product-owned sanitizer leak test over SALTED real state. */
class DayfoldLeakTest {
  private val salted = AppState(
    session = Session(access = "eyJSALTEDJWTACCESS", refresh = "eyJSALTEDREFRESH", userId = "u_salted"),
    myDisplayName = "Salted Q. User",
    hubFilter = "salted-search someone@example.com padding-padding-padding", // synthetic: real values are chip literals; salt proves the fence anyway
    detailStack = listOf("card_salt_1"),
  )

  @Test fun journal_never_contains_salted_pii() = runTest {
    val rec = ReduxTimelineRecorder(
      specs = dayfoldSlices(),
      sanitizer = dayfoldSanitizer,
      config = RecorderConfig(appVersion = "test"),
      clock = Clock { 0L },
      scope = this,
    )
    val store = createStore({ s: AppState, _: Any -> s.copy(syncing = !s.syncing) }, salted, rec.enhancer())
    rec.activate()
    repeat(3) { store.dispatch("tick"); advanceUntilIdle() }
    val text = rec.freeze()!!.journalJson.decodeToString() + rec.freeze()!!.finalStateJson.decodeToString()
    rec.deactivate()
    // secrets/PII salts must be absent
    assertFalse("eyJSALTED" in text)
    assertFalse("Salted Q. User" in text)
    assertFalse("someone@example.com" in text)  // hubFilter carried an email → sanitizer drops it
    assertFalse("u_salted" in text)
    // pseudonymous + derived slices ARE present (registry works)
    assertTrue("card_salt_1" in text)     // detailStack ids allowed (internal debug)
    assertTrue("cardsCount" in text)
  }

  @Test fun hub_filter_without_pii_is_truncated_not_dropped() = runTest {
    val rec = ReduxTimelineRecorder(
      specs = dayfoldSlices(), sanitizer = dayfoldSanitizer,
      config = RecorderConfig(appVersion = "test"), clock = Clock { 0L }, scope = this,
    )
    val longFilter = "x".repeat(100)
    val store = createStore({ s: AppState, _: Any -> s }, AppState(hubFilter = longFilter), rec.enhancer())
    rec.activate()
    store.dispatch("tick"); advanceUntilIdle()
    val text = rec.freeze()!!.journalJson.decodeToString()
    rec.deactivate()
    assertFalse(longFilter in text)
    assertTrue("x".repeat(32) in text)
  }
}
