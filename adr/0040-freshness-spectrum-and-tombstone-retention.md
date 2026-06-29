# ADR 0040: Freshness Spectrum & Tombstone Retention — Daily-Poll through Realtime on One Cursor

## Status

**Accepted** 2026-06-29 (operator ratified the two-way build bundle — the
cursor/watermark/full-resync contract + tombstone-retention floor build now; **push
credentials** (FCM/APNs) and any **held-connection realtime vendor** remain deferred
drop-ins behind their own operator gates). Was **Proposed** 2026-06-28 (agent-drafted
from the heterogeneous-cadence review of the
two-way engine; **partly operator-gated** — the cursor/watermark/full-resync contract
is agent-buildable, but the **push credentials** (FCM/APNs, composes ADR 0034/0023)
and any **held-connection realtime vendor** (spend/vendor) are operator gates).
**Extends/refines ADR 0020** (offline-first DB-as-SoT + freshness): it promotes
ADR 0020's R2/R3 from "polling now, push deferred" into a **client-cadence spectrum
contract** and adds the **tombstone-retention + stale-cursor** rule ADR 0020 only
gestured at. Composes ADR 0038/0039 (the two-way write engine — cursor + tombstones
are shared), ADR 0015/0017 (E2EE — push is content-blind), ADR 0029/0030
(credential-scoped, visibility-filtered sync), ADR 0024 (per-device settings),
ADR 0018 (Vercel/Neon serverless), ADR 0025 (abuse limits). Design:
`specs/two-way-engine-and-content-management-design.md` §10.5.

## Context

The product must serve a **range of client refresh cadences at once** — a wall tablet
that syncs **daily**, a phone that **foreground-polls** (~45s), background refresh
while closed, and eventually **near-realtime** devices — and a single family will
**mix** them across devices. ADR 0020 chose the right foundation (keyset cursor over
`(updated_at, id)` + tombstones, unidirectional `network→DB→store→UI`) and noted push
as a deferred drop-in, but left two things unspecified that the cadence range actually
depends on:

1. **Tombstone retention vs slow clients.** A daily / long-offline client must still
   receive the **tombstone** for content deleted while it was away, or it shows
   ghost-deleted content indefinitely. But tombstones (soft-deleted rows floated past
   the cursor by the `updated_at` trigger) **accumulate forever** without GC, growing
   every client's cold-start resync. These two requirements collide, and ADR 0020 left
   the resolution as an open question. The current `/cron/sweep` GCs only auth ephemera
   (`apps/api/src/sweep.ts`), **not content tombstones** — so today nothing purges them
   and nothing protects a slow client from a future purge.
2. **The realtime end is "deferred," not contracted.** "Push just triggers the same
   sync" is the right instinct, but *how* push stays E2EE-safe and single-path, *what*
   wakes a client, and *what realtime actually costs on Vercel serverless* are
   undefined.

The keyset cursor already makes **cadence a stateless, per-device client policy** — the
server stores no schedule; it answers `/sync` from whatever cursor a client presents.
That property is what makes the spectrum possible; this ADR makes it *safe* and
*contracted*.

## Decision

### 1. Cadence is a per-device client policy; the server is cadence-agnostic

