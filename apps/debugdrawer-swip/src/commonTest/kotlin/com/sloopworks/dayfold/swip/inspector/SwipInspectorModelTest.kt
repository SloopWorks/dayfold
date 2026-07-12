package com.sloopworks.dayfold.swip.inspector

import works.sloop.swip.DebugRecord
import works.sloop.swip.DropReason
import works.sloop.swip.ExperimentalSwipDebugApi
import works.sloop.swip.debug.DebugEntry
import works.sloop.swip.pipeline.LatencyTier
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalSwipDebugApi::class)
class SwipInspectorModelTest {

  private fun entry(seq: Long, rec: DebugRecord) = DebugEntry(seq = seq, ts = seq * 10, rec = rec)

  private val enq = DebugRecord.Enqueued(
    eventId = "e1", schema = "account_signed_in", propsRaw = emptyMap(), propsStripped = null,
    distinctId = "d1", sessionId = "s1", tier = LatencyTier.NORMAL, critical = false,
  )
  private val dropped = DebugRecord.Dropped(eventId = null, schema = "hub_opened", reason = DropReason.MODE)
  private val health = DebugRecord.HealthSnapshot(0, 1, 0, 0, 0, 0)

  @Test
  fun categoryOf_maps_record_types() {
    assertEquals(SwipFilter.EVENTS, categoryOf(enq))
    assertEquals(SwipFilter.DROPPED, categoryOf(dropped))
    assertEquals(SwipFilter.STATE, categoryOf(health))
  }

  @Test
  fun swipFilter_ALL_passes_everything() {
    val list = listOf(entry(0, enq), entry(1, dropped), entry(2, health))
    assertEquals(3, swipFilter(list, SwipFilter.ALL).size)
  }

  @Test
  fun swipFilter_narrows_to_category() {
    val list = listOf(entry(0, enq), entry(1, dropped), entry(2, health))
    assertEquals(listOf(1L), swipFilter(list, SwipFilter.DROPPED).map { it.seq })
    assertEquals(listOf(0L), swipFilter(list, SwipFilter.EVENTS).map { it.seq })
    assertEquals(listOf(2L), swipFilter(list, SwipFilter.STATE).map { it.seq })
  }

  @Test
  fun rowLabel_leads_with_schema_for_events() {
    assertEquals(true, rowLabel(enq).contains("account_signed_in"))
    assertEquals(true, rowLabel(dropped).contains("hub_opened") && rowLabel(dropped).contains("MODE"))
  }
}
