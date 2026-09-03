# Mobile release — dev, alpha, beta, production

Dayfold's Android and iOS release train is defined by ADR 0066 and the two
`release-*.yml` workflows. A public release is always an operator action: CI may
upload a production candidate, but it does not roll out Play production or submit
an App Store version for review.

## Channel map

| Channel | Android | iOS | Trigger |
|---|---|---|---|
| dev | signed CI AAB artifact; no upload | unsigned simulator `.app` archive | manual workflow dispatch |
| alpha | Play internal testing | TestFlight build for internal testing | merge to `main` on Android; `ios-alpha-vX.Y.Z` or manual on iOS |
| beta | Play closed testing (API track `alpha`) | TestFlight build for external testing | `android-beta-vX.Y.Z` / `ios-beta-vX.Y.Z` or manual |
| production | Play production draft | App Store Connect build only | `android-vX.Y.Z` / `ios-vX.Y.Z` or manual |

Manual dispatch accepts `dev`, `alpha`, `beta`, or `production` in both workflows.
Beta and production versions must be `MAJOR.MINOR.PATCH`. Android's monotonic
`versionCode` is `GITHUB_RUN_NUMBER + 1000`; iOS's `CFBundleVersion` is
`GITHUB_RUN_NUMBER + 2000`, above the validated TestFlight bootstrap build 1001.

## Account and app records

- Google Play developer: Patrick Jackson 52 (`6592419883867360357`)
- Play app: Dayfold (`com.sloopworks.dayfold`, Play app `4973429963864753978`)
- Apple team: SloopWorks LLC (`2XAXFD3872`)
- App Store Connect app: Dayfold Family Briefing (`6803282435`)
- Bundle/App ID: `com.sloopworks.dayfold`
- Production API and policy origin: `https://family-ai-dashboard.vercel.app`

Play uses app-scoped automation through
`dayfold-play-publisher@dayfold-app.iam.gserviceaccount.com`; App Store Connect
uses the App Manager API key recorded in the repository secrets. Apple Sign in,
the Firebase iOS app, the distribution certificate, and the App Store provisioning
profile are configured. The App ID and production AASA file are ready for universal
links, but the associated-domains entitlement remains intentionally deferred by
ADR 0048.

## GitHub secrets

Android:

- `ANDROID_KEYSTORE_BASE64`
- `DAYFOLD_KEYSTORE_PASSWORD`
- `DAYFOLD_KEY_ALIAS`
- `DAYFOLD_KEY_PASSWORD`
- `GOOGLE_SERVICES_JSON_BASE64`
- `PLAY_SERVICE_ACCOUNT_JSON`

iOS:

- `GOOGLE_SERVICE_INFO_PLIST_BASE64`
- `IOS_DISTRIBUTION_CERTIFICATE_BASE64`
- `IOS_DISTRIBUTION_CERTIFICATE_PASSWORD`
- `IOS_PROVISIONING_PROFILE_BASE64`
- `ASC_KEY_ID`
- `ASC_ISSUER_ID`
- `ASC_PRIVATE_KEY`
- `APPLE_SIGN_IN_KEY_P8`
- `APPLE_SIGN_IN_KEY_ID`
- `APPLE_TEAM_ID`

`DEBUGDRAWER_REPO_TOKEN` remains the private-package/submodule credential shared
with the normal mobile build. Workflows decode credentials only inside runner
temporary storage and delete them during cleanup.

## Local release checks

Android upload signing material lives outside the repository at
`/Users/patrick/keys/dayfold-upload.jks`; its password is in the macOS Keychain
under service `com.sloopworks.dayfold.upload.keystore`, account `dayfold`.

```sh
cd apps
./gradlew :client:desktopTest :ui:desktopTest :androidApp:lintRelease
./gradlew :androidApp:bundleRelease
```

The release AAB is
`apps/androidApp/build/outputs/bundle/release/dayfold-android-release.aab`.
Before uploading, CI verifies both the bundle signature and the pinned Dayfold
upload-certificate SHA-256 fingerprint.

For iOS, install XcodeGen, the Apple Distribution identity, and the `Dayfold App
Store CI` provisioning profile, then:

```sh
cd apps/iosApp
xcodegen generate
xcodebuild -project iosApp.xcodeproj -scheme iosApp -configuration Release \
  -destination 'generic/platform=iOS' -archivePath /tmp/Dayfold.xcarchive archive
xcodebuild -exportArchive -archivePath /tmp/Dayfold.xcarchive \
  -exportPath /tmp/Dayfold-export -exportOptionsPlist ExportOptions.plist
```

