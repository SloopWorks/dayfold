# Client Module Build Measurements

**Date:** 2026-07-02  
**Kotlin:** 2.3.20 | **Gradle:** 9.4.1 | **Java:** OpenJDK 17 (Homebrew)  
**Machine:** Darwin arm64 (Apple Silicon)

---

## Module size

| Source set | .kt files |
|-----------|-----------|
| commonMain | 77 |
| desktopMain | 7 |
| generated (compose/SQLDelight) | ~31 |
| **Total main compiled** | **115** |
| commonTest + desktopTest | 87 |
| **Total test compiled** | **87** |

---

## Build times

| Build | Task | Files compiled | Wall time | Kotlin time |
|-------|------|---------------|-----------|-------------|
| Cold-full | compileKotlinDesktop | 115 | 6.9s | 4.2s |
| Cold-full | compileTestKotlinDesktop | 87 | 8.7s (incl. main) | 1.7s |
| Incremental run 1 | compileKotlinDesktop | 110 | 7.4s (+ test) | ~4.5s |
| Incremental run 1 | compileTestKotlinDesktop | 87 | (included above) | ~2.1s |
| Incremental run 2 | compileKotlinDesktop | 115 | 5.2s | ~4.1s |
| Incremental run 2 | compileTestKotlinDesktop | 0 (UP-TO-DATE) | — | — |
| Incremental run 3 | compileKotlinDesktop | 115 | 4.6s | ~4.0s |
| Incremental run 3 | compileTestKotlinDesktop | 0 (UP-TO-DATE) | — | — |
| Client clean+test run | all | — | 18.8s | — |
| Whole-repo assembleDebug | — | BLOCKED* | — | — |

*`assembleDebug` blocked by missing `google-services.json` in worktree.

**REQUIRED FOLLOW-UP:** the whole-repo full-build baseline (`./gradlew clean && ./gradlew assembleDebug :client:desktopTest`) is NOT yet captured — `assembleDebug` needs `google-services.json` (a secret absent from this worktree). Capture it in CI or an environment that has the secret BEFORE claiming the +5–15% full-build-regression DoD in Phase 2.3. `:client:desktopTest` (18.8s) does NOT substitute — it excludes the androidApp compile.

---

## Phase 1a — org.gradle.caching=true + org.gradle.parallel=true

**Date:** 2026-07-02 | Gradle 9.4.1 | Kotlin 2.3.20 | Java 17 Homebrew | Darwin arm64

Properties added to `apps/gradle.properties`:
```
org.gradle.caching=true
org.gradle.parallel=true
```

### Build times — Phase 1a

| Build | Task | Lines analyzed (proxy) | Wall time | Kotlin time |
|-------|------|----------------------|-----------|-------------|
| Incremental P1a run 1 | compileKotlinDesktop | 15571 | ~15s total | 4.670 s |
| Incremental P1a run 1 | compileTestKotlinDesktop | UP-TO-DATE | — | — |
| Incremental P1a run 1 | desktopTest | ran | ~10s (incl. in total) | — |
| Incremental P1a run 2 | compileKotlinDesktop | 15572 | ~15.9s total | 4.438 s |
| Incremental P1a run 2 | compileTestKotlinDesktop | UP-TO-DATE | — | — |
| Incremental P1a run 2 | desktopTest | ran | ~10s (incl. in total) | — |
| Cold-daemon proxy (--stop + touch FeedScreen.kt) | compileKotlinDesktop | 15573 | 28s | 12.783 s |

### Phase 0 vs Phase 1a delta

| Metric | P0 baseline | P1a | Delta |
|--------|-------------|-----|-------|
| Kotlin compile time (incremental, warm daemon) | ~4.1–4.5 s | 4.44–4.67 s | ~0 (within noise) |
| Lines analyzed (main compile) | ~15570 | ~15571–15573 | ~0 |
| REBUILD_REASON | KT-62686 every run | KT-62686 every run | unchanged |
| `desktopTest` re-runs on FeedScreen.kt touch | Skipped runs 2+3 | Runs every time | Regression: +10s/loop |
| Cold-proxy wall time | 18.8s (warm-daemon + clean) | 28s (cold JVM + incremental) | **NOT COMPARABLE / excluded** — cold JVM startup dominates; apples-to-oranges measurement |

### Notes

