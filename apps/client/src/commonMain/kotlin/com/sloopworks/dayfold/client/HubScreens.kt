package com.sloopworks.dayfold.client

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.text.font.FontWeight
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
) {
  Scaffold(
    topBar = { TopAppBar(title = { Text("Hubs", fontWeight = FontWeight.SemiBold) }) },
    bottomBar = { DayfoldBottomNav(hubsActive = true, onNow = onNow, onHubs = {}) },
  ) { pad ->
    when {
      state.hubs.isEmpty() && state.hubsBusy ->
        Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) { Text("Loading hubs…") }
      state.hubs.isEmpty() ->
        Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) {
          Text(
            "When a big family event shows up — a trip, a move, a birthday — a hub appears here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(40.dp),
          )
        }
      else -> LazyColumn(
        Modifier.fillMaxSize().padding(pad),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        items(state.hubs, key = { it.id }) { hub -> HubRow(hub, onClick = { onOpenHub(hub.id) }) }
      }
    }
  }
}

@Composable
private fun HubRow(hub: Hub, onClick: () -> Unit) {
  Card(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    shape = RoundedCornerShape(24.dp),
  ) {
    Column(Modifier.padding(18.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(hub.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (hub.visibility == "restricted") {
          // calm restricted marker — warm-neutral, never error-red
          Text("🔒", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
      else -> LazyColumn(
        Modifier.fillMaxSize().padding(pad),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        item {
          Row(verticalAlignment = Alignment.CenterVertically) {
            StatusChip(tree.hub.status)
            if (tree.hub.visibility == "restricted") {
              Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(999.dp),
                modifier = Modifier.padding(start = 8.dp),
              ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                  Text("🔒", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                  Text("Private", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(start = 6.dp))
                }
              }
            }
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
          items(blocks, key = { "blk-${it.id}" }) { block -> HubBlockCard(block) }
        }
      }
    }
  }
}

@Composable
private fun HubBlockCard(block: HubBlock) {
  Card(
    Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    shape = RoundedCornerShape(22.dp),
  ) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      when (block.type) {
        "text", "markdown" -> Text(block.bodyMd ?: "", style = MaterialTheme.typography.bodyMedium)
        "checklist" -> block.payload?.items?.forEach { item ->
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (item.done) "☑ " else "☐ ", style = MaterialTheme.typography.bodyMedium)
            Text(item.text ?: "", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            item.due?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
          }
        }
        "link", "document" -> {
          Text(block.payload?.label ?: block.payload?.url ?: block.type, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
          block.payload?.url?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        "contact" -> {
          Text(block.payload?.name ?: "Contact", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
          val sub = listOfNotNull(block.payload?.role, block.payload?.phone, block.payload?.email).joinToString(" · ")
          if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        "location" -> {
          Text(block.payload?.label ?: "Location", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
          block.payload?.address?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        "milestone" -> {
          Text(block.payload?.label ?: block.bodyMd ?: "Milestone", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
          block.payload?.date?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        "budget" -> {
          val total = block.payload?.total
          val spent = block.payload?.spent
          Text("Budget", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
          if (total != null) Text("${'$'}${spent ?: 0.0} / ${'$'}$total", style = MaterialTheme.typography.bodyMedium)
        }
        else -> Text(block.bodyMd ?: block.type, style = MaterialTheme.typography.bodyMedium)
      }
      // provenance (honesty constraint) — consistent caption when present
      block.provenance?.source?.let { src ->
        Text(
          when (src) { "claude" -> "Added by Claude"; "email" -> "From your email"; "user" -> "You added this"; else -> "Saved" },
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}
