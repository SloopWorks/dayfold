# Smart Briefings — reviewed safe-slice implementation plan

> **Status:** Implemented and verified for the automated local/dogfood slice in this document,
> with the remaining device-level checks named below. This is
> not acceptance of Proposed ADR 0061 or 0062 and does not authorize live provider
> OAuth, hosted private-content processing, a K3/K4 gateway, Dayfold content
> writes, or routine telemetry.

**ADR 0008 sign-off record:** the operator's 2026-08-07 instruction to import,
review, plan, and implement this design is treated as sign-off for the explicitly
simulated local preview in this plan. It is not sign-off for a live provider flow
or acceptance of Proposed ADR 0061/0062.

**Goal:** Turn the imported interaction design into a coherent, testable mobile
preview contract and prove the first useful Dayfold CLI shadow loop without
placing family content or durable credentials in a provider cloud.

**Inputs:**

- `designs/routine-integration/Index.dc.html` and its imported project files
- `designs/routine-integration/NOTES.md`
- `designs/DESIGN-BRIEF-smart-briefings-subscription-routines.md`
- `docs/superpowers/specs/2026-08-07-routine-integration-design.md`
- `docs/superpowers/plans/2026-08-07-routine-integration.md`
- Proposed ADR 0061 and 0062

## Review outcome

Three focused reviews covered product/UX, schema/API/security, and KMP/mobile/
testing. The imported design is strong on subscription ownership, separate source
consent, requested-versus-observed source truth, review-first authority,
no-change/partial outcomes, last-good-content preservation, and honest revoke
cleanup. The safe implementation must correct these gaps:

1. Provider connector OAuth and the Dayfold routine principal are different
   credentials. Their ownership, callback binding, activation, rotation, and
   revocation are not yet reconciled, so neither is implemented here.
2. Consumer-plan language conflicts with the current privacy posture. This slice
   says provider eligibility is not yet verified and never promises production
   processing on a consumer plan.
3. Recovery values drift between the brief, design notes, and architecture spec.
   One versioned contract below becomes the source of truth.
4. The 46 mock views are examples, not 46 navigation destinations. Six layout
   families render from structured state.
5. The preview renders one illustrative Smart Briefing, owner-controlled and adult
   read-only. Persistent cardinality remains a decision for ADR 0061; no schema or
   API invariant is introduced here.
6. The current server auth and content routes cannot safely represent a routine
   principal or hub-scoped card authoring. This slice adds no API route, migration,
   credential, staging table, provider callback, or content write.
7. The fixed 390×844 mock is visual intent. Production Compose uses Dayfold theme
   roles, safe drawing/IME insets, dynamic type, reduced motion, adaptive actions,
   and 48 dp minimum targets.

## Plan review outcome

The post-plan Android/iOS, testing, schema, and sad-path review made these
corrections before implementation: bind every contract and finish receipt to a
family; require an exact requested-source outcome set and coherent terminal
counts; make the CLI network seam one GET with no refresh side effect; fence late
terminals with a strictly increasing generation across family resets; preserve
last-good content while preventing conflicted-draft acceptance; keep revocation
pending until an explicit terminal; redact the route and action names from SWIP;
and separate desktop/common, Android runtime, and iOS shared-layer evidence so a
platform compile is never reported as an installed mobile E2E test.

## Product and data contract

### Orthogonal state

Do not encode product state as a mockup name or infer it from a free-text error.
The client model separates:

- availability: `hidden | preview`;
- authority: `owner | adult_read_only`;
- setup destination: `entry | configure | privacy | handoff | status | draft`;
- lifecycle: `off | preparing | awaiting_provider | verifying | active | paused |
  revoking | revoked`;
- execution mode: `shadow` only in this slice;
- requested publication mode: `review` as non-authoritative future intent only;
- provider: `unselected | claude | chatgpt` (shown for design parity, never live);
- schedule: nullable while off; when selected, requested label + IANA timezone,
  never claimed as provider-observed;
