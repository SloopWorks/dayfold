# S2 — Google Sign-In (Firebase identity) — Design & Runbook

**Status:** Build in progress (2026-06-22). Implements ADR 0023 (Google+Apple,
Phone deferred) + ADR 0027 (direct JWKS verify, emulator-in-CI, Android-first),
on top of the ADR 0011 hardened auth architecture. Extends the S5 client
(`specs/auth-and-family-design.md`).

## 1. What S2 ships

Real **Google** sign-in on **Android**, replacing the dev-token stub behind the
existing "Continue with Google" button. Apple, provider-linking, iOS, and desktop
are explicitly deferred (§8). Everything downstream of identity — our EdDSA
access + rotating refresh, `whoami`, membership routing, per-device revoke — is
unchanged from S5.

## 2. Architecture — verify-then-mint (the seam)

The auth stack already separates **identity proof** from **token minting**:

```
Android: Credential Manager + Google  ──▶  Firebase ID token (RS256 JWT)
                                              │  POST /auth/firebase {idToken}
                                              ▼
backend FirebaseVerifier (JWKS)  ─▶  Identity{provider, provider_uid}
   findOrCreateUser  ─▶  mintCredentialFor  ─▶  mintAccess (EdDSA) + issueRefresh
                                              │  { access, refresh }
                                              ▼
                              client saves session → whoami → route
```

S2 only fills the **left half** (proving identity). The dev-token path
(`/auth/dev-token`, gated off prod/preview) is retained as the local/test seam.

## 3. Backend — `POST /auth/firebase`  *(BUILT)*

Request: `{ "idToken": "<Firebase ID token>" }` · no bearer (the ID token is the
proof). Responses: `200 {access, refresh}` · `400 missing-id-token` ·
`401 bad-identity` (bad/forged/expired/wrong-aud) · `503 auth-unconfigured`
(no `FIREBASE_PROJECT_ID`). Always-on (production included).

**`FirebaseVerifier`** (`apps/api/src/auth/identity.ts`):
- Verifies the token's **RS256 signature** against Google's securetoken JWKS via
  `jose` (no Admin SDK, no service-account secret). Checks
  `iss = https://securetoken.google.com/<projectId>`, `aud = <projectId>`, `exp`.
- **Identity binding (ADR 0011):** `provider = firebase.sign_in_provider`
  (`google.com`); `provider_uid = firebase.identities[provider][0]` (the Google
  account id), falling back to `sub` only if absent. Never the Firebase uid —
  we do our own linking, keyed on unique `(provider, provider_uid)`.
- **Emulator mode** (`FIREBASE_AUTH_EMULATOR_HOST` set): emulator tokens are
  unsigned (`alg:none`); decode-and-validate claims without signature check.
  **Security gate:** honored only when `VERCEL_ENV ∉ {production, preview}`.

## 4. Client — `firebaseToken` + `FirebaseSignIn` seam  *(commonMain BUILT; Android impl REMAINING)*

- `AuthClient.firebaseToken(idToken): Session` — POST /auth/firebase. *(built)*
- `fun interface FirebaseSignIn { suspend fun idToken(provider): String? }` — the
  platform seam. *(built)*
- `AuthEngine.signIn(provider)` prefers a Firebase token when the seam yields one,
  else falls back to dev-token, else fails closed. *(built)*
- **Remaining — Android `FirebaseSignIn` impl** (`androidMain`): use **Credential
  Manager** + `GetGoogleIdOption` (Web client id from Firebase) to get a Google ID
  token, exchange via Firebase Auth (`signInWithCredential`) for a **Firebase** ID
  token, return it. Wire it into `MainActivity` (replaces the `null` default).
  Needs the deps + `google-services.json` from §6 → blocked on the console steps.

## 5. Test topology (ADR 0027)

