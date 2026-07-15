package com.sloopworks.dayfold.client

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sloopworks.dayfold.client.ui.loading.RowBusy

// AUTH-S7 — the owner "Invite a member" surface (Dayfold, A8b `invite`). Mints an
// invite as a QR (in-person, one-time) or a shareable link, shows a live expiry
// countdown, and lists OUTSTANDING invites (revoke) + pending joiners (approve/
// decline). The minted token is display-only — it lives in state.mintedInvite and is
// cleared on leave (InviteDismissed); never persisted. QR auto-mints on entry to match
// the signed-off mockup (no explicit generate button).
@Composable
fun InviteScreen(
  state: InviteViewState,
  now: kotlin.time.Instant = kotlin.time.Clock.System.now(),
  onMode: (String) -> Unit = {},
  onMint: (String) -> Unit = {},
  onRevoke: (String) -> Unit = {},
  onApprove: (String) -> Unit = {},
  onDecline: (String) -> Unit = {},
  onBack: () -> Unit = {},
) {
  val cs = MaterialTheme.colorScheme

  // Auto-mint on entry / mode-change (mockup shows the QR already present). Guarded so
  // recomposition doesn't re-mint — only fires when there's no code, no in-flight mint,
  // and no error to retry from.
  LaunchedEffect(state.mode) {
    if (state.mintedInvite == null && !state.busy && state.mintError == null) onMint(state.mode)
  }

  Column(Modifier.fillMaxSize().background(cs.surface)) {
    Row(
      Modifier.fillMaxWidth().padding(start = 18.dp, end = 20.dp, top = 16.dp, bottom = 10.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Box(
        Modifier.size(38.dp).clip(RoundedCornerShape(50)).clickable(onClick = onBack).semantics { contentDescription = "Close" },
        contentAlignment = Alignment.Center,
      ) { Icon(DayfoldIcons.ArrowBack, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(24.dp).clearAndSetSemantics {}) }
      Text("Invite a member", style = MaterialTheme.typography.titleLarge, color = cs.onSurface)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 6.dp)) {
      Segmented(state.mode, onMode)
      Spacer(Modifier.height(18.dp))

      val minted = state.mintedInvite
      val error = state.mintError
      when {
        state.busy -> Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) { RowBusy() }
        error != null -> MintErrorCard(error, cs, onRetry = { onMint(state.mode) })
        minted != null && minted.mode == "qr" -> QrCard(minted, now)
        minted != null -> LinkCard(minted, now)
      }

      Spacer(Modifier.height(22.dp))
      RoleRow(cs)

      val others = state.outstandingInvites.filterNot { it.id == state.mintedInvite?.inviteId }
      if (others.isNotEmpty() || state.pendingApprovals.isNotEmpty()) {
        Spacer(Modifier.height(22.dp))
        Text(
          "OUTSTANDING", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant,
          modifier = Modifier.padding(start = 4.dp, bottom = 9.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
          others.forEach { invite ->
            key(invite.id) {
              OutstandingRow(invite, now, busy = invite.id == state.inviteOperationId, onRevoke = onRevoke)
            }
          }
          state.pendingApprovals.forEach { pending ->
            key(pending.uid) {
              InvitePendingRow(
                pending,
                busy = pending.uid == state.memberOperationId,
                anyBusy = state.memberOperationId != null,
                onApprove,
                onDecline,
              )
            }
          }
        }
      }
      Spacer(Modifier.height(24.dp))
    }
  }
}

@Composable
private fun Segmented(mode: String, onMode: (String) -> Unit) {
  val cs = MaterialTheme.colorScheme
  Row(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(cs.surfaceContainer).padding(4.dp),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    SegmentPill("In person · QR", selected = mode == "qr", Modifier.weight(1f)) { onMode("qr") }
    SegmentPill("Share a link", selected = mode == "link", Modifier.weight(1f)) { onMode("link") }
  }
}

@Composable
private fun SegmentPill(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
  val cs = MaterialTheme.colorScheme
  Box(
    modifier.clip(RoundedCornerShape(50)).background(if (selected) cs.primary else Color.Transparent)
      .clickable(onClick = onClick).padding(vertical = 9.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      label, style = MaterialTheme.typography.labelLarge,
      fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
      color = if (selected) cs.onPrimary else cs.onSurfaceVariant,
    )
  }
}

