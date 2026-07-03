package com.sloopworks.dayfold.client

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sloopworks.dayfold.client.theme.LocalDayfoldColors

// ADR 0044 §3 / ADR 0014 — the READ-ONLY saved-places list (designs/triggers/Places-Phone, list state).
// Places are CLI/server-authored (Add/Edit place UI is OUT of Phase B scope — a future place-egress lane
// + its own ADR); the client only RENDERS them. No edit pencil, no Add FAB. The family-privacy row
// carries the honesty posture: shared with the family + matched on-device, never live position, never ads.
@Composable
fun PlacesListScreen(places: List<Place>, modifier: Modifier = Modifier) {
  val cs = MaterialTheme.colorScheme
  val c = LocalDayfoldColors.current
  LazyColumn(
    modifier.fillMaxWidth(),
    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(11.dp),
  ) {
    item(key = "header") {
      Column {
        Text("FAMILY · SETTINGS", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant)
        Spacer(Modifier.size(4.dp))
        Text("Places", style = MaterialTheme.typography.headlineMedium, color = cs.onSurface)
      }
    }
    item(key = "privacy") {
      Surface(shape = RoundedCornerShape(16.dp), color = c.privacyContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
          Icon(DayfoldIcons.Verified, contentDescription = null, tint = c.onPrivacyContainer, modifier = Modifier.size(23.dp))
          Spacer(Modifier.width(12.dp))
          Column(Modifier.weight(1f)) {
            Text("Your places stay private", style = MaterialTheme.typography.titleSmall, color = c.onPrivacyContainer)
            Text("Shared with your family, matched on-device. Never used for ads.", style = MaterialTheme.typography.bodySmall, color = c.onPrivacyContainer)
          }
        }
      }
    }
    if (places.isEmpty()) {
      item(key = "empty") {
        Surface(shape = RoundedCornerShape(22.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
          Column(Modifier.padding(20.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No saved places yet", style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
            Text("Places are added by Claude or from the CLI.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
          }
        }
      }
    }
    items(places, key = { it.id }) { p -> PlaceRow(p) }
  }
}

@Composable
private fun PlaceRow(place: Place) {
  val cs = MaterialTheme.colorScheme
  Surface(shape = RoundedCornerShape(22.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
      Surface(shape = RoundedCornerShape(15.dp), color = cs.secondaryContainer, modifier = Modifier.size(46.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
          Icon(DayfoldIcons.Location, contentDescription = null, tint = cs.onSecondaryContainer, modifier = Modifier.size(24.dp))
        }
      }
      Spacer(Modifier.width(14.dp))
      Column(Modifier.weight(1f)) {
        Text(place.label, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
        place.kind?.let { Text(it.replaceFirstChar { ch -> ch.uppercase() }, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
        Spacer(Modifier.size(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
          place.radiusM?.let { r ->
            Surface(shape = RoundedCornerShape(7.dp), color = cs.surfaceContainerHigh) {
              Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(DayfoldIcons.MyLocation, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
                Text("$r m", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant)
              }
            }
          }
          Text("Added by Claude", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
      }
    }
  }
}
