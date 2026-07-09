# Card → Detail Shared Elements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the accent tile, kicker, title, and primary "Open" button *travel* as shared elements from a Now card into its detail (on top of the existing container transform), gated on content equality so it's correct across all types and self-scaling.

**Architecture:** A pure, unit-tested `sharedTransitionKeys(card)` predicate (apps/client) decides which of the four elements are shareable for a given card. A `Modifier.cardSharedElement(key)` Compose helper (apps/ui, no-op without transition scopes) applies the shared bounds. Both the card (`BaseCard`) and the detail (`HeroHeader`/`ActionsRow`) apply the same gated keys, and the shared primary button is unified to teal `FilledTonalButton`.

**Tech Stack:** Kotlin 2.3.20, Compose Multiplatform 1.11.1 (`sharedBounds`/`SharedTransitionLayout`), JDK 17 Gradle.

## Global Constraints

- JDK 17: `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`. Commands run from `apps/`.
- No new dependencies. No `Route`/reducer/model changes.
- Shared-element modifiers MUST be **no-ops when the transition scopes are absent** (like the existing `cardSharedBounds`) so snapshot tests are unaffected.
- Content-equality gating is the correctness rule: `title` always; `kicker` when `kickerFor(card)` non-blank; `tile` when `cardMonogram(card) == detailMonogram(card)`; `button` when `primaryActionFor(card).second == detailActions(card).firstOrNull()?.action`.
- The shared primary button is **teal `FilledTonalButton`** at both ends (card is already teal; detail's primary changes from coral `Button`).
- Shared-element keys are namespaced distinct from the container `card-$id`: `tile-$id`, `kicker-$id`, `title-$id`, `action-$id`.
- `CardAction` is a `sealed interface` with `data class` subtypes → `==` is structural (the button gate relies on this).
- Match existing code style; no unrelated refactors.

---

### Task 1: `sharedTransitionKeys` predicate + monogram extraction (TDD, apps/client)

**Files:**
- Create: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/cards/SharedTransitionKeys.kt`
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/cards/SharedTransitionKeysTest.kt`
- Modify: `apps/ui/.../cards/TypedCards.kt` (remove private `monogramOf`; card render sites use `cardMonogram`), `apps/ui/.../cards/DetailScreen.kt` (remove private `typeMonogram`; hero uses `detailMonogram`)

**Interfaces:**
- Consumes: `kickerFor`, `primaryActionFor` (`TypedCardLogic.kt`), `detailActions` (`DetailMeta.kt`), `Card`, payloads (`Model.kt`), `CardAction`.
- Produces: `data class CardSharedKeys(tile, kicker, title, button: Boolean)`; `fun sharedTransitionKeys(card: Card): CardSharedKeys`; `fun cardMonogram(card: Card): String`; `fun detailMonogram(card: Card): String`.

- [ ] **Step 1: Write the failing test**

Create `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/cards/SharedTransitionKeysTest.kt`:

```kotlin
package com.sloopworks.dayfold.client.cards

import com.sloopworks.dayfold.client.Card
import com.sloopworks.dayfold.client.ContactPayload
import com.sloopworks.dayfold.client.GeoPayload
import com.sloopworks.dayfold.client.InvitePayload
import com.sloopworks.dayfold.client.LinkPayload
import com.sloopworks.dayfold.client.Payload
import com.sloopworks.dayfold.client.Provenance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedTransitionKeysTest {
  private fun card(type: String, payload: Payload) =
    Card(id = "x", kind = "action", title = "T", provenance = Provenance("email"), type = type, payload = payload)

  @Test fun `link with url shares all four`() {
    val k = sharedTransitionKeys(card("link", Payload(link = LinkPayload(url = "https://x.com", kind = "page"))))
    assertTrue(k.title); assertTrue(k.kicker); assertTrue(k.tile); assertTrue(k.button)
  }

  @Test fun `link without url does not share button`() {
    val k = sharedTransitionKeys(card("link", Payload(link = LinkPayload(kind = "page"))))
    assertTrue(k.tile)     // "L" == "L"
    assertFalse(k.button)  // card falls back to Details/OpenDetail; detail drops Open
  }

  @Test fun `contact shares title and kicker only`() {
    val k = sharedTransitionKeys(card("contact", Payload(contact = ContactPayload(name = "Chris Jackson", phone = "+15551234567", address = "1 Main St"))))
    assertTrue(k.title); assertTrue(k.kicker)
    assertFalse(k.tile)    // card "CJ" != detail "C"
    assertFalse(k.button)  // card OpenDetail != detail Navigate(Directions)
  }

  @Test fun `invite shares tile but not button`() {
    val k = sharedTransitionKeys(card("invite", Payload(invite = InvitePayload())))
    assertTrue(k.tile)     // "!" == "!"
    assertFalse(k.button)  // OpenDetail != Directions/Share
  }

  @Test fun `geo with address shares button`() {
    val k = sharedTransitionKeys(card("geo", Payload(geo = GeoPayload(address = "Riverside Park"))))
    assertTrue(k.tile); assertTrue(k.button)  // Navigate == Navigate
  }

  @Test fun `monograms differ only for contact`() {
    assertEquals("L", cardMonogram(card("link", Payload(link = LinkPayload()))))
    assertEquals("L", detailMonogram(card("link", Payload(link = LinkPayload()))))
    assertEquals("CJ", cardMonogram(card("contact", Payload(contact = ContactPayload(name = "Chris Jackson")))))
    assertEquals("C", detailMonogram(card("contact", Payload(contact = ContactPayload(name = "Chris Jackson")))))
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :client:desktopTest --tests "com.sloopworks.dayfold.client.cards.SharedTransitionKeysTest"`
Expected: FAIL — `sharedTransitionKeys`/`cardMonogram`/`detailMonogram` unresolved.

- [ ] **Step 3: Write the implementation**

Create `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/cards/SharedTransitionKeys.kt`:

```kotlin
package com.sloopworks.dayfold.client.cards

import com.sloopworks.dayfold.client.Card

// Which card-header elements are shareable (identical content) between the Now card and its
// detail → drive the card→detail shared-element transition. Pure so it's unit-tested and both
// render sites (card + detail) agree. Design: 2026-07-08-card-detail-shared-elements.
data class CardSharedKeys(val tile: Boolean, val kicker: Boolean, val title: Boolean, val button: Boolean)

fun sharedTransitionKeys(card: Card): CardSharedKeys = CardSharedKeys(
  tile = cardMonogram(card) == detailMonogram(card),
  kicker = kickerFor(card).isNotBlank(),
  title = true,
  button = primaryActionFor(card).second == detailActions(card).firstOrNull()?.action,
)

// Monogram on the Now card's accent tile — a contact shows name initials; every other type a glyph.
fun cardMonogram(card: Card): String = when (card.type) {
  "file" -> "F"; "link" -> "L"; "email" -> "@"; "invite" -> "!"; "geo" -> "G"
  "contact" -> monogramOf(card.payload?.contact?.name, "C")
  else -> "•"
}

// Monogram on the detail hero tile — the type glyph, always.
fun detailMonogram(card: Card): String = when (card.type) {
  "file" -> "F"; "link" -> "L"; "invite" -> "!"; "contact" -> "C"; "geo" -> "G"; "email" -> "@"
  else -> "•"
}

// Name → up-to-2-letter initials (falls back when blank). Moved here from TypedCards so the
// predicate and the card render agree on one definition.
internal fun monogramOf(text: String?, fallback: String): String =
  text?.trim()?.split(" ")?.filter { it.isNotEmpty() }?.take(2)?.joinToString("") { it.first().uppercase() }
    ?.ifBlank { fallback } ?: fallback
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :client:desktopTest --tests "com.sloopworks.dayfold.client.cards.SharedTransitionKeysTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Point the render sites at the moved functions**

In `apps/ui/.../cards/TypedCards.kt`: delete the private `monogramOf` (now in apps/client). Change each card's monogram arg to `cardMonogram(card)`:
- `StandardCard`: `BaseCard(card, cardMonogram(card), onAction)`
- `InviteCard`: `BaseCard(card, cardMonogram(card), onAction, container = MaterialTheme.colorScheme.primaryContainer, solidAccent = true) { ... }`
- `ContactCard`: `BaseCard(card, cardMonogram(card), onAction) { ... }` (drop the local `val name` / `monogramOf(name, "C")`)
- `GeoCard`: `BaseCard(card, cardMonogram(card), onAction) { MapStrip() }`

In `apps/ui/.../cards/DetailScreen.kt`: delete the private `typeMonogram`; the hero's `AccentTile(typeMonogram(card), ...)` becomes `AccentTile(detailMonogram(card), ...)`. Add the import `import com.sloopworks.dayfold.client.cards.detailMonogram` (and `cardMonogram` in TypedCards if same-package resolution needs it — they're all in package `...cards`, so no import needed).

- [ ] **Step 6: Build + full suite (no visual change — same monograms)**

Run: `cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :ui:desktopTest`
Expected: green — the extraction is value-identical (same monograms), so no golden change.

- [ ] **Step 7: Commit**

```bash
git add apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/cards/SharedTransitionKeys.kt \
        apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/cards/SharedTransitionKeysTest.kt \
        apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/cards/TypedCards.kt \
        apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/cards/DetailScreen.kt
git commit -m "feat(ui): sharedTransitionKeys predicate + extract card/detail monograms"
```

---

### Task 2: `cardSharedElement` helper + modifier params on shared sub-composables

**Files:**
- Modify: `apps/ui/.../cards/SharedScopes.kt` (add `cardSharedElement`), `apps/ui/.../cards/TypedCards.kt` (add `modifier` params to `AccentTile`, `KickerChip`, `PrimaryActionPill`)

**Interfaces:**
- Produces: `@Composable fun Modifier.cardSharedElement(key: String): Modifier`; `AccentTile(monogram, accent, solid, modifier = Modifier)`, `KickerChip(text, accent, solid, modifier = Modifier)`, `PrimaryActionPill(label, modifier = Modifier, onClick)`.

- [ ] **Step 1: Add the helper** (no unit test — Compose, no-op in snapshots; verified by build + Task 5 on-device)

In `apps/ui/.../cards/SharedScopes.kt`, add below `cardSharedBounds`:

```kotlin
/** Apply a shared-element (sharedBounds) under an arbitrary key when both transition scopes are
 *  present — lets an individual element (tile / kicker / title / button) travel card→detail on top
 *  of the container transform. No-op otherwise (snapshot-safe), same as [cardSharedBounds]. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.cardSharedElement(key: String): Modifier {
  val sts = LocalSharedTransitionScope.current ?: return this
  val avs = LocalAnimatedVisibilityScope.current ?: return this
  return with(sts) {
    this@cardSharedElement.sharedBounds(rememberSharedContentState(key = key), animatedVisibilityScope = avs)
  }
}
```

- [ ] **Step 2: Add `modifier` params to the three shared sub-composables**

In `apps/ui/.../cards/TypedCards.kt`:
- `internal fun AccentTile(monogram: String, accent: CardAccent, solid: Boolean, modifier: Modifier = Modifier)` — change its `Surface(...)` to `Surface(color = bg, shape = RoundedCornerShape(14.dp), modifier = modifier.size(44.dp).clearAndSetSemantics {})`.
- `internal fun KickerChip(text: String, accent: CardAccent, solid: Boolean, modifier: Modifier = Modifier)` — change its `Surface(color = bg, shape = RoundedCornerShape(8.dp))` to `Surface(color = bg, shape = RoundedCornerShape(8.dp), modifier = modifier)`.
- `private fun PrimaryActionPill(label: String, modifier: Modifier = Modifier, onClick: () -> Unit)` — change to `FilledTonalButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp)) { Text(label) }`.

- [ ] **Step 3: Build**

Run: `cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :ui:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/cards/SharedScopes.kt \
        apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/cards/TypedCards.kt
git commit -m "feat(ui): cardSharedElement helper + modifier hooks on tile/kicker/pill"
```

---

### Task 3: Apply gated shared-element keys in the card (`BaseCard`)

**Files:**
- Modify: `apps/ui/.../cards/TypedCards.kt` (`BaseCard`)

**Interfaces:**
- Consumes: `sharedTransitionKeys` (Task 1), `cardSharedElement` + modifier params (Task 2), `kickerFor`, `primaryActionFor`.

- [ ] **Step 1: Gate the four elements in `BaseCard`**

In `BaseCard`, compute the keys once and apply them:

```kotlin
val accent = accentFor(card.type)
val keys = sharedTransitionKeys(card)
...
ElevatedCard(
  onClick = { onAction(CardAction.OpenDetail(card.id)) },
  shape = MaterialTheme.shapes.large,
  colors = colors,
  modifier = Modifier.fillMaxWidth().cardSharedBounds(card.id), // container transform (unchanged)
) {
  Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
      AccentTile(monogram, accent, solidAccent,
        modifier = if (keys.tile) Modifier.cardSharedElement("tile-${card.id}") else Modifier)
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        KickerChip(kickerFor(card), accent, solidAccent,
          modifier = if (keys.kicker) Modifier.cardSharedElement("kicker-${card.id}") else Modifier)
        Text(card.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
          modifier = if (keys.title) Modifier.cardSharedElement("title-${card.id}") else Modifier)
      }
    }
    bodySummaryFor(card)?.let {
      Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    extra()
    Row(verticalAlignment = Alignment.CenterVertically) {
      val (label, action) = primaryActionFor(card)
      PrimaryActionPill(label,
        modifier = if (keys.button) Modifier.cardSharedElement("action-${card.id}") else Modifier) { onAction(action) }
      Spacer(Modifier.weight(1f))
      ProvenanceChip(card.provenance?.source)
    }
    PrivacyChip(card.privacy?.storage)
  }
}
```

- [ ] **Step 2: Build + tests (no golden change — helper is a no-op in snapshots)**

Run: `cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :ui:desktopTest`
Expected: green, no golden diff (card scenes render without the transition scopes → `cardSharedElement` returns `this`).

- [ ] **Step 3: Commit**

```bash
git add apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/cards/TypedCards.kt
git commit -m "feat(ui): card-side gated shared elements (tile/kicker/title/button)"
```

---

### Task 4: Apply gated keys in the detail + unify the shared button to teal (+ golden re-record)

**Files:**
- Modify: `apps/ui/.../cards/DetailScreen.kt` (`HeroHeader`, `ActionsRow`)
- Re-record: `apps/ui/src/desktopTest/resources/snapshots/{macos,linux}/detail-{file,link,geo,email}*.png`

**Interfaces:**
- Consumes: `sharedTransitionKeys`, `cardSharedElement`, `detailMonogram`, `detailActions`.

- [ ] **Step 1: Gate tile/kicker/title in `HeroHeader`**

In `HeroHeader`, compute `val keys = sharedTransitionKeys(card)` and apply keys to the hero's `AccentTile`, `KickerChip`, and the title `Text`, mirroring the card:
```kotlin
AccentTile(detailMonogram(card), accent, solid = true,
  modifier = if (keys.tile) Modifier.cardSharedElement("tile-${card.id}") else Modifier)
KickerChip(kickerFor(card), accent, solid = true,
  modifier = if (keys.kicker) Modifier.cardSharedElement("kicker-${card.id}") else Modifier)
// on the hero title Text:
modifier = if (keys.title) Modifier.cardSharedElement("title-${card.id}") else Modifier  // compose with any existing title modifier
```
(If the hero title `Text` already has a `modifier`, chain `.cardSharedElement(...)` onto it under the same `if`.)

- [ ] **Step 2: Unify the primary button + share it in `ActionsRow`**

Change `ActionsRow` to take the `card`, and render the FIRST action as a teal `FilledTonalButton` carrying the shared key when `keys.button`; everything else unchanged:

```kotlin
@Composable
private fun ActionsRow(card: Card, actions: List<DetailAction>, onAction: (CardAction) -> Unit) {
  if (actions.isEmpty()) return
  val keys = sharedTransitionKeys(card)
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    actions.forEachIndexed { i, a ->
      if (i == 0 && keys.button) {
        // the shared primary → teal tonal + shared key so it matches the card's Open pill and morphs
        FilledTonalButton(onClick = { onAction(a.action) },
          modifier = Modifier.heightIn48().cardSharedElement("action-${card.id}")) { Text(a.label) }
      } else when (a.style) {
        ActionStyle.Filled -> Button(onClick = { onAction(a.action) }, modifier = Modifier.heightIn48()) { Text(a.label) }
        ActionStyle.Tonal -> FilledTonalButton(onClick = { onAction(a.action) }, modifier = Modifier.heightIn48()) { Text(a.label) }
        ActionStyle.Outlined -> OutlinedButton(onClick = { onAction(a.action) }, modifier = Modifier.heightIn48()) { Text(a.label) }
      }
    }
  }
}
```
Update the call site (in `DetailScreen`, the `item { ActionsRow(detailActions(card), onAction) }`) to `item { ActionsRow(card, detailActions(card), onAction) }`.

- [ ] **Step 3: Run goldens to see which detail scenes shifted**

Run: `cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :ui:desktopTest --tests "*GoldenSnapshotTest"`
Expected: the `detail` scene for the **shared-button types** fails (teal vs coral primary): `detail-file`, `detail-link`, `detail-geo`, `detail-email` (× light/dark). `detail-invite`/`detail-contact` stay coral → still pass. If anything else fails, STOP and report (something unintended changed).

- [ ] **Step 4: Re-record BOTH OS golden sets**

macOS:
```bash
cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :ui:desktopTest --tests "*GoldenSnapshotTest" -Dsnapshot.record=true
```
Linux (docker — the exact image/recipe is in `processes/agent-dev-loop.md` around line 232-237):
```bash
docker run --rm --memory=7g -v "$(git rev-parse --show-toplevel)":/repo -w /repo/apps <image-from-agent-dev-loop> \
  bash -lc './gradlew --no-daemon :ui:desktopTest --tests "*GoldenSnapshotTest*" -Dsnapshot.record=true'
```
Then `git status` the `snapshots/{macos,linux}/detail-*.png` — confirm ONLY `detail-{file,link,geo,email}*` changed and eyeball one (`git diff` won't show pixels; open the PNG) to verify the only change is the primary button color (coral→teal). If any other golden changed, STOP and report.

- [ ] **Step 5: Verify the suite is green with the new goldens**

Run: `cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :ui:desktopTest`
Expected: green.

- [ ] **Step 6: Commit**

```bash
git add apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/cards/DetailScreen.kt \
        apps/ui/src/desktopTest/resources/snapshots/macos apps/ui/src/desktopTest/resources/snapshots/linux
git commit -m "feat(ui): detail-side gated shared elements + teal shared primary button (+goldens)"
```

---

### Task 5: On-device verification + CHANGELOG

**Files:**
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Build + install + drive on device**

```bash
cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :androidApp:assembleDebug
adb install -r androidApp/build/outputs/apk/debug/dayfold-android-debug.apk
```
Tap a **link/file** card → detail and burst-capture (`adb exec-out screencap -p`): the tile, kicker, title, and teal Open button should travel/resize into their detail positions (mid-frame shows them in transit, not cross-fading). Then tap a **contact** card → detail: title + kicker travel, but the tile ("CJ"→"C") and the primary button cross-fade (graceful degradation). Confirm the Open button is the same teal color in card and detail.

- [ ] **Step 2: CHANGELOG entry**

Add to `CHANGELOG.md` under a new `## 2026-07-08` heading, "Changed (client)": the card→detail transition now morphs the shared header elements (accent tile, kicker, title) and the primary Open button — which is now the same teal in both places — so they visibly travel into the detail instead of cross-fading; elements whose content differs between card and detail (e.g. a contact's initials vs the detail's type glyph, or a type without a single shared primary action) cross-fade as before.

- [ ] **Step 3: Commit**

```bash
git add CHANGELOG.md
git commit -m "docs: changelog — card→detail shared-element morph"
```

---

## Self-Review

**Spec coverage:** content-equality predicate + monogram extraction (Task 1, with unit tests per type), `cardSharedElement` helper (Task 2), card-side gated keys (Task 3), detail-side gated keys + teal button unification + golden re-record (Task 4), on-device verification + changelog (Task 5). Non-goals (description/type-media cross-fade) are satisfied by *not* keying them. All spec sections map to a task.

**Placeholders:** none — the docker image name for the linux golden re-record is the one concrete value pointed to `processes/agent-dev-loop.md:232-237` (the exact recipe already lives there and must not be duplicated/guessed).

**Type consistency:** `CardSharedKeys(tile,kicker,title,button)`, `sharedTransitionKeys(card)`, `cardMonogram`/`detailMonogram`, `cardSharedElement(key)`, key strings `tile-$id`/`kicker-$id`/`title-$id`/`action-$id`, and the `AccentTile`/`KickerChip`/`PrimaryActionPill`/`ActionsRow` signatures are consistent across Tasks 1–4.
