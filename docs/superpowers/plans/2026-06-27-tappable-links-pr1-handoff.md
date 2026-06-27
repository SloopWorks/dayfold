# Tappable Links PR1 — Inline-Link Handoff Fix — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route inline `[label](url)` link taps in rendered card/hub text through the vetted, crash-safe `PlatformActions` instead of Compose's default `UriHandler`.

**Architecture:** Compose dispatches a `LinkAnnotation.Url` tap (with no custom listener) to `LocalUriHandler.current.openUri(url)`. We install a custom `LocalUriHandler` around the app content whose `openUri` calls a shell-provided `onOpenUri`, wired to a new `PlatformActions.openUri` that re-vets the scheme allowlist and opens via the existing guarded platform `open`. This makes inline links share the exact handoff path the action buttons already use (Android `runCatching` → no crash; iOS `geo:` → Apple Maps; desktop best-effort).

**Tech Stack:** Kotlin 2.3.20, Compose Multiplatform 1.11.1, KMP source sets (commonMain + androidMain/iosMain/desktopMain), JUnit-platform desktop tests.

## Global Constraints

- Worktree: `/Users/patrick/workspace/dayfold/.claude/worktrees/link-improvements` (branch `claude/link-improvements`). Run all commands there.
- `commonMain` link-vetting code stays Compose-free where it already is; the new `UriHandler` is the only Compose-touching common addition (allowed — `UriHandler` is a Compose UI type).
- Single allowlist source of truth: `ALLOWED_SCHEMES = {https, mailto, tel, geo, sms}` in `CardRender.kt:44`. Never duplicate it.
- The client render-time allowlist is THE security boundary (server is content-blind, ADR 0015). `openUri` re-vets as defense-in-depth; it must never open a non-allowlisted scheme.
- No server changes in this PR (or ever, for content — ADR 0015).
- Build/test toolchain: JDK17. Tests run device-free via the `desktopTest` task.
- Commit after each task. Conventional-commit messages, written normally (not caveman).

---

### Task 1: `PlatformActions.openUri` + vetted-open helper + iOS geo→maps

Add a direct "open this already-built URI" entry to the platform effect layer, re-vetting the scheme. Reuses each actual's existing private `open` (Android's is `runCatching`-guarded). Adds the iOS `geo:` → Apple Maps rewrite so `geo:` links stop being a silent no-op on iOS.

**Files:**
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/cards/PlatformActions.kt` (add `vettedOpenUri` + expect `fun openUri`)
- Modify: `apps/client/src/androidMain/kotlin/com/sloopworks/dayfold/client/cards/PlatformActions.android.kt`
- Modify: `apps/client/src/iosMain/kotlin/com/sloopworks/dayfold/client/cards/PlatformActions.ios.kt`
- Modify: `apps/client/src/desktopMain/kotlin/com/sloopworks/dayfold/client/cards/PlatformActions.desktop.kt`
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/cards/PlatformActionsTest.kt`

**Interfaces:**
- Produces:
  - `fun vettedOpenUri(uri: String): String?` (commonMain, top-level) — returns the trimmed uri iff `schemeOf(it) in ALLOWED_SCHEMES`, else null.
  - `expect class PlatformActions { fun perform(action: CardAction); fun openUri(uri: String) }` — new `openUri` member.
- Consumes: existing `schemeOf`, `ALLOWED_SCHEMES` (`CardRender.kt:44-45`), each actual's private `open(uri)`.

- [ ] **Step 1: Write the failing test** (append to `PlatformActionsTest.kt`)

```kotlin
@Test fun vettedOpenUri_allows_only_allowlisted_schemes() {
  // allowlisted → returned verbatim (trimmed)
  assertEquals("tel:+15551234567", vettedOpenUri("tel:+15551234567"))
  assertEquals("mailto:a@b.com", vettedOpenUri("mailto:a@b.com"))
  assertEquals("https://x.com", vettedOpenUri("https://x.com"))
  assertEquals("geo:0,0?q=x", vettedOpenUri("geo:0,0?q=x"))
  assertEquals("sms:+15551234567", vettedOpenUri("sms:+15551234567"))
  // disallowed → null (defense-in-depth; never opened)
  assertNull(vettedOpenUri("javascript:alert(1)"))
  assertNull(vettedOpenUri("data:text/html,x"))
  assertNull(vettedOpenUri("http://x.com")) // http never allowed (https only)
}
```

