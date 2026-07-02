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
