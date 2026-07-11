# Delta C — Per-Hub Participation Roles & In-App People Management (ADR 0053) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Make ADR 0030's hub allow-list a three-tier, in-app-manageable participation model — a hub owner (or delegated Co-owner) adds people as **Viewer / Contributor / Co-owner**, toggles Family/Restricted, and the server enforces that only the author + Contributors + Co-owners may write the hub.

**Architecture:** Add a `role` column to the existing `resource_visibility` table (the ADR 0030 allow-list). The `hubAudience` read returns each member's participation role; new incremental participant-management endpoints (add/set-role/remove + visibility toggle) replace today's full-replace-only authoring for the in-app path; the hub write-gate gains a `resource_visibility.role`-based check. Client grows hub mutation methods + a People management sheet. Author (`hubs.created_by`) is a permanent implicit Co-owner; the family owner is NOT auto-permitted (ADR 0030 preserved).

**Tech Stack:** API (apps/api TS/Hono/Postgres, vitest) · KMP `:client` (HubClient/HubEngine/models) + `:ui` (HubScreens).

## Global Constraints

- Branch: `design/account-acl-hub-roles` (P1+P1b already committed here). ADR 0053 is **Accepted**.
- API loop from `apps/api`: `export DATABASE_URL=postgres:///fad_test`; test harness provisions its own schema (add the new migration to those lists as prior tasks did); `npx vitest run`.
- KMP loop from `apps/` (`JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`): `./gradlew :client:desktopTest`, `./gradlew :ui:desktopTest`. Gradle works here.
- **Role vocabulary (exact):** `resource_visibility.role ∈ {'viewer','contributor','co_owner'}`. Participation role in API/DTO responses uses the same three strings; the hub **author** (`hubs.created_by`) is surfaced as `'co_owner'` (implicit, permanent) without needing a row. This is a **per-resource** attribute — do NOT touch `memberships.role` (owner|adult), which stays as-is (ADR 0053 item 1 / ADR 0030 item 7).
- **Family-owner-not-auto-permitted preserved** (ADR 0030): being family `owner` grants no hub read/write/manage; only authorship or a `resource_visibility` row does.
- **Visibility semantics unchanged (ADR 0030 + ADR 0053 item 7):** `family` = every active member is an implicit Viewer (read); a `resource_visibility` row on a family hub only *elevates* to contributor/co_owner. `restricted` = only author + allow-listed may read.
- **Write authorization (ADR 0053 item 4):** a member may write a hub iff they are the author OR their `resource_visibility.role ∈ {contributor, co_owner}`. `requireScope(hub:<id>, write)` (ADR 0029) remains the credential gate; the role check is the member gate; BOTH must pass. **Legacy/M0 token (`caller.legacy`, `user_id NULL`) stays write-exempt** (ADR 0030 item 4b) — do not break it.
- **Revocation (ADR 0053 item 5 / ADR 0030 item 2):** every `resource_visibility` mutation (insert/update/delete) and every `hubs.visibility` flip MUST touch `hubs.updated_at` so the keyset `/sync` re-surfaces a tombstone. The current trigger fires only on INSERT/DELETE — extend it to UPDATE.
- **Management authority (ADR 0053 item 5):** only the author or a `co_owner` may add/remove participants, set roles, or flip visibility. The author's row is not removable and its role not editable.

---

## File Structure

**New:**
- `apps/api/migrations/0018_resource_visibility_role.sql` — `role` column + trigger extension.
- `apps/api/test/hub-participants.test.ts`, `apps/api/test/hub-write-role.test.ts`.
- `apps/ui/.../HubPeopleSheet.kt` — the management sheet.
- `apps/ui/src/desktopTest/.../HubPeopleSheetTest.kt`.

**Modified:**
- `apps/api/src/content/hubs.ts` — `hubAudience()` returns participation role; new `setParticipant`/`removeParticipant`/`setHubVisibility` repo fns; `hubWriteGate` role check.
- `apps/api/src/content/write-guard.ts` — role-based write authority.
- `apps/api/src/app.ts` — new participant-management routes + audience response.
- `apps/client/.../HubClient.kt` — mutation methods; `.../HubEngine.kt` — engine ops + actions; `.../Model.kt` — `HubAudienceMember.participationRole`/`canManage`, actions; `.../fake/FakeBackend.kt`.
- `apps/ui/.../HubScreens.kt` — open the management sheet; role labels.

