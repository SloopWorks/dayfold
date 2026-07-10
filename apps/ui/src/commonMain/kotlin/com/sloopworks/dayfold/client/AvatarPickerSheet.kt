package com.sloopworks.dayfold.client

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sloopworks.dayfold.client.ui.DayfoldAvatar
import com.sloopworks.dayfold.client.ui.FunAvatars

private enum class AvatarPane { Monogram, Fun, Photo }

// Delta A / Task 5 — the avatar picker: Monogram (swatch grid) · Fun avatars (bundled
// vector set, ADR 0036) · Photo (disabled — upload is explicitly out of scope). Pure
// state-in/callback-out signature (no store/engine coupling) so it's unit-testable in
// isolation; AccountScreen supplies currentColor/currentRef from AppState and wires
// onSave to AuthEngine.updateAvatar.
//
// Compact layout intentional: the sheet must fit a headless test's default 1024x768
// virtual window (no scroll wired) — a tall column previously pushed later rows past
// the visible bounds and performClick() threw ("outside the Compose root bounds").
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvatarPickerSheet(
  currentColor: String?,
  currentRef: String?,
  onSave: (color: String?, ref: String?) -> Unit,
  onDismiss: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState()
  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    AvatarPickerContent(currentColor, currentRef, onSave)
  }
}

// The picker's inner content, split out from [AvatarPickerSheet] so it can be rendered
// standalone (the golden-snapshot registry, mirrors PrivacyDetailContent/ProximitySettingsHost)
// — a headless single-frame render never paints a Dialog's separate compose scene, so scening
// the ModalBottomSheet wrapper directly would golden an empty frame.
@Composable
fun AvatarPickerContent(currentColor: String?, currentRef: String?, onSave: (color: String?, ref: String?) -> Unit) {
  val cs = MaterialTheme.colorScheme
  var pane by remember { mutableStateOf(if (currentRef != null) AvatarPane.Fun else AvatarPane.Monogram) }
  var pickedColor by remember { mutableStateOf(currentColor) }
  var pickedRef by remember { mutableStateOf(currentRef) }

  Column(
    Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text("Choose an avatar", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
    Spacer(Modifier.height(12.dp))
    // Live preview — reflects the in-progress pick, not just the saved value.
    DayfoldAvatar(
      name = "You", size = 56.dp, avatarColorKey = pickedColor, avatarRef = pickedRef,
      contentDescription = "Avatar preview",
    )
    Spacer(Modifier.height(14.dp))

    val options = listOf(AvatarPane.Monogram to "Monogram", AvatarPane.Fun to "Fun avatars", AvatarPane.Photo to "Photo")
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
      options.forEachIndexed { i, (p, label) ->
        SegmentedButton(
          selected = pane == p,
          onClick = { pane = p },
          shape = SegmentedButtonDefaults.itemShape(i, options.size),
          enabled = p != AvatarPane.Photo,   // photo upload is out of scope (ADR 0036) — a real disabled control
        ) { Text(label) }
      }
    }
    Spacer(Modifier.height(14.dp))

    when (pane) {
      AvatarPane.Monogram -> MonogramPane(pickedColor) { key -> pickedColor = key; pickedRef = null }
      AvatarPane.Fun -> FunAvatarPane(pickedRef) { id -> pickedRef = id; pickedColor = null }
      AvatarPane.Photo -> Text(
        "Photo avatars are coming later.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
      )
    }

    Spacer(Modifier.height(18.dp))
    Button(onClick = { onSave(pickedColor, pickedRef) }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MonogramPane(picked: String?, onPick: (String) -> Unit) {
  val swatches = com.sloopworks.dayfold.client.theme.LocalDayfoldColors.current.avatarSwatches
  FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    swatches.forEach { sw ->
      Box(
        Modifier.size(40.dp).clip(CircleShape).background(sw.bg)
          .then(if (picked == sw.key) Modifier.border(2.5.dp, sw.fg, CircleShape) else Modifier)
          .clickable { onPick(sw.key) },
      )
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FunAvatarPane(picked: String?, onPick: (String) -> Unit) {
  val cs = MaterialTheme.colorScheme
  FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    FunAvatars.all.forEach { a ->
      Box(
        Modifier.size(48.dp).clip(CircleShape)
          .then(if (picked == a.id) Modifier.border(2.5.dp, cs.primary, CircleShape) else Modifier)
          .clickable { onPick(a.id) },
        contentAlignment = Alignment.Center,
      ) {
        DayfoldAvatar(name = a.name, size = 48.dp, avatarRef = a.id, contentDescription = a.name)
      }
    }
  }
}