The **single read contract** is the keyset `/sync` (cursor over `(updated_at, id)`,
incl. tombstones, visibility-filtered, paginated `has_more`/`next_cursor`). Every
client — at any cadence — resumes from its own cursor; the server stores no per-client
schedule and treats a 45-second-old and a 90-day-old cursor identically (modulo §3).
A family **mixes cadences across devices** freely. Per-device cadence is a **client
setting** (composes ADR 0024 — a device-local "background refresh: realtime / hourly /
daily / manual" + battery/data constraints; freshness preference **never syncs**).

### 2. The cadence ladder — one read path, escalating triggers

| Tier | Trigger | Latency | Server infra | Milestone |
|---|---|---|---|---|
| **Daily / background** | OS periodic — Android `WorkManager`, iOS `BGTaskScheduler` (ADR 0020 R3) | hours | none | M0-adjacent |
| **Foreground poll** | ~45s timer + sync-on-resume + sync-after-push (ADR 0020 R2) | ≤45s | none | **M0 default** |
| **Push-woken** | a **contentless server change-signal** wakes the client → it runs the *same* `/sync` | seconds | a write-time signal + debounce (§4) | next (deferred) |
| **Held-connection realtime** | managed pub/sub (Ably/Pusher/Supabase Realtime) wake → *same* `/sync` | sub-second | a vendor (does **not** fit Vercel serverless) | later, vendor+spend gate |

Each tier is a **trigger swap on one read path** — escalating a device's cadence never
changes the dataflow, the merge, or the schema.

### 3. Tombstone retention + stale-cursor full-resync (the load-bearing rule)

So that **arbitrarily-stale clients stay correct** without unbounded tombstone growth:

- **Stale-cursor → full-resync directive (contract now; cheap; costly to retrofit).**
  `/sync` gains a response that says *"your cursor is older than the retention horizon
  — wipe local cache and resync from a snapshot"* (cursor reset to the beginning;
  `has_more` paginates the rebuild). The client, on this directive, **clears its local
  DB and re-syncs from scratch**. This guarantees correctness for any client however
  stale (month-offline, or one whose needed tombstones were purged), at the cost of one
  expensive resync. **This response variant must exist from the start** — clients can
  already go offline arbitrarily long.
- **Tombstone retention floor (contract now).** Content-tombstone GC (a new arm of
  `/cron/sweep`) hard-purges a soft-deleted row only once it is **older than a fixed
  retention floor** ≥ the max plausible slow-cadence/offline gap (recommend **≥ 60–90
  days** `[estimate]`). Below the floor, tombstones are never purged → any client that
  synced within the floor never misses a delete.
- **Per-credential watermark-driven GC (defer; optimization).** Track a
  **per-credential sync watermark** (the cursor each active credential has acknowledged
  + its timestamp; a small `sync_watermark(credential_id, cursor, at)` table, updated on
  each `/sync`). When tombstone volume becomes a cost, GC may purge **more
  aggressively** — once a tombstone is older than the **oldest *active* credential's
  watermark** (not just the fixed floor). Until then the fixed floor + full-resync
  fallback suffices; the watermark is the reserved optimization.
- **Dormant credentials don't pin retention.** A credential past an **"active" cutoff**
  (e.g. no sync within the retention floor, or revoked) is **excluded** from the
  oldest-active-watermark computation (else one dead device pins tombstones forever); on
  its return it takes the **full-resync** path (§3a). Revoked credentials are excluded
  immediately.

### 4. Push is a contentless, E2EE-safe *signal* — emitted debounced + jittered

- **No content in push.** A push wake carries only *"family X changed — sync now"*
  (optionally a coalesced change-counter or cursor hint), **never content**. The push
  service (FCM/APNs/vendor) is an untrusted third party that **cannot see content under
  E2EE** anyway; keeping content out preserves the **single read path** + the
  zero-knowledge thesis (ADR 0015). Realtime never becomes a second, leakier dataflow.
- **Change-signal emission (the push-milestone build).** On a content mutation (the
  `/mutations` / content-write path), the server marks the family changed (a per-family
  `last_changed_at`, or Postgres `LISTEN/NOTIFY`, or enqueue-on-write). A signal emitter
  **debounces per family** (≥ N seconds — a burst from an offline flush or the loop
  authoring 10 cards fires **one** wake, not ten) and **jitters** the fan-out across the
  family's subscribed devices (avoid a synchronized `/sync` thundering herd on the
  serverless API — composes the ADR 0025 abuse posture + the perf cost wall).

### 5. Realtime ceiling — push-seconds fits serverless; held-connection needs a vendor

FCM/APNs push gives **seconds** latency, is battery-friendly, and fits Vercel's
request-scoped functions. **Held-connection sub-second (WebSocket/SSE) does not fit
Vercel serverless** → it requires a **managed pub/sub vendor** (Ably/Pusher/Supabase
Realtime) — and even then it is only a *faster trigger*; the cursor `/sync` underneath
is unchanged. So **sub-second realtime is a vendor + spend decision, not an
architectural rework** — deferred and operator-gated.

### 6. Two-way under slow cadence — cadence-agnostic correctness

