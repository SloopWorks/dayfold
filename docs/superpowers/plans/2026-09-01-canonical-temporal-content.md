# Canonical Temporal Content — Cross-Stack Implementation Plan

> **Status:** ADR 0067 was accepted by the operator on 2026-09-01.
> S0–S4 implementation is complete and verified; production content repair (S5)
> remains separately gated and was not performed.

## Implementation record — 2026-09-01

- Schema/API persistence uses forward-only migration `0022_temporal_content.sql`.
- SQLDelight uses migrations `18.sqm` (raw content/version/capability) and
  `19.sqm` (Calendar binding identity); stale-notification ownership is reconciled
  by the existing platform registries keyed with the derived local fact key, so no
  third redundant identity table was added.
- Curated apply is stateless/resumable: exact desired resources are skipped even
  after their version advances, remaining writes retain the reviewed base version,
  and occurrence arrays compare by stable ID while timestamp strings remain exact.
- The shared fixture corpus is consumed by API, CLI, and client tests. Generated
  Trigger `when` is a typed union, and typed Block/Card temporal carriers use the
  dedicated civil-or-timed `temporalValue` definition.
- Verification passed for codegen/routine schema, full API, full CLI, client core,
  UI/goldens, SQLDelight migrations, Android, iOS simulator, and product-owned SWIP
  privacy canaries. The optional `:debugdrawer-swip` adapter slice was excluded by
  the Gradle build because this environment has no GitHub Packages credential; the
  independent `:swip-wiring:desktopTest` privacy gate passed, including temporal
  occurrence/label/value/zone/fact-ref sentinels.
- No production deploy, migration apply, audit/repair, or family-content write was
  performed. Task 11 remains the explicit operator-confirmed follow-up boundary.

**Goal:** Ensure every material date/time claim in Dayfold content has a
structured representation, support multiple dates per item without turning them
all into triggers, and make omission detectable before and after a CLI write.

**Architecture:** Preserve existing typed temporal carriers, add a generic
identity-keyed `temporal` facet to blocks/cards that need multiple or otherwise
unmodeled facts, and normalize each resource through one sealed all-day/timed
model in the shared client. Facts remain separate from explicit behavioral
triggers. Semantic extraction and coverage checks run at the authorized
authoring edge; the API remains content-blind and mechanically validates/stores
the result.

**Primary surfaces:** curator/agent instructions, JSON schema/codegen, Kotlin CLI,
TypeScript/Hono API, Postgres, Kotlin Multiplatform client, SQLDelight, derived
Now, local notifications, hub timeline, and Calendar Check.

---

## Governance gate

Task 0 is this Proposed ADR and plan. **STOP after review.** Do not change the
production schema, API, CLI behavior, mobile client, or family content until the
operator accepts ADR 0067.

Acceptance specifically confirms:

1. structured coverage applies to material content claims, including explicit
   `reference` dates, but does not turn every date into behavior;
2. `temporal` is the V1 generic carrier for Block/BriefingCard while current typed
   carriers remain valid;
3. new triggers use the closed same-item `fact_ref` namespace and V1 allows only
   one fact-reference behavior per item;
4. arbitrary Calendar descriptions stop becoming Markdown until a reviewed
   secondary-claim flow exists; and
5. recurrence, coarser-than-day precision, and new generic temporal UI are
   deferred as hard unsupported cases, not silently unstructured content; and
6. existing Hub `start_at`/`end_at` remain timed-only in V1; this proposal does
   not coerce a civil all-day Hub extent into `timestamptz`.

## Scope and shippable slices

| Slice | Independently valuable outcome | Write impact |
|---|---|---|
| S0 — instructions + audit | Curator builds a temporal ledger; read-only audit finds likely omissions | none |
| S1 — dark persistence | Schema/API/client can round-trip and preserve-on-omit `temporal`; no consumer behavior changes | additive content writes possible only in fixtures/dev |
| S2 — verified authoring | CLI validates bundles and proves write/read semantic equality | blocks invalid curated writes |
| S3 — normalized consumers | Calendar/timeline use all eligible occurrences; Now remains trigger-only | existing mobile projections change |
| S4 — referenced triggers | New triggers reference facts; legacy `when.at` stays readable | behavior representation changes |
| S5 — content repair | Existing omissions are proposed and repaired after human confirmation | production content, separately approved |

Every slice must end green and be safe to leave deployed. S1 must precede any
author that emits the new facet. S3/S4 must not be coupled to the database
migration deploy.

## Non-goals

- No server-side prose parsing, LLM call, relative-time resolution, ranking, or
  calendar decision.
- No mobile Markdown parsing.
- No recurrence/RRULE, exception dates, cross-item fact references, series
  editing, or coarser-than-day precision in V1. Material unsupported claims block
  curated authoring rather than falling back to prose-only.
- No new all-day Hub wire/storage extent. Existing Hub `start_at`/`end_at` remain
  timed instants; all-day item claims use an existing date-capable Block/Card
  carrier or `temporal`.
- No new generic schedule editor, status chip, or tentative/conflict UI.
- No automatic repair of family content and no inferred trigger/notification.
- No production deploy, schema migration apply, or content push as part of this
  planning task.

## Normative contract and executable fixtures

