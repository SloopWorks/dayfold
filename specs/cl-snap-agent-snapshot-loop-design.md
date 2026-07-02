# CL-SNAP — Agent Snapshot Loop (headless render + golden diff + text semantics)

**Status:** Proposed (design) · 2026-07-02
**Owner:** operator · **Consumer side** of `redux-kotlin-snapshot`
**Depends on:** `org.reduxkotlin:redux-kotlin-snapshot:1.0.0-alpha04` (Maven Central)
**Composes with:** ADR 0019 (client observability & tooling), ADR 0013 (redux-kotlin),
`processes/agent-dev-loop.md`, the debug **Fake backend** (`FakeScenarios`)

---

## 1. Problem & goal

The agent dev loop verifies UI changes two expensive ways today: a device
screencap per iteration, or `Read`-ing raw PNGs that the hand-rolled snapshot
tests dump to `apps/client/build/snapshots/`. Both burn **vision tokens every
iteration** and neither catches a visual regression — the current tests
(`Hub/Feed/Auth/Enrichment/LoadingKit SnapshotTest`) write a PNG and assert only
`onNodeWithText(...)`; a broken layout passes as long as the text node exists.

`redux-kotlin-snapshot` gives us `f(state) → Composable → PNG (+ text semantics)`
rendered **headless in ms** (Compose `ImageComposeScene`, JVM/Skiko — no emulator,
no Robolectric). alpha04 is on Central and (per release) populates the semantics
dump. That unlocks a **text-first agent feedback loop**: verify most iterations in
text (semantics + golden verdict), and read a pixel PNG **only** when a shot
actually drifts or the change is deliberately visual.

**Goal:** stand up a scene registry + a Gradle entry point + a tiered
verification workflow so that:
- iterative changes that don't move pixels are verified with **zero image reads**,
- visual regressions are **caught** (golden diff), not silently passed,
- deliberate visual changes have a cheap **record → single-read → re-baseline** path,
- CI enforces goldens on every PR.

**Non-goals:** modifying `redux-kotlin-snapshot` (a separate reduxkotlin backlog);
rendering on the iOS target (JVM render is a proxy — see §9); solving headless
async image loading (fallback ladders only, unchanged); replacing reducer/selector
unit tests (snapshots complement, not replace them).

---

## 2. The tiered feedback loop (the core of this design)

Three tiers, cheapest first. The agent climbs a tier only when the cheaper one
can't decide the change.

| Tier | Command shape | Signal | Vision tokens | Use for |
|---|---|---|---|---|
| **0 — semantics** | `--scene X --preset Y --semantics` | text dump (rendered strings + node/role tree) on stdout | **none** | content/copy, ordering, conditional rendering, state→UI mapping, i18n |
| **1 — golden verdict** | `--batch shots.json --golden-dir … --json` | `report.json`: `MATCH`/`MISMATCH` + diff% per shot | **none** | refactors & behavior-preserving edits (goldens must stay MATCH); regression gate |
| **2 — pixel read** | `--scene X --preset Y --out /tmp/x.png` then `Read` | the PNG | ~1–2k / image | first sight of a new surface; deliberate visual change; investigating a Tier-1 MISMATCH |

**Velocity principle:** the loop's default is Tier 0/1 (text). Tier 2 is entered
**per-shot, on demand** — not every iteration, not every shot. A refactor that
touches ten screens but changes no pixels costs **zero** image reads and one batch
verdict.

### Change-class → tier mapping (velocity guide)

| Change class | Primary tier | Image reads | Notes |
|---|---|---|---|
| Reducer / selector / state plumbing (no visual delta) | 0 + unit test | 0 | semantics asserts content unchanged; goldens stay MATCH |
| Behavior-preserving refactor (rename, extract, move) | 1 | 0 | golden MATCH **is** the proof of no visual regression |
| Copy / content / conditional text | 0 | 0 | assert new strings present, old absent |
| New card/block renderer or new screen | 2 → 1 | 1 (once) | see it once; then golden guards it forever |
| Theme / spacing / visual polish (intentional) | 2 + record | 1 per changed shot | re-baseline the golden; diff% shows blast radius |
| Bug: "wrong thing renders for state Z" | 0 → 2 | 0–1 | semantics often localizes it without a read |

---

## 3. Architecture

Three cooperating pieces, each with one job:

```
:client (KMP)  ── commonMain composables + FakeScenarios/AppState builders  (unchanged)
      │  (desktop/jvm artifact)
      ▼
Scene registry (desktopTest)  ── SnapshotScenes.kt: one Scene per surface,
      │                            presets → real AppState via FakeScenarios/canonicalHub()
      ├──► JUnit path:  SnapshotApp.assertGolden(...)   → CI regression gate (fails build)
      └──► CLI path:    SnapshotApp.runCli(argv)        → agent loop + record + dashboard
                          exposed as Gradle task :client:snapshotUi (JavaExec)
```

