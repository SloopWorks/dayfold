# Debug drawer shared-component migration + component-aware reports

**Date:** 2026-08-10

**Status:** reviewed design proposal, updated with the organization consumer audit
and backend source-resolution contract; implementation blocked on the gates below
**Verdict:** migrate every consumer to the shared drawer, then build one shared
component-capture engine and one exact-build source resolver. Do not add another
app-owned drawer, annotation implementation, or source-linking heuristic.

## 1. Requested outcome

1. Dayfold consumes the reusable `SloopWorks/debugdrawer` component rather than
   maintaining an embedded copy.
2. Integrating apps choose `Disabled`, `BoundsOnly`, or `SourceOnDemand` per build;
   shared drawer/reporting UI does not force tooling dependencies or activation.
3. Debug builds can enter component-pick mode, tap the app, inspect the selected
   Compose component and its ancestors, and attach the selection to a SWIP report.
4. V1 reports include a privacy-bounded selected component tree: bounds,
   component/group identity, and best-effort name/file/function/line after
   successful tooling activation. Role/test tag is a gated extension, not a v1
   promise.
5. Public release retains no viewer, source strings, or SWIP debug dependencies.
   A future never-published `internalMinified` canary deliberately enables the
   feature to measure degraded/best-effort mapped attribution.
6. Submitted component details can be traced back to authoring code when the
   report references an immutable build manifest and the backend has the matching
   source index or Compose/R8 mapping. Exact, best-effort, ambiguous, and
   unavailable results are distinguishable; the system never links against the
   latest branch or guesses silently.

## 2. What exists today

### Dayfold drawer integration

Dayfold has one host integration, not competing drawer UIs:

- `MainActivity` calls `DebugDrawer.install(...)`, resolves the backend through
  `DebugDrawer.backendUrl(...)`, and wraps the app with `DebugDrawerHost`.
- Debug plugins are registered through `debugDrawerPlugins(...)`.
- Debug uses `:debugdrawer`, `:debugdrawer-redux`, and `:debugdrawer-swip`.
- Release uses `:debugdrawer-noop`; plugin mirrors return empty/inert values.

