# Owner Invite-Mint UI (code + QR share) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the owner-side "Invite a member" screen — generate an invite (QR or shareable link), show a live expiry countdown, copy the link, and manage outstanding invites (revoke) + pending joiners (approve/decline) — closing the one gap in an otherwise-complete invite flow (API + specs + hi-fi designs all exist; only this client surface was never built).

**Architecture:** Mirror the existing S5/S6 auth slice pattern exactly (AuthClient transport → AuthEngine effect with `mutex` + `callWithRefresh` + a no-mutex `*Locked` core → typed Action → pure `rootReducer` → Compose screen in `apps/ui` commonMain → `Route` branch in `FeedApp` → shell wiring in the 3 hosts). Adds one new capability: cross-platform QR **rendering** (none exists today — zxing is CLI/JVM-only; the client only ever *scanned* QR via ML Kit). QR is display-only this slice; the recipient still redeems by pasting the link into the existing `JoinInviteScreen` (deep-link is a deferred cut, spec §96/§121).

**Tech Stack:** Kotlin Multiplatform (commonMain), Compose Multiplatform 1.11.1 (M3 tokens), ktor client (no ContentNegotiation — explicit kotlinx-serialization), redux-kotlin-granular alpha01, `io.github.alexzhirkevich:qrose:1.1.2` (KMP QR painter). Targets: `androidTarget`, `jvm("desktop")`, `iosArm64`, `iosSimulatorArm64`. JDK17, Kotlin 2.3.20.

## Global Constraints

