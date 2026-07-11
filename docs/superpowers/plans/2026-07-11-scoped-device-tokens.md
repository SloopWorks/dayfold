# Scoped CLI/Device Tokens Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Let the operator grant a CLI/device token access to **only selected hubs** (`hub:<id>:read|write`) instead of the blanket `content:{read,write,delete}`, at device-approve time.

**Architecture:** Additive to the RFC 8628 device grant. The approver's scope choice is stored on `device_authorizations.granted_scopes`; `redeem` mints exactly those grants (else blanket). App-session credentials and the blanket-authoring path are untouched. Spec: `docs/superpowers/specs/2026-07-11-scoped-device-tokens-design.md`.

**Tech Stack:** API (apps/api TS/Hono/Postgres, vitest) · KMP `:client` (AuthClient/AuthEngine) + `:ui` (DeviceApprovalScreens).

## Global Constraints

- Worktree branch: `feat/role-scoped-write-grants` (off latest main).
- API loop from `apps/api`: `export DATABASE_URL=postgres:///fad_test`; test harness provisions its own schema (add the new migration to self-provisioning test files that hit device-approve/redeem); `npx vitest run`.
- KMP loop from `apps/` (`JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`): `./gradlew :client:desktopTest`, `./gradlew :ui:desktopTest`. Gradle works here.
- Scope vocabulary (ADR 0029, existing): global `content:read|write|delete`, or `hub:<id>:read|write`. `scopeAllows`/`grantedHubIds`/`grantScopes` in `apps/api/src/auth/scope.ts` — do NOT change their semantics.
- **`granted_scopes` NULL ⇒ blanket default** (back-compat for every unscoped grant). A hub-scoped token gets `hub:<id>:read` + `hub:<id>:write` per selected hub — NO global `content:write` (so it cannot create hubs/cards).
- Do NOT narrow the blanket grant for app sessions / existing credentials, and do NOT touch the M0/legacy authoring path (that was the rejected "Full" option).
- Migration number: `0019` (latest is `0018_resource_visibility_role.sql`). Forward-only.

---

## File Structure

**New:** `apps/api/migrations/0019_device_grant_scopes.sql`; `apps/api/test/device-scope.test.ts`; a `HubScopePicker`-style section in `DeviceApprovalScreens.kt` + its test.

**Modified:** `apps/api/src/auth/device.ts` (redeem), `apps/api/src/app.ts` (approve route), `apps/api/src/auth/scope.ts` (a `hubGrantsFor` helper), `apps/client/.../AuthClient.kt` + `AuthEngine.kt` + `fake/FakeBackend.kt`, `apps/ui/.../DeviceApprovalScreens.kt` + `FeedApp.kt` + the 3 platform mains (approve callback gains the scope arg).

---

## Task 1: Migration + redeem mints from `granted_scopes`

**Files:**
- Create: `apps/api/migrations/0019_device_grant_scopes.sql`
- Modify: `apps/api/src/auth/device.ts` (`redeem`, ~lines 35-72), `apps/api/src/auth/scope.ts` (add `hubGrantsFor`)
- Test: `apps/api/test/device-scope.test.ts`

**Interfaces:**
- Produces: `device_authorizations.granted_scopes text[]` (nullable). `redeem` mints `granted_scopes` when non-null (both `credentials.scopes` and `credential_grants`), else the blanket `{content:read,write,delete}`. `hubGrantsFor(hubIds: string[]): string[]` → `["hub:<id>:read","hub:<id>:write", …]`.

