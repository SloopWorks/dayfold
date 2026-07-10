# Profile Avatars (Delta A) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a member set their own avatar — a bundled **fun-avatar** or a **monogram color** — persisted on their user row and shown on their account header, with monogram as the universal fallback.

**Architecture:** A shared `DayfoldAvatar` composable + a pure `avatarStyle()` helper replace the 4 duplicated inline monograms. Storage is two nullable columns on `users` (`avatar_color`, `avatar_ref`); `avatar_ref` holds a **bundled avatar id** (e.g. `avatar:flower-01`, ADR 0036 posture — no upload, no external fetch), never an object-storage key. The client loads/saves the caller's own profile via `GET`/`PATCH /auth/me`; an avatar picker `ModalBottomSheet` writes it. Roster/hub-audience avatar *display* (Deltas B/D) is a deliberate follow-on (P1b) once storage exists.

**Tech Stack:** KMP (`:client` logic + `:ui` Compose, Kotlin 2.3.20, Compose-MP, redux-kotlin 1.0.0-alpha01) · API (apps/api TS/Hono/Postgres, vitest).

## Global Constraints

- Branch: `design/account-acl-hub-roles` (already checked out). Commit frequently; commits/PRs written normally (not caveman).
- **KMP build** (from `apps/`, `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`): `./gradlew :client:desktopTest` (logic/reducers), `./gradlew :ui:desktopTest` (Compose render + goldens). In a network-restricted sandbox `./gradlew` may 403 — then verify Kotlin by inspection + mirror proven patterns and rely on CI (per `processes/agent-dev-loop.md`).
- **API test loop** (from `apps/api`): `export DATABASE_URL=postgres:///fad_test`; reset+apply migrations with `psql -d fad_test -f migrations/<f>.sql`; `npx vitest run`.
- **`avatar_ref`** = a bundled avatar id string from the fixed registry (Task 2) OR null. `avatar_color` = a palette key string (Task 1 palette) OR null. Both nullable; **null `avatar_ref` ⇒ render monogram**; monogram tint = `avatar_color` if set else deterministic from a seed. **Photo upload is out of scope** (disabled UI slot only).
- **redux-kotlin 1.0.0-alpha01:** `selectorState`/`fieldState` are extensions (`store.selectorState{}`); actions are declared in `Model.kt`, handled in `Reducer.kt`.
- ADR 0008 gates any on-device build sign-off; ADR 0053 does NOT gate this plan (self-profile avatars are independent of hub roles).

---

## File Structure

**New files:**
- `apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/ui/DayfoldAvatar.kt` — shared avatar composable + `avatarStyle()` helper (initials + deterministic monogram color).
- `apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/ui/FunAvatars.kt` — the bundled fun-avatar registry (id → drawable + a11y name).
- `apps/ui/src/commonMain/composeResources/drawable/avatar_*.xml` — bundled avatar vector assets (starter set; final art drops in here under the same ids).
- `apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/AvatarPickerSheet.kt` — the picker `ModalBottomSheet`.
- `apps/api/migrations/0017_user_avatar.sql` — `avatar_color`, `avatar_ref` columns.
- `apps/ui/src/desktopTest/kotlin/com/sloopworks/dayfold/client/DayfoldAvatarTest.kt`, `AvatarPickerSheetTest.kt`.
- `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/AvatarReducerTest.kt`, and an `AuthClient` avatar test.
- `apps/api/test/auth-me-avatar.test.ts` (mirror existing auth test naming).

**Modified files:**
- `FeedScreen.kt:84-95`, `AccountScreen.kt:100-103`, `MembersScreen.kt:170-175`, `HubScreens.kt:632-637` — replace inline monograms with `DayfoldAvatar`.
- `apps/api/src/app.ts:197-224` — `GET`/`PATCH /auth/me` project + accept avatar fields.
- `AuthClient.kt`, `AuthEngine.kt`, `Model.kt`, `Reducer.kt`, `FakeBackend.kt` — profile load + avatar-update wiring.
- `AccountScreen.kt` — profile card wears the avatar + "Change avatar" opens the picker.
- `apps/ui/.../theme/Color.kt:71-105` — add the avatar-tint swatch palette to `DayfoldExtendedColors`.
- Snapshot registry (`SnapshotScenes.kt`/`SnapshotStates.kt`) + regenerated goldens.

---

## Task 1: Shared `DayfoldAvatar` composable + `avatarStyle()` helper

Consolidates the 4 inline monograms and adds a per-user deterministic tint (today every monogram is `primaryContainer`). Pure `:ui`; no API/schema. Ships improved avatars immediately.

