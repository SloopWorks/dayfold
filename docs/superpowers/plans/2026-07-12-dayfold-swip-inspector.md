# Dayfold SWIP Inspector Plugin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A debug-drawer `DebugPlugin` in Dayfold that renders the live SWIP analytics event lifecycle (events, drops, batches, sends, health/mode/consent) from the Phase-1 capture engine, with mask-by-default privacy + FLAG_SECURE capture isolation.

**Architecture:** New `apps/debugdrawer-swip` KMP module (mirrors `apps/debugdrawer-redux`), wired `debugImplementation` from `apps/androidApp` only → zero release footprint. The plugin renders `RingDebugSink.entries: StateFlow<List<DebugEntry>>` from `works.sloop.swip:swip-debug`. All display logic lives in **pure functions** (unit-tested, mirroring the drawer's `filterLogs`/`letter` test pattern — the drawer has no Compose-UI test infra); the Composable is a thin shell. Host glue creates the sink once in a shared holder, injects it via `SwipPlatformDeps.copy(debugSink = sink)`, and provides a `SecureWindow` that toggles the Activity's `FLAG_SECURE`.

**Tech Stack:** Kotlin Multiplatform, Compose (material3 allowed in drawer panels), kotlinx-coroutines StateFlow, kotlinx-serialization JsonElement, `works.sloop.swip:swip-debug:0.1.0`, `works.sloop.swip:swip-core:0.1.3`.

## Global Constraints

- **Module deps:** `works.sloop.swip:swip-debug:0.1.0`, project `:debugdrawer`, `compose.runtime`/`compose.material3`/`compose.foundation`. swip-debug transitively pulls swip-core 0.1.3.
- **Release footprint = zero swip-debug bytes.** Module is `debugImplementation` only; release uses the existing `:debugdrawer-noop` path — never reference `debugdrawer-swip` from `src/main` or `src/release`.
- **`@OptIn(ExperimentalSwipDebugApi::class)`** required wherever `SwipDebugSink`/`DebugRecord`/`RingDebugSink` are touched.
- **Panels do NOT self-apply insets** — host owns them. Use bare `fillMaxSize()` (never `WindowInsets.safeDrawing`), exactly like `LogsPanel`.
- **Colors from `LocalDebugDrawerColors.current`** (`DrawerColors`): `text/muted/faint/accent/accentSoft/surface2/surface3/border/ok/warn/err`. Privacy/status render as **labeled chips** (text always visible) — never a bare colored dot.
- **Mask-all-by-default.** Prop values, `distinctId`, `sessionId` render masked (`••••`) until reveal-on-tap. v1 masks everything (no per-field `privacy_class`).
- **Install-gate:** allowlist on `BuildConfig.DEBUG` with `// TODO: gate on channel ∈ {dev,ci} once a real channel signal exists — never a != prod blocklist (beta ships to real users)`.
- **JDK 17, Kotlin toolchain per `apps/debugdrawer-redux`** (`kotlin("multiplatform")`, compose plugin, `com.android.library`, compileSdk 35 / minSdk 33, jvmToolchain 17).
- Commit trailer: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.

---

## File Structure

- `apps/debugdrawer-swip/build.gradle.kts` — module config (mirror debugdrawer-redux)
- `apps/debugdrawer-swip/src/commonMain/kotlin/com/sloopworks/dayfold/swip/inspector/SwipInspectorModel.kt` — pure display logic (category, filter, labels, detail lines, masking)
- `apps/debugdrawer-swip/src/commonMain/kotlin/com/sloopworks/dayfold/swip/inspector/SwipInspectorPlugin.kt` — the `DebugPlugin` Composable + `SecureWindow` interface
- `apps/debugdrawer-swip/src/commonTest/kotlin/com/sloopworks/dayfold/swip/inspector/SwipInspectorModelTest.kt` — pure-function tests
- `settings.gradle.kts` — add `:debugdrawer-swip` to `include(...)`
- `apps/androidApp/build.gradle.kts` — `debugImplementation(project(":debugdrawer-swip"))`
- `apps/androidApp/src/debug/kotlin/com/sloopworks/dayfold/android/SwipInspectorGlue.kt` (new) — gated sink holder + Android `SecureWindow`
- `apps/androidApp/src/debug/kotlin/com/sloopworks/dayfold/android/SwipAnalyticsGlue.kt` — inject `debugSink` into `platformDeps`
- `apps/androidApp/src/debug/kotlin/com/sloopworks/dayfold/android/DebugDrawerPlugins.kt` — register `SwipInspectorPlugin`; signature → `debugDrawerPlugins(activity: ComponentActivity)`
- `apps/androidApp/src/release/kotlin/com/sloopworks/dayfold/android/DebugDrawerPlugins.kt` — match new signature (still returns empty)
- `apps/androidApp/src/main/kotlin/com/sloopworks/dayfold/android/MainActivity.kt` — pass `this` to `debugDrawerPlugins(...)`
- `adr/0057-swip-debug-inspector-plugin.md` (new)

---

### Task 0: Verify swip-debug published + dependency resolves

**Prereq:** the SWIP `publish-kmp` workflow was dispatched for `:swip-debug:publishAllPublicationsToGitHubPackagesRepository` (run 29204893647). This task confirms the artifact is live before consuming it.

- [ ] **Step 1: Confirm the publish run succeeded**

Run: `gh run view 29204893647 --repo SloopWorks/swip --json status,conclusion -q '.status + " " + .conclusion'`
Expected: `completed success`. If still running, wait; if failed, read logs and re-dispatch.

- [ ] **Step 2: Confirm the package exists on GH Packages**

Run: `gh api "/orgs/SloopWorks/packages/maven/works.sloop.swip.swip-debug/versions" --jq '.[].name'`
Expected: includes `0.1.0` (no 404).

---

### Task 1: Module scaffold + pure display model (category, filter, labels)

**Files:**
- Create: `apps/debugdrawer-swip/build.gradle.kts`
- Create: `apps/debugdrawer-swip/src/commonMain/kotlin/com/sloopworks/dayfold/swip/inspector/SwipInspectorModel.kt`
- Create: `apps/debugdrawer-swip/src/commonTest/kotlin/com/sloopworks/dayfold/swip/inspector/SwipInspectorModelTest.kt`
- Modify: `settings.gradle.kts` (add `:debugdrawer-swip` to the `include(...)` line)

**Interfaces:**
- Produces: `enum class SwipFilter { ALL, EVENTS, DROPPED, STATE }`; `fun categoryOf(rec: DebugRecord): SwipFilter` (returns EVENTS for Enqueued/Batched/Sent/SendFailed, DROPPED for Dropped, STATE for the rest; ALL is never returned — it's the "no filter" sentinel); `fun swipFilter(entries: List<DebugEntry>, filter: SwipFilter): List<DebugEntry>` (ALL passes everything; else keeps entries whose `categoryOf(rec) == filter`); `fun rowLabel(rec: DebugRecord): String` (one-line summary).

- [ ] **Step 1: Create the module build file**

`apps/debugdrawer-swip/build.gradle.kts`:
```kotlin
// Optional, debug-only adapter: renders the SWIP Phase-1 capture engine
// (works.sloop.swip:swip-debug RingDebugSink.entries) as a SloopWorks debug-drawer
// panel. Apps add this debugImplementation only; it is never in release. Depends on
// :debugdrawer (the plugin API) — keeping the core drawer swip-agnostic.
plugins {
  kotlin("multiplatform")
  kotlin("plugin.compose")
  id("org.jetbrains.compose")
  id("com.android.library")
}

group = "com.sloopworks.dayfold.swip"
version = "0.1.0-SNAPSHOT"

kotlin {
  jvmToolchain(17)
  androidTarget()
  jvm("desktop")
  listOf(iosArm64(), iosSimulatorArm64()).forEach {
    it.binaries.framework { baseName = "debugdrawerswip"; isStatic = true }
  }

  sourceSets {
    val commonMain by getting {
      dependencies {
        implementation(project(":debugdrawer"))
        implementation("works.sloop.swip:swip-debug:0.1.0")
        implementation(compose.runtime)
        implementation(compose.foundation)
        implementation(compose.material3)
      }
    }
    val commonTest by getting {
      dependencies { implementation(kotlin("test")) }
    }
  }
}

android {
  namespace = "com.sloopworks.dayfold.swip.inspector"
  compileSdk = 35
  defaultConfig { minSdk = 33 }
}
```

- [ ] **Step 2: Register the module**

Edit `settings.gradle.kts` — add `:debugdrawer-swip` to the `include(...)` list (after `:debugdrawer-redux`):
```kotlin
include(":client", ":ui", ":androidApp", ":debugdrawer", ":debugdrawer-noop", ":debugdrawer-redux", ":debugdrawer-swip", ":swip-wiring")
```

- [ ] **Step 3: Write the failing test**

`apps/debugdrawer-swip/src/commonTest/kotlin/com/sloopworks/dayfold/swip/inspector/SwipInspectorModelTest.kt`:
```kotlin
package com.sloopworks.dayfold.swip.inspector

import works.sloop.swip.DebugRecord
import works.sloop.swip.DropReason
import works.sloop.swip.ExperimentalSwipDebugApi
import works.sloop.swip.LatencyTier
import works.sloop.swip.debug.DebugEntry
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalSwipDebugApi::class)
class SwipInspectorModelTest {

  private fun entry(seq: Long, rec: DebugRecord) = DebugEntry(seq = seq, ts = seq * 10, rec = rec)

  private val enq = DebugRecord.Enqueued(
    eventId = "e1", schema = "account_signed_in", propsRaw = emptyMap(), propsStripped = null,
    distinctId = "d1", sessionId = "s1", tier = LatencyTier.NORMAL, critical = false,
  )
  private val dropped = DebugRecord.Dropped(eventId = null, schema = "hub_opened", reason = DropReason.MODE)
  private val health = DebugRecord.HealthSnapshot(0, 1, 0, 0, 0, 0)

  @Test
  fun categoryOf_maps_record_types() {
    assertEquals(SwipFilter.EVENTS, categoryOf(enq))
    assertEquals(SwipFilter.DROPPED, categoryOf(dropped))
    assertEquals(SwipFilter.STATE, categoryOf(health))
  }

  @Test
  fun swipFilter_ALL_passes_everything() {
    val list = listOf(entry(0, enq), entry(1, dropped), entry(2, health))
    assertEquals(3, swipFilter(list, SwipFilter.ALL).size)
  }

  @Test
  fun swipFilter_narrows_to_category() {
    val list = listOf(entry(0, enq), entry(1, dropped), entry(2, health))
    assertEquals(listOf(1L), swipFilter(list, SwipFilter.DROPPED).map { it.seq })
    assertEquals(listOf(0L), swipFilter(list, SwipFilter.EVENTS).map { it.seq })
    assertEquals(listOf(2L), swipFilter(list, SwipFilter.STATE).map { it.seq })
  }

  @Test
  fun rowLabel_leads_with_schema_for_events() {
    assertEquals(true, rowLabel(enq).contains("account_signed_in"))
    assertEquals(true, rowLabel(dropped).contains("hub_opened") && rowLabel(dropped).contains("MODE"))
  }
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `./gradlew :debugdrawer-swip:desktopTest`
Expected: FAIL — unresolved reference `SwipFilter`/`categoryOf`/`swipFilter`/`rowLabel`.

- [ ] **Step 5: Write minimal implementation**

`apps/debugdrawer-swip/src/commonMain/kotlin/com/sloopworks/dayfold/swip/inspector/SwipInspectorModel.kt`:
```kotlin
package com.sloopworks.dayfold.swip.inspector

import works.sloop.swip.DebugRecord
import works.sloop.swip.ExperimentalSwipDebugApi
import works.sloop.swip.debug.DebugEntry

/** Segmented filter categories. ALL is the "no filter" sentinel — categoryOf never returns it. */
enum class SwipFilter { ALL, EVENTS, DROPPED, STATE }

@OptIn(ExperimentalSwipDebugApi::class)
fun categoryOf(rec: DebugRecord): SwipFilter = when (rec) {
  is DebugRecord.Enqueued, is DebugRecord.Batched, is DebugRecord.Sent, is DebugRecord.SendFailed -> SwipFilter.EVENTS
  is DebugRecord.Dropped -> SwipFilter.DROPPED
  else -> SwipFilter.STATE
}

@OptIn(ExperimentalSwipDebugApi::class)
fun swipFilter(entries: List<DebugEntry>, filter: SwipFilter): List<DebugEntry> =
  if (filter == SwipFilter.ALL) entries else entries.filter { categoryOf(it.rec) == filter }

/** One-line row summary. Events lead with schema; state lines are compact + id-free. */
@OptIn(ExperimentalSwipDebugApi::class)
fun rowLabel(rec: DebugRecord): String = when (rec) {
  is DebugRecord.Enqueued -> rec.schema
  is DebugRecord.Dropped -> "${rec.schema} · ${rec.reason}"
  is DebugRecord.Batched -> "batch ${rec.batchId} · ${rec.eventIds.size} events"
  is DebugRecord.Sent -> "sent ${rec.batchId} · ${rec.status} · ${rec.count}"
  is DebugRecord.SendFailed -> "send failed ${rec.batchId} · attempt ${rec.attempt}"
  is DebugRecord.Purged -> "purged · ${rec.reason}"
  is DebugRecord.HealthSnapshot -> "health · queued ${rec.queued}"
  is DebugRecord.ModeChanged -> "mode ${rec.from} → ${rec.to}"
  is DebugRecord.ConsentChanged -> "consent changed"
  is DebugRecord.IdentityChanged -> "identity · ${rec.kind}"
  is DebugRecord.SessionRotated -> "session rotated · ${rec.reason}"
  is DebugRecord.FlushInvoked -> "flush" + if (rec.manual) " (manual)" else ""
  is DebugRecord.ChannelInfo -> "channel ${rec.channel} · ${rec.transportKind}"
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew :debugdrawer-swip:desktopTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add apps/debugdrawer-swip/build.gradle.kts settings.gradle.kts \
  apps/debugdrawer-swip/src/commonMain/kotlin/com/sloopworks/dayfold/swip/inspector/SwipInspectorModel.kt \
  apps/debugdrawer-swip/src/commonTest/kotlin/com/sloopworks/dayfold/swip/inspector/SwipInspectorModelTest.kt
git commit -m "feat(swip-inspector): module scaffold + pure display model (filter/category/label)"
```

---

### Task 2: Detail lines + masking (pure)

**Files:**
- Modify: `apps/debugdrawer-swip/src/commonMain/kotlin/com/sloopworks/dayfold/swip/inspector/SwipInspectorModel.kt`
- Modify: `apps/debugdrawer-swip/src/commonTest/kotlin/com/sloopworks/dayfold/swip/inspector/SwipInspectorModelTest.kt`

**Interfaces:**
- Consumes: `SwipFilter`, `categoryOf`, `rowLabel` from Task 1.
- Produces: `const val MASK = "••••"`; `data class DetailLine(val label: String, val value: String, val sensitive: Boolean)`; `fun detailLines(rec: DebugRecord): List<DetailLine>` (props + ids marked `sensitive=true`, schema/tier/status marked `sensitive=false`); `fun renderValue(line: DetailLine, revealed: Boolean): String` (returns `MASK` when `sensitive && !revealed`, else `line.value`); `fun copyText(rec: DebugRecord, revealed: Boolean): String` (joins detailLines through renderValue — copy honors the current mask state so a masked copy can't leak PII).

- [ ] **Step 1: Write the failing test** (append to `SwipInspectorModelTest.kt`)

```kotlin
  @Test
  fun detailLines_mark_props_and_ids_sensitive() {
    val lines = detailLines(enq)
    val distinct = lines.first { it.label == "distinctId" }
    val schema = lines.first { it.label == "schema" }
    assertEquals(true, distinct.sensitive)
    assertEquals(false, schema.sensitive)
  }

  @Test
  fun renderValue_masks_sensitive_until_revealed() {
    val line = DetailLine(label = "distinctId", value = "d1", sensitive = true)
    assertEquals(MASK, renderValue(line, revealed = false))
    assertEquals("d1", renderValue(line, revealed = true))
    val plain = DetailLine(label = "schema", value = "account_signed_in", sensitive = false)
    assertEquals("account_signed_in", renderValue(plain, revealed = false))
  }

  @Test
  fun copyText_masks_when_not_revealed() {
    assertEquals(false, copyText(enq, revealed = false).contains("d1"))
    assertEquals(true, copyText(enq, revealed = true).contains("d1"))
    assertEquals(true, copyText(enq, revealed = false).contains("account_signed_in")) // schema not sensitive
  }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :debugdrawer-swip:desktopTest`
Expected: FAIL — unresolved `MASK`/`DetailLine`/`detailLines`/`renderValue`/`copyText`.

- [ ] **Step 3: Write minimal implementation** (append to `SwipInspectorModel.kt`; add `import kotlinx.serialization.json.JsonElement` is unnecessary — values are stringified)

```kotlin
const val MASK = "••••"

/** A detail row. `sensitive` values are masked until the user reveals them (FLAG_SECURE gate). */
data class DetailLine(val label: String, val value: String, val sensitive: Boolean)

@OptIn(ExperimentalSwipDebugApi::class)
fun detailLines(rec: DebugRecord): List<DetailLine> = buildList {
  when (rec) {
    is DebugRecord.Enqueued -> {
      add(DetailLine("schema", rec.schema, sensitive = false))
      add(DetailLine("tier", rec.tier.name, sensitive = false))
      add(DetailLine("critical", rec.critical.toString(), sensitive = false))
      add(DetailLine("eventId", rec.eventId, sensitive = true))
      add(DetailLine("distinctId", rec.distinctId ?: "null", sensitive = true))
      add(DetailLine("sessionId", rec.sessionId ?: "null", sensitive = true))
      val props = rec.propsStripped ?: rec.propsRaw
      props.forEach { (k, v) -> add(DetailLine("prop.$k", v.toString(), sensitive = true)) }
    }
    is DebugRecord.Dropped -> {
      add(DetailLine("schema", rec.schema, sensitive = false))
      add(DetailLine("reason", rec.reason.name, sensitive = false))
      add(DetailLine("eventId", rec.eventId ?: "null", sensitive = true))
    }
    is DebugRecord.Batched -> {
      add(DetailLine("batchId", rec.batchId, sensitive = false))
      add(DetailLine("eventIds", rec.eventIds.joinToString(), sensitive = true))
    }
    is DebugRecord.Sent -> {
      add(DetailLine("batchId", rec.batchId, sensitive = false))
      add(DetailLine("status", rec.status, sensitive = false))
      add(DetailLine("count", rec.count.toString(), sensitive = false))
    }
    is DebugRecord.SendFailed -> {
      add(DetailLine("batchId", rec.batchId, sensitive = false))
      add(DetailLine("attempt", rec.attempt.toString(), sensitive = false))
      add(DetailLine("willRetry", rec.willRetry.toString(), sensitive = false))
    }
    is DebugRecord.HealthSnapshot -> {
      add(DetailLine("queued", rec.queued.toString(), sensitive = false))
      add(DetailLine("dropsConsentDenied", rec.dropsConsentDenied.toString(), sensitive = false))
      add(DetailLine("dropsOverflow", rec.dropsOverflow.toString(), sensitive = false))
      add(DetailLine("dropsDeadLetter", rec.dropsDeadLetter.toString(), sensitive = false))
      add(DetailLine("flushFailures", rec.flushFailures.toString(), sensitive = false))
      add(DetailLine("storageErrors", rec.storageErrors.toString(), sensitive = false))
    }
    is DebugRecord.ModeChanged -> {
      add(DetailLine("from", rec.from.name, sensitive = false))
      add(DetailLine("to", rec.to.name, sensitive = false))
      add(DetailLine("purged", rec.purged.toString(), sensitive = false))
    }
    is DebugRecord.ConsentChanged -> rec.consent.forEach { (k, v) -> add(DetailLine(k.toString(), v.toString(), sensitive = false)) }
    is DebugRecord.IdentityChanged -> add(DetailLine("kind", rec.kind, sensitive = false))
    is DebugRecord.SessionRotated -> add(DetailLine("reason", rec.reason, sensitive = false))
    is DebugRecord.FlushInvoked -> add(DetailLine("manual", rec.manual.toString(), sensitive = false))
    is DebugRecord.Purged -> add(DetailLine("reason", rec.reason, sensitive = false))
    is DebugRecord.ChannelInfo -> {
      add(DetailLine("channel", rec.channel, sensitive = false))
      add(DetailLine("internal", rec.internal.toString(), sensitive = false))
      add(DetailLine("transportKind", rec.transportKind, sensitive = false))
    }
  }
}

fun renderValue(line: DetailLine, revealed: Boolean): String =
  if (line.sensitive && !revealed) MASK else line.value

@OptIn(ExperimentalSwipDebugApi::class)
fun copyText(rec: DebugRecord, revealed: Boolean): String =
  detailLines(rec).joinToString("\n") { "${it.label}: ${renderValue(it, revealed)}" }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :debugdrawer-swip:desktopTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/debugdrawer-swip/src/commonMain/kotlin/com/sloopworks/dayfold/swip/inspector/SwipInspectorModel.kt \
  apps/debugdrawer-swip/src/commonTest/kotlin/com/sloopworks/dayfold/swip/inspector/SwipInspectorModelTest.kt
git commit -m "feat(swip-inspector): detail lines + mask-by-default render/copy (pure)"
```

---

### Task 3: The plugin Composable + SecureWindow seam

**Files:**
- Create: `apps/debugdrawer-swip/src/commonMain/kotlin/com/sloopworks/dayfold/swip/inspector/SwipInspectorPlugin.kt`

**Interfaces:**
- Consumes: all of `SwipInspectorModel` (Task 1–2); `com.sloopworks.debugdrawer.DebugPlugin`/`DebugScope`; `com.sloopworks.debugdrawer.theme.LocalDebugDrawerColors`/`DrawerColors`; `works.sloop.swip.debug.DebugEntry`; `kotlinx.coroutines.flow.StateFlow`.
- Produces: `interface SecureWindow { fun set(); fun clear() }`; `object NoOpSecureWindow : SecureWindow`; `class SwipInspectorPlugin(entries: StateFlow<List<DebugEntry>>, secure: SecureWindow = NoOpSecureWindow) : DebugPlugin` (`id="swip"`, `title="SWIP"`).

This task has no new pure logic (all logic is tested in Tasks 1–2); it is the thin Compose shell, verified by compilation + the on-device check in Task 6. It mirrors `LogsPanel.kt` structurally.

- [ ] **Step 1: Write the plugin**

`apps/debugdrawer-swip/src/commonMain/kotlin/com/sloopworks/dayfold/swip/inspector/SwipInspectorPlugin.kt`:
```kotlin
package com.sloopworks.dayfold.swip.inspector

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sloopworks.debugdrawer.DebugPlugin
import com.sloopworks.debugdrawer.DebugScope
import com.sloopworks.debugdrawer.theme.DrawerColors
import com.sloopworks.debugdrawer.theme.LocalDebugDrawerColors
import kotlinx.coroutines.flow.StateFlow
import works.sloop.swip.DebugRecord
import works.sloop.swip.ExperimentalSwipDebugApi
import works.sloop.swip.debug.DebugEntry

/** Host-provided window-security control: set while unmasked PII is on screen. */
interface SecureWindow { fun set(); fun clear() }
object NoOpSecureWindow : SecureWindow { override fun set() {}; override fun clear() {} }

/**
 * SWIP capture inspector — "LogsPanel over RingDebugSink.entries". Flat live timeline,
 * type/dropped filter, tap→detail with mask-by-default reveal. While a detail's values are
 * revealed the host window is FLAG_SECURE'd (via [secure]) so raw PII can't land in a
 * screenshot / dogfood bug bundle (the bug reporter captures via PixelCopy, which honors it).
 */
@OptIn(ExperimentalSwipDebugApi::class)
class SwipInspectorPlugin(
  private val entries: StateFlow<List<DebugEntry>>,
  private val secure: SecureWindow = NoOpSecureWindow,
) : DebugPlugin {
  override val id: String = "swip"
  override val title: String = "SWIP"

  @Composable
  override fun Content(scope: DebugScope) {
    val colors = LocalDebugDrawerColors.current
    val list by entries.collectAsState()
    var filter by remember { mutableStateOf(SwipFilter.ALL) }
    var detail by remember { mutableStateOf<DebugEntry?>(null) }

    Column(Modifier.fillMaxSize()) {
      FilterBar(filter, colors) { filter = it }
      val shown = remember(list, filter) { swipFilter(list, filter).asReversed() } // newest first
      if (shown.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
          Text("No SWIP records.", color = colors.muted, fontSize = 14.sp)
        }
      } else {
        LazyColumn(Modifier.fillMaxSize()) {
          items(shown, key = { it.seq }) { e -> SwipRow(e, colors) { detail = e } }
        }
      }
    }

    detail?.let { e -> DetailDialog(e, colors, scope, secure) { detail = null } }
  }
}

@Composable
private fun FilterBar(selected: SwipFilter, colors: DrawerColors, onSelect: (SwipFilter) -> Unit) {
  Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    SwipFilter.entries.forEach { f ->
      Chip(f.name, active = selected == f, accent = colors.accent, colors = colors) { onSelect(f) }
    }
  }
}

@Composable
private fun Chip(label: String, active: Boolean, accent: androidx.compose.ui.graphics.Color, colors: DrawerColors, onClick: () -> Unit) {
  Box(
    Modifier.clip(RoundedCornerShape(6.dp))
      .background(if (active) colors.accentSoft else colors.surface2)
      .border(1.dp, if (active) accent else colors.border, RoundedCornerShape(6.dp))
      .clickable(onClickLabel = "Filter $label") { onClick() }
      .padding(horizontal = 10.dp, vertical = 5.dp),
  ) {
    Text(label, color = if (active) accent else colors.muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Medium)
  }
}

@OptIn(ExperimentalSwipDebugApi::class)
@Composable
private fun SwipRow(entry: DebugEntry, colors: DrawerColors, onClick: () -> Unit) {
  val cat = categoryOf(entry.rec)
  val badgeColor = when (cat) { SwipFilter.DROPPED -> colors.err; SwipFilter.EVENTS -> colors.accent; else -> colors.muted }
  Row(
    Modifier.fillMaxWidth().clickable(onClickLabel = "Open SWIP record") { onClick() }.padding(horizontal = 12.dp, vertical = 6.dp),
    verticalAlignment = Alignment.Top,
  ) {
    Box(Modifier.clip(RoundedCornerShape(4.dp)).background(colors.surface2).padding(horizontal = 5.dp, vertical = 1.dp)) {
      Text(cat.name.take(3), color = badgeColor, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
    Text("  ${rowLabel(entry.rec)}", color = colors.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
  }
}

@OptIn(ExperimentalSwipDebugApi::class)
@Composable
private fun DetailDialog(entry: DebugEntry, colors: DrawerColors, scope: DebugScope, secure: SecureWindow, onDismiss: () -> Unit) {
  var revealed by remember(entry.seq) { mutableStateOf(false) }
  val lines = remember(entry.seq) { detailLines(entry.rec) }
  val hasSensitive = remember(entry.seq) { lines.any { it.sensitive } }

  // Secure the window whenever sensitive values are on screen unmasked; always clear on dispose.
  DisposableEffect(revealed) {
    if (revealed && hasSensitive) secure.set() else secure.clear()
    onDispose { secure.clear() }
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(rowLabel(entry.rec), fontFamily = FontFamily.Monospace, fontSize = 14.sp) },
    text = {
      Column(Modifier.verticalScroll(rememberScrollState())) {
        lines.forEach { line ->
          Text(
            "${line.label}: ${renderValue(line, revealed)}",
            color = if (line.sensitive) colors.warn else colors.text,
            fontFamily = FontFamily.Monospace, fontSize = 13.sp,
          )
        }
      }
    },
    confirmButton = {
      TextButton(onClick = { scope.copy(copyText(entry.rec, revealed)) }) { Text("Copy") }
    },
    dismissButton = {
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (hasSensitive) TextButton(onClick = { revealed = !revealed }) { Text(if (revealed) "Mask" else "Reveal") }
        TextButton(onClick = onDismiss) { Text("Close") }
      }
    },
  )
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :debugdrawer-swip:compileKotlinDesktop :debugdrawer-swip:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add apps/debugdrawer-swip/src/commonMain/kotlin/com/sloopworks/dayfold/swip/inspector/SwipInspectorPlugin.kt
git commit -m "feat(swip-inspector): DebugPlugin Compose shell + SecureWindow reveal gate"
```

---

### Task 4: Host wiring — gated sink + Android SecureWindow + registration

**Files:**
- Modify: `apps/androidApp/build.gradle.kts` (add `debugImplementation(project(":debugdrawer-swip"))` beside line 112)
- Create: `apps/androidApp/src/debug/kotlin/com/sloopworks/dayfold/android/SwipInspectorGlue.kt`
- Modify: `apps/androidApp/src/debug/kotlin/com/sloopworks/dayfold/android/SwipAnalyticsGlue.kt`
- Modify: `apps/androidApp/src/debug/kotlin/com/sloopworks/dayfold/android/DebugDrawerPlugins.kt`
- Modify: `apps/androidApp/src/release/kotlin/com/sloopworks/dayfold/android/DebugDrawerPlugins.kt`
- Modify: `apps/androidApp/src/main/kotlin/com/sloopworks/dayfold/android/MainActivity.kt:144`

**Interfaces:**
- Consumes: `SwipInspectorPlugin`, `SecureWindow` (Task 3); `RingDebugSink`/`DebugEntry` (swip-debug); `SwipAnalyticsHolder` (existing).
- Produces: `object SwipInspectorGlue { fun debugSink(): RingDebugSink?; fun secureWindow(activity: ComponentActivity): SecureWindow }`. `debugSink()` builds the gated `RingDebugSink` once (idempotent) and stores it in `SwipAnalyticsHolder`; returns null when the gate is closed.

- [ ] **Step 1: Add the module dependency**

Edit `apps/androidApp/build.gradle.kts` — after `debugImplementation(project(":debugdrawer-redux"))` (line 112):
```kotlin
  debugImplementation(project(":debugdrawer-swip"))
```

- [ ] **Step 2: Add the sink field to the holder + create the glue**

Edit `SwipAnalyticsGlue.kt` — add to `SwipAnalyticsHolder`:
```kotlin
  var debugSink: works.sloop.swip.debug.RingDebugSink? = null
```

Create `apps/androidApp/src/debug/kotlin/com/sloopworks/dayfold/android/SwipInspectorGlue.kt`:
```kotlin
package com.sloopworks.dayfold.android

import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import com.sloopworks.dayfold.swip.inspector.SecureWindow
import works.sloop.swip.ExperimentalSwipDebugApi
import works.sloop.swip.debug.RingDebugSink

/**
 * Debug-only SWIP inspector host glue. Builds the RingDebugSink behind an explicit
 * install-gate and exposes a FLAG_SECURE-toggling SecureWindow. Lives in src/debug only —
 * release never references debugdrawer-swip (zero swip-debug bytes in the public APK).
 */
@OptIn(ExperimentalSwipDebugApi::class)
object SwipInspectorGlue {

  // Install-gate (allowlist). src/debug already implies a debug build; this is the explicit,
  // documented seam for a real channel signal.
  // TODO: gate on channel ∈ {dev,ci} once a real channel signal exists — never a != prod
  // blocklist (beta ships to real users).
  private fun gateOpen(): Boolean = BuildConfig.DEBUG

  /** The gated capture sink, created once and shared with Swip.init. Null when gate closed. */
  fun debugSink(): RingDebugSink? {
    if (!gateOpen()) return null
    SwipAnalyticsHolder.debugSink?.let { return it }
    return RingDebugSink(
      scope = SwipAnalyticsHolder.scope,
      nowMs = { System.currentTimeMillis() },
    ).also { SwipAnalyticsHolder.debugSink = it }
  }

  fun secureWindow(activity: ComponentActivity): SecureWindow = object : SecureWindow {
    override fun set() = activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    override fun clear() = activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
  }
}
```

- [ ] **Step 3: Inject the sink into platformDeps**

Edit `SwipAnalyticsGlue.kt` `swipInit` — wrap the `platformDeps(...)` call with `.copy(debugSink = SwipInspectorGlue.debugSink())`:
```kotlin
    DayfoldSwip.platformDeps(
      transport = PostHogTransport(BuildConfig.POSTHOG_PROJECT_KEY, BuildConfig.POSTHOG_HOST, HttpUrlConnectionPoster()),
      storage = storage,
      appVersion = BuildConfig.VERSION_NAME,
      os = "android",
      nowMs = { System.currentTimeMillis() },
      monotonicNowMs = { SystemClock.elapsedRealtime() },
      random = { Random.nextDouble() },
      initialMode = CollectionMode.FULL,
    ).copy(debugSink = SwipInspectorGlue.debugSink()),
```
Add `@file:OptIn(works.sloop.swip.ExperimentalSwipDebugApi::class)` at the top of `SwipAnalyticsGlue.kt` (the `debugSink` param is experimental).

- [ ] **Step 4: Register the plugin (debug variant)**

Edit `apps/androidApp/src/debug/.../DebugDrawerPlugins.kt` — change signature to accept the Activity and append the inspector when the sink exists:
```kotlin
package com.sloopworks.dayfold.android

import androidx.activity.ComponentActivity
import com.sloopworks.debugdrawer.DebugPlugin
import com.sloopworks.debugdrawer.redux.ReduxDevToolsDebugPlugin
import com.sloopworks.dayfold.swip.inspector.SwipInspectorPlugin

// Debug variant only: redux DevTools + (when the gated capture sink is installed) the SWIP
// inspector. Both are debug-only modules wired debugImplementation → release never references them.
fun debugDrawerPlugins(activity: ComponentActivity): List<DebugPlugin> = buildList {
  add(ReduxDevToolsDebugPlugin())
  SwipInspectorGlue.debugSink()?.let { sink ->
    add(SwipInspectorPlugin(sink.entries, SwipInspectorGlue.secureWindow(activity)))
  }
}
```

- [ ] **Step 5: Match the release signature**

Edit `apps/androidApp/src/release/.../DebugDrawerPlugins.kt` — accept (and ignore) the Activity, still return empty:
```kotlin
package com.sloopworks.dayfold.android

import androidx.activity.ComponentActivity
import com.sloopworks.debugdrawer.DebugPlugin

// Release variant: no extra plugins (DebugDrawer is the no-op facade in release).
fun debugDrawerPlugins(activity: ComponentActivity): List<DebugPlugin> = emptyList()
```

- [ ] **Step 6: Pass the Activity from MainActivity**

Edit `MainActivity.kt:144` — `plugins = debugDrawerPlugins()` → `plugins = debugDrawerPlugins(this)`.

- [ ] **Step 7: Verify debug + release both compile**

Run: `./gradlew :androidApp:compileDebugKotlin :androidApp:compileReleaseKotlin`
Expected: BUILD SUCCESSFUL (release proves the swip-debug dep didn't leak into `src/main`/`src/release`).

- [ ] **Step 8: Commit**

```bash
git add apps/androidApp/build.gradle.kts \
  apps/androidApp/src/debug/kotlin/com/sloopworks/dayfold/android/SwipInspectorGlue.kt \
  apps/androidApp/src/debug/kotlin/com/sloopworks/dayfold/android/SwipAnalyticsGlue.kt \
  apps/androidApp/src/debug/kotlin/com/sloopworks/dayfold/android/DebugDrawerPlugins.kt \
  apps/androidApp/src/release/kotlin/com/sloopworks/dayfold/android/DebugDrawerPlugins.kt \
  apps/androidApp/src/main/kotlin/com/sloopworks/dayfold/android/MainActivity.kt
git commit -m "feat(swip-inspector): host wiring — gated RingDebugSink + FLAG_SECURE window + registration"
```

---

### Task 5: Full gate + on-device smoke + governance + PR

**Files:**
- Create: `adr/0057-swip-debug-inspector-plugin.md`
- Modify: `CHANGELOG.md`, `backlog/now.md`
- Modify: memory (`.claude/.../memory/`) + SWIP `swip-project-state.md`

- [ ] **Step 1: Full build + lint + test gate**

Run: `./gradlew :debugdrawer-swip:desktopTest :androidApp:compileDebugKotlin :androidApp:compileReleaseKotlin ktlintCheck`
Expected: all green. (Match the repo's actual lint task name per `processes/agent-dev-loop.md` if `ktlintCheck` differs.)

- [ ] **Step 2: On-device smoke (mandatory — goldens/unit tests cannot catch these)**

Install the debug APK on a device/emulator (`./gradlew :androidApp:installDebug`). Verify, per `processes/agent-dev-loop.md` (text action log + snapshot):
1. Open the debug drawer → **SWIP** panel appears; fire a mapped action (e.g. open a hub) → an `Enqueued` row appears newest-first under **Events**.
2. Filter chips narrow the list (Dropped/State/Events/All).
3. Tap a row → detail dialog; values show `••••`; **Reveal** unmasks; **Copy** while masked yields masked text.
4. Panel renders correctly under host chrome insets (no double inset / clipped top).
5. With a value **revealed**, take a screenshot → it is **blanked** by FLAG_SECURE. Re-mask/close → screenshots work again. Confirm a bug-report capture (shake) taken with a value revealed is likewise blanked.

Record results (pass/fail per item) in the PR description.

- [ ] **Step 3: Write ADR 0057**

Create `adr/0057-swip-debug-inspector-plugin.md` — Status Proposed 2026-07-12; decision: debug-only `apps/debugdrawer-swip` plugin over `swip-debug` RingDebugSink; install-gate = BuildConfig.DEBUG (TODO channel); mask-all-by-default + reveal; FLAG_SECURE-on-reveal composes with the bug reporter's PixelCopy capture; flat timeline v1, per-event folding / per-privacy_class chips deferred. Composes with ADR 0054/0055. Add to `adr/decisions-index.md`.

- [ ] **Step 4: Update CHANGELOG + backlog**

Add a dated `CHANGELOG.md` entry (user-visible dev tooling) and update `backlog/now.md`.

- [ ] **Step 5: Commit governance**

```bash
git add adr/0057-swip-debug-inspector-plugin.md adr/decisions-index.md CHANGELOG.md backlog/now.md
git commit -m "docs(swip-inspector): ADR 0057 + changelog + backlog"
```

- [ ] **Step 6: Push + open PR + poll CI**

```bash
git push -u origin feat/swip-inspector-plugin
gh pr create --fill --base main
```
Poll CI by SHA + workflow (per the CI-verification memory — never trust "latest run"). Merge on the operator's say-so. Update Dayfold memory + SWIP `swip-project-state.md` (Dayfold-integration line) after merge.

---

## Self-Review

**Spec coverage:** module + plugin skeleton (T1,T3) ✓ · filter (T1) ✓ · tap-detail + copy (T2,T3) ✓ · mask-by-default + reveal (T2,T3) ✓ · install-gate + register (T4) ✓ · FLAG_SECURE/capture-exclusion (T3 seam + T4 impl + T5 device check) ✓ · tests mirror LogsPanel via pure functions (T1,T2) ✓ · on-device check (T5) ✓ · ADR + governance (T5) ✓ · publish prereq (T0) ✓. Labeled chips: SwipRow uses a labeled 3-letter category badge (text always visible), satisfying "never a bare colored dot."

**Deferred (not in plan, per spec):** per-event journey folding; per-privacy_class chips; free-text search; iOS/desktop parity; per-OS goldens (drawer has no Compose-UI/golden panel harness — pure-function tests are the mirror-equivalent).

**Type consistency:** `SwipFilter`/`categoryOf`/`swipFilter`/`rowLabel`/`DetailLine`/`detailLines`/`renderValue`/`copyText`/`MASK`/`SecureWindow`/`SwipInspectorPlugin`/`SwipInspectorGlue.debugSink()` used consistently across tasks. `debugDrawerPlugins(activity)` signature changed in both debug + release variants + call site.
