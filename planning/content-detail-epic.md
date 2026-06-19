# Epic — Typed Content Library, Detail View & Fold Gesture

**Source:** Claude Design import "Family AI dashboard design brief"
(`designs/content/Content-Library.dc.html`, `Detail-Views.dc.html`,
`Detail-Phone.dc.html`, `Tap-To-Detail.dc.html`, `Brand.dc.html`).
**Governs:** ADR 0022 (Proposed — operator-gated). **Design-first (ADR 0008):**
mockups imported; **build gated on operator sign-off**; adaptive two-pane is a
design gap.

This epic turns the import into agent-buildable tasks across **schema → server →
CLI/API → client data → client UI (cards, detail, transition, theme) → adaptive**.
Tracking convention = `TASK-<slug>` (promote to `backlog/now.md` when active).

## Decision gates — RESOLVED 2026-06-19 (INB-15/16/17/18)

| Gate | What | Status |
|---|---|---|
| **G-ADR** | Accept ADR 0022; **D2 storage fork** | ✅ Accepted; **D2 = extend `briefing_cards` in place** (unify→M1) |
| **G-DESIGN** | Sign off imported `designs/content/*` mockups (ADR 0008) | ✅ Phone surfaces signed off (CL-0…7 unblocked); **adaptive pass queued** (CL-10 still blocked) |
| **G-NAME** | "Dayfold" product name | ✅ **Confirmed** (repo slug unchanged) |
| **G-SCOPE** | Guardrail 3 (email-body storage) | ✅ Binding constraint: email authored via CLI/Claude over operator's OWN data, **no server-side Gmail restricted-scope read** (ADR 0022; CL-1/CL-3) |
| **Slice** | How many types in M0 | ✅ **All 6** (operator widened the 2-type rec) |

---

## Task graph (dependencies)

```
CL-1 schema+codegen ─┬─ CL-2 server ──┐
                     ├─ CL-3 CLI/skill │
                     └─ CL-4 client-data ─┬─ CL-5 typed cards ─┐
CL-0 theme ──────────────────────────────┴─ CL-6 detail ──────┼─ CL-7 transition
                                                               └─ CL-8 related-edges
CL-9 map-strategy (spike) ─ feeds CL-5/CL-6 geo
CL-10 adaptive two-pane ── BLOCKED on G-DESIGN-adaptive (design pass first)
```

CL-0 (theme) and CL-9 (map spike) have no schema dependency — can start once
G-DESIGN passes.

---

## CL-0 — Dayfold M3 theme (client)

**Goal:** Replace library-default M3 theming with the brand token set so every
surface renders in-brand, light + dark.
**Files:** new `apps/client/src/commonMain/kotlin/com/familyai/client/theme/`
(`Color.kt`, `Type.kt`, `Shape.kt`, `Theme.kt`); wrap `FeedApp`/root in
`DayfoldTheme { }`. Fonts: bundle **Outfit** + **Figtree** as Compose resources
(`composeResources/font/`), Material Symbols Rounded as an icon font or migrate
to `androidx.compose.material.icons` equivalents (decide in task).
**Spec (exact tokens in `designs/Brand.dc.html` digest):** full light + dark
`ColorScheme` (primary `#C0381E`/dark `#FFB4A3`, secondary teal, tertiary
violet, surface ramp warm off-white/brown, plus custom roles `privacy*`,
`map*`, `provider-chip`); `Typography` (Outfit 600 for display/title with
negative tracking, Figtree for body/label, uppercase positive-tracked kickers);
`Shapes` object (`extraSmall 8 / small 12 / medium 16 / large 26 / extraLarge
32` dp; pills via component shape 999); warm-tinted elevation overlay.
**Use M3 Expressive APIs (available on 1.9.3):** wrap in
**`MaterialExpressiveTheme`** (dep `org.jetbrains.compose.material3:material3`
expressive variant + `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`),
supply `motionScheme = MotionScheme.expressive()` (spring-based, not bezier) so
CL-7 + component motion inherit it. This closes the "M3 2023 wearing an
Expressive label" gap the project review flagged — prefer springs, emphasized
type, and shape over classic-M3 tween.
**Android dynamic color:** per ADR 0009, allow dynamic color to remap seeds on
Android 12+; Dayfold scheme is the fallback + the iOS/desktop/web scheme.
**Test:** snapshot the existing feed under `DayfoldTheme` light+dark (no
regression); a `ColorScheme`-completeness unit check.
**DoD:** feed renders in Dayfold light+dark; tokens centralized; fonts load on
android+desktop+iOS; snapshots green.

