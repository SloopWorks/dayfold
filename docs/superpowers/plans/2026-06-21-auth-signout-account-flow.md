# Complete sign-in/out + account flow + e2e on emulator — plan

Goal (operator, /loop): a **complete sign-in / sign-out / account-creation flow
built and verified with e2e tests on the Android emulator.** Branch
`auth-signout-account` off `main`. Toolchain: `processes/agent-dev-loop.md`.

## Where slice-1 left it (merged, PR #6)
Sign-in (dev-token stub) → create-family → route-gated Feed all work; `AuthEngine`
(restore/signIn/createFamily/**signOut**/401-refresh) + reducer (incl. `SignedOut`)
are done and unit-tested. **Gaps to "complete":** (1) no UI to reach sign-out;
(2) no account/profile surface (designed now — Settings-Phone `profile`/`signin`);
(3) **no e2e/instrumented test harness** (`apps/androidApp/src/androidTest` absent).

## Design inputs
- `Settings-Phone.dc.html` (merged PR #9): `profile` (display name, monogram,
  role, methods, **sign out**, delete), `signin` methods (Google/Apple ✓, phone
  designed-not-built per ADR 0023), `family-owner/adult`.
- ADR 0011/0021/0023; ADR 0013 (`f(state)→UI`, no nav lib); ADR 0020 (DB-as-SoT).

## Slices (each: design→review→implement→verify; cron advances one per fire-ish)

### Slice A — Sign-out + Account screen  ← START HERE
- `Route.Account`; `AppState` already minimal — reuse. Feed `TopAppBar` gets an
  account action (monogram/avatar) → `Account` route.
- `AccountScreen` (Dayfold, from `profile` design): display name + monogram, role,
  sign-in methods (Google/Apple linked; phone "available later", disabled),
  **Sign out** (→ `onSignOut`), Delete account (→ deferred to Slice D / S6 design).
- Wire `onSignOut`/nav through `FeedApp` + the 3 entrypoints (desktop/Android/iOS)
  → `authEngine.signOut()`.
- Reducer: an `OpenAccount`/`CloseAccount` nav (or fold into the existing
  `detailStack` pattern — prefer a small `Route` addition for a full-screen).
- **Verify:** reducer tests (nav + already-tested SignedOut), `AccountScreen`
  snapshots (light/dark), desktopTest green (counted).

### Slice B — e2e harness on the emulator
- Add `apps/androidApp/src/androidTest` + `androidTestImplementation` (compose-ui-test-junit4,
  espresso-core, test-runner/rules). `connectedAndroidTest` task.
- **Approach (decide at build):** prefer a **hermetic UI e2e** — launch
  `MainActivity` with the AuthEngine pointed at a **fake AuthClient/in-memory
  TokenStore** (no network), driving the real route gate + screens:
  SignIn → tap provider → CreateFamily → type name → Feed → Account → Sign out →
  SignIn. Avoids a live API on the emulator (10.0.2.2 + DEV_AUTH_SECRET) for CI
  determinism. A **full live e2e** (local API + dev-token) is a second, gated test.
  - Seam: MainActivity reads its AuthEngine deps from an overridable provider so
    the test injects fakes (small refactor — a `Dependencies` holder).
- **Verify:** `./gradlew :androidApp:connectedDebugAndroidTest` green on
  `emulator-5554`; capture a screencap of the final SignIn state.

### Slice C — Account polish + sign-in methods
- Editable display name (PATCH /me? — check API; else local-only display at M0),
  monogram color, the sign-in-methods detail (link 2nd provider entry → S5
  slice-2/S2). Snapshots.

### Slice D — (gated) destructive + invitee
- Delete-account confirm + last-owner transfer (designs exist: `deleteconfirm`/
  `transferowner`) and invitee-join (slice-2) — larger, separate; not required
  for the core sign-in/out/account goal.

## Risks / notes
- **e2e on emulator is the new capability** — Slice B is the load-bearing,
  highest-uncertainty piece (Gradle instrumented-test config + emulator run).
  Hermetic (fake engine) keeps it deterministic; live API e2e is the stretch.
- redux-kotlin alpha01 gotchas + JDK17 + `:androidApp:connectedDebugAndroidTest`
  (ANDROID_HOME + JAVA_HOME) — per agent-dev-loop.
- Keep `Route` small (ADR 0013); don't add a nav library.
- Sign-out already fully reduced+engine-tested — Slice A is mostly UI + wiring.

## DoD (overall)
Cold-start → dev sign-in → create family → Feed → open Account → Sign out →
back to SignIn, all on the **emulator** under an instrumented test that passes in
`connectedDebugAndroidTest`; desktopTest stays green; screens match the Dayfold
designs; reviewed for security (no token leakage, dev-secret never shipped),
xplat (3 shells compile), simplicity.