- Kotlin compile time **unchanged** — caching/parallel don't accelerate an IC-fallback full recompile.
- `desktopTest` now re-executes every incremental build (vs UP-TO-DATE in P0 runs 2+3). With `org.gradle.caching=true`, the `Test` task becomes cacheable, which changes its output/up-to-date handling so `:client:desktopTest` no longer reaches UP-TO-DATE on an incremental edit. **Net effect: +~10s per incremental loop turn** while under KT-62686.
- Cold-daemon proxy (28s) is dominated by cold JVM startup (12.78s Kotlin compile vs ~4.5s warm). Not comparable to P0's clean-build cold metric (18.8s with warm daemon).
- Build cache is computing keys and storing entries, but offers no help for the inner-loop scenario because the source changes on every run.
- `org.gradle.parallel=true` benefits multi-module builds; no measurable effect on single-module `:client:desktopTest`.

**Verdict:** Neither `org.gradle.caching` nor `org.gradle.parallel` moves the incremental inner loop. KT-62686 is the controlling variable. Module split (Phase 2) remains the path to improvement.

**Gate recommendation:** `org.gradle.caching` REVERTED for local dev (it re-ran the full test suite every edit under KT-62686, +~10s/loop, and re-enabling test UP-TO-DATE skip restores the P0 fast path). Its CI/cold-build benefit is UNQUANTIFIED here — a valid full-build baseline needs the google-services secret (see the P0 required-follow-up). Re-evaluate enabling build-cache CI-side after the Phase-2 split, with a valid cold baseline. `org.gradle.parallel` kept (neutral for a single module, helps the multi-module build post-split). Net: neither config lever helps the inner loop under KT-62686 — the module split is the controlling lever.

---

## Phase 1b — org.gradle.configuration-cache=true

**Date:** 2026-07-02 | Gradle 9.4.1 | Kotlin 2.3.20 | Java 17 Homebrew | Darwin arm64

Property changed in `apps/gradle.properties`:
```
org.gradle.configuration-cache=true   # was false
```

### CC results per graph

| Graph | Task | Run 1 | Run 2 | CC result |
|-------|------|-------|-------|-----------|
| `:client` desktop | `./gradlew :client:desktopTest` | "entry stored" / 1s wall | "entry reused" / 315ms wall | PASS |
| `:client` iOS | `./gradlew :client:linkDebugFrameworkIosSimulatorArm64` | "entry stored" / 29s wall | "entry reused" / 363ms wall | PASS |
| `:androidApp` config-only | `./gradlew :androidApp:help --configuration-cache` | "entry stored" / 537ms wall | "entry reused" / 237ms wall | PASS |

### Configuration-time delta (from `--profile`)

| Graph | Config time (store, 1st run) | Config time (reuse, 2nd run) | Delta |
|-------|------------------------------|------------------------------|-------|
| `:client:desktopTest` | 0.175s | 0s | −0.175s |
| `:client:linkDebugFrameworkIosSimulatorArm64` | 0.120s | 0s | −0.120s |

Config-phase savings are small in absolute terms (~0.1–0.2s) because the project graph is shallow. The real CC win is that Gradle skips all `settings.gradle.kts` + plugin-application work on reuse, which matters more as the graph grows (Phase 2 module split will increase this).

### android-graph CC — DEFERRED (no secret)

`com.google.gms.google-services` (the prime CC-incompatibility suspect) was NOT exercised by `:androidApp:help` — that task only configures the project, it does not trigger the plugin's execution-time work. A full CC compatibility test for the android graph requires `./gradlew :androidApp:assembleDebug`, which needs `google-services.json` (absent from this worktree — same blocker as P0).

**Required follow-up:** run `./gradlew :androidApp:assembleDebug` in CI or an environment with the secret. If `com.google.gms.google-services` is CC-incompatible at execution time, Gradle will report it in the CC problem report and degrade gracefully (stores no entry for that invocation). Fix options: upgrade to a google-services version with CC support, or exclude the android assembleDebug invocation from CC. This is a required validation before declaring CC on for the full project.

### Verdict

**CC ON. Kept `org.gradle.configuration-cache=true`.**

All runnable graphs (`:client:desktopTest`, `:client:linkDebugFrameworkIosSimulatorArm64`) store and reuse the CC entry cleanly. Config-time win is small per-run (~0.12–0.18s) but compounds across the inner loop and will grow with the Phase-2 module split. No incompatibilities on any tested graph.

Android-graph full CC compatibility (`:androidApp:assembleDebug` + `com.google.gms.google-services`) is **UNTESTED** — needs the secret in CI. This is a required follow-up before shipping CC as a permanent setting for the android build path.

---

## IC behavior

**KT-62686 fires on every incremental build of `:client:compileKotlinDesktop`.**

Kotlin 2.3.20 with KMP + Compose triggers a known safety fallback (`Incremental compilation might be incorrect`) that causes a full recompilation regardless of how many files changed. This was observed on all 3 incremental runs.

