package com.sloopworks.dayfold.client

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// AUTH-S5 Slice 2b — invitee-join UI (Dayfold, from the A8b set). One screen:
// the paste-an-invite entry, then — once a redeem resolves — the matching result
// state (waiting / invitelocked / inviteerror / alreadymember / joinerror). Every
// invite is owner-approved (ADR 0011), so a success lands on "waiting".
@Composable
fun JoinInviteScreen(
  state: AppState,
  onJoin: (String) -> Unit = {},
  onDismiss: () -> Unit = {},
) {
  val cs = MaterialTheme.colorScheme
  Column(Modifier.fillMaxSize().background(cs.surface).padding(start = 28.dp, end = 28.dp, top = 16.dp, bottom = 30.dp)) {
    // back
    Box(
      Modifier.size(38.dp).clip(RoundedCornerShape(50)).clickable(onClick = onDismiss),
      contentAlignment = Alignment.Center,
    ) { Text("‹", style = MaterialTheme.typography.headlineSmall, color = cs.onSurface) }

    when (state.joinOutcome) {
      null -> JoinEntry(state.joinBusy, onJoin)
      "waiting" -> JoinResult(
        accent = cs.tertiaryContainer, onAccent = cs.onTertiaryContainer,
        title = "Almost in", body = "${state.joinFamilyName ?: "The owner"} needs to approve you. We'll let you know the moment they do.",
        cta = "Done", onCta = onDismiss,
      )
      "locked" -> JoinResult(
        accent = cs.surfaceContainerHigh, onAccent = cs.onSurfaceVariant,
        title = "Take a breather", body = "You've tried to join a few times in a row. Wait about 15 minutes, then try the invite again.",
        cta = "Done", onCta = onDismiss,
      )
      "already" -> JoinResult(
        accent = cs.secondaryContainer, onAccent = cs.onSecondaryContainer,
        title = "You're already in", body = "You're already a member of this family. Jump back to your briefing.",
        cta = "Open Dayfold", onCta = onDismiss,
      )
      "removed" -> JoinResult(
        accent = cs.errorContainer, onAccent = cs.error,
        title = "You're not in this family", body = "You were removed from this family. Ask the owner to invite you again.",
        cta = "Done", onCta = onDismiss,
      )
      "expired" -> JoinResult(
        accent = cs.errorContainer, onAccent = cs.error,
        title = "This invite isn't active", body = "It may have expired, been used up, or turned off. Ask for a fresh invite.",
        cta = "Try again", onCta = onDismiss,
      )
      else -> JoinResult(   // "error" (transient)
        accent = cs.surfaceContainerHigh, onAccent = cs.onSurfaceVariant,
        title = "Couldn't join", body = "We couldn't reach Dayfold to finish joining. Check your connection and try again.",
        cta = "Try again", onCta = onDismiss,
      )
    }
  }
}

@Composable
private fun ColumnScope.JoinEntry(busy: Boolean, onJoin: (String) -> Unit) {
  val cs = MaterialTheme.colorScheme
  var token by remember { mutableStateOf("") }
  Spacer(Modifier.height(24.dp))
  Text("Join a family", style = MaterialTheme.typography.displaySmall, color = cs.onSurface)
  Spacer(Modifier.height(10.dp))
  Text(
    "Paste the invite link or code someone shared with you. They'll approve you before you're in.",
    style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant,
  )
  Spacer(Modifier.height(26.dp))
  OutlinedTextField(
    value = token, onValueChange = { token = it },
    label = { Text("Invite link or code") }, singleLine = true,
    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
    shape = RoundedCornerShape(16.dp),
    colors = TextFieldDefaults.colors(
      focusedContainerColor = cs.surfaceContainer, unfocusedContainerColor = cs.surfaceContainer,
    ),
    modifier = Modifier.fillMaxWidth(),
  )
  Spacer(Modifier.weight(1f))
  Box(
    Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(16.dp))
      .background(if (!busy && token.isNotBlank()) cs.primary else cs.surfaceContainerHigh)
      .clickable(enabled = !busy && token.isNotBlank()) { onJoin(inviteTokenOf(token)) },
    contentAlignment = Alignment.Center,
  ) {
    Text(
      if (busy) "Joining…" else "Join",
      style = MaterialTheme.typography.labelLarge,
      color = if (!busy && token.isNotBlank()) cs.onPrimary else cs.onSurfaceVariant,
    )
  }
}

@Composable
private fun JoinResult(accent: Color, onAccent: Color, title: String, body: String, cta: String, onCta: () -> Unit) {
  val cs = MaterialTheme.colorScheme
  Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
    Box(Modifier.size(74.dp).clip(RoundedCornerShape(23.dp)).background(accent), contentAlignment = Alignment.Center) {
      Box(Modifier.size(30.dp).clip(RoundedCornerShape(50)).background(onAccent))
    }
    Spacer(Modifier.height(22.dp))
    Text(title, style = MaterialTheme.typography.displaySmall, color = cs.onSurface, textAlign = TextAlign.Center)
    Spacer(Modifier.height(10.dp))
    Text(body, style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
    Spacer(Modifier.height(28.dp))
    Box(
      Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp)).background(cs.primary).clickable(onClick = onCta),
      contentAlignment = Alignment.Center,
    ) { Text(cta, style = MaterialTheme.typography.labelLarge, color = cs.onPrimary) }
  }
}

// Accept either a raw token or an invite URL (https://<app>/invite/<token>) —
// pull the last path segment if it looks like a link. M0: no deep-link; paste.
private fun inviteTokenOf(input: String): String {
  val t = input.trim()
  return if ("/invite/" in t) t.substringAfterLast("/invite/").substringBefore("?").trim() else t
}
