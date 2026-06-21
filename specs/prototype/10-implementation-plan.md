# 10 — M0 Implementation Plan (dumb-store spine → first dogfood)

> Status: **reviewed → fixes applied**. Turns the `09 §Build order` into sequenced,
> task-level work packages with acceptance + verify + owner (agent-autonomous
> per ADR 0012 vs operator-gated). Target: **Gate G1a DoD — operator authors
> via CLI, sees it on device daily.** Build behind the ADR 0012 rails
> (test-green-before → preview → verify → promote → rollback).

## Pre-flight gates (resolve in order)

| Gate | Blocks | Owner |
|---|---|---|
| ~~INB-9~~ — **RESOLVED: TypeScript on Vercel (ADR 0018)** | P0 scaffold — unblocked | done |
| ~~INB-10~~ — **RESOLVED 2026-06-18: M0 = PLAINTEXT** (no E2E; live E2EE = M1 decision, gated by ADR 0017). **Schema freeze UNBLOCKED** — content fields are plaintext, server FTS kept, version = server-bumps. No typed-hole needed at M0. | done |
| **redux-kotlin coordinates** — ✅ **CONFIRMED** (verification done): all modules exist at `1.0.0-alpha01` (KMP), but it's ~1-day-old → **pin `0.6.2` stable as default**, alpha01 behind a feature flag; code calls **`fieldState`** (not `fieldStateOf`). **INB-11** for the operator's alpha01-vs-stable preference | P3 | resolved; operator preference INB-11 |
| ADR 0005/0006/0007/0015/0016 ratifications | scope lock | operator (inbox sweep) |
| Recovery-floor procedure | **M1 only** (no auth at M0) | operator+counsel |

## P-1 — Operator bootstrap (human-only preflight, ADR 0012)

Enumerated so the agent loop doesn't stall on an un-surfaced human gate:
- Create/authenticate **Vercel** project + org (MCP needs an authed account).
- Create **Postgres** (Neon/Supabase) instance + billing; connection string →
  Vercel secret store. (Agent can provision *within* the authed account ≤ cap.)
- **Domain** `api.<host>` DNS (operator-owned).
- **gh** repo + secret-store setup (mostly done — repo exists).
- **Apple** Developer account + a **Mac** for P3 iOS (physical dependency).

## Work packages

### P0 — Foundation  *(agent-autonomous; operator picks host + E2E first)*
- **Author the content JSON-schema** (02 tables → schema for hubs/sections/
  blocks/cards/places) — the dependency of *everything* (codegen, zod, Kotlin
  types, CLI, client). **INB-10-sensitive:** if E2E undecided, freeze only the
  cleartext routing fields; leave the **content-field representation a typed
  hole** (plaintext vs ciphertext) to fill post-INB-10 — so codegen wiring is
  built without a rebuild.
- Monorepo: Kotlin client + Kotlin CLI + TS/Vercel API; codegen (TS zod +
  Kotlin types) from the schema. Pick the **migration tool** (Drizzle/Prisma/
  raw SQL) — blocks P1 migrations.
- CI + the ADR 0012 toolchain (Vercel MCP, gh, secret store, per-env config);
  **structured logging + audit trail + the privacy log-scrub lint** (detekt
  rule banning location/plaintext in logs — prerequisite for security test #9
  + the ADR 0012 audit rail).
- **Acceptance:** codegen emits both type sets from one schema; empty deploy
  promotes to prod + auto-rolls-back on a forced health failure; audit log
  captures a deploy action.

### P1 — Data + API spine  *(agent-autonomous; operator approves first prod promote)*
- DDL migrations (02) — **freeze only after INB-10** (E2E flips ciphertext
  cols + version-authority + drops FTS).
- Content API M0 routes (03): `PUT/GET/DELETE` families·hubs·sections·blocks·
  cards·places, `:archive`, **`/sync`** (keyset + tombstones).
- M0 household-token middleware (04 M0): constant-time compare,
  **content:read+write**, default-deny, cross-tenant 404, mass-assignment
  allowlist, gzip/zip-bomb + size caps, idempotent upsert + parent-exists +
  version.
- **Seed + provision (the bridge to P2/P3):** a `families` seed row + fixed
  `family_id`; **mint the M0 household-token credential row**
  (`content:read+write`), generate the secret → Vercel secret store → deliver
  to the operator keychain; a **local-dev seed fixture** (family + sample hub)
  so P2/P3 develop without the full chain. (No M1 mint endpoint at M0 — this is
  a seed/provision script.)
