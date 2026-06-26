# ADR 0034: Mobile Release Pipeline — Alpha/Beta/Prod to Google Play

## Status

**Accepted** 2026-06-26 (operator-approved — "inb 23 approved"; agent-drafted from a
3-agent best-practices review). **Operator-gated** — platform/vendor choice + external
app publishing + spend, so it crosses CLAUDE.md guardrails #2/#6 and the external-action
line, beyond the ADR 0012 agent-build rails. The **agent-buildable pipeline is
implemented** under this ADR (`release-android.yml`, the `:androidApp` signing/versioning
Gradle changes, and a PR `assembleDebug` smoke job in `ci.yml`); the publish path is
**inert** until the operator setup gates are done — mirroring the ADR 0031 CLI-release
posture (every external step is gated on its secret; with no secrets the workflow builds
an unsigned AAB and skips publish, staying green). **On acceptance: G5 (the API-env +
auth posture) is RATIFIED as written** — all three tracks build against the prod Vercel
API (no staging exists), rely on real sign-in (AUTH-S3), and never bake
`HOUSEHOLD_SECRET`/`DEV_AUTH_SECRET` into a store build. **G1–G4 remain operator setup
actions** (keystore, real Firebase config, Play account + first manual upload, store
listing) — they are the one-time human steps to switch the pipeline live, not code
blockers (INB-23; runbook `processes/mobile-release.md`).
Composes with **ADR 0012** (agent-operated build — but the gates here exceed those
rails: store accounts, signing keys, spend), **ADR 0026** (`com.sloopworks.dayfold`
app id), **ADR 0023/0027** (Firebase config the store build needs), **ADR 0021**
(auth — store builds must use real sign-in, never a baked household credential), and
**ADR 0031** (the build-with-Gradle / publish-with-a-CLI / secret-gated-tag pattern
this mirrors). Immutable once Accepted — supersede, do not edit.

## Context

CI builds and tests the API, CLI, KMP client core, and debug-drawer libraries
(`ci.yml`) but **never builds the Android app** and has **no app-publishing path** at
all. The operator's requirement: a 3-track release train — **every PR merge to `main`
auto-publishes an "alpha", and "beta"/"prod" are cut by pushing version tags** — using
best practices.

Constraints that shaped the design (grounded in the repo):
- The Android module had **no signing config, no `buildTypes` block, and a hardcoded
  `versionCode = 1`** — so `bundleRelease` produced an *unsigned* AAB with a fixed
  code. Google Play requires a **signed AAB** with a **strictly monotonic versionCode**.
- The build toolchain is **AGP 9.2.1 / Gradle 9.4.1** (the dev-loop doc comment was
  stale). The popular `gradle-play-publisher` plugin's AGP-9 support is unproven →
  adding it risks breaking the *core build*, which CI must keep green.
- The repo's release security posture (`release-cli.yml`) deliberately uses **only
  official, SHA-pinned actions + plain `run:` CLI steps (no third-party actions)**,
  strictly validates untrusted tags, and **gates every external step on its secret**
  so the workflow can land before one-time setup.
- `google-services.json` is **gitignored** and the only local copy is a **stub**; the
  `com.google.gms.google-services` plugin fails the build if the file is absent.
- Store builds must **not** bake `HOUSEHOLD_SECRET` (one family's credential) or
  `DEV_AUTH_SECRET` (server refuses it in prod) into the APK.
- iOS has **no Xcode/Swift host project yet** (only the KMP framework compiles), so an
  iOS pipeline can't exist until that host is built.

## Decision

1. **Track mapping** (Play Developer API track names):
   | Stage | Trigger | Play track | Release status |
   |---|---|---|---|
   | **alpha** | push to `main` (PR merge) | `internal` | `completed` (≤100 testers, no review, seconds-to-minutes) |
   | **beta** | tag `android-beta-v<semver>` | `beta` | `completed` |
   | **prod** | tag `android-v<semver>` | `production` | **`draft`** |
   Production uploads as a **draft**: CI delivers the artifact to Play, but the
   externally-visible roll-out is a **human click in the Play Console** — preserving
   "agents draft; the operator sends" (CLAUDE.md external-action guardrail).

2. **Tag scheme — `android-v*` / `android-beta-v*`** (non-colliding with the existing
   `cli-v*`). Tags are untrusted → strictly validated `^[0-9]+\.[0-9]+\.[0-9]+$`.

3. **versionCode = `GITHUB_RUN_NUMBER + 1000`** (monotonic across all tracks, above any
   prior manual upload); **versionName** = the tag's semver (or `0.0.0-alpha.<run>` for
   merge builds). Both injected via env, with the M0 defaults preserved for local dev.