Ensure the file imports the symbol: add `import com.sloopworks.dayfold.client.cards.vettedOpenUri` is NOT needed (same package); confirm `import kotlin.test.assertNull` is present (add if missing).

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/patrick/workspace/dayfold/.claude/worktrees/link-improvements/apps && ./gradlew :client:desktopTest --tests "*PlatformActionsTest.vettedOpenUri*"`
Expected: FAIL — `Unresolved reference: vettedOpenUri`.

- [ ] **Step 3: Add `vettedOpenUri` + expect `openUri`** in `PlatformActions.kt`

Add the expect member to the class:

```kotlin
expect class PlatformActions {
  fun perform(action: CardAction)
  /** Open an already-built, vetted URI (inline body-link taps). Re-vets the
   *  scheme allowlist as defense-in-depth; no-op if it doesn't vet. */
  fun openUri(uri: String)
}
```

Add the top-level helper next to `cardActionUri`:

```kotlin
/** The URI to open for a direct inline-link tap, or null if its scheme isn't
 *  allowlisted. Same one-seam vetting as [cardActionUri], for already-built URIs. */
fun vettedOpenUri(uri: String): String? =
  uri.trim().takeIf { schemeOf(it) in ALLOWED_SCHEMES }
```

- [ ] **Step 4: Implement the three actuals**

Android (`PlatformActions.android.kt`) — add inside the class, reusing guarded `open`:

```kotlin
  actual fun openUri(uri: String) { vettedOpenUri(uri)?.let(::open) }
```

Desktop (`PlatformActions.desktop.kt`) — add inside the class:

```kotlin
  actual fun openUri(uri: String) { vettedOpenUri(uri)?.let(::open) }
```

iOS (`PlatformActions.ios.kt`) — add the member AND make `open` translate `geo:` to Apple Maps (iOS has no `geo:` handler):

```kotlin
  actual fun openUri(uri: String) { vettedOpenUri(uri)?.let(::open) }

  private fun open(uri: String) {
    // iOS has no geo: handler — geo:0,0?q=<enc> → https://maps.apple.com/?q=<enc>
    val target = if (uri.startsWith("geo:")) "https://maps.apple.com/?q=" + uri.substringAfter("q=", "") else uri
    val url = NSURL.URLWithString(target) ?: return
    UIApplication.sharedApplication.openURL(url)
  }
```

(Replace the existing iOS `open` body; keep the `perform` `else -> cardActionUri(action)?.let(::open)` branch so the same geo translation now benefits the Navigate button too.)

- [ ] **Step 5: Run test to verify it passes**

Run: `cd /Users/patrick/workspace/dayfold/.claude/worktrees/link-improvements/apps && ./gradlew :client:desktopTest --tests "*PlatformActionsTest.vettedOpenUri*"`
Expected: PASS.

- [ ] **Step 6: Compile all targets (catch actual/expect mismatch)**

Run: `cd /Users/patrick/workspace/dayfold/.claude/worktrees/link-improvements/apps && ./gradlew :client:compileKotlinDesktop :client:compileDebugKotlinAndroid :client:compileKotlinIosArm64`
Expected: BUILD SUCCESSFUL (proves the new `openUri` actual exists on every target — iOS reachable too).

- [ ] **Step 7: Commit**

```bash
cd /Users/patrick/workspace/dayfold/.claude/worktrees/link-improvements
git add apps/client/src
git commit -m "feat(client): PlatformActions.openUri — vetted direct-URI open + iOS geo→maps"
```

---

### Task 2: custom `LocalUriHandler` → route inline link taps through `PlatformActions`

Install a `UriHandler` around the app content so `LinkAnnotation.Url` taps call the shell's `openUri`. Wire all three shells.

**Files:**
- Create: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/cards/PlatformUriHandler.kt`
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/FeedApp.kt` (new `onOpenUri` param + provide `LocalUriHandler`)
- Modify: `apps/androidApp/src/main/kotlin/com/sloopworks/dayfold/android/MainActivity.kt:161`
- Modify: `apps/client/src/desktopMain/kotlin/com/sloopworks/dayfold/client/Main.kt:66`
- Modify: `apps/client/src/iosMain/kotlin/com/sloopworks/dayfold/client/MainViewController.kt:66`
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/cards/PlatformUriHandlerTest.kt`

