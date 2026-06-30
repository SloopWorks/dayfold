package com.sloopworks.dayfold.client

import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sloopworks.dayfold.client.theme.LocalDayfoldColors

// ADR 0044 §3 / ADR 0014 — the OFFLINE banner (designs/triggers/Offline-Phone). Privacy teal, NOT an
// error: offline is a STRENGTH here — time triggers fire from the device clock and geofences match
// against places saved on the phone, with no connection needed. Only map tiles and fresh deep-link
// content wait for signal. Reinforces the on-device honesty: matching happens right here.
@Composable
fun OfflineBanner(modifier: Modifier = Modifier) {
  val c = LocalDayfoldColors.current
  Surface(
    shape = RoundedCornerShape(18.dp), color = c.privacyContainer,
    modifier = modifier.fillMaxWidth()
      .semantics { contentDescription = "Offline. Still matched on your device — time reminders fire and places match right here, no connection needed." },
  ) {
    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
      Surface(shape = RoundedCornerShape(13.dp), color = c.onPrivacyContainer.copy(alpha = 0.12f), modifier = Modifier.size(42.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
          Icon(DayfoldIcons.WifiOff, contentDescription = null, tint = c.onPrivacyContainer, modifier = Modifier.size(23.dp))
        }
      }
      Spacer(Modifier.width(13.dp))
      Column(Modifier.weight(1f)) {
        Text("Offline · still matched on your device", style = MaterialTheme.typography.titleSmall, color = c.onPrivacyContainer)
        Text(
          "Time reminders fire and places match right here — no connection needed.",
          style = MaterialTheme.typography.bodySmall, color = c.onPrivacyContainer,
        )
      }
    }
  }
}