@Composable
private fun QrCard(invite: MintedInvite, now: kotlin.time.Instant) {
  // Live countdown: first paint uses the caller's `now` (deterministic for tests),
  // then recompute from the real clock each second so it self-corrects (no drift).
  var tick by remember(invite.token) { mutableStateOf(now) }
  LaunchedEffect(invite.token) {
    while (true) { kotlinx.coroutines.delay(1000); tick = kotlin.time.Clock.System.now() }
  }
  Column(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Color.White).padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    QrImage(invite.url, Modifier.size(200.dp), dark = Color(0xFF271814), light = Color.White)
    Spacer(Modifier.height(16.dp))
    Text(
      "Expires in ${countdownChip(invite.expiresAt, tick)} · one-time use",
      style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = Color(0xFF5A423C),
    )
  }
  Spacer(Modifier.height(14.dp))
  Text(
    "Have them scan this with their camera, then sign in. Every join waits for your approval before they see anything.",
    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

@Suppress("DEPRECATION")   // LocalClipboardManager: works on all four targets; the 1.11 LocalClipboard text-ClipEntry helper isn't uniform in commonMain yet
@Composable
private fun LinkCard(invite: MintedInvite, now: kotlin.time.Instant) {
  val cs = MaterialTheme.colorScheme
  val clipboard = LocalClipboardManager.current
  Column(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cs.surfaceContainer).padding(18.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(invite.url, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Button(onClick = { clipboard.setText(AnnotatedString(invite.url)) }, modifier = Modifier.fillMaxWidth()) {
      Text("Copy link")
    }
    Text(
      "Expires in ${countdownChip(invite.expiresAt, now)} · shareable",
      style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant,
    )
  }
}

@Composable
private fun MintErrorCard(reason: String, cs: androidx.compose.material3.ColorScheme, onRetry: () -> Unit) {
  val msg = if (reason == "ratelimited") "Too many invites right now — try again in a bit." else "Couldn't create an invite. Try again."
  Column(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cs.surfaceContainer).padding(20.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(msg, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
    Button(onClick = onRetry) { Text("Try again") }
  }
}

@Composable
private fun RoleRow(cs: androidx.compose.material3.ColorScheme) {
  Row(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainer).padding(horizontal = 16.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
      Icon(DayfoldIcons.Verified, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(21.dp))
      Text("Joins as", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
    }
    Text("Adult", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
  }
}

@Composable
private fun OutstandingRow(invite: Invite, now: kotlin.time.Instant, busy: Boolean, onRevoke: (String) -> Unit) {
  val cs = MaterialTheme.colorScheme
  val expiry = countdownLabel(invite.expiresAt, now.toString()) ?: "soon"
  Row(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainer).padding(horizontal = 15.dp, vertical = 13.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(cs.surfaceContainerHigh), contentAlignment = Alignment.Center) {
      Icon(DayfoldIcons.Link, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
    Column(Modifier.weight(1f)) {
      Text(if (invite.mode == "qr") "QR invite" else "Shared link", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
      Text("Expires ${expiry} · ${invite.usedCount} of ${invite.maxUses} used", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
    }
    if (busy) RowBusy() else Text(
      "Revoke", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = cs.error,
      modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onRevoke(invite.id) }.padding(horizontal = 8.dp, vertical = 4.dp)
        .semantics { contentDescription = "Revoke invite" },
    )
  }
}

@Composable
private fun InvitePendingRow(p: PendingMember, busy: Boolean, anyBusy: Boolean, onApprove: (String) -> Unit, onDecline: (String) -> Unit) {
  val cs = MaterialTheme.colorScheme
  Row(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainer).padding(13.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(50)).background(cs.tertiaryContainer), contentAlignment = Alignment.Center) {
      Text((p.displayName ?: "?").take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = cs.onTertiaryContainer)
    }
    Column(Modifier.weight(1f)) {
      Text(p.displayName ?: "Someone", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
      Text("Joined · needs approval", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
    }
    if (busy) {
      RowBusy()
    } else {
      Box(
        Modifier.size(34.dp).clip(RoundedCornerShape(50)).background(cs.surfaceContainerHigh)
          .testTag("decline-${p.uid}").clickable(enabled = !anyBusy) { onDecline(p.uid) }.semantics { contentDescription = "Decline ${p.displayName ?: "request"}" },
        contentAlignment = Alignment.Center,
      ) { Text("✕", color = cs.error, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clearAndSetSemantics {}) }
      Spacer(Modifier.size(7.dp))
      Box(
        Modifier.size(34.dp).clip(RoundedCornerShape(50)).background(cs.primary)
          .testTag("approve-${p.uid}").clickable(enabled = !anyBusy) { onApprove(p.uid) }.semantics { contentDescription = "Approve ${p.displayName ?: "request"}" },
        contentAlignment = Alignment.Center,
      ) { Text("✓", color = cs.onPrimary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clearAndSetSemantics {}) }
    }
  }
}

// The live QR chip: M:SS remaining (the QR TTL is ~15 min). Recompute-from-clock via
// the caller's `now` for a deterministic first paint; the caller ticks it.
private fun countdownChip(expiresAt: String, now: kotlin.time.Instant): String =
  formatCountdown(secondsUntil(expiresAt, now))

private fun secondsUntil(iso: String, now: kotlin.time.Instant): Long =
  runCatching { (kotlin.time.Instant.parse(iso) - now).inWholeSeconds }.getOrDefault(0L)

// M:SS, floored at 0:00 (QR chip only — day-scale rows use DateLabels.countdownLabel).
fun formatCountdown(remainingSeconds: Long): String {
  val s = remainingSeconds.coerceAtLeast(0)
  return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}