4. **Signing** is **env/Gradle-property driven** in `:androidApp/build.gradle.kts`,
   guarded so that **absent keystore env → release stays unsigned** (local
   `bundleRelease` + the debug dev loop keep working with no secrets). CI signs with the
   **upload key**; **Play App Signing** holds the real app key.

5. **Build with Gradle, publish with a CLI** (same split as the CLI release). The signed
   AAB comes from `./gradlew :androidApp:bundleRelease`; the upload uses **fastlane
   `supply`** invoked in a `run:` step — **not** `gradle-play-publisher` (AGP-9 plugin
   risk to the core build) and **not** a third-party GitHub Action (repo posture).
   Publish is gated on the AAB being signed **and** `PLAY_SERVICE_ACCOUNT_JSON` present.

6. **Rebuild-from-tag** (not promote-the-artifact) for beta/prod, for determinism and
   simplicity at M0. Play App Signing re-signs server-side, so a freshly-built AAB with a
   new versionCode is a valid release. *Promote-the-exact-alpha-artifact* (saves a build,
   guarantees "what was tested is what ships") is recorded as a future optimization (G6).

7. **A stub `google-services.json` fallback** lets the pipeline (and a new PR
   `assembleDebug` smoke job) run green before the real Firebase config exists; a
   `::warning::` flags that Google sign-in is non-functional with the stub.

8. **R8/minify stays OFF** (current behavior); enabling it needs vetted keep-rules and is
   a separate task (G7).

## Operator gates (cannot be agent-completed — secrets / accounts / spend / external)

- **G1 — Upload keystore.** Generate it; opt into **Play App Signing** (so this is the
  *upload* key, resettable if leaked). Add secrets `ANDROID_KEYSTORE_BASE64`,
  `DAYFOLD_KEYSTORE_PASSWORD`, `DAYFOLD_KEY_ALIAS`, `DAYFOLD_KEY_PASSWORD`.
- **G2 — Real Firebase config.** Create the Firebase Android app for
  `com.sloopworks.dayfold`; add `GOOGLE_SERVICES_JSON_BASE64`. Until then store builds
  compile (stub) but Google sign-in is dead.
- **G3 — Play Console + service account** ($25 one-time; **spend**). Create the app
  listing; mint a least-privilege service-account JSON (app-scoped release permission);
  add `PLAY_SERVICE_ACCOUNT_JSON`. **The first AAB per app must be uploaded manually**
  before the API can publish.
- **G4 — Store listing + data-safety form** (external-facing; intersects the
  children's-data / restricted-scope guardrails — counsel-adjacent).
- **G5 — API-environment + auth decision.** All three tracks currently point
  `DAYFOLD_API` at the prod Vercel API (no staging exists) and rely on **real
  sign-in (AUTH-S3), never a baked `HOUSEHOLD_SECRET`**. Confirm before first upload.

## Gaps / follow-on tasks (agent-buildable later)

- **G6** — Promote-the-artifact instead of rebuild-from-tag (track-to-track promotion).
- **G7** — Enable R8/resource-shrink with vetted keep-rules (redux/Firebase/Compose).
- **G8** — iOS pipeline — **blocked on building the Xcode/Swift host first** (separate
  task); then fastlane `match` + `pilot`/`deliver` via App Store Connect API key on a
  macOS runner. TestFlight-internal is the iOS "alpha" (no merge-time auto-publish).
- **G9** — First-CI-run validation of the runner Android-SDK setup (platform `android-37`
  vs `android-37.0`) — the build step is unverifiable until it runs on a GitHub runner.

## Consequences

- A PR merge produces a signed AAB on the `internal` track within minutes; tags promote
  to beta/prod; prod waits for the operator's roll-out click. The pipeline is **inert and
  green** until G1–G3 are done, so it lands safely now.
- New maintenance surface: one workflow + a signing/versioning block in the Android
  build. The Gradle changes are backward-compatible (validated locally: signed AAB with
  env, unsigned without).
- The `internal` track auto-publish is the one externally-reachable automation; it reaches
  only the operator's own ≤100 testers (not the public), so it needs no per-release gate —
  but the **production** path stays human-gated by the draft status.