---

## Task 1: [DC1] Schema — `resource_visibility.role` + audience returns participation role

**Files:**
- Create: `apps/api/migrations/0018_resource_visibility_role.sql`
- Modify: `apps/api/src/content/hubs.ts` (`hubAudience()` SELECT), `apps/api/src/app.ts` (audience route — response already forwards `hubs.hubAudience` rows, so participation role flows through)
- Test: extend `apps/api/test/hub-api.test.ts` (the `audience:` test at ~line 77)

**Interfaces:**
- Produces: `hubAudience()` rows gain `participation_role` (`'viewer'|'contributor'|'co_owner'|null`) — `'co_owner'` when `m.user_id = h.created_by`; else the member's `resource_visibility.role` if a row exists; else `null` (a family-hub member with no explicit grant = implicit viewer, `permitted=true`, `participation_role=null`).

- [ ] **Step 1: Write the failing test** — extend `hub-api.test.ts` audience test: after adding bob to a restricted hub with role `contributor` (insert a `resource_visibility` row incl. role — the test can seed via the new column directly for DC1), assert the audience row for bob has `participation_role === 'contributor'` and the author's row has `participation_role === 'co_owner'`.

- [ ] **Step 2: Run to verify it fails** — `cd apps/api && export DATABASE_URL=postgres:///fad_test && npx vitest run hub-api` → FAIL (column/field missing).

- [ ] **Step 3: Migration + SELECT**

`0018_resource_visibility_role.sql`:
```sql
-- ADR 0053: per-hub participation role on the allow-list. viewer = today's read grant
-- (migration default → zero behavior change); contributor = read+write; co_owner = +manage.
ALTER TABLE resource_visibility ADD COLUMN role text NOT NULL DEFAULT 'viewer';

-- ADR 0053 item 5: a role change must also re-surface the hub as a tombstone to the
-- affected member. Extend the existing touch trigger (0009) to fire on UPDATE too.
CREATE OR REPLACE FUNCTION trg_resvis_touch_hub_fn() RETURNS trigger AS $$
BEGIN
  UPDATE hubs SET updated_at = now()
   WHERE family_id = COALESCE(NEW.family_id, OLD.family_id)
     AND id = COALESCE(NEW.hub_id, OLD.hub_id);
  RETURN NULL;
END; $$ LANGUAGE plpgsql;
DROP TRIGGER IF EXISTS trg_resvis_touch_hub ON resource_visibility;
CREATE TRIGGER trg_resvis_touch_hub
  AFTER INSERT OR UPDATE OR DELETE ON resource_visibility
  FOR EACH ROW EXECUTE FUNCTION trg_resvis_touch_hub_fn();
```
(Confirm the exact existing trigger/function name in `0009_visibility.sql:31-40` and match it; if the existing function body differs, preserve its behavior and only widen the event to include UPDATE.)

`hubAudience()` SELECT — add participation role via LEFT JOIN, distinct from the family `role`:
```sql
SELECT m.user_id AS uid, u.display_name, u.avatar_color, u.avatar_ref, m.role,
       CASE WHEN m.user_id = h.created_by THEN 'co_owner' ELSE rv2.role END AS participation_role,
       (h.visibility = 'family'
        OR m.user_id = h.created_by
        OR EXISTS (SELECT 1 FROM resource_visibility rv
                    WHERE rv.family_id=$1 AND rv.hub_id=$2 AND rv.user_id=m.user_id)) AS permitted
  FROM memberships m
  JOIN users u ON u.id = m.user_id
  JOIN hubs h ON h.family_id=$1 AND h.id=$2
  LEFT JOIN resource_visibility rv2 ON rv2.family_id=$1 AND rv2.hub_id=$2 AND rv2.user_id=m.user_id
 WHERE m.family_id=$1 AND m.status='active'
 ORDER BY (m.role='owner') DESC, u.display_name, m.user_id
```