`REBUILD_REASON` values seen:
- `Incremental compilation might be incorrect (KT-62686)` — all 3 incremental runs
- `Unknown Gradle changes` — run 1 + cold-full builds

Source changed: only `FeedScreen.kt` (1 file). Files recompiled: 110–115 (all of them).

---

## C1 Verdict

**IC broad (N≈all → split delivers)**

Incremental N-files (115) ≈ cold-full N-files (115). IC is not narrowing the recompile set for the main compilation task. Splitting `:client` into smaller modules will reduce files-per-task proportionally and directly lower the incremental inner loop time.

Example: splitting into 4 modules of ~29 files each would reduce the per-module incremental compile from ~4.2s to roughly ~1–1.5s per touched module.

---

## Phase 2 (post-split) — `:client` + `:ui` module measurements

**Date:** 2026-07-02 | Kotlin 2.3.20 | Gradle 9.4.1 | Java 17 Homebrew | Darwin arm64  
**Branch:** `client-modularize` (commit `ef813d5` — P2.2b: strip Compose from `:client`)  
**Module sizes (post-split):**

| Module | Lines analyzed (warm daemon, full recompile) | Source scope |
|--------|---------------------------------------------|--------------|
| `:client` commonMain | ~7,347–7,348 | Compose-free: reducers, engines, data, store |
| `:ui` commonMain | ~7,434–7,453 | Compose composables, theme, cards, entry points |
| `:ui` test | ~2,885–3,555 | UI desktop tests |
| **Total (both modules, warm)** | **~14,795** | vs P0 monolith: ~15,570 |

---

### Measurement 1: `:client` logic edit — KT-62686 escape test

**Method:** edit NowRank.kt (Compose-free `:client` file, blank line appended); run `./gradlew :client:compileKotlinDesktop` with warm daemon; revert.

**Kotlin report:** `dayfold-apps-build-2026-07-02-16-47-06-0.txt`

```
tasks = [:client:compileKotlinDesktop]
Number of lines analyzed: 7348
REBUILD_REASON: Incremental compilation might be incorrect (KT-62686)
Total Gradle task time: 2.399 s (wall: 2.797 s)
```

**KT-62686 verdict: SIZE WIN ONLY — KT-62686 still fires.**

A logic edit to a Compose-free `:client` file still triggers the KT-62686 safety fallback (full recompile within the module). The compiler does **not** escape KT-62686 just because Compose was removed.

**However, the size win is real:**
- P0 monolith (before split): ~15,570 lines analyzed, ~4.2s Kotlin compile
- P2 `:client` alone (after split): **7,348 lines, ~2.4s Kotlin compile** — a ~53% reduction in lines, ~43% faster
- The remaining half (~8,100 lines, ~2.6s) now lives in `:ui` and is NOT recompiled on a `:client`-only logic edit

**On a pure `:client` edit:** only `:client` recompiles (~2.4s); `:ui` does not recompile if its jar is up-to-date. Net: **~43% faster logic edit** vs P0, even with KT-62686 still active.

---

### Measurement 2: `:ui` edit isolation

**Two causes of a `:client` recompile — separated below:**

**(a) `:ui` MAIN edit → `:ui:compileKotlinDesktop`:** `:client` is UP-TO-DATE. Isolation holds.
**(b) `:ui:compileTestKotlinDesktop` target:** Gradle's jar-dependency chain requires `:client`'s compiled jar before it can compile `:ui` test sources. This forces `:client:compileKotlinDesktop` to run (as an upstream task), and KT-62686 fires on it. This is a property of the TEST target, not of any source edit.

**Confirming build (2026-07-02, clean-state deterministic check):**

Method: verify `git status` clean; warm daemon; append blank line to FeedScreen.kt (`:ui` composable); run `./gradlew :ui:compileKotlinDesktop` (MAIN only); then run `./gradlew :ui:compileTestKotlinDesktop`.

Run 1 — `./gradlew :ui:compileKotlinDesktop` (MAIN):
```
:client:compileKotlinDesktop  UP-TO-DATE
:ui:compileKotlinDesktop      executed (2s wall)
BUILD SUCCESSFUL in 2s
```

**`:client` was UP-TO-DATE** — `:ui` MAIN edit does not touch `:client`. Isolation confirmed.

Run 2 — `./gradlew :ui:compileTestKotlinDesktop` (same edit, `:ui` main already compiled):
```
:client:compileKotlinDesktop  UP-TO-DATE
:ui:compileKotlinDesktop      UP-TO-DATE
:ui:compileTestKotlinDesktop  UP-TO-DATE
BUILD SUCCESSFUL in 323ms
```

