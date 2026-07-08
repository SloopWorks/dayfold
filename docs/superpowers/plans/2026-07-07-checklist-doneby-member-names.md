# Checklist `doneBy` → member name — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Render a checklist item's done-byline as a member's first name ("✓ Patrick" / "✓ You") instead of the raw authenticated userId ("✓ usr_9c200a3ccfefa0752f").

**Architecture:** `doneBy` stays a userId in content (content-blind, ADR 0015/0017). A pure resolver maps it to a first name at render time from the identity-layer roster (`state.members`), which is eager-loaded once memberships resolve so it's available wherever checklists render. Identity never enters content.

**Tech Stack:** Kotlin Multiplatform commonMain (`:client` logic + `:ui` Compose), redux-kotlin, ktor (existing `GET /members`). Tests: `:client:desktopTest` (pure/engine), `:ui:desktopTest` (Compose, JUnit5 `runComposeUiTest`).

## Global Constraints

- **Only `doneBy`.** `assignee` is author-written free text — DO NOT touch it.
- **No email.** First name from the profile `display_name` only.
- **Never render a raw `usr_…`** in the byline or the screen-reader state description.
- **Fallback strings (verbatim):** self → `"You"`; unknown/departed member → `"a family member"`.
- **First name** = the first whitespace-delimited token of `display_name`, trimmed.
- **Build/test from `apps/`:** `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew <task>` (homebrew JDK17 on PATH also works).
- **UI tests are JUnit5** (`useJUnitPlatform()`): `@OptIn(ExperimentalTestApi::class)` + `runComposeUiTest { setContent { MaterialTheme { … } } }` + `kotlin.test`. No `@get:Rule createComposeRule()`.
- **Client logic tests:** `@Test fun x() = runBlocking { … }` (NOT `runTest`) + ktor `MockEngine`.
- `displayNameFor`/`firstNameOf` live in package `com.sloopworks.dayfold.client` (`:client`) — same package as `HubScreens.kt` (`:ui`), so `:ui` calls them with no import.
- `FamilyMember` (`:client` `AuthClient.kt`): `uid: String`, `displayName: String?`. `Session.userId: String?`.

---

### Task 1: Pure resolver — `firstNameOf` + `displayNameFor`

**Files:**
- Create: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/MemberNames.kt`
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/MemberNamesTest.kt`

