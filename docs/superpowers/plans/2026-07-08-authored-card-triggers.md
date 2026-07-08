# Authored Card Triggers — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make an authored `BriefingCard`'s `when`/`geo` triggers drive Now-feed banding, notification wake time, and foreground proximity surfacing.

**Architecture:** Decode card triggers into the client `Card` (new synced column + schema-version resync heal). Then, in the pure `:client` Now pipeline: fold `alert_offset` into the time anchor (no new field, no notify-path signature change), and add a foreground authored-geo lane that reuses `deriveNow`'s geo math. Background geofencing is unchanged — authored geo reaches the background only via `place_ref` to an already-geofenced saved place (ADR 0049 Option A).

**Tech Stack:** Kotlin Multiplatform, SQLDelight 2.3.2, kotlinx-datetime 0.8.0, kotlin.time.Duration, redux-kotlin. Build: JDK 17, `cd apps && ./gradlew :client:desktopTest`.

## Global Constraints

- **Reuse `BlockTrigger`/`TriggerWhen`/`TriggerGeo`** for cards — do NOT define new trigger types. Fields: `BlockTrigger.whenTrigger: TriggerWhen?` (wire `when`), `BlockTrigger.geo: TriggerGeo?`; `TriggerWhen.at: String?`, `TriggerWhen.alertOffset: String?` (wire `alert_offset`); `TriggerGeo.lat/lng: Double?`, `TriggerGeo.radiusM: Long?` (wire `radius_m`), `TriggerGeo.label: String?`, `TriggerGeo.placeRef: String?` (wire `place_ref`).
- **Serializer:** reuse `TRIGGERS_SER = ListSerializer(BlockTrigger.serializer())` (already in `ContentStore.kt:20`).
- **Offset parsing:** `kotlin.time.Duration.parseIsoString` (stdlib, all KMP targets), wrapped in `runCatching` → fail-open to raw `at`. NOT kotlinx-datetime `DateTimePeriod`.
- **Anchor rule:** soonest **future** effective `when.at` (`> now`), else `not_before`. Never anchor on a past trigger when a future one exists.
- **Geo id:** `"authored:geo:<cardId>:<index>"` — MUST differ from the time item's `"authored:<cardId>"`.
- **No changes to:** `MainActivity` geofence registration, `NowItem` fields, `planExactSchedules` signature. (ADR 0049 Option A — no content-authored background geofences.)
- **Background coord-geo gate (ADR 0049 Option A — load-bearing):** `nowFeed` serves BOTH the foreground render AND the background notify pass. A coord-only (no `place_ref`) authored geo trigger must NOT surface in the **background** (else a saved-place region-enter wake could fire it while the user is inside the coord radius — the exact posture line the operator ratified). So `authoredGeoItems` takes a `placeRefOnly` flag; the background pass sets it true (only `place_ref`-resolved geo surfaces), the foreground render leaves it false (inline coords allowed).
- **Purity:** all new logic is pure commonMain (clock + location injected). No Android types in `:client`.
- **Toolchain:** `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`; tests `cd apps && ./gradlew :client:desktopTest --tests "<pattern>"`.

---

### Task 1: Decode card triggers (model + DB column + resync heal)