- each requested source: `requested | probing | observed | syncing |
  reauth_required | admin_blocked | unavailable | removed`;
- provider health: nullable while off; otherwise `never_seen | last_seen |
  reported_failed | stale`;
- last run result: nullable until a fixture finish; otherwise `success |
  no_changes | partial | rejected | failed`;
- revoke state: lifecycle plus a structured recovery envelope.

### Closed recovery envelope v1

`schemaVersion` is `1`. Producers validate strictly against the closed schema;
clients decode wire strings tolerantly and map an unknown value or unsupported
version to generic `Unknown` without retaining or displaying the raw value. Closed
values for implemented preview states are:

- phase: `enrollment | provider_handoff | authorization | source_probe |
  provider_observation | context_read | analysis | validation | stage | apply |
  finish | revoke`;
- reason: `enrollment_expired | routine_not_confirmed | app_return_lost |
  preparation_failed | provider_unavailable | authorization_denied |
  authorization_context_mismatch | late_callback | provider_quota |
  source_reauth | source_syncing | source_not_observed | admin_blocked |
  unexpected_source_set | partial_source_set | run_reported_failed |
  gateway_unreachable | invalid_output | policy_rejected | write_cap | conflict |
  rate_limited | confirm_pending | revoke_failed | provider_task_remains |
  retries_exhausted | repeated_conflicts | volume_anomaly | timeout | offline |
  internal`;
- retryability: `automatic | user_action | final`;
- recommended action: `retry | resume_provider | manage_source |
  restart_enrollment | continue_review_only | refresh_draft | review_policy |
  contact_support`.

Raw provider error text is never rendered or retained. Internal enum names appear
only in a clearly synthetic support-details fixture. A live support code does not
exist in this slice; preview details set `supportCodeAvailable=false`.

Reducer invariants fail closed: `off` has no provider health or run result;
`active` in preview requires an explicit synthetic finish marker; `revoked` clears
the synthetic grant marker; hidden availability rejects direct open/restored
navigation; Dayfold-only is mutually exclusive with external sources; and an
owner-to-adult role change removes setup/revoke controls immediately.

### Contracts implemented now

- `routine-manifest.schema.json`: a shadow manifest with
  selected hubs, requested sources, bounded window/cap, and explicitly forbidden
  operations.
- `routine-changeset.schema.json`: discriminated `create_card` operation only;
  every operation targets one approved hub and carries no audience supplied by the
  model. Update/delete/ACL/role/invite/message operations are unrepresentable.
- `routine-run-finish.schema.json`: content-free, family/routine-bound terminal
  result with complete requested-source outcomes, coherent safe counts, and a
  required recovery envelope for every partial, rejected, or failed result.
- sanitized valid/invalid fixtures reused by CLI tests. Source examples contain no
  real names, URLs, provider tokens, content, or stable production identifiers.

TypeScript and Kotlin implementations share schemas and conformance fixtures, not
runtime source code. `packages/routine-schema` validates Draft 2020-12 schemas and
fixtures in CI; the manually mirrored Kotlin DTO/policy layer must pass the same
corpus. A future server evaluator remains authoritative after the architecture
gate.

The executable schema gate is `npm run test:routine-schema`; the root workspace,
lockfile, and CI invoke that exact command.

## Imported-view coverage

Every imported `view` is accounted for without making it an application route:

