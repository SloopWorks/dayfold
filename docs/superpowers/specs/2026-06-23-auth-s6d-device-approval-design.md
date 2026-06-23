# Auth S6-D — CLI device approval (mobile UI + QR + deep-link) design

**Date:** 2026-06-23 · **Branch:** `claude/cli-login-flow-review-aq9lp0` ·
**ADR:** 0021 (S6 slice), 0011 §6/7, 0008 (design-first). Implements
`auth-and-family-design §Flow 3b` + `specs/prototype/07-cli.md §M1` + the A8b
mockup views `authorizedevice` / `entercode` / `devicedenied` / `deviceexpired`.
Builds on the **shipped S3 device-grant backbone** (`/device/authorize`,
`/device/token`, `/families/:fid/device/{approve,deny}`, `device_authorizations`,
`rate_limits`, `audit_log`) and the **S5/S6 client** (route gate, `AuthEngine`,
`AuthClient`, `DevicesScreen`).

## Goal & scope

Close the operator's login loop end-to-end: `dayfold login` on a computer →
**owner approves on the phone** → CLI authenticated. S3 shipped the API grant +
a text-only CLI; the **mobile approval UI never existed** (`DevicesScreen` only
lists/revokes). This slice builds that UI, renders a QR in the terminal, adds the
one read endpoint the approval screen needs, and (operator-chosen 2026-06-23)
ships the **in-app QR scanner + a real deep-link** so a phone can scan the
terminal QR and land directly on the approve screen.

**In scope:**
- API: `GET /device/pending?user_code=` owner-authenticated lookup (the preview
  the approve screen renders). Approve/deny reused unchanged.
- Client: `EnterCode`, `AuthorizeDevice`, `DeviceDenied`, `DeviceExpired`
  screens + routes/actions/engine/client wiring; entry from `FamilyNullState` +
  `DevicesScreen`.
- Client: **in-app QR scanner** (`expect/actual`; Android CameraX+ML Kit, iOS
  AVFoundation, desktop = unsupported → enter-code only).
- **Deep-link** on the **existing API origin** (no new domain): App Links
  (`assetlinks.json`) + Universal Links (AASA) for `/device?user_code=` → opens
  the app → `AuthorizeDevice` (or sign-in-then-resume).