**Files:**
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/Model.kt` (Card data class)
- Modify: `apps/client/src/commonMain/sqldelight/com/sloopworks/dayfold/client/db/Content.sq` (card table + `upsertCard` + `activeCards`)
- Create: `apps/client/src/commonMain/sqldelight/com/sloopworks/dayfold/client/db/migrations/11.sqm`
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/ContentStore.kt` (`upsertCard` bind, `rowToCard` decode, `CLIENT_SCHEMA_VERSION`)
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/ContentStoreTest.kt`

**Interfaces:**
- Produces: `Card.triggers: List<BlockTrigger>?` populated from the synced cache. Consumed by Tasks 3 & 4.

- [ ] **Step 1: Write the failing test** (append to `ContentStoreTest.kt`; the file's in-memory builder is `store()` — reviewer-confirmed at `ContentStoreTest.kt:16`; `applyDelta`'s `changedHubs` has NO default, so pass it)

```kotlin
@Test fun `applyDelta decodes a card's when + geo triggers`() = runBlocking {
  val store = store()   // existing helper: ContentStore.create(JdbcSqliteDriver(IN_MEMORY))
  val card = Card(
    id = "c1", kind = "action", title = "T", provenance = Provenance("claude"),
    triggers = listOf(
      BlockTrigger(whenTrigger = TriggerWhen(at = "2026-07-08T10:00:00-07:00", alertOffset = "-PT1H")),
      BlockTrigger(geo = TriggerGeo(lat = 47.76, lng = -122.66, radiusM = 800, label = "Firestone")),
    ),
  )
  store.applyDelta(changedCards = listOf(card), changedHubs = emptyList(), tombstones = emptyList(), nextCursor = "x", nowIso = "2026-07-08T00:00:00Z")
  val out = store.activeCards().single { it.id == "c1" }
  assertEquals("2026-07-08T10:00:00-07:00", out.triggers?.get(0)?.whenTrigger?.at)
  assertEquals("-PT1H", out.triggers?.get(0)?.whenTrigger?.alertOffset)
  assertEquals(800L, out.triggers?.get(1)?.geo?.radiusM)
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :client:desktopTest --tests "*ContentStoreTest"`
Expected: compile error — `Card` has no `triggers` parameter.

- [ ] **Step 3a: Add the model field** (`Model.kt`, in the `Card` data class, near `media`/`importance`)

```kotlin
  val triggers: List<BlockTrigger>? = null,   // ADR 0043/0049 — on-device trigger metadata (synced-from-server, never written)
```

- [ ] **Step 3b: Add the DB column** (`Content.sq`)

In `CREATE TABLE card (...)`, add a final column (after `importance REAL`):
```sql
  ,triggers    TEXT                        -- ADR 0043/0049: JSON [{when,geo}] list; decoded at projection. Synced-only.
```
In `upsertCard:` add `triggers` to the column list and one more `?` to `VALUES`, and `triggers=excluded.triggers` to the `DO UPDATE SET`:
```sql
INSERT INTO card(id, kind, title, body_md, source, not_before, expires_at, importance, type, payload, privacy, hub_ref, target_hub_id, target_section_id, target_block_id, related, related_kicker, media, triggers, updated_at, deleted)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
ON CONFLICT(id) DO UPDATE SET
  ... (unchanged) ...,
  media=excluded.media, triggers=excluded.triggers,
  updated_at=excluded.updated_at, deleted=0;
```
In `activeCards:` add `triggers` to the SELECT list:
```sql
SELECT id, kind, title, body_md, source, not_before, expires_at, importance, type, payload, privacy, hub_ref, target_hub_id, target_section_id, target_block_id, related, related_kicker, media, triggers
FROM card WHERE deleted=0 ORDER BY ...   -- keep the existing ORDER BY
```

- [ ] **Step 3c: Add the migration** (`migrations/11.sqm`)

```sql
-- Migration v11→v12 (issue #299): cards gain on-device trigger metadata (when/geo), mirroring
-- hub_block.triggers. Existing rows get NULL; the CLIENT_SCHEMA_VERSION 2→3 bump forces one resync
-- so the server backfills triggers into cached cards (incremental cursor can't heal a since-added field).
ALTER TABLE card ADD COLUMN triggers TEXT;
```

- [ ] **Step 3d: Wire ContentStore** (`ContentStore.kt`)

In `upsertCard(...)` call (after the `media` arg, before `nowIso`):
```kotlin
          c.media?.let { json.encodeToString(CardMedia.serializer(), it) },   // ADR 0036
          c.triggers?.let { json.encodeToString(TRIGGERS_SER, it) },          // ADR 0043/0049
          nowIso,
```
In `rowToCard(...)` (after the `media` line):
```kotlin
    media = decode(row.media, CardMedia.serializer()),   // ADR 0036
    triggers = decode(row.triggers, TRIGGERS_SER),        // ADR 0043/0049
```
Bump the constant:
```kotlin
const val CLIENT_SCHEMA_VERSION: Long = 3L   // 2→3 (#299): cards now carry decoded triggers
```

- [ ] **Step 4: Run tests to verify pass**

Run: `cd apps && JAVA_HOME=... ./gradlew :client:desktopTest --tests "*ContentStoreTest" --tests "*SchemaVersionResyncTest"`
Expected: PASS (new decode test green; existing schema-version resync test still green with the bumped constant).

- [ ] **Step 5: Commit**

```bash
git add apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/Model.kt \
  apps/client/src/commonMain/sqldelight/com/sloopworks/dayfold/client/db/Content.sq \
  apps/client/src/commonMain/sqldelight/com/sloopworks/dayfold/client/db/migrations/11.sqm \
  apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/ContentStore.kt \
  apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/ContentStoreTest.kt
git commit -m "feat(client): decode authored card triggers (when/geo) + schema-version 2→3 heal (#299)"
```

---

### Task 2: `applyOffset` helper

**Files:**
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/NowDerive.kt` (add helper near `parseInstantFlexible`)
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/DeriveNowTest.kt`

**Interfaces:**
- Produces: `internal fun applyOffset(atIso: String?, offsetIso: String?, zone: TimeZone): Instant?` — parses `atIso`, adds the ISO-8601 duration `offsetIso`; malformed/absent offset → the raw `at` instant; unparseable `at` → null. Consumed by Task 3.

- [ ] **Step 1: Write the failing test** (append to `DeriveNowTest.kt`; add `import kotlin.time.Instant` — the file doesn't import it yet)

```kotlin
@Test fun `applyOffset shifts by an ISO-8601 duration, fails open on junk`() {
  val z = TimeZone.currentSystemDefault()
  // -PT1H → an hour earlier
  assertEquals(Instant.parse("2026-07-08T09:00:00-07:00"), applyOffset("2026-07-08T10:00:00-07:00", "-PT1H", z))
  // positive offset → later
  assertEquals(Instant.parse("2026-07-08T10:30:00-07:00"), applyOffset("2026-07-08T10:00:00-07:00", "PT30M", z))
  // no offset → raw at
  assertEquals(Instant.parse("2026-07-08T10:00:00-07:00"), applyOffset("2026-07-08T10:00:00-07:00", null, z))
  // malformed offset → fail open to raw at
  assertEquals(Instant.parse("2026-07-08T10:00:00-07:00"), applyOffset("2026-07-08T10:00:00-07:00", "garbage", z))
  // unparseable at → null
  assertNull(applyOffset("not-a-date", "-PT1H", z))
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd apps && JAVA_HOME=... ./gradlew :client:desktopTest --tests "*DeriveNowTest"`
Expected: FAIL — unresolved reference `applyOffset`.

- [ ] **Step 3: Implement** (`NowDerive.kt`, add near `parseInstantFlexible`; add `import kotlin.time.Duration`)

```kotlin
// Shift an authored trigger instant by its ISO-8601 alert_offset (e.g. "-PT1H" → an hour earlier),
// used to fold the offset into the single relevance/notify anchor. Pure. A malformed or absent offset
// fails open to the raw instant; an unparseable `at` returns null. Duration.parseIsoString (stdlib) is
// the multiplatform parser; kotlinx-datetime's DateTimePeriod is the wrong (calendar) type here.
internal fun applyOffset(atIso: String?, offsetIso: String?, zone: TimeZone): Instant? {
  val at = parseInstantFlexible(atIso, zone) ?: return null
  val offset = offsetIso?.let { runCatching { kotlin.time.Duration.parseIsoString(it) }.getOrNull() } ?: return at
  return at + offset
}
```

- [ ] **Step 4: Run to verify pass**

Run: `cd apps && JAVA_HOME=... ./gradlew :client:desktopTest --tests "*DeriveNowTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/NowDerive.kt \
  apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/DeriveNowTest.kt
git commit -m "feat(client): applyOffset — fold ISO-8601 alert_offset into an instant (#299)"
```

---

### Task 3: `cardToNowItem` time anchor (when → banding + notify)

**Files:**
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/NowFeed.kt` (`cardToNowItem` signature + anchor; `nowFeed` call site; extract `subjectKeyFor`)
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/BackgroundNotify.kt` (`planExactSchedules` call site only)
- Modify: `apps/ui/src/desktopTest/kotlin/com/sloopworks/dayfold/client/NowFeedScreenTest.kt:35` — **THIRD `cardToNowItem` caller** (`cardToNowItem` is `public` because `:ui` consumes it); the signature change breaks it. Update to pass `nowIso` + `zone`.
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/NowFeedTest.kt` (already exists — append; add `import kotlin.time.Instant`), `BackgroundNotifyTest.kt` (add `import kotlin.time.Instant`)

**Interfaces:**
- Consumes: `applyOffset` (Task 2), `Card.triggers` (Task 1).
- Produces: `cardToNowItem(card: Card, config: RankConfig, nowIso: String, zone: TimeZone): NowItem` (new `nowIso`/`zone` params). `internal fun subjectKeyFor(card: Card): String` (extracted; consumed by Task 4).

- [ ] **Step 1: Write the failing test** (`NowFeedTest.kt`)

```kotlin
@Test fun `cardToNowItem anchors on the soonest future when-trigger, offset-folded`() {
  val z = TimeZone.currentSystemDefault()
  val now = "2026-07-08T08:00:00-07:00"
  val card = Card(
    id = "c1", kind = "action", title = "Firestone", provenance = Provenance("claude"),
    notBefore = "2026-07-08T09:00:00-07:00",
    triggers = listOf(
      BlockTrigger(whenTrigger = TriggerWhen(at = "2026-07-01T10:00:00-07:00")),          // past → ignored
      BlockTrigger(whenTrigger = TriggerWhen(at = "2026-07-08T10:00:00-07:00", alertOffset = "-PT1H")), // future → 09:00
    ),
  )
  val item = cardToNowItem(card, RankConfig(), now, z)
  assertEquals(Instant.parse("2026-07-08T09:00:00-07:00"), Instant.parse(item.triggerAtIso!!))  // at+offset, future one
}

@Test fun `cardToNowItem falls back to not_before when there is no future when-trigger`() {
  val z = TimeZone.currentSystemDefault()
  val card = Card(id = "c1", kind = "info", title = "X", provenance = Provenance("user"),
    notBefore = "2026-07-08T09:00:00-07:00")
  assertEquals("2026-07-08T09:00:00-07:00", cardToNowItem(card, RankConfig(), "2026-07-08T08:00:00-07:00", z).triggerAtIso)
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd apps && JAVA_HOME=... ./gradlew :client:desktopTest --tests "*NowFeedTest"`
Expected: FAIL — `cardToNowItem` takes 2 args, not 4.

- [ ] **Step 3a: Extract `subjectKeyFor` + update `cardToNowItem`** (`NowFeed.kt`)

Replace the inline `subjectKey` block in `cardToNowItem` with a call to a new helper, and add the anchor logic:
```kotlin
internal fun subjectKeyFor(card: Card): String =
  card.targetHubId?.let { hub ->
    buildString {
      append("hub:").append(hub)
      card.targetSectionId?.let { append("/sec:").append(it) }
      card.targetBlockId?.let { append("/blk:").append(it) }
    }
  } ?: "card:${card.id}"

fun cardToNowItem(card: Card, config: RankConfig, nowIso: String, zone: TimeZone): NowItem {
  val subjectKey = subjectKeyFor(card)
  val reasonKind = when {
    card.kind == "weather" -> ReasonKind.WEATHER
    card.provenance?.source == "email" -> ReasonKind.EMAIL
    card.provenance?.source == "claude" -> ReasonKind.CLAUDE
    else -> ReasonKind.EXTERNAL
  }
  // Relevance anchor: soonest FUTURE when-trigger (alert_offset folded in), else not_before.
  val now = parseInstantFlexible(nowIso, zone)
  val whenAnchor: String? = card.triggers
    ?.mapNotNull { t -> t.whenTrigger?.let { applyOffset(it.at, it.alertOffset, zone) } }
    ?.filter { now == null || it > now }
    ?.minOrNull()
    ?.toString()
  return NowItem(
    id = "authored:${card.id}",
    origin = Origin.AUTHORED,
    reasonKind = reasonKind,
    title = card.title,
    why = firstNonBlankLine(card.bodyMd) ?: card.title,
    subjectKey = subjectKey,
    target = card.targetHubId?.let { DeepLinkTarget(it, card.targetSectionId, card.targetBlockId) },
    triggerAtIso = whenAnchor ?: card.notBefore,
    weight = card.importance?.coerceIn(0.0, config.importanceCap) ?: DEFAULT_AUTHORED_WEIGHT,
    authoredSource = card.provenance?.source,
  )
}
```

- [ ] **Step 3b: Update the `nowFeed` call site** (`NowFeed.kt`)

```kotlin
  val visible = feedCards(state, nowIso).filter { notBeforeReached(it.notBefore, nowIso, zone) }
  val authored = visible.map { cardToNowItem(it, rankConfig, nowIso, zone) }
```
(Keep `visible` — Task 4 reuses it.)

- [ ] **Step 3c: Update the `planExactSchedules` call site** (`BackgroundNotify.kt:131`)

```kotlin
  val authored = snapshot.cards.map { cardToNowItem(it, rankConfig, nowIso, zone) }
```

- [ ] **Step 3d: Fix the `:ui` caller** (`apps/ui/src/desktopTest/.../NowFeedScreenTest.kt:35`)

Change the 2-arg call to 4-arg (add `import kotlinx.datetime.TimeZone` if absent):
```kotlin
val authored = RankedItem(cardToNowItem(card, RankConfig(), "2026-06-30T12:00:00Z", TimeZone.UTC), Band.SOON, 0.5)
```
(Use the same `nowIso` the surrounding test already uses if it has one; otherwise any fixed instant works — this test doesn't assert on the anchor.)

- [ ] **Step 4a: Add the notify-wake test** (`BackgroundNotifyTest.kt`)

```kotlin
@Test fun `planExactSchedules wakes at when-at plus offset, drops a past folded wake`() {
  val cfg = NotifConfig(enabled = true)
  val soon = Card(id = "c1", kind = "action", title = "Firestone", provenance = Provenance("claude"),
    triggers = listOf(BlockTrigger(whenTrigger = TriggerWhen(at = "2026-07-08T10:00:00-07:00", alertOffset = "-PT1H"))))
  val past = Card(id = "c2", kind = "action", title = "Gone", provenance = Provenance("claude"),
    triggers = listOf(BlockTrigger(whenTrigger = TriggerWhen(at = "2026-07-08T08:15:00-07:00", alertOffset = "-PT1H")))) // wake 07:15 < now
  val snap = NotifSnapshot(cards = listOf(soon, past), config = cfg)
  val out = planExactSchedules(snap, nowIso = "2026-07-08T08:00:00-07:00")
  assertEquals(1, out.size)
  assertEquals(Instant.parse("2026-07-08T09:00:00-07:00"), Instant.parse(out.single().atIso))
}
```

- [ ] **Step 4b: Run tests to verify pass**

Run: `cd apps && JAVA_HOME=... ./gradlew :client:desktopTest --tests "*NowFeedTest" --tests "*BackgroundNotifyTest"`
Expected: PASS. Then run the full `:client` AND `:ui` suites (the latter compiles `NowFeedScreenTest.kt` — the `:ui` caller fixed in Step 3d): `./gradlew :client:desktopTest :ui:desktopTest`.

- [ ] **Step 5: Commit**

```bash
git add apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/NowFeed.kt \
  apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/BackgroundNotify.kt \
  apps/ui/src/desktopTest/kotlin/com/sloopworks/dayfold/client/NowFeedScreenTest.kt \
  apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/NowFeedTest.kt \
  apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/BackgroundNotifyTest.kt
git commit -m "feat(client): authored card when-trigger drives banding + notify wake (#299)"
```

---

### Task 4: `authoredGeoItems` foreground geo lane

**Files:**
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/NowFeed.kt` (add `authoredGeoItems`; wire into `nowFeed`)
- Test: `apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/NowFeedTest.kt`

**Interfaces:**
- Consumes: `Card.triggers` (Task 1), `subjectKeyFor` (Task 3), `haversineMeters` + `DeriveConfig` (existing `NowDerive`, `internal`).
- Produces: `internal fun authoredGeoItems(cards: List<Card>, places: List<Place>, location: DeviceLocation?, config: DeriveConfig, placeRefOnly: Boolean): List<NowItem>` and a new `nowFeed` param `authoredCoordGeo: Boolean = true` (background pass passes `false`).

- [ ] **Step 1: Write the failing test** (`NowFeedTest.kt`; ensure `import kotlin.time.Instant` is present from Task 3)

```kotlin
private fun geoCard(id: String, geo: TriggerGeo, vararg extra: BlockTrigger) =
  Card(id = id, kind = "action", title = "F", provenance = Provenance("claude"),
    triggers = listOf(BlockTrigger(geo = geo)) + extra.toList())

@Test fun `authoredGeoItems emits a distinct-id NOW item inside an inline-coord radius`() {
  val card = geoCard("c1", TriggerGeo(lat = 47.7601, lng = -122.6610, radiusM = 800, label = "Firestone Poulsbo"))
  val items = authoredGeoItems(listOf(card), emptyList(), DeviceLocation(47.7605, -122.6612), DeriveConfig(), placeRefOnly = false)
  assertEquals(1, items.size)
  assertEquals("authored:geo:c1:0", items[0].id)   // distinct from the time item "authored:c1"
  assertEquals("card:c1", items[0].subjectKey)     // same subjectKey → dedups with the time item
  assertTrue(items[0].geoActive)
}

@Test fun `authoredGeoItems emits nothing outside radius, null location, or null coords`() {
  val far = geoCard("c1", TriggerGeo(lat = 47.7601, lng = -122.6610, radiusM = 100))
  assertTrue(authoredGeoItems(listOf(far), emptyList(), DeviceLocation(48.0, -122.0), DeriveConfig(), false).isEmpty())
  assertTrue(authoredGeoItems(listOf(far), emptyList(), null, DeriveConfig(), false).isEmpty())
  val noCoords = geoCard("c2", TriggerGeo(label = "nowhere"))   // no lat/lng, no place_ref
  assertTrue(authoredGeoItems(listOf(noCoords), emptyList(), DeviceLocation(47.76, -122.66), DeriveConfig(), false).isEmpty())
}

@Test fun `authoredGeoItems null radiusM falls back to the config default`() {
  val card = geoCard("c1", TriggerGeo(lat = 47.7601, lng = -122.6610))   // radiusM null → geoRadiusDefaultM (200m)
  // ~50m away → inside the 200m default
  assertEquals(1, authoredGeoItems(listOf(card), emptyList(), DeviceLocation(47.7605, -122.6610), DeriveConfig(), false).size)
}

@Test fun `authoredGeoItems resolves place_ref coordinates`() {
  val card = geoCard("c1", TriggerGeo(placeRef = "p1", label = "Safeway"))
  val place = Place(id = "p1", kind = "store", label = "Safeway", lat = 47.7601, lng = -122.6610, radiusM = 800)
  assertEquals(1, authoredGeoItems(listOf(card), listOf(place), DeviceLocation(47.7605, -122.6612), DeriveConfig(), false).size)
}

@Test fun `placeRefOnly gate (background) drops coord-only geo, keeps place_ref (ADR 0049 Option A)`() {
  val coordOnly = geoCard("c1", TriggerGeo(lat = 47.7601, lng = -122.6610, radiusM = 800))
  val viaPlace  = geoCard("c2", TriggerGeo(placeRef = "p1", radiusM = 800))
  val place = Place(id = "p1", kind = "store", label = "Safeway", lat = 47.7601, lng = -122.6610, radiusM = 800)
  val here = DeviceLocation(47.7605, -122.6612)
  // background: only the place_ref card surfaces
  val bg = authoredGeoItems(listOf(coordOnly, viaPlace), listOf(place), here, DeriveConfig(), placeRefOnly = true)
  assertEquals(listOf("authored:geo:c2:0"), bg.map { it.id })
  // foreground: both surface
  assertEquals(2, authoredGeoItems(listOf(coordOnly, viaPlace), listOf(place), here, DeriveConfig(), placeRefOnly = false).size)
}

@Test fun `a card with BOTH when and geo dedups to one NOW subject`() {
  val z = TimeZone.currentSystemDefault(); val now = "2026-07-08T08:00:00-07:00"
  val card = Card(id = "c1", kind = "action", title = "Firestone", provenance = Provenance("claude"),
    triggers = listOf(
      BlockTrigger(whenTrigger = TriggerWhen(at = "2026-07-08T10:00:00-07:00", alertOffset = "-PT1H")),
      BlockTrigger(geo = TriggerGeo(lat = 47.7601, lng = -122.6610, radiusM = 800)),
    ))
  val time = cardToNowItem(card, RankConfig(), now, z)
  val geo = authoredGeoItems(listOf(card), emptyList(), DeviceLocation(47.7605, -122.6612), DeriveConfig(), false)
  // same subjectKey → the ranker collapses them into ONE head (verified via rank() below)
  assertEquals(time.subjectKey, geo.single().subjectKey)
  val feed = rank(listOf(time) + geo, now, DeviceLocation(47.7605, -122.6612), emptyMap(), z, RankConfig())
  assertEquals(1, (feed.now + feed.soon + feed.later).count { it.item.subjectKey == "card:c1" })
}
```

(Match the real `Place` constructor param names if they differ — reviewer confirmed `Place(id,kind,label,lat,lng,radiusM)`.)

- [ ] **Step 2: Run to verify it fails**

Run: `cd apps && JAVA_HOME=... ./gradlew :client:desktopTest --tests "*NowFeedTest"`
Expected: FAIL — unresolved reference `authoredGeoItems`.

- [ ] **Step 3a: Implement `authoredGeoItems`** (`NowFeed.kt`)

```kotlin
// Foreground authored-geo lane (ADR 0049 Option A): a visible card whose geo trigger the user is
// within radius of surfaces as a NOW item, matched on-device (ADR 0014 — live location injected,
// never persisted). Reuses deriveNow's geo math (haversine, radius precedence, place_ref resolve).
// The id is distinct from the time item ("authored:<id>") but shares the subjectKey so the ranker
// dedups them. Background geofencing is unchanged — a place_ref trigger rides the existing saved-
// place geofence; a coord-only trigger surfaces in the foreground only.
internal fun authoredGeoItems(
  cards: List<Card>,
  places: List<Place>,
  location: DeviceLocation?,
  config: DeriveConfig,
  placeRefOnly: Boolean,   // background pass = true → only place_ref-resolved geo (ADR 0049 Option A)
): List<NowItem> {
  if (location == null) return emptyList()
  val placeById = places.associateBy { it.id }
  val out = ArrayList<NowItem>()
  cards.forEach { card ->
    card.triggers?.forEachIndexed { idx, t ->
      val geo = t.geo ?: return@forEachIndexed
      val place = geo.placeRef?.let { placeById[it] }
      // ADR 0049 Option A: in the background, a coord-only trigger (no resolved place_ref) never fires.
      if (placeRefOnly && place == null) return@forEachIndexed
      val lat = geo.lat ?: place?.lat ?: return@forEachIndexed
      val lng = geo.lng ?: place?.lng ?: return@forEachIndexed
      val radius = (geo.radiusM ?: place?.radiusM ?: config.geoRadiusDefaultM).toDouble()
      val dist = haversineMeters(location.lat, location.lng, lat, lng)
      if (dist <= radius) {
        val label = geo.label ?: place?.label ?: "this place"
        out += NowItem(
          id = "authored:geo:${card.id}:$idx",
          origin = Origin.AUTHORED, reasonKind = ReasonKind.GEO,
          title = card.title, why = "You're near $label",
          subjectKey = subjectKeyFor(card),
          target = card.targetHubId?.let { DeepLinkTarget(it, card.targetSectionId, card.targetBlockId) },
          triggerAtIso = null, weight = config.geoWeight,
          geoActive = true, distanceM = dist,
          authoredSource = card.provenance?.source,
        )
      }
    }
  }
  return out
}
```

- [ ] **Step 3b: Wire into `nowFeed`** (`NowFeed.kt`) — add the `authoredCoordGeo` param (default true = foreground)

Change the `nowFeed` signature to add `authoredCoordGeo: Boolean = true` (last param), then:
```kotlin
  val authoredGeo = authoredGeoItems(visible, state.nowContent.places, location, deriveConfig, placeRefOnly = !authoredCoordGeo)
  return rank(derived + authored + authoredGeo, nowIso, location, state.surfacing, zone, rankConfig)
```

- [ ] **Step 3c: Gate the background pass** (`BackgroundNotify.kt`, `planBackgroundNotifications`, the `nowFeed(...)` call ~line 59)

Pass `authoredCoordGeo = false` so a coord-only authored geo trigger cannot fire on a background wake (ADR 0049 Option A):
```kotlin
  val feed = nowFeed(state, nowIso, location, zone, deriveConfig, rankConfig, authoredCoordGeo = false)
```
(Foreground callers in `:ui` / `Selectors` keep the default `true` — no change.)

- [ ] **Step 4: Run tests to verify pass**

Run: `cd apps && JAVA_HOME=... ./gradlew :client:desktopTest --tests "*NowFeedTest"` then full `./gradlew :client:desktopTest` and `./gradlew :ui:desktopTest` (golden gate — expect no drift).
Expected: PASS; golden 0 failures.

- [ ] **Step 5: Commit**

```bash
git add apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/NowFeed.kt \
  apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/BackgroundNotify.kt \
  apps/client/src/desktopTest/kotlin/com/sloopworks/dayfold/client/NowFeedTest.kt
git commit -m "feat(client): foreground authored geo-trigger surfacing + background place_ref gate (ADR 0049 Option A, #299)"
```

---

### Task 5: CHANGELOG entry

**Files:** Modify: `CHANGELOG.md` (top, under the date header for 2026-07-08).

- [ ] **Step 1: Add the entry** (verbatim; user-visible behavior change per CLAUDE.md end-of-session routine)

```markdown
## 2026-07-08 — Authored card triggers now drive surfacing + notifications (#299)

### Added (client)
- **A Now-card authored with a `when` and/or `geo` trigger now acts on it.** Previously
  the client never even decoded a `BriefingCard`'s `triggers[]` — an authored time/location
  trigger did nothing. Now: a `when` trigger (with `alert_offset`) bands the card into
  NOW/SOON/LATER by its lead time and arms a local notification at `at + alert_offset`
  (background-notify opt-in required); a `geo` trigger surfaces the card as NOW when the
  device is within radius, matched **on-device** (ADR 0014 — position never leaves). Per
  **ADR 0049 (Option A)**, background geofencing stays user-curated — an authored geo
  trigger fires in the background only via `place_ref` to a saved place; a coord-only
  authored geo trigger surfaces in the foreground only. `not_before`/`expires_at` keep
  their meaning (visibility window). Existing devices self-heal via `CLIENT_SCHEMA_VERSION`
  2→3 (one forced resync backfills the new trigger field).
```

- [ ] **Step 2: Commit**

```bash
git add CHANGELOG.md
git commit -m "docs: changelog for authored card triggers (#299)"
```

---

## Verification (after all tasks)

- Full suites: `cd apps && ./gradlew :client:desktopTest :ui:desktopTest` — all green, golden 131/0.
- Android build: `./gradlew :androidApp:assembleDebug` compiles (no MainActivity change expected).
- On-device (Pixel), notifications enabled: re-push the Firestone card (triggers already present) → confirm it **fires a notification at the offset instant (09:00)** and sits in the NOW band on the appointment day (with `nowBandMinutes=180` the NOW band starts ~3h before the 09:00 anchor; the *notification* is the 09:00 event). If a `place_ref` geo card is authored against a saved place, confirm region-enter surfacing. Document the expected limit: a coord-only authored geo trigger does NOT background-fire (ADR 0049 Option A).

## Self-review notes

- Spec coverage: §0 decode chain → Task 1; §1 time/offset → Tasks 2+3; §2 geo foreground → Task 4; §2 background place_ref → no code (verification only). ✅
- Type consistency: `cardToNowItem(card, config, nowIso, zone)` used identically in `nowFeed` (Task 3b) and `planExactSchedules` (Task 3c); `subjectKeyFor` defined in Task 3, consumed in Task 4; `authoredGeoItems` signature identical in def (Task 4-3a) and call (Task 4-3b). ✅
- No `NowItem`/`planExactSchedules`/`MainActivity` changes — matches ADR 0049 Option A + Global Constraints. ✅