| Imported view(s) | Safe-slice disposition |
|---|---|
| `entry-off-owner`, `entry-off-adult` | Entry layout; implemented with owner/adult authority |
| `provider-choice` | Configuration step; implemented as provider intent, availability unverified |
| `source-intent`, `source-permission-explainer` | Source/access steps; implemented and merged |
| `briefing-preset` | Requested-schedule step; implemented, not observed truth |
| `hub-scope` | Synthetic eligible-hub step; implemented; restricted/live hubs deferred |
| `privacy-review`, `technical-details` | Privacy layout; merged; fingerprint and consumer-plan claims removed |
| `handoff`, `oauth-approval` | Replaced by preview-only provider information; no OAuth/grant claim |
| `waiting`, `returned-incomplete`, `return-recovery` | Status layout; fixture-driven preview transitions |
| `source-setup-help` | Provider-information sheet; implemented without setup code |
| `source-syncing`, `source-verification-partial` | Per-source status variants; implemented |
| `pairing-expired`, `provider-unavailable`, `handoff-preparation-failed` | Structured recovery variants; implemented |
| `authorization-denied`, `authorization-context-mismatch` | Structured recovery variants; implemented as synthetic fixtures |
| `active`, `adult-active-readonly` | Active overview; synthetic preview only |
| `first-draft` | Draft layout; read-only simulated decision, nothing publishes |
| `first-run-no-changes` | Successful no-change fixture; implemented |
| `partial-source-result`, `source-not-observed`, `source-needs-reauth` | Active recovery banners; implemented |
| `no-recent-checkin`, `connector-needs-attention`, `run-reported-failed` | Provider/source observation banners; implemented |
| `draft-stale-or-conflicted` | Readable, non-acceptable preview draft; implemented |
| `apply-retrying`, `apply-failed`, `routine-auto-paused` | Deferred: no stage/apply/auto authority in this slice |
| `support-details` | Synthetic safe details without code/IDs/content; implemented |
| `offline` | In-session preview offline variant; implemented |
| `stop-confirm`, `revoke-pending`, `revoke-failed` | Stop/revoke preview states; implemented, never claims a live grant changed |
| `revoked-provider-task-remains` | Cleanup explanation fixture; implemented without provider task claim |
| `advanced-run-claude`, `advanced-test-claude`, `advanced-claude-connected`, `advanced-run-chatgpt-unavailable` | Deferred: token paste, Run now, and provider task control are outside the safe boundary |

The journey's missing prepared-handoff beat is represented by the preview provider-
information layout. Production OAuth approval, first-run proof, apply states, and
advanced provider controls remain design references, not simulated capabilities.

## Implementation tasks

### Sub-agent execution map

The work is split into bounded lanes with one integration owner:

- **Schema/CLI lane:** Tasks 1–2, including the shared scenario corpus, strict
  schema/Kotlin parity, GET-only transport proof, and CLI tests.
- **KMP/mobile-state lane:** Task 3 plus host capability, navigation/back,
  lifecycle/generation, family reset, Android runtime, iOS shared-test, and SWIP
  privacy assertions.
- **Mobile-UX lane:** Task 4 plus route projection, owner/adult/offline/recovery/
  revoke states, semantics tests, and macOS/Linux visual baselines.
- **Integration owner:** Task 5, CI/docs, cross-lane contract reconciliation,
  full-matrix verification, privacy scan, and the final commit.

The schema/fixture vocabulary is the first shared handoff. KMP owns product state;
Compose consumes a projection rather than duplicating lifecycle rules. Lanes may
edit different files concurrently, but the integration owner resolves shared
`Main.kt`, navigation, CI, and plan changes and reruns all gates after merging.

### Task 1 — Contract package and fixtures

Files:

- `specs/domain-model/schemas/routine-manifest.schema.json`
- `specs/domain-model/schemas/routine-changeset.schema.json`
- `specs/domain-model/schemas/routine-run-finish.schema.json`
- `specs/domain-model/examples/routines/**`
- `packages/routine-schema/**`
- `package.json`, `package-lock.json`
- `.github/workflows/ci.yml`
- `apps/cli/src/main/kotlin/RoutineContract.kt`
- `apps/cli/src/test/kotlin/com/sloopworks/dayfold/cli/RoutineContractTest.kt`

Work:

- Encode the closed producer enums and strict object shapes
  (`additionalProperties=false`); client unknown handling is tested separately.
- Enforce one selected target hub for the safe shadow slice, `executionMode=shadow`, bounded
  source window, bounded operation count, unique `opId`/resource IDs, and
  `create_card` only.