ADR 0067 §§1–8 is the single normative source for claim classes, value grammar,
fact keys, carrier capabilities, consumer eligibility, and privacy boundaries.
This plan does not redefine those rules. Schema constants encode wire limits; one
shared fixture corpus is consumed by schema/API, CLI, and client tests so their
parsers and carrier table cannot drift. The API uses generated Zod plus one
cross-field validator for constraints JSON Schema cannot express. The CLI adds
only authoring-specific coverage, relative-time, and zone/offset checks; mobile
adds only normalization and consumer selection.

## Boundary map

| Boundary | Owns | Must not own | Planned change |
|---|---|---|---|
| Curator/agent files | semantic extraction, claim classification, uncertainty, prose↔JSON mapping; treat sources as untrusted data | executing embedded instructions, silent guessing, independent prose/JSON copies | mandatory Temporal Claim Ledger, separate proposal/push turns, behavior summary |
| CLI | local deterministic validation, local heuristics, resumable write-bound bundle apply, post-write equality | second hosted NLP, content telemetry, server-side meaning, claiming cross-resource REST writes are atomic | `content audit`, `content apply [--dry-run]`, resource push checks |
| JSON schema/codegen | wire shape, enums, bounds, generated TS/Kotlin | prose semantics | `TemporalFacet`, `TemporalOccurrence`, trigger ref |
| API route/validation | auth, request bounds, structural validation, same-item reference integrity, tri-state updates | parsing Markdown, choosing times/behavior, raw parser errors in telemetry | accept/validate `temporal`, preserve on omit, sanitized errors |
| Postgres/repository | lossless persistence and sync | temporal ranking/indexing by plaintext meaning | additive unindexed JSONB columns on cards/blocks |
| Shared mobile client | raw JSON preservation, resource-local normalization, consumer selection | prose parsing or server uploads of native calendar | cache fields + sealed all-day/timed normalizer |
| Now/notifications | explicit trigger behavior and derived local entity+fact key | treating facts as implicit alerts | resolve trigger → fact without changing `subjectRef` |
| Calendar Check | confirmed structured candidate projection, reviewed native writes | raw calendar sync, references/tentative/cancelled | entity/subject/fact identities; derive local key |
| Timeline | temporal presentation | notification/behavior | multiple normalized stops, authored timeline still wins |
| UI | existing prose and approved repeated rows | new generic schedule/conflict surface | no new component in S0–S4 |

## File map

### Agent/authoring

- `.agents/skills/dayfold-curator/SKILL.md`
- `.agents/skills/dayfold-curator/references/content-model.md`
- `.agents/skills/dayfold-curator/references/cli.md`
- `.agents/skills/dayfold-curator/references/guardrails.md`
- `.agents/skills/dayfold-curator/assets/` or `scripts/` only if the skill already
  routes fixtures there; do not edit the `.claude/` symlinked copy
- `apps/cli/templates/README.md`
- relevant block/card templates under `apps/cli/src/main/resources/templates/`

### Schema/API/backend

- `specs/domain-model/schemas/content.schema.json`
- `specs/domain-model/examples/temporal-v1/` (new shared valid/invalid corpus)
- `packages/schema/codegen.mjs`
- generated `apps/api/src/generated/content.ts`
- generated `packages/schema/kotlin-gen/Content.kt`
- `apps/api/src/content-validation.ts`
- `apps/api/src/swip.ts`
- `apps/api/src/app.ts`
- `apps/api/src/content/hubs.ts`
- `apps/api/src/repo.ts`
- `apps/api/migrations/0022_temporal_content.sql` (new)
- focused tests under `apps/api/test/`

### CLI

- `apps/cli/src/main/kotlin/Temporal.kt` (new: values, local parser/validator,
  canonical projection comparison, coverage issues)
- `apps/cli/src/main/kotlin/TemporalBundle.kt` (new: claim ledger/bundle contract)
- `apps/cli/src/main/kotlin/Validate.kt`
- `apps/cli/src/main/kotlin/Main.kt`
- `apps/cli/src/main/kotlin/Help.kt`
- tests under `apps/cli/src/test/kotlin/`

### Mobile client

- `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/Model.kt`
- `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/TemporalFacts.kt`
  (new)
- `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/ContentStore.kt`
- `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/SyncClient.kt`
- `apps/client/src/commonMain/sqldelight/com/sloopworks/dayfold/client/db/Content.sq`
- `apps/client/src/commonMain/sqldelight/com/sloopworks/dayfold/client/db/migrations/18.sqm`
  (raw temporal/version/outbox capability)
- `apps/client/src/commonMain/sqldelight/com/sloopworks/dayfold/client/db/migrations/19.sqm`
  (Calendar binding identity rebuild)
