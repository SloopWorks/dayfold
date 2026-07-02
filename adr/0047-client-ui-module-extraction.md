# ADR 0047: Client `:ui` Module Extraction — Incremental Split of the Monolithic `:client`

## Status

**Accepted** 2026-07-02 (operator accepted as written — "yes"; Phase 2
implementation cleared). Was **Proposed** 2026-07-02 (agent-drafted from a
measurement pass + a two-round expert panel — design round-1: 4 agents
(KMP/iOS/Android/Gradle); plan round-2: 3 agents. **Operator-gated** —
module/platform architecture + maintenance-burden class, ADR-class per
`CLAUDE.md`). Operator directed the work + chose the incremental scope
(2026-07-02) and, at the Phase-1 measurement gate, directed "proceed with the
split."

## Context

`apps/client` is a single monolithic Compose-Multiplatform module (~77 commonMain
files: UI + state/reducers/selectors + redux engines + data clients + ContentStore
+ SQLDelight + fake backend) with sqldelight/ktor/coil on its classpath. Measured
(2026-07-02, `specs/client-modularize-measurements.md`):

- Every incremental edit recompiles **~all 115 files** — the Kotlin compiler emits
  the **KT-62686** "incremental compilation might be incorrect" safety fallback and
  recompiles the whole module. IC never narrows to the changed file → **~5s
  recompile per edit**, the same whether a UI or a data file is touched.
- Cheap Gradle levers do **not** move it: `build-cache` was a *net local regression*
  (made `desktopTest` re-run every edit → reverted); `configuration-cache` is clean
  but only ~0.15s/edit; `parallel` is neutral for one module.

This tax is paid on every human UI iteration and every agent dev-loop edit. The
module size — not any tunable — is the controlling variable.

## Decision (proposed)

Extract a new **`:ui`** Compose-Multiplatform module from `:client`, **incremental**:

- **Move to `:ui`** the ~40 files that import Compose/coil (the composables +
  `theme/` + `ui/loading/` + the Compose `cards/` files + `FeedApp` +
  `MediaEnrichment`/`CoilSetup`), the three UI `expect/actual` seams (`QrScanner`,
  `cards/PlatformActions`, `rememberReduceMotion` — each with android/desktop/ios
  actuals), the bundled fonts (composeResources), and the entry points (desktop
  `Main.kt`, iOS `MainViewController.kt`). **`:ui` emits the iOS framework**
  (baseName `client`, static); `:client` keeps its iOS targets but drops the
  framework.
- **`:ui` `api(project(":client"))`.** Engines, reducers, store, data clients,
  ContentStore, SQLDelight, and the Compose-free `cards/` logic (`CardAction`,
  `DetailMeta`, `TypedCardLogic`) **stay in `:client`**. **No dependency
  inversion** this slice — `:ui` simply depends on all of `:client`.
- **`:client` ends Compose-free** (grep-verified). Cross-module `internal` symbols
  consumed by moved files are promoted to `public`.

Rejected (deferred, not this slice): the full `:model`/`:data`/`:ui` split (needs
dependency inversion of the engine↔client coupling); renaming `:client`→`:core`; a
direct KT-62686 workaround hunt (the split is the structural fix and is worth doing
on its own merits).

## Rationale

1. **Smaller compile unit per edit** — a UI edit recompiles `:ui`'s ~40 files, not
   `:client`'s ~115 + generated SQLDelight.
2. **Structural KT-62686 mitigation** — cross-module Compose IC is a common
   KT-62686 trigger; a Compose-free `:client` is expected to **escape the fallback**
   → data/logic edits narrow toward sub-second, confining the penalty to `:ui`.
3. **Test isolation** — editing UI stops recompiling the logic tests (they stay in
   `:client`).
4. `:client` **sheds Compose** from its classpath.
5. **Seeds** the eventual `:model`/`:data` split + a web (wasmJs) target by
   isolating the Compose layer first.

## Consequences

**Positive:** faster + isolated UI iteration loop (magnitude to be recorded at
Phase 2.3 vs the measured baseline); a Compose-free core; cleaner layering seed;
the agent snapshot loop (CL-SNAP) and human loop both benefit.

**Negative / costs:**
- **Full/CI build ~+5–15%** (two compile tasks + double project config + an extra
  klib link step); mitigated by build-cache on CI. **The whole-repo full-build
  regression number is not yet captured** — the worktree lacks the
  `google-services.json` secret to run `assembleDebug` (see Open).
- **Two KMP modules** to configure (android/desktop/ios source sets each).
- **Zero encapsulation** of core from app modules until the deferred `:model`/
  `:data` split (`api` dep exposes everything) — accepted for this slice.
- **Cross-module `internal`→`public` promotions** (e.g. `selectScale`,
  `cardToNowItem`) — a small, real API-surface widening + a new "keep the entry
  glue public" contract.
- Largely reversible (re-merge the modules) but the promotions + build-config
  churn make it non-trivial to undo.

## Composition

Composes **TASK-KMP** (the prior single-module KMP conversion). Relates **CL-SNAP**
(PR #277 — the snapshot registry/tests move to `:ui`; merge-order coordination in
the design §7). Design: `specs/client-modularize-ui-extraction-design.md`; plan:
`docs/superpowers/plans/2026-07-02-client-modularize.md`; evidence:
`specs/client-modularize-measurements.md`.

## Open

- **Whole-repo full-build baseline** (the +5–15% regression check) — deferred;
  needs the `google-services.json` secret (CI or a secret-bearing env). Required
  before the Phase 2.3 regression-DoD claim.
- **Android `assembleDebug` + `google-services` configuration-cache compatibility**
  — untested here (secret absent); re-evaluate CI-side.
- **`:model`/`:data` split + `:client`→`:core` rename** — a future slice (this ADR
  does not commit to it).
- **A direct KT-62686 workaround** — not pursued; the split is the structural fix,
  and `:client` shedding Compose is expected to sidestep the trigger.