- Validate cross-field policy after structural decoding: manifest family/hub
  binding, operation count, target hub, forbidden action/kind, source-ref caps,
  and tenant/source/count/result/recovery coherence in content-free finishes.
- Use API-compatible resource IDs (`[A-Za-z0-9_-]{1,128}`), opaque ULID `opId`,
  unique `opId` and `(kind,id)`, and explicit caps for operations, body fields,
  source references, and strings. Authority is a fixed positive allowlist of
  `create_card`; denylist fields do not grant authority. Reject model-supplied
  audience/visibility, credential/version/timestamp provenance, and apply flags.
- Cover zero-result observed, Dayfold-only, no-change, partial, reader-unknown,
  unsupported-version, prompt
  injection, wrong-hub, delete, update-without-version, and audience-forgery cases.
- Treat hostile source text as inert data: validate the resulting operation/scope,
  and prove hostile text never reaches summaries/errors/logs. Do not pretend the
  structural validator detects semantic prompt injection.

Exit: the same fixture corpus produces deterministic pass/fail results and no test
contains private content.

### Task 2 — CLI shadow commands

Files:

- `apps/cli/src/main/kotlin/Main.kt`
- `apps/cli/src/main/kotlin/Help.kt`
- `apps/cli/src/main/kotlin/RoutineContract.kt`
- `apps/cli/src/main/kotlin/RoutineDiff.kt`
- a small injectable GET-only CLI read seam
- CLI tests

Commands:

```text
dayfold changeset validate <manifest.json> <changeset.json>
dayfold changeset diff <manifest.json> <changeset.json> [--current <pull.json>]
```

Work:

- Keep parsing/validation/diff pure and testable; the main dispatcher only handles
  files, output, and exit behavior.
- With `--current`, diff entirely offline. Without it, use the current Keychain/
  Secret Service access credential through a dedicated one-GET adapter; do not
  enter the normal authenticated pull path or refresh/rotate credentials.
- Print stable create/no-change/conflict summaries, never full source refs by
  default. Do not call PUT/DELETE and do not add an apply command.
- Canonical diff is create-only: absent ID = `create`; same canonical content after
  removing server-managed fields = `no_change`; same ID with different content =
  `conflict`. It never infers an update. Errors emit path/code, not raw JSON.
- Validate uses zero HTTP; network diff performs exactly one GET through the
  injected seam. It fails closed on 401 without `POST /auth/refresh` or local
  credential rotation. A credential without global card-read scope fails with
  owned guidance and never widens access.
- Extend text and JSON help. Make `DAYFOLD_NO_UPDATE_CHECK=1` guidance explicit for
  an unattended local shadow invocation.

Document and test the output-only recipe:

```text
sanitized/operator-owned source records + dayfold pull
  -> dayfold-curator no-push changeset output
  -> dayfold changeset validate
  -> dayfold changeset diff
```

No step exposes `push`, PUT, DELETE, or apply.

Exit: an operator can validate and compare a generated create-card changeset with
current Dayfold state; tests prove no mutation method is reachable from the new
`changeset validate/diff` branches.

### Task 3 — Pure KMP feature state

Files:

- `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/features/routines/**`
- `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/fake/FakeScenarios.kt`
- `Model.kt`, `Reducer.kt`, navigation/back reducers/selectors
- desktop tests

Work:

- Add one `Route.SmartBriefings` and one `RoutineState` slice. Internal wizard and
  result navigation stays inside the feature; no route explosion.
- Add pure preview actions for provider/source/synthetic-hub choices, review-mode setup,
  privacy acknowledgement, handoff/resume, structured recovery, draft review, and
  revoke confirmation/outcomes.
- Correlate terminal actions with an opaque local operation generation so late
  results cannot cross a family switch. Clear the family-scoped feature on sign-out
  or family change while keeping device-local availability.
