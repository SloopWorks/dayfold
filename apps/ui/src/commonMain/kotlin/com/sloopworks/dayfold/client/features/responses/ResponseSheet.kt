package com.sloopworks.dayfold.client

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

/**
 * ADR 0064 — the ONE response sheet. Same verbs, same order, on every surface smart content
 * touches; only the copy changes (`NOTES.md` § Placement). A second vocabulary per surface is
 * exactly what this design set exists to avoid.
 */
enum class Verb { DONE, HIDE, MUTE, DELETE_AND_MUTE }

data class VerbRow(
  val verb: Verb,
  val icon: ImageVector,
  val title: String,
  val subtitle: String,
  /** Coral, like every delete. */
  val destructive: Boolean = false,
  /** The primary on a task-shaped card. */
  val emphasized: Boolean = false,
)

/**
 * The verb list for [surface]. Order is fixed across surfaces on purpose.
 *
 * There is deliberately NO positive-signal row ("more like this", "show more of these"):
 * keeping a card IS the positive signal, and an engagement affordance is what the calm
 * constitution bans.
 */
fun verbRowsFor(surface: ResponseSurface): List<VerbRow> = buildList {
  add(
    VerbRow(
      Verb.DONE, DayfoldIcons.CheckCircle, "Mark done",
      "Completes it for your family — future runs remember",
      emphasized = true,
    ),
  )
  add(
    VerbRow(
      Verb.HIDE, DayfoldIcons.VisibilityOff, "Hide for me",
      if (surface == ResponseSurface.HUB) "Your family still sees it in this hub"
      else "Your family still sees it",
    ),
  )
  add(
    VerbRow(
      Verb.MUTE, DayfoldIcons.DoNotDisturbOn,
      if (surface == ResponseSurface.HUB) "Don't add to this hub again" else "Don't add this again",
      if (surface == ResponseSurface.HUB) "This block stays; nothing similar is re-added"
      else "Nothing similar is added in future",
    ),
  )
  // Hub blocks only: removing an existing block stays a W4 delete, and pairing it with the
  // rule is what keeps cleanup from becoming whack-a-mole. One action, two effects — the
  // sub-line says so.
  if (surface == ResponseSurface.HUB) {
    add(
      VerbRow(
        Verb.DELETE_AND_MUTE, DayfoldIcons.Delete, "Remove, and don't re-add",
        "Removes it for everyone — pairs delete with the rule",
        destructive = true,
      ),
    )
  }
}

/**
 * One sheet, two steps. [scopeContent] is supplied by the host for the precision step rather
 * than nested here, so the sheet stays free of the mute-commit wiring and the golden-snapshot
 * registry can render either step standalone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResponseSheet(
  sheet: ResponseSheetState,
  onVerb: (Verb) -> Unit,
  onDismiss: () -> Unit,
  onBack: () -> Unit = onDismiss,
  scopeContent: (@Composable () -> Unit)? = null,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(
    // System Back, scrim tap, and drag are modal dismiss gestures and all close the sheet.
    // The explicit 48dp Back control is the one step-up action inside the flow.
    onDismissRequest = onDismiss,
    sheetState = sheetState,
  ) {
    if (scopeContent != null) scopeContent() else ResponseSheetContent(sheet, onVerb)
  }
}

/** One app-root overlay, shared by Now, Detail, and Hubs. */
@Composable
fun ResponseOverlay(
  state: ResponseState,
  onVerb: (Verb) -> Unit,
  onDismiss: () -> Unit,
  onBack: () -> Unit,
  onScope: (MatchScope) -> Unit,
  onAudience: (AudienceScope) -> Unit,
  onOpenRoutines: () -> Unit,
  onCommitMute: () -> Unit,
  onDone: (String?) -> Unit,
  onUndo: () -> Unit,
  onDismissReceipt: () -> Unit,
) {
  state.sheet?.let { sheet ->
    ResponseSheet(
      sheet = sheet,
      onVerb = onVerb,
      onDismiss = onDismiss,
      onBack = onBack,
      scopeContent = when (sheet.step) {
        ResponseStep.SCOPE -> {{
          ResponseScopeStep(
            sheet = sheet,
            onScope = onScope,
            onAudience = onAudience,
            onOpenRoutines = onOpenRoutines,
            onCommit = onCommitMute,
            onBack = onBack,
          )
        }}
        ResponseStep.DONE_NOTE -> {{
          DoneNoteStep(
            sheet = sheet,
            onJustDone = { onDone(null) },
            onSaveNote = { onDone(it) },
            onBack = onBack,
          )
        }}
        ResponseStep.VERBS -> null
      },
    )
  }
  state.lastReceipt?.let { ResponseReceiptBar(it, onUndo, onDismissReceipt) }
}

