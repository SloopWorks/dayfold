# Continue & complete the Hub Timeline feature (ADR 0045) — loop-to-done

Paste this as the opening message of a new session. It is self-contained; read the
referenced repo artifacts before acting.

---

You are completing the **Hub Timeline** feature (ADR 0045) in the Dayfold repo. **Phase 1
is built, merged, and on-device-verified.** Your job: drive ALL remaining timeline work to
100% and make the on-device UI pixel-match the hi-fi specs in `designs/hub-timeline/`.
**Work in a loop** — design → build → review → verify on-device against the mock → fix
gaps → repeat — until the feature is complete and faithful to the specs. Do not stop after
one pass; only stop for an operator-gated decision (ADR acceptance, design sign-off, scope,
spend) or a genuine blocker.

## Start-of-session (read first, in order)
1. `CLAUDE.md` (governance, confidence protocol, guardrails) + the start-of-session routine.
2. `adr/0045-hub-timeline-authored-content-type.md` (the accepted decision).
3. `specs/hub-timeline-design.md` (full design; §12 = open items).
4. `docs/superpowers/plans/2026-06-30-hub-timeline.md` (the Phase-1 plan + its binding v2 revisions block).
5. `designs/hub-timeline/` — the **hi-fi specs you must match**: `Index.dc.html` (brief + density ladder + decisions + a11y + Compose map), `Timeline-Card.dc.html`, `Timeline-Detail.dc.html`, `Tap-To-Detail.dc.html`, `NOTES.md`.
6. `.superpowers/sdd/progress.md` (the Phase-1 build ledger — every task, fix, and lesson).
7. `processes/agent-dev-loop.md` (toolchain + on-device runbook) + `processes/planning-loop.md`.

## Current state
- **Merged:** PR #261 → base branch **`now-derived-phase-b`** (squash `2086fa8`) + a bundle-sync commit `9f575f3`. Base is green.
- **Start new work by branching from latest `now-derived-phase-b`** (pull it first). Do not build on `main` (the timeline reuses `NowDerive` date helpers absent on `main`).
- Phase-1 shipped: `Hub.timeline` authored property (schema + server `hubTimelineIssues` + CLI `hubTimelineErrors`, content-blind); pure `TimelinePresenter` (status/scale/NOW/grouping/windowing, tz-injected); day-rail + hub-roadmap cards; scrollable detail; nav substate (`AppState.timelineDetail` + `OpenTimelineDetail`/`CloseTimelineDetail` + BackNav); attachment→`CardAction`; `Hub.timeline` persisted through SQLDelight (column + migration `9.sqm`); card→detail shared-element morph.

## Remaining work (prioritize P0 first)

**P0 — makes it real (nothing authors a timeline yet except hand JSON):**
- **Authoring enablement.** Update the `dayfold-curator` skill to author timelines, and add a CLI template/example (`apps/cli/templates/`). `dayfold push hubs <json-with-timeline>` already validates; the gap is that no tool *produces* a timeline. Author a real timeline for the operator's own hub (dogfood) once ready — operator-gated (external content).

**Phase 2 — deferred features (each its own slice; design-first + review):**
- **Client-derived fallback (`deriveTimeline`)** — derive a lightweight timeline on-device from a hub's existing dated blocks (checklist dues, milestones, location pickups) when no authored timeline exists. **This needs its own Proposed ADR** (it is the ADR-0043-class "second on-device projection over hub content" — Hub→Now boundary / dumb-client posture). It also re-enables any "surfaces through Now" story. Brainstorm → ADR → design → build.
- **Day↔hub scope toggle + second-scale affordance** — the in-detail `SingleChoiceSegmentedButtonRow`, `hasBothScales`, and the card "also a roadmap/day" hint. (Cut from Phase 1; render one auto-selected scale.)
- **Roadmap `✓N` collapse** — collapse a >6-node leading done-run into one `✓N` node (Phase 1 shows a "+M more" tail). Reinstate `SpineNode.collapsedCount` per `Timeline-Card.dc.html`.
- **Per-member "Hide for me"** on the hoisted timeline card (ADR-0039 §W5 local-hide for a hub-level element).

**Polish / fidelity (compare against the mocks; these are gaps):**
- **Card time labels lack AM/PM** — `stopTimeLabel` string-parses; the detail formats correctly. Cleanest: add `PresentedStop.timeLabel` computed via the tz-aware `clockTime` in the presenter so card + detail share it (also closes the raw-`at` DST/offset-vs-tz risk in `stopTimeLabel`/`calloutDateLabel`).
- **`moreCount>0` roadmap-overflow path untested** (fixtures ≤6 nodes) — add a >6-node test.
- **Detail-day `nowIndex`** is computed in original-list order vs grouped render order — aligns only if stops are chronological; make robust.
- Cosmetics: `TimelinePresenter.kt:50` redundant compare-key comment; `HubScreens.kt` indent; `reduceMotion` not a `LaunchedEffect` key (mirrors `ContentHost`).
- **Any other visual drift** you find comparing on-device screenshots to `designs/hub-timeline/` at both light+dark and every state (empty / all-done / not-today / single-scale) — fix to match.