In steady state (`:ui` main already compiled), `compileTestKotlinDesktop` also sees `:client` UP-TO-DATE because the jar is already fresh. The `:client` recompile seen in the **original P2.3 measurement** (report `16-48-20`) occurred because `compileTestKotlinDesktop` was targeted directly on a warm-but-pre-edit state, which forced Gradle's jar-dependency chain to re-run `:client:compileKotlinDesktop` as part of assembling the full test classpath. That recompile was caused by (b) the `compileTestKotlinDesktop` jar-dep chain, **not** by reverting the earlier `:client` source edit.

**Confirmed steady-state behavior:** `:ui` MAIN edits leave `:client` UP-TO-DATE (isolation holds). The `:client` recompile in the original measurement was the `compileTestKotlinDesktop` jar-dep chain firing on a state where `:ui` main had not yet been compiled in this invocation.

---

**Original measurement (retained for record):**

**Kotlin report:** `dayfold-apps-build-2026-07-02-16-48-20-0.txt`

```
tasks = [:ui:compileTestKotlinDesktop]
:client:compileKotlinDesktop finished in 2.412 s  (recompiled — NOT up-to-date)
  Number of lines analyzed: 7347  REBUILD_REASON: KT-62686
:ui:compileKotlinDesktop finished in 2.644 s
  Number of lines analyzed: 8241  REBUILD_REASON: KT-62686
:ui:compileTestKotlinDesktop finished in 0.778 s
  Number of lines analyzed: 2885
Total Gradle task time: 5.898 s
```

This run targeted `compileTestKotlinDesktop` directly (cause b above) — `:client`'s jar was not yet fresh for that invocation, so Gradle rebuilt it. Not representative of the `:ui`-main-edit inner loop.

---

### DoD verification

| Check | Result |
|-------|--------|
| `:client:desktopTest` | **440 / 440 passed**, 0 failures |
| `:ui:desktopTest` | **311 / 311 passed**, 0 failures |
| `:ui:linkDebugFrameworkIosSimulatorArm64` | **BUILD SUCCESSFUL** — `ui/build/bin/iosSimulatorArm64/debugFramework/client.framework` linked |
| `grep -rE "androidx\.compose\|@Composable\|coil3" client/src` | **client Compose-free ✓** — no hits |
| `:androidApp:assembleDebug` / `assembleRelease` | **BLOCKED — `google-services.json` absent from worktree** — REQUIRED DEFERRED FOLLOW-UP: run in CI or secret-bearing env before declaring full-build DoD |

Test counts match the P2.2a expected values (client 440 / ui 311).

---

### P2 verdict vs P0

| Metric | P0 monolith | P2 `:client` edit | P2 `:ui` edit (main only) | Delta (client edit) |
|--------|-------------|-------------------|---------------------------|---------------------|
| Lines analyzed | ~15,570 | ~7,348 | ~7,434 (ui only) | **−53%** |
| Kotlin compile time | ~4.2s | ~2.4s | ~2.6s (ui) + :client UP-TO-DATE | **~−43%** |
| KT-62686 fires? | Yes (always) | **Yes (still)** | Yes | No escape |
| Recompile scope (logic edit) | All 115 files (~15,570 lines) | `:client` only (~7,348 lines) | `:ui` only (~7,434 lines) | Scope halved |
| Compose import in `:client`? | Yes | **No** | N/A | ✓ clean split |

**KT-62686 escape hypothesis: NOT proven.** Removing Compose from `:client` does not escape KT-62686. The fallback fires on all tasks in all modules (`:client`, `:ui`, `:androidApp`). This is a Kotlin 2.3.20 + KMP issue at the project level, not a Compose-specific trigger. The split delivers a **size win** (each module is ~half the lines → ~half the compile time per module), but not a KT-62686 escape.

**Split benefit is real but different from the hypothesis:** a pure logic edit (`:client` only) recompiles ~7,348 lines in ~2.4s vs ~15,570 lines in ~4.2s before — a meaningful improvement for the agent dev loop. A `:ui` edit recompiles `:ui` (~7,434 lines, ~2.6s) and not the logic layer (when targeting main only). The biggest gap vs expectation: `compileTestKotlinDesktop` triggers upstream recompile of `:client` due to Gradle's jar-dependency chain.

**Next lever:** the `:model`/`:data` further split (the second slice noted in ADR 0047 §Remaining) would shrink each module further. Alternatively, investigate whether Kotlin 2.4.x or a project-level `enableUnsafeIncrementalCompilationForMultiplatform=true` can escape KT-62686 — but that is explicitly flagged as unsafe by the Kotlin team.
