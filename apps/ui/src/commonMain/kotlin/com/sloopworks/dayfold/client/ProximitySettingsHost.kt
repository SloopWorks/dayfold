package com.sloopworks.dayfold.client

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

// ADR 0044 Phase B — the navigable host for the Background-proximity settings (designs/triggers/
// Settings-Phone). Owns the top bar + the quiet-hours TimePicker dialogs + the privacy detail sheet, and
// turns each edit into a device-local config write (UI → onSetNotifConfig → ContentStore → flow →
// NotifConfigLoaded; no optimistic UI→store shortcut). Enabling here is what arms the geofences + exact
// alarms (the shell's notifConfigFlow reaction). Reversible, default-off, never synced.

private enum class QuietEdit { Start, End }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProximitySettingsHost(
  config: NotifConfig,
  permission: LocationPermission,
  onSetNotifConfig: (NotifConfig) -> Unit,
  onOpenPermission: () -> Unit,
  onBack: () -> Unit,
) {
  var editing by remember { mutableStateOf<QuietEdit?>(null) }
  var privacyOpen by remember { mutableStateOf(false) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Background proximity") },
        navigationIcon = {
          IconButton(onClick = onBack) { Icon(DayfoldIcons.ArrowBack, contentDescription = "Back") }
        },
      )
    },
  ) { pad ->
    ProximitySettingsScreen(
      config = config,
      permission = permission,
      deregistering = false,
      onToggle = { onSetNotifConfig(config.copy(enabled = it)) },
      onPickCap = { onSetNotifConfig(config.copy(dailyCap = it)) },
      onEditQuietStart = { editing = QuietEdit.Start },
      onEditQuietEnd = { editing = QuietEdit.End },
      onOpenPermission = onOpenPermission,
      onPrivacyInfo = { privacyOpen = true },
      modifier = Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()),
    )
  }

  editing?.let { which ->
    val initial = if (which == QuietEdit.Start) config.quietStartMinuteOfDay else config.quietEndMinuteOfDay
    QuietHoursTimePickerDialog(
      initialMinuteOfDay = initial,
      onConfirm = { m ->
        onSetNotifConfig(
          if (which == QuietEdit.Start) config.copy(quietStartMinuteOfDay = m)
          else config.copy(quietEndMinuteOfDay = m),
        )
        editing = null
      },
      onDismiss = { editing = null },
    )
  }

  if (privacyOpen) {
    ModalBottomSheet(onDismissRequest = { privacyOpen = false }) {
      PrivacyDetailContent(onManagePlaces = { privacyOpen = false }, onDismiss = { privacyOpen = false })
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuietHoursTimePickerDialog(initialMinuteOfDay: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
  val timeState = rememberTimePickerState(
    initialHour = initialMinuteOfDay / 60,
    initialMinute = initialMinuteOfDay % 60,
    is24Hour = false,
  )
  AlertDialog(
    onDismissRequest = onDismiss,
    confirmButton = { TextButton(onClick = { onConfirm(timeState.hour * 60 + timeState.minute) }) { Text("Set") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    title = { Text("Quiet hours", style = MaterialTheme.typography.titleMedium) },
    text = { Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { TimePicker(state = timeState) } },
  )
}