**Interfaces:**
- Consumes: `PlatformActions.openUri` (Task 1); `androidx.compose.ui.platform.UriHandler` / `LocalUriHandler`.
- Produces: `class PlatformUriHandler(onOpenUri: (String) -> Unit) : UriHandler`; `FeedApp(..., onOpenUri: (String) -> Unit = {})`.

- [ ] **Step 1: Empirical pre-check — confirm the mechanism**

Before building, confirm Compose routes `LinkAnnotation.Url` (no custom listener) through `LocalUriHandler`. Run the existing app on desktop and tap an inline link with a custom handler installed in a scratch test, OR rely on the Task-2 verify step. Documented Compose behavior: a `LinkAnnotation.Url` without a `linkInteractionListener` is opened via `LocalUriHandler.current`. If the Task-2 manual verify (Step 8) shows the handler is NOT invoked, STOP and switch to attaching a `linkInteractionListener` per link in `CardRender.kt` instead. (Spec risk callout.)

- [ ] **Step 2: Write the failing test** (`PlatformUriHandlerTest.kt`)

```kotlin
package com.sloopworks.dayfold.client.cards

import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformUriHandlerTest {
  @Test fun forwards_uri_to_callback() {
    var seen: String? = null
    val handler = PlatformUriHandler { seen = it }
    handler.openUri("tel:+15551234567")
    assertEquals("tel:+15551234567", seen)
  }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd /Users/patrick/workspace/dayfold/.claude/worktrees/link-improvements/apps && ./gradlew :client:desktopTest --tests "*PlatformUriHandlerTest*"`
Expected: FAIL — `Unresolved reference: PlatformUriHandler`.

- [ ] **Step 4: Create `PlatformUriHandler.kt`**

```kotlin
package com.sloopworks.dayfold.client.cards

import androidx.compose.ui.platform.UriHandler

// Routes inline [label](url) link taps (LinkAnnotation.Url, no custom listener →
// LocalUriHandler) through the shell's vetted PlatformActions.openUri instead of
// Compose's default system handler — so inline links share the action-button path
// (Android runCatching, iOS geo→maps, desktop best-effort). The render-time
// allowlist already gates which links exist; openUri re-vets (defense-in-depth).
class PlatformUriHandler(private val onOpenUri: (String) -> Unit) : UriHandler {
  override fun openUri(uri: String) = onOpenUri(uri)
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd /Users/patrick/workspace/dayfold/.claude/worktrees/link-improvements/apps && ./gradlew :client:desktopTest --tests "*PlatformUriHandlerTest*"`
Expected: PASS.

- [ ] **Step 6: Add `onOpenUri` param + provide `LocalUriHandler` in `FeedApp.kt`**

Add imports near the top of `FeedApp.kt`:

```kotlin
import androidx.compose.ui.platform.LocalUriHandler
import com.sloopworks.dayfold.client.cards.PlatformUriHandler
```

Add the parameter to the `FeedApp(` signature (next to `onPlatformAction`, line 56):

```kotlin
  onOpenUri: (String) -> Unit = {},     // inline body-link tap → shell PlatformActions.openUri
```

Wrap the existing `DayfoldTheme { … }` call (line 87) with the provider — wrapping OUTSIDE `DayfoldTheme` so the existing `return@DayfoldTheme` labels stay valid:

```kotlin
  val uriHandler = remember(onOpenUri) { PlatformUriHandler(onOpenUri) }
  CompositionLocalProvider(LocalUriHandler provides uriHandler) {
    DayfoldTheme {
      // … existing body unchanged …
    }
  }
```