- **Tests:** integration (Postgres); IDOR matrix; security register #6/#8/#10;
  **sync-tombstone (#11)**. **Acceptance:** curl can upsert a hub + `/sync`
  round-trips it; IDOR + tombstone green; **the household token auths + reads
  + writes**.

### P2 — CLI  *(agent builds; operator dogfoods)*
- Kotlin CLI (07): M0 token from keychain; manifest authoring + **deterministic
  IDs (ULID write-back) + anchor injection**; `push/--dry-run/--diff`;
  markdown→blocks; hub/card/place verbs; the **`.claude/skills/dayfold/`**.
- **Tests:** re-push idempotent (no dup); rename keeps IDs stable; `--diff`
  reads via the M0 token. **Acceptance:** `dayfold push <manifest>` → server
  has it; second push is a no-op diff; **the `.claude/skills/dayfold/` never
  reads token/FCK — the binary owns auth+keychain (07 invariant)**.

> **M0 SURFACE = BRIEFING FEED ONLY (D2, 2026-06-18).** Event Hubs render +
> deep-link are **deferred to the next slice (M0.5/M1)**. The Hub schema stays
> dormant in DB/CLI (still authorable); the M0 app renders **one card feed**.
> This removes the entire Hub-detail render + deep-link-scroll work from P3.

### P3 — CMP client (M0 slice)  *(NOT hard-gated; agent + operator Mac for iOS)*
- **State lib (resolved):** default **pin `redux-kotlin 0.6.2` stable** +
  **hand-written root reducer** + manual `selectorState`/`store.select{}` for
  render-isolation; **`1.0.0-alpha01` is an opt-in feature-flag upgrade**
  (1-day-old; two alpha01-only modules). Code calls **`fieldState`** (not
  `fieldStateOf`). For process-death use Compose `SaveableStateRegistry`
  (alpha01-only `compose-saveable` optional). → **no hard block.** (Operator
  preference: INB-11.)
- CMP scaffold (Android+iOS); plaintext SQLDelight cache; sync engine (per-page
  tx + `CacheUpdated` + WAL + foreground-resume/pull-to-refresh).
- Render: **the Now briefing-card feed only** (M3E cards; provenance + trigger
  chips; limited inline markdown via mikepenz). **No Hub detail, no card→block
  deep-link render at M0** (deferred with Hubs). Card tap = card detail/expand
  (no hub destination). **Time-trigger local notifications only** (no
  geofencing). **No Universal Links at M0.**
- **Error/empty/offline states:** first-launch empty cache, sync failure (quiet
  stale indicator), deep-link fallback banner — all non-crashing.
- **Tests:** reducer units, selector, screenshot, deep-link scroll, empty/error
  states. `./gradlew build` gate. **Acceptance:** operator's device renders
  pushed content; tap a card → its hub block scrolls + highlights.

### P4 — Dogfood verify  *(operator + agent)*
- Operator authors via the CLI / a Claude scheduled loop → content appears on
  device. Browser/device E2E of the full flow (auth-less M0 → push → sync →
  render → deep-link → time-notif). **= Gate G1a DoD.**

## Critical path

`P-1 (operator bootstrap) → P0 (schema+host; **INB-10 before schema**) → P1
(DB+API + **token/family provision + seed**) → P2 (CLI) ∥ P3 (client) → P4
dogfood.` The fork edge is **"P1 API live + token provisioned + seed data"**
(API-live ≠ authable) — P3 gets the seed fixture so it isn't blocked on P2.

## E2E conditional fold-in (if INB-10 accepted, before P1 freeze)
Content cols → ciphertext; drop the FTS index; **version-authority = client
supplies / server validates monotonic**; P2 CLI becomes the **encryptor**
(holds `FCK`); P3 client adds **SQLCipher** (`linkSqlite=false`) + decrypt-
once-into-cache + keychain. All already specced in 02/04/06/07/08.

## Verify loop (every package, ADR 0012)
unit+integration+ (M1: Firebase emulator) → preview deploy + verify → promote
prod → health/smoke + browser flow → **auto-rollback on fail** → log prod/cost
actions. Agents run it autonomously within the budget cap.

## Owner split (ADR 0012)
- **Agent-autonomous:** P0/P1/P2 build, tests, preview+prod deploy behind rails.
- **Operator-gated:** INB-9/INB-10 + ADR ratifications; iOS signing + Apple
  account + a Mac for P3; the first prod promote spot-check; any spend > cap.

## Open questions
- Monorepo tool (Gradle for Kotlin + a TS workspace) — settle in P0.
- Whether P3 ships iOS at G1a or Android-first then iOS (Mac/Apple-account
  dependency) — operator call.
