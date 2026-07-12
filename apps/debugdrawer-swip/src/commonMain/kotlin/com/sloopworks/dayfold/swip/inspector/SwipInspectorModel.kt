package com.sloopworks.dayfold.swip.inspector

import works.sloop.swip.DebugRecord
import works.sloop.swip.ExperimentalSwipDebugApi
import works.sloop.swip.debug.DebugEntry

/** Segmented filter categories. ALL is the "no filter" sentinel — categoryOf never returns it. */
enum class SwipFilter { ALL, EVENTS, DROPPED, STATE }

@OptIn(ExperimentalSwipDebugApi::class)
fun categoryOf(rec: DebugRecord): SwipFilter = when (rec) {
  is DebugRecord.Enqueued, is DebugRecord.Batched, is DebugRecord.Sent, is DebugRecord.SendFailed -> SwipFilter.EVENTS
  is DebugRecord.Dropped -> SwipFilter.DROPPED
  else -> SwipFilter.STATE
}

@OptIn(ExperimentalSwipDebugApi::class)
fun swipFilter(entries: List<DebugEntry>, filter: SwipFilter): List<DebugEntry> =
  if (filter == SwipFilter.ALL) entries else entries.filter { categoryOf(it.rec) == filter }

/** One-line row summary. Events lead with schema; state lines are compact + id-free. */
@OptIn(ExperimentalSwipDebugApi::class)
fun rowLabel(rec: DebugRecord): String = when (rec) {
  is DebugRecord.Enqueued -> rec.schema
  is DebugRecord.Dropped -> "${rec.schema} · ${rec.reason}"
  is DebugRecord.Batched -> "batch ${rec.batchId} · ${rec.eventIds.size} events"
  is DebugRecord.Sent -> "sent ${rec.batchId} · ${rec.status} · ${rec.count}"
  is DebugRecord.SendFailed -> "send failed ${rec.batchId} · attempt ${rec.attempt}"
  is DebugRecord.Purged -> "purged · ${rec.reason}"
  is DebugRecord.HealthSnapshot -> "health · queued ${rec.queued}"
  is DebugRecord.ModeChanged -> "mode ${rec.from} → ${rec.to}"
  is DebugRecord.ConsentChanged -> "consent changed"
  is DebugRecord.IdentityChanged -> "identity · ${rec.kind}"
  is DebugRecord.SessionRotated -> "session rotated · ${rec.reason}"
  is DebugRecord.FlushInvoked -> "flush" + if (rec.manual) " (manual)" else ""
  is DebugRecord.ChannelInfo -> "channel ${rec.channel} · ${rec.transportKind}"
}