- Default availability is an injected, immutable host capability of `hidden`;
  only the dedicated fake backend may create a preview-enabled initial state.
  Hidden state rejects open/restore actions. Preview actions may carry clearly
  synthetic fixture IDs only; a future live flow must resolve real IDs through a
  non-logged command boundary or sanitized action representation. No secret,
  handoff URL/code, source content, provider exception, support code, or production
  identifier is stored in an action-loggable preview action.

The preview is intentionally in-session: it survives recomposition and activity
recreation only while the retained runtime lives, and resets after process death.
Durable resume/cached receipts require future server and SQLDelight work.

Exit: exhaustive reducer/selector/back tests prove owner/adult authority, recovery
mapping, unknown fallback, in-session last-good preservation, hidden-route gating,
and tenant reset.

### Task 4 — Adaptive Compose implementation

Files:

- `apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/features/routines/**`
- `AccountScreen.kt`, session/shell selectors, `RouteHost.kt`, navigation motion
- `apps/ui/src/desktopMain/kotlin/com/sloopworks/dayfold/client/Main.kt`
- UI and snapshot tests

Six reusable layout families:

1. entry and owner/adult overview;
2. compact configuration (provider, sources, access, schedule);
3. privacy and in-app provider information;
4. waiting/status with structured recovery banner;
5. active overview and existing review-first draft decision;
6. stop/revoke confirmation and result.

Work:

- Add “Smart Briefings” under a family-scoped Connections section in Account when
  preview availability is enabled; it is not an “On this device” setting.
- Use Dayfold theme roles and standard M3 controls. All actions are at least 48 dp,
  wrap/stack at large font scale, and cooperate with
  `WindowInsets.safeDrawing`/IME padding and consumption. Add roles, selected/state
  descriptions, headings, and clear provider-information labels.
- Show an always-visible “Interactive preview · nothing connects or saves” banner
  on every layout. Live-looking timestamps/details are labeled synthetic.
- Treat schedule as “requested in provider; Dayfold cannot verify it.” Replace
  “revocable in one tap” with “Start revocation here anytime.”
- Remove consumer-plan, automatic-publishing, real-run, raw reason-code, key
  fingerprint, and token-paste claims from the safe slice.
- Use progressive internal setup steps—provider → sources → hub access → schedule—
  with a pure back-order table and preserved selections, rather than one dense form.
- Hub fixtures cover loading, empty/no-eligible, a restricted item excluded from
  selection, selected-hub disappearance, and owner-to-adult role loss.
- Draft controls say “Preview acceptance/rejection” and repeat “nothing publishes.”
  Stop/revoke controls simulate UI outcomes and never claim a live grant changed.
- Respect reduced motion and retain the last good result underneath calm recovery
  banners. Offline disables mutating preview actions but keeps in-session information.
- Adult mode is visibly read-only; owner controls are absent, not merely disabled.
- Stop/revoke and details use standard modal sheets/dialogs with large-type
  scrolling, explicit scrim-dismiss policy, focus restoration, sheet-first Android
  back/predictive back, and iOS-compatible dismissal behavior before route back.
  Overlay visibility lives in `RoutineState`, not plain `remember`, so retained
  activity recreation and pure back precedence are testable.

Exit: the design’s golden path and required sad paths render from structured state
in light/dark with semantic coverage.

### Task 5 — Preview wiring, platform honesty, and discoverability

- Add one explicit `smart-briefings-preview` fake scenario. Android
  `MainActivity.kt` and desktop `Main.kt` inject preview initial state only for
  that exact scenario; `isFakeBackend` or debug alone is insufficient. Release,
  other fake scenarios, and real dogfood backends remain hidden.
- Provider information stays in-app in this slice. There is no URI launcher,
  clipboard, provider-app, callback, App Link, universal link, token, copied setup
  code, or credential flow to fail or test.
- Document that Android and desktop can exercise the full local preview. Common
  Compose behavior is desktop-render/semantics tested and the iOS targets compile,
  link, and run shared pure tests; the existing iOS host cannot navigate to this
  fake scenario, so no iOS routine-screen or live provider E2E claim is made.
