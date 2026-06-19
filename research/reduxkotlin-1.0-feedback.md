# reduxkotlin 1.0.0-alpha01 — integration feedback (toward stable 1.0.0)

Dated findings from wiring `1.0.0-alpha01` into family-ai-dashboard: a real
KMP app — Kotlin 2.3.20, Compose-MP 1.9.3 (desktop) + AGP 8.7.2 / androidx
Compose (Android), `f(store.state) → UI`. Operator maintains reduxkotlin and can
action these. Severity: **P0** blocks adoption · **P1** sharp edge · **P2** nice-to-have.

## Blockers / bugs

**B1 · P0 · `redux-kotlin-compose` doesn't pull `redux-kotlin-granular`
transitively.** `FieldStateKt` (which hosts `selectorState`/`fieldState`)
references `org.reduxkotlin.granular.SubscribeFieldsKt` /
`SubscribeFieldsKPropertyKt`. The compose **POM** declares
`redux-kotlin-granular-jvm`, but the **Gradle Module Metadata** variant a
consumer resolves does NOT, so granular is absent from the compile classpath →
the whole `FieldStateKt` facade fails to load → **`unresolved reference
'selectorState'`** with no hint. `StableStoreKt` (no granular dep) resolves
fine, which makes it look random. *Fix:* add `redux-kotlin-granular` to the
compose module's GMM `api`/`implementation` variant deps (not just the POM), or
inline the granular pieces compose needs. Workaround consumers need today:
`implementation("org.reduxkotlin:redux-kotlin-granular:<v>")` explicitly.

**B2 · P1 · Cross-module Kotlin-metadata skew.** `redux-kotlin-compose` is
published with `mv=[2,3,0]` (needs a Kotlin **2.3.x** consumer) while
`redux-kotlin-core` / `-threadsafe` read fine from Kotlin 2.2.x. A consumer on
2.2.x gets `unresolved reference` on the compose APIs only. *Fix:* publish all
modules against one Kotlin baseline (or document the min Kotlin per module). At
minimum, state "redux-kotlin-compose requires Kotlin ≥ 2.3.x" prominently.

## API ergonomics

**E1 · P1 · `selectorState` / `fieldState` are extension functions, but that's
undiscoverable.** `selectorState(store) { … }` (top-level call) → `unresolved
reference`; only `store.selectorState { … }` works. The failure mode is
identical to "dependency missing" (B1), so a user can't tell which problem they
have. *Suggestions:* (a) docs / the ai-agents page should show the receiver
form; (b) consider also exposing a top-level `selectorState(store, selector)`
overload, since IDE autocomplete on a `Store` surfaces extensions but the
"unresolved" error for the wrong form is silent.

**E2 · P2 · `rememberStableStore` returns a value class that doesn't satisfy the
`selectorState` receiver.** `rememberStableStore(store)` → `StableStore<S>`, but
`selectorState`/`fieldState` extend `TypedStore<S,*>`, so you call them on the
raw `store`, not the stable wrapper — leaving it unclear what `rememberStableStore`
is *for* in the `f(store.state)→UI` path. Clarify the intended composition (does
selectorState already handle stability/recomposition keys internally?).

**E3 · P2 · Compose-runtime coupling.** `redux-kotlin-compose` pulls
`org.jetbrains.compose.runtime:runtime:1.11.1` (→ androidx 1.11.2). A consumer on
Compose-MP 1.9.x silently gets the runtime bumped to 1.11.2. Worth documenting
the Compose/Compose-MP compatibility floor for the compose module.

## Missing / requested (mentioned but not on Maven Central at alpha01)

**M1 · `concurrentStore`** — not a published symbol. Today only
`createStore`, `createThreadSafeStore` (threadsafe module),
`createSameThreadEnforcedStore`, `applyMiddleware`, `TypedStore`. If
`concurrentStore` is the intended 1.0 name for the thread-safe/concurrent store,
publish it (and consider deprecating/aliasing `createThreadSafeStore`).

**M2 · devtools — RESOLVED (my earlier "not published" was wrong).** I searched
`redux-kotlin-devtools` (singular); the real modules are
`redux-kotlin-devtools-{core,inapp,inapp-noop,bridge,remote,ui}`, published at
`1.0.0-alpha01` on Maven Central (+ `1.0.0-SNAPSHOT`). **Now wired + verified
on-device** (ADR 0019): `devTools()` enhancer + the in-app `ReduxDevToolsHost`
drawer (BUBBLE trigger) recording real actions. *Doc nit:* `DevTools.md` text
predates the publish and reads as "not yet published" — update it. *Minor API
notes:* the enhancer↔drawer auto-connect via the `DevToolsHub` singleton (nice);
`DevToolsConfig(instanceId=, name=)` worked with defaults for the rest;
`InAppConfig(triggers=setOf(BUBBLE))` exposes a visible affordance (good for
QA/screenshots).

**M3 · reduxkotlin CLI** — no artifact found (npm/maven/brew). If it scaffolds
stores/reducers or drives devtools, publishing + a one-line install would help.

**M4 · screenshot tooling** — nothing found. If this is Compose screenshot
testing for store-driven UI, a small helper (render `f(state)` → image for a
given state) would pair well with the architecture.

**M5 · P2 · thunk is published (`redux-kotlin-thunk:1.0.0-alpha01`).** Not a gap
— noting we plan to use it so the async sync becomes a dispatched thunk rather
than an out-of-band effect.

## What worked well
- Core store API (`createThreadSafeStore`, `dispatch`, `subscribe`, `state`)
  is stable + clean across KMP (desktop JVM + Android), no surprises.
- `store.selectorState { it }` is exactly the right `f(store.state)→UI`
  primitive once wired — reactive, minimal, Compose-idiomatic.
- Hand-written root reducer + `selectorState` selectors compose nicely; no
  `combineReducers` needed.

## Our versions (repro context)
Kotlin 2.3.20 · Compose-MP 1.9.3 · AGP 8.7.2 · Gradle 9.5.1 (apps), 8.11.1
(android) · JDK 17 · reduxkotlin `1.0.0-alpha01` (core/threadsafe/compose/granular).
