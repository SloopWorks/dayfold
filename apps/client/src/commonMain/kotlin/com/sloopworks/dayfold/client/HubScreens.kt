package com.sloopworks.dayfold.client

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

// ── Hubs surface (ADR 0006 render · ADR 0030 visibility) ─────────────────────
// f(state)→UI. Rich per-type block rendering (checklist boxes, budget bars, maps)
// is a follow-up; this renders the structure, the typed-block content, and the
// per-member visibility treatment (the gated ADR 0030 delta). M3 semantic colors
// resolve to the Dayfold warm palette via DayfoldTheme.

@Composable
fun DayfoldBottomNav(hubsActive: Boolean, onNow: () -> Unit, onHubs: () -> Unit) {
  NavigationBar {
    NavigationBarItem(
      selected = !hubsActive, onClick = onNow,
      icon = { Text("◴") },                                  // text glyph (no material-icons dep)
      label = { Text("Now") },
    )
    NavigationBarItem(
      selected = hubsActive, onClick = onHubs,
      icon = { Text("▦") },
      label = { Text("Hubs") },
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubListScreen(
  state: AppState,
  onOpenHub: (String) -> Unit = {},
  onNow: () -> Unit = {},
  onFilter: (String) -> Unit = {},
) {
  val shown = state.hubs.filter { when (state.hubFilter) { "active" -> it.status == "active"; "planning" -> it.status == "planning"; else -> true } }
  Scaffold(
    topBar = { TopAppBar(title = { Text("Hubs", fontWeight = FontWeight.SemiBold) }) },
    bottomBar = { DayfoldBottomNav(hubsActive = true, onNow = onNow, onHubs = {}) },
  ) { pad ->
    Column(Modifier.fillMaxSize().padding(pad)) {
      if (state.hubs.isNotEmpty()) {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FilterPill("All", state.hubFilter == "all") { onFilter("all") }
          FilterPill("Active", state.hubFilter == "active") { onFilter("active") }
          FilterPill("Planning", state.hubFilter == "planning") { onFilter("planning") }
        }
      }
      when {
        state.hubs.isEmpty() && state.hubsBusy ->
          Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Loading hubs…") }
        state.hubs.isEmpty() ->
          Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text(
              "When a big family event shows up — a trip, a move, a birthday — a hub appears here.",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(40.dp),
            )
          }
        shown.isEmpty() ->
          Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text("No ${state.hubFilter} hubs.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        else -> LazyColumn(
          Modifier.fillMaxSize(),
          contentPadding = PaddingValues(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          items(shown, key = { it.id }) { hub -> HubRow(hub, onClick = { onOpenHub(hub.id) }) }
        }
      }
    }
  }
}

@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
  Surface(
    color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
    shape = RoundedCornerShape(9.dp),
    modifier = Modifier.clickable(onClick = onClick)
      .then(if (selected) Modifier else Modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(9.dp))),
  ) {
    Text(
      label,
      style = MaterialTheme.typography.labelLarge,
      fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
      color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
    )
  }
}

@Composable
private fun HubRow(hub: Hub, onClick: () -> Unit) {
  val count = countdownLabel(hub.countdownTo ?: hub.startAt, kotlin.time.Clock.System.now().toString())
  Card(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    shape = RoundedCornerShape(24.dp),
  ) {
    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(hub.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
          if (hub.visibility == "restricted") {
            // calm restricted marker — warm-neutral, never error-red
            Text("🔒", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 7.dp))
          }
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
          StatusChip(hub.status)
          Text(
            if (hub.visibility == "restricted") "Private" else (hub.type ?: ""),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
          )
        }
      }
      // right-aligned countdown (the "when" — core to event hubs)
      if (count != null && hub.status != "archived") {
        Text(count, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 10.dp))
      }
    }
  }
}