**Files:**
- Create: `apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/ui/DayfoldAvatar.kt`
- Modify: `apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/theme/Color.kt:71-105` (add swatch list to `DayfoldExtendedColors` + Light/Dark instances)
- Test: `apps/ui/src/desktopTest/kotlin/com/sloopworks/dayfold/client/DayfoldAvatarTest.kt`

**Interfaces:**
- Produces:
  - `fun avatarStyle(seed: String, avatarColorKey: String?, swatches: List<AvatarSwatch>): AvatarStyle` — pure; picks a swatch by `avatarColorKey` if non-null else `abs(seed.hashCode()) % swatches.size`; returns initials (1–2 chars, uppercased, split on space) + bg/fg colors.
  - `data class AvatarSwatch(val key: String, val bg: Color, val fg: Color)`
  - `data class AvatarStyle(val initials: String, val bg: Color, val fg: Color)`
  - `@Composable fun DayfoldAvatar(name: String, size: Dp, avatarColorKey: String? = null, avatarRef: String? = null, modifier: Modifier = Modifier, contentDescription: String? = null)` — renders the fun-avatar drawable if `avatarRef` resolves (Task 2), else the monogram; `contentDescription` null ⇒ decorative (`clearAndSetSemantics{}`).

- [ ] **Step 1: Write the failing test** — `DayfoldAvatarTest.kt`

```kotlin
package com.sloopworks.dayfold.client
import androidx.compose.ui.graphics.Color
import com.sloopworks.dayfold.client.ui.AvatarSwatch
import com.sloopworks.dayfold.client.ui.avatarStyle
import kotlin.test.Test
import kotlin.test.assertEquals

private val SW = listOf(
  AvatarSwatch("coral", Color(0xFFFFDAD4), Color(0xFF7A2615)),
  AvatarSwatch("teal",  Color(0xFFCDE9E4), Color(0xFF12433C)),
  AvatarSwatch("violet",Color(0xFFE9DDFB), Color(0xFF3A2260)),
)

class DayfoldAvatarTest {
  @Test fun initialsAreTwoLettersFromNameParts() {
    assertEquals("LG", avatarStyle("Leo Garcia", null, SW).initials)
  }
  @Test fun initialsSingleWordSingleLetter() {
    assertEquals("Y", avatarStyle("you", null, SW).initials)
  }
  @Test fun explicitColorKeyWins() {
    assertEquals(SW[1].bg, avatarStyle("Leo Garcia", "teal", SW).bg)
  }
  @Test fun sameSeedIsDeterministic() {
    assertEquals(avatarStyle("Leo", null, SW).bg, avatarStyle("Leo", null, SW).bg)
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd apps && ./gradlew :ui:desktopTest --tests '*DayfoldAvatarTest*'`
Expected: FAIL — unresolved reference `avatarStyle` / `AvatarSwatch`.

- [ ] **Step 3: Write minimal implementation** — `DayfoldAvatar.kt`

```kotlin
package com.sloopworks.dayfold.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp

data class AvatarSwatch(val key: String, val bg: Color, val fg: Color)
data class AvatarStyle(val initials: String, val bg: Color, val fg: Color)

fun avatarStyle(seed: String, avatarColorKey: String?, swatches: List<AvatarSwatch>): AvatarStyle {
  val initials = seed.trim().split(" ").filter { it.isNotBlank() }
    .mapNotNull { it.firstOrNull()?.uppercaseChar() }.take(2).joinToString("")
    .ifEmpty { "?" }
  val sw = swatches.firstOrNull { it.key == avatarColorKey }
    ?: swatches[((seed.hashCode().toLong() and 0x7fffffffL) % swatches.size).toInt()]
  return AvatarStyle(initials, sw.bg, sw.fg)
}

@Composable
fun DayfoldAvatar(
  name: String,
  size: Dp,
  avatarColorKey: String? = null,
  avatarRef: String? = null,
  modifier: Modifier = Modifier,
  contentDescription: String? = null,
) {
  val swatches = com.sloopworks.dayfold.client.theme.LocalDayfoldColors.current.avatarSwatches
  val sem = Modifier.semantics {
    if (contentDescription != null) this.contentDescription = contentDescription
  }
  val fun_ = FunAvatars.resolve(avatarRef)   // Task 2; null-safe: returns null until Task 2 lands
  Box(modifier.size(size).clip(CircleShape).then(sem), contentAlignment = Alignment.Center) {
    if (fun_ != null) {
      FunAvatarImage(fun_, size)             // Task 2
    } else {
      val s = avatarStyle(name, avatarColorKey, swatches)
      Box(Modifier.size(size).clip(CircleShape).background(s.bg))
      Text(s.initials, color = s.fg, fontSize = (size.value * 0.4f).sp,
        modifier = Modifier.clearAndSetSemantics {})
    }
  }
}
```

