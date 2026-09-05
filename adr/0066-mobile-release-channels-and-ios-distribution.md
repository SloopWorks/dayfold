# ADR 0066 — Mobile release channels and iOS distribution

**Status:** Accepted 2026-08-19 (operator-directed store setup)

## Decision

Dayfold uses four named lifecycle channels on both mobile platforms:

| Channel | Android | iOS | External effect |
|---|---|---|---|
| dev | CI artifact | unsigned simulator archive | downloadable build only |
| alpha | Play internal testing | TestFlight internal testing | approved internal testers |
| beta | Play closed testing | TestFlight external testing | approved external testers |
| production | Play production draft | App Store version/build prepared for submission | no public release until the operator submits/rolls out |

Android keeps ADR 0034's accepted trigger contract: `main` publishes alpha,
`android-beta-vX.Y.Z` publishes beta, and `android-vX.Y.Z` creates the production
draft. A manual workflow run supplies the dev artifact lane.

iOS uses manual workflow dispatch for every channel and also accepts
`ios-alpha-vX.Y.Z`, `ios-beta-vX.Y.Z`, and `ios-vX.Y.Z` tags. Alpha/beta upload
the signed archive to App Store Connect and assign it to the matching TestFlight
group; submitting an external build for beta review remains operator-visible.
Production uploads the build but does not submit it for App Review or schedule
release.

GitHub Actions stores the platform credentials. Workflows materialize them only
inside the runner's temporary directory and remove them at job completion.
Distributed builds use the production API and never embed household or developer
authentication secrets.

## Rationale

The channel names make the requested lifecycle explicit without weakening ADR
0034's safe production-draft boundary. Apple processing, export-compliance
answers, TestFlight review, App Review attestations, and public rollout are
externally visible or legally meaningful actions, so automation prepares the
artifacts and records while the operator retains the final action.

## Consequences

- Play production access for a personal developer account remains gated by
  Google's closed-test eligibility period.
- App Store Connect agreements, trader status, privacy answers, export compliance,
  and review declarations remain operator attestations even when the technical
  forms and metadata are otherwise complete.
- Release automation may upload builds, but never performs public rollout.
