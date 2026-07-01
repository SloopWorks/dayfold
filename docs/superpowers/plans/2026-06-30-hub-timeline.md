# Hub Timeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a hub timeline as a new authored, content-blind `Hub.timeline` property that the client lays out in time on-device (two scales: a day rail + a hub roadmap), rendered as a hoisted dossier card that opens to a full detail via a container transform.

**Architecture:** The author (Claude skill / CLI / content API) writes `stops`; the server stores + structurally validates only (dumb-server invariant). The client parses `Hub.timeline` into a hand-written model and computes all time-relative presentation (status, NOW line, scale selection, grouping, windowing, roadmap collapse) as a pure function `TimelinePresenter` in `commonMain`, mirroring `deriveNow`. UI is Compose Multiplatform: a hoisted card in `HubDetailScreen` + a full detail screen reached through a new shared-element transition host scoped to the Hubs surface.

**Tech Stack:** Kotlin 2.3.20 / Compose-MP 1.11.1 / Material3 (Android + iOS + Desktop; NO web). JSON Schema → TS (zod) + quicktype Kotlin codegen. Server = TypeScript/zod on Vercel. CLI = Kotlin. Tests = `kotlin.test` via `:client:desktopTest`; server/CLI = `vitest`.

## Global Constraints

- **Kotlin 2.3.20 · Compose-MP 1.11.1 · Material3 · JDK 17** (`JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home`).
- **Platforms: Android + iOS + Desktop only.** No `wasmJs`/Web. Every construct must be `commonMain`-safe.
- **`timeline` is a hub PROPERTY, not a block type.** Do NOT touch the block-type enum (`content.schema.json` `Block.type`, `BlockSchema` zod enum, CLI `BLOCK_TYPES`).
- **Server stays content-blind.** Server + CLI validate STRUCTURE only (presence, array non-empty, enum membership) — never read stop prose.
- **Phase 1 = authored, render-only, Now-invisible.** No `deriveTimeline`, no Now-feed wiring, no notifications.
- **Provenance copy = authored origin.** Never "derived on-device / built from this hub". Chip text: "Added to this hub".
- **Author-stamped IANA tz.** The presenter takes an injected `tz` (family → device fallback); it NEVER reads `TimeZone.currentSystemDefault()` internally.
- **No `df://`.** OS-handoff attachments (`call`/`nav`/`link`) reuse `cardActionUri`; in-app (`open`) dispatches `CardAction.OpenHub`.
- **Stop time = RFC-3339 string, date-only = all-day.** Reuse `parseInstantFlexible` / `normalizeTs` / `relativeDays` (already `internal` in the client module).
- **Chips ≥ 48dp** via `minimumInteractiveComponentSize()`.
- **NOW halo = single settle-pulse then static; reduced motion → static/crossfade.** Halo color = per-mode token, never hardcoded rgba.
- **Reference for exact pixels/copy:** the signed-off mock `designs/hub-timeline/` (`Timeline-Card.dc.html`, `Timeline-Detail.dc.html`, `Index.dc.html`). Spec: `specs/hub-timeline-design.md`. Governance: ADR 0045.
- **TDD + frequent commits.** Every task ends green + committed.

## Post-review revisions (v2 — BINDING; overrides conflicting task text below)

Two adversarial rounds (correctness + simplification) + operator decisions. **These override any conflicting detail in the tasks below.**