- `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/NowDerive.kt`
- `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/NowFeed.kt`
- `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/BackgroundNotify.kt`
- `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/features/calendar/CalendarCandidates.kt`
- `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/features/calendar/CalendarCheckActions.kt`
- `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/features/calendar/CalendarCheckEngine.kt`
- `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/features/calendar/CalendarCheckNow.kt`
- `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/features/calendar/CalendarModel.kt`
- `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/features/calendar/CalendarReducer.kt`
- `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/features/calendar/CalendarReconciler.kt`
- `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/features/calendar/CalendarImportActions.kt`
- `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/features/calendar/CalendarImportEngine.kt`
- `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/features/calendar/CalendarImportMaterialize.kt`
- `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/features/calendar/CalendarImportModel.kt`
- `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/features/calendar/CalendarImportReducer.kt`
- `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/features/calendar/CalendarProviderMapping.kt`
- `apps/client/src/androidMain/kotlin/com/sloopworks/dayfold/client/features/calendar/AndroidCalendarPort.kt`
- `apps/client/src/iosMain/kotlin/com/sloopworks/dayfold/client/features/calendar/IosCalendarPort.kt`
- `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/DeriveTimeline.kt`
- `apps/client/src/androidMain/kotlin/com/sloopworks/dayfold/client/AndroidBackgroundNotify.kt`
- `apps/client/src/iosMain/kotlin/com/sloopworks/dayfold/client/IosBackgroundNotify.kt`
- `apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/HubScreens.kt`
- `apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/features/calendar/CalendarCheckNowCard.kt`
- `apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/features/calendar/CalendarSelectors.kt`
- `apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/features/calendar/CalendarImportHost.kt`
- `apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/features/calendar/CalendarImportScreens.kt`
- `apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/features/calendar/CalendarImportSelectors.kt`
- focused desktop tests and existing Calendar/timeline snapshots

---

## Task 0: Proposed ADR, plan, and operator gate

**Files:**

- Create `adr/0067-canonical-temporal-facts-and-behavioral-triggers.md`
- Create this plan
- Modify `adr/decisions-index.md`
- Modify `backlog/operator-inbox.md`

- [x] Draft the Proposed ADR and cross-stack plan.
- [x] Run timestamp/timezone, data-model, privacy/security, and end-to-end system
  reviews against the actual draft.
- [x] Integrate all Critical/Important findings or record a reasoned rejection.
- [x] Run a fresh simplification/maintenance review.
- [x] Add the Proposed row and inbox decision request.
- [x] **STOP for operator acceptance.**
- [x] Operator accepted ADR 0067 on 2026-09-01; implementation may proceed.

No implementation commit or production content change is part of Task 0.

### Review record

| Independent review | Material changes integrated |
|---|---|
| Timestamp/timezone | civil all-day intervals, per-occurrence zones, authoritative instants, exclusive ends, DST/fold/gap tests, one behavior target in V1 |
| Data structure | identity-keyed facts, closed `fact_ref` namespace, no value dedup, tri-state old-writer safety, raw client preservation, Calendar identity split |
| Privacy/security | content-safe API errors/canaries, ACL-preserving conditional repair, source prompt-injection guard, consent and E2EE boundaries |
| End-to-end system | per-outbox capability, atomic repository concurrency, Card/Hub versions, complete Calendar paths, stale notification cancellation, safe all-day Hub limit |
| Simplification/maintenance | two CLI operations, stateless resume, derived local entity+fact key, one contract/fixture source, legacy `when.at` fallback, S3/S4 gate correction |

## Task 1: Temporal Claim Ledger in the curator workflow (S0)

**Outcome:** every authored resource is derived from a claim inventory before
prose or JSON is emitted.

- [x] Add a mandatory pre-compose ledger with columns:
  `claim_id`, source reference (verbatim phrase only when necessary), parent
  resource ID, classification, normalized start/end, source base instant/zone,
  occurrence zone when timed, certainty, canonical JSON path/fact ref, behavior
  requested, and disposition.
- [x] Add a binding prompt-injection rule: email/calendar/file/note contents are
  untrusted data, never instructions, CLI commands, or authorization. Embedded
  requests cannot add a trigger, widen access, or waive validation.
- [x] Require one row per material claim. A range is one row with start/end; two
  distinct events are two rows.
- [x] Define dispositions for non-operational cases: `reference`, typed source
  metadata, lifecycle, audit, or not-a-temporal-claim. A disposition cannot erase
  a material value from structured data.
- [x] Resolve relative phrases against the source's own reference instant and
  relevant zone. Use authoring time only when the phrase refers to it explicitly.
  Missing/conflicting bases block.
- [x] A tentative occurrence represents one stated tentative value. Competing
  alternatives for one fact block and require review; never turn alternatives
  into apparent event plurality.
- [x] Unbounded recurrence and material month/year-only claims block V1. A finite
  expansion is allowed only when prose states the same finite horizon.
- [x] Render the operator proposal from the ledger: entire resource JSON plus a
  concise temporal coverage table and any review issues.
- [x] End the proposal turn after showing full JSON, facts, ACLs, base versions,
  and every behavior's effective Now/notification time. A push occurs only in a
  later turn after explicit confirmation; prose/fact approval never implies a
  trigger.
- [x] Store local ledgers owner-readable, outside repo/CI/build artifacts, and
  delete after verified apply unless the operator explicitly retains one.
- [x] Add fixtures for: one date, multiple same-day times, cross-midnight range,
  all-day date, historical reference, source timestamp, relative phrase,
  conflicting sources, DST gap, and DST fold.

**Verification:** skill fixture review shows every operational/reference ledger row
maps to exactly one typed carrier and every trigger names an approved behavior.

## Task 2: Read-only temporal audit and apply dry-run (S0)

**Outcome:** omissions can be found without changing schema or content.