The integration shape is correct. Draft PR
[`SloopWorks/dayfold#379`](https://github.com/SloopWorks/dayfold/pull/379) replaces
the in-repo modules with shared `0.1.0` coordinates, updates the shared SWIP
namespace, removes the embedded source, and adds dependency/DEX release checks.
It is intentionally draft until the artifacts resolve remotely.

### Shared component extraction

`SloopWorks/debugdrawer` PR #1 (`WI-256`) is merged at `92fec3b`, CI-green, and
declares that repository the source of truth for version `0.1.0`. Its functional
source matches Dayfold's embedded copy; differences are publishing configuration,
the non-snapshot version, compile SDK, and the shared SWIP adapter package
(`com.sloopworks.debugdrawer.swip`).

The package is **not published yet**. The first operator-only
[`publish` run](https://github.com/SloopWorks/debugdrawer/actions/runs/31438033231)
received an empty `SLOOPWORKS_PACKAGES_TOKEN`, excluded `debugdrawer-swip`, and
GitHub Packages rejected core/noop uploads with HTTP 401. The shared repository
must receive a write-capable packages secret and publish all platform variants
before a clean consumer can verify the migration.

### Organization consumer audit

An audit of all eight active SloopWorks repositories found one current runtime
consumer and one committed future consumer:

- **Dayfold** is the only app currently mounting `DebugDrawerHost`; its default
  branch still contains the embedded modules until PR #379 passes the remote-only
  gate and merges.
- **Dinners/PickedPlate** has accepted ADR 0028 requiring the shared drawer, but
  currently wires only its app-owned debug `:swip-inspector` data module. It does
  not yet resolve or mount `com.sloopworks.debugdrawer:*`.
- **SWIP** contains integration contracts and design handoffs, not another drawer
  runtime. The remaining repositories contain no runtime drawer usage.

Therefore “shared drawer adoption” is not complete when Dayfold migrates. The
shared API, setup copy, and tests must stay product-neutral, and portfolio closure
requires a Dinners consumer PR after the base artifacts publish. Source-copying
the drawer or recreating a Dinners-specific shell remains prohibited.

### Component-aware SWIP reporting

SWIP already contains most of the consumer side:

- `CaptureSources.uiTree: UiTreeProvider?` and `PartKind.UI_TREE` exist.
- The tree rides the screenshot consent toggle.
- The UI-tree contract is an allowlist: ids, roles, bounds, and test tags; no text
  nodes or values.
- The annotation editor already has a **Point** tool. It parses `bounds`, hit-tests
  the gesture center, and snaps to the smallest containing rectangle.
- Annotation JSON already has `highlights`.

Dayfold's `BugReporterGlue` supplies screenshot, breadcrumbs, and timeline, but
does **not** supply `uiTree`. The Point tool therefore receives an empty bounds
list and falls back to the user's free rectangle. The component path is designed
and partially implemented, but not connected. More importantly, Point currently
reduces any supplied tree to anonymous rectangles: it does not preserve node ids,
parents, source, or a selected path, and SWIP has no API to open a report
preseeded with the drawer's frozen capture. This is a SWIP prerequisite, not a
Dayfold adapter-only task.

### Existing Dayfold work

`backlog/later.md` already defines two levels for snapshot inspection:

1. semantics + bounds;
2. full LayoutNode/composable/source inspection.

That work is aimed at headless snapshots. The live debug/report surface should
reuse the same tree schema and hit-testing rules, but its capture lifecycle and
release posture are different.

## 3. Technical findings

### Supported tooling surface

The `androidx.compose.ui:ui-tooling-data` API exposes the required
source/name/bounds projection:

- `CompositionData.mapTree(...)` / `asTree()`;
- `SourceContext.name`;
- `SourceContext.bounds`;
- `SourceContext.location` (`SourceLocation` has file name, zero-based line,
  source offset/length, and package hash).

It is a tooling API (`@UiToolingDataApi`) and may change, so the dependency and
all translation code belong in one optional shared adapter, not in product UI.
See the official [SourceContext reference](https://developer.android.com/reference/kotlin/androidx/compose/ui/tooling/data/SourceContext)
and [tooling-data package reference](https://developer.android.com/reference/kotlin/androidx/compose/ui/tooling/data/package-summary).

Live composition capture can mirror Compose Preview's own pattern:

- a wrapper records `currentComposer.compositionData` into a weak set;
- `LocalInspectionTables` lets subcompositions register;
- tree traversal and serialization run only while component inspection/report
  capture is requested.

There is an unavoidable source-attribution tradeoff. Although compiler source
information is enabled, Composer only records the markers needed for
`SourceContext.name/location` after tooling calls `collectParameterInformation()`.
That call also enables parameter collection, forces recompose scopes, and cannot
be reversed for that composer's lifetime. The integrating build therefore chooses
`BoundsOnly`, which never enables it, or `SourceOnDemand`, which calls the tooling
API on first explicit source-aware inspection, waits for a completed
recomposition/frame, and accepts the additional allocations for the rest of that
debug composition. Parameter values are never read or serialized. If activation
is disabled or its canary fails, the UI reports source as unavailable rather than
guessing.

No reflection into `AndroidComposeView` or private `LayoutNode` fields is needed
for the source/name/bounds spike. General Role/TestTag extraction is not supplied
by `ui-tooling-data`; modern modifier nodes are not covered by a legacy
`SemanticsModifier` cast. Treat semantics as a separate pinned-version prototype
with an explicit stability/reflection decision. Do not promise role/test-tag
capture until Material-control and `Modifier.testTag` integration tests pass.

### Source information and shrinking

Kotlin 2.3.20 includes Compose source information by default; the project does
not override it. Kotlin documents that default from 2.1.20 onward and exposes
`includeSourceInformation` in the compiler DSL. See [Kotlin 2.1.20 source-information default](https://kotlinlang.org/docs/whatsnew2120.html#source-information-included-by-default)
and the [`includeSourceInformation` API](https://kotlinlang.org/api/kotlin-gradle-plugin/compose-compiler-gradle-plugin/org.jetbrains.kotlin.compose.compiler.gradle/-compose-compiler-gradle-plugin-extension/include-source-information.html).

That does **not** make source locations a release guarantee. Compose runtime's
consumer ProGuard rules mark `sourceInformation*` calls as having no side effects,
and the official runtime source states they are safe for R8/ProGuard to remove.
See [Compose runtime source-information contract](https://android.googlesource.com/platform/frameworks/support/+/HEAD/compose/runtime/runtime/src/commonMain/kotlin/androidx/compose/runtime/Composer.kt).

Kotlin 2.3 adds a better minified-build fallback: Compose group keys can be
appended to the R8 mapping file and later deobfuscated. `includeComposeMappingFile`
is enabled by default when R8 is active. See [Kotlin 2.3 minified Compose stack traces](https://kotlinlang.org/docs/whatsnew23.html#compose-compiler-stack-traces-for-minified-android-applications)
and the [`includeComposeMappingFile` API](https://kotlinlang.org/api/kotlin-gradle-plugin/compose-compiler-gradle-plugin/org.jetbrains.kotlin.compose.compiler.gradle/-compose-compiler-gradle-plugin-extension/include-compose-mapping-file.html).

Implications:

- debug/non-minified: file/function/line requires explicit tooling activation,
  a completed recomposition, and remains best-effort;
- minified internal canary: capture bounds + group key, then resolve against the
  exact build's Compose/R8 mapping off-device; validate how much call-site
  precision is recovered before promising file/line;
- never add release keep rules solely to retain source strings. That would add
  binary size and expose implementation detail in every distributed APK.

### Backend traceability to authoring code

Tracing a selected UI node back to authoring code is feasible, but a basename and
line number alone are not a durable key. `ResponseSheet.kt:146` may exist in more
than one module, line numbers move between revisions, local builds may be dirty,
and minified builds may retain only an integer Compose group key. The backend must
resolve against the **exact producing build**, never current `main`.

Every build for which backend code links are promised registers an immutable
`BuildSourceManifest` out of band from CI/build tooling. It is keyed by the
report's existing app/build id and contains:

- an allowlisted repository key and exact Git revision;
- variant, version code/name, dirty flag, Kotlin/Compose compiler versions;
- an immutable source-index id + SHA-256 digest;
- when R8 is enabled, the Compose/R8 mapping artifact id + digest;
- creation/retention metadata and authorization scope.

The manifest and mapping artifacts are never bundled into the client report. A
node carries only privacy-bounded lookup hints: display basename, optional
validated repository-relative path, one-based line, optional function name,
package hash/source offset when Compose supplies them, and an integer Compose
group key when the runtime key is actually an `Int`. The adapter must not invent
a repository-relative path from a basename. Absolute paths, source contents, raw
mapping contents, arbitrary runtime-key strings, and client-supplied repository
URLs are forbidden.

The resolver follows a deterministic confidence ladder:

1. **Exact direct** — a normalized repository-relative path exists at the
   manifest revision and the line is in range; link to that immutable commit.
2. **Exact mapped** — the integer group key resolves through the mapping artifact
   whose digest is registered for that exact build.
3. **Best-effort unique** — basename plus package/function/line hints yields one
   candidate in the source index at that revision; label it best-effort, not exact.
4. **Ambiguous** — multiple candidates remain; return the candidates and evidence
   without choosing or enabling a single “Open source” action.
5. **Unavailable** — the build is unregistered/dirty, source was not recorded, the
   mapping is missing, or no candidate exists. Preserve the raw safe display
   reference and reason; never fall forward to another revision.

The resolver treats all report hints as untrusted input: reject absolute paths,
`..` traversal, unknown repositories/build ids, digest mismatches, and commits
outside the configured repository. Source links inherit repository/report access
control and audit logging. A dirty local build may still show file/line on-device,
but the backend reports `Build not indexed` unless a deliberate internal build
pipeline registered that exact source state; it never uploads a developer's
working tree automatically.

## 4. Proposed architecture

### 4.1 Ownership

`SloopWorks/debugdrawer` owns:

- the generic in-memory component-tree model, capture, and privacy pruning;
- the Compose tooling adapter;
- hit testing and selection state;
- a `Components` `DebugPlugin`;
- the app-root selection overlay;
- a generic provider consumed by SWIP's published typed adapter.

SWIP owns:

- report capture/consent/transport;
- the typed `UI_TREE` wire model, encoder, consent filtering, persistence
  transform, and annotation parts;
- the annotation canvas and Point tool;
- preserving the selected component id in the vector annotation/report rather
  than reducing a component highlight to an anonymous rectangle.

Build/CI infrastructure owns:

- producing and registering the immutable `BuildSourceManifest`, source index,
  and optional Compose/R8 mapping under one exact build id;
- retaining mapping artifacts according to the report retention window;
- proving that the manifest digest matches the artifact used by the resolver.

The reporting backend owns:

- validated, access-controlled resolution against the registered exact build;
- exact/best-effort/ambiguous/unavailable confidence and evidence;
- immutable source permalinks and audit logging, never latest-branch links.

Each integrating app owns only variant wiring, a stable app/build identity, and
product leak tests. Dayfold and Dinners must not fork the capture, setup, or
source-resolution contracts.

### 4.2 Shared modules

Split the shared UI/contract from the optional tooling implementation:

- `com.sloopworks.debugdrawer:debugdrawer-components` — Components plugin,
  status UI, selection UI, and enablement guidance; no `ui-tooling-data`
  dependency.
- `com.sloopworks.debugdrawer:debugdrawer-compose-inspector` — Android Compose
  provider; this is the only artifact that depends on `ui-tooling-data`.

The core `debugdrawer` artifact owns all generic tree, availability, registration,
selection-state, and host-action types, mirrored by `debugdrawer-noop`. Both
`debugdrawer-components` and the Android-only inspector depend on core; neither
depends on the other. The SWIP adapter also depends only on core's generic
contract. Core, Components UI, and SWIP adapters must not pull tooling
transitively. The lifecycle is observable rather than a fire-and-forget picker
call:

```kotlin
enum class UiTreeMode {
  Disabled,
  BoundsOnly,
  SourceOnDemand,
}

enum class UiTreeCaptureMode {
  BoundsOnly,
  SourceOnDemand,
}

enum class SourceAvailability {
  Available,
  AvailableOnDemand,
  DisabledByMode,
  NotRecordedForBuild,
}

enum class UiTreeUnavailableReason {
  ProviderNotConnected,
  DisabledByHost,
  BoundsCaptureFailed,
  NoTreeForCurrentScreen,
}

data class UiTreeEnablementHint(
  val summary: String,
  val steps: List<String>,
  val docsUrl: String?,
)

sealed interface UiTreeRecovery {
  data class Configure(val hint: UiTreeEnablementHint) : UiTreeRecovery
  data object Retry : UiTreeRecovery
  data object None : UiTreeRecovery
}

sealed interface UiTreeAvailability {
  data class Ready(val source: SourceAvailability) : UiTreeAvailability
  data class Unavailable(
    val reason: UiTreeUnavailableReason,
    val recovery: UiTreeRecovery,
  ) : UiTreeAvailability
  data object UnsupportedPlatform : UiTreeAvailability
}

sealed interface UiTreeProviderState {
  data class Ready(val source: SourceAvailability) : UiTreeProviderState
  data object BoundsCaptureFailed : UiTreeProviderState
  data object NoTreeForCurrentScreen : UiTreeProviderState
  data object UnsupportedPlatform : UiTreeProviderState
}

interface UiTreeCapability {
  val state: StateFlow<UiTreeProviderState>
  suspend fun capture(mode: UiTreeCaptureMode): FrozenComponentCapture?
}

interface UiTreeRegistration {
  val availability: StateFlow<UiTreeAvailability>
  suspend fun capture(): FrozenComponentCapture?
}

fun registerUiTree(
  mode: UiTreeMode,
  capability: UiTreeCapability?,
): UiTreeRegistration

sealed interface InspectorState {
  data object Closed : InspectorState
  data class Drawer(val panelId: String) : InspectorState
  data object Picking : InspectorState
  data class Detail(val capture: FrozenComponentCapture) : InspectorState
  data class HandingOff(val capture: FrozenComponentCapture) : InspectorState
}

data class ComponentNode(
  val id: String,
  val parentId: String?,
  val composeGroupKey: Int?,
  val name: String?,
  val bounds: PixelBounds,
  val source: ComponentSource?,
)

data class ComponentSource(
  val displayFile: String,
  val repositoryRelativePath: String?,
  val line: Int?,
  val function: String?,
  val packageHash: Int?,
  val sourceOffset: Int?,
)

data class BuildSourceRef(
  val buildId: String,
  val dirty: Boolean,
)
```

The integrating app calls `registerUiTree(...)` once per build variant and passes
that same host-owned `UiTreeRegistration` to the drawer Components and SWIP
plugins:

- `Disabled`: no tooling API is called and selection/report capture stays inert;
- `BoundsOnly`: tree geometry is available without source-aware activation;
- `SourceOnDemand`: the first explicit inspection enables Compose tooling source
  collection, waits for a completed recomposition/frame, then captures best-effort
  name/file/line.

`SourceOnDemand` begins as `Ready(AvailableOnDemand)`. After the first completed
source-aware capture, the provider emits `Ready(Available)` when it obtained usable
name/file/line, otherwise `Ready(NotRecordedForBuild)`. Both results keep bounds
selection enabled; the pre-activation status cannot remain stale indefinitely.

The registration resolves availability before any provider access. `Disabled`
returns `Unavailable(DisabledByHost, Configure(change mode))` without reading or
calling `capability`; an enabled mode with `capability = null` returns
`Unavailable(ProviderNotConnected, Configure(connect provider))`; only enabled,
connected registrations observe or call the provider. This is the single source
of truth consumed by both plugins.

Typical use is `SourceOnDemand` in debug/developer variants and no Components
plugin at all in public release. An internal build may intentionally register
`debugdrawer-components` without the Compose provider; that keeps the discovery
surface while honestly reporting that UI-tree tooling is unavailable.

`Ready` always means bounds selection works. Missing source is represented only by
`Ready(source = DisabledByMode | NotRecordedForBuild)` and never disables picking.
`Unavailable` is reserved for no connected provider, explicit host disablement,
bounds-capture failure, or a valid capture with no selectable tree on the current
screen. A missing capability proves only that no provider is connected, not
whether its artifact exists. `UnsupportedPlatform` is defensive; the Components
plugin normally is not registered there.

Recovery is reason-specific: missing/disabled configuration uses
`Configure(hint)`, but with different instructions; transient bounds failure uses
`Retry`; a valid empty screen uses `None` plus manual reporting; defensive
unsupported state uses no visible recovery. Source guidance is derived from the
ready state's `SourceAvailability`, not from `Unavailable`.
`UiTreeEnablementHint` is display-only local metadata; it is never written into a
report bundle.

The provider-not-connected hint is short and actionable:

1. add `debugdrawer-compose-inspector` to this build variant if absent;
2. connect its `UiTreeCapability` to the Components/SWIP plugin with `BoundsOnly` or
   `SourceOnDemand`.

The host-disabled hint says only to change this build's mode. A ready
`DisabledByMode` source says to choose `SourceOnDemand`; `NotRecordedForBuild`
says to enable Compose compiler source information and notes that shrinkers may
remove it. Capture failure offers Retry/manual reporting, not build setup.

The UI may provide **Copy setup snippet**, but the versioned snippet belongs to
the shared artifact/docs so consuming apps do not invent divergent instructions.

The core host-action API includes `dismissDrawer`, overlay installation/removal,
cancel, and previous-panel restoration. `DebugDrawerHost` owns this state machine
and the topmost input-intercepting overlay. Back/Escape cancels Picking and
restores Components; `onStop`, host disposal, or configuration change must cancel
interception. A plugin cannot own this as local content state because today's
`DebugScope` has no close, result, or lifecycle contract.

The capture implementation must not copy or expose `Group.data`, parameters,
text, descriptions, state, or modifier values. Those can contain family data
even when the property name looks harmless. SWIP's encoder receives only the
already-pruned generic capture and owns the report representation.

`BuildSourceRef` is created by trusted build wiring, not inferred from UI-tree
data. Source-index/mapping artifact ids stay in the backend manifest and are not
accepted from the client. `repositoryRelativePath` is populated only when
tooling/build metadata supplies a path that normalizes beneath an allowlisted
source root; otherwise it is null and `displayFile` remains basename-only. The
mobile detail surface shows the safe code reference it has, but backend resolution
status is unknown until upload unless the build manifest was already confirmed
locally.

Compose tooling exposes `Group.key` as `Any?`, including joined or application
keys. `composeGroupKey` is populated only when the runtime key is already an
`Int`; never stringify or hash an arbitrary key for the report. Any future
semantics adapter must directly allowlist properties rather than iterate or
stringify `SemanticsConfiguration`. App-authored test tags can contain
record/user identifiers, so Dayfold v1 omits them from transport even if the
prototype can read them.

### 4.3 Capture wrapper

`DebugDrawerHost` receives the resolved host-owned `UiTreeRegistration` shared
with SWIP—never the raw optional capability. When registration is ready, it:

1. introduces an explicit product-content boundary, preferably a dedicated
   product subcomposition whose root table is separate from drawer/reporter/
   bubble siblings; Gate C must prove this isolation rather than assume a private
   `LocalInspectionTables` set splits one root slot table;
2. records only the product subcomposition root plus descendant product
   subcomposition `CompositionData` into a weak registry—never the host root;
3. in `BoundsOnly`, never enables parameter/source collection;
4. in `SourceOnDemand`, calls `collectParameterInformation()` once on explicit
   inspection, waits for a completed recomposition/frame, and accepts its
   lifetime allocation cost;
5. snapshots attached groups on the main thread only for an explicit
   component-view/report capture;
6. copies only allowlisted primitives, then completes tree shaping and size
   pruning off the render path.

The registry must exclude the drawer/reporter overlay from selection or label it
as tooling. The product app remains visible under selection mode but ordinary app
taps are intercepted until the user selects or cancels. This blocks touch input;
it does not freeze timers, network activity, recomposition, keyboard, or
accessibility actions, so all detail/report UI describes the result as a frozen
capture rather than a frozen app.

Components and reporting plugins render the same shared availability state. When
bounds are unavailable, they do not silently hide Point or pretend a component
was selected: component picking is disabled, manual rectangle annotation remains
available, and recovery matches the reason. Source degradation stays in the ready
state with bounds picking enabled and a non-error source note. Unsupported
platforms normally register neither plugin nor visible help.

### 4.4 Picker UX

One engine supports two entry points:

1. **Debug drawer → Components**: close the sheet, block app touch, show a
   crosshair/outline, tap to select a component, then show a detail sheet with
   ancestor navigation and copy actions.
2. **Bug reporter → Point**: reuse the captured tree from the screenshot, snap the
   highlight, and attach the selected node/path/source metadata to the report.

Both surfaces use one authoritative `ComponentSelection(nodeId, hitPath,
bounds)`. SWIP's current Point implementation discards node identity and keeps
only rectangles; it must preserve ids through parsing and serialize `node_id`
with the highlight. Point grammar is tap = component selection, drag = manual
rectangle with `node_id = null`. Reselecting in Point atomically replaces the
report's selected id/path and highlight, so metadata cannot describe a different
component from the rectangle.

Candidate ordering is deterministic: attached, visible, nonzero-area,
non-tooling nodes only; prefer an actionable semantic node, then the deepest
source call on the single ancestor path through the tap. If the semantics spike
is not approved, v1 skips the first preference. Parent/Child moves only within
that retained path. Remaining ties use stable source-tree order, never area alone.

### 4.5 Capture consistency

Screenshot pixels and bounds must share one Activity-window coordinate space.
Introduce a paired-capture coordinator: it snapshots the allowlisted tree on the
main thread immediately before requesting PixelCopy and caches the resulting
tree + PNG under one capture id for bundle assembly. SWIP currently calls its
separate `ScreenshotProvider` and then `UiTreeProvider`, so the adapter must return
the cached tree rather than traversing the live composition a second time.

PixelCopy is asynchronous, so “same frame” must be treated as an invariant to
verify in a pre-architecture spike, not an assumption. The capture UI intercepts
ordinary app interaction, then suppresses the drawer, bubble/edge tab, instruction
strip, candidate label, and outlines for the capture frame. The coordinator pins
tree + PNG operations to that main-thread sequence; after capture it restores
detail chrome and seeds the annotator with the frozen selection. An instrumentation
test intentionally animates/recomposes a target to prove that the screenshot and
bounds remain aligned (or forces a SWIP paired-provider API if they cannot be
made atomic).

The tree includes viewport dimensions and an origin. Insets are not manually
subtracted: `SourceContext.bounds` is window-relative and Android's screenshot is
the Activity window. Tests cover edge-to-edge status/navigation bars.

### 4.6 Report schema

Extend the existing UI-tree part without creating a second report part:

```json
{
  "schema": "swip:bugreport:ui-tree:1",
  "build_ref": "dayfold-android-debug-1842",
  "viewport": {"width": 1080, "height": 2400},
  "selected": "n_42",
  "nodes": [{
    "id": "n_42",
    "parent_id": "n_17",
    "compose_group_key": 123456789,
    "name": "ResponseSheet",
    "bounds": {"l": 72, "t": 2010, "r": 1008, "b": 2160},
    "source": {
      "display_file": "ResponseSheet.kt",
      "repo_path": "apps/ui/src/commonMain/kotlin/example/ResponseSheet.kt",
      "line": 146,
      "function": "ResponseSheet",
      "package_hash": 481516,
      "source_offset": 9124
    }
  }]
}
```

Rules:

- `build_ref` joins to a separately registered immutable build manifest; it is
  not a repository URL and the UI-tree part does not contain mapping artifacts;
- `source.display_file` is basename only;
- `source.repo_path` is optional, normalized repository-relative, and omitted
  unless trusted build/tooling metadata supplied and validated it;
- absolute paths, path traversal, source contents, and client-supplied repository
  URLs are rejected;
- line is one-based at the serialized boundary;
- name/source and every source hint may be absent;
- `compose_group_key` is retained only for an actual integer Compose group key;
- arbitrary/joined runtime keys are omitted, never stringified;
- Dayfold v1 omits role and test tag; any future semantics extension requires a
  proven adapter and always omits raw test tags from transport;
- node ids are capture-local, not durable user/device identifiers;
- no text/value/parameter fields;
- the frozen full tree exists only in draft memory for hit testing;
- persistence keeps the selected node + its ancestor path and the geometry
  required by manual annotations;
- a new 50 KiB UTF-8 serializer limit preserves the selected path first, records
  truncation, and handles pathological depth iteratively.

SWIP owns and versions `swip:bugreport:ui-tree:*`. Its typed annotation schema
also carries `node_id` for component highlights. This is a SWIP
schema/implementation change: today Point reduces the tree to anonymous
rectangles. Manual rectangles carry `node_id: null`, and changing Point selection
updates the one selected node, its ancestor path, and component vector highlight
in one operation. V1 supports one component selection; manual annotations remain
separate geometries.

The review surface must make the consent payload inspectable. Expanding the
screenshot/component row shows selected name/path/source when present, node
count, trimmed status, and “No text or state included.” Its single screenshot
toggle removes PNG, UI tree, selected id, and component highlight together.
The component source hints are removed with that toggle. The report-level build
identity may remain because it describes the app binary and is also needed for
ordinary crash/symbol resolution; it does not reveal selected UI structure.

### 4.7 Backend source resolution

On ingestion, the backend joins `build_ref` to the registered
`BuildSourceManifest`, validates its repository/revision/digests, and resolves the
selected node plus retained ancestors. It stores the safe submitted hints and a
separate derived result:

```json
{
  "status": "exact_direct | exact_mapped | best_effort_unique | ambiguous | unavailable",
  "revision": "4f8c2ab...",
  "repo_path": "apps/ui/src/commonMain/kotlin/example/ResponseSheet.kt",
  "line": 146,
  "confidence": "exact | best_effort | none",
  "reason": null,
  "candidates": []
}
```

Derived links always pin the manifest revision. Re-resolution is allowed only
against the same immutable manifest (for example after a delayed mapping upload)
and records an audit event; it cannot silently change to a newer commit. The
report detail UI shows **Open source** only for `exact_direct` or `exact_mapped`.
`best_effort_unique` requires an explicit **Open best-effort match** label;
`ambiguous` lists candidates without preselecting one; `unavailable` shows the
reason and retains Copy code reference when safe hints exist.

## 5. Debug, release, and obfuscation posture

### Current Dayfold release

- Real drawer, inspector tooling, SWIP reporter, and SWIP debug sink remain
  `debugImplementation` only.
- Release retains only the API-compatible noop facade required by `src/main`.
- `debugDrawerPlugins(...)` stays empty in release.
- Add a real CI scan of the release dependency graph plus APK/AAB DEX, resources,
  and strings. The existing `DebugLeakRegressionTest` checks weak-host lifecycle,
  not artifact leakage. The new scan rejects
  `debugdrawer-components`, `debugdrawer-compose-inspector`, `ui-tooling-data`,
  `swip-debug`, and real drawer classes/resources while allowing the noop facade.

This produces **zero component-viewer behavior and zero captured tree** in the
public release build.

### Future release reporting

If product bug reporting is later enabled outside internal/debug channels, that
is a separate consent/privacy ADR. Its component metadata contract should be:

- bounds + capture-local id by default; semantics require an explicitly approved
  adapter and test tags require a host sanitizer/allowlist;
- integer Compose group key when available;
- file/function/line only when present naturally;
- exact build id in the manifest and matching mapping stored in CI;
- never upload R8/Compose mapping files in the client report itself.

Backend source resolution is not permission to retain more client metadata. The
public-release posture remains independently gated: build manifests/mappings may
exist for normal crash symbolication, but no component node/source hint is sent
unless a separately approved report surface captures it with consent.

Dayfold's release build is currently non-minified and intentionally contains no
inspector/SWIP report code, so v1 cannot exercise mapped capture there. Obfuscation
testing is future work: an explicitly approved, never-published `internalMinified`
variant would enable inspector/SWIP with R8 and determine whether group-key
mapping recovers a function or precise file/line. Until then the only public
release requirement is total feature/dependency absence.

## 6. Sequencing and gates

### Gate A — publish and migrate shared drawer

Operator action:

1. configure a write-capable `SLOOPWORKS_PACKAGES_TOKEN` for
   `SloopWorks/debugdrawer`; the first run at exact source SHA `92fec3b` failed
   with an empty token/HTTP 401 and excluded the SWIP adapter;
2. rerun `SloopWorks/debugdrawer/.github/workflows/publish.yml` for 0.1.0 after
   confirming producer CI is green (the publish workflow itself does not test);
3. verify all four Maven artifacts, platform variants, and Gradle metadata resolve
   in a clean consumer.

Then Dayfold draft PR #379:

1. add the `SloopWorks/debugdrawer` Maven repository alongside SWIP;
2. replace project dependencies with `com.sloopworks.debugdrawer:*:0.1.0`;
3. change SWIP inspector imports to `com.sloopworks.debugdrawer.swip.*`;
4. remove `:debugdrawer*` includes and embedded source directories;
5. sweep repository references, replacing the CI job that invokes local
   `:debugdrawer*` tasks and stale `processes/mobile-release.md` instructions;
6. run debug tests/build, release bundle, and the new artifact leak checks.

Do not keep a fallback embedded copy after migration; that recreates two sources
of truth. After the base artifact is available, Dinners/PickedPlate must replace
its planned app-owned drawer work with a shared-artifact integration. That
portfolio follow-through is tracked independently from Dayfold's component-picker
delivery, but shared adoption cannot be described as organization-complete until
it lands.

### Gate B — design sign-off

Run `designs/DESIGN-BRIEF-debugdrawer-component-reporting.md` and obtain ADR 0008
operator approval for the Components panel, pick overlay, selection detail, and
report handoff. SWIP's existing Point-tool design remains the style/interaction
precedent; the new drawer entry and component detail are not yet mocked.

Before implementation, the shared drawer shell also needs 48dp back/close hit
targets, safe-drawing insets, and an OS-backed reduced-motion provider; the
component flow cannot claim those guarantees while the shared primitives do not.

### Gate C — capture feasibility spike

Before freezing architecture, prove on a real Android build:

1. `Disabled` makes no tooling call and `BoundsOnly` captures without source
   activation;
2. `SourceOnDemand` + completed-frame wait yields composable
   name/file/line and documents its one-way allocation cost;
3. product-only registry excludes all tooling and separate-window content;
4. PixelCopy + tree can form a clean, aligned immutable pair while a target is
   animating/recomposing;
5. a pinned Compose-version semantics prototype either proves Material role and
   modern `Modifier.testTag`, or v1 explicitly ships without semantics.

A single SWIP `VisualCaptureProvider` returning PNG, tree, viewport, and capture
id is required to remove the two-provider race, but its return type does not make
asynchronous PixelCopy atomic. If the animated-target spike cannot prove an
acceptable pair using a reliable frame-quiescence or synchronous app/decor draw
mechanism, component-aware reporting stops at this gate. Documenting a weaker
consistency contract requires explicit operator/privacy approval.

### Gate D — typed SWIP selection + handoff

SWIP must own/version the typed UI-tree model, keep node identity through Point,
and add a supported preseeded-draft or single-use capture-token entry point. Test
stale tokens, cancellation, rotation, concurrent report open, screenshot-toggle
removal, component tap, and manual rectangle fallback. V1 component handoff is
Bug-only: hide/disable Feedback for this preseeded draft unless SWIP makes typed
draft reclassification update the persisted manifest and tests it end-to-end.

### Gate E — build provenance and backend source resolver

Before claiming “open authoring code”:

1. define the immutable `BuildSourceManifest` and make CI register exact revision,
   source-index digest, optional Compose/R8 mapping digest, compiler versions, and
   dirty state under the report build id;
2. prove direct repository-relative path resolution pins an immutable commit and
   rejects traversal, absolute paths, unknown repositories, digest mismatches, and
   out-of-range lines;
3. prove basename-only hints return best-effort/ambiguous/unavailable without
   guessing, and that dirty/unregistered builds never fall forward to `main`;
4. prove integer group-key resolution uses only the exact build's Compose/R8
   mapping and handles absent/expired/corrupt mappings honestly;
5. define retention, access control, and audit behavior for manifests, mappings,
   candidates, source links, and re-resolution.

The mobile feature may ship with safe file/line display before this gate, but it
must say `Resolved after upload`/`Build not indexed` rather than promise a link.
The backend **Open source** action is blocked until this gate passes.

### Gate F — shared implementation

Implement `debugdrawer-components` plus the Compose provider in
`SloopWorks/debugdrawer` and publish the next version. Components/status UI must
work without a tooling dependency; the provider's target matrix is Android-only
(or Android/JVM only if a real desktop consumer is proven), not the core drawer's
iOS matrix. Update the SWIP adapter to consume the same capability/availability
contract. Dayfold upgrades only after shared CI + consumer smoke tests pass.

### Gate G — Dayfold adapter

Wire the shared provider into Dayfold's developer build with an explicit mode and
pass its frozen capture through the published typed SWIP adapter—Dayfold must not
author raw UI-tree JSON. Public release registers neither Components nor the
provider. Extend the product-owned privacy leak test with salted
text/description/parameter canaries.

## 7. Verification plan

### Shared unit tests

- `Disabled` neither observes provider state nor calls it; registration maps only
  enabled modes to `UiTreeCaptureMode`; `BoundsOnly` never activates source
  collection; `SourceOnDemand` activates at most once per composition;
- drawer Components and SWIP consume the same registration/availability instance;
- Components renders provider-not-connected, host-disabled, source-degraded-ready,
  capture failure/retry, no-tree/manual-only, and ready states from the shared
  availability model;
- `AvailableOnDemand` has an honest pre-activation label and transitions to
  `Available` or `NotRecordedForBuild` after completed activation;
- source-degraded-ready keeps bounds selection enabled; only missing, disabled,
  failed bounds or `NoTreeForCurrentScreen` disables it;
- unavailable Components offers report-without-details; Copy setup confirms
  success and Retry exposes busy/success/failure accessibility announcements;
- enablement guidance is concise, version-owned, copyable, and absent from
  serialized capture/report data;
- `debugdrawer-components` dependency metadata contains no `ui-tooling-data`;
- nested hit testing rejects detached/hidden/zero-area nodes and uses the defined
  ancestry/source-order tie-break;
- deterministic hit paths prefer actionable semantics when that adapter is
  approved, then deepest source call;
- parent/child stepping is deterministic;
- generic capture copies only allowlisted primitive fields;
- salted text, descriptions, state, parameters, absolute paths, traversal paths,
  repository URLs, and raw mapping contents never appear;
- non-integer/joined/application group keys are omitted without invoking their
  string representation;
- if the semantics adapter is approved, it reads only Role/TestTag even when the
  same configuration contains text, descriptions, and actions;
- absent source information produces a valid tree;
- pruning preserves the selected path and survives pathological depth;
- tooling overlays are excluded.

### SWIP tests

- unavailable UI-tree state keeps reporting/manual rectangle annotation usable,
  disables component snapping, and shows the shared reason-specific recovery;
- only `Configure` recovery shows enablement guidance; bounds failure shows Retry,
  no-tree shows manual-only, and unsupported normally has no registered surface;
- reporter keeps setup help collapsed and shows the short manual fallback only
  while Point is selected;
- unavailable guidance/status never enters report attachments or manifest;
- typed parser retains ids, parents, bounds, source, and the selected path;
- encoder emits only the versioned allowlisted wire keys, omits raw test tags,
  enforces 50 KiB by UTF-8 bytes, records truncation, and preserves the selected
  path before unselected geometry;
- Point reselection changes the selected id/path + component highlight atomically;
  manual drag stores `node_id = null`;
- preseeded capture/token is single-use and safe under stale token, cancellation,
  rotation, and concurrent open;
- screenshot consent removes PNG, tree, selection, and highlights together;
- Point parser and report bundle accept the versioned emitted schema.

### Backend source-resolution tests

- build id resolves only to an immutable allowlisted repository/revision manifest;
- exact relative path + valid line produces a commit-pinned link;
- basename-only unique match is labeled best-effort, duplicate basename is
  ambiguous, and neither silently becomes exact;
- unknown/dirty/unregistered build, missing source, missing/expired mapping, digest
  mismatch, invalid line, absolute path, and traversal yield explicit unavailable
  reasons without falling back to another revision;
- integer group keys resolve only through the exact build's Compose/R8 mapping;
  arbitrary key strings and mapping contents never enter the report/UI;
- delayed mapping upload may re-resolve against the same manifest with an audit
  event, but cannot alter the build revision;
- report/repository authorization controls source links and candidate disclosure;
- deleting screenshot consent data removes node source hints and derived component
  resolution while retaining only ordinary report-level build identity.

### Shared Compose integration tests

- product subcomposition root + descendant product subcomposition registration,
  with the host root and tooling siblings absent;
- bounds/source extraction from a small known composable;
- source collection starts disabled, explicit activation forces/awaits a frame,
  and name/file/line appear only afterward;
- parameter objects remain unread/unserialized despite runtime allocation;
- the inspection registry is cleared when the host disposes;
- Back/Escape, background, disposal, and configuration change always end touch
  interception and restore a valid host state.

### Dayfold tests

- developer build supplies the configured capability/mode to drawer and SWIP;
- a developer build with Components but no provider shows enablement help without
  crashing or invoking tooling;
- selected node + ancestor path ride the screenshot toggle;
- consent expansion describes component metadata and the toggle removes PNG,
  tree, selected id, and highlight together;
- leak fixture salts visible card text, member data, descriptions, and action
  parameters and asserts none enter `UI_TREE`;
- release registers no Components/help UI and its dependency graph/APK contains
  no real drawer, components, inspector tooling, or SWIP reporter/debug artifacts;
- public release scan proves complete feature/dependency absence; future
  `internalMinified` mapping verification is not a v1 completion gate.

### Portfolio consumer tests

- the shared repository publishes a minimal external-consumer fixture for core,
  noop, redux, SWIP, Components, and the optional Compose inspector without
  relying on a Dayfold source tree;
- Dayfold and Dinners resolve the same base artifact coordinates and do not carry
  embedded/source-copied drawer modules;
- setup snippets and availability copy contain no Dayfold/PickedPlate package,
  module, backend, or build-variant assumptions;
- each app's public release proves the real drawer and optional tooling absent.

### Mandatory device smoke

- component outline aligns with a tapped card/button under edge-to-edge insets;
- an animated/recomposing target proves the PNG and component tree are a
  capture-consistent pair;
- the captured PNG/tree contain no drawer, bubble/edge tab, instruction strip,
  selection label, outline, or tooling nodes;
- nested selection + ancestor stepping;
- report preview contains tree + selected component details;
- `FLAG_SECURE` behavior remains correct when SWIP detail values are revealed;
- configuration change and process restart do not leave selection interception
  active;
- dialogs/popups are either correctly excluded or clearly labeled unsupported;
- Android Back, TalkBack at 200% text, OS reduced motion, and high-contrast
  selection over light/dark/noisy content pass.

## 8. Definition of done

- Dayfold consumes published shared drawer artifacts; embedded modules are gone.
- One shared component tree powers both drawer viewing and SWIP Point annotation.
- A user can tap a component, inspect its hierarchy/source, and attach it to a
  report.
- Missing or disabled capability and failed bounds show reason-specific recovery;
  `NoTreeForCurrentScreen` shows no Retry/setup. All preserve **Report without
  component details**. Source-only degradation remains a ready, non-error state
  with bounds picking enabled.
- The report tree passes the product leak test and carries no UI text/values.
- Debug source attribution works after explicit tooling activation where Compose
  supplies it; missing source is handled honestly.
- Submitted component metadata joins to an immutable build manifest and produces
  exact, best-effort, ambiguous, or unavailable authoring-code resolution without
  linking to a different revision. Raw mappings and absolute paths never enter the
  report.
- Public release contains no viewer, SWIP report path, debug source strings, or
  tooling dependencies; future obfuscation behavior remains explicitly gated.
- Debug, release, desktop tests, Android debug/release builds, and the physical
  device smoke are green.

## 9. Explicit non-goals

- shipping the drawer/component viewer to public release builds;
- sending composable parameters, state, text, or accessibility descriptions;
- reflection into private Android Compose view/node implementations;
- preserving source-info strings through R8 with keep rules;
- treating basename + line or latest-branch lookup as an exact source link;
- uploading source code, local working trees, absolute paths, or R8/Compose
  mapping contents in a report;
- a general IDE/editor integration or automatic code modification from a report;
- iOS component capture in v1;
- a separate Dayfold-only inspector implementation.