A slow client's writes sit in the outbox and propagate on its next sync; its
`base_version` can be far behind → more 412-merge-retries + a larger merge surface, but
**client-side LWW + `op_id` idempotency are cadence-agnostic** (stamps converge
regardless of *when* exchanged — ADR 0038/0039), so correctness holds at any cadence.
Burst-flush hits **retry-friendly (429-retryable)** rate limits (ADR 0025); UX is honest
that a slow client shows intentionally stale shared state between syncs.

### 7. M0 scope

M0 ships the **poll + background tiers** (ADR 0020 R2/R3) **plus the §3 contract** —
the **stale-cursor full-resync `/sync` response + a fixed tombstone-retention floor**
(both cheap, both required for slow clients to be *safe*, both costly to retrofit into
the sync contract later). The **per-credential watermark GC** (§3c), **push signal**
(§4), and **held-connection realtime** (§5) are deferred drop-ins on the same cursor.

## Rationale

- **Cursor sync is the single property that makes heterogeneous cadence work** — a
  client resumes from its own position no matter the interval, so every tier is a
  trigger swap, not a new dataflow. Building the spectrum *down* from one cursor beats
  bolting separate realtime/daily paths together.
- **The full-resync fallback is the cheap correctness guarantee** — rather than promise
  to retain every tombstone forever (unbounded) or risk a slow client missing a delete
  (ghosts), a too-old cursor deterministically triggers a clean rebuild. One expensive
  resync for a rarely-synced client is the right trade.
- **Fixed floor now, watermark GC later** — the floor + fallback make slow clients safe
  with trivial logic; the per-credential watermark is a pure GC *optimization* that only
  earns its complexity once tombstone volume is a measured cost. Reserve the contract,
  defer the optimization.
- **Push as a content-blind signal** keeps the read path single and the server
  zero-knowledge — the only E2EE-coherent way to add realtime (the push vendor literally
  cannot carry content it can't read).
- **Honest realtime ceiling** — push-seconds is cheap and serverless-fit; sub-second is
  a vendor cost, not free. Stating it prevents a surprise rework.

**Rejected:** retain all tombstones forever (unbounded cold-start + storage); GC by a
fixed window with **no** stale-cursor fallback (silently drops deletes for slow clients
— the ghost-content bug); push payloads that carry content (breaks E2EE + forks the
dataflow); held-connection realtime on Vercel functions (architecturally unfit);
per-client server-side schedules (makes the server cadence-stateful for no gain — cadence
is a client policy).

## Consequences

**Positive:** one cursor read path serves daily → realtime; per-device cadence is
independent and a family mixes freely; slow/long-offline clients are provably correct
(full-resync fallback); tombstone growth is bounded (floor, later watermark); push and
realtime are drop-in trigger swaps that never touch the dataflow/merge/schema; the
zero-knowledge thesis survives realtime (content-blind signal).

**Negative / cost:** the `/sync` contract gains a **full-resync directive** + the client
must implement **cache-wipe-and-rebuild** (and the existing tenancy-401/404 cache-wipe
path, ADR 0030, is the natural hook); a content-tombstone **GC arm** on `/cron/sweep`
(new); the push tier needs a **change-signal + debounce + jitter** subsystem and
**FCM/APNs credentials** (operator-gated, composes ADR 0034/0023); sub-second realtime
needs a **managed vendor** (spend, operator-gated); a slow client's first sync after a
long gap is an **expensive paginated rebuild** (acceptable, rare). The per-credential
**watermark table** (when built) adds a small write on every `/sync`.

**Operator-gated / open (→ OQ-freshness-spectrum):** the retention-floor constant
(values/cost), the push credentials (ADR 0034/0023), the realtime vendor (spend). The
cursor/full-resync/floor contract is agent-buildable.

## Revisit Trigger

Tombstone volume becomes a measured cold-start cost (build the per-credential watermark
GC, §3c); foreground-poll invocation count on Vercel becomes material (ship the push
tier, §4 — already an ADR 0020 revisit trigger); a client/UX needs **sub-second**
freshness (evaluate the managed-realtime vendor, §5); or background-sync OS throttling
proves too coarse for the "daily" tier's freshness expectation.