Add the swatch palette to the theme. In `Color.kt`, extend `DayfoldExtendedColors` (line ~71) with `val avatarSwatches: List<AvatarSwatch>` and set it on both `DayfoldLightExtended` and `DayfoldDarkExtended` (line ~82-102), e.g. `avatarSwatches = listOf(AvatarSwatch("coral", Color(0xFFFFDAD4), Color(0xFF7A2615)), AvatarSwatch("teal", Color(0xFFCDE9E4), Color(0xFF12433C)), AvatarSwatch("violet", Color(0xFFE9DDFB), Color(0xFF3A2260)), AvatarSwatch("amber", Color(0xFFF7E2B8), Color(0xFF5C4400)), AvatarSwatch("sage", Color(0xFFD9E9CC), Color(0xFF33461F)), AvatarSwatch("rose", Color(0xFFF6D9E4), Color(0xFF6A2440)))` (dark instance: same keys, darker bg / lighter fg — mirror the existing Light/Dark split for other extended roles). Import `AvatarSwatch` from `...client.ui`.

For Task 1 in isolation, add temporary top-level stubs at the bottom of `DayfoldAvatar.kt` so it compiles before Task 2:

```kotlin
internal object FunAvatars { fun resolve(ref: String?): FunAvatar? = null }
internal class FunAvatar
@Composable internal fun FunAvatarImage(a: FunAvatar, size: Dp) {}
```
(Task 2 deletes these stubs and provides the real `FunAvatars`.)

- [ ] **Step 4: Run test to verify it passes**

Run: `cd apps && ./gradlew :ui:desktopTest --tests '*DayfoldAvatarTest*'`
Expected: PASS (4 tests).

- [ ] **Step 5: Refactor the 4 inline monograms to `DayfoldAvatar`**

Replace each with `DayfoldAvatar(name=…, size=…, contentDescription=…)`:
- `FeedScreen.kt:84-95` → `DayfoldAvatar(name = "You", size = 34.dp, contentDescription = "Account")` (keep the `onClick` wrapper).
- `AccountScreen.kt:100-103` → `DayfoldAvatar(name = displayName ?: "You", size = 48.dp)` (displayName arrives in Task 4; until then pass `"You"`).
- `MembersScreen.kt:170-175` (`Avatar(...)`) → `DayfoldAvatar(name = m.displayName ?: "?", size = 40.dp)`; delete the private `Avatar` composable.
- `HubScreens.kt:632-637` (`AudienceRow`) → `DayfoldAvatar(name = m.displayName ?: "?", size = 42.dp)`.

- [ ] **Step 6: Run the full :ui suite; regenerate goldens**

Run: `cd apps && ./gradlew :ui:desktopTest`
Expected: monogram-bearing snapshot goldens (Feed/Account/Members/Hub) FAIL on pixel diff (colors now per-user). Inspect the diffs to confirm only avatar tint changed, then regenerate: `./gradlew snapshotUi` (updates `apps/ui/src/desktopTest/resources/snapshots/`). Re-run `./gradlew :ui:desktopTest` → PASS.

- [ ] **Step 7: Commit**

```bash
git add apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/ui/DayfoldAvatar.kt \
        apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/theme/Color.kt \
        apps/ui/src/desktopTest/kotlin/com/sloopworks/dayfold/client/DayfoldAvatarTest.kt \
        apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/FeedScreen.kt \
        apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/AccountScreen.kt \
        apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/MembersScreen.kt \
        apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/HubScreens.kt \
        apps/ui/src/desktopTest/resources/snapshots/
git commit -m "feat(ui): shared DayfoldAvatar + per-user monogram tint (consolidate 4 monograms)"
```

---

## Task 2: Bundled fun-avatar registry + starter vector assets

**Files:**
- Create: `apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/ui/FunAvatars.kt`
- Create: `apps/ui/src/commonMain/composeResources/drawable/avatar_flower_01.xml` … (starter set; ids match the registry)
- Modify: `DayfoldAvatar.kt` (delete the Task-1 stubs; use the real `FunAvatars`)
- Test: `apps/ui/src/desktopTest/kotlin/com/sloopworks/dayfold/client/FunAvatarsTest.kt`