@Composable
private fun StatusChip(status: String) {
  val (bg, fg) = when (status) {
    "active" -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    "planning" -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
  }
  Surface(color = bg, shape = RoundedCornerShape(7.dp)) {
    Text(status.uppercase(), style = MaterialTheme.typography.labelSmall, color = fg, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubDetailScreen(
  state: AppState,
  onBack: () -> Unit = {},
  onNow: () -> Unit = {},
  onOpenAudience: () -> Unit = {},
) {
  val tree = state.currentHubTree
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(tree?.hub?.title ?: "Hub", fontWeight = FontWeight.SemiBold) },
        navigationIcon = { IconButton(onClick = onBack) { Text("←", style = MaterialTheme.typography.titleLarge) } },
      )
    },
    bottomBar = { DayfoldBottomNav(hubsActive = true, onNow = onNow, onHubs = {}) },
  ) { pad ->
    when {
      tree == null && state.hubsBusy -> Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) { Text("Loading…") }
      tree == null -> Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) {
        Text(state.hubError ?: "Hub unavailable", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(40.dp))
      }
      else -> {
      val listState = rememberLazyListState()
      // deep-link arrival: scroll the focused block into view. hasCountdown/restricted
      // must mirror the header items emitted below (the helper counts them).
      val hasCountdown = countdownLabel(tree.hub.countdownTo ?: tree.hub.startAt, kotlin.time.Clock.System.now().toString()) != null && tree.hub.status != "archived"
      LaunchedEffect(state.hubFocusBlockId, tree) {
        focusedBlockItemIndex(tree, state.hubFocusBlockId, hasCountdown, tree.hub.visibility == "restricted")
          ?.let { listState.animateScrollToItem(it) }
      }
      LazyColumn(
        Modifier.fillMaxSize().padding(pad),
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        item {
          Row(verticalAlignment = Alignment.CenterVertically) {
            StatusChip(tree.hub.status)
            if (tree.hub.visibility == "restricted") {
              // tappable → "Who can see this hub" sheet
              Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(999.dp),
                modifier = Modifier.padding(start = 8.dp).clickable(onClick = onOpenAudience),
              ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                  Text("🔒", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                  Text("Private", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(start = 6.dp))
                  Text("⌄", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 5.dp))
                }
              }
            }
          }
        }
        countdownLabel(tree.hub.countdownTo ?: tree.hub.startAt, kotlin.time.Clock.System.now().toString())?.let { c ->
          if (tree.hub.status != "archived") item {
            Text("📅  $c", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
          }
        }
        if (tree.hub.visibility == "restricted") {
          item {
            Text(
              "Only people you choose can open this hub.",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        // sections (ordered) each followed by their blocks (grouped by section_id).
        tree.sections.sortedBy { it.ord }.forEach { section ->
          item(key = "sec-${section.id}") {
            Text(
              (section.title ?: "").uppercase(),
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          val blocks = tree.blocks.filter { it.sectionId == section.id }.sortedBy { it.ord }
          items(blocks, key = { "blk-${it.id}" }) { block -> HubBlockCard(block, focused = block.id == state.hubFocusBlockId) }
        }
      }
      }
    }
  }
}

// Pure: the LazyColumn item index of the focused block (or null = not present / no
// focus), so the arrival can scroll it into view. Mirrors HubDetailScreen's emission
// order: [status header] + [countdown?] + [honesty?] + per section [header] + [blocks].
// Unit-tested so it can't silently drift from the render.
fun focusedBlockItemIndex(tree: HubTree, focusBlockId: String?, hasCountdown: Boolean, restricted: Boolean): Int? {
  if (focusBlockId == null) return null
  var idx = 1                                       // status header (always)
  if (hasCountdown) idx += 1
  if (restricted) idx += 1
  for (section in tree.sections.sortedBy { it.ord }) {
    idx += 1                                         // section header
    val blocks = tree.blocks.filter { it.sectionId == section.id }.sortedBy { it.ord }
    val pos = blocks.indexOfFirst { it.id == focusBlockId }
    if (pos >= 0) return idx + pos
    idx += blocks.size
  }
  return null
}

// "Who can see this hub" (ADR 0030, the signed-off Hubs-Visibility sheet). Display
// only at MVP — in-app editing of the allow-list is push/CLI (OQ-hub-collab). A
// scrim + bottom panel; the roster shows permitted (filled check) vs hidden (ring).
@Composable
fun WhoCanSeeSheet(state: AppState, onClose: () -> Unit = {}) {
  val aud = state.currentHubAudience
  Box(Modifier.fillMaxSize()) {
    // scrim
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)).clickable(onClick = onClose))
    Surface(
      color = MaterialTheme.colorScheme.surface,
      shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
      modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
    ) {
      Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Who can see this hub", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
          "Private hubs are visible only to the people you pick. The family owner isn't added automatically.",
          style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (aud == null) {
          Text("Loading…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
          aud.members.forEach { m -> AudienceRow(m, isYou = m.uid == state.session?.userId) }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(14.dp)) {
          Text(
            "Removing someone hides this hub from their devices on the next sync.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(13.dp),
          )
        }
        androidx.compose.material3.Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Done") }
      }
    }
  }
}

