package com.sloopworks.dayfold.client

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * ADR 0064 §Done — "Mark done, with a note."
 *
 * The note is optional and NEVER demanded: "Just done" is always one tap away, and it is not
 * gated on typing anything. That is the whole point of this step — the note is a gift, not a
 * toll. It is context twice over: for the family (how it was resolved) and for the next run,
 * which reads completion + note as context rather than mere suppression.
 */
@Composable
fun DoneNoteStep(
  sheet: ResponseSheetState,
  onJustDone: () -> Unit,
  onSaveNote: (String) -> Unit,
  onBack: () -> Unit = {},
) {
  val cs = MaterialTheme.colorScheme
  var note by remember(sheet.subjectRef) { mutableStateOf("") }

  Column(
    Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
      .padding(horizontal = 14.dp).padding(bottom = 22.dp),
  ) {
    Row(
      Modifier.fillMaxWidth().padding(bottom = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back" }) {
        Icon(DayfoldIcons.ArrowBack, contentDescription = null, modifier = Modifier.size(22.dp).clearAndSetSemantics {})
      }
      Column(Modifier.weight(1f).padding(end = 8.dp)) {
        Text("Mark done", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Text(
          sheet.subjectTitle,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          color = cs.onSurface,
        )
      }
    }

    // Says what Done actually does, before it is done — completion is family-wide and the
    // byline is the only signal anyone else gets.
    Text(
      "Completes it for your family. Who did it and when are recorded; no one is notified.",
      style = MaterialTheme.typography.bodySmall,
      color = cs.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 8.dp),
    )
    Spacer(Modifier.height(12.dp))

    OutlinedTextField(
      value = note,
      onValueChange = { note = it },
      modifier = Modifier.fillMaxWidth(),
      label = { Text("Add a note (optional)") },
      placeholder = { Text("How was it resolved?") },
      minLines = 2,
      maxLines = 4,
    )

    Spacer(Modifier.height(14.dp))
    BoxWithConstraints(Modifier.fillMaxWidth()) {
      val stack = maxWidth < 360.dp || LocalDensity.current.fontScale >= 1.3f
      val justDone: @Composable (Modifier) -> Unit = { modifier ->
        TextButton(onClick = onJustDone, modifier = modifier.heightIn(min = 48.dp)) {
          Text("Just done", fontWeight = FontWeight.SemiBold)
        }
      }
      val saveNote: @Composable (Modifier) -> Unit = { modifier ->
        Button(
          onClick = { onSaveNote(note) },
          enabled = note.isNotBlank(),
          modifier = modifier.heightIn(min = 48.dp),
          colors = ButtonDefaults.buttonColors(containerColor = cs.secondary, contentColor = cs.onSecondary),
        ) { Text("Save note", fontWeight = FontWeight.SemiBold) }
      }
      if (stack) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          justDone(Modifier.fillMaxWidth())
          saveNote(Modifier.fillMaxWidth())
        }
      } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          justDone(Modifier.weight(1f))
          saveNote(Modifier.weight(1f))
        }
      }
    }
  }
}