**Interfaces:**
- Produces:
  - `fun firstNameOf(name: String): String` — first whitespace token, trimmed.
  - `fun displayNameFor(userId: String?, members: List<FamilyMember>, selfId: String?): String?` — `null` when unresolvable.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MemberNamesTest {
  private val roster = listOf(
    FamilyMember(uid = "u1", displayName = "Patrick Jackson"),
    FamilyMember(uid = "u2", displayName = "Lillian"),
    FamilyMember(uid = "u3", displayName = null),
  )

  @Test fun `firstNameOf takes the first token`() {
    assertEquals("Patrick", firstNameOf("Patrick Jackson"))
    assertEquals("Lillian", firstNameOf("Lillian"))
    assertEquals("Pat", firstNameOf("  Pat  Q  "))
  }

  @Test fun `self resolves to You`() {
    assertEquals("You", displayNameFor("u1", roster, selfId = "u1"))
  }

  @Test fun `a known other member resolves to their first name`() {
    assertEquals("Patrick", displayNameFor("u1", roster, selfId = "u2"))
    assertEquals("Lillian", displayNameFor("u2", roster, selfId = "u1"))
  }

  @Test fun `unknown, null, or nameless member is null (caller falls back)`() {
    assertNull(displayNameFor("u9", roster, selfId = "u1"))   // departed / not synced
    assertNull(displayNameFor(null, roster, selfId = "u1"))
    assertNull(displayNameFor("u3", roster, selfId = "u1"))   // member exists but no display_name
  }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd apps && JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :client:desktopTest --tests "*MemberNamesTest*" --rerun-tasks`
Expected: FAIL — `firstNameOf`/`displayNameFor` unresolved.

- [ ] **Step 3: Implement**

```kotlin
package com.sloopworks.dayfold.client

// Render-time resolution of a checklist doneBy userId → a friendly name (ADR 0015 content-
// blind: doneBy lives in content; names come from the identity-layer roster). Pure + unit-
// tested. null return = unresolvable → the caller shows "a family member".
fun firstNameOf(name: String): String = name.trim().substringBefore(' ').trim()

fun displayNameFor(userId: String?, members: List<FamilyMember>, selfId: String?): String? {
  if (userId == null) return null
  if (userId == selfId) return "You"
  val name = members.firstOrNull { it.uid == userId }?.displayName ?: return null
  return name.trim().ifEmpty { return null }.let(::firstNameOf)
}
```

- [ ] **Step 4: Run to verify they pass**

Run: `cd apps && JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :client:desktopTest --tests "*MemberNamesTest*" --rerun-tasks`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/MemberNames.kt apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/MemberNamesTest.kt
git commit -m "feat(client): pure doneBy→member-name resolver (firstNameOf/displayNameFor)"
```

---

### Task 2: Eager-load the roster so `state.members` is populated app-wide

**Files:**
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/AuthEngine.kt` (`loadMemberships` tail ~line 344; new private `loadRosterLocked` near `loadMembers` ~line 221)
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/AuthEngineTest.kt`

**Interfaces:**
- Consumes: existing `mutex`, `callWithRefresh`, `authClient.familyMembers`, `RosterLoaded`, `store.state.activeFamilyId`.
- Produces: after `restore()`/`signIn()` resolve memberships, `state.members` holds the roster (previously only populated when the Members screen opened).

- [ ] **Step 1: Write the failing test** (mirror the existing `loadMembers fills the roster` test — MockEngine + `MemTokenStore(Session)` + `eng.restore()`)

```kotlin
@Test fun `restore eager-loads the roster so members are available app-wide`() = runBlocking {
  val ts = MemTokenStore(Session("ax", "rx"))
  val (eng, store) = engine(ts, handler = MockEngine { req ->
    when (req.url.encodedPath) {
      "/auth/whoami" -> respond(whoami(activeOwner), HttpStatusCode.OK, jsonCt)   // active owner of fam1
      "/families/fam1/members" -> respond("""{"members":[{"uid":"u1","display_name":"Pat"},{"uid":"u2","display_name":"Maya"}]}""", HttpStatusCode.OK, jsonCt)
      else -> respond("", HttpStatusCode.NotFound)
    }
  })
  eng.restore()
  assertEquals(listOf("u1", "u2"), store.state.members.map { it.uid })   // loaded WITHOUT opening the Members screen
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd apps && JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :client:desktopTest --tests "*AuthEngineTest*restore eager*" --rerun-tasks`
Expected: FAIL — `store.state.members` empty (roster not loaded on restore).

- [ ] **Step 3: Implement** — add the no-mutex roster loader + call it at the `loadMemberships` tail (it already holds the mutex; same convention as `resumePendingDeviceLink`).

Add near `loadMembers` (~line 227):
```kotlin
// Eager, quiet roster load so a checklist doneBy byline resolves to a name ANYWHERE the
// content renders (not only after opening the Members screen). No RosterRequested/Failed
// noise — a failure just leaves bylines on the "a family member" fallback. No-mutex core
// (called from loadMemberships, which already holds the lock).
private suspend fun loadRosterLocked(fid: String?) {
  val session = store.state.session ?: return
  if (fid.isNullOrEmpty()) return
  try { store.dispatch(RosterLoaded(callWithRefresh(session) { authClient.familyMembers(it.access, fid) })) }
  catch (e: Exception) { /* quiet */ }
}
```

In `loadMemberships` (after the two resume calls, ~line 346):
```kotlin
      store.dispatch(MembershipsLoaded(who.families))
      resumePendingDeviceLink()
      resumePendingInviteLink()
      loadRosterLocked(store.state.activeFamilyId)   // eager roster for doneBy bylines
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd apps && JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :client:desktopTest --tests "*AuthEngineTest*" --rerun-tasks`
Expected: PASS (the new test + all existing AuthEngine tests).

- [ ] **Step 5: Commit**

```bash
git add apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/AuthEngine.kt apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/AuthEngineTest.kt
git commit -m "feat(client): eager-load the roster on membership resolve (app-wide member names)"
```

---

### Task 3: Render the resolved name in the checklist byline

**Files:**
- Modify: `apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/HubScreens.kt` (`HubDetailScreen` ~260, `HubBlockCard` ~675, `ChecklistBlock` ~956, `ChecklistRow` ~1010)
- Test: `apps/ui/src/desktopTest/kotlin/com/sloopworks/dayfold/client/HubChecklistToggleTest.kt`

**Interfaces:**
- Consumes: `displayNameFor` (Task 1), `state.members` + `state.session?.userId` (populated by Task 2).
- Produces: a `resolveDoneBy: (String?) -> String?` threaded `HubDetailScreen → HubBlockCard → ChecklistBlock → ChecklistRow` (same path as the existing `onToggleItem`), built once in `HubDetailScreen` from its `state`.

- [ ] **Step 1: Write the failing tests** (extend `HubChecklistToggleTest`; the harness `treeWith` + `HubDetailScreen(state, …)` already exists there)

```kotlin
@Test fun `a done item shows the toggler's first name, not the raw userId`() = runComposeUiTest {
  val state = AppState(
    currentHubId = "h1",
    session = Session("a", "r", userId = "u_me"),
    members = listOf(FamilyMember(uid = "u_pat", displayName = "Patrick Jackson")),
    currentHubTree = treeWith(ChecklistItem(id = "i1", text = "Email letters", done = true, doneBy = "u_pat")),
  )
  setContent { MaterialTheme { HubDetailScreen(state) } }
  onNodeWithText("1 done").performClick()                 // expand the folded done section
  onNodeWithText("✓ Patrick").assertExists()
  onAllNodesWithText("u_pat", substring = true).assertCountEquals(0)   // never the raw userId
}