## CL-SNAP — rk snapshot scenes + rk devtools bridge (dev-loop foundation)

**Goal:** Stand up the **`rk` redux-kotlin CLI** (Homebrew, 1.0.0-alpha02) as the
client's rapid-confirmation + golden-diff harness, so every later UI task (CL-5/6/7)
verifies by rendering a state→PNG in ms and diffing against a golden — and so
agents inspect redux behavior as text. **Realizes ADR 0019's deferred golden-diff
+ CLI items.** Full how-to: `processes/agent-dev-loop.md` (rk sections).
**Files:** new `apps/client/src/desktopMain/.../snapshot/Snapshots.kt`
(`snapshotApp { scene(...) }` registry + `main { runCli(args) }`); a
`:client:snapshotUi` Gradle task (group `render`); debug-only `BridgeOutput`
registration in the store init; `shots.json` batch manifest; goldens dir
(`designs/goldens/` or `src/test/resources/snapshots/`).
**Scope:**
- Add test-scoped `redux-kotlin-snapshot`. **⚠ confirm availability** — the docs
  say it is *not yet on Maven Central*; the operator owns/maintains reduxkotlin
  and `rk` is now published, so verify the published coordinate/version (or
  publish it) **before** committing CL-5/6/7 to this harness; fallback = keep the
  hand-rolled `FeedSnapshotTest` until the dep is published.
- Define **one scene per surface** with **presets = states** and **theme =
  light|dark**: `feed`(loaded/empty/loading), one per **card type** (×default/
  urgent), one per **detail type**, the **fold transition** key frames where
  capturable. Scenes build a real store + render the actual composable (no mocks).
- Wire **`rk devtools` BridgeOutput** (127.0.0.1:9090) into the debug store so
  `rk devtools actions/diff/state/tail` work against the running android/desktop app.
- **CI:** add a `:client:snapshotUi --batch shots.json --golden-dir … --json`
  step to the client job (exit 1 on drift) — the golden-diff gate.
**Test:** `rk snapshot --list` shows our scenes; a known-good shot `--verify`
passes; a deliberately-broken render fails the batch with a dashboard diff.
**DoD:** `:client:snapshotUi` renders+verifies our scenes headlessly; goldens
committed; CI fails on visual drift; `rk devtools` reads live actions/state from
the running client. **Blocks-soft:** CL-5/6/7 *should* land their scenes here as
they build (each UI task adds its scenes + goldens).

## CL-1 — Schema + codegen: typed content + detail payload

**Goal:** Make `type` + per-type `payload` + detail fields **first-class and
fully code-generated** (TS + Kotlin), per ADR 0022 D1/D2.
**Files:** `specs/domain-model/schemas/content.schema.json`; codegen outputs
`apps/api/src/generated/*` + `packages/schema/kotlin-gen/Content.kt`; codegen
runner (`npm run codegen`).
**Scope:**
- Define the 6 content types as a discriminated union with **typed `$defs`
  payloads** (no more `z.any()`): `file`(filename, mime, size, pages, source,
  owner, modified, sharedWith, docRef), `link`(url, domain, title, ogDesc,
  favicon, kind=page|form, fieldCount, closesAt, savedAt), `invite`(eventName,
  host, startAt, place, rsvpBy, rsvpState, guestCount, confirmedCount, notes),
  `contact`(name, company, role, phone, email, address, hours, linkedEventId,
  deliveryWindow), `geo`(label, address, lat, lng, etaMin, distance, travelMode,
  parking, leaveBy, linkedEventId), `email`(from, fromAddr, subject, date,
  threadLen, bodyExcerpt, attachments[], labels[]).
