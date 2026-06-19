# 10 — M0 Implementation Plan (dumb-store spine → first dogfood)

> Status: **draft → in review**. Turns the `09 §Build order` into sequenced,
> task-level work packages with acceptance + verify + owner (agent-autonomous
> per ADR 0012 vs operator-gated). Target: **Gate G1a DoD — operator authors
> via CLI, sees it on device daily.** Build behind the ADR 0012 rails
> (test-green-before → preview → verify → promote → rollback).

## Pre-flight gates (resolve in order)

| Gate | Blocks | Owner |
|---|---|---|
| **INB-9** — API host = TS/Vercel | P0 scaffold | operator |
| **INB-10** — ADR 0015 E2EE (changes DDL: ciphertext cols, version-authority, FTS) | **P1 DDL freeze** | operator |
| **redux-kotlin `1.0.0-alpha1` coordinates** confirmed (verification in flight) | P3 client | agent→operator |
| ADR 0005/0006/0007/0015/0016 ratifications | scope lock | operator (inbox sweep) |
| Recovery-floor procedure | **M1 only** (no auth at M0) | operator+counsel |

## Work packages

### P0 — Foundation  *(agent-autonomous; operator picks host)*
- Monorepo: Kotlin client + Kotlin CLI + TS/Vercel API; **JSON-schema as the
  single source** + codegen (TS zod + Kotlin types).
- CI + the ADR 0012 toolchain (Vercel MCP, gh, secret store, per-env config);
  empty preview→promote→rollback pipeline proven green.
- **Acceptance:** codegen emits both type sets from one schema; an empty deploy
  promotes to prod + auto-rolls-back on a forced health failure.

### P1 — Data + API spine  *(agent-autonomous; operator approves first prod promote)*
- DDL migrations (02) — **freeze only after INB-10** (E2E flips ciphertext
  cols + version-authority + drops FTS).
- Content API M0 routes (03): `PUT/GET/DELETE` families·hubs·sections·blocks·
  cards·places, `:archive`, **`/sync`** (keyset + tombstones).
- M0 household-token middleware (04 M0): constant-time compare,
  **content:read+write**, default-deny, cross-tenant 404, mass-assignment
  allowlist, gzip/zip-bomb + size caps, idempotent upsert + parent-exists +
  version.
- **Tests:** integration (Postgres); IDOR matrix; security register #6/#8/#10;
  **sync-tombstone (#11)**. **Acceptance:** curl can upsert a hub + `/sync`
  round-trips it; IDOR + tombstone green.

### P2 — CLI  *(agent builds; operator dogfoods)*
- Kotlin CLI (07): M0 token from keychain; manifest authoring + **deterministic
  IDs (ULID write-back) + anchor injection**; `push/--dry-run/--diff`;
  markdown→blocks; hub/card/place verbs; the **`.claude/skills/familyai/`**.
- **Tests:** re-push idempotent (no dup); rename keeps IDs stable; `--diff`
  reads via the M0 token. **Acceptance:** `familyai push <manifest>` → server
  has it; second push is a no-op diff.

### P3 — CMP client (M0 slice)  *(GATED on redux coordinates; agent + operator Mac for iOS)*
- CMP scaffold (Android+iOS); **hand-written root reducer**; plaintext
  SQLDelight cache; sync engine (per-page tx + `CacheUpdated` + WAL +
  foreground-resume/pull-to-refresh).
- Render: Now feed + Hub detail (M3E + mikepenz **lazy** markdown; **single
  outer LazyColumn keyed by blockId**); **deep-link** (state-keyed,
  `scrollToItem`+highlight, nearest-ancestor fallback); **time-trigger local
  notifications only** (no geofencing at M0).
- **Tests:** reducer units, selector, screenshot, deep-link scroll. `./gradlew
  build` gate. **Acceptance:** operator's device renders pushed content; tap a
  card → its hub block scrolls + highlights.

### P4 — Dogfood verify  *(operator + agent)*
- Operator authors via the CLI / a Claude scheduled loop → content appears on
  device. Browser/device E2E of the full flow (auth-less M0 → push → sync →
  render → deep-link → time-notif). **= Gate G1a DoD.**

## Critical path

`P0 (host) → P1 (DB+API, DDL-freeze after INB-10) → P2 (CLI) ∥ P3 (client,
after redux confirm) → P4 dogfood.` P2 and P3 parallelize once P1's API is
live (CLI writes, client reads).

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