### 3.1 Where the registry lives — decision

**Put the registry + entry point in `:client` `desktopTest`**, with
`redux-kotlin-snapshot` as a `desktopTestImplementation` dependency. The
`:client:snapshotUi` JavaExec task runs against the desktopTest runtime classpath.

*Rationale (HIGH-confidence infra choice, recorded here):*
- The existing snapshot tests **and** the state builders we reuse
  (`FakeScenarios` in commonMain, `canonicalHub()` in `HubSnapshotTest`) are
  already on the desktopTest classpath — maximum reuse, zero new wiring.
- Keeps the heavy JVM-only render dep **out of the shipping** `desktopMain`
  artifact (test scope only).
- Matches the `:client:snapshotUi` naming already documented in
  `agent-dev-loop.md`.

*Alternative considered — a dedicated `:snapshots` kotlin-jvm module.* Cleaner
`main()`, but adds a module and re-imports the state builders. **Adopt only if**
the registry outgrows desktopTest (many scenes, its own helpers). Documented as
the escape hatch, not the starting point.

### 3.2 Scene registry

```kotlin
// apps/client/src/desktopTest/kotlin/.../snapshot/SnapshotScenes.kt
val clientSnapshots = snapshotApp {
  defaults { width = 411; height = 891; density = 2f; theme = "light" }  // pin for determinism

  scene("feed") {
    presets("busy-family", "empty-new", "sync-error")     // = FakeScenarios ids
    render { args -> DayfoldTheme(args.theme == "dark") { FeedApp(storeOf(args)) } }
  }
  scene("hub-detail") {
    presets("canonical-light", "canonical-dark", "enriched")
    render { args -> DayfoldTheme(args.theme == "dark") { HubDetailScreen(stateOf(args)) } }
  }
  // …one scene per card type + per detail type; presets = curated states; theme = light|dark
}

fun main(argv: Array<String>) { clientSnapshots.runCli(argv); kotlin.system.exitProcess(0) }
```

- **State comes from the existing builders**, not new fixtures: presets map to
  `FakeScenarios.all` ids and `canonicalHub()`. Scenes render the **same
  `@Serializable` wire models** the fake backend serves, so a scene can't drift
  from real data shapes.
- `--state-json` remains available for ad-hoc states an agent constructs inline
  (each scene decodes its own JSON per `SnapshotInput.Json`).
- **`exitProcess(0)` is mandatory** — Skiko leaves non-daemon threads alive.

### 3.3 Gradle task

```kotlin
// apps/client/build.gradle.kts
tasks.register<JavaExec>("snapshotUi") {
  group = "verification"
  mainClass.set("com.sloopworks.dayfold.client.snapshot.SnapshotScenesKt")
  classpath = /* desktopTest runtime classpath */
  args = (project.findProperty("snapshotArgs") as String? ?: "").split(" ").filter { it.isNotBlank() }
}
```
Invoked: `./gradlew :client:snapshotUi -PsnapshotArgs="snapshot --scene feed --preset busy-family --out /tmp/x.png"`.
(Exact classpath wiring resolved in the plan; the JB Compose desktop test classpath already
carries Skiko.)

### 3.4 Goldens & manifest — one source of truth