- Add common detail fields: `kicker`, `actions[]` (label/icon/style),
  `meta[]` rows, `provenance{source}`, **`privacy{storage}`** (ADR 0022 D4),
  `relatedKicker` + `related[]` (typed refs → CL-8).
- Decide per **ADR 0022 D2**: unified `content_item` vs extend `briefing_cards`.
  Keep the **cleartext/ciphertext column split boundary** (ADR 0015/0017)
  annotated so M1 E2EE lands cleanly.
- Versioning unchanged (M0 = server-bump).
**Test:** schema validates example docs per type (add `specs/.../examples/`);
codegen produces compiling TS + Kotlin; round-trip serialize test in client +
zod parse test in server.
**DoD:** 6 types codegen'd both sides, payloads strongly typed, examples
validate, no `z.any()` for payload.

## CL-2 — Server: typed storage, validation, sync

**Goal:** Persist + serve typed content with payloads; keep keyset sync +
tombstones.
**Files:** new migration `apps/api/migrations/000N_typed_content.sql`;
`apps/api/src/repo.ts`; `apps/api/src/app.ts`.
**Scope:** per D2 — either new `content_item` table (type, payload jsonb,
provenance, privacy, triggers, version, cleartext routing cols + keyset index on
`(family_id, updated_at, id)` + `touch_updated_at`) **or** add columns to
`briefing_cards`. Upsert validates the **typed** Zod payload (nested, per type).
`/sync` includes the payload; cursor/tombstone model unchanged. Migration must
preserve the throwaway-but-live dogfood data path (back-compat read of old
cards, or a documented reset since M0 data is disposable).
**Test:** integration vs live PG (existing harness) — upsert each type, sync
round-trip, tombstone, bad-payload rejection (422). Cursor stability test
retained.
**DoD:** all 6 types upsert+sync+soft-delete green vs live PG; invalid payloads
rejected; keyset/tombstone invariants intact.

## CL-3 — CLI + Claude-skill: typed authoring

