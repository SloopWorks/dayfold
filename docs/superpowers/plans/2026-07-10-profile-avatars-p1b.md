# Profile Avatars — P1b (Deltas B/D: roster + hub-audience display) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Show each member's chosen avatar (fun-avatar or monogram tint) in the family members roster and the hub "Who can see this" audience sheet — completing Deltas B/D on top of P1's storage.

**Architecture:** P1 already shipped storage (`users.avatar_color/avatar_ref`), the shared `DayfoldAvatar(name, size, avatarColorKey, avatarRef, …)`, and refactored the roster/audience call sites to `DayfoldAvatar`. P1b only *feeds the data through*: two API SELECTs project the columns, three client models carry them, and the existing `DayfoldAvatar` call sites pass them. No new UI, no schema.

**Tech Stack:** API (apps/api TS/Hono/Postgres, vitest) · KMP `:client` models + `:ui` display (Kotlin 2.3.20, Compose-MP).

## Global Constraints

- Branch: `design/account-acl-hub-roles` (already checked out; P1 is committed here up to `263d09d`).
- API loop from `apps/api`: `export DATABASE_URL=postgres:///fad_test`; the harness in each test file provisions its own schema (migrations incl. `0017_user_avatar.sql`). `npx vitest run`.
- KMP loop from `apps/` (`JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`): `./gradlew :client:desktopTest`, `./gradlew :ui:desktopTest`. Gradle works here.
- Wire field names are snake_case (`avatar_color`, `avatar_ref`); client DTOs map via `@SerialName`. Both nullable; null ⇒ `DayfoldAvatar` renders monogram (fallback already correct in P1).
- Do NOT change `DayfoldAvatar`, `FunAvatars`, the picker, or `/auth/me` — those are P1 and done. P1b is additive projection only.

---

## File Structure

**Modified:**
- `apps/api/src/app.ts:472-484` (`GET /families/:fid/members` SELECT) — add `u.avatar_color, u.avatar_ref`.
- `apps/api/src/content/hubs.ts` (`hubAudience()` SELECT) — add `u.avatar_color, u.avatar_ref`.
- `apps/client/.../AuthClient.kt` — `FamilyMember`, `PendingMember` DTOs gain `avatarColor`/`avatarRef` (`@SerialName`).
- `apps/client/.../Model.kt:369-374` — `HubAudienceMember` gains `avatarColor`/`avatarRef`.
- `apps/client/.../fake/FakeBackend.kt` — seed a couple of avatar values in the fake roster/audience so debug UI shows them.
- `apps/ui/.../MembersScreen.kt` (`MemberRow` + `PendingRow` `DayfoldAvatar` calls) — pass `avatarColorKey`/`avatarRef`.
- `apps/ui/.../HubScreens.kt` (`AudienceRow` `DayfoldAvatar` call) — pass `avatarColorKey`/`avatarRef`.
- Tests: `apps/api/test/*` (extend roster + audience tests), `apps/client/.../*` (DTO parse), snapshot scene states.

---

## Task 1: API — project avatar columns into roster + hub audience

**Files:**
- Modify: `apps/api/src/app.ts` (`GET /families/:fid/members`, ~line 478 SELECT), `apps/api/src/content/hubs.ts` (`hubAudience()` SELECT)
- Test: extend the existing roster test and the hub-audience test (find them: `grep -rl "/families/.*/members\|hubAudience\|/audience" apps/api/test`)

**Interfaces:**
- Produces: `GET /families/:fid/members` → `members[]` rows now include `avatar_color`, `avatar_ref`; `hubAudience` rows (served by the audience endpoint) include `avatar_color`, `avatar_ref`.

