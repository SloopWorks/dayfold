package com.sloopworks.dayfold.client

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sloopworks.dayfold.client.theme.LocalDayfoldColors

// ADR 0044 §3 — the reusable "Matched on your device" privacy affordance, the brand-load-bearing
// honesty piece. THREE densities, ONE honest two-part story (designs/triggers/Privacy-Affordance):
//   • saved places sync to your family, encrypted;  • only your LIVE position — where you are right
// now — stays on this phone and is matched on-device.
// The CHIP carries ONLY the short honest claim ("Matched on your device"). The false
// "saved coords never leave" claim is the killed P0 bug — it never appears here. The full true line
// ("your live position never leaves this phone") lives in the info-row subtitle + the detail sheet.
// a11y: privacy chip text is labelSmall (≥11sp); the row is a labelled, tappable control.

/** A · the chip — rides cards & (expanded) notifications. labelSmall (≥11sp) per the a11y bar. */
@Composable
fun MatchedOnDeviceChip(modifier: Modifier = Modifier) {
  val c = LocalDayfoldColors.current
  Surface(shape = RoundedCornerShape(8.dp), color = c.privacyContainer, modifier = modifier) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
    ) {
      Icon(DayfoldIcons.Verified, contentDescription = null, tint = c.onPrivacyContainer, modifier = Modifier.size(14.dp))
      Spacer(Modifier.width(5.dp))
      Text("Matched on your device", color = c.onPrivacyContainer, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
    }
  }
}

/** B · the info-row — in sheets & settings; tapping opens the detail sheet (C). */
@Composable
fun MatchedOnDeviceRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
  val cs = MaterialTheme.colorScheme
  val c = LocalDayfoldColors.current
  Surface(
    shape = RoundedCornerShape(18.dp),
    color = cs.surfaceVariant,
    modifier = modifier.fillMaxWidth().clickable(onClickLabel = "How matching stays private", onClick = onClick)
      .semantics { contentDescription = "Matched on your device. Your live position never leaves this phone. Learn how matching stays private." },
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(14.dp)) {
      PrivacyTile(DayfoldIcons.Verified)
      Spacer(Modifier.width(13.dp))
      Column(Modifier.weight(1f)) {
        Text("Matched on your device", style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
        Text("Your live position never leaves this phone.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
      }
      Icon(DayfoldIcons.ChevronRight, contentDescription = null, tint = cs.onSurfaceVariant)
    }
  }
}

/** C · the detail-sheet body — the full, honest explanation (host it in a ModalBottomSheet). */
@Composable
fun PrivacyDetailContent(onManagePlaces: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
  val cs = MaterialTheme.colorScheme
  Column(modifier = modifier.fillMaxWidth().padding(20.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      PrivacyTile(DayfoldIcons.Verified)
      Spacer(Modifier.width(10.dp))
      Text("How matching stays private", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
    }
    Spacer(Modifier.size(14.dp))
    PrivacyStep(DayfoldIcons.AutoAwesome, "Claude tags the content", "A place or time is attached as metadata — not your location.")
    PrivacyStep(DayfoldIcons.Lock, "Saved places sync to your family", "Home, school, the store are shared family content — stored encrypted.")
    PrivacyStep(DayfoldIcons.Smartphone, "Your live position stays here", "Where you are right now never leaves the phone — your device does the matching.")
    Spacer(Modifier.size(6.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
      Button(onClick = onManagePlaces, modifier = Modifier.weight(1f)) {
        Icon(DayfoldIcons.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Text("Manage places")
      }
      TextButton(onClick = onDismiss) { Text("Got it") }
    }
  }
}

@Composable
private fun PrivacyTile(icon: ImageVector) {
  val c = LocalDayfoldColors.current
  Surface(shape = RoundedCornerShape(13.dp), color = c.privacyContainer, modifier = Modifier.size(40.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
      Icon(icon, contentDescription = null, tint = c.onPrivacyContainer, modifier = Modifier.size(21.dp))
    }
  }
}

@Composable
private fun PrivacyStep(icon: ImageVector, title: String, body: String) {
  val cs = MaterialTheme.colorScheme
  val c = LocalDayfoldColors.current
  Row(modifier = Modifier.fillMaxWidth().padding(bottom = 13.dp)) {
    Surface(shape = RoundedCornerShape(999.dp), color = c.privacyContainer, modifier = Modifier.size(30.dp)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = c.onPrivacyContainer, modifier = Modifier.size(17.dp))
      }
    }
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
      Text(body, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
  }
}
