# ADR 0036: Reusable Auth Foundation (Tether) — Hybrid: Keep Dayfold's Auth, New Backends on Better Auth, Package the Client/CLI

## Status

**Proposed** 2026-06-26 (agent-drafted from the auth-reuse survey + two build spikes;
**operator-gated** — platform/vendor choice + maintenance-burden posture, never agent-decided
per `CLAUDE.md`). Immutable once Accepted — supersede, do not edit. **Composes with** ADR 0011
(Auth & Family-Tenancy, Hardened), ADR 0010 (RFC 8628 device grant), ADR 0029 (CLI resource-scoped
grants), ADR 0018 (API host — TypeScript/Vercel), ADR 0013 (client KMP/redux), ADR 0032 (licensing —
Apache client/CLI), and the **business constitution** (scope discipline). Spikes:
`packages/tether-cli/`, `packages/tether-client/`. Research: `packages/tether-client/kmp-publishing-and-secure-storage.md`.

## Context

The operator asked whether Dayfold's working auth could be reused to stand up new authenticated
apps quickly — i.e., is there a reusable "Tether" core hiding in Dayfold's auth stack?

A survey of ~14 auth products/libraries (managed IdPs, self-host servers, and auth libraries) plus
an inventory of Dayfold's own auth surface found:

- **The backend is commoditized.** Device-authorization-grant + bearer-token + org/RBAC backends are
  well-served by mature options (managed IdPs and the **Better Auth** TS library), and Dayfold's own
  server auth (ADR 0011) is hardened and **live in production**.
- **The genuinely unique, nobody-ships-it part is the *client* side:** a Kotlin/JVM **CLI** that does
  the RFC 8628 device login + OS-keychain refresh storage + refresh-on-401; a **KMP client SDK** that
  does the same in-app across Android/iOS/JVM/Web; and the **in-app "owner approves this device, with
  per-resource scopes" UX** (ADRs 0011 §6/7, 0029). No surveyed product ships any of these — and they
  are **backend-agnostic** (pure device-grant + bearer), so they work against Dayfold's API, Better
  Auth, or any standards-shaped backend.

