package com.familyai.client

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// AUTH-S5 T5 — the onboarding/identity screens (Dayfold, ADR 0008/0011/0023).
// Pure composables: state in, callbacks out — snapshot-testable in isolation and
// routed by FeedApp. Renders the `signin` / `createfamily` / `familynull` mockups
// (designs/.../Auth-Phone.dc.html). Google + Apple only — phone/OTP deferred
// (ADR 0023). No icon-font dep: the brand mark + provider glyphs are drawn.

// The Dayfold mark — a coral content-card with a turned (folded) corner. Same
// warm coral on both themes (it reads like the app icon). Brand.dc.html §02.
@Composable
fun DayfoldMark(modifier: Modifier = Modifier, size: Int = 74) {
  val radius = (size * 0.31f)
  Box(
    modifier
      .size(size.dp)
      .clip(RoundedCornerShape(radius.dp))
      .background(Brush.linearGradient(listOf(Color(0xFFFF8A6E), Color(0xFFC0381E)))),
  ) {
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
      val fold = this.size.width * 0.46f
      // highlight triangle — the lifted page corner
      drawPath(
        Path().apply {
          moveTo(this@Canvas.size.width - fold, 0f)
          lineTo(this@Canvas.size.width, 0f)
          lineTo(this@Canvas.size.width, fold)
          close()
        },
        color = Color(0xFFFFE2D8),
      )
      // shadow triangle — the fold's underside
      drawPath(
        Path().apply {
          moveTo(this@Canvas.size.width - fold, 0f)
          lineTo(this@Canvas.size.width, fold)
          lineTo(this@Canvas.size.width - fold, fold)
          close()
        },
        color = Color(0x335A1100),
      )
    }
  }
}

// A full-width auth button. `filled` uses the primary brand fill; otherwise a
// theme-derived provider surface (auto-correct in dark).
@Composable
private fun AuthButton(
  text: String,
  container: Color,
  content: Color,
  modifier: Modifier = Modifier,
  border: Color? = null,
  enabled: Boolean = true,
  leading: @Composable (() -> Unit)? = null,
  onClick: () -> Unit = {},
) {
  val cs = MaterialTheme.colorScheme
  Box(
    modifier
      .fillMaxWidth()
      .height(54.dp)
      .clip(RoundedCornerShape(16.dp))
      .background(if (enabled) container else cs.surfaceContainerHigh)
      .then(if (border != null) Modifier.border(1.dp, border, RoundedCornerShape(16.dp)) else Modifier)
      .clickable(enabled = enabled, onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
      if (leading != null) leading()
      Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (enabled) content else cs.onSurfaceVariant,
      )
    }
  }
}

// Google 4-colour ring (sweep gradient) — no logo asset needed.
@Composable
private fun GoogleGlyph() {
  Box(
    Modifier.size(22.dp).clip(RoundedCornerShape(50)).background(
      Brush.sweepGradient(
        listOf(
          Color(0xFFEA4335), Color(0xFFFBBC05), Color(0xFF34A853), Color(0xFF4285F4), Color(0xFFEA4335),
        ),
      ),
    ),
    contentAlignment = Alignment.Center,
  ) {
    Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerLowest))
  }
}

@Composable
private fun AppleGlyph(tint: Color) {
  // A simple filled disc stands in for the Apple mark (no glyph font at M0).
  Box(Modifier.size(20.dp).clip(RoundedCornerShape(50)).background(tint))
}

// ── Splash (cold-start, restoring the session) ──
@Composable
fun SplashScreen() {
  val cs = MaterialTheme.colorScheme
  Box(Modifier.fillMaxSize().background(cs.surface), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
      DayfoldMark(size = 64)
      CircularProgressIndicator(color = cs.primary, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
    }
  }
}

