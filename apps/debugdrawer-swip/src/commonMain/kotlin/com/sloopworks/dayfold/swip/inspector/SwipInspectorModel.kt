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

const val MASK = "••••"

/** A detail row. `sensitive` values are masked until the user reveals them (FLAG_SECURE gate). */
data class DetailLine(val label: String, val value: String, val sensitive: Boolean)

@OptIn(ExperimentalSwipDebugApi::class)
fun detailLines(rec: DebugRecord): List<DetailLine> = buildList {
  when (rec) {
    is DebugRecord.Enqueued -> {
      add(DetailLine("schema", rec.schema, sensitive = false))
      add(DetailLine("tier", rec.tier.name, sensitive = false))
      add(DetailLine("critical", rec.critical.toString(), sensitive = false))
      add(DetailLine("eventId", rec.eventId, sensitive = true))
      add(DetailLine("distinctId", rec.distinctId ?: "null", sensitive = true))
      add(DetailLine("sessionId", rec.sessionId ?: "null", sensitive = true))
      val props = rec.propsStripped ?: rec.propsRaw
      props.forEach { (k, v) -> add(DetailLine("prop.$k", v.toString(), sensitive = true)) }
    }
    is DebugRecord.Dropped -> {
      add(DetailLine("schema", rec.schema, sensitive = false))
      add(DetailLine("reason", rec.reason.name, sensitive = false))
      add(DetailLine("eventId", rec.eventId ?: "null", sensitive = true))
    }
    is DebugRecord.Batched -> {
      add(DetailLine("batchId", rec.batchId, sensitive = false))
      add(DetailLine("eventIds", rec.eventIds.joinToString(), sensitive = true))
    }
    is DebugRecord.Sent -> {
      add(DetailLine("batchId", rec.batchId, sensitive = false))
      add(DetailLine("status", rec.status, sensitive = false))
      add(DetailLine("count", rec.count.toString(), sensitive = false))
    }
    is DebugRecord.SendFailed -> {
      add(DetailLine("batchId", rec.batchId, sensitive = false))
      add(DetailLine("attempt", rec.attempt.toString(), sensitive = false))
      add(DetailLine("willRetry", rec.willRetry.toString(), sensitive = false))
    }
    is DebugRecord.HealthSnapshot -> {
      add(DetailLine("queued", rec.queued.toString(), sensitive = false))
      add(DetailLine("dropsConsentDenied", rec.dropsConsentDenied.toString(), sensitive = false))
      add(DetailLine("dropsOverflow", rec.dropsOverflow.toString(), sensitive = false))
      add(DetailLine("dropsDeadLetter", rec.dropsDeadLetter.toString(), sensitive = false))
      add(DetailLine("flushFailures", rec.flushFailures.toString(), sensitive = false))
      add(DetailLine("storageErrors", rec.storageErrors.toString(), sensitive = false))
    }
    is DebugRecord.ModeChanged -> {
      add(DetailLine("from", rec.from.name, sensitive = false))
      add(DetailLine("to", rec.to.name, sensitive = false))
      add(DetailLine("purged", rec.purged.toString(), sensitive = false))
    }
    is DebugRecord.ConsentChanged -> rec.consent.forEach { (k, v) -> add(DetailLine(k.toString(), v.toString(), sensitive = false)) }
    is DebugRecord.IdentityChanged -> add(DetailLine("kind", rec.kind, sensitive = false))
    is DebugRecord.SessionRotated -> add(DetailLine("reason", rec.reason, sensitive = false))
    is DebugRecord.FlushInvoked -> add(DetailLine("manual", rec.manual.toString(), sensitive = false))
    is DebugRecord.Purged -> add(DetailLine("reason", rec.reason, sensitive = false))
    is DebugRecord.ChannelInfo -> {
      add(DetailLine("channel", rec.channel, sensitive = false))
      add(DetailLine("internal", rec.internal.toString(), sensitive = false))
      add(DetailLine("transportKind", rec.transportKind, sensitive = false))
    }
  }
}

fun renderValue(line: DetailLine, revealed: Boolean): String =
  if (line.sensitive && !revealed) MASK else line.value

@OptIn(ExperimentalSwipDebugApi::class)
fun copyText(rec: DebugRecord, revealed: Boolean): String =
  detailLines(rec).joinToString("\n") { "${it.label}: ${renderValue(it, revealed)}" }