**Governance / rollout:**
- **Family-tz delivery** — Phase 1 falls back device→author-stamped tz; revisit when M1 `family_settings.timezone` lands.
- **Tune** scale-selection thresholds (>14d / ≥3 date-only) + **NOW-marker calm** (reconcile the loud timeline NOW vs the quiet Now-tab band) against real authored content.
- **Ship** — `now-derived-phase-b → main` (rides with Phase B); this deploys the API bundle to Vercel and makes timelines authorable in prod.

## Process (per slice)
1. **Design-first (ADR 0008):** for any NEW surface (toggle, derived-fallback provenance/empty states, collapse), produce/refresh a **hi-fi mockup in `designs/hub-timeline/`** (author with `frontend-design`; sync via the claude_design MCP / `/design-sync`) and get **operator sign-off** before deep build. Keep the mocks honest (content-blind, Phase-appropriate copy — no "derived on-device" until the derive path ships).
2. **ADR-class items** (the derived fallback; any schema change) → **Proposed ADR** accepted by the operator before build. Never agent-decide scope/pricing/legal/spend.
3. **Build TDD**, subagent-driven where useful (fresh subagent per task + a spec+quality review after each; final whole-branch review). Reuse `superpowers` skills.
4. **Verify on-device every slice** (this is what caught the Phase-1 DB-round-trip bug): build the Android debug APK against the fake backend, drive to the surface, screenshot, and **Read the PNG to compare against the mock**. Fix any gap, re-verify.
5. **CI/PR discipline** (see lessons) → merge → repeat until done.

## Definition of done
- All P0 + Phase-2 features shipped; polish gaps closed.
- **On-device pixel-matches `designs/hub-timeline/`** across day-card, roadmap-card, and detail, in light AND dark, for every state (rich, empty, all-done, not-today, single-scale, >6-node roadmap).
- A real authored timeline exists on the operator's own hub (dogfood).
- CI green; merged to `now-derived-phase-b` (and, when Phase B ships, to `main`).

## Critical lessons / gotchas (do NOT relearn these)
- **After ANY schema codegen, run `cd apps/api && npm run build:fn` and commit `apps/api/api/index.js`.** `npm run codegen` updates `content.ts`/kotlin but NOT the esbuild Vercel bundle; CI's staleness guard fails otherwise, and the stale `.strict()` `HubSchema` would reject a timeline hub at runtime.
- **`now-derived-phase-b` is UNPROTECTED** — `gh pr merge --squash` merges *immediately*, not waiting for the non-required `ci.yml`. Before merging: confirm `gh pr checks <n>` is all green (or use `--auto` only after the fix commit's run passes). The Phase-1 merge landed a stale bundle this way.
- **Any new `Hub` field must be persisted through the client DB:** add a column to the `hub` table in `Content.sq` + upsert + `rowToHub` decode + a migration (mirror how `media`/`timeline` are stored as JSON TEXT). Desktop tests that build `HubTree` directly will NOT catch a dropped field — on-device will.
- **Continuous vertical rails:** `Row(Modifier.height(IntrinsicSize.Min))` + connector `Modifier.weight(1f)` (the mock's `flex:1`); a fixed-height stub leaves gaps. In an unbounded parent (a wrap-content card), do NOT use `weight(1f)` for a spacer/connector (it grabs the canvas) — that was a real Phase-1 bug.
- **Toolchain:** `JAVA_HOME=$(/usr/libexec/java_home -v 17)` (or `ls -d /opt/homebrew/Cellar/openjdk@17/*/libexec/openjdk.jdk/Contents/Home`). Client tests: `cd apps && ./gradlew :client:desktopTest`. CLI has its OWN gradlew at `apps/cli/`. Snapshots land in `apps/client/build/snapshots/*.png` — `Read` them.
- **On-device:** Pixel 10 Pro serial `57091FDCH01331` (re-pick each session — `adb devices`; it comes and goes on USB). Build with `DAYFOLD_API=fake://busy-family FAMILY_ID=dummy HOUSEHOLD_SECRET=dummy ANDROID_HOME=~/Library/Android/sdk JAVA_HOME=<jdk17> ./gradlew :androidApp:assembleDebug`; `pm clear` before install; `am start ...MainActivity`; tap "Dev sign-in (fake)". The demo timeline lives in `FakeScenarios.kt` `collegeHub` (busy-family scenario; open the "Lillian → Butler" hub). A `fake://<scenario>` build-time `DAYFOLD_API` routes straight to that scenario.
- **`FakeScenarios.kt` is a subpackage (`.fake`)** — model types (`Timeline`/`Stop`/`Attachment`/`AttachmentRef`) need explicit imports.
- **Guardrails:** server/CLI stay content-blind (structure only). Phase-1 copy stays authored-honest. `timeline` is a hub property, not a block type — never touch the block-type enum. tz is injected (never `currentSystemDefault()` inside the presenter). No `df://`.

Begin by reading the artifacts above, then propose the slice order and the first slice's plan.