- [ ] **Step 4: Run to verify it passes** — `npx vitest run hub-api` then full `npx vitest run` (add `0018_...sql` to any self-provisioning test file's migration list that touches audience, as prior tasks did).

- [ ] **Step 5: Commit** — `git commit -m "feat(api): resource_visibility.role column + participation_role in hub audience (ADR 0053)"`

---

## Task 2: [DC2] Participant-management API (add / set-role / remove / visibility toggle)

**Files:**
- Modify: `apps/api/src/content/hubs.ts` (repo fns), `apps/api/src/app.ts` (routes)
- Test: `apps/api/test/hub-participants.test.ts`

**Interfaces:**
- Produces:
  - `PUT /families/:fid/hubs/:id/participants/:uid` body `{role}` — upsert a `resource_visibility` row with role (`viewer|contributor|co_owner`); author/co_owner-gated; author `:uid` rejected (400 `{type:"author-immutable"}`).
  - `DELETE /families/:fid/hubs/:id/participants/:uid` — remove the row; author-gated same; removing author rejected.
  - `PUT /families/:fid/hubs/:id/visibility` body `{visibility:"family"|"restricted"}` — flip; author/co_owner-gated.
  - Repo fns `setParticipant(familyId, hubId, uid, role)`, `removeParticipant(familyId, hubId, uid)`, `setHubVisibility(familyId, hubId, visibility)` and a gate `canManageHub(familyId, hubId, caller)` (author OR `resource_visibility.role='co_owner'`).

- [ ] **Step 1: Write the failing test** — `hub-participants.test.ts`: author adds bob as `viewer` (200), promotes to `contributor` (200), a non-manager member (carl) trying to add/remove → 403, removing the author → 400, flip visibility family→restricted by a co_owner → 200. Mirror `hub-api.test.ts` harness (dev-token, family, hub, members).

- [ ] **Step 2: Run to verify it fails** — `npx vitest run hub-participants` → FAIL (routes 404).

- [ ] **Step 3: Implement gate + repo fns + routes**

`canManageHub` (hubs.ts): author (`hub.created_by === caller.userId`) OR `EXISTS resource_visibility rv WHERE ... user_id=caller.userId AND role='co_owner'`. Legacy caller (`caller.legacy`) = allowed (M0 operator).

`setParticipant`: `INSERT INTO resource_visibility(family_id,hub_id,user_id,role) VALUES (...) ON CONFLICT (family_id,hub_id,user_id) DO UPDATE SET role=EXCLUDED.role` (the trigger touches `hubs.updated_at`). Reject `uid === hub.created_by`. `removeParticipant`: `DELETE ... AND user_id<>hub.created_by` (author never removable). `setHubVisibility`: `UPDATE hubs SET visibility=$3, updated_at=now() WHERE ...` (0011 fan-out trigger already handles the subtree on visibility change).

Routes in app.ts (mirror the audience route's `authorizeTenant` + a `canManageHub` gate; validate `role`/`visibility` enums, 400 on bad value; 404 uniform-absence if hub not visible/exists):
```ts
app.put("/families/:fid/hubs/:id/participants/:uid", async (c) => { /* authorizeTenant → getHub/404 → canManageHub/403 → validate role → setParticipant → 200 */ });
app.delete("/families/:fid/hubs/:id/participants/:uid", async (c) => { /* … removeParticipant … */ });
app.put("/families/:fid/hubs/:id/visibility", async (c) => { /* … validate visibility → setHubVisibility … */ });
```

- [ ] **Step 4: Run to verify it passes** — `npx vitest run hub-participants` then full suite.

- [ ] **Step 5: Commit** — `git commit -m "feat(api): in-app hub participant management (add/set-role/remove + visibility toggle, author/co_owner-gated)"`

---

## Task 3: [DC3] Write-gate — restrict hub writes to author + contributor + co_owner

> **Behavior-change warning:** today EVERY credential holds a blanket `content:write` grant, so any member can write any visible hub. This task tightens that: a member who is only a `viewer` (or not on a restricted hub) can no longer write. Keep the **legacy/M0 token write-exempt** (`caller.legacy`). Update existing tests that assumed a plain member could write.

**Files:**
- Modify: `apps/api/src/content/write-guard.ts` (`hubWriteGate`), `apps/api/src/content/hubs.ts` (a `hubWritableByMember` helper)
- Test: `apps/api/test/hub-write-role.test.ts` + fix any existing write test that breaks

**Interfaces:**
- Produces: `hubWriteGate` returns `"denied"` when the caller is a real member who is neither the author nor a `resource_visibility.role ∈ {contributor,co_owner}` — in addition to the existing `requireScope(hub:<id>, write)` and visibility checks. `caller.legacy` bypasses the role check (M0). A `hubWritableByMember(hub, caller, roleLookup)` pure helper mirrors `hubVisible` for unit-testing.

- [ ] **Step 1: Write the failing tests** — unit (`hub-visibility-unit.test.ts` sibling) for `hubWritableByMember`: author→true, contributor→true, co_owner→true, viewer→false, non-listed-on-restricted→false, legacy→true. Integration (`hub-write-role.test.ts`): a viewer member PUTting hub content → 403/denied; a contributor → ok.

- [ ] **Step 2: Run to verify it fails** — `npx vitest run hub-write-role hub-visibility-unit` → FAIL.

- [ ] **Step 3: Implement** — add `hubWritableByMember(hub, caller, roleLookup)` in hubs.ts (author OR role∈{contributor,co_owner} OR caller.legacy). In `hubWriteGate`, after the `requireScope` check, look up the caller's `resource_visibility.role` (extend `allowListFor` into a `roleFor(familyId, hubId): Map<uid,role>` or a single-user lookup) and return `"denied"` unless `hubWritableByMember` passes.

- [ ] **Step 4: Run to verify it passes** — `npx vitest run` (fix any pre-existing write test where a bare member wrote — give that member a `contributor` grant in the fixture, or mark the caller legacy where that reflects the M0 intent). Document each fixed test in the report.

- [ ] **Step 5: Commit** — `git commit -m "feat(api): gate hub writes on resource_visibility.role (author/contributor/co_owner); M0 exempt (ADR 0053)"`

---

## Task 4: [DC4] Client — participation role on model + hub mutation methods + engine ops

**Files:**
- Modify: `apps/client/.../Model.kt` (`HubAudienceMember.participationRole`/`canManage`; actions), `apps/client/.../HubClient.kt` (mutation methods), `apps/client/.../HubEngine.kt` (ops), `apps/client/.../fake/FakeBackend.kt`
- Test: `HubClientTest.kt`, `HubEngineTest.kt`

**Interfaces:**
- Produces: `HubAudienceMember.participationRole: String? = null` (`@SerialName("participation_role")`) and a derived `HubAudience.canManage: Boolean` (true if the current user is author/co_owner — computed from the response, or a server-provided flag; simplest: server adds `can_manage` to the audience response in DC2, client reads it). `HubClient.setParticipant(access, fid, hubId, uid, role)`, `removeParticipant(...)`, `setVisibility(access, fid, hubId, visibility)`. `HubEngine.setParticipant/removeParticipant/setVisibility` dispatching optimistic actions + reload. New actions in Model.kt following `HubAudienceLoaded`/`AudienceFailed`.

- [ ] **Step 1: Write the failing test** — `HubClientTest`: `setParticipant` issues `PUT …/participants/<uid>` with `{role}` body; `HubEngineTest`: a successful setParticipant dispatches a reload/updated action. Mirror `loadAudience`'s existing test.
- [ ] **Step 2: Run to verify it fails** — `cd apps && ./gradlew :client:desktopTest --tests '*HubClientTest*' --tests '*HubEngineTest*'` → FAIL.
- [ ] **Step 3: Implement** the model field, `HubClient` mutation methods (mirror `AuthClient.updateAvatar`'s ktor PATCH/PUT + `@Serializable` req body), `HubEngine` ops (mirror `loadAudience` mutex/callWithRefresh + optimistic dispatch + reload on success / failure action), and FakeBackend PUT/DELETE branches for the participant routes.
- [ ] **Step 4: Run to verify it passes** — scoped then full `./gradlew :client:desktopTest`.
- [ ] **Step 5: Commit** — `git commit -m "feat(client): hub participant-management client + engine + participation role model (ADR 0053)"`

---

## Task 5: [DC5] People management sheet UI

**Files:**
- Create: `apps/ui/.../HubPeopleSheet.kt` + `HubPeopleSheetTest.kt`
- Modify: `apps/ui/.../HubScreens.kt` (open the management sheet when the caller `canManage`; keep `WhoCanSeeSheet` read-only for non-managers)
- Modify: snapshot registry + goldens

**Interfaces:**
- Consumes: DC4's `participationRole`/`canManage`, `HubEngine.setParticipant/removeParticipant/setVisibility`, `DayfoldAvatar`, `FunAvatars`.
- Produces: `HubPeopleSheet(audience: HubAudience, canManage: Boolean, onSetRole, onRemove, onSetVisibility, onAddPeople, onDismiss)` — Family/Restricted `SegmentedButton` (manager-only, with the ADR 0030 "who loses access" confirm on family→restricted), per-participant row with avatar + name + role `DropdownMenu` (Viewer/Contributor/Co-owner) + remove (author row pinned, Owner badge, locked), an inline role explainer, and "Add people". Non-managers get the read-only `WhoCanSeeSheet` (unchanged). The family-owner-not-permitted "no access" state on a restricted hub is already handled by the audience 404 → keep.

- [ ] **Step 1: Write the failing test** — `HubPeopleSheetTest` (mirror `HubScreenTest.whoCanSeeSheetRenders…`): a manager view shows a role `DropdownMenu` and picking `Contributor` for a member emits `onSetRole(uid, "contributor")`; the author row shows an "Owner" badge and no remove; `canManage=false` renders no controls.
- [ ] **Step 2: Run to verify it fails** — `cd apps && ./gradlew :ui:desktopTest --tests '*HubPeopleSheet*'` → FAIL.
- [ ] **Step 3: Implement** the sheet (M3 `ModalBottomSheet`, `SegmentedButton`, `DropdownMenu`, `AssistChip`) + wire `HubScreens` to open it when `canManage`, calling the DC4 engine ops. Add the visibility-flip confirm (`AlertDialog`/sheet naming who loses access).
- [ ] **Step 4: Run to verify it passes** — scoped then full `./gradlew :ui:desktopTest`; add a snapshot scene, `./gradlew snapshotUi`, commit goldens.
- [ ] **Step 5: Commit** — `git commit -m "feat(ui): hub People management sheet — roles, visibility toggle, add/remove (ADR 0053)"`

---

## Self-Review

- **ADR 0053 coverage:** role column (DC1) ✓; three tiers viewer/contributor/co_owner (DC1 audience + DC2 set-role + DC5 dropdown) ✓; author=implicit permanent co_owner (DC1 `'co_owner'` synth + DC2 author-immutable) ✓; family-owner-not-auto (unchanged, preserved) ✓; write via role + requireScope both, M0 exempt (DC3) ✓; revocation touch on role UPDATE (DC1 trigger) ✓; contributor→viewer downgrade re-surfaces (DC1 trigger fires on UPDATE) ✓; roles hubs-only, cards untouched ✓; family=implicit viewers, named elevates (DC1 `permitted` unchanged; DC3 write requires explicit role) ✓; management author/co_owner-only (DC2 gate) ✓.
- **Behavior-change flagged:** DC3 tightens today's blanket-write; legacy/M0 exempt; existing write tests updated (documented per-test).
- **Type consistency:** `participation_role`/`participationRole`, `role ∈ {viewer,contributor,co_owner}`, `canManage`/`can_manage`, `setParticipant`/`removeParticipant`/`setVisibility` used consistently API↔client↔UI.
- **Deferred:** the `hub:<id>:write` per-credential grant minting/revocation on role change (ADR 0053 item 5 "belt-and-suspenders" credential side) is approximated in DC3 by the server-side member-role check as the source of truth; a follow-up can add per-hub grant minting if the blanket-grant model is later tightened. Noted, not silently dropped.