@Test fun `a done item by an unknown member falls back to a family member`() = runComposeUiTest {
  val state = AppState(
    currentHubId = "h1", session = Session("a", "r", userId = "u_me"), members = emptyList(),
    currentHubTree = treeWith(ChecklistItem(id = "i1", text = "Email letters", done = true, doneBy = "u_gone")),
  )
  setContent { MaterialTheme { HubDetailScreen(state) } }
  onNodeWithText("1 done").performClick()
  onNodeWithText("✓ a family member").assertExists()
  onAllNodesWithText("u_gone", substring = true).assertCountEquals(0)
}
```
Add imports: `androidx.compose.ui.test.assertCountEquals`, `androidx.compose.ui.test.onAllNodesWithText`. (`assertExists`/`onNodeWithText`/`performClick` already imported.)

- [ ] **Step 2: Run to verify they fail**

Run: `cd apps && JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :ui:desktopTest --tests "*HubChecklistToggleTest*" --rerun-tasks`
Expected: FAIL — byline currently renders `✓ u_pat` (raw userId), so `✓ Patrick` doesn't exist and the raw-userId count is 1.

- [ ] **Step 3: Thread the resolver + use it in the byline**

`HubDetailScreen` — build the resolver from `state` and pass it to the block card. Find where `HubBlockCard(...)` is called (~line 508) and add the argument; add the param to `HubDetailScreen`'s body-scope (it already has `state`):
```kotlin
// build once from the eager-loaded roster + self id; identity resolved render-side only
val resolveDoneBy: (String?) -> String? = { displayNameFor(it, state.members, state.session?.userId) }
```
Pass `resolveDoneBy = resolveDoneBy` into the `HubBlockCard(...)` call (~508).

`HubBlockCard` (~675) — add param + forward:
```kotlin
private fun HubBlockCard(
  block: HubBlock,
  … existing params …,
  resolveDoneBy: (String?) -> String? = { null },
) { … }
```
In its `"checklist" -> ChecklistBlock(block, onToggleItem = onToggleItem, onRetryBlock = onRetryBlock)` (~725) add `, resolveDoneBy = resolveDoneBy`.

`ChecklistBlock` (~956) — add param + forward to every `ChecklistRow(...)` call (there are two: the non-interactive `ChecklistRow(it, onToggle = null)` ~968 and the interactive `ChecklistRow(item, onToggle = …)` ~991, plus the one inside `DoneSection`). Simplest: pass `resolveDoneBy` to `ChecklistRow` and to `DoneSection` so its rows get it too:
```kotlin
private fun ChecklistBlock(
  block: HubBlock,
  onToggleItem: (String, String, Boolean) -> Unit,
  onRetryBlock: (String) -> Unit,
  resolveDoneBy: (String?) -> String? = { null },
) { … }
```
Thread `resolveDoneBy` into: the non-interactive `ChecklistRow(it, onToggle = null, resolveDoneBy = resolveDoneBy)`, the active `ChecklistRow(item, onToggle = …, resolveDoneBy = resolveDoneBy)`, and `DoneSection(done, …, resolveDoneBy = resolveDoneBy)`. Add `resolveDoneBy` param to `DoneSection` (~1077) and forward it to the `ChecklistRow` it renders.

`ChecklistRow` (~1010) — add param + use it in BOTH the byline and the a11y state description:
```kotlin
private fun ChecklistRow(item: ChecklistItem, onToggle: ((Boolean) -> Unit)? = null,
                         resolveDoneBy: (String?) -> String? = { null }) {
```
State description (~1036):
```kotlin
        if (done && item.doneBy != null) append(", by ${resolveDoneBy(item.doneBy) ?: "a family member"}")
```
Byline (~1067):
```kotlin
      val sub = if (done && item.doneBy != null) "✓ ${resolveDoneBy(item.doneBy) ?: "a family member"}"
        else listOfNotNull(item.due?.let { "Due $it" }, item.assignee).joinToString(" · ")
```
(No import needed — `displayNameFor` is the same package.)

- [ ] **Step 4: Run to verify they pass**

Run: `cd apps && JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :ui:desktopTest --tests "*HubChecklistToggleTest*" --rerun-tasks`
Expected: PASS. Then the full `:ui:desktopTest` to confirm no golden churn (existing checklist goldens use short items with name-style `doneBy` like `"Mom"` → `resolveDoneBy("Mom")` returns null (not a member uid) → falls back to "a family member"; if any golden's `doneBy` was a bare name it will change → re-record via the CI record-goldens job. Check the diff; only `doneBy`-bearing checklist goldens can change).

- [ ] **Step 5: Commit**

```bash
git add apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/HubScreens.kt apps/ui/src/desktopTest/kotlin/com/sloopworks/dayfold/client/HubChecklistToggleTest.kt
git commit -m "feat(ui): checklist done-byline shows the member's first name, not the raw userId"
```

---

### Task 4: Full verification + on-device + CHANGELOG

- [ ] **Step 1: Full sweep** — `cd apps && JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :client:desktopTest :ui:desktopTest :androidApp:compileDebugKotlin :ui:compileKotlinIosArm64`. Expected: BUILD SUCCESSFUL. If a checklist golden changed (Task 3 Step 4), record macOS locally (`-Dsnapshot.record=true`) + linux via the `_record-goldens` CI job (see the owner-invite-mint PR history), eyeball, commit.
- [ ] **Step 2: On-device** — install to the Pixel 4a, open the Butler hub → Money checklist; the done items now read "✓ Patrick" / "✓ Lillian" / "✓ You" (whoever toggled), never `✓ usr_…`. Screenshot.
- [ ] **Step 3: CHANGELOG** — add under a dated heading:
```markdown
### Fixed (client)
- **Checklist "done by" now shows a name, not a user ID.** A ticked checklist item's
  byline resolved the toggler's userId (`usr_…`) verbatim; it now renders the member's
  first name ("✓ Patrick", "✓ You"), resolved render-side from the family roster — content
  stays content-blind (the name never enters the payload). Unknown/departed → "a family member".
```
- [ ] **Step 4: PR + merge on green** (confirm before merge, per the operator's standing pattern).

---

## Self-Review

**Spec coverage:** what-to-show (first name / "You") → Task 1 + Task 3 byline ✓; render-time-from-roster (Approach A) → Task 1 resolver + Task 3 render ✓; roster available app-wide (eager-load) → Task 2 ✓; assignee/email out of scope → Global Constraints + byline leaves the `else` branch untouched ✓; departed/unknown → "a family member" (Task 1 null → Task 3 fallback) ✓; a11y state description also resolved → Task 3 Step 3 ✓; content-blind/E2EE unchanged (name never written to content) → Task 1/3 read-only resolution ✓.

**Placeholder scan:** every step has concrete code + commands. The one conditional ("if a golden changed, re-record") is bounded with the exact trigger + tool.

**Type consistency:** `firstNameOf(String):String` + `displayNameFor(String?, List<FamilyMember>, String?):String?` (Task 1) consumed identically in Task 2 (via `state.members`) and Task 3 (`resolveDoneBy`). `resolveDoneBy: (String?) -> String?` threaded with one signature through all four composables. `FamilyMember.uid/displayName` + `Session.userId` match `AuthClient.kt`/`Model.kt`.

**Open note:** existing checklist snapshot states use name-style `doneBy` (e.g. `"Mom"`), not real uids — after the fix those resolve to null → "a family member", which will change any such golden. Task 3 Step 4 catches it; re-record if so.
