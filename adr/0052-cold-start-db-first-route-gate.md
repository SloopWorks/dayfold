# ADR 0052: DB-First Cold-Start Route Gate — Cache Memberships So the Splash Doesn't Wait on the Network

## Status

**Proposed** 2026-07-09 (agent-drafted from a cold-start investigation). Operator-gated:
it changes cold-start auth/render behavior and briefly renders already-on-device cached
content before the network confirms the session — an ADR-0011/0020-class boundary. Not
yet ratified; not built.

Statuses: Proposed | Accepted | Superseded | Deprecated.

## Context

Dayfold's content is offline-first and correct (ADR 0020): the SQLDelight DB is the
single source of truth, `SyncEngine.start()` bridges DB→store before any network, and
cached cards/hubs render on the first frame with zero network. The `/sync` poll writes
fresh data into the DB out-of-band; nothing in the content render path awaits the network.

**But the top-level route gate — the dayfold-icon splash the operator occasionally sees
on cold start — is network-gated, not DB-gated.** The splash is `Route.Loading`. It only
clears when `MembershipsLoaded` fires, and that action is dispatched **only after a
successful network `whoami` round-trip** (`AuthEngine.loadMemberships` →
`authClient.whoami`, `AuthEngine.kt:352-355`). So even with a fully-populated local cache,
the app sits on the splash until `whoami` returns.

**Root cause:** `state.families` (the user's family memberships) lives only in memory and
is sourced only from network `whoami`. It is **never persisted locally** — confirmed: no
`membership`/`families` table in `Content.sq`, and `TokenStore` persists only the
`Session` (access + refresh), not memberships. The saved token *is* read locally
(`tokenStore.load()`), but a token-present state deliberately holds route at `Loading`
("whoami next", `Reducer.kt:96`). So the route gate has nothing local to resolve against
and must hit the network.

**Symptom / cost.** Cold start (process was reclaimed by the OS) blocks first paint of
content that is already sitting in the DB for the duration of one `whoami` HTTP
round-trip, plus a refresh-retry on 401. Offline or on a slow link, the splash holds until
the ktor timeout, then falls to `Route.AuthError` ("Couldn't reach Dayfold… retry") —
withholding a perfectly good cached view because the *auth* gate, not the content, failed.
Warm start (process alive, foreground) shows no splash — the route is already `Route.Feed`
and foreground only kicks a background `/sync`.

This is the one place the offline-first posture is not honored end-to-end.

## Decision (proposed)

Make the cold-start route gate DB-first, mirroring the content path: **persist last-known
memberships locally, restore them on cold start to route straight to `Route.Feed` when a
saved token + cached memberships exist, and run `whoami` in the background to reconcile.**
The network becomes a *confirmation*, not a *gate*. Pure client; the content-blind server
(ADR 0015) is untouched — no schema change, no new server route, no new write path.

1. **Persist memberships locally — SQLDelight local-only table.** Add a `membership`
   table to `Content.sq` alongside the existing local-only tables (`hidden`,
   `surfacing_state`, `notif_config`) — reuses the `DriverFactory` + migration pipeline
   (ADR 0033), needs zero new per-platform code (vs. extending `TokenStore`, which would
   touch three platform impls). It is a *cache of the user's own family list*, not synced
   content — write it whenever `MembershipsLoaded` lands; clear it on the same boundary the
   content cache clears (logout / dead-session / 403-404, per `clearCache`).

2. **Cold-start sequence becomes:**
   - `SyncEngine.start()` bridges DB→store (unchanged) — now also emits cached memberships.
   - `AuthEngine.restore()`: read token. No token → `SignIn` (unchanged). Token **+ cached
     memberships** → dispatch a `MembershipsRestored` and route via `routeFor` **straight
     to `Route.Feed`** (cached content already rendering underneath). Token but **no**
     cached memberships (first launch after a fresh install / cleared cache) → hold
     `Route.Loading` and network-gate exactly as today — nothing to show, so the splash is
     honest.
   - `whoami` then runs in the background and reconciles (see §3).

3. **Reconciliation semantics (`loadMemberships` after an optimistic Feed):**
   - **Success** → `MembershipsLoaded` overwrites `state.families` + re-runs `routeFor`
     (may downgrade to `CreateFamily` if the server says no active membership remains, or
     stay on `Feed`). Also re-persists the fresh list.
   - **401 after refresh fails** (revoked/expired) → `SessionExpired` → clear token **and
     cache** (incl. the membership cache) → `SignIn`. Unchanged boundary.
   - **Network / 5xx** → **stay on `Route.Feed` with the cached view** (do *not* fall to
     `AuthError`). `AuthError` is reserved for the case where there is **nothing cached to
     show** (token but empty membership cache). Surface a quiet, non-blocking "offline /
     last updated…" affordance instead of stranding the user. *(This is the one new UX
     surface — see Open / Gate A.)*