- CLI: render a QR of `verification_uri_complete` beside the text `user_code`.
- **Datacenter-origin anti-phishing (no-vendor heuristic)** — `GET /device/pending`
  classifies `origin_ip` as `datacenter | residential | unknown` from a **bundled
  cloud/datacenter CIDR list** (no geoip vendor, no recurring cost), and the
  `AuthorizeDevice` screen warns on a datacenter origin ("this request comes from a
  datacenter — only approve if you started it"). Satisfies the intent of ADR 0011 §7
  without the deferred precise geo/ASN. *(Operator decision 2026-06-23.)*
- **CLI refresh-token in the OS keychain** — the long-lived (45-day) refresh token
  moves into the OS credential store; the access token + non-secret config may stay
  in `~/.config/dayfold/` (`0600`). Closes the plaintext-long-lived-secret gap
  (07-cli DX: "secrets in the OS keychain, never the config file").
- **Central scope gate (`requireScope`)** — read routes enforce `content:read`
  (today only writes are gated); every content route declares its required scope.
  Lands the enforcement half of ADR 0029; the per-hub grant *selection* rides with
  the content slice (below).

**Out of scope (deferred, with reason):**
- **Per-hub / resource scope SELECTION at approval** (ADR 0029, operator-chosen) —
  depends on the hub/content resource model, which lands in the **next content-API +
  CLI-verbs slice**; S6-D ships the **interim single global `content:read+write`
  grant** (today's behavior) with the scope row shown honestly as informational.
- **Precise geo/ASN enrichment** (vendor-backed city/ASN, block-list) — the
  no-vendor datacenter heuristic above covers the ADR 0011 §7 intent; precise
  enrichment stays operator-gated (cost) for a later slice.
- **E2E X25519 keypair + wrapped-FCK** (07-cli §M1, ADR 0015/0017) — separate M1
  epic; only the `"v":1` / additive-key hooks already exist.
- **CLI content read/write (hubs + pull/status/diff)** — separate content-API +
  CLI-verbs slice (planned next); S6-D closes the *login loop*, not content I/O.

## Decisions (incl. review findings, folded)

- **Preview-before-consent is mandatory** (RFC 8628 §5.4). The shipped approve is
  a *blind* `UPDATE … WHERE user_code` — it returns nothing to render. Add a
  read-only **`GET /device/pending`**. Approve/deny are reused as-is.
- **[C1] `GET /device/pending` is a `user_code` oracle.** Owner-gated (signed-in
  session required), **reuse the existing `account:approve:<sub>` rate-limiter**
  (lookup + approve are one abuse surface — **[S1]** no new key/table), **uniform
  404** on miss/expired, audit `device.lookup`. 34.6-bit code + owner-gate +
  rate-limit bounds enumeration.
- **[C2] Family selector lists owner-families only.** Approve requires
  `role==='owner'` (a member-family → 403). Filter `whoami.families` to
  `role=="owner"`; default to `activeFamilyId`. **[S2]** if exactly one
  owner-family, render a static row (no picker).
- **[C3] Lookup→approve race / expiry.** Approve returns uniform 404 on
  not-pending/expired → route to `DeviceExpired`. Deny → `DeviceDenied`.
- **Deep-link uses the existing API origin** — `verification_uri_complete`
  already is `<origin>/device?user_code=…`. Host `/.well-known/assetlinks.json`
  (Android) + `/.well-known/apple-app-site-association` (iOS) on Vercel; no new
  domain → **closes `OQ-deeplink-domain` without a spend/vendor decision**.
- **[C4] QR honesty.** With the scanner + deep-link in scope, scanning the
  terminal QR works two ways: in-app scanner parses the URL → code; OR the native
  camera opens the verified App/Universal Link → app. Enter-code stays the
  always-available fallback (desktop, link-unverified, cold install).
- **[S3] CLI QR = one small dep** (`zxing-core`) over a hand-rolled
  encoder (ECC + masking is bug-prone).
- **[S4] Two routes** (`EnterCode` → `AuthorizeDevice`), matching the one-screen-
  per-route house style + the mockup; not collapsed into a sub-state.

## API — `GET /device/pending?user_code=` (new)

Auth: a **signed-in session** (any `kind='app'` access token; not family-scoped —
the owner hasn't picked the family yet). Before lookup: `isLocked` +
atomic-increment the **`account:approve:<sub>`** counter (15-min window) — shared
with approve so a guess-then-approve loop is one budget; over cap → 429 + audit
`device.lockout`. Look up a `pending`, unexpired row by `user_code`; not
found/expired → **uniform 404** (`{type:"not-found"}`). On hit, audit
`device.lookup` and return:
```json
{ "user_code": "WDJF-7K2P", "client": "dayfold-cli",
  "origin_ip": "…", "origin_ua": "…", "origin_kind": "datacenter",
  "created_at": "…", "expires_at": "…" }
```
No `device_code`, no `user_id`, no credential. **`origin_kind`** ∈
`datacenter | residential | unknown` is computed from a **bundled cloud/datacenter
CIDR list** (no geoip vendor) — drives the anti-phishing warning per ADR 0011 §7.
(Precise geo/ASN fields stay deferred to a vendor-backed slice.)

**Approve/deny** unchanged: `POST /families/:fid/device/{approve,deny}` with
`{user_code}`, owner+`kind='app'`, family from PATH (anti-IDOR). The app sends the
selected `fid`.

## Client

**Routes** (`Model.kt`): add `EnterCode`, `AuthorizeDevice` to `Route`.

**State:** `val pendingDevice: PendingDevice? = null`, `val deviceBusy: Boolean`,
`val deviceError: String? = null` in `AppState`.

**Actions:** `OpenEnterCode`, `DeviceCodeEntered(code)`,
`DevicePendingLoaded(PendingDevice)`, `DeviceLookupFailed(reason)` (notfound →
`DeviceExpired`), `ApproveDeviceRequested(fid)`, `DenyDeviceRequested`,
`DeviceApproved`, `DeviceDeniedAck`. Reducer pure; transitions unit-tested
(incl. expired/denied/failed).

**AuthClient** (ktor, `AuthClient.kt`):
```kotlin
suspend fun devicePending(access: String, userCode: String): PendingDevice  // GET /device/pending; 404 → DeviceLookupNotFound
suspend fun deviceApprove(access: String, fid: String, userCode: String)     // POST …/device/approve; 404 → expired
suspend fun deviceDeny(access: String, fid: String, userCode: String)        // POST …/device/deny
@Serializable data class PendingDevice(val user_code, val client?, val origin_ip?, val origin_ua?, val origin_kind?, val created_at?, val expires_at?)
```

**AuthEngine** (`mutex` + `callWithRefresh`, mirrors `loadDevices`/`approveMember`):
`lookupDevice(code)`, `approveDevice(fid, code)`, `denyDevice(fid, code)`.

**Screens** (`DeviceApprovalScreens.kt`, pure composables, snapshot-tested vs the
mockup PNGs, light+dark): `EnterCodeScreen` (8-cell entry + scan affordance on
camera platforms), `AuthorizeDeviceScreen` (code-confirm, **`origin_kind`
datacenter warning banner**, scope row — informational interim per ADR 0029,
owner-family selector/static row, Deny/Approve, "only approve if you started
this"), `DeviceDeniedScreen`, `DeviceExpiredScreen`. Entry CTAs from
`FamilyNullState` ("Connect a device or CLI") and `DevicesScreen`.

**QR scanner** (`expect`):
```kotlin
@Composable expect fun QrScanner(onCode: (String) -> Unit, onCancel: () -> Unit)
expect val qrScanSupported: Boolean   // false on desktop → scan button hidden
```
- Android: CameraX `Preview`+`ImageAnalysis` + ML Kit `BarcodeScanning`; runtime
  CAMERA permission.
- iOS: `AVCaptureSession`+`AVCaptureMetadataOutput` via `UIKitView`.
- Desktop: `qrScanSupported=false`; composable throws/no-ops (never shown).
- Scanned payload = the `verification_uri_complete` URL → parse `user_code` query
  → `DeviceCodeEntered` → lookup. (Tolerate a bare code too.)

**Deep-link:** App Links (`autoVerify` intent-filter, host=API origin,
path `/device`) + Universal Links (associated-domains). On open with
`?user_code=`: signed-in → `lookupDevice` → `AuthorizeDevice`; not-signed-in →
stash the code, run sign-in/onboarding, **resume** to `AuthorizeDevice`. Static
`/device` page updated to instruct "open Dayfold / enter this code" for the
unverified-link / no-app case.

## CLI (`apps/cli`)

`zxing-core` dep; in `deviceLogin` (`Main.kt`), encode `verification_uri_complete`
to a QR and print it (Unicode half-block rows) **above** the existing text
`user_code` + URI (always keep the text — terminals/SSH may not render the QR).

## Design-first gate (ADR 0008)

`authorizedevice`/`entercode`/`devicedenied`/`deviceexpired` are signed off (A8b).
**The camera viewfinder/scan screen has NO mockup** → a quick hi-fi of the scan
overlay (viewfinder, permission-denied, manual-entry fallback link) must be added
to `designs/` + operator sign-off **before** the scanner actual is built. The
enter-code/approve loop is unblocked now.

## Testing

- **API (vitest):** `device/pending` hit / uniform-404 miss / 404 expired /
  shared rate-limit lockout / non-session 401 / audit row. Approve 404→expired,
  deny→denied still green.
- **Client:** reducer transitions (all, incl. expired/denied/failed);
  `AuthClient` MockEngine (pending parse, 404 mapping, approve/deny);
  `AuthEngine` (refresh-retry); snapshot `EnterCode`/`AuthorizeDevice`/denied/
  expired vs mockups; deep-link parse (URL→code, signed-in vs resume).
- **CLI:** QR renders for a sample `verification_uri_complete`; text fallback
  intact; `./gradlew build` green.
- **E2E:** desktop dev-auth API — `dayfold login` → enter code in app → approve →
  CLI "logged in" → `dayfold push` 200. Android: scan terminal QR → approve.

## Definition of Done

The lookup endpoint + the four screens + scanner (Android/iOS) + deep-link
(both platforms, existing origin) + CLI QR shipped and tested; the enter-code and
scan paths both close `login`→`push` end-to-end; owner-only + shared-rate-limited
lookup; expired/denied states render; full api + client + CLI suites green.
geo/ASN, E2E keypair, keychain deferred. Scanner build gated on the viewfinder
mockup sign-off.