**Interfaces:**
- Produces:
  - `data class FunAvatar(val id: String, val name: String, val drawable: DrawableResource)`
  - `object FunAvatars { val all: List<FunAvatar>; fun resolve(ref: String?): FunAvatar? }` — `resolve("avatar:flower-01")` → the entry, unknown/`null` → `null`.
  - `@Composable fun FunAvatarImage(a: FunAvatar, size: Dp)` — `Image(painterResource(a.drawable), contentDescription = null, Modifier.size(size))`.

> **Asset note (flag to operator):** final illustrated art is a **design deliverable** — this task ships a small tasteful **starter set** of vector `drawable/avatar_*.xml` under fixed ids; final art replaces the files in place (same ids), no code change. The mockup calls for ~21; ship ≥6 starters here, backfill the rest as art lands.

- [ ] **Step 1: Write the failing test** — `FunAvatarsTest.kt`

```kotlin
package com.sloopworks.dayfold.client
import com.sloopworks.dayfold.client.ui.FunAvatars
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FunAvatarsTest {
  @Test fun resolvesKnownId() { assertEquals("avatar:flower-01", FunAvatars.resolve("avatar:flower-01")?.id) }
  @Test fun unknownIsNull() { assertNull(FunAvatars.resolve("avatar:nope")) }
  @Test fun nullIsNull() { assertNull(FunAvatars.resolve(null)) }
  @Test fun everyEntryHasA11yName() { assertTrue(FunAvatars.all.all { it.name.isNotBlank() }) }
  @Test fun idsUnique() { assertEquals(FunAvatars.all.size, FunAvatars.all.map { it.id }.toSet().size) }
}
```

- [ ] **Step 2: Run to verify it fails** — `cd apps && ./gradlew :ui:desktopTest --tests '*FunAvatarsTest*'` → FAIL (unresolved `FunAvatars`).

- [ ] **Step 3: Add the drawable assets + registry**

Create ≥6 vector avatars under `apps/ui/src/commonMain/composeResources/drawable/`, e.g. `avatar_flower_01.xml` (a simple warm geometric motif — a filled circle background + a flower/leaf path; tasteful, adult-friendly, NOT a sticker). Example minimal vector:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="96dp" android:height="96dp" android:viewportWidth="96" android:viewportHeight="96">
  <path android:fillColor="#F6D9E4" android:pathData="M0,0h96v96h-96z"/>
  <path android:fillColor="#6A2440" android:pathData="M48,30 a10,10 0 1,0 0.1,0 M48,50 v18 M40,60 h16"/>
</vector>
```

Then `FunAvatars.kt` (assets generate as `Res.drawable.avatar_flower_01` etc. in package `...client.generated`):

```kotlin
package com.sloopworks.dayfold.client.ui
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.sloopworks.dayfold.client.generated.Res
import com.sloopworks.dayfold.client.generated.*   // generated drawable accessors
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

data class FunAvatar(val id: String, val name: String, val drawable: DrawableResource)

object FunAvatars {
  val all: List<FunAvatar> = listOf(
    FunAvatar("avatar:flower-01", "Flower avatar", Res.drawable.avatar_flower_01),
    FunAvatar("avatar:fox-01",    "Fox avatar",    Res.drawable.avatar_fox_01),
    FunAvatar("avatar:leaf-01",   "Leaf avatar",   Res.drawable.avatar_leaf_01),
    FunAvatar("avatar:moon-01",   "Moon avatar",   Res.drawable.avatar_moon_01),
    FunAvatar("avatar:wave-01",   "Wave avatar",   Res.drawable.avatar_wave_01),
    FunAvatar("avatar:sun-01",    "Sun avatar",    Res.drawable.avatar_sun_01),
  )
  private val byId = all.associateBy { it.id }
  fun resolve(ref: String?): FunAvatar? = ref?.let { byId[it] }
}

@Composable fun FunAvatarImage(a: FunAvatar, size: Dp) =
  Image(painterResource(a.drawable), contentDescription = null, modifier = Modifier.size(size))
```

Delete the temporary stub `object FunAvatars` / `FunAvatar` / `FunAvatarImage` from `DayfoldAvatar.kt` (Task 1 Step 3).

- [ ] **Step 4: Run to verify it passes** — `cd apps && ./gradlew :ui:desktopTest --tests '*FunAvatarsTest*'` → PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/ui/src/commonMain/composeResources/drawable/avatar_*.xml \
        apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/ui/FunAvatars.kt \
        apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/ui/DayfoldAvatar.kt \
        apps/ui/src/desktopTest/kotlin/com/sloopworks/dayfold/client/FunAvatarsTest.kt
git commit -m "feat(ui): bundled fun-avatar registry + starter vector set"
```