- **Build/test invocation** (from `apps/`): `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew <task>`. Client logic tests: `:client:desktopTest`. UI tests: `:ui:desktopTest`. Both modules use **JUnit5 (`useJUnitPlatform()`)**.
- **UI-test idiom (MANDATORY — the repo has NO JUnit4 `createComposeRule` in desktopTest):** every Compose test is `@OptIn(ExperimentalTestApi::class) class X { @Test fun y() = runComposeUiTest { setContent { DayfoldTheme { … } }; onNodeWithText(…).assertExists() } }` using `kotlin.test` asserts and `runBlocking`/no-arg test fns (see `apps/ui/src/desktopTest/.../AuthFlowUiTest.kt`, `OfflineBannerTest.kt`). After any route-changing `store.dispatch`, add `waitForIdle()`/`waitUntil { … }` — the skiko harness doesn't auto-sync the redux subscription. **Do not use `@get:Rule createComposeRule()`** — it's a JUnit4 rule and won't run under JUnit5.
- **Client-logic-test idiom:** `@Test fun name() = runBlocking { … }` (NOT `runTest` — `kotlinx-coroutines-test` is not on this classpath) with ktor `MockEngine { req -> respond(body, HttpStatusCode.X, headersOf(HttpHeaders.ContentType, "application/json")) }`. Copy the exact MockEngine + `respond(...)` shape from an existing test in `AuthClientTest.kt` / `AuthEngineTest.kt`; there is no `mockJson`/`engineWith`/`signedInOwner` helper — either add a small local one or inline it.
- **Reducer is NOT exhaustive-checked:** `rootReducer(state: AppState, action: Any): AppState` (`Reducer.kt:33`) dispatches over `Any` with `else -> state` (`:178`). A missing case silently no-ops — the compiler will NOT catch it. Therefore **every new action MUST have a reducer unit test** (Task 2); do not rely on compile errors.
- **KMP surface:** all new non-UI logic in `apps/client` commonMain; all new UI in `apps/ui` commonMain. No new expect/actual — clipboard uses Compose `LocalClipboard` (commonMain, all four targets), datetime uses `kotlin.time`.
- **datetime:** use `kotlin.time.Instant` / `kotlin.time.Clock` (the repo migrated to stdlib types — `FeedScreen.kt:52`). `kotlinx.datetime.Instant`/`Clock` are deprecated typealiases in the pinned 0.8.0 — do not use them. `DateLabels.countdownLabel(targetIso, nowIso, tz)` (`DateLabels.kt:61`) already formats day/hour-scale relative expiry; reuse it for outstanding rows.
- **QR must resolve on ALL four targets.** zxing is **forbidden** for the client (JVM-only, breaks iOS). Task 4 gates qrose behind a real `compileKotlinIosArm64 + compileKotlinDesktop` check before any screen depends on it.
- **Mint API (verbatim, `apps/api/src/app.ts:796`):** `POST /families/{fid}/invites` Bearer access, body `{ mode:"qr"|"link", role:"adult", max_uses:Int }` → `201 { invite_id, token, url, role, mode, expires_at }`. Errors: `400` (`bad-mode`/`bad-role`/`bad-max-uses`), `403` (non-owner, via `ownerGate`), `429` (mint rate-limit 20/600s OR caps ≥10 active / ≥20 pending, no body). `qr`→`max_uses=1` server-forced; `link`→1–10. `cache-control: no-store` — **the raw token is a one-time secret; never persist to SQLDelight, never `ClientLog` it.**
- **List API (verbatim, `app.ts:920`):** `GET /families/{fid}/invites` → `{ invites:[{id,role,mode,max_uses,used_count,expires_at,created_at}], pending:[...] }` (only `status='active' AND expires_at>now()`). **The client already calls this** (`AuthClient.familyApprovals`) but discards `invites`. This slice widens the existing call to surface both — no second network call.
- **Revoke API (verbatim, `app.ts:912`):** `DELETE /families/{fid}/invites/{id}` → `204` (sticky).
- **Role:** `adult` only (spec §109; owner never mintable, teen deferred ADR 0005). The role row is static "Adult".
- **Copy (verbatim from the signed-off mockup `Auth-Phone-Android.dc.html:252-308`):** title "Invite a member"; toggle "In person · QR" / "Share a link"; QR caption "Have them scan this with their camera, then sign in. Every join waits for your approval before they see anything."; QR chip "Expires in M:SS · one-time use"; role row "Joins as" → "Adult"; section "OUTSTANDING"; link row action "Revoke"; outstanding subtitle pattern "Expires in {relative} · {used} of {max} used".
- **Design-first (ADR 0008) deltas — flagged for operator visibility (build design-system-consistent; note in PR):** the mockup draws QR mode with the QR **auto-present on entry** (no explicit "generate" button — so this plan **auto-mints on entry**, not via a button). The **link-mode minted card** (URL text + "Copy link") and the transient **loading/error** states are standard states of the designed screen but are not separately drawn — build them with M3 tokens matching the gallery; surface the delta in the PR body. "Copy link" is the one invented string.
- **Scope OUT (deferred, not gaps):** invitee-side in-app QR *scan* + App/Universal-Link deep-linking (spec §96/§121, needs domain association, ADR 0011 §1). Native share-sheet (clipboard copy only). Dedup/reuse of an existing active QR invite across repeated screen opens (each open auto-mints; capped by the server's 10-active/20-per-600s limits → error state — acceptable slice-1 limitation, note as follow-up).

---

### Task 1: AuthClient — widen list to include invites; add mint + revoke

**Files:**
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/AuthClient.kt`
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/AuthClientTest.kt`

**Interfaces:**
- Consumes: existing `AuthClient` ctor, `AuthHttpException`, `PendingMember`, existing `familyApprovals`.
- Produces:
  - `@Serializable data class Invite(id, role, mode, maxUses, usedCount, expiresAt, createdAt)` (snake_case `@SerialName`).
  - `data class MintedInvite(inviteId, token, url, role, mode, expiresAt)`.
  - `data class InviteQueue(val invites: List<Invite>, val pending: List<PendingMember>)`.
  - `sealed interface MintResult { data class Ok(val invite: MintedInvite); data object RateLimited; data object Forbidden }`.
  - Change `familyApprovals(access, fid): List<PendingMember>` → **`familyApprovals(access, fid): InviteQueue`** (returns both arrays). Update its one caller in Task 3.
  - `suspend fun mintInvite(access, fid, mode, maxUses): MintResult`
  - `suspend fun revokeInvite(access, fid, id)` — throws on non-2xx.

- [ ] **Step 1: Write the failing tests** (append to `AuthClientTest.kt`; copy its existing `MockEngine`/`respond`/client-ctor idiom — use `runBlocking`, `headersOf(HttpHeaders.ContentType,"application/json")`):

```kotlin
@Test fun mintInvite_qr_parsesMinted() = runBlocking {
  val http = HttpClient(MockEngine { req ->
    assertEquals("/families/fam1/invites", req.url.encodedPath); assertEquals("POST", req.method.value)
    respond("""{"invite_id":"inv1","token":"TOK","url":"https://x/invite/TOK","role":"adult","mode":"qr","expires_at":"2026-07-07T10:15:00Z"}""",
      HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json"))
  })
  val res = AuthClient("", http).mintInvite("acc", "fam1", "qr", 1)
  assertTrue(res is MintResult.Ok); assertEquals("TOK", (res as MintResult.Ok).invite.token)
}
@Test fun mintInvite_429_rateLimited() = runBlocking {
  val http = HttpClient(MockEngine { respond("", HttpStatusCode.TooManyRequests) })
  assertEquals(MintResult.RateLimited, AuthClient("", http).mintInvite("a","fam1","link",5))
}
@Test fun mintInvite_403_forbidden() = runBlocking {
  val http = HttpClient(MockEngine { respond("", HttpStatusCode.Forbidden) })
  assertEquals(MintResult.Forbidden, AuthClient("", http).mintInvite("a","fam1","qr",1))
}
@Test fun familyApprovals_parsesBothArrays() = runBlocking {
  val http = HttpClient(MockEngine { respond("""{"invites":[{"id":"inv1","role":"adult","mode":"link","max_uses":5,"used_count":1,"expires_at":"2026-07-09T00:00:00Z","created_at":"2026-07-07T00:00:00Z"}],"pending":[{"uid":"u9","display_name":"Sam Rivera","role":"adult"}]}""",
    HttpStatusCode.OK, headersOf(HttpHeaders.ContentType,"application/json")) })
  val q = AuthClient("", http).familyApprovals("a", "fam1")
  assertEquals(1, q.invites.size); assertEquals("link", q.invites[0].mode); assertEquals(1, q.pending.size)
}
@Test fun revokeInvite_204_ok() = runBlocking {
  AuthClient("", HttpClient(MockEngine { respond("", HttpStatusCode.NoContent) })).revokeInvite("a","fam1","inv1")
}
```
Any existing `AuthClientTest` test asserting `familyApprovals` returns a `List` must be updated to `.pending`.

- [ ] **Step 2: Run to verify they fail**

Run: `cd apps && JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :client:desktopTest --tests "*AuthClientTest*" --rerun-tasks`
Expected: FAIL (unresolved `mintInvite`/`revokeInvite`; `familyApprovals` type mismatch).

- [ ] **Step 3: Implement**

Change `ApprovalsResp` to carry both, and change `familyApprovals`:
```kotlin
@Serializable private data class ApprovalsResp(val invites: List<Invite> = emptyList(), val pending: List<PendingMember> = emptyList())

/** GET /families/{fid}/invites (owner) → active invites + pending joiners (one call). */
suspend fun familyApprovals(access: String, fid: String): InviteQueue {
  val resp = http.get("$api/families/$fid/invites") { header("authorization", "Bearer $access") }
  if (resp.status.value != 200) throw AuthHttpException(resp.status.value, "family-invites")
  val r = json.decodeFromString(ApprovalsResp.serializer(), resp.bodyAsText())
  return InviteQueue(r.invites, r.pending)
}

@Serializable private data class MintReq(val mode: String, val role: String = "adult", @SerialName("max_uses") val maxUses: Int)
@Serializable private data class MintResp(@SerialName("invite_id") val inviteId: String, val token: String, val url: String, val role: String, val mode: String, @SerialName("expires_at") val expiresAt: String)

/** POST /families/{fid}/invites (owner). 201 → token (one-time secret, display-only, never persist/log). */
suspend fun mintInvite(access: String, fid: String, mode: String, maxUses: Int): MintResult {
  val resp = http.post("$api/families/$fid/invites") {
    header("authorization", "Bearer $access"); contentType(ContentType.Application.Json)
    setBody(json.encodeToString(MintReq.serializer(), MintReq(mode = mode, maxUses = maxUses)))
  }
  return when (resp.status.value) {
    201 -> json.decodeFromString(MintResp.serializer(), resp.bodyAsText())
      .let { MintResult.Ok(MintedInvite(it.inviteId, it.token, it.url, it.role, it.mode, it.expiresAt)) }
    429 -> MintResult.RateLimited
    403 -> MintResult.Forbidden
    else -> throw AuthHttpException(resp.status.value, "mint-invite")
  }
}

/** DELETE /families/{fid}/invites/{id} (owner) — revoke. 204 sticky. */
suspend fun revokeInvite(access: String, fid: String, id: String) {
  val resp = http.delete("$api/families/$fid/invites/$id") { header("authorization", "Bearer $access") }
  if (resp.status.value !in 200..204) throw AuthHttpException(resp.status.value, "revoke-invite")
}
```
Public types at the bottom (near `RedeemResult`):
```kotlin
@Serializable
data class Invite(
  val id: String, val role: String = "adult", val mode: String,
  @SerialName("max_uses") val maxUses: Int = 1, @SerialName("used_count") val usedCount: Int = 0,
  @SerialName("expires_at") val expiresAt: String, @SerialName("created_at") val createdAt: String? = null,
)
data class MintedInvite(val inviteId: String, val token: String, val url: String, val role: String, val mode: String, val expiresAt: String)
data class InviteQueue(val invites: List<Invite>, val pending: List<PendingMember>)
sealed interface MintResult {
  data class Ok(val invite: MintedInvite) : MintResult
  data object RateLimited : MintResult
  data object Forbidden : MintResult
}
```

- [ ] **Step 4: Run to verify they pass** — `--tests "*AuthClientTest*" --rerun-tasks`. Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/AuthClient.kt apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/AuthClientTest.kt
git commit -m "feat(client): AuthClient mint/revoke + widen invites list to include active invites"
```

---

### Task 2: Actions + AppState + reducer for the invite-mint slice

**Files:**
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/Model.kt` (actions ~L594; `AppState` fields ~L496; `Route` enum L397)
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/Reducer.kt`
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/AuthReducerTest.kt` (route/auth reducer transitions live here)

**Interfaces:**
- Consumes: `Invite`, `MintedInvite` (Task 1); `Route`.
- Produces (Actions):
  - `data object OpenInvite : Action` — Members → Invite (clears prior mint state).
  - `data class InviteModeSelected(val mode: String) : Action`.
  - `data object MintRequested : Action`.
  - `data class InviteMinted(val invite: MintedInvite) : Action`.
  - `data class MintFailed(val reason: String) : Action` — "ratelimited" | "forbidden" | "error".
  - `data class InviteRevokeRequested(val id: String) : Action`.
  - `data class InviteRevoked(val id: String) : Action`.
  - `data object InviteDismissed : Action` — leave → Members; clears token.
  - **Change** existing `data class ApprovalsLoaded(val pending: List<PendingMember>)` → **`ApprovalsLoaded(val pending: List<PendingMember>, val invites: List<Invite> = emptyList())`** (one load feeds both queue + outstanding).
- Produces (`AppState` fields, near `memberOpId` L496):
  - `val inviteMode: String = "qr"`
  - `val inviteBusy: Boolean = false`   (`…Busy` naming convention)
  - `val mintedInvite: MintedInvite? = null`
  - `val mintError: String? = null`
  - `val outstandingInvites: List<Invite> = emptyList()`
  - `val inviteOpId: String? = null`
- Produces (`Route`): add `Invite`.

- [ ] **Step 1: Write the failing reducer tests** (in `AuthReducerTest.kt`; the reducer entry is `rootReducer(state, action)`):

```kotlin
@Test fun openInvite_routes_clearsPriorMint() {
  val s = rootReducer(AppState(route = Route.Members, mintedInvite = MintedInvite("i","t","u","adult","qr","z"), mintError = "error"), OpenInvite)
  assertEquals(Route.Invite, s.route); assertNull(s.mintedInvite); assertNull(s.mintError)
}
@Test fun mintRequested_busy_clearsError() {
  val s = rootReducer(AppState(mintError = "error"), MintRequested); assertTrue(s.inviteBusy); assertNull(s.mintError)
}
@Test fun inviteMinted_storesToken_clearsBusy() {
  val s = rootReducer(AppState(inviteBusy = true), InviteMinted(MintedInvite("i","TOK","u","adult","qr","z")))
  assertEquals("TOK", s.mintedInvite?.token); assertFalse(s.inviteBusy)
}
@Test fun mintFailed_setsError_clearsBusy() {
  val s = rootReducer(AppState(inviteBusy = true), MintFailed("ratelimited")); assertEquals("ratelimited", s.mintError); assertFalse(s.inviteBusy)
}
@Test fun modeSelected_switches_clearsStaleToken() {
  val s = rootReducer(AppState(inviteMode = "qr", mintedInvite = MintedInvite("i","t","u","adult","qr","z")), InviteModeSelected("link"))
  assertEquals("link", s.inviteMode); assertNull(s.mintedInvite)
}
@Test fun approvalsLoaded_setsBoth() {
  val s = rootReducer(AppState(), ApprovalsLoaded(listOf(PendingMember("u9","Sam")), listOf(Invite(id="inv1", mode="link", expiresAt="z"))))
  assertEquals(1, s.pendingApprovals.size); assertEquals(1, s.outstandingInvites.size)
}
@Test fun inviteRevoked_dropsRow_clearsOpId() {
  val s = rootReducer(AppState(outstandingInvites = listOf(Invite(id="inv1", mode="link", expiresAt="z")), inviteOpId = "inv1"), InviteRevoked("inv1"))
  assertTrue(s.outstandingInvites.isEmpty()); assertNull(s.inviteOpId)
}
@Test fun inviteDismissed_clearsToken_routesMembers() {
  val s = rootReducer(AppState(route = Route.Invite, mintedInvite = MintedInvite("i","t","u","adult","qr","z")), InviteDismissed)
  assertEquals(Route.Members, s.route); assertNull(s.mintedInvite)
}
```

- [ ] **Step 2: Run to verify they fail** — `--tests "*AuthReducerTest*" --rerun-tasks`. Expected: FAIL.

- [ ] **Step 3: Add Route entry, actions, state fields, reducer cases**

`Route` enum (L397): insert `Invite` after `Members`.
Actions (near ApprovalsLoaded ~L589): change `ApprovalsLoaded` signature as above; add the new actions.
`AppState` fields (near `memberOpId` L496): add the six fields above.
`Reducer.kt` cases (match `is X -> state.copy(...)` style):
```kotlin
is OpenInvite -> state.copy(route = Route.Invite, mintedInvite = null, mintError = null, inviteBusy = false)
is InviteModeSelected -> state.copy(inviteMode = action.mode, mintedInvite = null, mintError = null)
is MintRequested -> state.copy(inviteBusy = true, mintError = null)
is InviteMinted -> state.copy(inviteBusy = false, mintedInvite = action.invite)
is MintFailed -> state.copy(inviteBusy = false, mintError = action.reason)
is InviteRevokeRequested -> state.copy(inviteOpId = action.id)
is InviteRevoked -> state.copy(outstandingInvites = state.outstandingInvites.filterNot { it.id == action.id }, inviteOpId = null)
is InviteDismissed -> state.copy(route = Route.Members, mintedInvite = null, mintError = null, inviteBusy = false)
```
And extend the existing `is ApprovalsLoaded ->` case to also set `outstandingInvites = action.invites`:
```kotlin
is ApprovalsLoaded -> state.copy(pendingApprovals = action.pending, outstandingInvites = action.invites, approvalsBusy = false)
```
(Preserve whatever `approvalsBusy`/other fields the existing case already sets — read it first.)

- [ ] **Step 4: Run to verify they pass** — `--tests "*AuthReducerTest*" --rerun-tasks`, then full `:client:desktopTest` (the `ApprovalsLoaded` signature change ripples to any test that constructs it — update those call sites to the 1- or 2-arg form; the second arg defaults to `emptyList()`). Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/Model.kt apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/Reducer.kt apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/AuthReducerTest.kt
git commit -m "feat(client): invite-mint actions, state, reducer; ApprovalsLoaded carries invites"
```

---

### Task 3: AuthEngine — refactor loadApprovals to feed both; add mint + revoke

**Files:**
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/AuthEngine.kt` (`loadApprovals` ~L137)
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/AuthEngineTest.kt`

**Interfaces:**
- Consumes: `authClient.familyApprovals(→InviteQueue)`, `mintInvite`, `revokeInvite` (Task 1); actions (Task 2); existing `mutex`, `callWithRefresh`.
- Produces:
  - Refactor `loadApprovals(fid)` to dispatch `ApprovalsLoaded(queue.pending, queue.invites)` via a no-mutex `loadApprovalsLocked(session, fid)` core.
  - `suspend fun mintInvite(fid: String, mode: String)`
  - `suspend fun revokeInvite(fid: String, id: String)`

- [ ] **Step 1: Write the failing engine tests** (reuse the real `engine(ts, handler): Pair<AuthEngine, Store>` helper; **seed a signed-in owner** by giving `MemTokenStore(Session("ax","rx"))` + a `MockEngine` whose `/auth/whoami` returns an owner membership for `fam1`, then `eng.restore()` before the invite call — mirror the existing `restore with a saved active session lands on Feed` test):

```kotlin
@Test fun mintInvite_qr_dispatchesMinted() = runBlocking {
  val ts = MemTokenStore(Session("ax","rx"))
  val (eng, store) = engine(ts, handler = MockEngine { req ->
    when {
      req.url.encodedPath == "/auth/whoami" -> respond("""{"family_id":"fam1","families":[{"family_id":"fam1","name":"Fam","role":"owner","status":"active"}]}""", HttpStatusCode.OK, jsonCt)
      req.url.encodedPath == "/families/fam1/invites" && req.method.value == "POST" -> respond("""{"invite_id":"i","token":"TOK","url":"u","role":"adult","mode":"qr","expires_at":"z"}""", HttpStatusCode.Created, jsonCt)
      req.url.encodedPath == "/families/fam1/invites" -> respond("""{"invites":[],"pending":[]}""", HttpStatusCode.OK, jsonCt)
      else -> respond("", HttpStatusCode.OK)
    }
  })
  eng.restore()
  eng.mintInvite("fam1", "qr")
  assertEquals("TOK", store.state.mintedInvite?.token); assertFalse(store.state.inviteBusy)
}
@Test fun mintInvite_429_rateLimited() = runBlocking {
  val ts = MemTokenStore(Session("ax","rx"))
  val (eng, store) = engine(ts, handler = MockEngine { req ->
    if (req.url.encodedPath == "/auth/whoami") respond("""{"family_id":"fam1","families":[{"family_id":"fam1","name":"F","role":"owner","status":"active"}]}""", HttpStatusCode.OK, jsonCt)
    else respond("", HttpStatusCode.TooManyRequests)
  })
  eng.restore(); eng.mintInvite("fam1", "link")
  assertEquals("ratelimited", store.state.mintError)
}
```
(`jsonCt` = `headersOf(HttpHeaders.ContentType,"application/json")` — define once at the top of the file if not present. If `engine()` doesn't accept a pre-set session store, note that it already takes a `MemTokenStore` param — pass one carrying a `Session`.)

- [ ] **Step 2: Run to verify they fail** — `--tests "*AuthEngineTest*" --rerun-tasks`. Expected: FAIL (methods unresolved).

- [ ] **Step 3: Implement** (mirror `loadApprovals`/`resolveMember` + the `lookupDeviceLocked` no-mutex convention at AuthEngine.kt:208)

```kotlin
/** Owner: load the pending queue + outstanding invites (one GET → both). */
suspend fun loadApprovals(fid: String) = mutex.withLock {
  val session = store.state.session ?: return@withLock
  loadApprovalsLocked(session, fid)
}
private suspend fun loadApprovalsLocked(session: Session, fid: String) {
  store.dispatch(ApprovalsRequested)
  try {
    val q = callWithRefresh(session) { authClient.familyApprovals(it.access, fid) }
    store.dispatch(ApprovalsLoaded(q.pending, q.invites))
  } catch (e: Exception) { store.dispatch(ApprovalsFailed) }
}

/** Owner: mint an invite (qr|link) → show it, then refresh outstanding. Token is
 *  display-only (dispatched to state, never persisted). */
suspend fun mintInvite(fid: String, mode: String) = mutex.withLock {
  val session = store.state.session ?: return@withLock
  val maxUses = if (mode == "qr") 1 else 5   // qr forced to 1 server-side; link default 5 ("0 of 5 used")
  store.dispatch(MintRequested)
  try {
    when (val r = callWithRefresh(session) { authClient.mintInvite(it.access, fid, mode, maxUses) }) {
      is MintResult.Ok -> { store.dispatch(InviteMinted(r.invite)); loadApprovalsLocked(session, fid) }
      MintResult.RateLimited -> store.dispatch(MintFailed("ratelimited"))
      MintResult.Forbidden -> store.dispatch(MintFailed("forbidden"))
    }
  } catch (e: Exception) { store.dispatch(MintFailed("error")) }
}

/** Owner: revoke an outstanding invite → drop it; reload on a guarded failure. */
suspend fun revokeInvite(fid: String, id: String) = mutex.withLock {
  val session = store.state.session ?: return@withLock
  store.dispatch(InviteRevokeRequested(id))
  try {
    callWithRefresh(session) { authClient.revokeInvite(it.access, fid, id) }
    store.dispatch(InviteRevoked(id))
  } catch (e: Exception) { loadApprovalsLocked(session, fid) }
}
```
(The existing `loadApprovals` body moves into `loadApprovalsLocked`; keep the public `loadApprovals` as the locked wrapper. Verify no other caller depends on the old single-arg `ApprovalsLoaded` dispatch — grep.)

- [ ] **Step 4: Run to verify they pass** — `--tests "*AuthEngineTest*" --rerun-tasks`. Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/AuthEngine.kt apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/AuthEngineTest.kt
git commit -m "feat(client): AuthEngine mint/revoke; loadApprovals feeds pending+outstanding"
```

---

### Task 4: KMP QR rendering (qrose) — DEPENDENCY GATE

**Files:**
- Modify: `apps/ui/build.gradle.kts` (commonMain deps)
- Create: `apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/QrImage.kt`
- Test: `apps/ui/src/desktopTest/kotlin/com/sloopworks/dayfold/client/QrImageTest.kt`

**Interfaces:**
- Produces: `@Composable fun QrImage(data: String, modifier: Modifier, dark: Color = Color.Black, light: Color = Color.White)`.

- [ ] **Step 1: Add the dependency and PROVE multi-target resolution FIRST**

Add to `apps/ui/build.gradle.kts` commonMain: `implementation("io.github.alexzhirkevich:qrose:1.1.2")` (1.1.2 = Feb-2026, built for the current Kotlin/Compose toolchain; 1.0.1's older Kotlin/Native klibs risk ABI-drift link failures against Kotlin 2.3.20). Then:
```bash
cd apps && JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :ui:compileKotlinIosArm64 :ui:compileKotlinDesktop
```
Expected: BUILD SUCCESSFUL (resolves + links for iOS-native and JVM).
**If it fails to resolve/link on any target: STOP.** Report the failure. Fallback (operator-flagged, not hand-rolled silently): try the latest qrose, else a pure-Kotlin matrix encoder rendered via Compose Canvas — but hand-rolling QR (mode/ECC/Reed-Solomon/masking/versioning) is multi-day + correctness-critical → escalate before taking it on.

- [ ] **Step 2: Write the failing render test** (JUnit5 `runComposeUiTest` idiom):
```kotlin
@OptIn(ExperimentalTestApi::class)
class QrImageTest {
  @Test fun rendersWithoutCrash() = runComposeUiTest {
    setContent { QrImage("https://x/invite/TOK", Modifier.size(160.dp), Color.Black, Color.White) }
    waitForIdle()
    onNodeWithContentDescription("Invite QR code").assertExists()
  }
}
```

- [ ] **Step 3: Run to verify it fails** — `:ui:desktopTest --tests "*QrImageTest*" --rerun-tasks`. Expected: FAIL (unresolved `QrImage`).

- [ ] **Step 4: Implement QrImage** (verify the qrose builder API against the RESOLVED 1.1.2 artifact — the color DSL is a **builder block**, not property assignment; light modules are transparent by default so the white card behind provides the background):
```kotlin
package com.sloopworks.dayfold.client

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import io.github.alexzhirkevich.qrose.options.QrBrush
import io.github.alexzhirkevich.qrose.options.solid

@Composable
fun QrImage(data: String, modifier: Modifier, dark: Color = Color.Black, light: Color = Color.White) {
  val painter = rememberQrCodePainter(data) {
    colors {
      dark = QrBrush.solid(this@QrImage.let { dark })   // resolve names against the 1.1.2 DSL; see note
      light = QrBrush.solid(light)
    }
  }
  Image(painter = painter, contentDescription = "Invite QR code", modifier = modifier)
}
```
NOTE: the exact `colors { dark = …; light = … }` receiver/property names must be taken from the resolved qrose 1.1.2 API — the guaranteed contract of this task is only the `QrImage(...)` signature + the `"Invite QR code"` content description. If the DSL differs, adapt the block; do not change the public signature.

- [ ] **Step 5: Run to verify it passes** — `--tests "*QrImageTest*" --rerun-tasks`. Expected: PASS.

- [ ] **Step 6: Commit**
```bash
git add apps/ui/build.gradle.kts apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/QrImage.kt apps/ui/src/desktopTest/kotlin/com/sloopworks/dayfold/client/QrImageTest.kt
git commit -m "feat(ui): cross-platform QrImage via qrose 1.1.2"
```

---

### Task 5: Countdown formatter (pure) + InviteScreen

**Files:**
- Create: `apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/InviteScreen.kt`
- Test: `apps/ui/src/desktopTest/kotlin/com/sloopworks/dayfold/client/InviteScreenTest.kt`

**Interfaces:**
- Consumes: `AppState`, `QrImage` (Task 4), `MintedInvite`/`Invite` (Task 1), `DateLabels.countdownLabel` (`DateLabels.kt:61`), `LocalClipboard`.
- Produces:
  - `fun formatCountdown(remainingSeconds: Long): String` — `"14:32"`, floored at `"0:00"` (QR chip ONLY — outstanding rows use `DateLabels.countdownLabel`).
  - `@Composable fun InviteScreen(state, now, onMode, onMint, onRevoke, onApprove, onDecline, onBack)` where `now: kotlin.time.Instant = kotlin.time.Clock.System.now()` (own default, like `FeedScreen.kt:52` — NOT threaded through FeedApp).

- [ ] **Step 1: Write the failing tests**

```kotlin
class FormatCountdownTest {
  @Test fun formatsMmSs() { assertEquals("14:32", formatCountdown(872)); assertEquals("1:05", formatCountdown(65)); assertEquals("0:00", formatCountdown(-3)) }
}
@OptIn(ExperimentalTestApi::class)
class InviteScreenTest {
  private val fixedNow = kotlin.time.Instant.parse("2026-07-07T00:00:00Z")
  @Test fun showsTitle() = runComposeUiTest {
    setContent { DayfoldTheme { InviteScreen(AppState(route = Route.Invite, inviteMode = "qr"), now = fixedNow) } }
    onNodeWithText("Invite a member").assertExists()
  }
  @Test fun showsQr_whenMinted() = runComposeUiTest {
    val s = AppState(route = Route.Invite, inviteMode = "qr", mintedInvite = MintedInvite("i","TOK","https://x/invite/TOK","adult","qr","2099-01-01T00:00:00Z"))
    setContent { DayfoldTheme { InviteScreen(s, now = fixedNow) } }
    onNodeWithContentDescription("Invite QR code").assertExists()
  }
  @Test fun showsRevoke_forOutstanding() = runComposeUiTest {
    val s = AppState(route = Route.Invite, outstandingInvites = listOf(Invite(id="inv1", mode="link", maxUses=5, usedCount=0, expiresAt="2099-01-01T00:00:00Z")))
    setContent { DayfoldTheme { InviteScreen(s, now = fixedNow) } }
    onNodeWithText("Revoke").assertExists()
  }
}
```

- [ ] **Step 2: Run to verify they fail** — `--tests "*FormatCountdownTest*" --tests "*InviteScreenTest*" --rerun-tasks`. Expected: FAIL.

- [ ] **Step 3: Implement**

`formatCountdown`:
```kotlin
fun formatCountdown(remainingSeconds: Long): String {
  val s = remainingSeconds.coerceAtLeast(0)
  return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}
```
`InviteScreen` — a `Column` mirroring `MembersScreen`'s structure + the mockup (`Auth-Phone-Android.dc.html:252-308`), built in order:
1. **Header** — back/close icon + centered "Invite a member" (`onBack`).
2. **Segmented toggle** — "In person · QR" / "Share a link"; selected = `state.inviteMode` (`cs.primary`/`cs.onPrimary`), track `cs.surfaceContainer`; tap → `onMode("qr"|"link")`.
3. **Auto-mint on entry / mode-change** (matches the mockup's auto-present QR — no button):
```kotlin
LaunchedEffect(state.inviteMode) {
  if (state.mintedInvite == null && !state.inviteBusy && state.mintError == null) onMint(state.inviteMode)
}
```
4. **Body by state:**
   - `state.inviteBusy` → centered `RowBusy()`.
   - `state.mintError != null` → inline message + Retry → `onMint(state.inviteMode)`. Copy: "ratelimited" → "Too many invites right now — try again in a bit."; else "Couldn't create an invite. Try again."
   - `state.mintedInvite != null` and mode "qr" → white QR card: `QrImage(mintedInvite.url, Modifier.size(180.dp), dark = Color(0xFF271814))` + a live countdown chip "Expires in ${formatCountdown(rem)} · one-time use" where `rem` recomputes from the real clock each second (ticker below) + the QR caption (verbatim).
   - `state.mintedInvite != null` and mode "link" → rounded card: the URL (ellipsized, `mintedInvite.url`) + a "Copy link" button using `LocalClipboard`:
```kotlin
val clipboard = LocalClipboard.current
val scope = rememberCoroutineScope()
// on click: scope.launch { clipboard.setClipEntry(<url as ClipEntry>) }  // use the Compose 1.11 ClipEntry helper for the target
```
     Keep the same expiry caption. (If a platform `ClipEntry` constructor from text is awkward in commonMain, fall back to the repo's existing `PlatformActions`/`CardAction.Copy` seam via an `onCopyLink` callback threaded to the shells — but prefer the commonMain `LocalClipboard` path to avoid 3-shell wiring.)
5. **Role row** — "Joins as" · "Adult" (static; `badge` icon).
6. **OUTSTANDING** — header; for each `state.outstandingInvites.filterNot { it.id == state.mintedInvite?.inviteId }` (exclude the one shown as the big QR): a row (mode icon, "Shared link"/"QR invite", subtitle "Expires in ${DateLabels.countdownLabel(it.expiresAt, now.toString()) ?: "soon"} · ${it.usedCount} of ${it.maxUses} used", trailing "Revoke" → `onRevoke(it.id)`, `RowBusy()` when `it.id == state.inviteOpId`). Then `state.pendingApprovals` rows reusing the existing `PendingRow` composable (`onApprove`/`onDecline`).

Countdown ticker (recompute from the real clock so it self-corrects; seed from `now` for deterministic tests):
```kotlin
var remaining by remember(state.mintedInvite?.token) {
  mutableStateOf(secondsUntil(state.mintedInvite?.expiresAt, now))
}
LaunchedEffect(state.mintedInvite?.token) {
  while (state.mintedInvite != null) {
    kotlinx.coroutines.delay(1000)
    remaining = secondsUntil(state.mintedInvite?.expiresAt, kotlin.time.Clock.System.now())
  }
}
// private fun secondsUntil(iso: String?, now: kotlin.time.Instant): Long =
//   iso?.let { (kotlin.time.Instant.parse(it) - now).inWholeSeconds } ?: 0L
```
Signature (defaulted callbacks so tests omit them):
```kotlin
@Composable
fun InviteScreen(
  state: AppState, now: kotlin.time.Instant = kotlin.time.Clock.System.now(),
  onMode: (String) -> Unit = {}, onMint: (String) -> Unit = {},
  onRevoke: (String) -> Unit = {}, onApprove: (String) -> Unit = {}, onDecline: (String) -> Unit = {},
  onBack: () -> Unit = {},
) { /* ... */ }
```

- [ ] **Step 4: Run to verify they pass** — `--tests "*FormatCountdownTest*" --tests "*InviteScreenTest*" --rerun-tasks`. Expected: PASS. (In tests, `onMint` defaults to `{}` so the auto-mint LaunchedEffect is a no-op — the `showsTitle` test won't loop.)

- [ ] **Step 5: Commit**
```bash
git add apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/InviteScreen.kt apps/ui/src/desktopTest/kotlin/com/sloopworks/dayfold/client/InviteScreenTest.kt
git commit -m "feat(ui): InviteScreen (auto-mint QR/link + outstanding) + countdown"
```

---

### Task 6: Route branch + Members entry point

**Files:**
- Modify: `apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/FeedApp.kt` (params ~L76; `when` branch ~L223)
- Modify: `apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/MembersScreen.kt` (add "Invite a member" action)
- Test: `apps/ui/src/desktopTest/kotlin/com/sloopworks/dayfold/client/AuthFlowUiTest.kt`

**Interfaces:**
- Consumes: `InviteScreen` (Task 5); actions `OpenInvite`, `InviteDismissed`, `InviteModeSelected` (Task 2).
- Produces: new `FeedApp` params `onMintInvite: (String)->Unit = {}`, `onRevokeInvite: (String)->Unit = {}` (NO `onLoadOutstanding` — `Route.Invite` reuses `onLoadApprovals`), a `Route.Invite` branch, and `onInvite` on MembersScreen.

- [ ] **Step 1: Write the failing UI test** (extend `AuthFlowUiTest`; mount idiom from `FeedAppHostTest.kt`: `val store = createAppStore(AppState(route = Route.Members, ...), debug = false)` then `runComposeUiTest { setContent { DayfoldTheme { FeedApp(store) } } }` — all FeedApp params default so `FeedApp(store)` compiles):
```kotlin
@Test fun membersScreen_inviteEntry_routesToInvite() = runComposeUiTest {
  val store = createAppStore(AppState(route = Route.Members, session = Session("a","r"), activeFamilyId = "fam1",
    families = listOf(FamilyMembership(familyId = "fam1", name = "Fam", role = "owner", status = "active"))), debug = false)
  setContent { DayfoldTheme { FeedApp(store) } }
  onNodeWithText("Invite a member").performClick(); waitForIdle()
  assertEquals(Route.Invite, store.state.route)
}
```
(Confirm `FamilyMembership` field names against `Model.kt` before using.)

- [ ] **Step 2: Run to verify it fails** — `--tests "*AuthFlowUiTest*" --rerun-tasks`. Expected: FAIL (no "Invite a member" node / no `Route.Invite` branch).

- [ ] **Step 3: Implement**

`MembersScreen.kt`: add `onInvite: () -> Unit = {}` param; render a prominent "Invite a member" filled button/row (M3 `cs.primary`) above the PENDING/MEMBERS lists.
`FeedApp.kt`:
- Add params `onMintInvite: (String) -> Unit = {}, onRevokeInvite: (String) -> Unit = {}`.
- In the existing `Route.Members -> MembersScreen(...)` call add `onInvite = { store.dispatch(OpenInvite) }`.
- Add:
```kotlin
Route.Invite -> {
  LaunchedEffect(Unit) { onLoadApprovals() }   // one GET → outstanding invites + pending joiners
  InviteScreen(
    state,
    onMode = { store.dispatch(InviteModeSelected(it)) },
    onMint = onMintInvite,
    onRevoke = onRevokeInvite,
    onApprove = onApproveMember, onDecline = onDeclineMember,
    onBack = { store.dispatch(InviteDismissed) },
  )   // InviteScreen defaults `now` itself — no clock threading
}
```

- [ ] **Step 4: Run to verify it passes** — `--tests "*AuthFlowUiTest*" --rerun-tasks`. Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/FeedApp.kt apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/MembersScreen.kt apps/ui/src/desktopTest/kotlin/com/sloopworks/dayfold/client/AuthFlowUiTest.kt
git commit -m "feat(ui): Invite route + Members entry point"
```

---

### Task 7: Shell wiring (Android / iOS / desktop) + Android E2E

**Files:**
- Modify: `apps/androidApp/src/main/kotlin/com/sloopworks/dayfold/android/MainActivity.kt` (~L273)
- Modify: `apps/ui/src/iosMain/kotlin/com/sloopworks/dayfold/client/MainViewController.kt` (~L138)
- Modify: `apps/ui/src/desktopMain/kotlin/com/sloopworks/dayfold/client/Main.kt` (~L75)
- Test: `apps/androidApp/src/androidTest/kotlin/com/sloopworks/dayfold/android/AuthFlowE2ETest.kt`

**Interfaces:** consumes `authEngine.mintInvite/revokeInvite` (Task 3) + FeedApp params (Task 6). (No `loadOutstanding` — `onLoadApprovals` already wired in all three shells.)

- [ ] **Step 1: Write the failing E2E** (this module's Android test DOES use `createComposeRule()` — androidTest is JUnit4 instrumentation; keep that idiom here, unlike desktopTest). Extend `AuthFlowE2ETest` with fake callbacks:
```kotlin
onMintInvite = { mode -> store.dispatch(InviteMinted(MintedInvite("i","TOK","https://x/invite/TOK","adult",mode,"2099-01-01T00:00:00Z"))) },
// after navigating to Members:
composeRule.onNodeWithText("Invite a member").performClick()
composeRule.onNodeWithContentDescription("Invite QR code").assertExists()
```
(The default auto-mint LaunchedEffect calls `onMintInvite("qr")` on entry → dispatches `InviteMinted` → QR renders; no explicit button tap needed.)

- [ ] **Step 2: Run to verify it fails** — `:androidApp:connectedDebugAndroidTest --tests "*AuthFlowE2ETest*"` (needs a running emulator/`ANDROID_SERIAL`). Expected: FAIL/compile-error until Step 3.

- [ ] **Step 3: Wire the three shells** (exact pattern of the adjacent `onLoadApprovals`/`onApproveMember` bindings at each host):

Android `MainActivity.kt`:
```kotlin
onMintInvite = { mode -> lifecycleScope.launch { store.state.activeFamilyId?.let { authEngine.mintInvite(it, mode) } } },
onRevokeInvite = { id -> lifecycleScope.launch { store.state.activeFamilyId?.let { authEngine.revokeInvite(it, id) } } },
```
iOS `MainViewController.kt` + desktop `Main.kt` (both use `scope.launch { ... }`):
```kotlin
onMintInvite = { mode -> scope.launch { store.state.activeFamilyId?.let { authEngine.mintInvite(it, mode) } } },
onRevokeInvite = { id -> scope.launch { store.state.activeFamilyId?.let { authEngine.revokeInvite(it, id) } } },
```

- [ ] **Step 4: Run to verify it passes** — the E2E, then compile ALL targets: `:androidApp:compileDebugKotlin :ui:compileKotlinDesktop :ui:compileKotlinIosArm64 :ui:compileKotlinIosSimulatorArm64 :client:desktopTest`. Expected: all green.

- [ ] **Step 5: Commit**
```bash
git add apps/androidApp/src/main apps/ui/src/iosMain apps/ui/src/desktopMain apps/androidApp/src/androidTest
git commit -m "feat: wire invite mint/revoke in Android, iOS, desktop shells + E2E"
```

---

### Task 8: Full verification + CHANGELOG + backlog

- [ ] **Step 1: Full sweep**
```bash
cd apps && JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew \
  :client:desktopTest :ui:desktopTest \
  :androidApp:compileDebugKotlin :ui:compileKotlinIosArm64 :ui:compileKotlinIosSimulatorArm64 :ui:compileKotlinDesktop
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: On-device drive (verify skill)** — install to an emulator/device (`apps/scripts/ondevice-prod.sh` or `installDebug`), sign in as an owner, Account → Family → "Invite a member": confirm the QR + live countdown render and an outstanding row appears; toggle "Share a link" → confirm a link + "Copy link" works and the URL is `…/invite/<token>`; revoke an outstanding invite → confirm it drops. Screenshot.

- [ ] **Step 3: CHANGELOG** (`CHANGELOG.md`, top):
```markdown
## 2026-07-07 — Owners can invite family members (QR + share link)

### Added (client)
- **"Invite a member" screen.** Account → Family → Invite mints an invite as a **QR
  code** (in-person, one-time, ~15-min TTL) or a **shareable link** (~72-h, up to 5
  uses), with a live expiry countdown and copy-to-clipboard. Outstanding invites list
  with **Revoke**; pending joiners approve/decline inline. Backed by the owner-approved
  invite API (ADR 0011); the raw token is display-only (never persisted or logged).
  Recipient redeems by pasting the link into Join (in-app QR scan + deep-link deferred,
  spec §96/§121). New cross-platform QR rendering via qrose (KMP).
```

- [ ] **Step 4: Update `backlog/now.md` / `backlog/next.md`** (invite-mint UI shipped), commit, push, open PR. **Confirm with the operator before merging** (design-delta note in the PR body: link-mode card + loading/error states extend the signed-off QR mockup; auto-mint-on-entry matches it). Merge on green if approved.
```bash
git add CHANGELOG.md backlog/now.md backlog/next.md
git commit -m "docs: changelog + backlog for owner invite-mint UI"
```

---

## Self-Review

**Spec coverage** (`specs/prototype/05-invite.md` + mockup): mint qr/link + TTL/max_uses (T1/T3) ✓; raw-token-once, never persist (Constraints + `InviteDismissed` clears, T2) ✓; QR encodes URL (T4/T5) ✓; outstanding + revoke (T1/T3/T5) ✓; pending-joiner approve/decline in-surface (T5 reuses `PendingRow`, loaded via `onLoadApprovals` T6) ✓; owner-only (server 403 → `MintFailed("forbidden")`) ✓; rate/cap 429 → `MintFailed("ratelimited")` ✓; adult-only role (static row) ✓; invitee QR-scan/deep-link explicitly OUT ✓.

**Review fixes folded in:** JUnit5 `runComposeUiTest` for all UI tests (not `createComposeRule`, except androidTest which is genuinely JUnit4); `rootReducer` name + no false compile-safety claim (unit tests pin every case); `InviteScreen` owns its `now` default (no FeedApp clock threading); `formatCountdown` scoped to the QR chip, `DateLabels.countdownLabel` for day-scale outstanding rows; `familyApprovals` widened to one GET feeding pending+outstanding (deletes `listInvites`/`loadOutstanding`/`onLoadOutstanding`/its wiring, and fixes stale-pending-on-deep-open); qrose 1.1.2 + builder-block color DSL; `kotlin.time` not `kotlinx.datetime`; `LocalClipboard` not deprecated `LocalClipboardManager`; `inviteBusy` naming; auto-mint-on-entry (removes the undesigned CTA); real test-helper idioms (`runBlocking` + MockEngine; engine seeded via `MemTokenStore(Session)` + `restore()`).

**Type consistency:** `Invite`/`MintedInvite`/`MintResult`/`InviteQueue` (T1) → consumed T2/T3/T5. Actions + `ApprovalsLoaded(pending, invites)` (T2) → T3/T5/T6. `mintInvite(fid,mode)`/`revokeInvite(fid,id)`/`loadApprovals(fid)` (T3) → wired T6/T7. `QrImage(data,modifier,dark,light)` (T4) → T5. `formatCountdown(Long):String` + `InviteScreen(state, now, …)` (T5) → T6.

**Open decisions flagged to operator:** (1) qrose dependency — gated in T4 with a defined fallback; (2) design-delta — link-mode card + loading/error states extend the signed-off QR mockup (T8 PR note); (3) auto-mint-on-entry burns toward the 10-active cap on repeated opens — acceptable slice-1 limit, dedup/reuse is a noted follow-up.