4. **Accepted risk — brief optimistic render before confirmation.** For one `whoami`
   round-trip, a cold start renders already-on-device cached content (the user's own family
   data) before the network confirms the session is still valid. If the session was revoked
   or the user was removed from a family, that stale view shows until reconcile clears it.
   This is the **same data boundary the content cache already accepts** (ADR 0020 — cached
   cards render offline regardless of live session state) and matches every offline-first
   app; the window is bounded by the `whoami` reconcile and closed decisively on 401. No
   *new* data leaves the device and no data crosses a *new* boundary — it's the same
   device, same user, one round-trip earlier.

## Consequences

**Positive.** Cold start collapses to warm-start behavior: instant paint of cached content,
no network wait, no splash on the common path. The offline-first posture (ADR 0020) is
finally honored end-to-end — auth stops being the one network gate in front of local data.
Offline cold start now shows the last-known dashboard instead of an `AuthError` dead end.
Pure client, no server/schema/write-path change (dumb-server invariant holds).

**Costs / risks.** The optimistic-render window in §4 (bounded, accepted). One new table +
migration (ADR 0033 process). New branch in `loadMemberships` error handling (network-fail
→ stay vs. `AuthError`) widens the auth test matrix — needs cold-start tests for: token +
cache → Feed; token + no cache → Loading→whoami; whoami-network-fail-with-cache → stay on
Feed; whoami-401 → clear + SignIn; whoami says membership revoked → downgrade route. A
membership cache is one more thing to invalidate correctly — a stale cache that outlives a
real revocation is the failure mode to test hardest (mitigated by clearing on the exact
`clearCache` boundary).

**Rejected alternatives.**
- **(A) Persist memberships in `TokenStore`** (travel with the session) — conceptually
  clean (memberships are auth-domain) but costs three per-platform impl changes and an
  interface widening for no reactive benefit; the DB already is the cold-start cache home.
- **(B) Shorten the `whoami` timeout** — treats the symptom, still blanks the screen
  offline, still gates local data on the network.
- **(C) Do nothing** — the status quo; leaves the one un-honored corner of offline-first.

## Composition

Composes ADR 0011/0021/0023 (backend-minted session, app-driven auth — architecture
intact; this only caches its *output*), 0020 (DB = source of truth — this extends the
principle to the route gate), 0015 (content-blind server — untouched; pure client), 0033
(migration pipeline the new table uses), 0040 (cache-clear / freshness boundaries the
membership cache must honor).

## Open

- **Gate A (ADR 0008 design-first).** No *new* screen — the change routes to the existing
  `Feed` and `Splash` sooner. The one genuinely new affordance is the **quiet offline /
  "last updated" indicator** shown when a cold start renders cached content and `whoami`
  can't be reached (§3 network-fail path). That wants a small design nod before build (does
  it reuse the existing sync/refresh chrome, or a new stale-banner?). Everything else is
  behavior-only and needs no mock.
- **Gate B — operator ratification.** Accepting §4 (optimistic render before session
  confirmation) is the operator's call — it's the one posture shift. Everything downstream
  is mechanical.
- **Decision to confirm at ratification:** membership-cache home — DB table (recommended,
  §1) vs. `TokenStore` (rejected-alt A). Flagged because it's a small architecture choice
  with a long tail (where does "the user's own family list" live on-device?).