---

## Task 3: API — persist + serve avatar fields on `/auth/me`

**Files:**
- Create: `apps/api/migrations/0017_user_avatar.sql`
- Modify: `apps/api/src/app.ts:197-224` (`GET`/`PATCH /auth/me`)
- Test: `apps/api/test/auth-me-avatar.test.ts`

**Interfaces:**
- Produces: `GET /auth/me` → `{ user_id, display_name, avatar_color, avatar_ref }`; `PATCH /auth/me` accepts optional `avatar_color` (string ≤32, or null to clear) and `avatar_ref` (string matching `^avatar:[a-z0-9-]{1,40}$`, or null), alongside optional `display_name`; returns the updated row. Invalid `avatar_ref` → `400 {type:"bad-avatar"}`.

- [ ] **Step 1: Write the failing test** — `auth-me-avatar.test.ts` (mirror an existing `apps/api/test/*.test.ts` for the harness: dev-token → `PATCH`/`GET /auth/me`).

```ts
import { describe, it, expect, beforeAll } from "vitest";
import { devToken } from "./helpers.ts";        // reuse the existing test helper used by other auth tests
// (if no helper exists, mint via POST /auth/dev-token as agent-dev-loop.md documents)

describe("PATCH /auth/me avatar", () => {
  let access: string;
  beforeAll(async () => { access = await devToken("alice"); });

  it("sets and returns a bundled avatar_ref + color", async () => {
    const r = await fetch(`${API}/auth/me`, { method: "PATCH",
      headers: { authorization: `Bearer ${access}`, "content-type": "application/json" },
      body: JSON.stringify({ avatar_ref: "avatar:fox-01", avatar_color: "teal" }) });
    expect(r.status).toBe(200);
    const got = await (await fetch(`${API}/auth/me`, { headers: { authorization: `Bearer ${access}` } })).json();
    expect(got.avatar_ref).toBe("avatar:fox-01");
    expect(got.avatar_color).toBe("teal");
  });

  it("rejects a malformed avatar_ref", async () => {
    const r = await fetch(`${API}/auth/me`, { method: "PATCH",
      headers: { authorization: `Bearer ${access}`, "content-type": "application/json" },
      body: JSON.stringify({ avatar_ref: "http://evil/x.png" }) });
    expect(r.status).toBe(400);
  });

  it("clears avatar_ref with null", async () => {
    await fetch(`${API}/auth/me`, { method: "PATCH", headers: { authorization: `Bearer ${access}`, "content-type": "application/json" }, body: JSON.stringify({ avatar_ref: "avatar:fox-01" }) });
    await fetch(`${API}/auth/me`, { method: "PATCH", headers: { authorization: `Bearer ${access}`, "content-type": "application/json" }, body: JSON.stringify({ avatar_ref: null }) });
    const got = await (await fetch(`${API}/auth/me`, { headers: { authorization: `Bearer ${access}` } })).json();
    expect(got.avatar_ref).toBeNull();
  });
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd apps/api && export DATABASE_URL=postgres:///fad_test && npx vitest run auth-me-avatar`
Expected: FAIL (columns missing / fields undefined).

- [ ] **Step 3: Migration + route changes**

`migrations/0017_user_avatar.sql`:
```sql
-- Delta A: self-profile avatar. avatar_ref = bundled avatar id (e.g. 'avatar:fox-01'),
-- NOT an object-storage key (ADR 0036 posture — no upload, no external fetch).
ALTER TABLE users ADD COLUMN avatar_color text;
ALTER TABLE users ADD COLUMN avatar_ref   text;
```
(Register in the tracked runner consistent with `0012_schema_migrations.sql` / ADR 0033 if that flow requires an entry; otherwise apply forward-only like its siblings.)

`GET /auth/me` — extend the SELECT + response:
```ts
const u = (await q(`SELECT id, display_name, avatar_color, avatar_ref FROM users WHERE id=$1 AND deleted_at IS NULL`, [sub])).rows[0];
if (!u) return c.body(null, 401);
return c.json({ user_id: u.id, display_name: u.display_name, avatar_color: u.avatar_color, avatar_ref: u.avatar_ref });
```