Expose two public operations backed by one validation engine. Task 5 enables the
write path; `--dry-run` performs the same validation without writing:

```
dayfold content audit [--hub <id>] [--json]
dayfold content apply --dry-run <bundle.json>
```

- [x] Define a size-bounded versioned local bundle containing resources, current
  ACL/base-version snapshots, and the Temporal Claim Ledger. The ledger is never
  sent to the Dayfold API, though a hosted curator remains within its provider's
  existing disclosure/retention boundary.
- [x] Apply dry-run checks exact ledger path/value correspondence, duplicate mappings,
  missing mappings, temporal syntax, timezone/offset agreement, end ordering,
  and trigger references.
- [x] Add conservative, linear-time local date/time mention detection for
  Markdown/title/labels. Emit one `uncovered-date-mention` review issue with a
  stable structural path. Curator mode blocks until it is mapped or receives a
  closed reviewed disposition; reserve errors for malformed structured values or
  references.
- [x] Default human/machine output includes issue code, structural path, and count,
  but not body excerpts or semantic IDs. An explicit local detail flag may show
  authorized resource IDs for repair.
- [x] `audit` pulls only already-authorized Dayfold content, runs the same local
  coverage classifier, and writes nothing.
- [x] Exit codes: `0` clean, `1` validation/coverage failure, `2` invocation/read
  failure. Curator apply always requires read access to every target; it never
  reports a write-only bundle as verified.
- [x] There is no general bypass. Closed review-disposition codes require a later
  explicit operator confirmation and can never waive malformed data, dangling
  refs, ACL/version mismatch, or round-trip comparison.
- [x] State explicitly that dry-run is diagnostic and does not authorize a later
  changed file. Task 5 always revalidates the in-memory bundle before writing.
- [x] Bound bundle bytes, strings, resources, and audit pages before parsing;
  partial audits return an explicit incomplete status.
- [x] Unit-test false-positive boundaries: years in names, version numbers, phone
  numbers, scores, amounts, ordinal labels, URL paths, and audit/source dates.

**Verification:** the saved Big Night-shaped fixture fails for all missing
operational claims; historical/source-only fixtures do not become behavior.

## Task 3: Schema and generated contracts (S1)

**Outcome:** the wire contract can represent item-local multiple occurrences and
same-item trigger references.

- [x] Add `$defs.TemporalOccurrence` and `$defs.TemporalFacet` with closed objects,
  enums, patterns, lengths, and array bounds.
- [x] Add optional `temporal` to Block and BriefingCard.
- [x] Add the closed `fact_ref` trigger union. A fact-ref variant carries only
  `fact_ref` and optional bounded `alert_offset`; it cannot coexist with `at`,
  `relative`, `recurring`, or `window`. Legacy variants remain readable.
- [x] Define the exact reserved fact-key grammar for every carrier in the
  capability table; API, CLI, and client consume the same valid/invalid corpus
  under `specs/domain-model/examples/temporal-v1/`.
- [x] Correct existing schema description drift: `$defs.timestamp` is date-time
  only while milestone/timeline clients accept bare dates. Do not silently widen
  every audit/lifecycle timestamp. Introduce a dedicated temporal-value definition
  instead.
- [x] Keep Hub `start_at`/`end_at` date-time-only. Remove/dead-code any client path
  that pretends a bare-date Hub value can survive the current wire/`timestamptz`
  storage, and add a test that rejects coercion rather than manufacturing midnight.
- [x] Update `codegen.mjs` definition ordering/ref resolution: nested `$ref`s
  currently collapse to `z.any()`. Prove `TemporalFacetSchema`,
  `BlockSchema.temporal`, and `TriggerSchema.when` contain no `any`.
- [x] Regenerate TypeScript Zod and Kotlin generated types with the pinned codegen.
- [x] Inspect generated names/nullability and ensure the facet is not a generic
  map.
- [x] Record the E2EE compatibility constraint from Proposed ADR 0015: schedule
  ID/role/status/start/end/zone are planned clear metadata, while label/triggers
  are content. Do not implement a speculative V1 encryption branch; ADR 0015 must
  define its eventual nested-label/trigger wire representation before activation.
- [x] Update template/reference docs from the schema, not by copying a divergent
  hand-written shape.

**Tests:** schema accepts all-day/timed examples and rejects offset-less time,
`-00:00`, leap seconds, fractions, mixed endpoint families, empty/duplicate IDs,
bad enums, role/range violations, excessive arrays, unknown keys, invalid fact
keys, and mixed trigger variants.

## Task 4: API structural validation and persistence (S1)

**Outcome:** `temporal` survives PUT → row → GET/tree → sync unchanged, with no
server semantic inference.

- [x] Add forward-only migration `0022_temporal_content.sql`:
  nullable, unindexed `blocks.temporal jsonb` and
  `briefing_cards.temporal jsonb`. Do not add a database CHECK that can echo the
  failing row into an error until telemetry sanitization is proven.
- [x] Implement tri-state update semantics on card/block routes: absent preserves,
  object replaces, explicit `null` clears. Resolve it with `If-Match` and tombstone
  state in one conditional SQL statement/transaction; never route-read then upsert.
- [x] Preserve card visibility/audience on omission, matching hub PUT. Add card
  and hub `If-Match` support (sections/blocks already accept it); temporal
  repair/apply always sends reviewed ACL + base version.