@Composable
private fun AudienceRow(m: HubAudienceMember, isYou: Boolean) {
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
    val initials = (m.displayName ?: "?").split(" ").mapNotNull { it.firstOrNull()?.uppercaseChar() }.take(2).joinToString("")
    Box(Modifier.size(42.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
      Text(initials, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
    Column(Modifier.padding(horizontal = 13.dp).weight(1f)) {
      Text((m.displayName ?: "Member") + if (isYou) " · You" else "", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
      Text(m.role.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    // permitted = filled coral check; hidden = empty ring
    Box(
      Modifier.size(30.dp).clip(RoundedCornerShape(50))
        .background(if (m.permitted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
        .then(if (m.permitted) Modifier else Modifier.border(2.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(50))),
      contentAlignment = Alignment.Center,
    ) { if (m.permitted) Text("✓", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium) }
  }
}

@Composable
private fun HubBlockCard(block: HubBlock, focused: Boolean = false) {
  Card(
    Modifier.fillMaxWidth()
      .then(if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(22.dp)) else Modifier),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    shape = RoundedCornerShape(22.dp),
  ) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      // deep-link arrival badge (the design's "FROM YOUR BRIEFING" pulse)
      if (focused) {
        Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(7.dp)) {
          Text("↗ FROM YOUR BRIEFING", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
        }
      }
      when (block.type) {
        "text", "markdown" -> Text(block.bodyMd ?: "", style = MaterialTheme.typography.bodyMedium)
        "checklist" -> block.payload?.items?.forEach { item -> ChecklistRow(item) }
        "link", "document" -> LinkRow(block)
        "contact" -> ContactRow(block.payload)
        "location" -> LocationBlock(block.payload)
        "milestone" -> MilestoneRow(block.payload, block.bodyMd)
        "budget" -> BudgetBar(block.payload)
        else -> Text(block.bodyMd ?: block.type, style = MaterialTheme.typography.bodyMedium)
      }
      block.provenance?.source?.let { src -> ProvenanceChip(src) }
    }
  }
}

@Composable
private fun ChecklistRow(item: ChecklistItem) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    // filled coral check when done, outlined warm ring when not (design treatment)
    val box = if (item.done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
    Box(
      Modifier.size(22.dp).clip(RoundedCornerShape(7.dp)).background(box)
        .then(if (item.done) Modifier else Modifier.border(2.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(7.dp))),
      contentAlignment = Alignment.Center,
    ) { if (item.done) Text("✓", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary) }
    Column(Modifier.padding(start = 12.dp).weight(1f)) {
      Text(
        item.text ?: "",
        style = MaterialTheme.typography.bodyMedium,
        color = if (item.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        textDecoration = if (item.done) TextDecoration.LineThrough else null,
      )
      val sub = listOfNotNull(item.due?.let { "Due $it" }, item.assignee).joinToString(" · ")
      if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun LinkRow(block: HubBlock) {
  val p = block.payload
  Row(verticalAlignment = Alignment.CenterVertically) {
    IconTile(if (block.type == "document") "📄" else "🔗", MaterialTheme.colorScheme.tertiaryContainer)
    Column(Modifier.padding(horizontal = 13.dp).weight(1f)) {
      Text(p?.label ?: p?.url ?: block.type, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
      (p?.domain ?: p?.url)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
    }
    Text("↗", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)  // opens externally (calm)
  }
}

@Composable
private fun ContactRow(p: BlockPayload?) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    val initials = (p?.name ?: "?").split(" ").mapNotNull { it.firstOrNull()?.uppercaseChar() }.take(2).joinToString("")
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
      Text(initials, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
    Column(Modifier.padding(horizontal = 13.dp).weight(1f)) {
      Text(p?.name ?: "Contact", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
      p?.role?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    // round Call / Text affordances (OS handoff when wired — display here)
    if (p?.phone != null) { RoundAffordance("📞"); Box(Modifier.width(8.dp)); RoundAffordance("💬") }
  }
}

@Composable
private fun LocationBlock(p: BlockPayload?) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    // map placeholder tile (Coil3 map render is M1; a calm warm tile + pin at M0)
    Box(
      Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
      contentAlignment = Alignment.Center,
    ) { Text("📍", style = MaterialTheme.typography.headlineMedium) }
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(p?.label ?: "Location", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        p?.address?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
      }
      Text("Directions ↗", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
  }
}

@Composable
private fun MilestoneRow(p: BlockPayload?, bodyMd: String?) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(11.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary))  // timeline dot
    Column(Modifier.padding(start = 12.dp)) {
      Text(p?.label ?: bodyMd ?: "Milestone", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
      p?.date?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
  }
}

@Composable
private fun BudgetBar(p: BlockPayload?) {
  val total = p?.total ?: 0.0
  val spent = p?.spent ?: 0.0
  val frac = if (total > 0) (spent / total).coerceIn(0.0, 1.0).toFloat() else 0f
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(verticalAlignment = Alignment.Bottom) {
      Text("${'$'}${spent.toInt()}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
      Text(" / ${'$'}${total.toInt()}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
      Text("${'$'}${(total - spent).toInt()} left", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.secondary)
    }
    Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant)) {
      Box(Modifier.fillMaxWidth(frac).height(10.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.secondary))
    }
  }
}

@Composable
private fun IconTile(glyph: String, bg: androidx.compose.ui.graphics.Color) =
  Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(bg), contentAlignment = Alignment.Center) { Text(glyph) }

@Composable
private fun RoundAffordance(glyph: String) =
  Box(Modifier.size(40.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) { Text(glyph) }

@Composable
private fun ProvenanceChip(src: String) {
  Text(
    when (src) { "claude" -> "✦ Added by Claude"; "email" -> "From your email"; "user" -> "You added this"; else -> "Saved" },
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}