- [ ] **Step 1: Write the failing test** — `device-scope.test.ts`: drive a full device grant where the approval stored `granted_scopes = {hub:H1:read,hub:H1:write}`; after redeem, assert `resolveGrants(cid)` (or the credential's grants) equals exactly that set (no `content:write`); and a second case with `granted_scopes = NULL` yields the blanket set. Mirror the existing device-grant test harness (`grep -l "device/authorize\|redeem\|device/approve" apps/api/test`).

- [ ] **Step 2: Run to verify it fails** — `cd apps/api && export DATABASE_URL=postgres:///fad_test && npx vitest run device-scope` → FAIL (column missing / blanket minted).

- [ ] **Step 3: Migration + redeem + helper**

`0019_device_grant_scopes.sql`:
```sql
-- ADR 0029: per-hub device-grant scoping. NULL granted_scopes = blanket default
-- (content:read/write/delete) — preserves every unscoped/in-flight grant. A non-null
-- array is the exact grant set the redeemed CLI credential receives.
ALTER TABLE device_authorizations ADD COLUMN granted_scopes text[];
```

`scope.ts` — add:
```ts
// Materialize a hub-id list into read+write grant strings (ADR 0029 resource scopes).
export function hubGrantsFor(hubIds: string[]): string[] {
  return hubIds.flatMap((id) => [`hub:${id}:read`, `hub:${id}:write`]);
}
```

`device.ts` `redeem` — read the row's `granted_scopes` (add it to the SELECT that fetches the approved authorization), then:
```ts
const scopes = row.granted_scopes ?? ["content:read", "content:write", "content:delete"];
// mint the credential with `scopes` for BOTH credentials.scopes and the grant rows:
//   INSERT INTO credentials(..., scopes) VALUES (..., <scopes as pg array>)
//   grantScopes(cid, scopes, client)
```
(Preserve the single-transaction lazy-mint. Build the `credentials.scopes` array literal from `scopes` with a parameterized array, not string interpolation.)

- [ ] **Step 4: Run to verify it passes** — `npx vitest run device-scope` then full `npx vitest run` (add `0019` to any self-provisioning device test file's migration list).

- [ ] **Step 5: Commit** — `git commit -m "feat(api): device_authorizations.granted_scopes + redeem mints scoped grants (ADR 0029)"`

---

## Task 2: Approve route accepts + validates the scope selection

**Files:**
- Modify: `apps/api/src/app.ts` (`POST /families/:fid/device/approve`, ~line 920)
- Test: extend `device-scope.test.ts`

**Interfaces:**
- Produces: `POST /families/:fid/device/approve` accepts an optional body: absent/`{"scope":"full"}` ⇒ `granted_scopes=NULL`; `{"scope":"hubs","hubs":[...]}` ⇒ validate + store `hubGrantsFor(hubs)`. Invalid → 400.

- [ ] **Step 1: Write the failing test** — extend `device-scope.test.ts`: approving with `{scope:"hubs", hubs:[H1]}` (H1 a hub in the caller's family) stores `granted_scopes={hub:H1:read,hub:H1:write}` and the redeemed token then passes `requireScope(hub:H1,write)` but FAILS `requireScope(hub:H2,write)` and `requireScope(content,write)`. Approving with an unknown/other-family hub → 400; `{scope:"hubs",hubs:[]}` → 400; `{scope:"full"}` → `granted_scopes` stays NULL (blanket).

- [ ] **Step 2: Run to verify it fails** — `npx vitest run device-scope` → FAIL.

- [ ] **Step 3: Implement the approve-route scope handling**

In `POST /families/:fid/device/approve`, after the existing auth/tenancy/lockout checks and before/within the `UPDATE device_authorizations SET status='approved' …`:
```ts
const body = await c.req.json().catch(() => null);
const mode = body?.scope;   // undefined | "full" | "hubs"
let grantedScopes: string[] | null = null;   // null = blanket
if (mode === "hubs") {
  const hubs: unknown = body?.hubs;
  if (!Array.isArray(hubs) || hubs.length === 0) return c.json({ type: "bad-scope" }, 400);
  for (const h of hubs) {
    if (typeof h !== "string") return c.json({ type: "bad-scope" }, 400);
    const e = idError(h); if (e) return c.json(e, 422);   // reuse the content-id charset guard
    const exists = await q(`SELECT 1 FROM hubs WHERE family_id=$1 AND id=$2`, [fid, h]);
    if (!exists || exists.rowCount === 0) return c.json({ type: "bad-scope" }, 400);
  }
  const { hubGrantsFor } = await import("./auth/scope.ts");
  grantedScopes = hubGrantsFor(hubs as string[]);
}
// include granted_scopes in the approve UPDATE (parameterized text[]).
```
Add `granted_scopes=$N` to the approve `UPDATE … SET status='approved', …`. Keep the audit event; optionally add the scope to its detail.

- [ ] **Step 4: Run to verify it passes** — `npx vitest run device-scope` then full `npx vitest run` (fix any existing device-approve test that now must send/withstand the new optional body — they should be unaffected since absent body ⇒ blanket).

- [ ] **Step 5: Commit** — `git commit -m "feat(api): device/approve accepts per-hub scope selection (validated, stored)"`

---

## Task 3: Client — thread the scope through approveDevice

**Files:**
- Modify: `apps/client/.../AuthClient.kt` (`approveDevice`), `apps/client/.../AuthEngine.kt` (`approveDevice`), `apps/client/.../fake/FakeBackend.kt`
- Test: `AuthClientTest` (request body), `AuthEngineTest` if the op is unit-testable

**Interfaces:**
- Produces: `AuthClient.approveDevice(access, fid, userCode, scope: DeviceScope? = null)` where `sealed DeviceScope { Full; data class Hubs(hubIds: List<String>) }` (or a simpler `hubIds: List<String>? = null`, null = full). Sends the matching body (`{scope:"hubs",hubs:[...]}` or nothing). `AuthEngine.approveDevice(fid, userCode?, scope)` threads it.

- [ ] **Step 1: Write the failing test** — `AuthClientTest`: `approveDevice(..., hubIds=listOf("H1"))` issues `POST …/device/approve` with body `{"scope":"hubs","hubs":["H1"]}`; `approveDevice(...)` with no scope sends the current body (full/blanket). Mirror the existing device-approve client test (`grep -n approveDevice apps/client/src/desktopTest -r`).

- [ ] **Step 2: Run to verify it fails** — `cd apps && ./gradlew :client:desktopTest --tests '*AuthClientTest*'` → FAIL.

- [ ] **Step 3: Implement** — add the optional scope param to `AuthClient.approveDevice` (serialize `{scope:"hubs",hubs}` when hubs non-empty, else keep current body); thread through `AuthEngine.approveDevice`; update the FakeBackend device-approve branch to accept the body. Read the current `approveDevice` signatures first and extend them (default arg = full ⇒ existing callers unaffected).

- [ ] **Step 4: Run to verify it passes** — scoped then full `./gradlew :client:desktopTest`.

- [ ] **Step 5: Commit** — `git commit -m "feat(client): approveDevice carries an optional per-hub scope"`

---

## Task 4: UI — the scope picker on the device-approve screen

**Files:**
- Modify: `apps/ui/.../DeviceApprovalScreens.kt` (the scope row ~line 288), `apps/ui/.../FeedApp.kt` (onApproveDevice signature), the 3 platform mains (`Main.kt`, `MainViewController.kt`, `MainActivity.kt`)
- Test: `DeviceApprovalScreens` UI test

**Interfaces:**
- Consumes: Task 3's `AuthEngine.approveDevice(fid, userCode, scope)`. Produces: `onApproveDevice` now carries the scope selection (extend its type: `(hubIds: List<String>?) -> Unit`, null = full).

- [ ] **Step 1: Write the failing test** — `DeviceApprovalScreens` test (mirror an existing screen test): default is **Full access** (approve emits `null`/full); selecting **Only these hubs** + tapping a hub then Approve emits that hub id; in hubs-mode with no hub selected, Approve is disabled. Build the screen with `AppState(hubs = listOf(Hub("H1", …)))` + the pending device.

- [ ] **Step 2: Run to verify it fails** — `cd apps && ./gradlew :ui:desktopTest --tests '*DeviceApproval*'` → FAIL.

- [ ] **Step 3: Implement** — replace the informational scope row (~288) with a `SegmentedButton` [Full access · Only these hubs]; in hubs-mode render a checkable list of `state.hubs` (title + a check); track the selected hub ids in local state; `canApprove` additionally requires ≥1 hub in hubs-mode; pass the selection (null for full) to `onApprove`. Thread the new `onApproveDevice` signature through FeedApp + the 3 platform mains → `authEngine.approveDevice(fid, userCode, scope)`.

- [ ] **Step 4: Run to verify it passes** — scoped then full `./gradlew :ui:desktopTest`; regenerate snapshot goldens if a device-approve scene shifted (macOS only is fine).

- [ ] **Step 5: Commit** — `git commit -m "feat(ui): device-approve per-hub scope picker (Full / selected hubs)"`

---

## Self-Review

- **Spec coverage:** granted_scopes column + redeem source (T1) ✓; approve validation + store (T2) ✓; client thread (T3) ✓; UI picker (T4) ✓; back-compat null⇒blanket (T1/T2) ✓; enforcement asserts scoped-can't-create/other-hub (T2 test) ✓; non-goals respected (no blanket narrowing, no role-sync) ✓.
- **Placeholder scan:** each step has concrete SQL/TS/Kotlin or a precise mirror-reference with a grep to locate the real signature (device flow spans files this plan doesn't fully quote).
- **Type consistency:** `granted_scopes` (pg `text[]`), `hubGrantsFor(hubIds)→["hub:<id>:read","hub:<id>:write"]`, approve body `{scope:"full"|"hubs", hubs:[…]}`, client `approveDevice(..., hubIds: List<String>? = null)`, `onApproveDevice: (List<String>?) -> Unit` used consistently API↔client↔UI.