- [x] Upgraded full-replacement writers explicitly send temporal object/null.
  Old/field-level writers may omit and preserve. Plan a later capability gate for
  rejecting ambiguous full replacements only after the supported old-client
  window.
- [x] Ensure every list/tree/sync projection includes the columns by construction;
  add explicit round-trip tests to prevent a repeat of the accepted-but-dropped
  Hub.timeline bug.
- [x] Parse with generated Zod and add one API cross-field validator for
  request/string/array bounds, unique IDs, timestamp/date syntax, role/range rules,
  and same-parent fact references that JSON Schema cannot express.
  Keep offset/zone transition agreement at the CLI authoring boundary to avoid
  tzdb-version disagreement; API checks only bounded zone-token syntax, not tzdb
  recognition.
- [x] Enforce route-specific request-byte limits before `c.req.json()` allocation;
  string/array/occurrence limits then apply before date parsing.
- [x] Validate same-item references against the effective stored/request value
  inside the same transaction. Cover concurrent omit/replace/clear and stale
  updates, not only sequential requests.
- [x] Implement same-item fact-reference validation for plaintext M0 only. Treat
  the ADR 0015 split as a future acceptance constraint, not conditional V1 code.
- [x] Map every expected failure to stable 422 paths/codes without throwing. Never
  echo parser-library values or PostgreSQL message/detail/row/parameters.
- [x] Sanitize unexpected errors before SWIP/Sentry (prefer a content-safe reported
  error with stable code + route pattern). Add leak canaries for body, occurrence
  ID/label/value/zone, parent/family IDs, and simulated DB failures.
- [x] Keep tenant visibility/auth unchanged. `temporal` inherits the parent row's
  family, hub visibility, audience, and author/write gates.
- [x] Add malformed-size tests and ensure validation is linear in the bounded
  occurrence count.

**Tests:** API unit tests plus live-Postgres card and block round trips, update,
preserve/replace/clear, old-writer omission, ACL preservation, stale If-Match,
tombstone/resurrection posture, hub tree, merged `/sync` pagination, request-size
rejection, and telemetry leak canaries.

## Task 5: CLI apply gate and verified round trip (S2)

**Outcome:** curated writes cannot claim success when expected temporal data is
missing, invalid, or dropped.

- [x] Reuse generated temporal types for shape and keep one CLI authoring validator
  for coverage, relative-time bases, zone/offset agreement, and the cross-field
  constraints required before a server call.
- [x] Run deterministic temporal validation on every relevant `push`, independent
  of `--type`. Cards currently validate generated shape only when `--type` is
  present; temporal correctness must not inherit that opt-in hole.
- [x] Curator writes go through `content apply`, which verifies and writes the same
  in-memory projection. It includes explicit temporal object/null, current ACL,
  and `If-Match` version. Ordinary `push` retains resource-local mechanical checks
  but is not the curated multi-resource path.
- [x] Wire `dayfold content apply [--dry-run] <bundle.json>` as the only bundle
  verifier/write path. Real apply repeats the same in-memory validation.
- [x] Treat apply as stateless and resumable, not atomic: require read access,
  GET/preflight every target, skip exact desired matches, write in deterministic
  dependency order only when base versions match, and stop on the first conflict.
  Rerunning the same bundle safely resumes from server state; no durable apply
  journal or rollback overwrite is introduced.
- [x] On 200, compare canonical selected fields directly—no hash/digest. Ignore
  object-key and occurrence-array order, key occurrences by ID, but compare stored
  timestamp strings exactly so loss of offset/civil representation is visible.
- [x] After all writes, pull the affected hub/card with the already read-authorized
  credential and compare the assembled tree/card. Never request broader scope;
  refuse curated apply when any target cannot be read back.
- [x] Verify ACL and version as well as temporal facts. A returned/pulled resource
  that widens or changes visibility/audience fails.
- [x] For Hubs, read the existing audience/participant endpoint within the current
  credential scope; the tree response alone cannot prove the allow-list or roles.
- [x] Print a final content-minimal receipt: resource counts, occurrence counts,
  fact-ref counts, review-issue counts, and `round_trip=verified`. Do not print,
  persist, transmit, or telemeter a schedule hash.
- [x] Keep source phrases/labels out of logs, shell history guidance, and error
  telemetry.

**Tests:** a fake server returns 200 while dropping `temporal`; apply must fail.
Another returns semantically identical JSON with key/occurrence reordering; it
passes. Test global read, hub-scoped read, allow-listed/not-allow-listed restricted
content, and refusal for write-only, revoked, and expired credentials. Test
stateless resume after a mid-bundle conflict and exact-desired skip.

## Task 6: Shared mobile DTO and SQLDelight round trip (S1/S3 prerequisite)

**Outcome:** all KMP platforms retain the facet without changing presentation.

- [x] Add raw `JsonObject`/JSON-text `temporal` fields to `Card` and `HubBlock`.
  Do not decode-ignore-reencode the stored facet: that would drop unknown future
  keys. The normalizer parses a validated view; full-resource egress preserves the
  raw value, while typed partial writes omit it for server-side preserve-on-omit.