- Add the imported suite to `designs/README.md`, remove its nonexistent root-index
  claim, and repair the routine suite's broken parent link to point to that README.
- Update backlog/changelog with the exact safe-slice boundary and remaining gates.

## Golden path

```text
owner opens Account in the dedicated fake scenario
  -> Smart Briefings preview
  -> chooses provider intent, Gmail/Calendar or Dayfold-only, and one eligible hub
  -> reviews processing and review-only authority
  -> sees preview-only provider information
  -> uses an explicit “Continue preview” fixture control
  -> a synthetic zero-result source outcome is explained as observed semantics
  -> a synthetic no-change or draft finish demonstrates review mode
  -> owner previews accept/reject behavior; nothing publishes
  -> owner previews revoke pending/success/failure; no live grant changes
```

No step in this slice creates a provider task, OAuth grant, routine principal,
remote run, Dayfold write, or analytics event. Provider information stays in-app;
there is no browser, provider-app, account, task, or authorization handoff.

## Sad paths and recovery coverage

- preparation failure: safe retry with a new local generation;
- provider app/browser/clipboard/callback failures are deferred because the safe
  preview has no external launcher or callback seam;
- canceled/expired handoff: restart or resume; no claim that an unimplemented grant
  was revoked;
- source syncing/reauth/admin block: per-source state, explicit reduced-set review;
- no changes: success, not an empty/error state;
- partial result: review-only and last-good preserved;
- stale/conflicted draft: readable, cannot accept until refreshed;
- offline: current in-session preview visible, setup/revoke disabled;
- authorization denial/context mismatch, lost return, late callback, connector
  denial, and run-reported failure map to distinct synthetic recovery reasons;
- no recent check-in/reported failure: provider health is an observation, not task
  enablement truth;
- revoke pending/failure: never show stopped until confirmed in a future live flow;
- provider-task cleanup remains separately owned after a revoke fixture;
- apply retry/failure and automatic pause are deferred because no apply/auto mode
  exists in this slice;
- unknown recovery values: generic copy and local recovery guidance, no raw message
  or invented support/contact channel.

## Verification matrix

### Automated

```bash
npm run test:routine-schema
cd apps/cli && ./gradlew test
cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :client:desktopTest :ui:desktopTest
cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :androidApp:assembleDebug
cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :ui:compileKotlinIosArm64 :ui:linkDebugFrameworkIosSimulatorArm64
cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :client:iosSimulatorArm64Test :ui:iosSimulatorArm64Test
cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :swip-wiring:desktopTest
cd apps && ANDROID_SERIAL=<API-36-or-lower-device> \
  ./gradlew :androidApp:connectedDebugAndroidTest
```

The SWIP task requires configured Gradle GitHub Packages credentials or
`SLOOPWORKS_PACKAGES_TOKEN`.

Focused tests:

- schema/fixture validity and strict unknown handling;
- CLI valid/invalid/wrong-hub/operation-cap and deterministic diff;
- reducer transition table, feature back, family reset, owner/adult, late result;
- Compose callbacks, radio/checkbox selected semantics, owner controls absent for
  adults, source-name + status announcements, polite live regions,
  provider-information labels, and representative 48 dp bounds;
- in-app provider-information copy, recovery CTAs, offline, revoke, and unknown fallback;
- curated snapshots: owner entry, adult read-only, configuration, privacy dark,
  waiting, active dark, partial, draft conflict, offline, revoke failure;
- register the scene/presets in `SnapshotScenes.kt`, `SnapshotStates.kt`,
  `snapshot-shots.json`, and semantics/manifest tests;
- render semantics with `:ui:snapshotUi`, record macOS goldens with
  `:ui:desktopTest --tests "*GoldenSnapshotTest" -Dsnapshot.record=true`, and use
  the documented Linux Docker record/verify command from
  `processes/agent-dev-loop.md` before committing per-OS goldens.

### Mobile quality and named residual checks

