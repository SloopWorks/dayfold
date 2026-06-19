# ADR 0020: Offline-First Client Data + Freshness (DB-as-source-of-truth)

## Status

**Proposed** 2026-06-19 (operator-directed). Extends ADR 0013 (KMP/CMP +
redux-kotlin) and the sync design in `specs/prototype/08-mobile-client.md`.
Build gap acknowledged: the shipped M0 client is **in-memory only** (redux store
fed directly by the network); this ADR is the target to build toward in the
**Persistence & Sync** slice.

## Context

The product renders intelligence authored elsewhere (CLI/API push). Clients must
(a) **open instantly with data** even offline, and (b) **reflect new pushes
nearly immediately** while open. The store-only build does neither (cold start =
empty until a network round-trip; no persistence; no background refresh).

## Decision

Adopt **offline-first** with the **local DB as the single source of truth** and
a **strictly unidirectional** dataflow:

```
network (/sync)  ──writes──▶  local DB (SQLDelight)  ──reactive query──▶  redux store  ──selectorState──▶  UI
                                   ▲ source of truth
```

The UI **never** reads the network directly; it renders the store, which is a
**reactive projection of the DB**. The network's only job is to write the DB.

**Requirements:**
- **R1 — Instant cold start (offline-first).** Opening the app renders the last
  cached content from the DB immediately, with zero network dependency. Works
  fully offline (read).
- **R2 — Foreground freshness.** While open, a push reaches the client **near-
  immediately**. M0 mechanism = **foreground polling** of `/sync` (~30–60 s) +
  an immediate sync on app open / foreground-resume. **Push (FCM/APNs or
  SSE/WebSocket) is deferred** to a later milestone — polling first to avoid the
  complexity/credentials, upgradeable without changing the dataflow.
- **R3 — Background freshness.** While backgrounded / not running, the OS pulls
  `/sync` on a schedule so the *next* open is already fresh — **Android
  `WorkManager`** (periodic, network-constrained) and **iOS `BGTaskScheduler`
  (`BGAppRefreshTask`)**, both calling the **same shared sync engine**
  (`commonMain`). Best-effort, OS-throttled.
- **R4 — DB is source of truth; unidirectional.** All reads project from the DB.
  Network writes the DB in **one transaction** (upsert changes + apply tombstones
  + advance the keyset cursor — crash-safe, cursor moves only on commit). The
  cursor + `lastSyncedAt` persist in the DB so foreground, background, and
  cold-start all resume from the same point (no gaps, no double-pull).

**Scope note:** M0 content is **server-authored, single-writer** → the client is
a pure read-replica (no client-write conflicts). The 2-way path (ADR 0016) later
adds a local **outbox** table feeding the same unidirectional flow.

## Rationale

DB-as-SoT + unidirectional is the standard robust offline pattern (instant,
resilient, testable) and maps cleanly onto redux: the store stays the UI's
reactive view, the DB stays truth, and `network→DB→store→UI` keeps one writer
per layer. Polling-before-push gets near-real-time freshness now without push
infra; the dataflow is identical when push is added (push just triggers the same
sync). One shared sync engine means Android/iOS/desktop + foreground/background
all reuse it — no divergent sync code.

## Consequences

Positive: instant, offline-capable UI; near-real-time while open; background
freshness; one tested sync path; push is a drop-in later. Negative: requires the
**Persistence & Sync build slice** (SQLDelight KMP + DB↔store bridge +
WorkManager/BGTask glue) the in-memory build skipped; polling has a freshness/
battery trade-off (tune the interval; pause when backgrounded — background uses
WorkManager instead); background sync is OS-throttled (not guaranteed cadence).

## Revisit Trigger

Freshness needs to be < polling interval (add push); battery/cost from polling
becomes material; or 2-way (ADR 0016) ships (add the outbox).
