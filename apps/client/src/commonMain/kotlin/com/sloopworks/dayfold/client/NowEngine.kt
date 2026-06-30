package com.sloopworks.dayfold.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import org.reduxkotlin.Store

// ADR 0043 §2b — the render-driven surfacing EFFECT (the Phase-A carryover). The Now feed is a pure
// render-time selector (nowFeed); it must NEVER write surfacing state from the render path. Instead
// the screen reports which subjects are currently surfaced (RankedFeed.visibleSubjectKeys()) and
// THIS engine performs the write, keeping the dataflow unidirectional and mirroring HubEngine's
// local-write methods:
//
//   render (visible subjects) → effect (here) → DB (ContentStore) → surfacingFlow bridge →
//   SurfacingLoaded → state.surfacing → next nowFeed() recompute (decay/soften/omit engage).
//
// Two writes, both LOCAL-ONLY / never-synced (syncing who-saw-what would be a behavioral leak,
// ADR 0043 §2b.3):
//   • noteShown — STARTS each subject's anti-nag decay clock ONCE (recordShownIfNew). Debounced so a
//     burst of recompositions coalesces into a single write pass; an in-memory `started` set +
//     a state check skip subjects whose clock is already running, and the write-if-new SQL is the
//     final backstop so continuous visibility never RESETS the clock (which would defeat softening).
//   • dismiss — drops a subject from future ranking (recordDismissed; rank() omits dismissed).
class NowEngine(
  private val store: Store<AppState>,
  private val contentStore: ContentStore,
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
  private val nowProvider: () -> String = { Clock.System.now().toString() },
  private val debounceMs: Long = 750L,
) {
  private val mutex = Mutex()
  private val started = mutableSetOf<String>()   // subjects whose clock we've already started this session
  private val pending = mutableSetOf<String>()   // subjects awaiting the next debounced flush
  private var flushJob: Job? = null

  /**
   * The render path reports the subjects currently surfaced. Coalesces a burst of emissions over
   * [debounceMs], then starts the decay clock for any not-yet-started subject. Non-suspending
   * (safe to call from a Compose effect); idempotent.
   */
  fun noteShown(subjectKeys: Set<String>) {
    if (subjectKeys.isEmpty()) return
    scope.launch {
      mutex.withLock {
        val fresh = subjectKeys - started - pending
        if (fresh.isEmpty()) return@withLock
        pending += fresh
        // Fixed-window batch, NOT a resettable trailing debounce: arm the timer once and let it
        // fire after debounceMs; later subjects join `pending` without postponing it. A reset-on-
        // every-call debounce could starve (a feed whose visible set keeps changing faster than the
        // window would re-arm forever and never write last_shown). This guarantees the clock starts.
        if (flushJob?.isActive != true) flushJob = scope.launch { delay(debounceMs); flush() }
      }
    }
  }

  private suspend fun flush() = mutex.withLock {
    if (pending.isEmpty()) return@withLock
    val now = nowProvider()
    val surfacing = store.state.surfacing
    pending.forEach { key ->
      // write-if-new: skip subjects whose clock is already running (state fast-path; the SQL
      // DO NOTHING is the authoritative backstop). Starting it once is the whole point — see SQL.
      if (surfacing[key]?.lastShownAtIso == null) contentStore.recordShownIfNew(key, now)
      started += key
    }
    pending.clear()
  }

  /** Dismiss a subject — omitted from future ranking (rank() filters dismissed). LOCAL-ONLY. */
  fun dismiss(subjectKey: String) {
    scope.launch { contentStore.recordDismissed(subjectKey, nowProvider()) }
  }

  fun stop() { flushJob?.cancel(); scope.cancel() }
}