- **Goldens committed** at `apps/client/src/desktopTest/resources/snapshots/`
  (the library's `assertGolden` default), and the CLI batch `--golden-dir` points
  at the **same** dir — one baseline, two consumers (CI JUnit + agent CLI).
- `shots.json` (the batch manifest) at
  `apps/client/src/desktopTest/resources/shots.json`:
  `{ "defaults": {…}, "shots": [ {"id","scene","preset"|"stateJson","theme"?} ] }`.
- Batch output (ephemeral) → `build/snapshots/` (`report.json` + `--dashboard`
  `index.html`), git-ignored.

---

## 4. Testing / verification strategy (ADR 0019 alignment)

ADR 0019's test policy: every store-driven surface gets **(a)** reducer unit test,
**(b)** a Compose snapshot, **(c)** a golden assertion "once available." alpha04
makes **(c)** available — this design lands it.

1. **Migrate the 5 existing snapshot tests** from raw-PNG-dump to golden assertions
   via `SnapshotApp.assertGolden(...)`. They keep their `onNodeWithText` content
   asserts (belt-and-suspenders: golden = pixels, semantics/text = content) but now
   **fail CI on visual drift** instead of dumping an unchecked PNG.
2. **CI gate:** `:client:desktopTest` runs the golden assertions; a PR that moves
   pixels without re-recording fails. Record locally with the library's record flag
   (`-Dsnapshot.record=true`), review the diff, commit the new golden.
3. **Agent loop:** the `:client:snapshotUi` CLI drives Tiers 0–2 (§2) during
   development — the fast inner loop before the CI gate.
4. **Semantics smoke (one-time, gates the token claim):**
   ```
   ./gradlew :client:snapshotUi -PsnapshotArgs="snapshot --scene feed --preset busy-family --semantics --out /tmp/x.png"
   # PASS = non-empty text dump on stdout. If empty/flag absent → CLI semantics not wired;
   # fall back to reading RenderResult.semantics via the JUnit assertGolden path in-process.
   ```

---

## 5. Determinism & CI (goldens are binaries)

- **Pin** width/height/density/theme in `defaults{}` so a shot is reproducible.
- Skiko bundles its own font/raster → deterministic **within an architecture**;
  cross-arch (dev arm64 vs CI x64) can still drift sub-pixel. The library's pixel
  `Differ` carries a `tolerance` + `maxDiffPercent` — keep it.
- **Decision needed (open question):** canonical golden arch. Options: (a) record
  on CI (Linux x64) as the source of truth, tolerate small local drift; (b) record
  on dev (arm64), let CI tolerate. Lean **(a)** — CI is the enforcer. Confirm at
  plan time.
- **Repo weight:** commit **few, small** goldens (pinned dims, canonical presets
  only — not every theme×state combo). If the golden set grows heavy, revisit
  git-lfs. Prefer **semantics-golden (text) diffs** where a text signal is
  sufficient — cheaper to store, review, and diff than PNGs.

---

## 6. Rollout (phases — detailed steps go to the implementation plan)

- **P1 — spine:** add the dep; `SnapshotScenes.kt` with **one** scene (`feed`) +
  `main`; `:client:snapshotUi` task; run the §4.4 semantics smoke. Proves the path
  end-to-end.
- **P2 — coverage:** port the remaining surfaces (hub-detail, card types, auth,
  enrichment, loading kit) as scenes/presets; migrate the 5 hand-rolled tests to
  `assertGolden`; record baseline goldens.
- **P3 — gate + loop:** `shots.json` batch + `--dashboard`; wire the CI golden
  gate into `:client:desktopTest`; commit goldens.
- **P4 — document:** update `agent-dev-loop.md` (the tiered loop, real commands)
  and post the ADR-0019 correction (golden-diff is no longer "Remaining via
  Roborazzi"; it's `redux-kotlin-snapshot`).

---

## 7. Success criteria

- Agent can verify a content/refactor change in **text only** (Tier 0/1), zero
  image reads, and the transcript shows it.
- A deliberate one-pixel-moving change produces a **single** targeted PNG read +
  a re-recorded golden — not a full-suite re-eyeball.
- An unintended visual regression **fails** `:client:desktopTest` (golden
  MISMATCH), where today it passes.
- The 5 legacy snapshot tests assert goldens; new surfaces get a scene + golden by
  policy.

---

## 8. Risks & open questions

- **Semantics-CLI unverified from here** — the §4.4 smoke is the gate; fallback is
  the in-process `RenderResult.semantics` JUnit path. (The exact class was a stub
  in alpha02; alpha04 reportedly wires it — verify, don't assume.)
- **iOS proxy** (this is an iOS-active period): JVM/Skiko renders **commonMain**
  Compose — a faithful proxy for shared UI, but blind to iOS insets/system
  fonts/status bar. Snapshots gate the shared surface; iOS-specific chrome still
  needs a device/sim check. Don't oversell green goldens as "iOS verified."
- **Alpha dependency concentration** — `redux-kotlin-*` now underpins state,
  devtools, snapshot, and the test gate. Consistent with the accepted alpha bet
  (ADR 0013/0019); pin `1.0.0-alpha04` explicitly and bump deliberately.
- **Gradle invocation floor** — each `:client:snapshotUi` call pays warm-daemon
  overhead (~1–3s); **batch** amortizes JVM/classpath start across all shots.
  Prefer batch for multi-shot loops; keep the daemon warm.
- **Golden review discipline** — a re-recorded golden must be **eyeballed once**
  before commit, or the gate rubber-stamps regressions. Record is a deliberate,
  reviewed act.

---

## 9. Why not Roborazzi (the ADR-0019 "Remaining" plan)

Roborazzi-CMP has a real iOS render path; `redux-kotlin-snapshot` does not. But for
dayfold: (1) the UI is commonMain → JVM render is a valid proxy; (2) the operator
**owns** reduxkotlin → the missing pieces (semantics depth, CLI surface) are our
own backlog, not an external dependency; (3) the **text semantics dump** is the
token lever this design is built around, and it's native here. Roborazzi buys the
iOS path at the cost of that leverage and an extra vendor. Revisit only if
iOS-specific visual regression becomes load-bearing.