- inspect selected render PNGs rather than approving tests from exit status alone;
- Android automated coverage assembles the debug APK and exercises the retained
  runtime's routine route/overlay back order with a reused `ViewModelStore`.
  It is not an `ActivityScenario.recreate()` or rendered installed-app flow.
  Installed-APK Account → preview → configuration → provider information →
  fixture result → revoke, predictive gesture progress/precedence, 2× font,
  TalkBack traversal, RTL, reduced motion, and real activity recreation remain
  device-level follow-ups. The shared shell currently routes this feature through
  `BackHandler`, not a feature-specific predictive-progress handler.
- iOS shared layer: simulator tests/framework compile/link only. Desktop/common UI
  tests cover adaptive Compose semantics. The current iOS host has no reachable
  fake-preview entry, so VoiceOver/Dynamic Type/sheet validation remains a named
  follow-up and no routine-screen/live-E2E claim is made;
- expanded-width action reflow is covered on desktop; RTL remains a follow-up.

### Privacy canaries

- routine actions do not add content-bearing values to the Redux/devtools log;
- SWIP maps the preview route to its non-routine Account entry point and all
  routine action class names to one generic private-UI action before recording;
- no routine SWIP/PostHog/Sentry events are introduced;
- fixtures use unmistakably synthetic IDs; diagnostics/action-log assertions reject
  production/stable ID canaries, tokens, OAuth codes, source URLs/content, provider
  errors, and support codes;
- the SWIP registry/sanitizer excludes routine state, route/action semantics,
  source labels/refs, schedule text, IDs, and support details.

## Implementation result

Completed 2026-08-07. The contract package and 30-scenario sanitized corpus, read-only CLI
validate/diff commands, pure KMP state machine, adaptive Compose preview, exact
fake-scenario wiring, SWIP exclusion canary, and macOS/Linux golden baselines are
implemented together. Verification passed for schema and CLI suites, KMP/client
and UI desktop suites, SWIP privacy tests, Android debug assembly, iOS simulator
tests/framework link, and the Linux Smart Briefings golden gate. No live provider,
OAuth, credential, API mutation, Dayfold write, or routine telemetry path was
introduced.

The verification boundary is intentionally precise: desktop/common tests cover the
structured flow, semantics, reducer transitions, recovery, and revocation; Android
has an assembled debug app plus retained-runtime instrumentation coverage; iOS runs
shared pure tests and compiles/links the framework. The installed Android golden
path, Android predictive gesture/large-font/TalkBack/RTL checks, and any reachable
iOS routine screen remain unverified. The current iOS host has no entry to this
fake scenario, so this commit makes no Android installed-flow or iOS UI-E2E claim.

## Deferred, explicit start gates

The following remain in the long-horizon plan and must not be inferred from this
implementation:

1. accept/replace ADR 0061 and answer INB-34;
2. accept/replace ADR 0062 and answer INB-35;
3. choose one provider after a real capability/entitlement spike;
4. reconcile provider OAuth with the gateway principal, pending/inert authority,
   PKCE/state/callback binding, and three-party revocation;
5. decide eligible account tier, retention/disclosure, counsel posture, and policy
   constants;
6. design a distinct routine JWT audience and `/routine-tools/*` data plane that
   old human/content routes reject;
7. add durable server/cache records and transaction-bound idempotency before staged
   content;
8. begin with human-approved, create-card-only staged apply; bounded auto, updates,
   E2EE/K3, and direct K4 remain later separate gates.

## Completion definition for this commit

- Imported designs are discoverable and deviations are documented.
- Contracts, fixtures, CLI validate/diff, state model, Compose flow, and tests ship
  together.
- Android debug/fake can traverse the local flow; release remains hidden.
- Kotlin/JVM, Compose desktop, Android debug, and iOS framework checks pass.
- The final implementation review finds no route to live processing, credential
  handling, content mutation, or telemetry.
- Every traversable preview layout says nothing connects or saves; it never claims
  real source observation, provider connection, publication, or confirmed revoke.