@Composable
private fun ResponseReceiptBar(
  receipt: ResponseReceipt,
  onUndo: () -> Unit,
  onDismiss: () -> Unit,
) {
  val cs = MaterialTheme.colorScheme
  Box(
    Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 72.dp)
      .semantics { liveRegion = LiveRegionMode.Polite },
    contentAlignment = Alignment.BottomCenter,
  ) {
    androidx.compose.material3.Surface(
      shape = RoundedCornerShape(14.dp),
      color = cs.inverseSurface,
      modifier = Modifier.padding(14.dp).fillMaxWidth(),
    ) {
      Row(
        Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(receipt.message, style = MaterialTheme.typography.bodyMedium, color = cs.inverseOnSurface, modifier = Modifier.weight(1f))
        TextButton(onClick = if (receipt.undoable) onUndo else onDismiss) {
          Text(if (receipt.undoable) "Undo" else "Dismiss", color = cs.inversePrimary)
        }
      }
    }
  }
}

/**
 * The sheet's inner content, split out so the golden-snapshot registry can render it
 * standalone — a headless single-frame render never paints a Dialog's separate compose scene,
 * so scening the ModalBottomSheet wrapper directly would golden an empty frame (same reason
 * AvatarPickerContent is split).
 */
@Composable
fun ResponseSheetContent(sheet: ResponseSheetState, onVerb: (Verb) -> Unit) {
  val cs = MaterialTheme.colorScheme
  Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 22.dp)) {
    // The subject line, so the member can see WHAT they are responding to without dismissing.
    Text(
      subjectLineFor(sheet),
      style = MaterialTheme.typography.bodySmall,
      color = cs.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 8.dp).padding(bottom = 12.dp),
    )
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      verbRowsFor(sheet.surface).forEach { row ->
        VerbRowItem(row, onClick = { onVerb(row.verb) })
      }
    }
  }
}

/** "Rain at soccer practice · added by Morning briefing" — provenance, not a second title. */
internal fun subjectLineFor(sheet: ResponseSheetState): String =
  if (sheet.source != null) "${sheet.subjectTitle} · added by ${sheet.source}" else sheet.subjectTitle

@Composable
private fun VerbRowItem(row: VerbRow, onClick: () -> Unit) {
  val cs = MaterialTheme.colorScheme
  val fg = when {
    row.destructive -> cs.error
    row.emphasized -> cs.onSecondaryContainer
    else -> cs.onSurface
  }
  Row(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(14.dp))
      .background(if (row.emphasized) cs.secondaryContainer else cs.surface)
      .clickable(onClick = onClick)
      // 48dp minimum touch target — the sheet is the keyboard/TalkBack-reachable path.
      .heightIn(min = 48.dp)
      .padding(horizontal = 10.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Icon(
      row.icon,
      contentDescription = null,
      tint = if (row.destructive) cs.error else cs.secondary,
      modifier = Modifier.size(21.dp),
    )
    Column(Modifier.weight(1f)) {
      Text(row.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = fg)
      Spacer(Modifier.height(1.dp))
      Text(row.subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
  }
}