`PATCH /auth/me` — accept optional avatar fields (keep display_name behavior):
```ts
const body = await c.req.json().catch(() => null);
const hasName = typeof body?.display_name === "string";
const name = hasName ? body.display_name.trim() : null;
if (hasName && (!name || name.length < 1 || name.length > 80)) return c.json({ type: "bad-display-name" }, 400);

const AVATAR_RE = /^avatar:[a-z0-9-]{1,40}$/;
const hasRef = "avatar_ref" in (body ?? {});
const ref = body?.avatar_ref ?? null;                 // null clears
if (hasRef && ref !== null && !(typeof ref === "string" && AVATAR_RE.test(ref)))
  return c.json({ type: "bad-avatar" }, 400);
const hasColor = "avatar_color" in (body ?? {});
const color = body?.avatar_color ?? null;
if (hasColor && color !== null && !(typeof color === "string" && color.length <= 32))
  return c.json({ type: "bad-avatar" }, 400);

const sets: string[] = ["updated_at=now()"]; const vals: any[] = []; let i = 1;
if (hasName)  { sets.push(`display_name=$${i++}`); vals.push(name); }
if (hasRef)   { sets.push(`avatar_ref=$${i++}`);   vals.push(ref); }
if (hasColor) { sets.push(`avatar_color=$${i++}`); vals.push(color); }
vals.push(sub);
const r = await q(`UPDATE users SET ${sets.join(", ")} WHERE id=$${i} AND deleted_at IS NULL
  RETURNING display_name, avatar_color, avatar_ref`, vals);
if (r.rowCount === 0) return c.body(null, 401);
return c.json(r.rows[0]);
```

- [ ] **Step 4: Apply migration + run to verify it passes**

Run: `cd apps/api && export DATABASE_URL=postgres:///fad_test && psql -d fad_test -f migrations/0017_user_avatar.sql && npx vitest run auth-me-avatar`
Expected: PASS (3 tests). Then `npx vitest run` — full suite green (no regressions).

- [ ] **Step 5: Commit**

```bash
git add apps/api/migrations/0017_user_avatar.sql apps/api/src/app.ts apps/api/test/auth-me-avatar.test.ts
git commit -m "feat(api): persist + serve user avatar_color/avatar_ref on /auth/me"
```

---

## Task 4: Client — load own profile + avatar-update wiring

Mirrors the proven self-scoped mutation path (`revokeCredential` → `MemberRemoved`, `AuthClient.kt:211-213` / `Reducer.kt:145`).

**Files:**
- Modify: `AuthClient.kt` (add `getMe`, `updateAvatar`), `AuthEngine.kt` (sequence + dispatch), `Model.kt` (state fields + actions), `Reducer.kt` (cases), `apps/client/src/.../fake/FakeBackend.kt` (`GET`/`PATCH /auth/me`).
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/AvatarReducerTest.kt` + an `AuthClientTest` case for the request path.

**Interfaces:**
- Produces:
  - `AuthClient.getMe(access): MeProfile` and `AuthClient.updateAvatar(access, avatarColor: String?, avatarRef: String?): MeProfile` where `data class MeProfile(val userId: String, val displayName: String?, val avatarColor: String?, val avatarRef: String?)`.
  - State on `AppState`: `myAvatarRef: String?`, `myAvatarColor: String?`, `myDisplayName: String?`, `avatarOpId: String?` (optimistic op, mirror `memberOpId`).
  - Actions (in `Model.kt`): `ProfileLoaded(profile: MeProfile)`, `AvatarUpdated(avatarColor: String?, avatarRef: String?)`, `AvatarUpdateFailed`.

- [ ] **Step 1: Write the failing reducer test** — `AvatarReducerTest.kt`

```kotlin
package com.sloopworks.dayfold.client
import kotlin.test.Test
import kotlin.test.assertEquals