Two spikes were built to test extractability (both compile + test green; both are inert, not wired
into Dayfold's build):

- **`packages/tether-cli`** — Dayfold's CLI auth lifted into a standalone, config-driven module; every
  Dayfold specific (keychain service, creds path, API host/env, tenant noun, OAuth paths) hoisted into
  one `TetherConfig`. The proven device-grant loop / keychain / 0600 file / refresh lock are unchanged.
- **`packages/tether-client`** — Dayfold's in-app client auth lifted into a standalone **KMP** library;
  backend specifics → `TetherClientConfig`, and the **redux coupling removed** (rotation now flows
  through an `onRotate` callback, so any state model can adopt it). Common core tested on the JVM target.

The decision needed: what foundation do future authenticated apps build on, and what do we do with
these spikes — without destabilizing Dayfold's live auth or over-committing to speculative maintenance.

## Decision

Adopt the **Hybrid** foundation.

1. **Do NOT refactor Dayfold's live auth.** ADRs 0011/0029 describe a hardened, production system.
   "Modularizing" it to share with hypothetical future apps is **risk with no user benefit** —
   regression exposure on a security-critical path for zero Dayfold-side gain. Dayfold's server auth
   stays exactly as is.

2. **New projects build their backend on Better Auth** (TS auth library, MIT), not on a re-extraction
   of Dayfold's server code. It matches the "auth-library-inside-your-own-backend" shape, ships native
   device-grant + organizations + RBAC, and exposes approve/deny primitives the in-app UX needs. This
   is a **recommendation for future projects, not a change to Dayfold** (Dayfold keeps its own TS auth
   on Vercel per ADR 0018) and is therefore low-commitment today.

3. **Concentrate reusable investment in the client/CLI + in-app approval UX** — the unique,
   backend-agnostic value — as the `tether-cli` (JVM) and `tether-client` (KMP) modules. These are the
   no-regrets artifacts: worth packaging regardless of which backend any project picks, because
   device-grant + bearer is identical across all of them.

4. **Keep the spikes as spikes until a second real consumer exists.** Do not publish to Maven Central,
   do not wire into Dayfold's build, do not take on a maintained-library cadence speculatively. The
   publishing path (vanniktech → Sonatype Central Portal, all targets from one macOS CI job; see the
   research doc) is **documented but inactive**. Promotion from spike → published library is a separate,
   later decision triggered by an actual second project.

5. **This is explicitly NOT a product/scope commitment.** "Tether" is reusable *internal tooling for
   the learning lab*, not a new product to ship, market, or monetize. Productizing it (selling/
   supporting a reusable auth library) would be a **scope change requiring its own ADR** per the
   business constitution. This ADR does not authorize that, and does not widen any external-action,
   legal, pricing, spend, or children's-data guardrail.

## Rationale

The choice is between three options:

| Option | Upside | Decisive cost |
|---|---|---|
| **A — Extract Dayfold's TS server auth** | Proven, zero new deps, max control + learning value | Own a security-critical codebase **forever**; refactoring the **live, hardened** system to modularize it risks regressions for **zero Dayfold user benefit** |
| **B — Pure Better Auth (drop Dayfold's work)** | Offloads backend auth maintenance; MIT; native device-grant + orgs + RBAC | Young dependency; TS-only backend; still must build the client SDK yourself; no reason to touch Dayfold's working server |
| **C — Hybrid (chosen)** | Leave the working system alone; new projects get a low-maintenance backend; reusable effort goes where it's genuinely unique (client/CLI) | Two backend mental models coexist — but they never have to converge, so the cost is cheap |

The deciding fact from the survey: **no one ships the Kotlin CLI auth, the KMP client SDK, or the
in-app owner-approves-with-scope-picker UX**, and those are backend-agnostic. So the reusable value is
the *client* half, not the server half — which means re-extracting the server (Option A) spends
maintenance budget on the commoditized layer while carrying refactor risk, and Option B throws away a
working server for no gain. Hybrid keeps each piece where it already wins.

Keeping the spikes inert (Decision 4) follows the constitution's maintenance-burden discipline: a
published multiplatform library is a standing cost (CI on a macOS runner, semver, consumer support).
Paying that before a second consumer exists is speculative; the spikes already prove extractability, so
the option is preserved at no carrying cost.

## Consequences

Positive:
- Dayfold's production auth is untouched — no regression risk, no wasted refactor.
- The unique, hard-to-replace work (client/CLI + approval UX) is captured in two compiling, tested
  modules — the option to reuse is banked.
- Future apps have a clear, low-maintenance recipe: Better Auth backend + the Tether client/CLI.
- No new standing maintenance cost is incurred now (spikes stay inert).
- Reusable learning-lab value realized without a scope/product commitment.

Negative / costs:
- Two backend models (Dayfold's TS auth vs Better Auth) will coexist conceptually; mitigated by the
  fact they never need to merge.
- Better Auth is a relatively young dependency; its maturity is a revisit input, and the recommendation
  binds nothing until a real future project adopts it.
- The spikes will **drift** from Dayfold's evolving auth unless periodically re-synced; accepted,
  because they're options not products. If/when promoted, a re-sync pass is the entry cost.

## Revisit Trigger

Reconsider when **any** of: (a) a second real project needs authenticated apps fast (→ decide
spike→published-library promotion + activate the documented publishing path); (b) Better Auth's
maturity/licensing materially changes (→ re-weigh Option B's backend choice); (c) a decision to
*productize* Tether is contemplated (→ requires a separate scope ADR per the business constitution);
or (d) Dayfold's own auth (ADR 0011/0029) changes enough that the spikes are badly stale and a re-sync
is needed to keep the option alive.