| Lane | Identity path | Status |
|---|---|---|
| API unit (`auth-firebase.test.ts`) | signed RS256 (DI JWKS) + fake emulator decode | ✅ 12 tests |
| API integration (`auth-firebase-emulator.test.ts`) | **real Firebase Auth Emulator** token → `/auth/firebase` | ✅ CI `firebase-emulator` job (skipIf-gated) |
| Client unit (`AuthClientTest`/`AuthEngineTest`) | MockEngine — firebaseToken shape, firebase-preferred + dev-fallback | ✅ 4 tests |
| Existing dev-token / MockEngine suite | dev-token seam | ✅ unchanged |
| On-device E2E | manual Google sign-in (after console config) | ⬜ operator |
| Preview deploy | real Google test users (dev-token is 404) | ⬜ manual |

## 6. Operator console runbook *(operator-gated — ADR 0012 login; guardrail #3 free Spark tier)*

1. **Create the Firebase project** (or reuse). Note the **project id** → this is
   `FIREBASE_PROJECT_ID` and the token `aud`/`iss`. Stay on the **Spark (free)**
   tier — no Blaze (ADR 0023).
2. **Authentication → Sign-in method → enable Google.** Set the project support
   email. This auto-creates an OAuth **Web client id** (used by Credential Manager).
3. **Add an Android app** under applicationId **`com.sloopworks.dayfold`**
   (ADR 0026). Add signing **SHA-1** fingerprints:
   - debug keystore SHA-1 (local dev), and
   - the **Play App Signing** SHA-1 (from Play Console → Setup → App signing) for
     release — Google re-signs, so the local release SHA-1 is *not* the one.
4. Download **`google-services.json`** → `apps/androidApp/`. (Client config, not a
   secret, but project-identifying — confirm commit policy before checking in.)
5. **Vercel env** (Production + Preview): set `FIREBASE_PROJECT_ID=<project id>`.
   Nothing else — JWKS verify needs no service-account secret. **Never** set
   `FIREBASE_AUTH_EMULATOR_HOST` in Vercel.

## 7. Config / env reference

| Var | Where | Purpose |
|---|---|---|
| `FIREBASE_PROJECT_ID` | Vercel + CI | token `aud`/`iss`; absent → `/auth/firebase` 503 |
| `FIREBASE_JWKS_URI` | optional | override Google's default JWKS URL |
| `FIREBASE_AUTH_EMULATOR_HOST` | CI emulator job only | enables decode-only emulator mode; refused in prod/preview |
| `DEV_AUTH_SECRET` / `ENABLE_DEV_AUTH` | local/test | dev-token seam (unchanged) |
| `google-services.json` | `apps/androidApp/` | Android Firebase/Google config |

## 8. Deferred (out of S2)

- **Apple sign-in** — provider binding (`apple.com` / `sub`) + `revokeToken` +
  account-deletion (ADR 0011/0023). Verifier already generalizes to any provider.
- **Provider linking** (`account-exists-with-different-credential`, owner ≥2
  methods, ADR 0011) — Google-only slice = one provider, no linking yet.
- **iOS** real sign-in — needs the iOS app project + bundle id + its own OAuth
  client + `GoogleService-Info.plist`.
- **Desktop** — keeps dev-token; a loopback OAuth flow is a later ergonomics task.
- **Phone-OTP** — ADR 0023 (Blaze + SMS fraud surface).

## 9. Status checklist

- [x] ADR 0027 (verify mechanism + test topology + target)
- [x] Backend `FirebaseVerifier` (JWKS + emulator mode + prod gate) + `/auth/firebase`
- [x] Backend tests (12 hermetic) + CI emulator integration job (3 gated)
- [x] Client `firebaseToken` + `FirebaseSignIn` seam + `AuthEngine` wiring + 4 tests
- [ ] Android Credential Manager `FirebaseSignIn` impl + `MainActivity` wiring *(blocked: §6)*
- [ ] Operator console steps (§6)
- [ ] On-device + preview manual QA