class AvatarReducerTest {
  @Test fun profileLoadedPopulatesMyAvatar() {
    val s = reduce(AppState(), ProfileLoaded(MeProfile("U1", "Leo", "teal", "avatar:fox-01")))
    assertEquals("avatar:fox-01", s.myAvatarRef)
    assertEquals("teal", s.myAvatarColor)
    assertEquals("Leo", s.myDisplayName)
  }
  @Test fun avatarUpdatedAppliesOptimistically() {
    val s = reduce(AppState(myAvatarRef = null), AvatarUpdated("coral", "avatar:sun-01"))
    assertEquals("avatar:sun-01", s.myAvatarRef)
    assertEquals("coral", s.myAvatarColor)
  }
}
```
(Use the same `reduce(state, action)` entry point the existing `*ReducerTest.kt` use — match `AuthReducerTest.kt`'s import/harness exactly.)

- [ ] **Step 2: Run to verify it fails** — `cd apps && ./gradlew :client:desktopTest --tests '*AvatarReducerTest*'` → FAIL (unresolved `ProfileLoaded`/fields).

- [ ] **Step 3: Implement**

- `Model.kt`: add fields to `AppState` (`myDisplayName: String? = null, myAvatarColor: String? = null, myAvatarRef: String? = null, avatarOpId: String? = null`); declare `data class MeProfile(...)`; declare the three actions (follow the existing `sealed`/action style in `Model.kt`).
- `Reducer.kt`: add cases —
  ```kotlin
  is ProfileLoaded -> state.copy(myDisplayName = action.profile.displayName,
      myAvatarColor = action.profile.avatarColor, myAvatarRef = action.profile.avatarRef)
  is AvatarUpdated -> state.copy(myAvatarColor = action.avatarColor, myAvatarRef = action.avatarRef, avatarOpId = null)
  is AvatarUpdateFailed -> state.copy(avatarOpId = null)
  ```
- `AuthClient.kt`: mirror the credentials calls (`:204-213`):
  ```kotlin
  suspend fun getMe(access: String): MeProfile =
    http.get("$api/auth/me") { bearer(access) }.body<MeDto>().toModel()
  suspend fun updateAvatar(access: String, avatarColor: String?, avatarRef: String?): MeProfile =
    http.patch("$api/auth/me") { bearer(access); contentType(ContentType.Application.Json)
      setBody(mapOf("avatar_color" to avatarColor, "avatar_ref" to avatarRef)) }.body<MeDto>().toModel()
  ```
  with `@Serializable data class MeDto(val user_id: String? = null, val display_name: String? = null, val avatar_color: String? = null, val avatar_ref: String? = null)` and `toModel()`.
- `AuthEngine.kt`: after session restore/whoami, call `getMe` → dispatch `ProfileLoaded`; add `updateAvatar(...)` that sets `avatarOpId`, dispatches `AvatarUpdated` optimistically, calls the client, and dispatches `AvatarUpdateFailed` on error (mirror the member-remove op flow).
- `FakeBackend.kt`: add a branch for `GET`/`PATCH /auth/me` returning a `MeDto` JSON (mirror the credentials branch at `:95-102`) so the fake backend renders avatars.

- [ ] **Step 4: Run to verify it passes** — `cd apps && ./gradlew :client:desktopTest --tests '*AvatarReducerTest*'` then `./gradlew :client:desktopTest` (full) → PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/client/src/commonMain/... apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/AvatarReducerTest.kt
git commit -m "feat(client): load own profile + optimistic avatar-update wiring (GET/PATCH /auth/me)"
```

---

## Task 5: Avatar picker sheet + wire into AccountScreen

**Files:**
- Create: `apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/AvatarPickerSheet.kt`
- Modify: `AccountScreen.kt` (profile card wears `DayfoldAvatar(name = myDisplayName ?: "You", avatarColorKey = myAvatarColor, avatarRef = myAvatarRef, size = 48.dp)`; add a "Change avatar" affordance opening the sheet; dispatch `updateAvatar` on Save)
- Modify: snapshot registry `SnapshotScenes.kt`/`SnapshotStates.kt` (+ goldens)
- Test: `apps/ui/src/desktopTest/kotlin/com/sloopworks/dayfold/client/AvatarPickerSheetTest.kt`

**Interfaces:**
- Consumes: `DayfoldAvatar` (T1), `FunAvatars.all` (T2), `AvatarSwatch` palette (T1), `updateAvatar`/state fields (T4).
- Produces: `@Composable fun AvatarPickerSheet(currentColor: String?, currentRef: String?, onSave: (color: String?, ref: String?) -> Unit, onDismiss: () -> Unit)` — a `ModalBottomSheet` with a `SegmentedButton` [Monogram · Fun avatars · Photo(disabled)], a live preview, and an explicit **Save**.

- [ ] **Step 1: Write the failing test** — `AvatarPickerSheetTest.kt`