The Xcode project is generated from `project.yml`; do not hand-edit it. The
release workflow performs the same archive/export and uploads with the App Store
Connect API key. Before uploading, it opens the exported IPA and verifies the
bundle/version identity, arm64 payload, distribution signature and profile,
production `get-task-allow=false`, Sign in with Apple entitlement, and packaged
privacy manifest. `scripts/asc-testflight.mjs` waits for Apple processing and
assigns alpha to `Dayfold Internal` or beta to `Dayfold Beta`; production remains
unassigned. Its read-only `audit-app` command reports the live version metadata,
screenshot counts, and testing groups; `audit-build <marketing-version>
<build-number>` reports processing state and the groups to which that exact build
is assigned.

## Store source of truth

- `store/android/metadata/android/en-US/` — Play copy, icon, feature graphic,
  screenshots, and release notes (fastlane `supply` layout)
- `store/apple/metadata/` and `store/apple/fastlane-screenshots/en-US/` —
  fastlane-ready App Store copy and required iPhone/iPad screenshots (the
  dimension-named sibling directories preserve the generation inputs)
- `store/compliance.yml` — objective inputs for Play Data safety and Apple App
  Privacy; it is not an operator attestation
- `store/apple/app_privacy_details.json` — reviewable Fastlane-format translation
  of those Apple privacy inputs; the operator must approve it before upload/publish
- `store/android/app-access.md` and `store/apple/review-notes.md` — reviewer
  navigation instructions with no shared credentials
- `apps/iosApp/Resources/PrivacyInfo.xcprivacy` — first-party collection and
  required-reason API declarations packaged into the iOS app
- `fastlane/Deliverfile` — repeatable metadata/screenshot upload configuration;
  it never uploads a binary or submits for review
- `designs/brand/dayfold-mark.svg` — approved source mark
- `scripts/generate-store-assets.swift` — deterministic app/store asset generator

Run the asset generator from the repository root whenever the approved mark or
palette changes, then visually inspect the regenerated icon, launch surfaces,
feature graphic, and screenshots.

With an App Store Connect API-key JSON path in `ASC_API_KEY_PATH`, run
`fastlane deliver` to refresh the listing without uploading a binary or submitting
for review. On a brand-new app record, Fastlane may report its upstream `No data`
review-detail edge case after the metadata is accepted; complete the screenshots
independently with `fastlane deliver --skip_metadata true`. The Deliverfile enables
Fastlane's checksum-based screenshot synchronization so a processing retry does not
create duplicate image records; `asc-testflight.mjs dedupe-screenshots` is the
idempotent repair command for a legacy duplicate set.

## Publishing procedure

1. Run the local checks and update both stores' release notes.
2. Ship alpha and exercise sign-in, account deletion, family creation/join, camera,
   optional calendar access, notifications, and optional background location.
3. Promote to beta only after the alpha smoke test passes. Use the Play closed
   tester list and the `Dayfold Beta` TestFlight external group.
4. Create the production candidates with the final tags. Verify that Play says
   `Draft` and that App Store Connect has not been submitted for review.
5. The operator reviews the policy answers, agreements, review notes, screenshots,
   phased/staged-release choice, and then performs the two public-release actions.

Never embed `FAMILY_ID`, `HOUSEHOLD_SECRET`, or `DEV_AUTH_SECRET` in a distributed
binary. Every store build targets the production API and uses Firebase-backed
Google/Apple sign-in.

Each non-dev Android run uploads the checked-in Play listing alongside the AAB;
the Play Console remains the authority for policy questionnaires and tester lists.
While the Play app record itself is still a draft, the publishing API may refuse
an active testing release. The workflow automatically retries that first upload as
a safe track draft; after the operator completes the Play setup gates, subsequent
alpha/beta uploads activate normally.

## Outstanding operator/time gates

- A personal Play account must run a closed test with at least 12 opted-in testers
  continuously for at least 14 days before production access can be requested.
  The current account cannot bypass that calendar gate.
- Tester email addresses are required to populate the Play closed list and the
  TestFlight external group; never invent them.
- The operator must personally attest Play Data safety, target audience/content
  rating, background-location use, and app access answers.
- The Account Holder must complete Apple's Digital Services Act trader status,
  agreements, tax/banking if applicable, App Privacy, age rating, export compliance,
  TestFlight beta review, and App Review declarations.

These gates do not prevent dev/internal builds. They do prevent truthful completion
of beta distribution or a public production release until the required people,
elapsed test period, and legal attestations exist.