- [x] Add nullable `temporal TEXT` to `card` and `hub_block`, plus Card version/ACL
  fields needed for safe replacement, in migration `18.sqm`; rebuild Calendar
  binding identity in `19.sqm`.
- [x] Thread JSON through every `ContentStore.applyDelta`, row projection, fake
  backend, full-resync, and cache test.
- [x] Preserve unknown future roles/statuses as non-eligible rather than failing an
  entire sync page.
- [x] Make the `CLIENT_SCHEMA_VERSION` bump mandatory when temporal-aware readers
  activate. Older caches may have advanced their sync cursor after discarding the
  field; force full resync while preserving outbox/local device state.
- [x] Member/checklist/Calendar field writes omit `temporal`; server tri-state
  semantics preserve it. Only explicit full-replacement writers, including
  curator apply, send an object or explicit null. Test omission from old and
  upgraded partial writers.
- [x] Preserve every whole-resource field in caches/egress, including raw unknown
  triggers/actions/body references and ordering. Card and Hub Calendar writes put
  their cached base version in the outbox and send `If-Match`; `baseVersion=null`
  is not permitted for an existing temporal-bearing resource.
- [x] Stamp each outbox row with the writer schema/capability at creation. Request
  capability comes from that row, never the current binary. After the mandatory
  full resync, rebuild pre-upgrade whole-resource rows by replaying their bounded
  typed member intent, or drain them explicitly as legacy; test upgrade with a
  queued checklist toggle.
- [x] Preserve raw unknown trigger members on upgraded clients. An old client can
  decode `fact_ref` as an empty/partial `when` and erase it on re-encode, so S4
  emission requires a minimum supported-client floor or a server capability merge
  that preserves existing fact-ref triggers for writers lacking `temporal-v1`.

**Tests:** card/block wire decode, database migration from v15, applyDelta round
trip, full resync, tombstone, unknown enum token, and all target compilation.

## Task 7: One pure temporal normalizer (S3)

**Outcome:** the four mobile consumers share parsing, precedence, and eligibility.

Create `TemporalFacts.kt` with a pure per-resource API shaped approximately as:

```kotlin
sealed interface TemporalExtent {
  data class AllDay(val start: LocalDate, val endExclusive: LocalDate) : TemporalExtent
  data class Timed(val start: Instant, val endExclusive: Instant?, val zone: TimeZone) : TemporalExtent
}

data class NormalizedFact(
  val factRef: FactRef,
  val label: String,
  val role: TemporalRole,
  val status: TemporalStatus,
  val extent: TemporalExtent,
  val source: TemporalSource,
  val capabilities: TemporalCapabilities,
)

fun temporalFacts(resource: HubBlock): List<NormalizedFact>
fun temporalFacts(resource: Card): List<NormalizedFact>
fun temporalFacts(resource: Hub): List<NormalizedFact>
```

- [x] Parse date-only as civil interval and instants separately; never coerce an
  all-day date at UTC/device midnight.
- [x] Hub normalization accepts only the timed values its schema/storage can
  preserve; it does not route Hub strings through `AllDay` in V1.
- [x] Treat stored timed instant as authoritative. Invalid syntax remains prose-
  renderable but ineligible; do not invalidate an accepted fact solely because a
  later platform tzdb disagrees with its authoring-time offset.
- [x] Implement the exact carrier/capability/fact-key table.
- [x] Never merge explicit IDs by value. Reject double-authoring at CLI; normalizer
  preserves both if malformed legacy data still reaches it.
- [x] Treat legacy `when.at` as a Calendar compatibility fallback only when the
  parent has no canonical Calendar-eligible fact. Do not normalize it as a second
  fact or apply general value/title deduplication.
- [x] Expose consumer-specific queries (`forTrigger`, `calendarEligible`,
  `timelineEligible`) rather than letting each consumer reimplement predicates.
- [x] Resolve and write back by the closed `fact_ref` grammar, including checklist
  item ID or typed payload field. No separate locator identity and no title/array-
  position identity.
- [x] Keep clock/zone injected; no wall-clock reads in the pure model.

**Timestamp test matrix:** UTC, positive/negative offsets, Kathmandu `+05:45`,
Lord Howe 30-minute DST, spring gap, both fall-fold offsets, `-00:00`, leap-second
rejection, leap day, cross-midnight, DST-spanning timed range, same instant through
different offsets, date-only single day, exclusive multi-day end, DST-transition
all-day event, invalid zone, past/future, cancelled/tentative/reference, and bound.

## Task 8: Calendar Check consumes normalized occurrences (S3)

**Outcome:** a single block/card can yield several safe review candidates without
using the first trigger.

- [x] Keep hub start/end and milestone compatibility through the normalizer.
- [x] Emit one candidate per **capability-eligible** confirmed
  event/deadline/window fact. Do not newly emit checklist/card payload dates that
  the matrix marks Calendar-ineligible.
- [x] Give `DayfoldEventCandidate` distinct `entityRef`, existing `subjectRef`, and
  `factRef`. Derive `localFactKey = encode(entityRef, factRef)` only at local
  Calendar/notification persistence boundaries. Do not append suffixes to the
  current `SubjectRef` grammar or persist redundant locator/behavior identities.