(`CompositionLocalProvider` is already imported at `FeedApp.kt:15`.)

- [ ] **Step 7: Wire the three shells**

Android `MainActivity.kt` (after `onPlatformAction = actions::perform,` at line 161):

```kotlin
          onOpenUri = actions::openUri,
```

Desktop `Main.kt` (after `onPlatformAction = actions::perform,` at line 66):

```kotlin
      onOpenUri = actions::openUri,
```

iOS `MainViewController.kt` (after `onPlatformAction = actions::perform,` at line 66):

```kotlin
    onOpenUri = actions::openUri,
```

- [ ] **Step 8: Compile all targets + run full client desktop tests**

Run: `cd /Users/patrick/workspace/dayfold/.claude/worktrees/link-improvements/apps && ./gradlew :client:desktopTest :client:compileDebugKotlinAndroid :client:compileKotlinIosArm64 :androidApp:compileDebugKotlin`
Expected: BUILD SUCCESSFUL; all desktop tests pass.

- [ ] **Step 9: Commit**

```bash
cd /Users/patrick/workspace/dayfold/.claude/worktrees/link-improvements
git add apps/client/src apps/androidApp/src
git commit -m "feat(client): route inline link taps through PlatformActions via LocalUriHandler"
```

---

### Task 3: Manual verification (the BLOCKER this PR fixes)

Prove the actual user-visible defects are gone. Per `processes/agent-dev-loop.md` cheap feedback loop (run desktop app + action log / snapshot).

**Files:** none (verification only).

- [ ] **Step 1: Run the desktop app**

Run: `cd /Users/patrick/workspace/dayfold/.claude/worktrees/link-improvements/apps && ./gradlew :client:run` (or the project's documented desktop run task — check `processes/agent-dev-loop.md`).

- [ ] **Step 2: Verify inline `tel:` / `geo:` handoff**

Open a card/hub whose `body_md` contains an inline `[call](tel:+15551234567)` and `[map](geo:0,0?q=test)` link (use a fake scenario in `apps/client/src/commonMain/.../fake/FakeScenarios.kt` or `SampleData.kt` if one isn't present — add a temporary one, do NOT commit it). Tap each.
Expected: handoff is attempted via `PlatformActions` (desktop `Desktop.browse` best-effort, guarded — no crash on unsupported scheme). Confirm via no exception in the action log / console.

- [ ] **Step 3: Verify the security invariant holds**

Confirm a `[x](javascript:alert(1))` in `body_md` renders as plain text (no link) — it never becomes a `LinkAnnotation` (render-time allowlist, `CardRender.kt:54/107`), so the custom handler is never even reached for it.

- [ ] **Step 4: Record the result**

Note in the PR description: Android crash-on-missing-handler resolved (inline path now `runCatching` via `openUri`→`open`); iOS `geo:` now routes to Apple Maps; desktop guarded. State which were verified live (desktop) vs by code-reuse argument (Android/iOS actuals not run on-device this PR).

---

## Self-Review

**Spec coverage:** PR1 scope in `2026-06-27-tappable-links-design.md` = custom `LocalUriHandler` routing inline taps through `PlatformActions`, iOS `geo:` home, Android crash-safety, no render change, security invariant test. ✓ Tasks 1–3 cover each. Out-of-scope items (linkifier, `linkrules`, server) correctly excluded → PR2.

**Placeholder scan:** No TBD/TODO. Every code step shows real code. The one conditional ("if handler not invoked, switch to linkInteractionListener", Task 2 Step 1) is an explicit, spec-flagged fallback with a concrete alternative, not a placeholder.

**Type consistency:** `vettedOpenUri(String): String?`, `PlatformActions.openUri(String)`, `PlatformUriHandler((String)->Unit)`, `FeedApp(onOpenUri = ...)`, shells `actions::openUri` — names consistent across all tasks. `openUri` is the single verb everywhere.

**Risk:** the whole PR hinges on Compose routing `LinkAnnotation.Url` through `LocalUriHandler` — Task 2 Step 1 + Step 8 verify empirically with a documented fallback.