// ── Sign in ──
@Composable
fun SignInScreen(
  busy: Boolean = false,
  error: String? = null,
  onProvider: (String) -> Unit = {},
) {
  val cs = MaterialTheme.colorScheme
  Column(
    Modifier.fillMaxSize().background(cs.surface).padding(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 30.dp),
  ) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
      DayfoldMark(size = 74)
      Spacer(Modifier.height(26.dp))
      Text(
        "DAYFOLD",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = cs.onSurfaceVariant,
      )
      Spacer(Modifier.height(8.dp))
      Text("One calm view of family life.", style = MaterialTheme.typography.displaySmall, color = cs.onSurface)
      Spacer(Modifier.height(14.dp))
      Text(
        "Your day's briefing and every family project, gathered in one place. No feeds to refresh, no notifications to chase.",
        style = MaterialTheme.typography.bodyLarge,
        color = cs.onSurfaceVariant,
      )
    }
    Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
      error?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.error, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
      }
      AuthButton(
        "Continue with Google", container = cs.surfaceContainerLowest, content = cs.onSurface,
        border = cs.outlineVariant, enabled = !busy, leading = { GoogleGlyph() },
        onClick = { onProvider("google") },
      )
      AuthButton(
        "Continue with Apple", container = cs.onSurface, content = cs.surface,
        enabled = !busy, leading = { AppleGlyph(cs.surface) }, onClick = { onProvider("apple") },
      )
      Spacer(Modifier.height(2.dp))
      Text(
        "By continuing you agree to the Terms and Privacy Policy. Adults only.",
        style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

// ── Create family (onboarding) ──
@Composable
fun CreateFamilyScreen(
  busy: Boolean = false,
  error: String? = null,
  initialName: String = "",
  onCreate: (String) -> Unit = {},
  onJoinInvite: () -> Unit = {},
) {
  val cs = MaterialTheme.colorScheme
  var name by remember { mutableStateOf(initialName) }
  Column(
    Modifier.fillMaxSize().background(cs.surface).padding(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 30.dp),
  ) {
    Text("STEP 1 OF 1", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    Text("Name your family", style = MaterialTheme.typography.displaySmall, color = cs.onSurface)
    Spacer(Modifier.height(10.dp))
    Text(
      "This is the shared space everyone you invite will see.",
      style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant,
    )
    Spacer(Modifier.height(26.dp))
    OutlinedTextField(
      value = name, onValueChange = { name = it },
      label = { Text("Family name") },
      singleLine = true,
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
      shape = RoundedCornerShape(16.dp),
      colors = TextFieldDefaults.colors(
        focusedContainerColor = cs.surfaceContainer, unfocusedContainerColor = cs.surfaceContainer,
      ),
      modifier = Modifier.fillMaxWidth(),
    )
    error?.let {
      Spacer(Modifier.height(8.dp))
      Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.error)
    }
    Spacer(Modifier.weight(1f))
    AuthButton(
      if (busy) "Creating…" else "Create family",
      container = cs.primary, content = cs.onPrimary,
      enabled = !busy && name.isNotBlank(), onClick = { onCreate(name.trim()) },
    )
    Spacer(Modifier.height(14.dp))
    // invitee path — someone shared an invite with you
    Box(
      Modifier.fillMaxWidth().clickable(onClick = onJoinInvite),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        "Have an invite? Join a family",
        style = MaterialTheme.typography.labelLarge, color = cs.primary,
      )
    }
  }
}

// ── Family null state (Feed substate: a family with nothing in it yet) ──
@Composable
fun FamilyNullState(modifier: Modifier = Modifier) {
  val cs = MaterialTheme.colorScheme
  Column(
    modifier.fillMaxSize().background(cs.surface).padding(horizontal = 26.dp),
    verticalArrangement = Arrangement.Center,
  ) {
    DayfoldMark(size = 64)
    Spacer(Modifier.height(22.dp))
    Text("Your family space is ready", style = MaterialTheme.typography.displaySmall, color = cs.onSurface)
    Spacer(Modifier.height(10.dp))
    Text(
      "Invite the people you share a home with, or connect a device so Dayfold can start gathering your day.",
      style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant,
    )
    Spacer(Modifier.height(26.dp))
    NullCta(title = "Invite a member", subtitle = "Share a QR or link — you approve each one", container = cs.primaryContainer, onContainer = cs.onPrimaryContainer)
    Spacer(Modifier.height(11.dp))
    NullCta(title = "Connect a device or CLI", subtitle = "Let Claude Code or a script add cards", container = cs.tertiaryContainer, onContainer = cs.onTertiaryContainer)
  }
}

@Composable
private fun NullCta(title: String, subtitle: String, container: Color, onContainer: Color) {
  val cs = MaterialTheme.colorScheme
  Row(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(cs.surfaceContainer).padding(16.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(container), contentAlignment = Alignment.Center) {
      Box(Modifier.size(18.dp).clip(RoundedCornerShape(50)).background(onContainer))
    }
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
      Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
    }
  }
}