- [ ] **Step 1: Write the failing test** — extend the roster + audience tests to assert the fields exist. Example (adapt to the real test file's harness — it already sets up a family with a member and a hub):

```ts
it("roster rows carry avatar fields", async () => {
  // after a member sets an avatar via PATCH /auth/me { avatar_ref: "avatar:fox-01", avatar_color: "teal" }
  const r = await fetch(`${API}/families/${fid}/members`, { headers: { authorization: `Bearer ${access}` } });
  const { members } = await r.json();
  const me = members.find((m: any) => m.uid === myUid);
  expect(me.avatar_ref).toBe("avatar:fox-01");
  expect(me.avatar_color).toBe("teal");
});
```
(Mirror for the hub-audience endpoint: after setting an avatar, the audience row for that member carries `avatar_ref`/`avatar_color`.)

- [ ] **Step 2: Run to verify it fails** — `cd apps/api && export DATABASE_URL=postgres:///fad_test && npx vitest run <roster-test-file>` → FAIL (`avatar_ref` undefined).

- [ ] **Step 3: Add the columns to both SELECTs**

`app.ts` roster SELECT — add the two columns to the projection:
```ts
`SELECT m.user_id AS uid, u.display_name, u.avatar_color, u.avatar_ref, m.role, m.status, m.joined_at
   FROM memberships m JOIN users u ON u.id = m.user_id
  WHERE m.family_id = $1 AND m.status = 'active'
  ORDER BY (m.role = 'owner') DESC, m.joined_at`
```

`hubs.ts` `hubAudience()` SELECT — add `u.avatar_color, u.avatar_ref` to the projection alongside `u.display_name`:
```ts
`SELECT m.user_id AS uid, u.display_name, u.avatar_color, u.avatar_ref, m.role,
        (h.visibility = 'family' OR m.user_id = h.created_by
         OR EXISTS (SELECT 1 FROM resource_visibility rv
                     WHERE rv.family_id=$1 AND rv.hub_id=$2 AND rv.user_id=m.user_id)) AS permitted
   FROM memberships m
   JOIN users u ON u.id = m.user_id
   JOIN hubs h ON h.family_id=$1 AND h.id=$2
  WHERE m.family_id=$1 AND m.status='active'
  ORDER BY (m.role='owner') DESC, u.display_name, m.user_id`
```
(Both endpoints return `rows` directly as JSON, so the new columns flow into the response with no mapping change. If a pending-members SELECT exists on the same path and feeds `PendingMember`, add the columns there too.)

- [ ] **Step 4: Run to verify it passes** — `npx vitest run <roster + audience test files>` → PASS; then full `npx vitest run` (no regressions — other tests asserting exact roster/audience row shape may need the new keys; update them if they use strict deep-equal).

- [ ] **Step 5: Commit**
```bash
git add apps/api/src/app.ts apps/api/src/content/hubs.ts apps/api/test/
git commit -m "feat(api): project avatar_color/avatar_ref into roster + hub audience"
```

---

## Task 2: Client — carry avatar fields on member models

**Files:**
- Modify: `apps/client/.../AuthClient.kt` (`FamilyMember`, `PendingMember`), `apps/client/.../Model.kt:369-374` (`HubAudienceMember`), `apps/client/.../fake/FakeBackend.kt`
- Test: extend the existing DTO-parse tests (`AuthClientTest`, and the audience-parse path)

**Interfaces:**
- Consumes: Task 1's wire fields.
- Produces: `FamilyMember`, `PendingMember`, `HubAudienceMember` each gain `val avatarColor: String? = null` (`@SerialName("avatar_color")`) and `val avatarRef: String? = null` (`@SerialName("avatar_ref")`).

- [ ] **Step 1: Write the failing test** — add a case asserting a roster/audience JSON payload with `avatar_ref`/`avatar_color` deserializes onto the model (mirror the existing `AuthClientTest` roster-parse case; find it with `grep -n "FamilyMember\|/members\|HubAudienceMember" apps/client/src/desktopTest -r`).

```kotlin
@Test fun familyMemberParsesAvatarFields() {
  val m = json.decodeFromString<FamilyMember>(
    """{"uid":"U1","display_name":"Leo","avatar_color":"teal","avatar_ref":"avatar:fox-01","role":"adult","status":"active"}""")
  assertEquals("avatar:fox-01", m.avatarRef)
  assertEquals("teal", m.avatarColor)
}
```

- [ ] **Step 2: Run to verify it fails** — `cd apps && ./gradlew :client:desktopTest --tests '*AuthClientTest*'` → FAIL (unresolved `avatarRef`).

- [ ] **Step 3: Add the fields** to `FamilyMember`, `PendingMember` (in AuthClient.kt) and `HubAudienceMember` (Model.kt), each:
```kotlin
@SerialName("avatar_color") val avatarColor: String? = null,
@SerialName("avatar_ref") val avatarRef: String? = null,
```
(Place them consistently with the existing `@SerialName("display_name")` fields; defaults keep old payloads parsing.) In `FakeBackend.kt`, set an `avatar_ref`/`avatar_color` on one or two seeded roster/audience members so the fake backend demonstrates avatars.

- [ ] **Step 4: Run to verify it passes** — `./gradlew :client:desktopTest --tests '*AuthClientTest*'` then full `./gradlew :client:desktopTest` → PASS.

- [ ] **Step 5: Commit**
```bash
git add apps/client/src/commonMain/... apps/client/src/desktopTest/...
git commit -m "feat(client): carry avatar_color/avatar_ref on member + audience models"
```

---

## Task 3: UI — render member/audience avatars

**Files:**
- Modify: `apps/ui/.../MembersScreen.kt` (`MemberRow`, `PendingRow`), `apps/ui/.../HubScreens.kt` (`AudienceRow`)
- Test: extend a MembersScreen/Hub UI test; add/update snapshot scene states

**Interfaces:**
- Consumes: Task 2's model fields; the P1 `DayfoldAvatar(name, size, avatarColorKey, avatarRef, contentDescription)`.

- [ ] **Step 1: Write/extend the failing test** — assert a roster member with a fun `avatarRef` renders its fun-avatar (its `contentDescription` = the fun avatar's a11y name) rather than initials. Mirror `MembersScreenA11yTest.kt`:

```kotlin
@OptIn(ExperimentalTestApi::class)
@Test fun memberWithFunAvatarShowsIt() = runComposeUiTest {
  setContent { DayfoldTheme { MembersScreen(state = stateWithMember(avatarRef = "avatar:fox-01"), …) } }
  onNodeWithContentDescription("Fox avatar").assertExists()
}
```
(Use the screen's real state-construction/params as the existing test does.)

- [ ] **Step 2: Run to verify it fails** — `cd apps && ./gradlew :ui:desktopTest --tests '*MembersScreen*'` → FAIL.

- [ ] **Step 3: Pass the fields into `DayfoldAvatar`** at the three call sites:
- `MembersScreen.kt` `MemberRow`: `DayfoldAvatar(name = m.displayName ?: "?", size = 40.dp, avatarColorKey = m.avatarColor, avatarRef = m.avatarRef, contentDescription = FunAvatars.resolve(m.avatarRef)?.name)`
- `MembersScreen.kt` `PendingRow`: same, from the `PendingMember`.
- `HubScreens.kt` `AudienceRow`: `DayfoldAvatar(name = m.displayName ?: "?", size = 42.dp, avatarColorKey = m.avatarColor, avatarRef = m.avatarRef, contentDescription = FunAvatars.resolve(m.avatarRef)?.name)`
(Import `com.sloopworks.dayfold.client.ui.FunAvatars` if needed for the a11y name; a null contentDescription is fine for the monogram case.)

- [ ] **Step 4: Run to verify it passes** — `./gradlew :ui:desktopTest --tests '*MembersScreen*'` then full `./gradlew :ui:desktopTest` → PASS. Regenerate any affected snapshot goldens (`./gradlew snapshotUi`) and commit them.

- [ ] **Step 5: Commit**
```bash
git add apps/ui/src/commonMain/... apps/ui/src/desktopTest/...
git commit -m "feat(ui): render member + hub-audience avatars (fun-avatar or monogram)"
```

---

## Self-Review

- **Coverage:** roster avatars (T1 SELECT + T2 model + T3 MemberRow/PendingRow) ✓; hub-audience avatars (T1 audience SELECT + T2 HubAudienceMember + T3 AudienceRow) ✓; monogram fallback unchanged (null ref) ✓; no schema/UI-component change ✓.
- **Placeholder scan:** each step has concrete SQL/Kotlin; the two test-file discoveries use a `grep` to locate the real harness (line numbers shifted after P1).
- **Type consistency:** `avatarColor`/`avatarRef` (`@SerialName avatar_color/avatar_ref`) identical across `FamilyMember`/`PendingMember`/`HubAudienceMember`; `DayfoldAvatar(avatarColorKey=, avatarRef=)` param names match P1.
