# CL-4 — Client data: typed model, DB, store (design)

**Epic:** `planning/content-detail-epic.md` · **ADR:** 0020 (DB-as-SoT,
`network→DB→store→UI`) / 0022 (typed content) · **Depends:** CL-1 (schema), CL-2
(server serves `type`/`payload`/`privacy`/`hub_ref` on `/sync`) — both on `cl-next`.

## Goal

Carry the typed payloads through **wire → SQLDelight → redux store** so CL-5/CL-6
can render them. Today the client drops everything but `id/kind/title/body_md/
source/not_before/expires_at`. Preserve the ADR 0020 invariants (unidirectional,
instant offline cold-start, no new network path).

## Design decision — payload shape = **wrapper, not sealed interface**

The review suggested `sealed interface Payload`, but the wire is **externally
tagged** — `{"file":{…}}` — and the **codegen source of truth**
(`packages/schema/kotlin-gen/Content.kt`) already emits a **wrapper**
`BriefingCardPayload(file/link/invite/contact/geo/email: T? )`. kotlinx
externally-tagged polymorphism needs a custom serializer; the wrapper is native,
simpler, and matches both the wire and the generated type. **Mirror the wrapper.**
(Server cross-validation (CL-2) guarantees exactly one variant is set.) The
client hand-rolls these in `Model.kt` (it does **not** depend on `kotlin-gen`
today — wiring to generated types stays a separate deferred follow).

## Scope

1. **`Model.kt`** — add to `Card`: `type: String?`, `payload: Payload?`,
   `privacy: CardPrivacy?`, `@SerialName("hub_ref") hubRef: String?`. (`type` and
   `hub_ref` arrive **snake/raw** from the server's DB-shaped `/sync` rows, like
   the existing `target_*`.) Add the wrapper `Payload` + 6 variant data classes +
   `Attachment` + `CardPrivacy`, field-for-field from `kotlin-gen` (all `val`,
   all nullable → immutable + back-compat). `type`/`rsvpState`/`link.kind` kept
   as `String?` (not enums) for forward-compat + parity with `Card.kind`.
2. **`Content.sq`** — add `type TEXT`, `payload TEXT` (JSON), `privacy TEXT`
   (JSON), `hub_ref TEXT` to `card`; thread them through `upsertCard` and
   `activeCards`. (Local cache is disposable, ADR 0020 — no `.sqm` migration
   infra exists; fresh `Schema.create`. **Known M0 limit:** a device with an old
   on-disk schema must clear app data on upgrade; real SQLDelight migrations are
   post-M0. State it.)
3. **`ContentStore.kt`** — `applyDelta` serializes `payload`/`privacy` to JSON
   TEXT (re-encode of the wire object; **lossy-to-model** — fields the client
   model doesn't declare are dropped, accepted at M0 since the cache mirrors our
   own model). `rowToCard` decodes at the **DB→store projection boundary**
   (background dispatcher), **not during Compose recomposition** — the store
   holds decoded objects, the feed never sees JSON. (Re-decode per sync emission
   is fine: ≤200 rows, off the render path; no memo cache needed at M0.)
   `payload` and `privacy` are decoded **independently**, each guarded by
   `runCatching{}.getOrNull()` so one corrupt field never nulls the other nor
   drops the card. Add a private `Json { ignoreUnknownKeys = true }`.
4. **No change** to `SyncEngine` (passes `Card` through), `SyncClient`
   (`ignoreUnknownKeys` already tolerant; richer `Card` captures the fields),
   `Reducer`/`Selectors` (operate on `List<Card>`; selected-detail state is CL-6).

## Out of scope

CL-5 cards / CL-6 detail / CL-7 transition; `@Stable` Compose annotations
(CL-5); selected-detail / `detailStack` nav state (CL-6); wiring to `kotlin-gen`;
two-way RSVP writes (M1, ADR 0016).

## Security / privacy

- Read-only projection; no new network or write path (ADR 0020 preserved).
- `payload`/`privacy` are opaque cached content; `bodyExcerpt` is
  `[E2E-ciphertext @ M1]` — stored as-is in the disposable cache, decrypted-at-M1
  by the same boundary. No claim asserted by the client here (CL-6 audits chips).
- Bad/corrupt cached payload JSON must **not crash** the feed — `rowToCard`
  decode is guarded (skip→null payload + the card still renders title/kind).

## Test plan (`desktopTest`, JdbcSqliteDriver in-memory)

Extend `ContentStoreTest` + a small wire-decode test:
1. Typed card (each of the 6 variants representative) round-trips
   `applyDelta → activeCards`: `type`, decoded `payload.<variant>` fields,
   `privacy.storage`, `hubRef` all survive.
2. A kind-only card (no type/payload) still round-trips (back-compat) — payload
   null.
3. Corrupt `payload` TEXT in the row → `rowToCard` yields `payload=null`, no
   throw (guard test).
4. Wire decode: a `SyncResponse` JSON with `{"type":"invite","payload":{"invite":
   {…}},"privacy":{"storage":"on_device"},"hub_ref":"h1"}` decodes into `Card`.
5. Existing `ContentStoreTest` / `ReducerTest` / `SyncEngineTest` /
   `FeedSnapshotTest` stay green (cold-start + ordering unchanged).

## DoD

Typed payloads survive sync→DB→store→selector; existing desktop tests + snapshots
green; offline cold-start still instant (no decode on the hot path —
once-per-projection). Build: `:client:desktopTest` green; Android APK assembles;
iOS framework links (gated, best-effort).

## Risks

- kotlinx wrapper with all-nullable variants permits multiple set in theory;
  server CL-2 cross-validation prevents it on the wire. Acceptable (cache mirrors
  server). Not enforced client-side at M0.
- Schema-change-without-migration on an existing device DB → documented
  clear-app-data limitation (M0 disposable cache).