**Goal:** The **content-API + CLI + skill** wedge can author typed cards (this
is the product's MVP authoring loop — operator + Claude Code via CLI).
**Files:** `apps/cli/src/main/kotlin/Main.kt`; per-type JSON **example
templates** (`apps/cli/templates/<type>.json`); a Claude **skill/doc** update so
the authoring loop emits valid typed payloads.
**Scope:** validate the pushed JSON against the **generated Kotlin types**
before PUT (fail fast with field errors, not a server 422); optional
`familyai push --type invite card.json`; `familyai template <type>` to emit a
starter. Keep env config + Bearer.
**Test:** CLI unit test — valid type passes, malformed payload rejected locally;
a golden template per type parses.
**DoD:** operator/agent can author all 6 types from the CLI with local
validation; templates documented.

## CL-4 — Client data: typed model, DB, store

**Goal:** Carry typed payloads through wire → SQLDelight → redux store.
**Files:** `apps/client/src/commonMain/kotlin/.../Model.kt` (typed `Card` +
`sealed interface Payload` with 6 variants); `.../db/Content.sq` (add `type`,
`payload` (TEXT/json), privacy/provenance cols; update `upsertCard`,
`activeCards`); `ContentStore.kt`/`SyncEngine.kt` (decode payload);
`Reducer.kt`/`Selectors.kt` (selected-detail state — see CL-6).
**Scope:** unidirectional `network→DB→store→UI` preserved (ADR 0020). Payload
stored as JSON column, decoded to the sealed type on projection. No new network
path.
**Test:** `ContentStoreTest` round-trips each payload type; reducer test for
new actions; sync→DB→store test with typed payload.
**DoD:** typed payloads survive sync→DB→store→selector; existing sync tests
green; offline cold-start still instant.

## CL-5 — Client UI: 6 typed Now cards

**Goal:** Render the six Now-card layouts in-brand, light + dark.
**Files:** new `apps/client/src/commonMain/kotlin/.../cards/` (one composable
per type + a `CardItem` dispatcher on `type`); refactor `CardRender.kt`/
`FeedScreen.kt` to dispatch by type.
**Spec (digest):** standard card (file/link/email/contact-Now); **distinct
layouts** — invite (coral `primaryContainer` bg + inline **Yes/No** RSVP row),
contact (avatar + inline **Call/Chat** round buttons), geo (92px **map strip**,
see CL-9). Each: icon tile + accent, kicker chip (type+urgency), title (Outfit),
body, primary action pill, provenance pill. M3: ElevatedCard 26dp, pill buttons,
assist/filter chips, circular icon buttons. States: default, urgent, inline-
actionable, dismissed-on-answer (invite), loading skeleton.
**Test:** add an **rk scene per card type** (presets = states, theme = light|dark)
+ commit goldens (CL-SNAP harness); interaction test for inline actions
(dispatch correct action; M0 RSVP = display-only); golden skeleton state.
**DoD:** all 6 cards match mockups light+dark; inline actions dispatch; tap
opens detail (wires to CL-6/CL-7); snapshots green.

## CL-6 — Client UI: DetailScreen

**Goal:** Full-screen detail — one skeleton, per-type hero.
**Files:** new `.../detail/DetailScreen.kt` + per-type hero composables;
navigation state (`selectedCardId: String?`) in `AppState` + `OpenDetail`/
`CloseDetail` actions in `Reducer.kt`; root hosts feed↔detail.
**Spec (digest):** in-hero colored top app bar (back + share + overflow); hero
header (46dp icon tile, kicker chip, Outfit 27sp title, sub); scroll body:
**HERO MEDIA** (per type: file page-thumb, link OG card, invite date/RSVP card,
contact avatar+reach FABs, geo map+ETA+Navigate, email message+attachment) →
**actions row** (filled/tonal/outlined pills) → **DETAILS** meta list →
**PROVENANCE + PRIVACY** chips → **RELATED** rows (→ CL-8). No detail-level FAB.
**Navigation:** hand-rolled redux nav (no nav lib needed for one feed↔detail
pair) — keep it minimal so it composes with CL-7's SharedTransition.
**Test:** **rk scene per detail type** (theme light|dark) + goldens; back action
clears selection (verify via `rk devtools state`/`diff`); deep-link actions emit
handoff intents (allowlisted schemes only, reuse `CardRender` link allowlist).
**DoD:** detail renders for all 6 types light+dark; open/back via redux; matches
mockups; snapshots green.

## CL-7 — Client UI: the fold gesture (container transform)

**Goal:** Card→detail **container transform**; the signature interaction.
**Files:** wrap feed+detail host in `SharedTransitionLayout`;
`AnimatedContent`/`AnimatedVisibility` keyed on `selectedCardId`; shared bounds
key `"card-$id"`.
**Spec (digest):** corner morph **26→0dp**; container bounds card→full; content
**fades in after** the grow starts (~300ms); **scrim** 0→0.18 (~340ms); open
~460ms / **back ~420ms (asymmetric, faster)**; emphasized-decelerate
`CubicBezierEasing(0.2f,0f,0f,1f)` **or** `MotionScheme.expressive()` spatial
spring. Share the **container bounds only**, fade children (cheap). **Perf:**
media (geo map, file thumb, link OG) must be **placeholdered/loaded before** the
morph — never block the transition on a fetch.
**API availability (verified 2026-06-19, supersedes earlier review notes):**
`SharedTransitionLayout`/`Modifier.sharedBounds` (Compose 1.7+) **and**
`PredictiveBackHandler` are **both present in the pinned Compose-MP 1.9.3** — **no
1.10 upgrade is required** (an earlier review flagged this as Critical; it is a
false positive). Both are `@Experimental*` → opt-in and pin in the spike.
**Predictive-back:** wire `PredictiveBackHandler` to scrub the shared bounds on
Android (and the iOS interactive-pop); desktop/web fall back to back-button/Esc
(see Platform shims). **Spike (½ day, first step of CL-7):** confirm the
experimental shared-transition + predictive-back APIs behave on android+desktop
(+iOS framework-link) at 1.9.3; if an experimental API misbehaves, fall back to
an `Animatable`-driven bounds+corner morph (the WAAPI prototype's approach) —
documented fallback, not a blocker.
**Test:** transition is hard to snapshot — assert state machine (open→grown→
close→idle, `_gen`-style guard against rapid re-tap); a manual on-device/desktop
verification note; optional frame-capture at t=0/mid/end if the harness allows.
**DoD:** tap grows card→detail, back reverses faster; no snap-back; media never
gates the morph; rapid-tap safe. Predictive-back either landed or filed.

## CL-8 — Related-edges (relation model)

**Goal:** The `related[]` cross-links (same-email, same-thread, same-hub,
same-trip, attachment↔email).
**Files:** schema (CL-1 `related[]` typed refs), server (CL-2 — serve edges),
client (CL-4 decode, CL-6 render related rows).
**Scope:** an edge = `{relation, targetId, targetType}`; render as related rows
(38dp icon tile + 2-line + chevron) that navigate to another detail (re-enters
CL-7 transition). Email attachment **promotes to its own `file` item** linked
back to the email.
**Test:** edge round-trips; tapping a related row opens the target detail.
**DoD:** related sections populate per type and navigate; attachment↔email link
holds.

## CL-9 — Map render strategy (spike → impl)

**Goal:** Decide + implement the geo card/detail map (currently a CSS
placeholder).
**Scope:** evaluate **static map image** (privacy-friendly, one image; provider
+ cost) vs **embedded maps SDK** (heavy, per-platform, licensing) vs **keep the
stylized placeholder** for M0. Must honor **ADR 0014** (live position never
leaves the device; on-device routing handoff). Recommend: M0 = stylized
placeholder + one-tap **Navigate** handoff to the OS maps app (no embedded map,
no key, no position leak); revisit embedded/static for M1.
**Deliverable:** a short spike note + the chosen impl for the geo card strip +
detail hero.
**DoD:** geo card+detail render a map affordance + working Navigate handoff
within the ADR 0014 privacy posture; decision recorded.

## CL-10 — Adaptive two-pane detail (BLOCKED — design gap)

**Goal:** Tablet/web/foldable **list-detail** (`ListDetailPaneScaffold`) hosting
the detail in the detail pane.
**Status:** **BLOCKED on a design pass** — only **phone** detail is designed
(`Detail-Views.dc.html` is a desktop *catalog*, not an expanded layout). Per
ADR 0008, the design brief is written: **`designs/DESIGN-BRIEF-content-
adaptive.md`** — hand it to a fresh Claude Design session. It specifies the
expanded/two-pane detail (`ListDetailPaneScaffold`), the 6 detail-pane types,
and the fold-gesture-at-width decision (in-pane transform vs shared-axis swap).
Output → `designs/content/adaptive/`; operator sign-off → unblocks this build.
**DoD (design):** expanded detail mockup signed off → unblocks the build task.

---

## Cross-cutting test & process

- **Snapshot harness = `rk snapshot`** (CL-SNAP): one **scene** per card + per
  detail, **presets = states**, **theme = light|dark**; a golden per shot,
  verified in CI (`:client:snapshotUi --batch … --golden-dir …` exits 1 on
  drift). This is the golden-diff CI ADR 0019 deferred — not deferred anymore.
  Agent rapid loop: `rk snapshot --scene <x> --preset <s> --out /tmp/x.png` then
  `Read` it. (Falls back to the hand-rolled `*SnapshotTest` only if the
  `redux-kotlin-snapshot` dep isn't published yet — see CL-SNAP.)
- **Server** tests run vs **live PG** (existing pattern). **CLI** local-validate
  tests. **Schema** example-validation in CI.
- Each task ships **subagent-driven** with spec+plan in
  `docs/superpowers/{specs,plans}/` and the standard **2-round adversarial
  review + final whole-branch review** (CLAUDE.md process rules).
- **Toolchain** (don't re-derive — `processes/agent-dev-loop.md`): Kotlin
  2.3.20, Compose-MP **1.9.3** (SharedTransitionLayout + PredictiveBackHandler +
  MaterialExpressiveTheme/MotionScheme **all present at 1.9.3**, experimental/
  opt-in — **no upgrade needed**), SQLDelight 2.3.2, redux-kotlin 1.0.0-alpha01,
  ktor 3.1.1. **material3 expressive variant** must be on the classpath for CL-0.

---

# Review findings folded (8-dimension multi-agent review, 2026-06-19)

Reviewed across correctness, gaps, security/privacy, simplicity, performance,
best-practice, M3-Expressive fidelity, and cross-platform reuse. Convergent
fixes below are now binding on the task specs above.

## M0 slice

> **DECIDED (operator, INB-18): M0 ships ALL 6 content types** — operator
> widened the review's recommended 2-type slice for full-surface coverage
> sooner. The review's minimum-slice analysis is retained below for context and
> as the fallback if codegen/UI cost balloons. The other M0 decisions
> (extend-in-place, codegen spike first, base transition first) **still apply**.

**Review's minimum coherent slice (NOT taken — for reference):**
- **2 content types, not 6** — **`file` + `invite`** (file = heaviest hero/
  provenance; invite = most interactive). Adds the other 4 in a follow-on slice
  once the renderer + detail + fold pattern is proven.
- **D2 = extend in place (Option B) for M0**, not the unify migration. M0 data
  is throwaway; `briefing_cards` works; unify's real payoff is the single E2EE
  column boundary — **file the unified `content_item` migration as M1 debt**
  (you migrate for E2EE anyway). *(Updates ADR 0022 D2 recommendation.)*
- **CL-1 first as a ½–1 day codegen spike** (define the 2 payloads, run TS+Kotlin
  codegen, round-trip a serialize/deserialize test both sides). If codegen rat-
  holes, fall back to hand-maintained types — don't let it block the UI work.
- **Defer to a later slice:** CL-3 (CLI typed authoring — you already push JSON
  by hand; fold a ½-day per-type template generator into CL-1 instead), CL-8
  (related-edges), CL-9 real maps (ship placeholder + Navigate handoff only),
  CL-10 (adaptive), predictive-back polish.
- **M0 build order (6 types, per INB-18):** CL-0 → **CL-SNAP** (rk snapshot/
  devtools harness — early, so every UI task verifies via golden) → CL-1(codegen
  spike, all 6 payloads) → CL-2(extend in place) → CL-4 → CL-5(6 cards) →
  CL-6(detail, no related) → CL-7(base transition). CL-8/CL-9-realmaps/CL-10 +
  CLI typed-author (CL-3) stay deferred; map = placeholder + Navigate handoff.

## Inline-action write-path (CRITICAL correctness — ADR 0020/0016)

M0 is **unidirectional read-only** (`network→DB→store→UI`, ADR 0020); two-way
intents are **reserved for M1** (ADR 0016). Therefore in M0:
- **OS handoffs are fine** (they don't write our backend): Call/Text (`tel:`/
  `sms:`), Navigate (`geo:`), Reply / Open-in-Mail (`mailto:` / share), Open-form
  (`https:`), Copy (clipboard), Share (share sheet).
- **`invite` RSVP Yes/No is a content MUTATION with no M0 write path.** Do **not**
  ship a server-writing RSVP in M0. Options: (a) the invite's primary action is a
  **Reply handoff** (open the email to RSVP), with the Yes/No row shown as
  **display of current `rsvpState`** (authored value), or (b) defer the inline
  RSVP control to M1 when ADR 0016 intents land. **CL-5/CL-6 must pick (a) for
  M0.** Same rule for any "mark done"/mutate affordance.

## Security & privacy hardening (CRITICAL/Important)

- **Email type vs Guardrail 3 — operationalize G-SCOPE.** `email` (+`invite`/
  `contact`) store sender/body-excerpt/phone/address. **Binding constraint:**
  email content is authored via **CLI / Claude reasoning over the operator's
  OWN data only — never a server-side Gmail *restricted*-scope OAuth read.** That
  is what keeps M0 clear of CASA (Guardrail 3). If that posture ever changes it's
  a **new ADR**, not a task tweak. State this in CL-1 + CL-3; *(ADR 0022 G-SCOPE
  updated)*.
- **Actions are a closed typed union, not freeform URLs.** CL-1 models
  `actions[]` as a **discriminated union of vetted action IDs**
  (`open_url{https}`, `call{tel}`, `message{sms}`, `email{mailto}`, `navigate
  {geo}`, `copy`, `share`, `add_to_hub`) — **no arbitrary-scheme/`params:any`**.
  Extend `CardRender` `ALLOWED_SCHEMES` deliberately (add `sms:`); reject
  `javascript:`/`intent:`/`file:`/`content:`. `mailto:` params (subject/body)
  must be sanitized.
- **OG-unfurl = no server fetch, no client re-fetch.** Link OG metadata is
  **stamped at author time** (by the authoring loop), stored immutable; the
  **server never fetches the URL** (no SSRF) and the **client never re-fetches on
  render** (no timing oracle). CL-2 validates URL *syntax* only. Favicon →
  hardcoded accent or Material icon, not a live fetch.
- **Privacy chips must be structurally true (ADR 0014/0015 honesty).** "Location
  never leaves" is honest only for **live position** (ADR 0014); place *coords*
  are family-visible content — phrase accordingly. M0 plaintext content is on the
  server too → "Stored on your device" must mean **"a copy is cached on your
  device"**, not "only". CL-1 ties each chip to an enforced code/schema boundary;
  CL-6 audits chip copy per type. No claim without an enforcing boundary.
- **Tenancy:** new payload fields ride existing `authorizeTenant`; add an IDOR
  invariant test — a family-A credential cannot write `linkedEventId`/related
  refs pointing at family-B content (CL-2/CL-8 tests).
- **E2EE column split = machine-readable.** CL-1 marks every ciphertext-candidate
  field (`body`, `payload`, `triggers`, place `label/lat/lng`, email body) with a
  schema annotation (e.g. `"x-e2e":"ciphertext"`) that **codegen preserves** as a
  Kotlin/TS marker — so ADR 0015/0017 M1 encryption drops in without re-modeling.

## Platform shims — expect/actual (CRITICAL xplat)

These **cannot live in commonMain**; add `apps/client/src/commonMain/.../platform/
PlatformActions.kt` (expect) + androidMain/desktopMain/iosMain actuals. commonMain
dispatches **action IDs**; the actual performs the effect:
`openUrl` · `call` · `message` · `share` (Android `ShareCompat` / iOS
`UIActivityViewController` / desktop+web fallback) · `copyToClipboard` ·
`hapticTick` (Android vibrate / iOS Taptic / desktop+web no-op) · predictive-back
affordance (Android framework gesture / iOS interactive-pop / desktop Esc+button /
web button). **All card/detail rendering stays in commonMain** — only these
effects are per-platform. Map render (CL-9): commonMain stylized placeholder +
`navigate` handoff (no embedded SDK at M0 → stays in commonMain).

## Performance (Critical/Important)

- **Decode payload OFF the render path.** Don't `Json.decodeFromString` per
  `activeCardsFlow` emission or per recomposition. Decode once in `SyncEngine`/
  `ContentStore` projection into the cached `sealed Payload`; feed never touches
  raw JSON. Sort lives in the SQL query (`activeCards` already orders) — don't
  re-sort in `Selectors` at render.
- **`@Stable`/immutable payloads** (all `val`, no `var`) so cards/detail are
  skippable — codegen must emit `@Stable` sealed variants (also a best-practice
  finding).
- **Media never gates the morph (CL-7):** preload/placeholder hero media on tap;
  start the transition immediately; fade media in on a separate state channel so
  it can't recompose the animating bounds.
- **Sync payload size:** keep `/sync` card payloads small (Now-card fields);
  if detail payloads grow (email body, attachments), consider a **separate detail
  fetch** rather than bloating 200-item sync pages. Subset bundled fonts to Latin.

## Best-practice (Important)

- **Discriminated union, done right.** JSON-schema `oneOf` + `discriminator:
  {propertyName:"type"}` → zod `z.discriminatedUnion("type",[…])` (kills the
  current `z.any()`) and Kotlin `@JsonClassDiscriminator("type") sealed interface
  Payload` with `@Serializable` variants. This is CL-1's core deliverable.
- **Detail nav = a stack, not a scalar.** Related-edges chain detail→detail, so
  model `detailStack: List<String>` in `AppState` (push/pop actions, middleware-
  driven per ADR 0013 Rule E), saveable across process death — not a lone
  `selectedCardId`. Bounds-key by the top of stack for CL-7.
- **Payload storage:** JSON `TEXT` column decoded at the store boundary (matches
  ADR 0020 "DB is truth"); handle `DecodeException` on bad cache data (skip+log,
  don't crash). Migration numbering: next is **`0003_*`** (after `0001_m0_init`,
  `0002_auth`).

## Cross-cutting gaps (Important/Minor — add to each UI task's DoD)

- **Accessibility (ADR 0009 WCAG-AA):** per card/detail — ≥48dp targets, AA
  contrast (check the warm palette, esp. coral-on-coral invite), TalkBack/
  VoiceOver labels, Dynamic Type scaling, **prefers-reduced-motion** path that
  swaps the container transform for a fade. Add to CL-5/6/7 DoD.
- **i18n:** **M0 = English-only by explicit decision** (state it; don't hardcode-
  by-accident — keep strings centralized for later extraction).
- **States:** every card/detail ships **empty / loading (skeleton) / error /
  dismissed** per the design — transcribe from `Now.dc.html`/`Content-Library`.
- **Bottom nav ownership:** the feed↔detail pair nests inside the app's
  Now/Hub/Settings nav (ADR 0009 bottom-bar→rail→drawer). The fold gesture's
  `SharedTransitionLayout` must wrap **above** the nav host. Owner = a small
  nav-shell task (fold into CL-6 or note as a prerequisite).
- **Fonts:** Outfit + Figtree are **SIL OFL** (bundling OK; confirm at task
  time). Material Symbols → decide bundle-font vs `compose.material.icons`
  mapping in CL-0.
- **Snapshots:** golden-diff is now **`rk snapshot`** (CL-SNAP) — commit goldens,
  CI verifies via `:client:snapshotUi --batch … --golden-dir …`. Supersedes the
  ADR-0019 Roborazzi-DIY note.
- **Web target is NOT built** (`apps/client/build.gradle.kts` has no `wasmJs`).
  Strike "web" from CL-10's claims → **tablet/foldable/desktop** only until a
  `wasmJs()` target is added (out of scope here).
