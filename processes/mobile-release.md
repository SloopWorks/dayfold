# Mobile Release — Alpha/Beta/Prod to Google Play (ADR 0034)

How the Android app ships to testers and users, and how to cut a release.

```
merge a PR to main          → builds + publishes to Play INTERNAL (alpha)
git tag android-beta-v1.2.0 → publishes to Play BETA
git tag android-v1.2.0      → publishes to Play PRODUCTION as a DRAFT (you roll it out)
git push origin <tag>
```

The pipeline (`.github/workflows/release-android.yml`) is **inert until the one-time
operator gates below are done**: with no secrets it builds an *unsigned* AAB and skips
the Play upload, so it stays green. Each external step is gated on its secret being
present (same posture as the CLI release, `processes/cli-release.md`).

## How it works

- **Supported Android versions:** **minSdk 33** (Android 13) → the app installs on
  Android 13+ devices; **compileSdk / targetSdk 37**. minSdk is a plain build-config
  floor (`:androidApp` + `:client` + the `:debugdrawer*` modules) — not ADR-governed
  and carries no API-34-only code (dropping 34→33 needed zero source change, no `NewApi`
  lint). Lowering it further re-runs the same check: `assembleDebug` + `lintDebug` must
  stay clean (no `NewApi` / API-guard additions) or the change isn't free.
- **versionCode** = `GITHUB_RUN_NUMBER + 1000` (strictly monotonic across every track —
  Play requires it). **versionName** = the tag's semver (alpha builds get
  `0.0.0-alpha.<run>`). Local dev keeps the `1` / `0.0.0-M0` defaults.
- **Signing** is env/Gradle-property driven (`:androidApp/build.gradle.kts`): CI signs
  the AAB with the **upload key**; **Play App Signing** holds the real app key. No
  keystore env → unsigned (local builds still work).
- **Build vs publish are separate**: `./gradlew :androidApp:bundleRelease` makes the
  signed AAB; `fastlane supply` uploads it. Production uploads as a **draft** — the
  artifact reaches Play, but you click **roll out** in the Play Console (keeps go-live an
  operator action).

## One-time operator setup (the ADR 0034 gates)

These need a human (keystore, accounts, spend, store listing) — not agent-buildable:

1. **G1 — Upload keystore.** Generate it and **opt into Play App Signing** so this is the
   *upload* key (resettable if leaked):
   ```
   keytool -genkeypair -v -keystore upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 9125
   openssl base64 -A < upload.jks   # → ANDROID_KEYSTORE_BASE64
   ```
   Add repo **secrets**: `ANDROID_KEYSTORE_BASE64`, `DAYFOLD_KEYSTORE_PASSWORD`,
   `DAYFOLD_KEY_ALIAS`, `DAYFOLD_KEY_PASSWORD`.
2. **G2 — Real `google-services.json`.** Create the Firebase Android app for
   `com.sloopworks.dayfold` (ADR 0023/0027); `openssl base64 -A < google-services.json`
   → secret **`GOOGLE_SERVICES_JSON_BASE64`**. Until then store builds compile with a
   stub but **Google sign-in does not work**.
3. **G3 — Play Console + service account** ($25 one-time — **spend**). Create the app
   listing; in Google Cloud mint a service-account JSON; in Play Console → Users &
   permissions grant it **app-scoped release** permission (not account admin). Secret
   **`PLAY_SERVICE_ACCOUNT_JSON`** (full JSON). **The very first AAB per app must be
   uploaded by hand** in the Console before the API can publish.
4. **G4 — Store listing + data-safety form.** Icon, screenshots, description, privacy
   policy URL, data-safety answers. External-facing; the data-safety form intersects the
   CLAUDE.md children's-data / restricted-scope guardrails — review carefully.
5. **G5 — Confirm the API target + auth path.** All three tracks currently build with
   `DAYFOLD_API=https://family-ai-dashboard.vercel.app` (no staging API exists) and rely
   on **real sign-in (AUTH-S3)**. `FAMILY_ID` / `HOUSEHOLD_SECRET` / `DEV_AUTH_SECRET` are
   left **unset** — never bake a household credential or the dev-token secret into a
   store build. Confirm before the first upload.

Until G1+G3 are set, merges/tags still run the workflow (build + AAB), they just skip the
upload. Order to go live: G1 → G2 → G3 (manual first upload) → then merges auto-ship to
`internal`.

## Cutting a release

- **Alpha:** nothing to do — every merge to `main` publishes to `internal`.
- **Beta:** `git tag android-beta-v1.2.0 && git push origin android-beta-v1.2.0`.
- **Prod:** `git tag android-v1.2.0 && git push origin android-v1.2.0`, then open the
  Play Console and **roll out** the drafted production release (optionally as a staged %).

Restrict who can push `android-*` tags (operator), like `cli-v*`.

## Known follow-ups (ADR 0034 gaps)

- **G6** promote-the-tested-artifact instead of rebuild-from-tag · **G7** enable R8 with
  vetted keep-rules · **G8** iOS release pipeline (the Xcode/Swift host itself already
  exists, sim-verified — see below; G8 needs fastlane `match`+`pilot` → TestFlight) ·
  **G9** first-CI-run check of the runner SDK platform name (`android-37` vs
  `android-37.0`).

## iOS (not yet — G8)

The Xcode/Swift host (`apps/iosApp`, SwiftUI/xcodegen) has existed and been sim-verified
since 2026-07-01 — it is not the gap. What's missing is the **release pipeline**:
TestFlight-internal as the iOS "alpha" (no merge-time auto-publish — Apple processing/
review latency); fastlane `match` (signing) + `pilot` (TestFlight) / `deliver` (App
Store) authenticated by an **App Store Connect API key** (`.p8` + issuer/key ids), on a
**macOS runner** (~10× the minute cost). Tags would drive it: `android-beta-v*`-equivalent
→ TestFlight, final tag → App Store submit (Apple review + operator submit). Also blocked
on the operator's Mac + an Apple Developer account ($99/yr spend). Tracked as backlog
tasks (`TASK-ios-pipeline`, `backlog/next.md`).