- [x] Rebuild/migrate device-local `calendar_binding` so entity + fact form the
  binding identity while subject remains separate. Preserve legacy single-event
  bindings and Calendar-owned notification decisions where mapping is unambiguous;
  surface ambiguity for review rather than orphaning/suppressing broadly.
- [x] Thread `entityRef`/`factRef` and the derived `localFactKey` through Calendar
  actions, reducer/model, engine commands, selectors, UI row keys, binding queries,
  and notification-owner queries—not just the candidate DTO and reconciler.
- [x] Do not emit reference/tentative/cancelled occurrences.
- [x] Preserve typed-field-only and on-device-only rules; never inspect body text.
- [x] Timed candidate matching, fingerprints, and diffs compare parsed instants;
  lexical offset differences alone are not changes. All-day values compare
  `LocalDate` boundaries; zone comparison is separate for timed facts.
- [x] Define all-day end translation explicitly for Android/iOS native APIs.
  Fix iOS's `start + 1.days` default: compute next civil date then its start so
  23/25-hour DST days remain one all-day event.
- [x] Re-run duplicate matching with several occurrences sharing a title/time.
- [x] Calendar “Use Calendar” dispatches through the exact `factRef`, not the first
  trigger. A referenced trigger follows the changed fact without a timestamp copy.
- [x] Verify add-only handoff and Calendar Check read/write consent are unchanged;
  no fact causes an unreviewed native write.
- [x] Stop Calendar import from materializing arbitrary external descriptions as
  Markdown in V1. Keep primary start/end typed. Any reviewed secondary-claim
  mapping UI is design-gated separately. Because removing the existing option
  changes the import preview, revise/sign off that state under ADR 0008 before
  shipping it. Remove the option/materialization path across Import engine/model/
  actions/reducer/selectors/screens/host and `ContentStore`, not only the helper.
  S3 retains the legacy timed milestone `when.at`; S4 owns the gated ref rewrite.

**Tests/UI:** pure candidate tests, reconciliation identity tests, Android/iOS
adapter tests, and existing Calendar Check repeated-row snapshots. If status or
conflict UI becomes necessary, stop for ADR 0008 design rather than improvising.

## Task 9: Derived timeline consumes all eligible occurrences (S3)

**Outcome:** multi-date content becomes several stops instead of silently choosing
one date.

- [x] Route derived-timeline input through the resource-local normalizer and a
  small hub-tree collector; do not introduce a global cross-family index.
- [x] Preserve authored `Hub.timeline` precedence from ADR 0045.
- [x] For derived fallback only, emit separate confirmed event/deadline/window
  facts that the capability table marks eligible, with stable source tags.
- [x] Do not emit reference/tentative/cancelled occurrences.
- [x] Do not value-deduplicate legitimate simultaneous facts. Reject/mark malformed
  double-authoring using source identity, never timestamp/title equality.
- [x] Extend the derived presenter input with a client-only/transient
  `endExclusive` (do not silently add a new authored TimelineStop wire field).
  All-day/ranged items become done at exclusive end; timed points after start;
  active ranges remain active. A separately authored range field would require
  its own schema/design review.
- [x] Keep render-only/no-notification posture.
- [x] Update the ADR 0046 compatibility test: legacy multiple-date blocks keep
  most-specific behavior; accepted canonical occurrences produce multiple stops.

**Tests/UI:** derived stop identity/order/status tests plus existing hub/day timeline
goldens. No new component or copy.

## Task 10: Now and notification triggers reference occurrences (S4)

**Outcome:** event data and behavior share one timestamp without duplicated values.

- [x] Extend `TriggerWhen` with `factRef` and the closed fact-ref trigger variant.
- [x] Resolve through the same parent resource's normalized facts.
- [x] Keep `eventAt` separate from offset-adjusted `effectiveAt` exactly as current
  Now selection does.
- [x] Reject/ignore dangling, all-day, reference, tentative, cancelled, and invalid
  facts. Malformed `alert_offset` is an authoring/API error, not zero offset.
- [x] Continue reading legacy `when.at`; new writes use the closed ref variant.
- [x] Enforce at most one fact-ref trigger per parent in V1. Multiple behavior is a
  later ADR, not silently selected/dropped.
- [x] Do not emit fact refs until the old-writer gate from Task 6 is closed. Add
  capability/min-version fixtures proving an old app cannot erase or corrupt a
  referenced trigger during checklist/calendar field writes.
- [x] After that gate closes, change Calendar import's timed milestone trigger
  from the compatibility `when.at` copy to `fact_ref=payload:milestone`.
- [x] Use the derived entity-plus-fact `localFactKey` for notification ownership/
  log/cancellation while preserving parent `subjectRef` for calm-feed collapse and
  content-response rules. Matching one Calendar event must not suppress another
  fact or unrelated preparation behavior on the same item.
- [x] Add a device-local desired-schedule registry keyed by `localFactKey`. Diff it
  against desired schedules and cancel absent/changed Android alarms and iOS
  notification requests before adding replacements. Clear/cancel on content
  tombstone, update, notification disable, sign-out/family replacement, and S4
  rollback; iOS must never deliver stale content-bearing copy without recheck.
- [x] Ensure `expires_at` remains lifecycle authority, not inferred from event end.
- [x] Exact local schedules use only explicitly triggered occurrences; temporal
  facts alone never schedule a wake.

