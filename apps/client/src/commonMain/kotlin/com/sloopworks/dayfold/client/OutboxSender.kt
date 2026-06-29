package com.sloopworks.dayfold.client

// ADR 0038 §6 / 0039 — the outbox sender state machine, as a PURE function so the
// egress path is testable headlessly (no network, no DB). The sender loop in SyncEngine
// drains the outbox FIFO and feeds each attempt's HTTP result here to decide what's next.
//
// Lifecycle of an op: pending → inflight → { acked (200) → drop on echo | failed | dropped }.
// Outcomes:
//   Acked   (200)            — store the returned version; mark acked; the row is deleted
//                              when /sync echoes a block at ≥ that version (echo-suppress on op_id).
//   ReMerge (412)            — the base version was stale; re-fetch, re-run merge(), re-enqueue.
//   Drop    (410/404/4xx)    — the target is gone or the request is malformed → give up, remove op.
//   Backoff (401/5xx/network)— transient; retry later with exponential backoff.
//   Failed  (cap reached)    — surfaced calmly ("couldn't save — will retry on next sync");
//                              the op stays for a later manual/foreground retry.

sealed interface SendOutcome {
  /** 200 — applied; caller stores the returned version and marks the row acked. */
  object Acked : SendOutcome
  /** 412 — stale base; the caller re-fetches, re-merges the local done-triple, re-enqueues. */
  object ReMerge : SendOutcome
  /** 410/404/other-4xx — give up and remove the op (the sender does not retry). */
  object Drop : SendOutcome
  /** 401/5xx/network — retry after [delayMs] (attempt is the new, incremented count). */
  data class Backoff(val attempt: Int, val delayMs: Long) : SendOutcome
  /** Attempt cap reached on a retryable/conflict path — mark the row failed (calm surface). */
  object Failed : SendOutcome
}

object OutboxSender {
  /** Max attempts before a retryable/412 op is parked as `failed` (§6.2 "~3 attempts" + headroom). */
  const val MAX_ATTEMPTS = 5

  /**
   * Decide the next action for an op whose PUT returned [httpStatus] (null = network error),
   * given the [attempt] count BEFORE this attempt. Pure + total.
   */
  fun classify(httpStatus: Int?, attempt: Int, maxAttempts: Int = MAX_ATTEMPTS): SendOutcome {
    val next = attempt + 1
    val capReached = next >= maxAttempts
    return when {
      httpStatus == 200 || httpStatus == 204 -> SendOutcome.Acked    // 204 = delete OK / idempotent re-delete
      httpStatus == 410 || httpStatus == 404 -> SendOutcome.Drop          // tombstoned / parent gone → give up
      httpStatus == 412 -> if (capReached) SendOutcome.Failed else SendOutcome.ReMerge
      // other client errors (malformed body, 403, 422 …) except 401 → not retryable → give up
      httpStatus != null && httpStatus in 400..499 && httpStatus != 401 -> SendOutcome.Drop
      // 401 (token), 5xx, or network (null) → transient; back off unless capped
      else -> if (capReached) SendOutcome.Failed else SendOutcome.Backoff(next, backoffMs(next))
    }
  }

  /** Exponential backoff (caller adds jitter): base 1s, doubling, capped at 60s. */
  fun backoffMs(attempt: Int, baseMs: Long = 1_000L): Long {
    val shift = (attempt - 1).coerceIn(0, 6)        // 1,2,4,…,64×
    return (baseMs shl shift).coerceAtMost(60_000L)
  }
}