```kotlin
package com.sloopworks.dayfold.client
import androidx.compose.ui.test.*
import com.sloopworks.dayfold.client.theme.DayfoldTheme
import kotlin.test.Test
import kotlin.test.assertEquals

class AvatarPickerSheetTest {
  @OptIn(ExperimentalTestApi::class)
  @Test fun pickingFunAvatarAndSavingEmitsItsId() = runComposeUiTest {
    var saved: Pair<String?, String?>? = null
    setContent { DayfoldTheme { AvatarPickerSheet(null, null, { c, r -> saved = c to r }, {}) } }
    onNodeWithText("Fun avatars").performClick()
    onNodeWithContentDescription("Fox avatar").performClick()
    onNodeWithText("Save").performClick()
    assertEquals("avatar:fox-01", saved?.second)
  }
  @OptIn(ExperimentalTestApi::class)
  @Test fun photoTabIsDisabled() = runComposeUiTest {
    setContent { DayfoldTheme { AvatarPickerSheet(null, null, { _, _ -> }, {}) } }
    onNodeWithText("Photo").assertIsNotEnabled()
  }
}
```

- [ ] **Step 2: Run to verify it fails** — `cd apps && ./gradlew :ui:desktopTest --tests '*AvatarPickerSheetTest*'` → FAIL (unresolved `AvatarPickerSheet`).

- [ ] **Step 3: Implement the sheet + wire AccountScreen**

Build `AvatarPickerSheet.kt`: a `ModalBottomSheet { }` with a 3-option `SegmentedButton` (`Monogram`, `Fun avatars`, `Photo` — Photo `enabled=false` with a "later" label); Monogram pane = a row of the `avatarSwatches` as tappable circles (selecting sets `pickedColor`, `pickedRef=null`); Fun pane = a grid of `FunAvatars.all` rendered via `DayfoldAvatar(name=…, avatarRef=it.id, size=56.dp, contentDescription=it.name)` (selecting sets `pickedRef=it.id`); a live preview `DayfoldAvatar` at top; a `Button("Save"){ onSave(pickedColor, pickedRef) }`. In `AccountScreen.kt`, replace the profile-card avatar with the state-driven `DayfoldAvatar` and add an `onClick`/"Change avatar" that shows the sheet, calling the engine's `updateAvatar` in `onSave`.

- [ ] **Step 4: Run to verify it passes** — `cd apps && ./gradlew :ui:desktopTest --tests '*AvatarPickerSheetTest*'` → PASS.

- [ ] **Step 5: Snapshot scene + full suite**

Add an `AvatarPickerSheet` scene to `SnapshotScenes.kt`/`SnapshotStates.kt`; `./gradlew snapshotUi` to generate goldens; `./gradlew :ui:desktopTest` → PASS.

- [ ] **Step 6: Commit**

```bash
git add apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/AvatarPickerSheet.kt \
        apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/AccountScreen.kt \
        apps/ui/src/desktopTest/kotlin/com/sloopworks/dayfold/client/AvatarPickerSheetTest.kt \
        apps/ui/src/desktopTest/kotlin/com/sloopworks/dayfold/client/snapshot/ \
        apps/ui/src/desktopTest/resources/snapshots/
git commit -m "feat(ui): avatar picker sheet (monogram + fun avatars) wired into AccountScreen"
```

---

## Follow-ons (out of scope for this plan)

- **P1b — Deltas B/D display:** propagate avatars to the members roster + hub audience — add `avatar_color`/`avatar_ref` to `FamilyMember`/`HubAudienceMember`/`PendingMember` (`AuthClient.kt:300-316`, `Model.kt:369-380`) + the API SELECTs (`app.ts` whoami/roster, `hubs.ts:151`), then pass them into the already-refactored `DayfoldAvatar` call sites. Small once storage (Task 3) exists.
- **P2/P3 — hub People + roles:** gated on ADR 0053 → Accepted (and P3 on in-app authoring 0038/0039). Separate plans.

## Self-Review

- **Spec coverage (Delta A):** monogram default + fallback (T1) ✓; fun-avatar bundled set, no upload/external fetch (T2, ADR 0036) ✓; `avatar_ref`=bundled id / `avatar_color` (T3) ✓; picker with Monogram·Fun·disabled-Photo + Save (T5) ✓; a11y names on avatars (T2/T1) ✓. Deltas B/D explicitly deferred to P1b (noted). Photo upload out of scope ✓.
- **Placeholder scan:** none — every step has concrete code/commands; Kotlin tasks that mirror existing patterns cite exact file:line to copy.
- **Type consistency:** `MeProfile`/`MeDto`, `AvatarSwatch`/`AvatarStyle`, `FunAvatar`, actions `ProfileLoaded`/`AvatarUpdated`/`AvatarUpdateFailed`, state `myAvatarRef`/`myAvatarColor`/`myDisplayName`/`avatarOpId` used consistently across tasks. `DayfoldAvatar(name, size, avatarColorKey, avatarRef, contentDescription)` signature stable T1→T5.
