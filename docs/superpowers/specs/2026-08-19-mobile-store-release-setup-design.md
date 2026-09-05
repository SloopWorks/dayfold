# Mobile store release setup — implementation design

## Outcome

Dayfold can produce and distribute dev, alpha, beta, and production-candidate
builds for Google Play and App Store Connect. Store identities, signing, CI,
authentication, account deletion, listing assets, and operator handoffs are
treated as one release surface.

## Release topology

The channel mapping is defined by ADR 0066. Android extends the existing ADR
0034 workflow with a manual dev lane and uploads listing metadata separately
from binaries. iOS gains a macOS workflow that builds the KMP release framework,
generates the Xcode project, restores signing material into a temporary keychain,
archives an IPA, and uploads non-dev builds with the App Store Connect API key.
All public releases stay drafts.

Version names come from validated semantic-version tags. Version codes/build
numbers use the GitHub run number so every submitted binary is monotonic.

## Native iOS host

The iOS host configures Firebase and links FirebaseAuth plus GoogleSignIn. It
implements Google and Apple sign-in natively, returns only a Firebase ID token to
the shared KMP command layer, and uses Apple's one-time authorization code before
account deletion so Firebase can revoke the Apple token as required by App Store
Review. The shared client remains the source of session state and performs the
Dayfold account deletion request after native reauthentication/revocation.

The Xcode project has distinct Debug and Release framework paths/tasks, real
release signing settings, the Firebase plist, the Apple Sign In entitlement,
URL callback schemes, and a production API base URL. Universal-link
handling remains deferred under ADR 0048; the developer App ID and AASA are ready,
but the app does not claim the associated-domains entitlement until it can handle
those links end to end.

## Account deletion

Account Settings presents a destructive confirmation dialog. On confirmation,
the platform prepares provider-specific revocation if necessary and then invokes
the shared command. The shared auth engine calls `DELETE /auth/me`; success clears
tokens, cached identity, tenant-scoped runtime state, and returns to sign-in. A
409 transfer-required response remains visible to the user and does not clear the
session.

## Assets

The approved Dayfold turned-corner card is the sole mark. Claude Design's store
asset specification is applied mechanically:

- iOS and Play listing icons use an opaque, square, full-bleed coral gradient
  canvas with the cream fold and shadow; platform masks supply corner rounding.
- Android uses a warm adaptive background and keeps the complete card inside the
  guaranteed safe zone, with a one-color notched silhouette for themed icons.
- Native launch screens use the Dayfold light/dark surface colors and hand off to
  the existing Compose splash. They contain no text or promotional content.

Screenshots are generated only from real app UI states and are kept distinct from
the icon artwork.

## Metadata and compliance

Versioned store metadata lives under `store/`. It includes descriptions,
keywords, support/privacy URLs, category, review notes, release notes, and a
source-of-truth questionnaire documenting data collection and permissions.
Privacy and support pages are hosted by the existing production web app so store
URLs remain stable.

The automation may populate objective metadata and create draft releases. It
must not attest to legal agreements, trader status, export compliance, data
safety, content ratings, privacy labels, beta review, App Review, or public
rollout on the operator's behalf.

## Verification

- Android: unit tests, lint, signed release bundle, bundle inspection, launcher
  and splash resource checks.
- iOS: shared tests, simulator build, signed archive/export, entitlements and
  provisioning inspection, icon alpha/dimension checks.
- CI: workflow syntax plus a dry derivation test for every trigger/channel.
- Stores: uploaded build visible on the intended non-public track, listing assets
  accepted, and all remaining operator gates recorded explicitly.