**Tests:** existing Now window/offset tests plus rejection of multiple V1 fact-ref
triggers, dangling refs, status changes, DST boundaries, same instant/different
ID, background exact-schedule parity, and notification cancellation on content
update/tombstone.

## Task 11: Existing-content audit and repair proposal (S5)

**Outcome:** understand and repair legacy gaps without inventing data or behavior.

- [x] Before repair, ship/test card ACL preserve-on-omit, `If-Match`, and direct
  ACL round-trip comparison. Never restore an old snapshot over newer ACL/member
  edits.
- [ ] Run the read-only audit only within existing grants/visibility and classify:
  missing operational, structured elsewhere but unlinked, reference-only,
  lifecycle/source metadata, ambiguous, and false positive.
- [ ] Produce counts and item IDs/paths; keep body excerpts local and out of repo.
- [ ] For each repair, reconstruct a ledger from original authoritative sources
  where available. Do not treat an old LLM sentence as stronger evidence than the
  source.
- [ ] Present full before/after JSON, unchanged ACL, base version, fact table, and
  any explicit behavior. Proposal and push are separate turns.
- [ ] Obtain explicit confirmation before any push.
- [ ] Repair Big Night with opaque stable occurrence IDs and all known facts
  item-locally; the breakfast, 11 AM call, picnic, performance, and cleanup are
  distinct facts. The 6:30/7:00 meeting conflict remains blocked until the
  operator resolves it. Add a trigger only if the operator separately requests
  behavior.
- [ ] Verify every write via returned row + pull + audit.
- [ ] Record unresolved/conflicting items as findings, not guessed timestamps.

## Task 12: Cross-stack verification and rollout

Run in the cheapest relevant loops; exact commands are confirmed against the
repo at implementation time.

### Schema/codegen

```bash
npm run codegen
git diff --check
```

Verify generated TS/Kotlin diffs are intentional and no `any` regression appears.

### API

```bash
cd apps/api
npx vitest run test/temporal-validation.test.ts test/temporal-api.test.ts
npm test
```

Live-Postgres tests apply migrations through `0022`; never modify an already
applied migration.

### CLI

```bash
cd apps/cli
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew test
```

Exercise offline bundle verification, fake round-trip loss, help JSON, and audit
content-minimal output.

### Mobile/shared UI

```bash
cd apps
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :client:desktopTest
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :ui:desktopTest
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :client:compileKotlinIosSimulatorArm64
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :swip-wiring:desktopTest
```

Run focused Android/iOS calendar adapter tests and snapshot diffs. S3/S4 always
run privacy canaries—not only when sanitizer files change—with unique sentinels
in occurrence IDs, labels, values, zones, native event IDs, and fingerprints.
Assert none enter logs, analytics, bug reports, SWIP inspector, or unrelated
sync/outbox payloads. The temporal facet itself is expected parent content and
therefore does sync inside its authorized parent.

### End-to-end acceptance fixtures

- **Fixture A — unresolved source:** five unambiguous logical claims plus the
  conflicting 6:30/7:00 meeting claim exist in the ledger. Apply is blocked before
  the first write and server state is unchanged.
- **Fixture B — operator-resolved source:** breakfast, 11 AM call, picnic, meeting,
  performance, and cleanup map to six identity-distinct facts. No trigger exists
  unless separately requested. API PUT/GET/tree/sync and client cache preserve all
  six; Calendar derives eligible rows keyed by entity/subject/fact plus the derived
  local key; the fallback timeline produces intended stops only when no authored
  timeline exists; Now/notifications produce nothing without a trigger; and audit
  is clean after the round trip.

## Rollback and compatibility

- S0 can be rolled back by reverting instructions/CLI audit; it writes nothing.
- S1 database columns are additive and nullable. Roll back application readers by
  ignoring them; preserve-on-omit remains active and columns are not dropped in
  an emergency rollback.
- S2 CLI authoring can stop emitting the facet while persisted JSON remains safe.
- S3 consumer rollout is a client release boundary; preserve legacy adapters for
  at least one supported release window. Its client-schema bump/full resync is not
  rolled back by reusing an older behavior version.
- S4 keeps `when.at` read support. If occurrence refs regress, stop emitting refs
  before removing reader support. Rollback also reconciles and cancels the local
  desired-schedule registry; uninstalling reader behavior alone is insufficient.
- Content repair is not rolled back by deleting facts blindly. Restore the
  operator-approved previous JSON by ID/version through the normal authoring
  boundary.

## Review checklist

The specialist and systems reviews must answer, with Critical/Important/Minor
severity:

- Are date-only civil intervals, per-occurrence zones, offsets, DST gaps/folds,
  exclusive ends, ranges, and
  relative phrases unambiguous?
- Can entity/subject/fact/derived-local-key identity, codegen, tri-state storage,
  partial writers, sync, or migration
  produce two sources of truth or lose an occurrence?
- Does any source prompt injection, server error, telemetry, audit, calendar, or
  notification boundary reveal or
  act on content beyond current authorization/consent?
- Does every write/read path carry the new field? Are old/new CLI, API, and mobile
  versions deployable in a safe order?
- Can one smaller abstraction or fewer commands provide the same guarantee?
- Are agent instructions strong enough that the ledger is created before prose,
  and is post-write verification mandatory rather than advisory?