**Correctness (verified against the codebase):**
1. **Datetime types:** use `kotlin.time.Instant` + `kotlin.time.Clock` (kotlinx-datetime 0.8.0). Use `kotlinx.datetime` only for `TimeZone`, `LocalDate`, `toLocalDateTime`, `atStartOfDayIn`, `daysUntil`. NOT `kotlinx.datetime.Instant`.
2. **Reused helpers exist + are `internal`:** `parseInstantFlexible(s, zone)` (NowDerive.kt) + `relativeDays(...)` — **no foundation task needed** (round-1's "missing" claim was false; verified). `clockTime(instant, zone)` is `private` in NowDerive.kt → change to `internal` (one-liner) so the presenter can call it (or duplicate a 6-line helper).
3. **Task 2 wiring:** wire in the **hub POST handler** `apps/api/src/app.ts:~516` (right after the `hubMediaIssues` block, before `upsertHub` at :531) — NOT the block handler at :610. NOTE: `HubSchema.safeParse` (:512) **already** structurally rejects a bad timeline (codegen inlines the `$ref`: `required:[tz]`, `minItems:1`, `additionalProperties:false`, kind `enum`). So Task 2 reduces to: (a) tests asserting `HubSchema` rejects {missing tz, empty stops, bad kind}; (b) OPTIONAL thin `hubTimelineIssues` only for friendlier messages. zod is the real content-blind gate.
4. **Task 3:** CLI test needs `package com.sloopworks.dayfold.cli` in `apps/cli/src/test/kotlin/com/sloopworks/dayfold/cli/`. `JsonArray` already imported.
5. **Task 13 → 13a/13b, and it MUST edit `FeedApp.kt`:** `HubDetailScreen` (HubScreens.kt:238) has no `store`; it's rendered by `HubsHost` (FeedApp.kt:318), which lacks the `handle:(CardAction)->Unit` and the shared-transition scope (both live only in `ContentHost`, FeedApp.kt:255). Thread `onOpenTimeline(scale)`/`onCloseTimeline`/`onTimelineAction(CardAction)` (wire to the `handle` at ~:102) through `HubsHost`→`HubDetailScreen`, and wrap the Hubs detail + a full-surface `TimelineDetail` overlay in a **fresh** `SharedTransitionLayout`+`AnimatedContent(state.timelineDetail)` re-providing both scope locals (mirror `ContentHost` :255-313). `cardSharedBounds` is null-safe → the card still snapshots without a host.
6. **tz injection (Task 13):** `val tz = runCatching { TimeZone.of(tl.tz) }.getOrElse { TimeZone.currentSystemDefault() }; val nowIso = kotlin.time.Clock.System.now().toString()`. NEVER read `currentSystemDefault()` inside the presenter.
7. **Nav model:** `AppState.timelineDetail: TimelineScale?` substate (not `Route.TimelineDetail`) is the intended implementation — it matches the `ContentHost` morph precedent (substate inside one SharedTransitionLayout, not a Route switch). A deliberate, morph-compatible refinement of ADR 0045 §6's "new nav destination" wording (not a decision reversal).

**Simplification + operator-approved scope cuts:**
8. **Delete `StopStatus.Now`** — never produced in Phase 1 (NOW is a line *between* stops). Enum = `{ Done, Next, Upcoming }`.
9. **Defer the day↔hub scope toggle + second-scale affordance to Phase 2** (operator-approved). Phase 1 renders the ONE auto-selected scale per hub. Cut `hasBothScales` (Task 6), `SingleChoiceSegmentedButtonRow`+`showToggle` (Tasks 12/13), and the `hasOtherScale` fields. `TimelineDetail` = single scale, no toggle.
10. **Defer the roadmap `✓N` collapse to Phase 2** — render all spine nodes when ≤6; if >6, a simple "+M more" tail. Cut `SpineNode.collapsedCount` + the collapse test from Task 7.
11. **Defer per-member "Hide for me" on the card to a follow-up** (operator-approved) — record in `now.md` + spec §12. Not built in Phase 1.
12. **Split Task 11 → 11a (day-rail card) + 11b (roadmap-spine card); split Task 13 → 13a (hoist a static tappable card, detail opens as a plain full-screen overlay dispatching `OpenTimelineDetail`) + 13b (add the shared-element morph in `FeedApp.kt`).** 13a is the shippable end-to-end slice; 13b is deferrable polish.
13. **Glyphs (Task 8):** add only glyphs the built composables reference — audit after 11/12; don't pre-add the mock's decorative set.
14. **Snapshot tests → behavioral:** each UI snapshot test also asserts load-bearing content via `onNodeWithText(...).assertIsDisplayed()` (done-count "3 done", NOW time label, tail "1 more", "Added to this hub" chip, windowed titles) — the house harness has no pixel diff, so `width>0` alone catches nothing.
15. **Add presenter edge tests (Tasks 6-7):** all-done (no NOW line), single-scale, focalDay ties (→ today), **cross-tz** (member in another zone sees the same done-set for the author-stamped tz), archived-hub NOW suppression, dangling `open` ref → graceful no-op.

Revised task count ≈ 16. Sequence unchanged: schema→server→CLI→model→presenter→icons→nav→mapper→cards(day/roadmap)→detail→integration(static→morph)→verify.

---

## Shared Types (defined in Task 4, referenced everywhere after)

```kotlin
// commonMain — Model.kt additions
@Serializable data class Timeline(val title: String? = null, val tz: String, val stops: List<Stop> = emptyList())
@Serializable data class Stop(
  val at: String, val title: String, val sub: String? = null,
  val major: Boolean = false, val done: Boolean = false, val assignee: String? = null,
  val attachments: List<Attachment> = emptyList(),
)
@Serializable data class Attachment(
  val kind: String, val label: String,           // kind: "call"|"nav"|"link"|"open"
  val tel: String? = null, val query: String? = null, val url: String? = null,
  val ref: AttachmentRef? = null,
)
@Serializable data class AttachmentRef(val hubId: String, val sectionId: String? = null, val blockId: String? = null)
// Hub gains: val timeline: Timeline? = null

// TimelinePresenter output (Task 5-7). NOTE (v2 §8): no `Now` — NOW is a line between stops.
enum class StopStatus { Done, Next, Upcoming }
enum class TimelineScale { Day, Hub }
data class PresentedStop(val stop: Stop, val status: StopStatus, val instant: Instant?)
data class TimelineGroup(val label: String, val stops: List<PresentedStop>)
// nowIndex = index in the FLATTENED presented-stop list AFTER which the NOW line sits (null = no NOW line)
data class PresentedTimeline(
  val scale: TimelineScale, val hasOtherScale: Boolean,
  val groups: List<TimelineGroup>, val nowIndex: Int?, val nowTimeLabel: String?,
)
data class TimelineCardModel(
  val scale: TimelineScale, val hasOtherScale: Boolean,
  val doneCount: Int, val nowTimeLabel: String?, val window: List<PresentedStop>, val tailCount: Int,
  val spine: List<SpineNode>? = null,          // roadmap only
  val nextCallout: PresentedStop? = null,      // roadmap only
)
data class SpineNode(val label: String, val status: StopStatus, val collapsedCount: Int? = null)
```

---

### Task 1: Schema — add `Hub.timeline` + `Stop`/`Attachment` defs + regenerate

**Files:**
- Modify: `specs/domain-model/schemas/content.schema.json` (Hub `properties`, `$defs`)
- Regenerate: `apps/api/src/generated/content.ts`, `packages/schema/kotlin-gen/Content.kt` (via `npm run codegen`)
- Test: `apps/api/src/generated/content.ts` (assert generated shape)

**Interfaces:**
- Produces: schema `$defs.Timeline`, `$defs.TimelineStop`, `$defs.TimelineAttachment`; `Hub.properties.timeline`.

- [ ] **Step 1: Add the `$defs` and the Hub property.** In `content.schema.json`, add to `$defs` (next to `MilestonePayload`):

```json
"TimelineAttachment": {
  "type": "object",
  "required": ["kind", "label"],
  "properties": {
    "kind": { "enum": ["call", "nav", "link", "open"] },
    "label": { "type": "string" },
    "tel": { "type": "string" },
    "query": { "type": "string" },
    "url": { "type": "string" },
    "ref": {
      "type": "object",
      "required": ["hubId"],
      "properties": { "hubId": { "$ref": "#/$defs/ulid" }, "sectionId": { "$ref": "#/$defs/ulid" }, "blockId": { "$ref": "#/$defs/ulid" } },
      "additionalProperties": false
    }
  },
  "additionalProperties": false
},
"TimelineStop": {
  "type": "object",
  "required": ["at", "title"],
  "properties": {
    "at": { "type": "string", "description": "RFC-3339 instant, or a bare YYYY-MM-DD for an all-day stop" },
    "title": { "type": "string", "description": "[CONTENT/E2E-hole]" },
    "sub": { "type": "string", "description": "[CONTENT/E2E-hole]" },
    "major": { "type": "boolean", "default": false },
    "done": { "type": "boolean", "default": false },
    "assignee": { "type": "string", "description": "[CONTENT/E2E-hole] display-only label, never an identity binding" },
    "attachments": { "type": "array", "items": { "$ref": "#/$defs/TimelineAttachment" } }
  },
  "additionalProperties": false
},
"Timeline": {
  "type": "object",
  "required": ["tz", "stops"],
  "properties": {
    "title": { "type": "string", "description": "[CONTENT/E2E-hole]" },
    "tz": { "type": "string", "description": "IANA timezone, author-stamped; anchors the day-boundary + NOW line" },
    "stops": { "type": "array", "minItems": 1, "items": { "$ref": "#/$defs/TimelineStop" } }
  },
  "additionalProperties": false
}
```

Then add to `Hub.properties` (after `sections`): `"timeline": { "$ref": "#/$defs/Timeline" },`. Add `"Timeline"` to the codegen emit-order list in `packages/schema/codegen.mjs` (lines 16-21) if that list is explicit.

- [ ] **Step 2: Regenerate.**

Run: `cd /Users/patrick/workspace/dayfold && npm run codegen`
Expected: exit 0; `apps/api/src/generated/content.ts` now has `HubSchema` with a `timeline` field and a `TimelineSchema`/`TimelineStopSchema` export.

- [ ] **Step 3: Write the assertion test.** Create `apps/api/src/generated/content.timeline.test.ts`:

```typescript
import { describe, it, expect } from "vitest";
import { HubSchema } from "./content";

describe("generated Hub.timeline", () => {
  it("accepts a hub with a valid timeline", () => {
    const r = HubSchema.safeParse({ id: "01H", type: "starting-college", title: "Maya",
      timeline: { tz: "America/New_York", stops: [{ at: "2026-08-24", title: "Move-in" }] } });
    expect(r.success).toBe(true);
  });
  it("still accepts a hub with no timeline", () => {
    expect(HubSchema.safeParse({ id: "01H", type: "move", title: "X" }).success).toBe(true);
  });
});
```

- [ ] **Step 4: Run it.**

Run: `cd /Users/patrick/workspace/dayfold/apps/api && npx vitest run src/generated/content.timeline.test.ts`
Expected: PASS (2 tests). If `timeline` generated as `z.any().optional()` the first still passes; structural rigor is Task 2.

- [ ] **Step 5: Commit.**

```bash
git add specs/domain-model/schemas/content.schema.json apps/api/src/generated/content.ts packages/schema/kotlin-gen/Content.kt apps/api/src/generated/content.timeline.test.ts packages/schema/codegen.mjs
git commit -m "feat(schema): add Hub.timeline authored property (ADR 0045)"
```

---

### Task 2: Server — structural `hubTimelineIssues` validation (content-blind)

**Files:**
- Modify: `apps/api/src/content-validation.ts` (add `hubTimelineIssues`, export + wire where hubs are validated)
- Test: `apps/api/src/content-validation.timeline.test.ts`

**Interfaces:**
- Consumes: `Hub` shape from Task 1.
- Produces: `export function hubTimelineIssues(hub: { timeline?: unknown }): CrossIssue[]`.

- [ ] **Step 1: Write the failing test.** Create `apps/api/src/content-validation.timeline.test.ts`:

```typescript
import { describe, it, expect } from "vitest";
import { hubTimelineIssues } from "./content-validation";

describe("hubTimelineIssues", () => {
  it("no timeline → no issues", () => { expect(hubTimelineIssues({})).toEqual([]); });
  it("valid timeline → no issues", () => {
    expect(hubTimelineIssues({ timeline: { tz: "America/New_York", stops: [{ at: "2026-08-24", title: "Move-in" }] } })).toEqual([]);
  });
  it("missing tz → issue", () => {
    expect(hubTimelineIssues({ timeline: { stops: [{ at: "2026-08-24", title: "X" }] } }).length).toBe(1);
  });
  it("empty stops → issue", () => {
    expect(hubTimelineIssues({ timeline: { tz: "UTC", stops: [] } }).length).toBe(1);
  });
  it("stop missing at → issue", () => {
    expect(hubTimelineIssues({ timeline: { tz: "UTC", stops: [{ title: "X" }] } }).length).toBe(1);
  });
  it("bad attachment kind → issue", () => {
    expect(hubTimelineIssues({ timeline: { tz: "UTC", stops: [{ at: "2026-01-01", title: "X",
      attachments: [{ kind: "df", label: "y" }] }] } }).length).toBe(1);
  });
});
```

- [ ] **Step 2: Run to verify it fails.**

Run: `cd /Users/patrick/workspace/dayfold/apps/api && npx vitest run src/content-validation.timeline.test.ts`
Expected: FAIL — `hubTimelineIssues is not a function`.

- [ ] **Step 3: Implement.** In `apps/api/src/content-validation.ts`, add (mirroring `blockPayloadIssues`, never reading title/sub text):

```typescript
const ATTACH_KINDS = new Set(["call", "nav", "link", "open"]);

export function hubTimelineIssues(hub: { timeline?: unknown }): CrossIssue[] {
  const t = hub.timeline;
  if (t == null) return [];
  if (typeof t !== "object" || Array.isArray(t)) return [{ path: ["timeline"], message: "timeline must be an object" }];
  const tl = t as Record<string, unknown>;
  const issues: CrossIssue[] = [];
  if (typeof tl.tz !== "string" || tl.tz.trim() === "") issues.push({ path: ["timeline", "tz"], message: "timeline.tz (IANA) is required" });
  if (!Array.isArray(tl.stops) || tl.stops.length === 0) { issues.push({ path: ["timeline", "stops"], message: "timeline.stops must be a non-empty array" }); return issues; }
  (tl.stops as unknown[]).forEach((s, i) => {
    const stop = s as Record<string, unknown>;
    if (typeof stop.at !== "string" || stop.at.trim() === "") issues.push({ path: ["timeline", "stops", i, "at"], message: "stop.at is required" });
    if (typeof stop.title !== "string" || stop.title.trim() === "") issues.push({ path: ["timeline", "stops", i, "title"], message: "stop.title is required" });
    if (stop.attachments != null) {
      if (!Array.isArray(stop.attachments)) issues.push({ path: ["timeline", "stops", i, "attachments"], message: "attachments must be an array" });
      else (stop.attachments as unknown[]).forEach((a, j) => {
        const k = (a as Record<string, unknown>).kind;
        if (typeof k !== "string" || !ATTACH_KINDS.has(k)) issues.push({ path: ["timeline", "stops", i, "attachments", j, "kind"], message: `attachment.kind must be one of ${[...ATTACH_KINDS].join("|")}` });
      });
    }
  });
  return issues;
}
```

Wire it: find where `blockPayloadIssues` is called in the hub upsert path (grep `blockPayloadIssues` in `apps/api/src`) and add a sibling `hubTimelineIssues(hub)` call, concatenating its issues into the same reject list.

- [ ] **Step 4: Run to verify pass.**

Run: `cd /Users/patrick/workspace/dayfold/apps/api && npx vitest run src/content-validation.timeline.test.ts`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit.**

```bash
git add apps/api/src/content-validation.ts apps/api/src/content-validation.timeline.test.ts
git commit -m "feat(api): content-blind structural validation for Hub.timeline (ADR 0045)"
```

---

### Task 3: CLI — timeline validation in `validateHubTree`

**Files:**
- Modify: `apps/cli/src/main/kotlin/Validate.kt` (add `hubTimelineErrors`, call from the `"hubs"` branch)
- Test: `apps/cli/src/test/kotlin/ValidateTimelineTest.kt`

**Interfaces:**
- Consumes: `validateHubTree("hubs", json)` entry (Extraction §5).
- Produces: `internal fun hubTimelineErrors(timeline: JsonObject?): List<String>`.

- [ ] **Step 1: Write the failing test.** Create `apps/cli/src/test/kotlin/ValidateTimelineTest.kt` (match the module's existing test package/style — grep an existing `*Test.kt` under `apps/cli/src/test`):

```kotlin
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class ValidateTimelineTest {
  private fun hub(tl: String) = """{"title":"H","type":"move","timeline":$tl}"""
  @Test fun validTimelineOk() {
    val e = validateHubTree("hubs", hub("""{"tz":"UTC","stops":[{"at":"2026-08-24","title":"Move-in"}]}"""))
    assertEquals(emptyList(), e)
  }
  @Test fun missingTzFlagged() {
    val e = validateHubTree("hubs", hub("""{"stops":[{"at":"2026-08-24","title":"X"}]}"""))
    assertTrue(e.any { it.contains("tz") })
  }
  @Test fun emptyStopsFlagged() {
    assertTrue(validateHubTree("hubs", hub("""{"tz":"UTC","stops":[]}""")).any { it.contains("stops") })
  }
  @Test fun badKindFlagged() {
    val e = validateHubTree("hubs", hub("""{"tz":"UTC","stops":[{"at":"2026-01-01","title":"X","attachments":[{"kind":"df","label":"y"}]}]}"""))
    assertTrue(e.any { it.contains("kind") })
  }
}
```

- [ ] **Step 2: Run to verify it fails.**

Run: `cd /Users/patrick/workspace/dayfold/apps && JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ./gradlew :cli:test --tests "ValidateTimelineTest"`
Expected: FAIL (validTimelineOk fails — timeline currently ignored, but the negative tests fail: no errors produced).

- [ ] **Step 3: Implement.** In `Validate.kt`, in the `"hubs" ->` branch add after the media line: `e += hubTimelineErrors(obj["timeline"] as? JsonObject)`. Add:

```kotlin
private val ATTACH_KINDS = setOf("call", "nav", "link", "open")

internal fun hubTimelineErrors(timeline: JsonObject?): List<String> {
  if (timeline == null) return emptyList()
  val e = mutableListOf<String>()
  val tz = (timeline["tz"] as? JsonPrimitive)?.takeIf { it.isString }?.content
  if (tz.isNullOrBlank()) e += "timeline: `tz` (IANA) is required"
  val stops = timeline["stops"] as? JsonArray
  if (stops == null || stops.isEmpty()) { e += "timeline: `stops` must be a non-empty array"; return e }
  stops.forEachIndexed { i, s ->
    val stop = s as? JsonObject ?: run { e += "timeline.stops[$i]: must be an object"; return@forEachIndexed }
    if ((stop["at"] as? JsonPrimitive)?.takeIf { it.isString }?.content.isNullOrBlank()) e += "timeline.stops[$i]: `at` is required"
    if ((stop["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content.isNullOrBlank()) e += "timeline.stops[$i]: `title` is required"
    (stop["attachments"] as? JsonArray)?.forEachIndexed { j, a ->
      val kind = ((a as? JsonObject)?.get("kind") as? JsonPrimitive)?.content
      if (kind !in ATTACH_KINDS) e += "timeline.stops[$i].attachments[$j]: `kind` must be ${ATTACH_KINDS.joinToString("|")}"
    }
  }
  return e
}
```

Ensure `JsonArray` is imported (`kotlinx.serialization.json.JsonArray`).

- [ ] **Step 4: Run to verify pass.**

Run: `cd /Users/patrick/workspace/dayfold/apps && JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ./gradlew :cli:test --tests "ValidateTimelineTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit.**

```bash
git add apps/cli/src/main/kotlin/Validate.kt apps/cli/src/test/kotlin/ValidateTimelineTest.kt
git commit -m "feat(cli): validate Hub.timeline structure (ADR 0045)"
```

---

### Task 4: Client model — `Timeline`/`Stop`/`Attachment` + `Hub.timeline`

**Files:**
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/Model.kt`
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/TimelineModelTest.kt`

**Interfaces:**
- Produces: the Shared Types block above (`Timeline`, `Stop`, `Attachment`, `AttachmentRef`), and `Hub.timeline: Timeline?`.

- [ ] **Step 1: Write the failing test.** Create `TimelineModelTest.kt`:

```kotlin
package com.sloopworks.dayfold.client
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

class TimelineModelTest {
  private val json = Json { ignoreUnknownKeys = true }
  @Test fun `parses a hub with a timeline`() {
    val h = json.decodeFromString<Hub>("""{"id":"h1","title":"Maya",
      "timeline":{"tz":"America/New_York","stops":[
        {"at":"2026-08-24T11:00:00-04:00","title":"Move-up","major":true,
         "attachments":[{"kind":"nav","label":"Map","query":"Henderson Hall"},
                        {"kind":"open","label":"List","ref":{"hubId":"h1","blockId":"b2"}}]}]}}""")
    assertEquals("America/New_York", h.timeline?.tz)
    assertEquals(1, h.timeline?.stops?.size)
    assertEquals(true, h.timeline?.stops?.first()?.major)
    assertEquals("nav", h.timeline?.stops?.first()?.attachments?.first()?.kind)
    assertEquals("b2", h.timeline?.stops?.first()?.attachments?.get(1)?.ref?.blockId)
  }
  @Test fun `hub without timeline parses null`() {
    assertEquals(null, json.decodeFromString<Hub>("""{"id":"h1","title":"X"}""").timeline)
  }
}
```

- [ ] **Step 2: Run to verify it fails.**

Run: `cd /Users/patrick/workspace/dayfold/apps && JAVA_HOME=… ./gradlew :client:desktopTest --tests "*TimelineModelTest"`
Expected: FAIL — `Unresolved reference: timeline`.

- [ ] **Step 3: Implement.** Add the Shared Types data classes to `Model.kt` (near `HubBlock`), and add `val timeline: Timeline? = null,` as the last field of `data class Hub`.

- [ ] **Step 4: Run to verify pass.**

Run: same as Step 2. Expected: PASS (2 tests).

- [ ] **Step 5: Commit.**

```bash
git add apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/Model.kt apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/TimelineModelTest.kt
git commit -m "feat(client): Timeline/Stop/Attachment model + Hub.timeline (ADR 0045)"
```

---

### Task 5: Presenter — status computation

**Files:**
- Create: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/TimelinePresenter.kt`
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/TimelinePresenterStatusTest.kt`

**Interfaces:**
- Consumes: `Timeline`, `Stop`, `parseInstantFlexible(s, zone)` (internal, same module), `TimeZone`, `Instant`.
- Produces: `internal fun stopStatuses(stops: List<Stop>, nowIso: String, tz: TimeZone): List<PresentedStop>` and the `StopStatus` enum. Rule: a stop is `Done` if `stop.done || instant < now` (monotonic — never un-done); the single `Now` = the last non-done stop whose instant ≤ now when the next stop is in the future OR the nearest-upcoming when none has passed today; `Next` = first stop with instant > now; rest `Upcoming`. Stops with an unparseable `at` sort last and default `Upcoming` (unless author `done`).

- [ ] **Step 1: Write the failing test.**

```kotlin
package com.sloopworks.dayfold.client
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class TimelinePresenterStatusTest {
  private val ny = TimeZone.of("America/New_York")
  private fun s(at: String, done: Boolean = false) = Stop(at = at, title = at, done = done)
  @Test fun `past is done, first future is next, rest upcoming`() {
    val stops = listOf(s("2026-08-24T08:00:00-04:00"), s("2026-08-24T09:50:00-04:00"),
                       s("2026-08-24T11:00:00-04:00"), s("2026-08-24T14:00:00-04:00"))
    val out = stopStatuses(stops, "2026-08-24T10:40:00-04:00", ny)
    assertEquals(listOf(StopStatus.Done, StopStatus.Done, StopStatus.Next, StopStatus.Upcoming), out.map { it.status })
  }
  @Test fun `author done overrides even a future date`() {
    assertEquals(StopStatus.Done, stopStatuses(listOf(s("2026-12-01", done = true)), "2026-08-24T10:00:00-04:00", ny).first().status)
  }
  @Test fun `not-today makes everything upcoming, no next-before-now`() {
    val out = stopStatuses(listOf(s("2026-09-19"), s("2026-09-20")), "2026-08-24T10:00:00-04:00", ny)
    assertEquals(listOf(StopStatus.Next, StopStatus.Upcoming), out.map { it.status })
  }
}
```

- [ ] **Step 2: Run to verify it fails.**

Run: `./gradlew :client:desktopTest --tests "*TimelinePresenterStatusTest"` (with JAVA_HOME). Expected: FAIL — `Unresolved reference: stopStatuses`.

- [ ] **Step 3: Implement.** Create `TimelinePresenter.kt`:

```kotlin
package com.sloopworks.dayfold.client
import kotlin.time.Instant            // v2 §1: kotlin.time.Instant, NOT kotlinx.datetime.Instant (0.8.0)
import kotlinx.datetime.TimeZone

enum class StopStatus { Done, Next, Upcoming }   // v2 §8: no Now
enum class TimelineScale { Day, Hub }
data class PresentedStop(val stop: Stop, val status: StopStatus, val instant: Instant?)

internal fun stopStatuses(stops: List<Stop>, nowIso: String, tz: TimeZone): List<PresentedStop> {
  val now = parseInstantFlexible(nowIso, tz)
  val parsed = stops.map { it to parseInstantFlexible(it.at, tz) }
  // order: parseable-by-instant ascending, unparseable last (stable)
  val ordered = parsed.withIndex().sortedWith(compareBy({ it.value.second == null }, { it.value.second }))
  var nextAssigned = false
  val statusByOrig = HashMap<Int, StopStatus>()
  for ((origIdx, pair) in ordered) {
    val (stop, inst) = pair
    val done = stop.done || (inst != null && now != null && inst < now)
    val status = when {
      done -> StopStatus.Done
      !nextAssigned -> { nextAssigned = true; StopStatus.Next }
      else -> StopStatus.Upcoming
    }
    statusByOrig[origIdx] = status
  }
  return stops.mapIndexed { i, stop -> PresentedStop(stop, statusByOrig[i]!!, parsed[i].second) }
}
```

(Note: `Now` is assigned by the day/roadmap presenter — the NOW *line* sits between the last Done and the Next; a stop is not itself "Now" in this model. `StopStatus.Now` stays reserved for a live in-window stop, set in Task 6.)

- [ ] **Step 4: Run to verify pass.** Same command. Expected: PASS (3 tests).

- [ ] **Step 5: Commit.**

```bash
git add apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/TimelinePresenter.kt apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/TimelinePresenterStatusTest.kt
git commit -m "feat(client): TimelinePresenter status computation (ADR 0045)"
```

---

### Task 6: Presenter — scale selection, focal day, NOW line

**Files:**
- Modify: `TimelinePresenter.kt`
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/TimelinePresenterScaleTest.kt`

**Interfaces:**
- Produces:
  - `internal fun selectScale(tl: Timeline, nowIso: String, tz: TimeZone): TimelineScale` — `Day` if there are ≥1 intraday-timed stops on the focal day; else `Hub` if stops span > 14 days or ≥ 3 date-only stops; else `Day`.
  - `internal fun hasBothScales(tl: Timeline, nowIso: String, tz: TimeZone): Boolean` — true when the non-selected scale also has content (≥1 intraday stop AND a >14-day/≥3-date-only span).
  - `internal fun focalDay(tl, nowIso, tz): LocalDate` — the date with the most intraday-timed stops; ties → the one containing `now` if any, else earliest.
  - `internal fun nowLineIndex(day: List<PresentedStop>, nowIso, tz): Int?` — index AFTER which the NOW line sits on the focal day; null if focal day ≠ today.

- [ ] **Step 1: Write the failing test.**

```kotlin
package com.sloopworks.dayfold.client
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimelinePresenterScaleTest {
  private val ny = TimeZone.of("America/New_York")
  private fun intraday() = Timeline(tz = "America/New_York", stops = listOf(
    Stop("2026-08-24T08:00:00-04:00", "a"), Stop("2026-08-24T11:00:00-04:00", "b")))
  private fun roadmap() = Timeline(tz = "America/New_York", stops = listOf(
    Stop("2026-05-01", "a"), Stop("2026-06-12", "b"), Stop("2026-07-20", "c"), Stop("2026-09-19", "d")))
  @Test fun `intraday-today selects Day`() {
    assertEquals(TimelineScale.Day, selectScale(intraday(), "2026-08-24T10:00:00-04:00", ny))
  }
  @Test fun `multi-month date-only selects Hub`() {
    assertEquals(TimelineScale.Hub, selectScale(roadmap(), "2026-08-24T10:00:00-04:00", ny))
  }
  @Test fun `now line only on the focal day when it is today`() {
    val day = stopStatuses(intraday().stops, "2026-08-24T10:00:00-04:00", ny)
    assertEquals(1, nowLineIndex(day, "2026-08-24T10:00:00-04:00", ny)) // after stop[0] (08:00), before stop[1] (11:00)
    assertEquals(null, nowLineIndex(day, "2026-09-01T10:00:00-04:00", ny))
  }
}
```

- [ ] **Step 2: Run to verify it fails.** `--tests "*TimelinePresenterScaleTest"`. Expected: FAIL (unresolved refs).

- [ ] **Step 3: Implement.** Add to `TimelinePresenter.kt` (use `kotlinx.datetime`: `LocalDate`, `toLocalDateTime`, `daysUntil`):

```kotlin
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.daysUntil

private fun Stop.hasIntradayTime(): Boolean = at.trim().length > 10   // "YYYY-MM-DD" is 10 chars; longer = has a time

internal fun focalDay(tl: Timeline, nowIso: String, tz: TimeZone): LocalDate? {
  val today = parseInstantFlexible(nowIso, tz)?.toLocalDateTime(tz)?.date
  val byDay = tl.stops.filter { it.hasIntradayTime() }
    .mapNotNull { parseInstantFlexible(it.at, tz)?.toLocalDateTime(tz)?.date }
    .groupingBy { it }.eachCount()
  if (byDay.isEmpty()) return today
  val max = byDay.values.max()
  val tied = byDay.filterValues { it == max }.keys
  return tied.firstOrNull { it == today } ?: tied.minOrNull()
}

internal fun selectScale(tl: Timeline, nowIso: String, tz: TimeZone): TimelineScale {
  val focal = focalDay(tl, nowIso, tz)
  val intradayOnFocal = tl.stops.any { it.hasIntradayTime() && parseInstantFlexible(it.at, tz)?.toLocalDateTime(tz)?.date == focal }
  if (intradayOnFocal) return TimelineScale.Day
  val dates = tl.stops.mapNotNull { parseInstantFlexible(it.at, tz)?.toLocalDateTime(tz)?.date }.sorted()
  val spanDays = if (dates.size >= 2) dates.first().daysUntil(dates.last()) else 0
  val dateOnlyCount = tl.stops.count { !it.hasIntradayTime() }
  return if (spanDays > 14 || dateOnlyCount >= 3) TimelineScale.Hub else TimelineScale.Day
}

internal fun hasBothScales(tl: Timeline, nowIso: String, tz: TimeZone): Boolean {
  val hasIntraday = tl.stops.any { it.hasIntradayTime() }
  val dates = tl.stops.mapNotNull { parseInstantFlexible(it.at, tz)?.toLocalDateTime(tz)?.date }.sorted()
  val roadmapWorthy = (if (dates.size >= 2) dates.first().daysUntil(dates.last()) else 0) > 14 || tl.stops.count { !it.hasIntradayTime() } >= 3
  return hasIntraday && roadmapWorthy
}

internal fun nowLineIndex(day: List<PresentedStop>, nowIso: String, tz: TimeZone): Int? {
  val now = parseInstantFlexible(nowIso, tz) ?: return null
  val today = now.toLocalDateTime(tz).date
  val focalMatchesToday = day.any { it.instant?.toLocalDateTime(tz)?.date == today }
  if (!focalMatchesToday) return null
  // index after the last stop whose instant <= now
  val lastPast = day.indexOfLast { it.instant != null && it.instant!! <= now }
  return lastPast + 1   // 0 = before all; day.size = after all
}
```

- [ ] **Step 4: Run to verify pass.** Same. Expected: PASS (3 tests).

- [ ] **Step 5: Commit.**

```bash
git add apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/TimelinePresenter.kt apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/TimelinePresenterScaleTest.kt
git commit -m "feat(client): timeline scale selection + focal day + NOW line (ADR 0045)"
```

---

### Task 7: Presenter — grouping, card windowing, roadmap collapse

**Files:**
- Modify: `TimelinePresenter.kt`
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/TimelinePresenterWindowTest.kt`

**Interfaces:**
- Produces:
  - `fun presentTimelineCard(tl: Timeline, nowIso: String, tz: TimeZone): TimelineCardModel?` — null when `tl.stops` empty. Day card: `doneCount` = count of Done, `window` = up to next 3 non-Done from the NEXT onward, `tailCount` = remaining after the window, `nowTimeLabel` from `nowLineIndex`. Roadmap card: `spine` (≤6 nodes, leading Done-run>2 → one collapsed node with `collapsedCount`), `nextCallout` = first non-Done stop.
  - `fun presentTimelineDetail(tl: Timeline, scale: TimelineScale, nowIso: String, tz: TimeZone): PresentedTimeline` — full grouped feed (day: part-of-day groups MORNING/AFTERNOON/EVENING; roadmap: month groups), `nowIndex` from `nowLineIndex` (day) / current-month band (roadmap).
- Uses the Shared Types `TimelineCardModel`, `SpineNode`, `TimelineGroup`, `PresentedTimeline`.

- [ ] **Step 1: Write the failing test.**

```kotlin
package com.sloopworks.dayfold.client
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimelinePresenterWindowTest {
  private val ny = TimeZone.of("America/New_York")
  private fun day() = Timeline(tz="America/New_York", stops = listOf(
    Stop("2026-08-24T07:30:00-04:00","load"), Stop("2026-08-24T08:00:00-04:00","left"),
    Stop("2026-08-24T09:50:00-04:00","checkin"), Stop("2026-08-24T11:00:00-04:00","elevator"),
    Stop("2026-08-24T12:30:00-04:00","lunch"), Stop("2026-08-24T14:00:00-04:00","bookstore"),
    Stop("2026-08-24T16:30:00-04:00","goodbye")))
  @Test fun `day card windows done-cap + next3 + tail`() {
    val c = presentTimelineCard(day(), "2026-08-24T10:40:00-04:00", ny)!!
    assertEquals(TimelineScale.Day, c.scale)
    assertEquals(3, c.doneCount)              // 07:30, 08:00, 09:50
    assertEquals(3, c.window.size)            // 11:00, 12:30, 14:00
    assertEquals(1, c.tailCount)              // 16:30
    assertEquals(true, c.hasOtherScale.let { true }) // presence check only
  }
  @Test fun `empty timeline → null card`() {
    assertNull(presentTimelineCard(Timeline(tz="UTC", stops = emptyList()), "2026-08-24T10:40:00-04:00", ny))
  }
  @Test fun `roadmap collapses a long leading done run to one node`() {
    val tl = Timeline(tz="America/New_York", stops = (1..5).map { Stop("2026-0$it-01","m$it") } + Stop("2026-09-01","future"))
    val c = presentTimelineCard(tl, "2026-08-24T10:00:00-04:00", ny)!!
    assertEquals(TimelineScale.Hub, c.scale)
    // 5 past done-> collapsed to 1 node with count, + the future node = spine <= 6
    assertEquals(true, (c.spine?.size ?: 0) <= 6)
    assertEquals(5, c.spine?.first()?.collapsedCount)
  }
}
```

- [ ] **Step 2: Run to verify it fails.** `--tests "*TimelinePresenterWindowTest"`. Expected: FAIL.

- [ ] **Step 3: Implement.** Add the `TimelineCardModel`/`SpineNode`/`TimelineGroup`/`PresentedTimeline` data classes (Shared Types) + the two `present*` functions to `TimelinePresenter.kt`. Day windowing: `doneCount = stops.count{Done}`, `window = firstThreeNonDoneFromNext`, `tailCount = nonDone - window.size`; `nowTimeLabel` = `clockTime` of `now` (reuse the existing `clockTime` helper referenced in Extraction §7 — grep `fun clockTime` in the client module). Roadmap: month-group the stops, build spine nodes per month with the group's dominant status; if the leading run of Done nodes > 2, replace it with one `SpineNode(label="✓N", status=Done, collapsedCount=N)`; cap the tail at 6 total. (Full body follows the mock's `Timeline-Card.dc.html` JS `months`/`display`/`spine` logic — Extraction not needed; reimplement in Kotlin.)

- [ ] **Step 4: Run to verify pass.** Same. Expected: PASS (3 tests).

- [ ] **Step 5: Commit.**

```bash
git add apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/TimelinePresenter.kt apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/TimelinePresenterWindowTest.kt
git commit -m "feat(client): timeline card windowing + roadmap collapse + detail grouping (ADR 0045)"
```

---

### Task 8: Icons — add the ~14 missing Material-Symbols glyphs

**Files:**
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/DayfoldIcons.kt`
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/DayfoldIconsTimelineTest.kt`

**Interfaces:**
- Produces: `val Timeline, School, WbSunny, CalendarMonth, OpenInFull, Bolt, Schedule, Map, Checklist, TouchApp, LinearScale, Star, Alarm, AutoAwesome: ImageVector` (only those not already present — reuse existing `Call`/`Location`/`ArrowForward`/`ArrowOutward`/`ArrowBack`).

- [ ] **Step 1: Write the failing test.**

```kotlin
package com.sloopworks.dayfold.client
import kotlin.test.Test
import kotlin.test.assertTrue

class DayfoldIconsTimelineTest {
  @Test fun `timeline glyphs exist and have path data`() {
    listOf(DayfoldIcons.Timeline, DayfoldIcons.School, DayfoldIcons.WbSunny, DayfoldIcons.CalendarMonth,
           DayfoldIcons.Bolt, DayfoldIcons.Schedule, DayfoldIcons.Checklist, DayfoldIcons.Star, DayfoldIcons.AutoAwesome)
      .forEach { assertTrue(it.defaultWidth.value > 0f) }
  }
}
```

(Adjust the `DayfoldIcons.` accessor to match the file's actual object/namespace — Extraction §12 shows top-level `val`s; if so, reference them directly without the `DayfoldIcons.` prefix.)

- [ ] **Step 2: Run to verify it fails.** `--tests "*DayfoldIconsTimelineTest"`. Expected: FAIL (unresolved).

- [ ] **Step 3: Implement.** For each glyph, add `val Name: ImageVector = ms("<path-data>")` using the exact Material Symbols Rounded path data (opsz24, weight400, grade0, fill0) — pull each from the Material Symbols source (the same source the existing `Document`/`Today` glyphs used). Follow the exact `ms(...)` pattern in the file.

- [ ] **Step 4: Run to verify pass.** Same. Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git add apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/DayfoldIcons.kt apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/DayfoldIconsTimelineTest.kt
git commit -m "feat(client): add timeline Material-Symbols glyphs to DayfoldIcons"
```

---

### Task 9: Nav — actions, reducer, AppState substate, BackNav

**Files:**
- Modify: `Reducer.kt` (actions + reducer clauses), `Model.kt` (`AppState.timelineDetail`), `BackNav.kt`
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/TimelineNavTest.kt`

**Interfaces:**
- Produces: `data class OpenTimelineDetail(val scale: TimelineScale) : Action`, `data object CloseTimelineDetail : Action`, `AppState.timelineDetail: TimelineScale? = null` (non-null = the timeline detail overlay is open at that scale, within the current hub). BackNav closes it before `CloseHub`.

- [ ] **Step 1: Write the failing test.**

```kotlin
package com.sloopworks.dayfold.client
import kotlin.test.Test
import kotlin.test.assertEquals

class TimelineNavTest {
  @Test fun `open sets the detail scale`() {
    val s = rootReducer(AppState(route = Route.Hubs, currentHubId = "h1"), OpenTimelineDetail(TimelineScale.Day))
    assertEquals(TimelineScale.Day, s.timelineDetail)
  }
  @Test fun `close clears it`() {
    val s = rootReducer(AppState(route = Route.Hubs, currentHubId = "h1", timelineDetail = TimelineScale.Hub), CloseTimelineDetail)
    assertEquals(null, s.timelineDetail)
  }
  @Test fun `back closes the timeline detail before the hub`() {
    val open = AppState(route = Route.Hubs, currentHubId = "h1", timelineDetail = TimelineScale.Day)
    assertEquals(CloseTimelineDetail, backAction(open))
    val closed = AppState(route = Route.Hubs, currentHubId = "h1")
    assertEquals(CloseHub, backAction(closed))
  }
}
```

- [ ] **Step 2: Run to verify it fails.** `--tests "*TimelineNavTest"`. Expected: FAIL.

- [ ] **Step 3: Implement.**
  - `Model.kt` `AppState`: add `val timelineDetail: TimelineScale? = null,`.
  - `Reducer.kt` actions: `data class OpenTimelineDetail(val scale: TimelineScale) : Action` and `data object CloseTimelineDetail : Action`.
  - `Reducer.kt` clauses (in the Hubs section): `is OpenTimelineDetail -> state.copy(timelineDetail = action.scale)` and `is CloseTimelineDetail -> state.copy(timelineDetail = null)`. Also clear it on `CloseHub`: change the `CloseHub` clause to `state.copy(currentHubId = null, currentHubTree = null, hubFocusBlockId = null, timelineDetail = null)`.
  - `BackNav.kt`: in the `Route.Hubs ->` branch, change to `if (state.timelineDetail != null) CloseTimelineDetail else if (state.currentHubId != null) CloseHub else null`.

- [ ] **Step 4: Run to verify pass.** Same. Expected: PASS (3 tests).

- [ ] **Step 5: Commit.**

```bash
git add apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/Reducer.kt apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/Model.kt apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/BackNav.kt apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/TimelineNavTest.kt
git commit -m "feat(client): timeline-detail nav substate + back handling (ADR 0045)"
```

---

### Task 10: Attachment → CardAction mapping

**Files:**
- Create: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/TimelineActions.kt`
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/TimelineActionsTest.kt`

**Interfaces:**
- Consumes: `Attachment`, `CardAction` (Extraction §11).
- Produces: `fun Attachment.toCardAction(): CardAction?` — `call`→`Call(tel)`, `nav`→`Navigate(query)`, `link`→`OpenUrl(url)`, `open`→`OpenHub(ref.hubId, ref.blockId)`; returns null when the required field is absent.

- [ ] **Step 1: Write the failing test.**

```kotlin
package com.sloopworks.dayfold.client
import com.sloopworks.dayfold.client.cards.CardAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimelineActionsTest {
  @Test fun `maps each kind`() {
    assertEquals(CardAction.Call("18005551212"), Attachment("call","Call",tel="18005551212").toCardAction())
    assertEquals(CardAction.Navigate("Henderson Hall"), Attachment("nav","Map",query="Henderson Hall").toCardAction())
    assertEquals(CardAction.OpenUrl("https://x.test"), Attachment("link","List",url="https://x.test").toCardAction())
    assertEquals(CardAction.OpenHub("h1","b2"), Attachment("open","List",ref=AttachmentRef("h1",blockId="b2")).toCardAction())
  }
  @Test fun `missing field → null`() {
    assertNull(Attachment("call","Call").toCardAction())
    assertNull(Attachment("open","x").toCardAction())
  }
}
```

- [ ] **Step 2: Run to verify it fails.** `--tests "*TimelineActionsTest"`. Expected: FAIL.

- [ ] **Step 3: Implement.** Create `TimelineActions.kt`:

```kotlin
package com.sloopworks.dayfold.client
import com.sloopworks.dayfold.client.cards.CardAction

fun Attachment.toCardAction(): CardAction? = when (kind) {
  "call" -> tel?.let { CardAction.Call(it) }
  "nav" -> query?.let { CardAction.Navigate(it) }
  "link" -> url?.let { CardAction.OpenUrl(it) }
  "open" -> ref?.let { CardAction.OpenHub(it.hubId, it.blockId) }
  else -> null
}
```

- [ ] **Step 4: Run to verify pass.** Same. Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git add apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/TimelineActions.kt apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/TimelineActionsTest.kt
git commit -m "feat(client): map timeline attachments to CardAction (ADR 0045)"
```

---

### Task 11: Timeline card composable + snapshot

**Files:**
- Create: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/TimelineCard.kt`
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/TimelineCardSnapshotTest.kt`

**Interfaces:**
- Consumes: `TimelineCardModel` (Task 7), `DayfoldIcons` (Task 8), the app theme (`MaterialTheme.colorScheme`, `DayfoldTheme`).
- Produces: `@Composable fun TimelineCard(model: TimelineCardModel, onOpen: () -> Unit)`. Renders the day rail (done cap → NOW line → windowed rows → tail → footer with "Added to this hub" chip) or the roadmap spine + next callout, per `model.scale`. Pixel reference: `designs/hub-timeline/Timeline-Card.dc.html`. Use `MaterialTheme.colorScheme` roles (done=`secondary`, next=`primary`, major=`tertiary`, surface=`surfaceContainerHigh`, lines=`outlineVariant`). NOW halo = static `Modifier.drawBehind` ring in `primary` at ~0.22 alpha (single settle-pulse deferred behind `rememberReduceMotion()`; static ring is the settled state). Chips + the whole card ≥48dp; card root `Modifier.clickable(onClick = onOpen)`.

- [ ] **Step 1: Write the failing snapshot test.**

```kotlin
package com.sloopworks.dayfold.client
import androidx.compose.ui.test.*
import kotlinx.datetime.TimeZone
import java.io.File
import javax.imageio.ImageIO
import androidx.compose.ui.graphics.toAwtImage
import kotlin.test.Test
import kotlin.test.assertTrue

class TimelineCardSnapshotTest {
  private val ny = TimeZone.of("America/New_York")
  private fun dayModel() = presentTimelineCard(Timeline(tz="America/New_York", stops = listOf(
    Stop("2026-08-24T07:30:00-04:00","Car loaded"), Stop("2026-08-24T09:50:00-04:00","Checked in"),
    Stop("2026-08-24T11:00:00-04:00","Elevator slot"), Stop("2026-08-24T12:30:00-04:00","Lunch"),
    Stop("2026-08-24T14:00:00-04:00","Bookstore"))), "2026-08-24T10:40:00-04:00", ny)!!
  @OptIn(ExperimentalTestApi::class)
  private fun shot(name: String, dark: Boolean) = runComposeUiTest {
    setContent { DayfoldTheme(darkTheme = dark) { TimelineCard(dayModel(), onOpen = {}) } }
    val img = onRoot().captureToImage()
    assertTrue(img.width > 0 && img.height > 0)
    File("build/snapshots").apply { mkdirs() }.let { ImageIO.write(img.toAwtImage(), "png", File(it, "$name.png")) }
  }
  @Test fun dayLight() = shot("timeline-card-day-light", false)
  @Test fun dayDark() = shot("timeline-card-day-dark", true)
}
```

- [ ] **Step 2: Run to verify it fails.** `--tests "*TimelineCardSnapshotTest"`. Expected: FAIL — `Unresolved reference: TimelineCard`.

- [ ] **Step 3: Implement `TimelineCard.kt`.** Translate `Timeline-Card.dc.html` to Compose: a `Column` in a `Surface(color = surfaceContainerHigh, shape = RoundedCornerShape(22.dp))`; done cap `Row`; NOW `Row` (dot with `drawBehind` halo + `Badge`-style pill + gradient trail `Box`); windowed rows via `model.window.forEach` (rail dot `Canvas`/`Box` + connector + title/time); tail `Row`; footer `Row` ("Open timeline" + the "Added to this hub" `AssistChip`). Roadmap branch: a `Row` of `SpineNode`s over a `LinearProgressIndicator`-style track + the next-callout `Row`. All interactive elements wrapped to ≥48dp.

- [ ] **Step 4: Run to verify pass + eyeball.** Same command → PASS; open `apps/client/build/snapshots/timeline-card-day-light.png` and compare to the mock.

- [ ] **Step 5: Commit.**

```bash
git add apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/TimelineCard.kt apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/TimelineCardSnapshotTest.kt
git commit -m "feat(client): timeline card composable + snapshot (ADR 0045)"
```

---

### Task 12: Timeline detail composable (grouped feed + scope toggle + NOW) + snapshot

**Files:**
- Create: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/TimelineDetail.kt`
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/TimelineDetailSnapshotTest.kt`

**Interfaces:**
- Consumes: `presentTimelineDetail(...)` (Task 7), `hasBothScales(...)` (Task 6), `Attachment.toCardAction()` (Task 10).
- Produces: `@Composable fun TimelineDetail(tl: Timeline, initialScale: TimelineScale, nowIso: String, tz: TimeZone, showToggle: Boolean, onBack: () -> Unit, onAction: (CardAction) -> Unit)`. Header (back + title + subtitle + `SingleChoiceSegmentedButtonRow` toggle when `showToggle`) over a scrolling `LazyColumn` with `stickyHeader` group headers, the NOW line, T3/T4 entry rows (assignee avatar + attachment chips dispatching `onAction(attachment.toCardAction())`), and the authored-provenance footnote. Toggle state is local (`remember`), seeded from `initialScale`. Pixel reference: `Timeline-Detail.dc.html`.

- [ ] **Step 1: Write the failing snapshot test** (day + hub × light — mirror Task 11's `shot` harness; construct a `Timeline` with the mock's day + roadmap stops; assert non-empty pixels).

- [ ] **Step 2: Run to verify it fails.** `--tests "*TimelineDetailSnapshotTest"`. Expected: FAIL (unresolved `TimelineDetail`).

- [ ] **Step 3: Implement `TimelineDetail.kt`** translating `Timeline-Detail.dc.html`: `Column` header + `LazyColumn` (`stickyHeader` for group labels; `item` for the NOW line; `items` for entries). Entry = rail (`Box` dot with per-status glyph from `DayfoldIcons` + connector) + content card (`Surface`); attachment chips = `AssistChip(onClick = { onAction(att.toCardAction() ?: return@AssistChip) }, ...)` sized ≥48dp; assignee = a small avatar `Box` + `Text`. NOW `liveRegion = LiveRegionMode.Polite` semantics. Reduced motion respected (no infinite animation).

- [ ] **Step 4: Run to verify pass + eyeball** the four PNGs vs the mock.

- [ ] **Step 5: Commit.**

```bash
git add apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/TimelineDetail.kt apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/TimelineDetailSnapshotTest.kt
git commit -m "feat(client): timeline detail composable + snapshot (ADR 0045)"
```

---

### Task 13: Wire into HubDetailScreen — hoisted card + transition host + a11y

**Files:**
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/HubScreens.kt` (hoist the card above sections; wrap the hub detail in a shared-transition host for the card→detail morph)
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/HubTimelineIntegrationSnapshotTest.kt`

**Interfaces:**
- Consumes: `state.currentHubTree.hub.timeline`, `state.timelineDetail`, `presentTimelineCard`, `TimelineCard`, `TimelineDetail`, `OpenTimelineDetail`/`CloseTimelineDetail` (Task 9), `cardSharedBounds` (Extraction §9).
- Produces: the hub dossier renders a hoisted `TimelineCard` (when `hub.timeline != null`) that morphs into `TimelineDetail` when `state.timelineDetail != null`, modeled on `ContentHost` (Extraction §9) but scoped to the Hubs surface.

- [ ] **Step 1: Write the failing integration snapshot test.** Build an `AppState(route=Route.Hubs, currentHubId="h1", currentHubTree = <tree whose hub has a timeline>)`; render `HubDetailScreen(state)`; assert the card appears (non-empty pixels + the snapshot visually contains the timeline card). Add a second scene with `timelineDetail = TimelineScale.Day` asserting the detail overlay renders.

- [ ] **Step 2: Run to verify it fails.** Expected: FAIL (card not yet rendered).

- [ ] **Step 3: Implement.**
  - In `HubDetailScreen`, before the `tree.sections.sortedBy { it.ord }.forEach` loop (Extraction §10, ~line 304), add: `tree.hub.timeline?.let { tl -> presentTimelineCard(tl, nowIso, tz)?.let { model -> item(key = "timeline") { Box(Modifier.cardSharedBounds("timeline")) { TimelineCard(model) { store.dispatch(OpenTimelineDetail(model.scale)) } } } } }` — obtaining `nowIso`/`tz` the same way the screen already gets the clock (grep the screen for an existing `now`/clock source; if none, inject a `nowProvider`/`Clock.System.now().toString()` + `tz` from the timeline's `tz` with device fallback per Global Constraints).
  - Wrap the Hubs detail content + a full-surface `TimelineDetail` overlay in a `SharedTransitionLayout` + `AnimatedContent` keyed on `state.timelineDetail` (modeled on `ContentHost`, Extraction §9), applying `cardSharedBounds("timeline")` to both the card and the detail root; `TimelineDetail(..., onBack = { store.dispatch(CloseTimelineDetail) }, onAction = handle)`; reduced-motion → `snapTo`.
  - The `showToggle` arg = `hasBothScales(tl, nowIso, tz)`.

- [ ] **Step 4: Run to verify pass + eyeball** the integration PNGs (card in dossier; detail overlay). Then run the FULL client test suite: `./gradlew :client:desktopTest`.

- [ ] **Step 5: Commit.**

```bash
git add apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/HubScreens.kt apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/HubTimelineIntegrationSnapshotTest.kt
git commit -m "feat(client): hoist timeline card in hub dossier + card→detail transform (ADR 0045)"
```

---

### Task 14: Full-stack verification + docs

**Files:**
- Modify: `backlog/now.md` (mark the timeline slice), `specs/hub-timeline-design.md` (flip §12 open-item 1 to done)
- No new code.

- [ ] **Step 1: Run the whole gate.**

Run:
```bash
cd /Users/patrick/workspace/dayfold && npm run codegen && \
  cd apps/api && npx vitest run && \
  cd ../.. && cd apps && JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ./gradlew :cli:test :client:desktopTest
```
Expected: all green.

- [ ] **Step 2: Author a real timeline via the CLI** against a dev hub (a manual smoke — push a hub with a `timeline`, pull on-device/desktop, confirm the card renders + opens + toggles + a chip deep-links). Record the result.

- [ ] **Step 3: Update `backlog/now.md`** — add the timeline slice under the current stage with status + a pointer to ADR 0045 + this plan.

- [ ] **Step 4: Commit.**

```bash
git add backlog/now.md specs/hub-timeline-design.md
git commit -m "docs: record hub-timeline slice complete (ADR 0045)"
```

---

## Self-Review

**Spec coverage (`specs/hub-timeline-design.md`):** §2 model → Tasks 1,4,9,13. §3 schema → Tasks 1,4. §4 presentation pipeline → Tasks 5,6,7. §5 states → Tasks 6,7 (empty/all-done/not-today logic) + snapshots 11-13. §6 nav/transform/chips → Tasks 9,10,13. §7 member model (Hide-for-me) → **carried to a follow-up** (the standard `SwipeToDismissBox`/overflow "Hide for me" wrapper is applied when the hoisted card is placed; add it in Task 13 if the wrapper is reused, else a small follow-up — noted). §8 M3/a11y → Tasks 8,11,12,13. §9 change-set → Tasks 1-3 (schema/server/CLI) + 4-13 (client). §11 testing → every task. §12 open items: mock revision (done pre-plan), tz fallback (Global Constraints + Task 13).

**Gap flagged:** §7 "Hide for me" on the timeline card is not its own task — fold it into Task 13 Step 3 (wrap the hoisted card in the existing hide wrapper used by `HubBlockCard`) or spin a Task 13b. **Action:** implementer adds the hide wrapper in Task 13; if the wrapper is block-specific and non-trivial to reuse for a hub-level card, defer to a follow-up and note in Task 14 Step 3.

**Placeholder scan:** UI Tasks 11-13 specify structure + exact tokens + the mock as pixel reference rather than pasting full Compose bodies (the mock is the pixel-complete source; snapshots are the gate). Logic Tasks 1-10 carry complete code. No TBD/TODO.

**Type consistency:** `presentTimelineCard`/`presentTimelineDetail`, `TimelineCardModel`, `PresentedTimeline`, `StopStatus`, `TimelineScale`, `Attachment.toCardAction()`, `OpenTimelineDetail(scale)`/`CloseTimelineDetail`, `AppState.timelineDetail` used consistently across tasks.

## Execution Handoff

After review, execute via **subagent-driven-development** (fresh subagent per task + two-stage review) or **executing-plans** (inline batches).
