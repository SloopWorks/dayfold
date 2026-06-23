# Auth S6-D — client implementation spec (steps 2–6: the approval UI)

**Date:** 2026-06-23 · **For:** a session with the full mobile toolchain (Android
SDK + the operator's Mac per `processes/agent-dev-loop.md`). **Branch:** continue
`claude/cli-login-flow-review-aq9lp0`. **Parents:**
`docs/superpowers/specs/2026-06-23-auth-s6d-device-approval-design.md` (the slice
design) + `…/plans/2026-06-23-auth-s6d.md` (build status). **ADRs:** 0011 §6/7,
0013 (f(state)→UI, no nav lib), 0029 (scope grants), 0008 (mockups signed off:
A8b `authorizedevice`/`entercode`/`devicedenied`/`deviceexpired`).

This specs the **client half** of Phase 1. The **server contract is already
shipped + tested** (commit `1ff5f5e`): `GET /device/pending?user_code=` and the
reused `POST /families/:fid/device/{approve,deny}`. The CLI terminal QR is done
(`76c42c8`). Build the UI that drives the owner's approve/deny against that
contract. **Scanner + deep-link stay Phase 2** (leave the seams null).

## Server contract (already live — code to this)
- `GET /device/pending?user_code=` — **session-auth** (any signed-in `kind='app'`
  access token; not family-scoped). Returns
  `{ user_code, client, origin_ip, origin_ua, origin_kind, created_at, expires_at }`.
  `origin_kind ∈ datacenter|residential|unknown`. **401** no/expired session ·
  **404** `{type:"not-found"}` miss/expired (uniform) · **429** shared
  `account:approve:<sub>` lockout · **400** missing code.
- `POST /families/:fid/device/approve` `{user_code}` — owner+`kind='app'`. **204**
  ok · **404** not-pending/expired (race) · **429** lockout · **403** non-owner ·
  **401**.
- `POST /families/:fid/device/deny` `{user_code}` — same gate. **204** · **404** gone.
- **403 ≠ 404:** read-scope failures are 403 (permission). Don't treat as not-found.

## File touchpoints
- `Model.kt` — Route enum, AppState fields, actions, `PendingDevice` DTO.
- `Reducer.kt` — new arms + `ownerFamiliesFor()`.
- `AuthClient.kt` — `devicePending`/`deviceApprove`/`deviceDeny` + sealed results.
- `AuthEngine.kt` — `lookupDevice`/`approveDevice`/`denyDevice`.
- `DeviceApprovalScreens.kt` (new) — the 4 composables.
- `FeedApp.kt` — route arms + callbacks; `FamilyNullState`/`DevicesScreen` CTAs.
- desktop `Main.kt` / `androidApp MainActivity` / `iosMain MainViewController` — wire
  the 3 new callbacks to the engine (mirror `onLoadDevices`/`onRevokeDevice`).
- desktopTest: `AuthReducerTest`, `AuthClientTest`, `AuthEngineTest`,
  `AuthScreensSnapshotTest`, `FeedAppHostTest`.

## Model.kt

```kotlin
enum class Route { Loading, SignIn, AuthError, CreateFamily, Feed, Account,
                   JoinInvite, Members, Devices, EnterCode, AuthorizeDevice }  // +2

// in AppState:
val pendingDevice: PendingDevice? = null,
val deviceBusy: Boolean = false,
val deviceError: String? = null,
val deviceOutcome: String? = null,   // null | "denied" | "expired" | "approved"

@Serializable
data class PendingDevice(
  @SerialName("user_code") val userCode: String,
  val client: String? = null,
  @SerialName("origin_ip") val originIp: String? = null,
  @SerialName("origin_ua") val originUa: String? = null,
  @SerialName("origin_kind") val originKind: String? = null,   // datacenter|residential|unknown
  @SerialName("created_at") val createdAt: String? = null,
  @SerialName("expires_at") val expiresAt: String? = null,
)
```
Actions (mirror the approvals/join effect-trigger pattern — engine dispatches
`*Requested`/`*Loaded`/`*Failed`; reducer is pure):
```kotlin
data object OpenEnterCode : Action                              // → EnterCode (clears device fields)
data object DeviceLookupRequested : Action                     // engine: lookup start (busy)
data class  DevicePendingLoaded(val device: PendingDevice) : Action  // → AuthorizeDevice
data object DeviceLookupNotFound : Action                      // 404 → AuthorizeDevice + outcome "expired"
data class  DeviceLookupFailed(val message: String) : Action  // transient/429 → stay on EnterCode, inline error
data object ApproveDeviceRequested : Action                    // engine: approve start (busy)
data object DenyDeviceRequested : Action
data object DeviceApproved : Action                            // 204 → outcome "approved"
data object DeviceDenied : Action                             // 204 → outcome "denied"
data object DeviceApproveExpired : Action                     // approve 404 race → outcome "expired"
data class  DeviceOpFailed(val message: String) : Action      // approve/deny transient/403/429
data object CloseDeviceFlow : Action                         // exit → routeFor(session,families)
```

## Reducer.kt
```kotlin
fun ownerFamiliesFor(families: List<FamilyMembership>): List<FamilyMembership> =
  families.filter { it.role == "owner" && it.status == "active" }   // [C2]

is OpenEnterCode -> state.copy(route = Route.EnterCode, pendingDevice = null,
  deviceBusy = false, deviceError = null, deviceOutcome = null)
is DeviceLookupRequested -> state.copy(deviceBusy = true, deviceError = null)
is DevicePendingLoaded -> state.copy(deviceBusy = false, pendingDevice = action.device,
  route = Route.AuthorizeDevice, deviceOutcome = null)
is DeviceLookupNotFound -> state.copy(deviceBusy = false, pendingDevice = null,
  route = Route.AuthorizeDevice, deviceOutcome = "expired")
is DeviceLookupFailed -> state.copy(deviceBusy = false, deviceError = action.message)
is ApproveDeviceRequested -> state.copy(deviceBusy = true, deviceError = null)
is DenyDeviceRequested -> state.copy(deviceBusy = true, deviceError = null)
is DeviceApproved -> state.copy(deviceBusy = false, deviceOutcome = "approved")
is DeviceDenied -> state.copy(deviceBusy = false, deviceOutcome = "denied")
is DeviceApproveExpired -> state.copy(deviceBusy = false, deviceOutcome = "expired")
is DeviceOpFailed -> state.copy(deviceBusy = false, deviceError = action.message)
is CloseDeviceFlow -> state.copy(route = routeFor(state.session, state.families),
  pendingDevice = null, deviceBusy = false, deviceError = null, deviceOutcome = null)
```

## AuthClient.kt (mirror `redeemInvite` sealed-result style)
```kotlin
suspend fun devicePending(access: String, userCode: String): DeviceLookupResult {
  val resp = http.get("$api/device/pending?user_code=${userCode.encodeURLQueryComponent()}") {
    header("authorization", "Bearer $access") }
  return when (resp.status.value) {
    200 -> DeviceLookupResult.Found(json.decodeFromString(PendingDevice.serializer(), resp.bodyAsText()))
    404 -> DeviceLookupResult.NotFound
    429 -> DeviceLookupResult.Locked
    else -> throw AuthHttpException(resp.status.value, "device-pending")   // 401 → callWithRefresh
  }
}
suspend fun deviceApprove(access, fid, userCode): DeviceActionResult  // 204 Ok·404 Expired·429 Locked·403 Forbidden·else throw
suspend fun deviceDeny(access, fid, userCode): DeviceActionResult      // 204/404 → Ok (gone == denied); else throw
// bodies: POST "$api/families/$fid/device/{approve,deny}" {user_code}, Bearer access

sealed interface DeviceLookupResult {
  data class Found(val device: PendingDevice) : DeviceLookupResult
  data object NotFound : DeviceLookupResult; data object Locked : DeviceLookupResult
}
sealed interface DeviceActionResult { data object Ok; data object Expired; data object Locked; data object Forbidden }
```
401 must **throw** (so `callWithRefresh` rotates + retries); other statuses are
typed results.

## AuthEngine.kt (mutex + callWithRefresh, like `loadDevices`/`approveMember`)
```kotlin
suspend fun lookupDevice(code: String) = mutex.withLock {
  val s = store.state.session ?: return@withLock
  store.dispatch(DeviceLookupRequested)
  try {
    when (val r = callWithRefresh(s) { authClient.devicePending(it.access, code) }) {
      is DeviceLookupResult.Found -> store.dispatch(DevicePendingLoaded(r.device))
      DeviceLookupResult.NotFound -> store.dispatch(DeviceLookupNotFound)
      DeviceLookupResult.Locked -> store.dispatch(DeviceLookupFailed("Too many tries — wait ~15 min."))
    }
  } catch (e: Exception) { store.dispatch(DeviceLookupFailed("Couldn't check that code. Try again.")) }
}
suspend fun approveDevice(fid: String, code: String) = mutex.withLock { /* ApproveDeviceRequested;
  callWithRefresh approve → Ok:DeviceApproved · Expired:DeviceApproveExpired ·
  Locked/Forbidden:DeviceOpFailed(msg) · catch:DeviceOpFailed */ }
suspend fun denyDevice(fid: String, code: String) = mutex.withLock { /* DenyDeviceRequested;
  approve→ DeviceDenied (Ok or gone) · catch:DeviceOpFailed */ }
```

## DeviceApprovalScreens.kt (pure composables; mirror `DevicesScreen` chrome)
Each `@Composable` takes `state` + `on…` callbacks defaulting to `{}` (snapshot-
testable in isolation). MaterialTheme roles only (light+dark correct).
- **`EnterCodeScreen(state, onLookup: (String)->Unit, onBack, onScan: (()->Unit)? = null)`**
  → mockup `entercode`. 8-cell `XXXX-XXXX` entry (Roboto-Mono feel), submit →
  `onLookup(normalized)`; `state.deviceBusy` shows progress; `state.deviceError`
  inline. **`onScan` stays null in Phase 1** (the scan button renders only when
  non-null && `qrScanSupported` — Phase 2). Back → `onBack`.
- **`AuthorizeDeviceScreen(state, onApprove: (String)->Unit, onDeny: (String)->Unit, onCancel)`**
  → mockup `authorizedevice`. Renders `state.pendingDevice`: large `user_code`
  confirm; `client` + origin (`originIp`/`originUa`) rows; **datacenter warning
  banner when `originKind=="datacenter"`** ("This request comes from a datacenter —
  only approve if you started it"); a **scope row, informational** (interim per ADR
  0029: "Full content access · read & write"); an **owner-family selector** from
  `ownerFamiliesFor(state.families)` — `remember` the selection, default =
  `activeFamilyId` if an owner-family else first; **static row when exactly one**;
  Deny/Approve (disabled while `deviceBusy`) → `onDeny(fid)`/`onApprove(fid)`;
  footer "Only approve if you started this on your computer."
- **`DeviceDeniedScreen(onDone)`** → mockup `devicedenied`. onDone → `CloseDeviceFlow`.
- **`DeviceExpiredScreen(onRetry, onDone)`** → mockup `deviceexpired`. onRetry →
  `OpenEnterCode`; onDone → `CloseDeviceFlow`.
- **Approved:** no A8b mockup exists → do NOT invent a hi-fi screen (design-first).
  On `deviceOutcome=="approved"` render a minimal confirmation reusing existing
  styles (title + "You're connected." + Done) → `CloseDeviceFlow`; the CLI shows
  "logged in" and the new grant appears under Connected devices.

## FeedApp.kt
Add params `onLookupDevice: (String)->Unit = {}`, `onApproveDevice: (String)->Unit = {}`,
`onDenyDevice: (String)->Unit = {}` and route arms:
```kotlin
Route.EnterCode -> EnterCodeScreen(state, onLookup = onLookupDevice,
  onBack = { store.dispatch(CloseDeviceFlow) })
Route.AuthorizeDevice -> when (state.deviceOutcome) {
  "denied"  -> DeviceDeniedScreen(onDone = { store.dispatch(CloseDeviceFlow) })
  "expired" -> DeviceExpiredScreen(onRetry = { store.dispatch(OpenEnterCode) },
                                   onDone = { store.dispatch(CloseDeviceFlow) })
  "approved"-> DeviceApprovedConfirm(onDone = { store.dispatch(CloseDeviceFlow) })
  else      -> AuthorizeDeviceScreen(state, onApprove = onApproveDevice,
                 onDeny = onDenyDevice, onCancel = { store.dispatch(CloseDeviceFlow) })
}
```
Entry CTAs (dispatch `OpenEnterCode`): a secondary "Connect a device or CLI" on
`FamilyNullState`, and a "Connect a device" affordance on `DevicesScreen` (top
of the list). Both already render inside the gate.

## Shell wiring (3 shells)
Mirror the existing `onLoadDevices = { scope.launch { engine.loadDevices() } }`:
`onLookupDevice = { code -> scope.launch { engine.lookupDevice(code) } }`,
`onApproveDevice = { fid -> scope.launch { engine.approveDevice(fid, state.pendingDevice?.userCode ?: return@launch) } }`,
`onDenyDevice` likewise. (Pass the user_code from `pendingDevice`.)

## Tests
- **AuthReducerTest:** OpenEnterCode clears fields; DevicePendingLoaded→AuthorizeDevice;
  DeviceLookupNotFound→outcome "expired"; DeviceLookupFailed→deviceError, stays
  EnterCode; DeviceApproved/Denied/ApproveExpired→outcomes; CloseDeviceFlow→gate
  + cleared. (`runBlocking<Unit>` not needed — pure.)
- **AuthClientTest (MockEngine):** devicePending 200→Found(parsed, originKind);
  404→NotFound; 429→Locked; 401→throws AuthHttpException; approve 204→Ok / 404→
  Expired / 429→Locked / 403→Forbidden; deny 204→Ok.
- **AuthEngineTest:** lookup happy dispatches DeviceLookupRequested+DevicePendingLoaded;
  **401→refresh→retry** (MockEngine 401-then-200 + a refresh stub, asserts
  SessionRotated then PendingLoaded); notfound→DeviceLookupNotFound; approve happy→
  DeviceApproved; approve 404→DeviceApproveExpired. **Use `runBlocking<Unit>`**
  (agent-dev-loop JUnit gotcha — a non-Unit-returning test is silently skipped;
  verify COUNTS).
- **AuthScreensSnapshotTest:** add `entercode`, `authorizedevice` (datacenter +
  residential variants), `devicedenied`, `deviceexpired` — light+dark; Read the
  PNGs in `build/snapshots/` against the A8b mockups.
- **FeedAppHostTest:** host renders EnterCode + AuthorizeDevice (each outcome)
  without crashing.

## Build / DoD
- `cd apps && JAVA_HOME=<jdk17> ./gradlew :client:desktopTest` green (verify test
  COUNTS rose); `:androidApp:assembleDebug` compiles; iOS framework links.
- E2E (step 8, after this): dev-auth API + desktop client — `dayfold login` →
  EnterCode → Approve → CLI "logged in" → `dayfold push` 200 (verify via `[redux]`).
- **DoD:** the enter-code→approve loop closes `login`→`push` end-to-end; owner-only
  family selector; datacenter warning renders; denied/expired/approved outcomes;
  full `:client:desktopTest` green; snapshots match mockups. Scanner/deep-link +
  CLI keychain (7b) remain Phase 2 / target-OS.

## Gotchas (don't re-derive)
- **403 vs 404:** read-scope/forbidden = 403; not-found = 404 — distinct handling.
- **Owner-family filter** is load-bearing (a member-family approve → 403). Filter
  with `ownerFamiliesFor`; default to `activeFamilyId` only if it's an owner family.
- **Scan/deep-link are Phase 2** — keep `onScan` null; do not pull camera deps now.
- **JUnit non-Unit test** silently skipped — `runBlocking<Unit>`; check counts.
- redux-kotlin alpha01: `store.selectorState { }` (extension); see agent-dev-loop.
